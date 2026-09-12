use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::fmt::Write as _;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::rc::Rc;

use rquickjs::{Array, Ctx, Function, Object, Result as JsResult, Runtime, TypedArray, Value};

use crate::api::error::{wire_error_to_js, PluginErrorCode};
use crate::api::io::blob::{self, BlobHandle, BlobState};
use crate::api::telegram::account::AccountState;
use crate::api::telegram::progress::ProgressReporter;
use crate::api::telegram::rpc::{format_exception, pump_jobs, PendingSettle};
use crate::api::tl::proxy::{js_value_to_wire, TlViews, ViewLife};
use crate::sandbox::grants::{GrantHost, MATCH_EXACT};
use crate::sandbox::registry::RequestIds;

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/writes.qbc"));

pub trait WritesHost {
  fn account_write(&self, account_id: i32, request_id: i64, op: i32, arg: &str, values: &[String]) -> Option<String>;

  fn message_file(&self, account_id: i32, value: &str) -> String;
}

const OP_SEND_MESSAGE: i32 = 0;
const OP_SEND_MEDIA: i32 = 1;
const OP_SEND_MULTI_MEDIA: i32 = 2;
const OP_EDIT_MESSAGE: i32 = 3;
const OP_DELETE_MESSAGES: i32 = 4;
const OP_FORWARD_MESSAGES: i32 = 5;
const OP_SET_REACTION: i32 = 6;
const OP_READ_HISTORY: i32 = 7;
const OP_SEND_TYPING: i32 = 8;
const OP_SET_DRAFT: i32 = 9;
const OP_DOWNLOAD_MEDIA: i32 = 10;
const OP_DOWNLOAD_MEDIA_TO_FILE: i32 = 11;
const OP_UPLOAD_FILE: i32 = 12;
const OP_SET_SEND_MEDIA: i32 = 13;

pub const TRANSFER_LIMIT_BYTES: u64 = 256 * 1024 * 1024;

const STAGE_CHUNK_BYTES: u64 = 1024 * 1024;

fn grant_of(op: i32) -> Option<(&'static str, &'static str)> {
  Some(match op {
    OP_SEND_MESSAGE | OP_SEND_MEDIA | OP_SEND_MULTI_MEDIA | OP_UPLOAD_FILE | OP_SET_SEND_MEDIA => ("account.write", "send"),
    OP_EDIT_MESSAGE => ("account.write", "edit"),
    OP_DELETE_MESSAGES => ("account.write", "delete"),
    OP_FORWARD_MESSAGES => ("account.write", "forward"),
    OP_SET_REACTION => ("account.write", "react"),
    OP_READ_HISTORY => ("account.write", "read"),
    OP_SEND_TYPING => ("account.write", "typing"),
    OP_SET_DRAFT => ("account.write", "draft"),
    OP_DOWNLOAD_MEDIA | OP_DOWNLOAD_MEDIA_TO_FILE => ("account.read", "messages"),
    _ => return None,
  })
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Shape {
  Value,
  List,
  File,
}

fn shape_of(op: i32) -> Shape {
  match op {
    OP_SEND_MULTI_MEDIA | OP_FORWARD_MESSAGES => Shape::List,
    OP_DOWNLOAD_MEDIA => Shape::File,
    _ => Shape::Value,
  }
}

pub struct WritesState {
  host: Rc<dyn WritesHost>,
  grants: Rc<dyn GrantHost>,
  views: Rc<TlViews>,
  blobs: Rc<BlobState>,
  stage_dir: PathBuf,
  log: crate::Log,
  transfer_limit: u64,
  next_request_id: RequestIds,
  next_staged: Cell<u64>,
  pending: RefCell<HashMap<i64, PendingWrite>>,
}

struct PendingWrite {
  settle: PendingSettle,
  shape: Shape,
  progress: Option<Rc<ProgressReporter>>,
  last_total: Cell<i64>,
  staged: Vec<PathBuf>,
}

impl WritesState {
  fn check_write_grant(&self, ctx: &Ctx<'_>, op: i32) -> JsResult<()> {
    let Some((name, scope)) = grant_of(op) else {
      return PluginErrorCode::InvalidArgument.throw(ctx, "unknown account write");
    };
    self.grants.check_grant(ctx, name, Some(scope), MATCH_EXACT)
  }

  fn check_path_grant(&self, ctx: &Ctx<'_>, path: &str) -> JsResult<()> {
    if Path::new(path).is_absolute() {
      return self.grants.check_grant(ctx, "unsafe.fs", None, MATCH_EXACT);
    }
    self.grants.check_grant(ctx, "fs", None, MATCH_EXACT)?;
    PluginErrorCode::Unsupported.throw(
    ctx,
    "a relative path needs the plugin's scoped directory, which arrives with inu.fs; pass a Blob, bytes, or an absolute path under unsafe.fs",
  )
  }
}

struct Staged {
  wire: String,
  path: Option<PathBuf>,
}

impl WritesState {
  fn stage_value<'js>(&self, ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<Staged> {
    if let Some(object) = value.as_object() {
      if let Some(path) = object.get::<_, Option<String>>("path")? {
        if object.get::<_, Value>("_")?.is_undefined() {
          self.check_path_grant(ctx, &path)?;
          return Ok(Staged {
            wire: file_wire(&path, "", ""),
            path: None,
          });
        }
      }
    }
    if rquickjs::Class::<BlobHandle>::from_value(value).is_ok() {
      let Some(exported) = self.blobs.export_for_host(value) else {
        return PluginErrorCode::HandleExpired.throw(ctx, "this blob has been disposed");
      };
      return self.stage_blob(ctx, value, &exported);
    }
    if let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) {
      let Some(bytes) = typed.as_bytes() else {
        return PluginErrorCode::InvalidArgument.throw(ctx, "this Uint8Array is detached");
      };
      self.check_transfer_limit(ctx, bytes.len() as u64)?;
      let path = self.write_staged(ctx, |file| file.write_all(bytes))?;
      return Ok(Staged {
        wire: file_wire(&path.to_string_lossy(), "", ""),
        path: Some(path),
      });
    }
    Ok(Staged {
      wire: js_value_to_wire(ctx, value.clone())?,
      path: None,
    })
  }

  fn check_transfer_limit(&self, ctx: &Ctx<'_>, len: u64) -> JsResult<()> {
    if len <= self.transfer_limit {
      return Ok(());
    }
    PluginErrorCode::QuotaExceeded(
      i64::try_from(len).unwrap_or(i64::MAX),
      i64::try_from(self.transfer_limit).unwrap_or(i64::MAX),
    )
    .throw(
      ctx,
      &format!("this transfer is {len} bytes; at most {} may be staged at once", self.transfer_limit,),
    )
  }

  fn stage_blob<'js>(&self, ctx: &Ctx<'js>, value: &Value<'js>, exported: &str) -> JsResult<Staged> {
    let Some(id) = parse_export_id(exported) else {
      return PluginErrorCode::Internal.throw(ctx, "this blob could not be handed over");
    };
    let Some(export) = self.blobs.resolve_export(id) else {
      return PluginErrorCode::HandleExpired.throw(ctx, "this blob has been disposed");
    };
    let len = export.len();
    self.check_transfer_limit(ctx, len)?;
    let object = value.as_object().cloned();
    let name = object.as_ref().and_then(|o| o.get::<_, Option<String>>("name").ok().flatten()).unwrap_or_default();
    let mime = object.as_ref().and_then(|o| o.get::<_, Option<String>>("type").ok().flatten()).unwrap_or_default();

    let path = self.write_staged(ctx, |file| {
      let mut at = 0u64;
      while at < len {
        let take = STAGE_CHUNK_BYTES.min(len - at);
        let chunk = export
          .read(at, take)
          .map_err(|_| std::io::Error::other("this blob's content is no longer readable"))?;
        file.write_all(&chunk)?;
        at += take;
      }
      Ok(())
    })?;
    Ok(Staged {
      wire: file_wire(&path.to_string_lossy(), &name, &mime),
      path: Some(path),
    })
  }
}

fn parse_export_id(exported: &str) -> Option<i64> {
  exported.strip_prefix('B')?.split(':').next()?.parse().ok()
}

impl WritesState {
  fn write_staged<'js>(
    &self,
    ctx: &Ctx<'js>,
    fill: impl FnOnce(&mut fs::File) -> std::io::Result<()>,
  ) -> JsResult<PathBuf> {
    if self.stage_dir.as_os_str().is_empty() {
      return PluginErrorCode::Internal.throw(ctx, "this engine has no directory to stage a transfer in");
    }
    let n = self.next_staged.get() + 1;
    self.next_staged.set(n);
    let path = self.stage_dir.join(format!("transfer-{n}.bin"));
    let written = fs::create_dir_all(&self.stage_dir)
      .and_then(|()| fs::File::create(&path))
      .and_then(|mut file| fill(&mut file).and_then(|()| file.sync_all()));
    if let Err(e) = written {
      let _ = fs::remove_file(&path);
      return PluginErrorCode::Internal.throw(ctx, &format!("staging this transfer failed: {e}"));
    }
    Ok(path)
  }
}

fn file_wire(path: &str, name: &str, mime: &str) -> String {
  format!("F{{\"path\":{},\"name\":{},\"mime\":{}}}", json_string(path), json_string(name), json_string(mime),)
}

pub(crate) fn json_string(value: &str) -> String {
  let mut out = String::with_capacity(value.len() + 2);
  out.push('"');
  for c in value.chars() {
    match c {
      '"' => out.push_str("\\\""),
      '\\' => out.push_str("\\\\"),
      '\n' => out.push_str("\\n"),
      '\r' => out.push_str("\\r"),
      '\t' => out.push_str("\\t"),
      c if (c as u32) < 0x20 => {
        write!(out, "\\u{:04x}", c as u32).expect("writing to a String cannot fail");
      }
      c => out.push(c),
    }
  }
  out.push('"');
  out
}

impl WritesState {
  fn js_write<'js>(
    &self,
    ctx: &Ctx<'js>,
    slot: i32,
    op: i32,
    arg: &str,
    values: Array<'js>,
    on_progress: Option<Function<'js>>,
  ) -> JsResult<Value<'js>> {
    self.check_write_grant(ctx, op)?;

    let mut wires = Vec::new();
    let mut staged = Vec::new();
    let outcome = (|| -> JsResult<()> {
      for value in crate::utils::arguments::array_values(ctx, &values, "account write")? {
        let one = if matches!(op, OP_SEND_MEDIA | OP_SEND_MULTI_MEDIA | OP_UPLOAD_FILE | OP_SET_SEND_MEDIA) {
          self.stage_value(ctx, &value)?
        } else {
          Staged {
            wire: js_value_to_wire(ctx, value)?,
            path: None,
          }
        };
        if let Some(path) = one.path {
          staged.push(path);
        }
        wires.push(one.wire);
      }
      Ok(())
    })();
    if let Err(e) = outcome {
      for path in &staged {
        let _ = fs::remove_file(path);
      }
      return Err(e);
    }

    let request_id = self.next_request_id.alloc();
    let (promise, settle) = PendingSettle::new(ctx)?;
    let progress = on_progress.map(|callback| ProgressReporter::new(ctx, callback, self.log.clone()));
    self.pending.borrow_mut().insert(
      request_id,
      PendingWrite {
        settle,
        shape: shape_of(op),
        progress,
        last_total: Cell::new(0),
        staged,
      },
    );

    if let Some(err) = self.host.account_write(slot, request_id, op, arg, &wires) {
      if let Some(pending) = self.take_pending(request_id) {
        let value = crate::api::error::host_error_to_js(ctx, &err)?;
        if let Some(progress) = pending.progress.as_ref() {
          progress.release(ctx);
        }
        pending.settle.reject_with_value(ctx, value)?;
      }
    }
    Ok(promise.into_value())
  }

  fn take_pending(&self, request_id: i64) -> Option<PendingWrite> {
    let pending = self.pending.borrow_mut().remove(&request_id)?;
    for path in &pending.staged {
      let _ = fs::remove_file(path);
    }
    Some(pending)
  }

  fn decode_list<'js>(&self, ctx: &Ctx<'js>, wire: &str) -> JsResult<Array<'js>> {
    let array = Array::new(ctx.clone())?;
    if wire.is_empty() {
      return Ok(array);
    }
    for (index, element) in wire.split("\n").enumerate() {
      array.set(index, self.views.wire_to_js_value(ctx, element, ViewLife::Plugin)?)?;
    }
    Ok(array)
  }

  fn decode_result<'js>(&self, ctx: &Ctx<'js>, shape: Shape, wire: &str) -> JsResult<Value<'js>> {
    match shape {
      Shape::Value => self.views.wire_to_js_value(ctx, wire, ViewLife::Plugin),
      Shape::List => Ok(self.decode_list(ctx, wire)?.into_value()),
      Shape::File => {
        let described = self.views.wire_to_js_value(ctx, wire, ViewLife::Plugin)?;
        let Some(object) = described.as_object() else {
          return PluginErrorCode::Internal.throw(ctx, "the host described a download it did not make");
        };
        let path: String = object.get("path")?;
        let size: f64 = object.get::<_, Option<f64>>("size")?.unwrap_or(0.0);
        let mime: String = object.get::<_, Option<String>>("mime")?.unwrap_or_default();
        let name: Option<String> = object.get::<_, Option<String>>("name")?.filter(|n| !n.is_empty());
        let mtime: f64 = object.get::<_, Option<f64>>("mtime")?.unwrap_or(0.0);
        blob::mint_app_file(ctx, Path::new(&path), size.max(0.0) as u64, &mime, name.as_deref(), mtime as i64)
      }
    }
  }
}

pub struct WritesDeps {
  pub host: Rc<dyn WritesHost>,
  pub grants: Rc<dyn GrantHost>,
  pub views: Rc<TlViews>,
  pub blobs: Rc<BlobState>,
  pub stage_dir: PathBuf,
  pub log: crate::Log,
}

pub fn install_writes<'js>(
  ctx: &Ctx<'js>,
  deps: WritesDeps,
  shared: &Object<'js>,
  accounts: &Rc<AccountState>,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<WritesState>> {
  install_writes_with_limit(ctx, deps, shared, accounts, globals, TRANSFER_LIMIT_BYTES)
}

pub(crate) fn install_writes_with_limit<'js>(
  ctx: &Ctx<'js>,
  deps: WritesDeps,
  shared: &Object<'js>,
  accounts: &Rc<AccountState>,
  globals: &crate::api::Globals<'js>,
  transfer_limit: u64,
) -> JsResult<Rc<WritesState>> {
  let state = Rc::new(WritesState {
    host: deps.host,
    grants: deps.grants,
    views: deps.views,
    blobs: deps.blobs,
    stage_dir: deps.stage_dir,
    log: deps.log,
    transfer_limit,
    next_request_id: RequestIds::default(),
    next_staged: Cell::new(0),
    pending: RefCell::new(HashMap::new()),
  });

  let natives = Object::new(ctx.clone())?;
  {
    let state = state.clone();
    natives.set(
      "write",
      Function::new(
        ctx.clone(),
        move |ctx: Ctx<'js>,
              slot: i32,
              op: i32,
              arg: String,
              values: Array<'js>,
              on_progress: Option<Function<'js>>| { state.js_write(&ctx, slot, op, &arg, values, on_progress) },
      )?,
    )?;
  }
  {
    let state = state.clone();
    natives.set(
      "messageFile",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, slot: i32, message: Value<'js>| {
        state.grants.check_grant(&ctx, "account.read", Some("messages"), MATCH_EXACT)?;
        let wire = js_value_to_wire(&ctx, message)?;
        let answer = state.host.message_file(slot, &wire);
        state.views.wire_to_js_value(&ctx, &answer, ViewLife::Plugin)
      })?,
    )?;
  }

  let message = globals.get_message(ctx)?;
  let plugin_error = globals.plugin_error.clone();

  let reads = accounts.take_prototype(ctx);
  let ops = Object::new(ctx.clone())?;
  ops.set("sendMessage", OP_SEND_MESSAGE)?;
  ops.set("sendMedia", OP_SEND_MEDIA)?;
  ops.set("sendMultiMedia", OP_SEND_MULTI_MEDIA)?;
  ops.set("editMessage", OP_EDIT_MESSAGE)?;
  ops.set("deleteMessages", OP_DELETE_MESSAGES)?;
  ops.set("forwardMessages", OP_FORWARD_MESSAGES)?;
  ops.set("setReaction", OP_SET_REACTION)?;
  ops.set("readHistory", OP_READ_HISTORY)?;
  ops.set("sendTyping", OP_SEND_TYPING)?;
  ops.set("setDraft", OP_SET_DRAFT)?;
  ops.set("downloadMedia", OP_DOWNLOAD_MEDIA)?;
  ops.set("downloadMediaToFile", OP_DOWNLOAD_MEDIA_TO_FILE)?;
  ops.set("uploadFile", OP_UPLOAD_FILE)?;
  ops.set("setSendMedia", OP_SET_SEND_MEDIA)?;

  let factory = crate::utils::prelude::load(ctx, PRELUDE)?;
  let prototype: Object = factory.call((natives, shared.clone(), message, plugin_error, reads, ops))?;
  accounts.set_prototype(ctx, &prototype);

  Ok(state)
}

impl WritesState {
  pub fn resolve_write(self: &Rc<Self>, rt: &Runtime, context: &rquickjs::Context, request_id: i64, result_wire: &str) {
    let state = self;
    context.with(|ctx| {
      let Some(pending) = state.take_pending(request_id) else {
        return;
      };
      if let Some(built) = wire_error_to_js(&ctx, result_wire) {
        if let Some(progress) = pending.progress.as_ref() {
          progress.abandon(&ctx);
        }
        match built {
          Ok(value) => {
            if pending.settle.reject_with_value(&ctx, value).is_err() {
              (state.log)(&format!("write({request_id}) reject failed: {}", format_exception(&ctx)));
            }
          }
          Err(e) => {
            pending.settle.release(&ctx);
            (state.log)(&format!("write({request_id}) error decode failed: {e:?}"));
          }
        }
        return;
      }
      if let Some(progress) = pending.progress.as_ref() {
        let total = pending.last_total.get();
        if total > 0 {
          progress.finish(&ctx, total, total);
        } else {
          progress.abandon(&ctx);
        }
      }
      match state.decode_result(&ctx, pending.shape, result_wire) {
        Ok(value) => {
          if pending.settle.resolve_with(&ctx, value).is_err() {
            (state.log)(&format!("write({request_id}) resolve failed: {}", format_exception(&ctx)));
          }
        }
        Err(_) => {
          pending.settle.release(&ctx);
          (state.log)(&format!("write({request_id}) bad result wire: {}", format_exception(&ctx)));
        }
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  pub fn report_progress(
    self: &Rc<Self>,
    rt: &Runtime,
    context: &rquickjs::Context,
    request_id: i64,
    loaded: i64,
    total: i64,
  ) {
    let state = self;
    context.with(|ctx| {
      let progress = {
        let table = state.pending.borrow();
        let Some(pending) = table.get(&request_id) else {
          return;
        };
        pending.last_total.set(total);
        pending.progress.clone()
      };
      let Some(progress) = progress else { return };
      progress.report(&ctx, loaded, total);
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  pub fn dispose(self: &Rc<Self>, context: &rquickjs::Context) {
    let state = self;
    context.with(|ctx| {
      for (_, pending) in state.pending.borrow_mut().drain() {
        if let Some(progress) = pending.progress.as_ref() {
          progress.release(&ctx);
        }
        pending.settle.release(&ctx);
        for path in &pending.staged {
          let _ = fs::remove_file(path);
        }
      }
    });
  }
}

#[cfg(test)]
#[path = "writes_tests.rs"]
mod tests;

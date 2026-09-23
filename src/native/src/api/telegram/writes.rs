use crate::runtime::Dispose;
use std::cell::Cell;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::rc::Rc;

use rquickjs::{Array, Ctx, Function, Object, Result as JsResult, Runtime, TypedArray, Value};

use crate::api::error::PluginErrorCode;
use crate::api::io::blob::{self, BlobHandle};
use crate::api::io::staging::{SourceStager, StagedFile};
use crate::api::telegram::account::AccountState;
use crate::api::telegram::progress::ProgressReporter;
use crate::api::tl::proxy::{js_value_to_wire, TlViews, ViewLife};
use crate::runtime::{pump_jobs, Parked, PendingTable};
use crate::sandbox::grants::{GrantHost, MATCH_EXACT};
use crate::utils::qjs::{qjs_load_prelude, qjs_read_typed_bytes};

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

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/writes.qbc"));

pub trait WritesHost {
  fn account_write(&self, account_id: i32, request_id: i64, op: i32, arg: &str, values: &[String]) -> Option<String>;

  fn message_file(&self, account_id: i32, value: &str) -> String;
}

pub const TRANSFER_LIMIT_BYTES: u64 = 256 * 1024 * 1024;

fn get_op_grant(op: i32) -> Option<(&'static str, &'static str)> {
  Some(match op {
    OP_SEND_MESSAGE | OP_SEND_MEDIA | OP_SEND_MULTI_MEDIA | OP_UPLOAD_FILE | OP_SET_SEND_MEDIA => {
      ("account.write", "send")
    }
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

fn get_op_shape(op: i32) -> Shape {
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
  sources: SourceStager,
  log: crate::Log,
  pending: PendingTable<PendingWrite>,
}

struct PendingWrite {
  shape: Shape,
  progress: Option<Rc<ProgressReporter>>,
  last_total: Cell<i64>,
  _staged: Vec<StagedFile>,
}

impl Parked for PendingWrite {
  fn reject(self, ctx: &Ctx<'_>) {
    if let Some(progress) = self.progress {
      progress.abandon(ctx);
    }
  }

  fn release(self, ctx: &Ctx<'_>) {
    if let Some(progress) = self.progress {
      progress.release(ctx);
    }
  }
}

impl WritesState {
  fn check_write_grant(&self, ctx: &Ctx<'_>, op: i32) -> JsResult<()> {
    let Some((name, scope)) = get_op_grant(op) else {
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
    if rquickjs::Class::<BlobHandle>::from_value(value).is_ok() {
      let Some(export) = crate::api::io::blob::export_blob(value) else {
        return PluginErrorCode::HandleExpired.throw(ctx, "this blob has been disposed");
      };
      return self.stage_blob(ctx, value, &export);
    }
    if let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) {
      // an error thrown past the limit is built after the last use of the bytes
      let staged = qjs_read_typed_bytes(&typed, |bytes| {
        self.sources.check_limit(ctx, bytes.len() as u64)?;
        self.sources.write(ctx, |file| file.write_all(bytes))
      });
      let Some(staged) = staged else {
        return PluginErrorCode::InvalidArgument.throw(ctx, "this Uint8Array is detached");
      };
      let path = staged?;
      return Ok(Staged {
        wire: file_wire(ctx, &path.to_string_lossy(), "", "")?,
        path: Some(path),
      });
    }
    if let Some(object) = value.as_object() {
      if object.get::<_, Value>("_")?.is_undefined() {
        if let Some(path) = object.get::<_, Option<String>>("path")? {
          self.check_path_grant(ctx, &path)?;
          return Ok(Staged {
            wire: file_wire(ctx, &path, "", "")?,
            path: None,
          });
        }
      }
    }
    Ok(Staged {
      wire: js_value_to_wire(ctx, value.clone())?,
      path: None,
    })
  }

  fn stage_blob<'js>(&self, ctx: &Ctx<'js>, value: &Value<'js>, export: &blob::BlobExport) -> JsResult<Staged> {
    self.sources.check_limit(ctx, export.len())?;
    let object = value.as_object().cloned();
    let name = object.as_ref().and_then(|o| o.get::<_, Option<String>>("name").ok().flatten()).unwrap_or_default();
    let mime = object.as_ref().and_then(|o| o.get::<_, Option<String>>("type").ok().flatten()).unwrap_or_default();

    let path = self.sources.write(ctx, |file| {
      export.write_to(file).map_err(|fault| std::io::Error::other(fault.message().to_string()))
    })?;
    Ok(Staged {
      wire: file_wire(ctx, &path.to_string_lossy(), &name, &mime)?,
      path: Some(path),
    })
  }
}

fn file_wire(ctx: &Ctx<'_>, path: &str, name: &str, mime: &str) -> JsResult<String> {
  let described = Object::new_proto(ctx.clone(), None)?;
  described.set("path", path)?;
  described.set("name", name)?;
  described.set("mime", mime)?;
  match ctx.json_stringify(described)? {
    Some(json) => Ok(format!("F{}", json.to_string()?)),
    None => PluginErrorCode::Internal.throw(ctx, "this file could not be described to the app"),
  }
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
          staged.push(StagedFile(path));
        }
        wires.push(one.wire);
      }
      Ok(())
    })();
    outcome?;

    let parked = PendingWrite {
      shape: get_op_shape(op),
      progress: on_progress.map(|callback| ProgressReporter::new(ctx, callback, self.log.clone())),
      last_total: Cell::new(0),
      _staged: staged,
    };
    let promise = self
      .pending
      .park(ctx, parked, |request_id| self.host.account_write(slot, request_id, op, arg, &wires))?;
    Ok(promise.into_value())
  }

  fn decode_result<'js>(&self, ctx: &Ctx<'js>, shape: Shape, wire: &str) -> JsResult<Value<'js>> {
    match shape {
      Shape::Value => self.views.wire_to_js_value(ctx, wire, ViewLife::Plugin),
      Shape::List => Ok(self.views.wire_to_js_list(ctx, wire, ViewLife::Plugin)?.into_value()),
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
    sources: SourceStager::new(deps.stage_dir, "transfer", transfer_limit, "a transfer"),
    log: deps.log,
    pending: PendingTable::default(),
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
  set_fn!(natives, "messageFile", ctx, state, move |ctx: Ctx<'js>, slot: i32, message: Value<'js>| {
    state.grants.check_grant(&ctx, "account.read", Some("messages"), MATCH_EXACT)?;
    let wire = js_value_to_wire(&ctx, message)?;
    let answer = state.host.message_file(slot, &wire);
    state.views.wire_to_js_value(&ctx, &answer, ViewLife::Plugin)
  });

  let message = globals.get_message(ctx)?;
  let plugin_error = globals.plugin_error.clone();

  let reads = accounts.take_prototype(ctx);
  let ops = Object::new(ctx.clone())?;
  for (name, op) in [
    ("sendMessage", OP_SEND_MESSAGE),
    ("sendMedia", OP_SEND_MEDIA),
    ("sendMultiMedia", OP_SEND_MULTI_MEDIA),
    ("editMessage", OP_EDIT_MESSAGE),
    ("deleteMessages", OP_DELETE_MESSAGES),
    ("forwardMessages", OP_FORWARD_MESSAGES),
    ("setReaction", OP_SET_REACTION),
    ("readHistory", OP_READ_HISTORY),
    ("sendTyping", OP_SEND_TYPING),
    ("setDraft", OP_SET_DRAFT),
    ("downloadMedia", OP_DOWNLOAD_MEDIA),
    ("downloadMediaToFile", OP_DOWNLOAD_MEDIA_TO_FILE),
    ("uploadFile", OP_UPLOAD_FILE),
    ("setSendMedia", OP_SET_SEND_MEDIA),
  ] {
    ops.set(name, op)?;
  }

  let factory = qjs_load_prelude(ctx, PRELUDE)?;
  let prototype: Object = factory.call((natives, shared.clone(), message, plugin_error, reads, ops))?;
  accounts.set_prototype(ctx, &prototype);

  Ok(state)
}

impl WritesState {
  pub fn settle(self: &Rc<Self>, rt: &Runtime, context: &rquickjs::Context, request_id: i64, result_wire: &str) {
    self
      .pending
      .settle_and_pump(rt, context, &self.log, "write", request_id, result_wire, |ctx, pending, wire| {
        if let Some(progress) = pending.progress.take() {
          let total = pending.last_total.get();
          if total > 0 {
            progress.finish(ctx, total, total);
          } else {
            progress.abandon(ctx);
          }
        }
        self.decode_result(ctx, pending.shape, wire)
      });
  }

  pub fn report_progress(
    self: &Rc<Self>,
    rt: &Runtime,
    context: &rquickjs::Context,
    request_id: i64,
    loaded: i64,
    total: i64,
  ) {
    context.with(|ctx| {
      let progress = self.pending.with_parked(request_id, |pending| {
        pending.last_total.set(total);
        pending.progress.clone()
      });
      if let Some(Some(progress)) = progress {
        progress.report(&ctx, loaded, total);
      }
    });
    pump_jobs(rt, context, self.log.as_ref());
  }
}

impl Dispose for WritesState {
  fn dispose(&self, context: &rquickjs::Context) {
    context.with(|ctx| self.pending.dispose(&ctx));
  }
}

#[cfg(test)]
#[path = "writes_tests.rs"]
mod tests;

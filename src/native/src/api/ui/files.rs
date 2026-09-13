use std::cell::RefCell;
use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::rc::Rc;

use rquickjs::function::Opt;
use rquickjs::{Array, Ctx, Exception, Function, Object, Result as JsResult, Runtime, Value};

use crate::api::error::{format_exception, PluginErrorCode};
use crate::api::io::blob::{mint_owned_file, BlobState, BUILD_LIMIT_BYTES};
use crate::api::io::fs::FsState;
use crate::api::io::staging::{SourceStager, StagedSource};
use crate::api::ui::{OP_PICK_FILE, OP_SAVE_FILE};
use crate::runtime::{pump_jobs, PendingSettle};
use crate::sandbox::registry::RequestIds;
use crate::utils::arguments::opt_bool;

/// at most this many types may be named in `accept`, a picker offering more being a picker offering
/// nothing in particular
const MAX_ACCEPT_TYPES: usize = 32;

pub trait FilesHost {
  /// both answer through [`FilesState::resolve`]; the return is the refusal of the ask itself
  fn ui_files(&self, op: i32, request_id: i64, options_json: &str) -> Option<String>;
}

enum Pending {
  /// a pick, and whether the caller asked for more than one file
  Pick { multiple: bool },
  /// a save, and the file staged for the host to copy out of
  Save { _staged: Option<StagedFile> },
}

struct StagedFile(PathBuf);

impl Drop for StagedFile {
  fn drop(&mut self) {
    let _ = fs::remove_file(&self.0);
  }
}

pub struct FilesState {
  host: Rc<dyn FilesHost>,
  blobs: Rc<BlobState>,
  sources: SourceStager,
  log: crate::Log,
  next_id: RequestIds,
  pending: RefCell<HashMap<i64, (Pending, PendingSettle)>>,
}

pub fn install_files<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn FilesHost>,
  blobs: Rc<BlobState>,
  stage_dir: PathBuf,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<FilesState>> {
  let state = Rc::new(FilesState {
    host,
    blobs: blobs.clone(),
    sources: SourceStager::new(blobs, stage_dir, "save", BUILD_LIMIT_BYTES, "inu.ui.saveFile"),
    log,
    next_id: RequestIds::default(),
    pending: RefCell::new(HashMap::new()),
  });

  let ui: Object = match globals.inu.get::<_, Object>("ui") {
    Ok(o) => o,
    Err(_) => {
      let o = Object::new(ctx.clone())?;
      globals.inu.set("ui", o.clone())?;
      o
    }
  };

  let owned = state.clone();
  ui.set(
    "pickFile",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, options: Opt<Value<'js>>| -> JsResult<Value<'js>> {
      owned.pick(&ctx, options)
    })?,
  )?;

  let owned = state.clone();
  ui.set(
    "saveFile",
    Function::new(
      ctx.clone(),
      move |ctx: Ctx<'js>, content: Opt<Value<'js>>, options: Opt<Value<'js>>| -> JsResult<Value<'js>> {
        let content = content.0.unwrap_or_else(|| Value::new_undefined(ctx.clone()));
        owned.save(&ctx, &content, options)
      },
    )?,
  )?;

  Ok(state)
}

impl FilesState {
  pub fn attach_fs(self: &Rc<Self>, fs: Rc<FsState>) {
    self.sources.attach_fs(fs);
  }

  fn pick<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, options: Opt<Value<'js>>) -> JsResult<Value<'js>> {
    let state = self;
    let mut multiple = false;
    let out = Object::new(ctx.clone())?;
    if let Some(options) = options.0.as_ref().and_then(|v| v.as_object()) {
      multiple = opt_bool(ctx, options, "pickFile", "multiple")?;
      if let Some(accept) = options.get::<_, Option<Value>>("accept")? {
        let array =
          accept.as_array().ok_or_else(|| Exception::throw_type(ctx, "pickFile: 'accept' must be an array"))?;
        let types = crate::utils::arguments::array_values(ctx, array, "pickFile: 'accept'")?;
        if types.len() > MAX_ACCEPT_TYPES {
          return PluginErrorCode::InvalidArgument
            .throw(ctx, &format!("pickFile: at most {MAX_ACCEPT_TYPES} types may be accepted"));
        }
        let wanted = Array::new(ctx.clone())?;
        for (index, value) in types.into_iter().enumerate() {
          let Some(text) = value.as_string() else {
            return PluginErrorCode::InvalidArgument.throw(ctx, "pickFile: 'accept' takes media types as strings");
          };
          wanted.set(index, text.to_string()?)?;
        }
        out.set("accept", wanted)?;
      }
    }
    out.set("multiple", multiple)?;
    state.start(ctx, OP_PICK_FILE, out, Pending::Pick { multiple })
  }

  fn save<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    content: &Value<'js>,
    options: Opt<Value<'js>>,
  ) -> JsResult<Value<'js>> {
    let state = self;
    let out = Object::new(ctx.clone())?;
    if let Some(options) = options.0.as_ref().and_then(|v| v.as_object()) {
      for name in ["fileName", "type"] {
        if let Some(value) = options.get::<_, Option<String>>(name)? {
          out.set(name, value)?;
        }
      }
    }
    let StagedSource { path, owned } = state.sources.stage(ctx, content)?;
    out.set("path", path.to_string_lossy().to_string())?;
    state.start(ctx, OP_SAVE_FILE, out, Pending::Save { _staged: owned.then(|| StagedFile(path)) })
  }

  fn start<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, op: i32, options: Object<'js>, kind: Pending) -> JsResult<Value<'js>> {
    let state = self;
    let json = ctx
      .json_stringify(options)?
      .map(|s| s.to_string())
      .transpose()?
      .ok_or_else(|| Exception::throw_message(ctx, "ui: serialization failed"))?;
    let request_id = state.next_id.alloc();
    let (promise, settle) = PendingSettle::new(ctx)?;
    state.pending.borrow_mut().insert(request_id, (kind, settle));
    if let Some(err) = state.host.ui_files(op, request_id, &json) {
      if let Some((_, settle)) = state.pending.borrow_mut().remove(&request_id) {
        settle.reject_with(ctx, &err)?;
      }
    }
    Ok(promise.into_value())
  }

  /// The one way either request answers: `answer` is what the host made of it - the files a pick
  /// chose, or whether a save happened - and `error` an error wire that replaces it.
  pub fn resolve(
    self: &Rc<Self>,
    rt: &Runtime,
    context: &rquickjs::Context,
    request_id: i64,
    answer: &str,
    error: Option<&str>,
  ) {
    let state = self;
    context.with(|ctx| {
      let Some((kind, settle)) = state.pending.borrow_mut().remove(&request_id) else {
        return;
      };
      if let Some(error) = error {
        if settle.reject_with(&ctx, error).is_err() {
          (state.log)(&format!("ui: files({request_id}) reject failed: {}", format_exception(&ctx)));
        }
        return;
      }
      let value = match kind {
        Pending::Pick { multiple } => state.picked(&ctx, answer, multiple),
        Pending::Save { .. } => Ok(Value::new_bool(ctx.clone(), answer == "1")),
      };
      match value {
        Ok(value) => {
          if settle.resolve_with(&ctx, value).is_err() {
            (state.log)(&format!("ui: files({request_id}) resolve failed: {}", format_exception(&ctx)));
          }
        }
        // an answer this cannot read is still an answer: a promise left hanging is worse than a
        // rejection, and what it threw reading it says more than anything made up here would
        Err(rquickjs::Error::Exception) => {
          let thrown = ctx.catch();
          if settle.reject_with_value(&ctx, thrown).is_err() {
            (state.log)(&format!("ui: files({request_id}) reject failed: {}", format_exception(&ctx)));
          }
        }
        Err(e) => {
          (state.log)(&format!("ui: files({request_id}) answer unreadable: {e:?}"));
          match crate::api::error::make_plugin_error(
            &ctx,
            "internal",
            "the picker's answer could not be read",
            None,
            None,
            None,
          ) {
            Ok(value) => {
              if settle.reject_with_value(&ctx, value).is_err() {
                (state.log)(&format!("ui: files({request_id}) reject failed: {}", format_exception(&ctx)));
              }
            }
            Err(_) => settle.release(&ctx),
          }
        }
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  /// a plugin torn down mid-picker leaves the dialog on screen and nothing to answer it
  pub fn dispose(self: &Rc<Self>, context: &rquickjs::Context) {
    let state = self;
    context.with(|ctx| {
      for (_, (_, settle)) in state.pending.borrow_mut().drain() {
        settle.release(&ctx);
      }
    });
  }

  /// One `File` per copy the host made, owning it the way a spilled blob owns its file: the content
  /// counts against this plugin's spill budget and the copy is deleted when the handle is. Nothing
  /// else on the device is reachable through it, the picker's permission being the user's one-shot.
  fn picked<'js>(&self, ctx: &Ctx<'js>, answer: &str, multiple: bool) -> JsResult<Value<'js>> {
    let parsed = ctx.json_parse(answer)?;
    let array = parsed.as_array().ok_or_else(|| Exception::throw_message(ctx, "pickFile: malformed host answer"))?;
    let files = Array::new(ctx.clone())?;
    let mut count = 0;
    for entry in array.iter::<Object>() {
      let entry = entry?;
      let path: String = entry.get("path")?;
      let name: String = entry.get("name")?;
      let mime: String = entry.get("type").unwrap_or_default();
      let path = PathBuf::from(path);
      // a copy that is not there is a failure and not a cancellation, which is what an empty answer is
      let Ok(meta) = fs::metadata(&path) else {
        return PluginErrorCode::Internal.throw(ctx, "pickFile: the copy of this file is gone");
      };
      let mtime = meta
        .modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0);
      files.set(count, mint_owned_file(ctx, &self.blobs, &path, meta.len(), &mime, &name, mtime)?)?;
      count += 1;
    }
    if multiple {
      return Ok(files.into_value());
    }
    match files.get::<Value>(0) {
      Ok(first) if !first.is_undefined() => Ok(first),
      _ => Ok(Value::new_null(ctx.clone())),
    }
  }
}

#[cfg(test)]
#[path = "files_tests.rs"]
mod tests;

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use rquickjs::{Ctx, Exception, Function, Object, Result as JsResult, Runtime, Value};

use crate::api::error::PluginErrorCode;
use crate::api::platform::jvm::JvmState;
use crate::api::error::format_exception;
use crate::runtime::{pump_jobs, PendingSettle};
use crate::api::ui::icons;
use crate::sandbox::registry::RequestIds;

pub trait DialogHost {
  fn toast(&self, text: &str);
  fn bulletin(&self, text: &str, icon_spec: &str) -> Option<String>;
  fn dialog(&self, request_id: i64, options_json: &str) -> Option<String>;
  fn chooser(&self, request_id: i64, options_json: &str) -> Option<String>;
}

pub struct DialogState {
  host: Rc<dyn DialogHost>,
  jvm: Option<Rc<JvmState>>,
  log: crate::Log,
  next_request_id: RequestIds,
  pending_dialogs: RefCell<HashMap<i64, PendingSettle>>,
  pending_choosers: RefCell<HashMap<i64, (PendingSettle, bool)>>,
}

impl DialogState {
  fn js_ui_bulletin<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, options: Value<'js>) -> JsResult<()> {
    let Some(options) = options.as_object() else {
      return Err(Exception::throw_type(ctx, "bulletin: expected an options object"));
    };
    let text = opt_string(ctx, options, "bulletin", "text")?
      .ok_or_else(|| Exception::throw_type(ctx, "bulletin: 'text' must be a string"))?;
    let icon_value: Value =
      options.get("icon").map_err(|_| Exception::throw_type(ctx, "bulletin: cannot read 'icon'"))?;
    let icon = icons::icon_from_value(ctx, icon_value, "bulletin", self.jvm.as_ref())?
      .ok_or_else(|| Exception::throw_type(ctx, "bulletin: 'icon' is required"))?;
    if let Some(err) = self.host.bulletin(&text, &icon.spec) {
      return Err(ctx.throw(crate::api::error::host_error_to_js(ctx, &err)?));
    }
    Ok(())
  }

  fn js_ui_dialog<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, options: Value<'js>) -> JsResult<Value<'js>> {
    let Some(obj) = options.as_object() else {
      return Err(Exception::throw_type(ctx, "dialog: expected an options object"));
    };
    let body: Value = obj.get("body").map_err(|_| Exception::throw_type(ctx, "dialog: cannot read 'body'"))?;
    if !body.is_undefined() && !body.is_null() {
      let kind = body
        .as_object()
        .and_then(|o| o.get::<_, Option<String>>(crate::api::ui::pages::ELEMENT_TAG).ok().flatten());
      match kind.as_deref() {
        Some("native") => {}
        Some(other) => {
          return PluginErrorCode::Unsupported.throw(
            ctx,
            &format!("dialog: a '{other}' element cannot be a dialog body; only inu.android.nativeView can"),
          );
        }
        None => return Err(Exception::throw_type(ctx, "dialog: 'body' is not an inu.ui element")),
      }
    }
    let json = ctx
      .json_stringify(options)?
      .map(|s| s.to_string())
      .transpose()?
      .ok_or_else(|| Exception::throw_type(ctx, "dialog: expected an options object"))?;

    let request_id = self.next_request_id.alloc();
    let (promise, pending) = PendingSettle::new(ctx)?;
    self.pending_dialogs.borrow_mut().insert(request_id, pending);

    if let Some(err) = self.host.dialog(request_id, &json) {
      if let Some(pending) = self.pending_dialogs.borrow_mut().remove(&request_id) {
        pending.reject_with(ctx, &err)?;
      }
    }
    Ok(promise.into_value())
  }
}

fn opt_string<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Option<String>> {
  let value: Value = obj.get(key).map_err(|_| Exception::throw_type(ctx, &format!("{what}: cannot read '{key}'")))?;
  if value.is_undefined() || value.is_null() {
    return Ok(None);
  }
  match value.as_string() {
    Some(s) => Ok(Some(s.to_string()?)),
    None => Err(Exception::throw_type(ctx, &format!("{what}: '{key}' must be a string"))),
  }
}

fn opt_flag<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<bool> {
  let value: Value = obj.get(key).map_err(|_| Exception::throw_type(ctx, &format!("{what}: cannot read '{key}'")))?;
  if value.is_undefined() || value.is_null() {
    return Ok(false);
  }
  value
    .as_bool()
    .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a boolean")))
}

fn chooser_index(ctx: &Ctx<'_>, value: &Value<'_>, len: usize) -> JsResult<i32> {
  let index = value
    .as_int()
    .or_else(|| value.as_float().filter(|f| f.fract() == 0.0).map(|f| f as i32))
    .ok_or_else(|| Exception::throw_type(ctx, "chooser: 'selected' must be an integer index"))?;
  if index < 0 || index as usize >= len {
    return Err(Exception::throw_type(ctx, "chooser: 'selected' out of range"));
  }
  Ok(index)
}

impl DialogState {
  fn js_ui_chooser<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, opts: Object<'js>) -> JsResult<Value<'js>> {
    let out = Object::new(ctx.clone())?;
    if let Some(title) = opt_string(ctx, &opts, "chooser", "title")? {
      out.set("title", title)?;
    }
    let multiple = opt_flag(ctx, &opts, "chooser", "multiple")?;
    out.set("multiple", multiple)?;

    let raw: Value = opts.get("items").map_err(|_| Exception::throw_type(ctx, "chooser: cannot read 'items'"))?;
    let source = raw.as_array().ok_or_else(|| Exception::throw_type(ctx, "chooser: 'items' must be an array"))?;
    let source = crate::utils::arguments::array_values(ctx, source, "chooser: 'items'")?;
    if source.is_empty() {
      return Err(Exception::throw_type(ctx, "chooser: 'items' must not be empty"));
    }
    let items = rquickjs::Array::new(ctx.clone())?;
    for (i, item) in source.into_iter().enumerate() {
      let entry = Object::new(ctx.clone())?;
      if let Some(text) = item.as_string() {
        entry.set("text", text.to_string()?)?;
        entry.set("danger", false)?;
      } else if let Some(obj) = item.as_object() {
        let text = opt_string(ctx, obj, "chooser item", "text")?
          .ok_or_else(|| Exception::throw_type(ctx, "chooser item: 'text' must be a string"))?;
        entry.set("text", text)?;
        if let Some(subtitle) = opt_string(ctx, obj, "chooser item", "subtitle")? {
          entry.set("subtitle", subtitle)?;
        }
        entry.set("danger", opt_flag(ctx, obj, "chooser item", "danger")?)?;
      } else {
        return Err(Exception::throw_type(
          ctx,
          "chooser: items must be strings or { text, subtitle?, danger? } objects",
        ));
      }
      items.set(i, entry)?;
    }
    let len = items.len();
    out.set("items", items)?;

    let selected: Value =
      opts.get("selected").map_err(|_| Exception::throw_type(ctx, "chooser: cannot read 'selected'"))?;
    let picked = rquickjs::Array::new(ctx.clone())?;
    if !selected.is_undefined() && !selected.is_null() {
      match (multiple, selected.as_array()) {
        (true, Some(list)) => {
          for (i, index) in
            crate::utils::arguments::array_values(ctx, list, "chooser: 'selected'")?.into_iter().enumerate()
          {
            picked.set(i, chooser_index(ctx, &index, len)?)?;
          }
        }
        (true, None) => {
          return Err(Exception::throw_type(
            ctx,
            "chooser: 'selected' must be an array of indices when 'multiple' is set",
          ))
        }
        (false, Some(_)) => {
          return Err(Exception::throw_type(ctx, "chooser: 'selected' must be a single index unless 'multiple' is set"))
        }
        (false, None) => picked.set(0, chooser_index(ctx, &selected, len)?)?,
      }
    }
    out.set("selected", picked)?;

    let json = ctx
      .json_stringify(out.into_value())?
      .map(|s| s.to_string())
      .transpose()?
      .ok_or_else(|| Exception::throw_message(ctx, "chooser: serialization failed"))?;

    let request_id = self.next_request_id.alloc();
    let (promise, pending) = PendingSettle::new(ctx)?;
    self.pending_choosers.borrow_mut().insert(request_id, (pending, multiple));

    if let Some(err) = self.host.chooser(request_id, &json) {
      if let Some((pending, _)) = self.pending_choosers.borrow_mut().remove(&request_id) {
        pending.reject_with(ctx, &err)?;
      }
    }
    Ok(promise.into_value())
  }
}

pub fn install_dialogs<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn DialogHost>,
  jvm: Option<Rc<JvmState>>,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<DialogState>> {
  let state = Rc::new(DialogState {
    host,
    jvm,
    log,
    next_request_id: RequestIds::default(),
    pending_dialogs: RefCell::new(HashMap::new()),
    pending_choosers: RefCell::new(HashMap::new()),
  });
  let ui = Object::new(ctx.clone())?;
  let state2 = state.clone();
  ui.set(
    "toast",
    Function::new(ctx.clone(), move |text: rquickjs::Coerced<String>| {
      state2.host.toast(&text.0);
    })?,
  )?;

  let state2 = state.clone();
  ui.set(
    "bulletin",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, options: Value<'js>| state2.js_ui_bulletin(&ctx, options))?,
  )?;

  let state2 = state.clone();
  ui.set(
    "dialog",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, options: Value<'js>| state2.js_ui_dialog(&ctx, options))?,
  )?;

  let state2 = state.clone();
  ui.set(
    "chooser",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, options: Object<'js>| state2.js_ui_chooser(&ctx, options))?,
  )?;
  globals.inu.set("ui", ui)?;
  Ok(state)
}

impl DialogState {
  pub fn resolve_chooser(
    self: &Rc<Self>,
    rt: &Runtime,
    context: &rquickjs::Context,
    request_id: i64,
    picked: Option<&str>,
  ) {
    let state = self;
    context.with(|ctx| {
      let Some((pending, multiple)) = state.pending_choosers.borrow_mut().remove(&request_id) else {
        return;
      };
      let indices: Option<Vec<i32>> = picked.map(|list| {
        list
          .split(',')
          .filter(|part| !part.is_empty())
          .filter_map(|part| part.parse::<i32>().ok())
          .collect()
      });
      let value = match (indices, multiple) {
        (None, _) => Ok(Value::new_null(ctx.clone())),
        (Some(indices), true) => rquickjs::Array::new(ctx.clone()).and_then(|array| {
          for (i, index) in indices.iter().enumerate() {
            array.set(i, *index)?;
          }
          Ok(array.into_value())
        }),
        (Some(indices), false) => match indices.first() {
          Some(index) => rquickjs::IntoJs::into_js(*index, &ctx),
          None => Ok(Value::new_null(ctx.clone())),
        },
      };
      match value {
        Ok(v) => {
          if pending.resolve_with(&ctx, v).is_err() {
            (state.log)(&format!("chooser({request_id}) resolve failed: {}", format_exception(&ctx)));
          }
        }
        Err(e) => {
          pending.release(&ctx);
          (state.log)(&format!("chooser({request_id}) result conversion failed: {e:?}"));
        }
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  pub fn resolve_dialog(self: &Rc<Self>, rt: &Runtime, context: &rquickjs::Context, request_id: i64, result: &str) {
    let state = self;
    context.with(|ctx| {
      use rquickjs::IntoJs;
      if let Some(pending) = state.pending_dialogs.borrow_mut().remove(&request_id) {
        match result.into_js(&ctx) {
          Ok(v) => {
            if pending.resolve_with(&ctx, v).is_err() {
              (state.log)(&format!("dialog({request_id}) resolve failed: {}", format_exception(&ctx)));
            }
          }
          Err(e) => {
            pending.release(&ctx);
            (state.log)(&format!("dialog({request_id}) result conversion failed: {e:?}"));
          }
        }
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  pub fn dispose(self: &Rc<Self>, context: &rquickjs::Context) {
    let state = self;
    context.with(|ctx| {
      for (_, pending) in state.pending_dialogs.borrow_mut().drain() {
        pending.release(&ctx);
      }
      for (_, (pending, _)) in state.pending_choosers.borrow_mut().drain() {
        pending.release(&ctx);
      }
    });
  }
}

#[cfg(test)]
#[path = "dialogs_tests.rs"]
mod dialogs_tests;

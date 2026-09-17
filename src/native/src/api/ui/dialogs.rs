use std::rc::Rc;

use crate::runtime::Dispose;
use rquickjs::{Ctx, Exception, Function, Object, Result as JsResult, Runtime, Value};

use crate::api::error::PluginErrorCode;
use crate::api::platform::jvm::JvmState;
use crate::api::tl::proxy::plain_wire_to_js;
use crate::api::ui::icons;
use crate::runtime::{pump_jobs, Parked, PendingTable};
use crate::utils::arguments::{opt_bool, opt_str, req_str};

pub trait DialogHost {
  fn toast(&self, text: &str);
  fn bulletin(&self, text: &str, entities_json: &str, icon_spec: &str) -> Option<String>;
  fn dialog(&self, request_id: i64, options_json: &str) -> Option<String>;
  fn chooser(&self, request_id: i64, options_json: &str) -> Option<String>;
  fn prompt(&self, request_id: i64, options_json: &str) -> Option<String>;
}

/// what a modal answers once the user is done with it
enum Modal {
  Dialog,
  Prompt,
  Chooser { multiple: bool },
}

impl Parked for Modal {}

pub struct DialogState {
  host: Rc<dyn DialogHost>,
  jvm: Option<Rc<JvmState>>,
  log: crate::Log,
  pending: PendingTable<Modal>,
}

impl DialogState {
  fn js_ui_bulletin<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, options: Value<'js>) -> JsResult<()> {
    let Some(options) = options.as_object() else {
      return Err(Exception::throw_type(ctx, "bulletin: expected an options object"));
    };
    let text = crate::utils::arguments::opt_text(ctx, options, "bulletin", "text")?
      .ok_or_else(|| Exception::throw_type(ctx, "bulletin: 'text' must be a string"))?;
    let entities = (|| -> JsResult<String> {
      let entities = text.1;
      let Some(entities) = entities else {
        return Ok(String::new());
      };
      Ok(ctx.json_stringify(entities)?.map(|s| s.to_string()).transpose()?.unwrap_or_default())
    })()?;
    let icon_value: Value =
      options.get("icon").map_err(|_| Exception::throw_type(ctx, "bulletin: cannot read 'icon'"))?;
    let icon = icons::icon_from_value(ctx, icon_value, "bulletin", self.jvm.as_ref())?
      .ok_or_else(|| Exception::throw_type(ctx, "bulletin: 'icon' is required"))?;
    if let Some(err) = self.host.bulletin(&text.0, &entities, &icon.spec) {
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
    let out = Object::new(ctx.clone())?;
    for key in ["title", "message"] {
      if let Some(value) = crate::utils::arguments::opt_text(ctx, obj, "dialog", key)? {
        crate::utils::arguments::write_input_text(&out, key, value)?;
      }
    }
    for key in ["positive", "negative", "neutral"] {
      if let Some(text) = opt_str(ctx, obj, "dialog", key)? {
        out.set(key, text)?;
      }
    }
    if !body.is_undefined() && !body.is_null() {
      out.set("body", body)?;
    }
    let json = ctx
      .json_stringify(out.into_value())?
      .map(|s| s.to_string())
      .transpose()?
      .ok_or_else(|| Exception::throw_type(ctx, "dialog: expected an options object"))?;

    Ok(
      self
        .pending
        .park(ctx, Modal::Dialog, |request_id| self.host.dialog(request_id, &json))?
        .into_value(),
    )
  }
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
    if let Some(title) = opt_str(ctx, &opts, "chooser", "title")? {
      out.set("title", title)?;
    }
    let multiple = opt_bool(ctx, &opts, "chooser", "multiple")?;
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
        let text = opt_str(ctx, obj, "chooser item", "text")?
          .ok_or_else(|| Exception::throw_type(ctx, "chooser item: 'text' must be a string"))?;
        entry.set("text", text)?;
        if let Some(subtitle) = opt_str(ctx, obj, "chooser item", "subtitle")? {
          entry.set("subtitle", subtitle)?;
        }
        entry.set("danger", opt_bool(ctx, obj, "chooser item", "danger")?)?;
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

    let modal = Modal::Chooser { multiple };
    Ok(self.pending.park(ctx, modal, |request_id| self.host.chooser(request_id, &json))?.into_value())
  }

  fn js_prompt<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, opts: Object<'js>) -> JsResult<Value<'js>> {
    let out = Object::new(ctx.clone())?;
    out.set("title", req_str(ctx, &opts, "prompt", "title")?)?;
    if let Some(hint) = opt_str(ctx, &opts, "prompt", "hint")? {
      out.set("hint", hint)?;
    }
    if let Some(value) = opt_str(ctx, &opts, "prompt", "value")? {
      out.set("value", value)?;
    }
    out.set("selectAll", opt_bool(ctx, &opts, "prompt", "selectAll")?)?;
    let json = ctx
      .json_stringify(out)?
      .map(|s| s.to_string())
      .transpose()?
      .ok_or_else(|| Exception::throw_message(ctx, "prompt: serialization failed"))?;
    Ok(
      self
        .pending
        .park(ctx, Modal::Prompt, |request_id| self.host.prompt(request_id, &json))?
        .into_value(),
    )
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
    pending: PendingTable::default(),
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

  let state2 = state.clone();
  ui.set(
    "prompt",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, options: Object<'js>| state2.js_prompt(&ctx, options))?,
  )?;
  globals.inu.set("ui", ui)?;
  Ok(state)
}

impl DialogState {
  /// every modal answers a plain wire: a dialog the button's name, a prompt the text or `N`, and a
  /// chooser `N` or the picked indices - of which a single-choice chooser resolves the first
  pub fn settle(self: &Rc<Self>, rt: &Runtime, context: &rquickjs::Context, request_id: i64, result_wire: &str) {
    let state = self;
    context.with(|ctx| {
      let settled = state.pending.settle(&ctx, request_id, result_wire, false, |ctx, modal, wire| {
        let value = plain_wire_to_js(ctx, wire)?;
        match (modal, value.as_array()) {
          (Modal::Chooser { multiple: false }, Some(picked)) => {
            let first: Value = picked.get(0)?;
            Ok(if first.is_undefined() { Value::new_null(ctx.clone()) } else { first })
          }
          _ => Ok(value),
        }
      });
      if let Err(why) = settled {
        (state.log)(&format!("modal({request_id}) settle failed: {why}"));
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }
}

impl Dispose for DialogState {
  fn dispose(&self, context: &rquickjs::Context) {
    context.with(|ctx| self.pending.dispose(&ctx));
  }
}

#[cfg(test)]
#[path = "dialogs_tests.rs"]
mod dialogs_tests;

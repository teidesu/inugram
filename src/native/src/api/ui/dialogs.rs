use std::rc::Rc;

use crate::runtime::Dispose;
use rquickjs::{Ctx, Exception, Function, Object, Result as JsResult, Runtime, Value};

use crate::api::error::PluginErrorCode;
use crate::api::platform::jvm::JvmState;
use crate::api::tl::proxy::plain_wire_to_js;
use crate::api::ui::icons;
use crate::runtime::{pump_jobs, Parked, PendingTable};
use crate::utils::arguments::{self, opt_bool, opt_str, read_index, req_str, stringify_json};

pub trait DialogHost {
  fn toast(&self, text: &str);
  fn bulletin(&self, request_id: i64, options_json: &str) -> Option<String>;
  fn dialog(&self, request_id: i64, options_json: &str) -> Option<String>;
  fn chooser(&self, request_id: i64, options_json: &str) -> Option<String>;
  fn prompt(&self, request_id: i64, options_json: &str) -> Option<String>;
}

/// what a modal answers once the user is done with it
enum Modal {
  Dialog,
  Prompt,
  Chooser { multiple: bool },
  Bulletin,
}

impl Parked for Modal {}

/// stock's `Bulletin.UsersLayout` draws three avatars and nothing past them
const AVATAR_LIMIT: usize = 3;
/// stock's own `Bulletin.DURATION_SHORT`/`DURATION_LONG`
const DURATION_SHORT_MS: i64 = 1500;
const DURATION_LONG_MS: i64 = 2750;
const DURATION_MIN_MS: i64 = 500;
const DURATION_MAX_MS: i64 = 30_000;

/// `inu.icons` values are opaque tagged objects, so a plain `{ type: 'avatars' }` cannot be one
fn is_avatars(value: &Value<'_>) -> bool {
  let Some(object) = value.as_object() else { return false };
  object.get::<_, Option<String>>("type").ok().flatten().as_deref() == Some("avatars")
}

pub struct DialogState {
  host: Rc<dyn DialogHost>,
  jvm: Option<Rc<JvmState>>,
  log: crate::Log,
  pending: PendingTable<Modal>,
}

impl DialogState {
  /// A bulletin is parked like the modals are: it has an outcome the plugin may wait for, and
  /// reusing that machinery is what keeps a tappable bulletin from needing a dispatch of its own.
  /// Nothing has to be awaited - a plugin that only wants to say something drops the promise.
  fn js_ui_bulletin<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, options: Value<'js>) -> JsResult<Value<'js>> {
    let Some(options) = options.as_object() else {
      return Err(Exception::throw_type(ctx, "bulletin: expected an options object"));
    };
    let out = Object::new(ctx.clone())?;
    let text = arguments::opt_text(ctx, options, "bulletin", "text")?
      .ok_or_else(|| Exception::throw_type(ctx, "bulletin: 'text' must be a string"))?;
    arguments::write_input_text(&out, "text", text)?;
    if let Some(subtitle) = arguments::opt_text(ctx, options, "bulletin", "subtitle")? {
      arguments::write_input_text(&out, "subtitle", subtitle)?;
    }

    let icon_value: Value =
      options.get("icon").map_err(|_| Exception::throw_type(ctx, "bulletin: cannot read 'icon'"))?;
    if icon_value.is_undefined() || icon_value.is_null() {
      return Err(Exception::throw_type(ctx, "bulletin: 'icon' is required"));
    }
    if is_avatars(&icon_value) {
      let spec = icon_value.as_object().expect("an avatars icon is an object");
      let list: Value =
        spec.get("avatars").map_err(|_| Exception::throw_type(ctx, "bulletin: cannot read 'avatars'"))?;
      let list = list
        .as_array()
        .ok_or_else(|| Exception::throw_type(ctx, "bulletin: 'avatars' must be an array"))?;
      let ids = arguments::array_values(ctx, list, "bulletin: 'avatars'")?;
      // stock's own layout draws three and counts the rest; more than that is a silent no-op
      if ids.len() > AVATAR_LIMIT {
        return PluginErrorCode::InvalidArgument.throw(ctx, &format!("bulletin: at most {AVATAR_LIMIT} avatars"));
      }
      if ids.is_empty() {
        return Err(Exception::throw_type(ctx, "bulletin: 'avatars' must name at least one peer"));
      }
      let out_list = rquickjs::Array::new(ctx.clone())?;
      for (index, value) in ids.iter().enumerate() {
        let id = value
          .as_int()
          .map(i64::from)
          .or_else(|| value.as_float().filter(|f| f.fract() == 0.0).map(|f| f as i64))
          .ok_or_else(|| Exception::throw_type(ctx, "bulletin: 'avatars' takes dialog ids"))?;
        out_list.set(index, id)?;
      }
      out.set("avatars", out_list)?;
      // the peers are looked up in an account, and the one showing the bulletin is not always the
      // one the peers belong to - a notification about another account's chat is the whole reason
      if let Some(account) = arguments::opt_int(ctx, spec, "bulletin", "account")? {
        out.set("account", account)?;
      }
    } else {
      let icon = icons::icon_from_value(ctx, icon_value, "bulletin", self.jvm.as_ref())?
        .ok_or_else(|| Exception::throw_type(ctx, "bulletin: 'icon' is required"))?;
      out.set("icon", icon.spec.clone())?;
    }

    let duration: Value = options
      .get("duration")
      .map_err(|_| Exception::throw_type(ctx, "bulletin: cannot read 'duration'"))?;
    if !duration.is_undefined() && !duration.is_null() {
      let millis = if let Some(name) = duration.as_string() {
        match name.to_string()?.as_str() {
          "short" => DURATION_SHORT_MS,
          "long" => DURATION_LONG_MS,
          other => {
            return PluginErrorCode::InvalidArgument.throw(ctx, &format!("bulletin: unknown duration '{other}'"))
          }
        }
      } else {
        let millis = duration
          .as_int()
          .map(i64::from)
          .or_else(|| duration.as_float().map(|f| f as i64))
          .ok_or_else(|| Exception::throw_type(ctx, "bulletin: 'duration' must be a number or a name"))?;
        if !(DURATION_MIN_MS..=DURATION_MAX_MS).contains(&millis) {
          return PluginErrorCode::InvalidArgument
            .throw(ctx, &format!("bulletin: 'duration' must be between {DURATION_MIN_MS} and {DURATION_MAX_MS} ms"));
        }
        millis
      };
      out.set("duration", millis)?;
    }

    if let Some(position) = opt_str(ctx, options, "bulletin", "position")? {
      match position.as_str() {
        "top" => out.set("top", true)?,
        "bottom" => out.set("top", false)?,
        other => return PluginErrorCode::InvalidArgument.throw(ctx, &format!("bulletin: unknown position '{other}'")),
      }
    }

    if let Some(button) = opt_str(ctx, options, "bulletin", "button")? {
      out.set("button", button)?;
    }

    let json = stringify_json(ctx, out.into_value(), "bulletin")?;
    Ok(
      self
        .pending
        .park(ctx, Modal::Bulletin, |request_id| self.host.bulletin(request_id, &json))?
        .into_value(),
    )
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
      if let Some(value) = arguments::opt_text(ctx, obj, "dialog", key)? {
        arguments::write_input_text(&out, key, value)?;
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
    let source = arguments::array_values(ctx, source, "chooser: 'items'")?;
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
          for (i, index) in arguments::array_values(ctx, list, "chooser: 'selected'")?.into_iter().enumerate() {
            picked.set(i, read_index(ctx, &index, "chooser", len)?)?;
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
        (false, None) => picked.set(0, read_index(ctx, &selected, "chooser", len)?)?,
      }
    }
    out.set("selected", picked)?;

    let json = stringify_json(ctx, out.into_value(), "chooser: serialization failed")?;

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
    let json = stringify_json(ctx, out.into_value(), "prompt: serialization failed")?;
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

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use rquickjs::{Array, Ctx, Exception, Function, Object, Persistent, Result as JsResult, Value};

use crate::api::error::{
  call_callback, describe_js_error, error_value_to_string, format_thrown, host_error_to_js, PluginErrorCode,
};
use crate::api::platform::jvm::JvmState;
use crate::api::tl::proxy::json_stringify_tl;
use crate::api::ui::icons::opt_icon;
use crate::runtime::{enter_js, pump_jobs, Dispose};
use crate::sandbox::registry::{Lifecycle, RequestIds};
use crate::utils::arguments::{
  array_values, field, opt_bool, opt_fn, opt_int, opt_str, opt_text, req_fn, req_text, stringify_json,
  write_input_text,
};
use crate::utils::qjs::resolve_and_then;

pub(crate) const OP_APPEND: i32 = 0;
pub(crate) const OP_REPLACE: i32 = 1;
pub(crate) const OP_REMOVE: i32 = 2;
pub(crate) const OP_UNREAD_COUNT: i32 = 3;
pub(crate) const OP_BUTTON: i32 = 4;

/// a page past this builds too many message layouts on the ui thread at once
const MAX_ENTRIES: usize = 200;
const MAX_MENU_ITEMS: usize = 30;

pub trait HistoryHost {
  fn history_open(&self, history_id: i64, options_json: &str) -> Option<String>;
  fn history_update(&self, history_id: i64, op: i32, json: &str) -> Option<String>;
  fn history_close(&self, history_id: i64);
  /// `S` + page json, or `E` + message
  fn history_page(&self, request_id: i64, wire: &str);
  /// `S` + menu items json, or `E` + message
  fn history_menu(&self, request_id: i64, wire: &str);
}

struct HistoryDef {
  load: Persistent<Function<'static>>,
  resolve_closed: Persistent<Function<'static>>,
  retained_icon: Option<Persistent<Value<'static>>>,
  on_read: Option<Persistent<Function<'static>>>,
  on_button_click: Option<Persistent<Function<'static>>>,
  menu: Option<Persistent<Function<'static>>>,
  shown_menu: Option<ShownMenu>,
}

/// the items of the last menu the host was handed; clicks name it by request id
struct ShownMenu {
  request_id: i64,
  callbacks: Vec<Persistent<Function<'static>>>,
  retained_icons: Vec<Persistent<Value<'static>>>,
}

impl ShownMenu {
  fn release(self, ctx: &Ctx<'_>) {
    for f in self.callbacks {
      let _ = f.restore(ctx);
    }
    for icon in self.retained_icons {
      let _ = icon.restore(ctx);
    }
  }
}

impl HistoryDef {
  fn release(self, ctx: &Ctx<'_>) {
    let _ = self.load.restore(ctx);
    let _ = self.resolve_closed.restore(ctx);
    for f in [self.on_read, self.on_button_click, self.menu].into_iter().flatten() {
      let _ = f.restore(ctx);
    }
    if let Some(icon) = self.retained_icon {
      let _ = icon.restore(ctx);
    }
    if let Some(menu) = self.shown_menu {
      menu.release(ctx);
    }
  }
}

pub struct HistoryState {
  host: Rc<dyn HistoryHost>,
  jvm: Option<Rc<JvmState>>,
  lifecycle: Rc<Lifecycle>,
  pub(crate) log: crate::Log,
  next_id: RequestIds,
  defs: RefCell<HashMap<i64, HistoryDef>>,
}

fn serialize_page<'js>(ctx: &Ctx<'js>, value: Value<'js>, what: &str, with_next: bool) -> JsResult<String> {
  let page = value
    .as_object()
    .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: expected a page object")))?;
  let out = Object::new(ctx.clone())?;
  let entries = field(ctx, page, what, "entries")?;
  let entries = entries
    .as_array()
    .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: 'entries' must be an array")))?;
  let entries = array_values(ctx, entries, &format!("{what}: 'entries'"))?;
  if entries.len() > MAX_ENTRIES {
    return Err(Exception::throw_type(ctx, &format!("{what}: at most {MAX_ENTRIES} entries per page")));
  }
  let out_entries = Array::new(ctx.clone())?;
  for (i, entry) in entries.into_iter().enumerate() {
    let entry = entry
      .as_object()
      .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: entries must be objects")))?;
    let message = field(ctx, entry, what, "message")?;
    if message.as_object().and_then(|o| o.get::<_, Option<String>>("_").ok().flatten()).is_none() {
      return Err(Exception::throw_type(ctx, &format!("{what}: 'message' must be a TL message")));
    }
    let out_entry = Object::new(ctx.clone())?;
    out_entry.set("message", message)?;
    if let Some(key) = opt_str(ctx, entry, what, "key")? {
      out_entry.set("key", key)?;
    }
    if let Some(synthetic) = opt_bool(ctx, entry, what, "synthetic")? {
      out_entry.set("synthetic", synthetic)?;
    }
    out_entries.set(i, out_entry)?;
  }
  out.set("entries", out_entries)?;
  for list in ["users", "chats"] {
    let value = field(ctx, page, what, list)?;
    if value.is_undefined() || value.is_null() {
      continue;
    }
    if value.as_array().is_none() {
      return Err(Exception::throw_type(ctx, &format!("{what}: '{list}' must be an array")));
    }
    out.set(list, value)?;
  }
  if with_next {
    if let Some(next) = opt_str(ctx, page, what, "next")? {
      out.set("next", next)?;
    }
    if let Some(newer) = opt_str(ctx, page, what, "newer")? {
      out.set("newer", newer)?;
    }
    if let Some(first_unread) = opt_str(ctx, page, what, "firstUnread")? {
      out.set("firstUnread", first_unread)?;
    }
  }
  if let Some(count) = opt_int(ctx, page, what, "unreadCount")? {
    if count < 0 {
      return Err(Exception::throw_type(ctx, &format!("{what}: 'unreadCount' must not be negative")));
    }
    out.set("unreadCount", count)?;
  }
  json_stringify_tl(ctx, out.into_value())
}

impl HistoryState {
  fn js_open<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, options: Value<'js>) -> JsResult<Object<'js>> {
    let what = "openChatHistory";
    let opts = options
      .as_object()
      .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: expected an options object")))?;
    let out = Object::new(ctx.clone())?;
    write_input_text(&out, "title", req_text(ctx, opts, what, "title")?)?;
    if let Some(subtitle) = opt_text(ctx, opts, what, "subtitle")? {
      write_input_text(&out, "subtitle", subtitle)?;
    }
    if let Some(account) = opt_int(ctx, opts, what, "account")? {
      out.set("account", account)?;
    }
    out.set("synthetic", opt_bool(ctx, opts, what, "synthetic")?.unwrap_or_default())?;
    out.set("avatars", opt_bool(ctx, opts, what, "avatars")?.unwrap_or(true))?;
    let mut retained_icon = None;
    if let Some(icon) = opt_icon(ctx, opts, what, self.jvm.as_ref())? {
      // the header is a static avatar slot: nothing plays there
      if icon.spec.starts_with(['a', 'e', 't']) {
        return Err(Exception::throw_type(ctx, &format!("{what}: animated, emoji and sticker icons cannot be a chat avatar")));
      }
      out.set("icon", icon.spec)?;
      retained_icon = icon.retained_value.map(|value| Persistent::save(ctx, value));
    }
    let load = req_fn(ctx, opts, what, "load")?;
    let on_read = opt_fn(ctx, opts, what, "onRead")?;
    out.set("read", on_read.is_some())?;
    let button = field(ctx, opts, what, "button")?;
    let on_button_click = if button.is_undefined() || button.is_null() {
      None
    } else {
      let button = button.as_object().ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: 'button' must be an object")))?;
      let shown = Object::new(ctx.clone())?;
      if let Some(text) = opt_text(ctx, button, "button", "text")? {
        write_input_text(&shown, "text", text)?;
      }
      out.set("button", shown)?;
      Some(req_fn(ctx, button, "button", "onClick")?)
    };
    let menu = opt_fn(ctx, opts, what, "menu")?;
    out.set("menu", menu.is_some())?;
    let json = stringify_json(ctx, out.into_value(), what)?;

    let history_id = self.next_id.alloc();
    let (closed, resolve_closed, _) = ctx.promise()?;
    if !self.lifecycle.is_unloading() {
      self.defs.borrow_mut().insert(
        history_id,
        HistoryDef {
          load: Persistent::save(ctx, load),
          resolve_closed: Persistent::save(ctx, resolve_closed),
          retained_icon,
          on_read: on_read.map(|f| Persistent::save(ctx, f)),
          on_button_click: on_button_click.map(|f| Persistent::save(ctx, f)),
          menu: menu.map(|f| Persistent::save(ctx, f)),
          shown_menu: None,
        },
      );
      if let Some(err) = self.host.history_open(history_id, &json) {
        self.defs.borrow_mut().remove(&history_id);
        return Err(ctx.throw(host_error_to_js(ctx, &err)?));
      }
    }

    let handle = Object::new(ctx.clone())?;
    handle.set("closed", closed)?;
    for (name, op) in [("append", OP_APPEND), ("replace", OP_REPLACE)] {
      let state = self.clone();
      handle.set(
        name,
        Function::new(ctx.clone(), move |ctx: Ctx<'js>, page: Value<'js>| -> JsResult<()> {
          state.require_open(&ctx, history_id, name)?;
          let json = serialize_page(&ctx, page, name, false)?;
          state.update(&ctx, history_id, op, &json)
        })?,
      )?;
    }
    let state = self.clone();
    handle.set(
      "remove",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, keys: Value<'js>| -> JsResult<()> {
        state.require_open(&ctx, history_id, "remove")?;
        let keys = keys.as_array().ok_or_else(|| Exception::throw_type(&ctx, "remove: expected an array of keys"))?;
        for key in array_values(&ctx, keys, "remove")? {
          if key.as_string().is_none() {
            return Err(Exception::throw_type(&ctx, "remove: keys must be strings"));
          }
        }
        let json = stringify_json(&ctx, keys.clone().into_value(), "remove")?;
        state.update(&ctx, history_id, OP_REMOVE, &json)
      })?,
    )?;
    let state = self.clone();
    handle.set(
      "setUnreadCount",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, count: Value<'js>| -> JsResult<()> {
        state.require_open(&ctx, history_id, "setUnreadCount")?;
        let count = count
          .as_int()
          .or_else(|| count.as_float().filter(|f| f.fract() == 0.0).map(|f| f as i32))
          .filter(|c| *c >= 0)
          .ok_or_else(|| Exception::throw_type(&ctx, "setUnreadCount: expected a non-negative integer"))?;
        state.update(&ctx, history_id, OP_UNREAD_COUNT, &count.to_string())
      })?,
    )?;
    let state = self.clone();
    handle.set(
      "setButton",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, button: Value<'js>| -> JsResult<()> {
        state.require_open(&ctx, history_id, "setButton")?;
        if state.defs.borrow().get(&history_id).is_some_and(|def| def.on_button_click.is_none()) {
          return Err(Exception::throw_type(&ctx, "setButton: this chat history was opened without a button"));
        }
        if button.is_null() {
          return state.update(&ctx, history_id, OP_BUTTON, "null");
        }
        let button = button.as_object().ok_or_else(|| Exception::throw_type(&ctx, "setButton: expected { text } or null"))?;
        let out = Object::new(ctx.clone())?;
        write_input_text(&out, "text", req_text(&ctx, button, "setButton", "text")?)?;
        let json = stringify_json(&ctx, out.into_value(), "setButton")?;
        state.update(&ctx, history_id, OP_BUTTON, &json)
      })?,
    )?;
    let state = self.clone();
    handle.set(
      "close",
      Function::new(ctx.clone(), move || {
        if state.defs.borrow().contains_key(&history_id) {
          state.host.history_close(history_id);
        }
      })?,
    )?;
    Ok(handle)
  }

  fn require_open(&self, ctx: &Ctx<'_>, history_id: i64, what: &str) -> JsResult<()> {
    if self.defs.borrow().contains_key(&history_id) {
      return Ok(());
    }
    PluginErrorCode::HandleExpired.throw(ctx, &format!("{what}: this chat history has been closed"))
  }

  fn update(&self, ctx: &Ctx<'_>, history_id: i64, op: i32, json: &str) -> JsResult<()> {
    match self.host.history_update(history_id, op, json) {
      Some(err) => Err(ctx.throw(host_error_to_js(ctx, &err)?)),
      None => Ok(()),
    }
  }
}

pub fn install_history<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn HistoryHost>,
  jvm: Option<Rc<JvmState>>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<HistoryState>> {
  let state = Rc::new(HistoryState {
    host,
    jvm,
    lifecycle,
    log,
    next_id: RequestIds::default(),
    defs: RefCell::new(HashMap::new()),
  });
  let ui = globals.get_namespace(ctx, "ui")?;
  let state2 = state.clone();
  ui.set(
    "openChatHistory",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, options: Value<'js>| state2.js_open(&ctx, options))?,
  )?;
  Ok(state)
}

impl HistoryState {
  fn try_dispatch_load<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    history_id: i64,
    request_id: i64,
    cursor: Option<&str>,
    newer: bool,
  ) -> JsResult<()> {
    let load = match self.defs.borrow().get(&history_id) {
      Some(def) => def.load.clone(),
      None => {
        self.host.history_page(request_id, "Ethe chat history was closed");
        return Ok(());
      }
    };
    let load = load.restore(ctx)?;
    let cursor = match cursor {
      Some(cursor) => rquickjs::String::from_str(ctx.clone(), cursor)?.into_value(),
      None => Value::new_null(ctx.clone()),
    };
    let result: Value = load.call((cursor, if newer { "newer" } else { "older" }))?;
    let ok = {
      let state = self.clone();
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, page: Value<'js>| {
        let wire = match serialize_page(&ctx, page, "load", true) {
          Ok(json) => format!("S{json}"),
          Err(e) => format!("E{}", describe_js_error(&ctx, e)),
        };
        state.host.history_page(request_id, &wire);
      })?
    };
    let err = {
      let state = self.clone();
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| {
        (state.log)(&crate::fault(format_args!("openChatHistory load rejected: {}", format_thrown(&ctx, &value))));
        state.host.history_page(request_id, &format!("E{}", error_value_to_string(&ctx, &value)));
      })?
    };
    resolve_and_then(ctx, result, ok, err)
  }

  pub fn dispatch_load(
    self: &Rc<Self>,
    context: &rquickjs::Context,
    history_id: i64,
    request_id: i64,
    cursor: Option<&str>,
    newer: bool,
  ) {
    if self.lifecycle.is_unloading() {
      self.host.history_page(request_id, "Ethe plugin is unloading");
      return;
    }
    enter_js(context, |ctx| {
      if let Err(e) = self.try_dispatch_load(&ctx, history_id, request_id, cursor, newer) {
        let msg = describe_js_error(&ctx, e);
        (self.log)(&crate::fault(format_args!("openChatHistory load failed: {msg}")));
        self.host.history_page(request_id, &format!("E{msg}"));
      }
    });
    pump_jobs(context, self.log.as_ref());
  }

  /// the newest entry the user has had on screen, once per advance
  pub fn dispatch_read(self: &Rc<Self>, context: &rquickjs::Context, history_id: i64, key: &str) {
    if self.lifecycle.is_unloading() {
      return;
    }
    enter_js(context, |ctx| {
      let on_read = self.defs.borrow().get(&history_id).and_then(|def| def.on_read.clone());
      let Some(on_read) = on_read else { return };
      match on_read.restore(&ctx) {
        Ok(f) => {
          call_callback(&ctx, &self.log, "onRead callback", &f, (key,));
        }
        Err(e) => (self.log)(&format!("openChatHistory: failed to restore onRead: {e:?}")),
      }
    });
    pump_jobs(context, self.log.as_ref());
  }

  pub fn dispatch_button_click(self: &Rc<Self>, context: &rquickjs::Context, history_id: i64) {
    if self.lifecycle.is_unloading() {
      return;
    }
    enter_js(context, |ctx| {
      let on_click = self.defs.borrow().get(&history_id).and_then(|def| def.on_button_click.clone());
      let Some(on_click) = on_click else { return };
      match on_click.restore(&ctx) {
        Ok(f) => {
          call_callback(&ctx, &self.log, "button onClick callback", &f, ());
        }
        Err(e) => (self.log)(&format!("openChatHistory: failed to restore the button callback: {e:?}")),
      }
    });
    pump_jobs(context, self.log.as_ref());
  }

  fn try_dispatch_menu<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, history_id: i64, request_id: i64) -> JsResult<()> {
    let menu = self.defs.borrow().get(&history_id).and_then(|def| def.menu.clone());
    let Some(menu) = menu else {
      self.host.history_menu(request_id, "Ethe chat history was closed");
      return Ok(());
    };
    let result: Value = menu.restore(ctx)?.call(())?;
    let ok = {
      let state = self.clone();
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, items: Value<'js>| {
        let wire = match state.serialize_menu(&ctx, history_id, request_id, items) {
          Ok(json) => format!("S{json}"),
          Err(e) => format!("E{}", describe_js_error(&ctx, e)),
        };
        state.host.history_menu(request_id, &wire);
      })?
    };
    let err = {
      let state = self.clone();
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| {
        (state.log)(&crate::fault(format_args!("openChatHistory menu rejected: {}", format_thrown(&ctx, &value))));
        state.host.history_menu(request_id, &format!("E{}", error_value_to_string(&ctx, &value)));
      })?
    };
    resolve_and_then(ctx, result, ok, err)
  }

  /// keeps the callbacks as the history's shown menu, dropping the one before
  fn serialize_menu<'js>(&self, ctx: &Ctx<'js>, history_id: i64, request_id: i64, items: Value<'js>) -> JsResult<String> {
    const WHAT: &str = "menu";
    let items = items.as_array().ok_or_else(|| Exception::throw_type(ctx, "menu: expected an array of items"))?;
    let items = array_values(ctx, items, WHAT)?;
    if items.len() > MAX_MENU_ITEMS {
      return Err(Exception::throw_type(ctx, &format!("menu: at most {MAX_MENU_ITEMS} items")));
    }
    let out = Array::new(ctx.clone())?;
    let mut callbacks = Vec::with_capacity(items.len());
    let mut retained_icons = Vec::new();
    for (i, item) in items.into_iter().enumerate() {
      let item = item.as_object().ok_or_else(|| Exception::throw_type(ctx, "menu: items must be objects"))?;
      let entry = Object::new(ctx.clone())?;
      write_input_text(&entry, "text", req_text(ctx, item, "menu item", "text")?)?;
      if let Some(icon) = opt_icon(ctx, item, "menu item", self.jvm.as_ref())? {
        entry.set("icon", icon.spec)?;
        retained_icons.extend(icon.retained_value.map(|value| Persistent::save(ctx, value)));
      }
      entry.set("danger", opt_bool(ctx, item, "menu item", "danger")?.unwrap_or_default())?;
      if let Some(checked) = opt_bool(ctx, item, "menu item", "checked")? {
        entry.set("checked", checked)?;
      }
      callbacks.push(Persistent::save(ctx, req_fn(ctx, item, "menu item", "onClick")?));
      out.set(i, entry)?;
    }
    let json = stringify_json(ctx, out.into_value(), WHAT)?;
    let shown = ShownMenu { request_id, callbacks, retained_icons };
    let previous = match self.defs.borrow_mut().get_mut(&history_id) {
      Some(def) => def.shown_menu.replace(shown),
      None => {
        shown.release(ctx);
        return Err(Exception::throw_type(ctx, "menu: the chat history was closed"));
      }
    };
    if let Some(previous) = previous {
      previous.release(ctx);
    }
    Ok(json)
  }

  pub fn dispatch_menu(self: &Rc<Self>, context: &rquickjs::Context, history_id: i64, request_id: i64) {
    if self.lifecycle.is_unloading() {
      self.host.history_menu(request_id, "Ethe plugin is unloading");
      return;
    }
    enter_js(context, |ctx| {
      if let Err(e) = self.try_dispatch_menu(&ctx, history_id, request_id) {
        let msg = describe_js_error(&ctx, e);
        (self.log)(&crate::fault(format_args!("openChatHistory menu failed: {msg}")));
        self.host.history_menu(request_id, &format!("E{msg}"));
      }
    });
    pump_jobs(context, self.log.as_ref());
  }

  /// a click on a menu that has since been replaced is dropped
  pub fn dispatch_menu_click(self: &Rc<Self>, context: &rquickjs::Context, history_id: i64, request_id: i64, index: i32) {
    if self.lifecycle.is_unloading() {
      return;
    }
    enter_js(context, |ctx| {
      let callback = self.defs.borrow().get(&history_id).and_then(|def| {
        let shown = def.shown_menu.as_ref().filter(|shown| shown.request_id == request_id)?;
        shown.callbacks.get(usize::try_from(index).ok()?).cloned()
      });
      let Some(callback) = callback else { return };
      match callback.restore(&ctx) {
        Ok(f) => {
          call_callback(&ctx, &self.log, "menu item callback", &f, ());
        }
        Err(e) => (self.log)(&format!("openChatHistory: failed to restore a menu callback: {e:?}")),
      }
    });
    pump_jobs(context, self.log.as_ref());
  }

  pub fn dispatch_closed(self: &Rc<Self>, context: &rquickjs::Context, history_id: i64) {
    enter_js(context, |ctx| {
      let Some(def) = self.defs.borrow_mut().remove(&history_id) else { return };
      let resolve_closed = def.resolve_closed.clone();
      def.release(&ctx);
      match resolve_closed.restore(&ctx) {
        Ok(resolve) => {
          call_callback(&ctx, &self.log, "chat history closed", &resolve, ());
        }
        Err(e) => (self.log)(&format!("openChatHistory: failed to restore the closed promise: {e:?}")),
      }
    });
    pump_jobs(context, self.log.as_ref());
  }
}

impl Dispose for HistoryState {
  fn dispose(&self, context: &rquickjs::Context) {
    enter_js(context, |ctx| {
      for (_, def) in std::mem::take(&mut *self.defs.borrow_mut()) {
        def.release(&ctx);
      }
    });
  }
}

#[cfg(test)]
#[path = "history_tests.rs"]
mod tests;

use std::cell::RefCell;
use std::rc::Rc;

use rquickjs::{Array, Ctx, Exception, Function, Object, Persistent, Result as JsResult, Value};

use crate::api::error::{call_callback, host_error_to_js, PluginErrorCode};
use crate::api::platform::jvm::JvmState;
use crate::api::ui::icons::opt_icon;
use crate::api::ui::pages::{MenuAnchor, UiState};
use crate::runtime::{enter_js, pump_jobs, Dispose};
use crate::sandbox::registry::{Lifecycle, Registry};
use crate::utils::arguments::{
  array_values, field, opt_bool, opt_fn, opt_int, opt_str, req_fn, req_str, stringify_json, to_int,
};
use crate::utils::qjs::qjs_symbol_dispose_atom;

const WHAT: &str = "addDialogEntry";
/// every entry is a row the chat list merges on each rebuild
const MAX_ENTRIES: usize = 20;

pub trait DialogEntryHost {
  /// adds the entry, or replaces the content of one already added under `token`
  fn dialog_entry_set(&self, token: u32, json: &str) -> Option<String>;
  fn dialog_entry_remove(&self, token: u32);
}

struct EntryDef {
  on_click: Persistent<Function<'static>>,
  on_secondary_click: Option<Persistent<Function<'static>>>,
  retained_icon: RefCell<Option<Persistent<Value<'static>>>>,
}

pub struct DialogEntryState {
  host: Rc<dyn DialogEntryHost>,
  ui: Rc<UiState>,
  jvm: Option<Rc<JvmState>>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  entries: Registry<Rc<EntryDef>>,
}

fn read_peer<'js>(ctx: &Ctx<'js>, value: &Value<'js>, what: &str) -> JsResult<i64> {
  value
    .as_int()
    .map(i64::from)
    .or_else(|| value.as_float().filter(|f| f.fract() == 0.0 && f.abs() < 2f64.powi(53)).map(|f| f as i64))
    .filter(|id| *id != 0)
    .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: 'peer' must be a non-zero marked peer id")))
}

impl DialogEntryState {
  /// the content json, and the jvm value an icon keeps alive
  fn serialize_content<'js>(
    &self,
    ctx: &Ctx<'js>,
    content: &Object<'js>,
    what: &str,
  ) -> JsResult<(Object<'js>, Option<Value<'js>>)> {
    let out = Object::new(ctx.clone())?;
    out.set("pinned", opt_bool(ctx, content, what, "pinned")?.unwrap_or_default())?;
    let peer = field(ctx, content, what, "peer")?;
    if !peer.is_undefined() && !peer.is_null() {
      out.set("peer", read_peer(ctx, &peer, what)? as f64)?;
      return Ok((out, None));
    }
    out.set("title", req_str(ctx, content, what, "title")?)?;
    if let Some(text) = opt_str(ctx, content, what, "text")? {
      out.set("text", text)?;
    }
    for name in ["date", "unreadCount"] {
      if let Some(value) = opt_int(ctx, content, what, name)? {
        if value < 0 {
          return Err(Exception::throw_type(ctx, &format!("{what}: '{name}' must not be negative")));
        }
        out.set(name, value)?;
      }
    }
    for name in ["muted", "verified"] {
      out.set(name, opt_bool(ctx, content, what, name)?.unwrap_or_default())?;
    }
    let mut retained = None;
    if let Some(icon) = opt_icon(ctx, content, what, self.jvm.as_ref())? {
      // the avatar slot is static: nothing plays there
      if icon.spec.starts_with(['a', 'e', 't']) {
        return Err(Exception::throw_type(ctx, &format!("{what}: animated, emoji and sticker icons cannot be an avatar")));
      }
      out.set("icon", icon.spec)?;
      retained = icon.retained_value;
    }
    Ok((out, retained))
  }

  fn set(&self, ctx: &Ctx<'_>, token: u32, json: &str) -> JsResult<()> {
    match self.host.dialog_entry_set(token, json) {
      Some(err) => Err(ctx.throw(host_error_to_js(ctx, &err)?)),
      None => Ok(()),
    }
  }

  fn js_add<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, options: Value<'js>) -> JsResult<Object<'js>> {
    let opts = options
      .as_object()
      .ok_or_else(|| Exception::throw_type(ctx, &format!("{WHAT}: expected an options object")))?;
    let (content, retained_icon) = self.serialize_content(ctx, opts, WHAT)?;
    if let Some(account) = opt_int(ctx, opts, WHAT, "account")? {
      content.set("account", account)?;
    }
    let folders = field(ctx, opts, WHAT, "chatFolders")?;
    if !folders.is_undefined() && !folders.is_null() {
      let invalid = || Exception::throw_type(ctx, &format!("{WHAT}: 'chatFolders' must be an array of chat folder ids"));
      let ids = Array::new(ctx.clone())?;
      for (i, id) in array_values(ctx, folders.as_array().ok_or_else(invalid)?, WHAT)?.iter().enumerate() {
        ids.set(i, to_int(id).filter(|id| *id >= 0).ok_or_else(invalid)?)?;
      }
      content.set("chatFolders", ids)?;
    }
    let on_click = req_fn(ctx, opts, WHAT, "onClick")?;
    let on_secondary_click = opt_fn(ctx, opts, WHAT, "onSecondaryClick")?;
    let json = stringify_json(ctx, content.into_value(), WHAT)?;

    let handle = Object::new(ctx.clone())?;
    if self.lifecycle.is_unloading() {
      handle.set("update", Function::new(ctx.clone(), || {})?)?;
      let dispose = Function::new(ctx.clone(), || {})?;
      handle.set(qjs_symbol_dispose_atom(ctx)?, dispose.clone())?;
      handle.set("dispose", dispose)?;
      return Ok(handle);
    }
    if self.entries.len() >= MAX_ENTRIES {
      return PluginErrorCode::QuotaExceeded(self.entries.len() as i64, MAX_ENTRIES as i64)
        .throw(ctx, &format!("{WHAT}: at most {MAX_ENTRIES} entries per plugin"));
    }
    let token = self.entries.alloc();
    self.set(ctx, token, &json)?;
    self.entries.insert(
      token,
      None,
      Rc::new(EntryDef {
        on_click: Persistent::save(ctx, on_click),
        on_secondary_click: on_secondary_click.map(|f| Persistent::save(ctx, f)),
        retained_icon: RefCell::new(retained_icon.map(|value| Persistent::save(ctx, value))),
      }),
    );

    let state = self.clone();
    handle.set(
      "update",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, content: Value<'js>| -> JsResult<()> {
        let what = "DialogEntry.update";
        let Some(def) = state.entries.get(token) else {
          return PluginErrorCode::HandleExpired.throw(&ctx, &format!("{what}: this entry has been disposed"));
        };
        let content = content
          .as_object()
          .ok_or_else(|| Exception::throw_type(&ctx, &format!("{what}: expected a content object")))?;
        let (content, retained_icon) = state.serialize_content(&ctx, content, what)?;
        state.set(&ctx, token, &stringify_json(&ctx, content.into_value(), what)?)?;
        def.retained_icon.replace(retained_icon.map(|value| Persistent::save(&ctx, value)));
        Ok(())
      })?,
    )?;
    let state = self.clone();
    let dispose = Function::new(ctx.clone(), move || {
      if state.entries.remove(token).is_some() {
        state.host.dialog_entry_remove(token);
      }
    })?;
    handle.set(qjs_symbol_dispose_atom(ctx)?, dispose.clone())?;
    handle.set("dispose", dispose)?;
    Ok(handle)
  }

  pub fn dispatch_click(self: &Rc<Self>, context: &rquickjs::Context, token: u32, tap_id: i64, secondary: bool) {
    if self.lifecycle.is_unloading() {
      return;
    }
    enter_js(context, |ctx| {
      let Some(def) = self.entries.get(token) else { return };
      let callback = if secondary { def.on_secondary_click.clone() } else { Some(def.on_click.clone()) };
      let Some(callback) = callback else { return };
      let callback = match callback.restore(&ctx) {
        Ok(f) => f,
        Err(e) => return (self.log)(&format!("{WHAT}: failed to restore a callback: {e:?}")),
      };
      let anchor = match self.ui.make_anchor(&ctx, MenuAnchor::DialogEntryTap(tap_id)) {
        Ok(anchor) => anchor,
        Err(e) => return (self.log)(&format!("{WHAT}: failed to build the anchor: {e:?}")),
      };
      let name = if secondary { "onSecondaryClick callback" } else { "onClick callback" };
      call_callback(&ctx, &self.log, name, &callback, (anchor,));
    });
    pump_jobs(context, self.log.as_ref());
  }
}

pub fn install_dialog_entries<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn DialogEntryHost>,
  ui: Rc<UiState>,
  jvm: Option<Rc<JvmState>>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<DialogEntryState>> {
  let state = Rc::new(DialogEntryState { host, ui, jvm, lifecycle, log, entries: Registry::default() });
  let namespace = globals.get_namespace(ctx, "ui")?;
  let state2 = state.clone();
  namespace.set(
    "addDialogEntry",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, options: Value<'js>| state2.js_add(&ctx, options))?,
  )?;
  Ok(state)
}

impl Dispose for DialogEntryState {
  fn dispose(&self, context: &rquickjs::Context) {
    enter_js(context, |_| drop(self.entries.remove_matching(|_| true)));
  }
}

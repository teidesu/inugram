use std::cell::RefCell;
use crate::runtime::Dispose;
use std::rc::Rc;

use rquickjs::object::Accessor;
use rquickjs::{Array, Ctx, Exception, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::api::error::format_exception;
use crate::api::error::PluginErrorCode;
use crate::api::platform::jvm::JvmState;
use crate::api::telegram::account::AccountState;
use crate::api::tl::proxy::json_parse_tl;
use crate::api::ui::icons::{icon_from_value, Icon};
use crate::runtime::pump_jobs;
use crate::sandbox::grants::{GrantHost, MATCH_EXACT};
use crate::sandbox::registry::{make_disposer, noop_disposer, Lifecycle, Registry, Token};
use crate::utils::arguments::{field, opt_fn, req_fn, req_str};

pub const KIND_GLOBAL: i32 = 0;
pub const KIND_CHAT: i32 = 1;
pub const KIND_MESSAGE: i32 = 2;
pub const KIND_PROFILE: i32 = 3;
pub const KIND_EDITOR: i32 = 4;

pub const MESSAGE_PLACEMENT_BUBBLE: i32 = 1;
pub const MESSAGE_PLACEMENT_SELECTION: i32 = 2;
const ALL_PLACEMENTS: i32 = -1;

pub const DYNAMIC_TEXT: i32 = 1;
pub const DYNAMIC_ICON: i32 = 2;
pub const DYNAMIC_VISIBLE: i32 = 4;

const DRAFT_GRANT: &str = "account.read";
const DRAFT_SCOPE: &str = "draft";

const KIND_COUNT: usize = 5;

pub const EDITOR_REPLACE: i32 = 0;
pub const EDITOR_SEND: i32 = 1;

fn kind_name(kind: i32) -> &'static str {
  match kind {
    KIND_CHAT => "registerChatAction",
    KIND_MESSAGE => "registerMessageAction",
    KIND_PROFILE => "registerProfileAction",
    KIND_EDITOR => "registerMessageEditorAction",
    _ => "registerAction",
  }
}

pub trait ActionHost {
  fn action_register(
    &self,
    kind: i32,
    token: u32,
    id: &str,
    placements: i32,
    text: Option<&str>,
    icon: Option<&str>,
    dynamic_fields: i32,
  ) -> Option<String>;
  fn action_unregister(&self, kind: i32, token: u32);
  fn action_editor(&self, op: i32, surface: i64, payload_json: &str) -> Option<String>;
}

enum Label {
  Static(String),
  Dynamic(Persistent<Function<'static>>),
}

enum ActionIcon {
  Static { spec: String, retained: Option<Persistent<Value<'static>>> },
  Dynamic(Persistent<Function<'static>>),
}

struct ActionDef {
  token: Token,
  placements: i32,
  label: Label,
  icon: Option<ActionIcon>,
  retained_icons: RefCell<Vec<Persistent<Value<'static>>>>,
  visible: Option<Persistent<Function<'static>>>,
  callback: Persistent<Function<'static>>,
}

impl ActionDef {
  fn release(self, ctx: &Ctx<'_>) {
    if let Label::Dynamic(p) = self.label {
      let _ = p.restore(ctx);
    }
    if let Some(icon) = self.icon {
      match icon {
        ActionIcon::Static { retained, .. } => {
          if let Some(p) = retained {
            let _ = p.restore(ctx);
          }
        }
        ActionIcon::Dynamic(p) => {
          let _ = p.restore(ctx);
        }
      }
    }
    for p in self.retained_icons.into_inner() {
      let _ = p.restore(ctx);
    }
    if let Some(p) = self.visible {
      let _ = p.restore(ctx);
    }
    let _ = self.callback.restore(ctx);
  }
}

fn release_icon(ctx: &Ctx<'_>, icon: Option<ActionIcon>) {
  match icon {
    Some(ActionIcon::Static { retained: Some(p), .. }) => {
      let _ = p.restore(ctx);
    }
    Some(ActionIcon::Dynamic(p)) => {
      let _ = p.restore(ctx);
    }
    _ => {}
  }
}

fn release_label(ctx: &Ctx<'_>, label: Label) {
  if let Label::Dynamic(p) = label {
    let _ = p.restore(ctx);
  }
}

pub struct ActionState {
  host: Rc<dyn ActionHost>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  accounts: Option<Rc<AccountState>>,
  grants: Rc<dyn GrantHost>,
  jvm: Option<Rc<JvmState>>,
  kinds: Vec<Registry<Rc<ActionDef>>>,
}

impl ActionState {
  fn has_draft_grant(&self) -> bool {
    self.grants.is_granted(DRAFT_GRANT, Some(DRAFT_SCOPE), MATCH_EXACT)
  }

  fn registry(&self, kind: i32) -> Option<&Registry<Rc<ActionDef>>> {
    self.kinds.get(kind as usize)
  }
}

pub fn install_actions<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn ActionHost>,
  lifecycle: Rc<Lifecycle>,
  accounts: Option<Rc<AccountState>>,
  grants: Rc<dyn GrantHost>,
  jvm: Option<Rc<JvmState>>,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<ActionState>> {
  let state = Rc::new(ActionState {
    host,
    lifecycle,
    log,
    accounts,
    grants,
    jvm,
    kinds: (0..KIND_COUNT).map(|_| Registry::default()).collect(),
  });

  for (name, kind) in [
    ("registerAction", KIND_GLOBAL),
    ("registerChatAction", KIND_CHAT),
    ("registerMessageAction", KIND_MESSAGE),
    ("registerProfileAction", KIND_PROFILE),
    ("registerMessageEditorAction", KIND_EDITOR),
  ] {
    let state = state.clone();
    globals.inu.set(
      name,
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, opts: Object<'js>| state.js_register(&ctx, kind, opts))?,
    )?;
  }
  Ok(state)
}

impl ActionState {
  fn js_register<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, kind: i32, opts: Object<'js>) -> JsResult<Function<'js>> {
    let state = self;
    let what = kind_name(kind);
    let id = req_str(ctx, &opts, what, "id")?;
    let placements = if kind == KIND_MESSAGE { parse_message_placements(ctx, &opts, what)? } else { ALL_PLACEMENTS };
    let label = {
      let value = field(ctx, &opts, what, "text")?;
      match value.as_string() {
        Some(s) => Label::Static(s.to_string()?),
        None => match value.into_function() {
          Some(f) => Label::Dynamic(Persistent::save(ctx, f)),
          None => return Err(Exception::throw_type(ctx, &format!("{what}: 'text' must be a string or a function"))),
        },
      }
    };
    let icon = field(ctx, &opts, what, "icon")?;
    let icon = if icon.is_undefined() || icon.is_null() {
      None
    } else if let Some(f) = icon.as_function() {
      Some(ActionIcon::Dynamic(Persistent::save(ctx, f.clone())))
    } else {
      match icon_from_value(ctx, icon, what, state.jvm.as_ref()) {
        Ok(Some(Icon { spec, retained_value })) => Some(ActionIcon::Static {
          spec,
          retained: retained_value.map(|value| Persistent::save(ctx, value)),
        }),
        Ok(None) => None,
        Err(e) => {
          if let Label::Dynamic(p) = label {
            let _ = p.restore(ctx);
          }
          return Err(e);
        }
      }
    };
    let visible = match opt_fn(ctx, &opts, what, "visible") {
      Ok(visible) => visible,
      Err(e) => {
        release_label(ctx, label);
        release_icon(ctx, icon);
        return Err(e);
      }
    };
    let callback = match req_fn(ctx, &opts, what, "callback") {
      Ok(callback) => callback,
      Err(e) => {
        release_label(ctx, label);
        release_icon(ctx, icon);
        return Err(e);
      }
    };

    if state.lifecycle.is_unloading() {
      release_label(ctx, label);
      release_icon(ctx, icon);
      return noop_disposer(ctx);
    }

    let Some(registry) = state.registry(kind) else {
      return Err(Exception::throw_type(ctx, &format!("{what}: unknown action kind")));
    };
    let token = registry.alloc();
    let static_text = match &label {
      Label::Static(text) => Some(text.as_str()),
      Label::Dynamic(_) => None,
    };
    let static_icon = match &icon {
      Some(ActionIcon::Static { spec, .. }) => Some(spec.as_str()),
      _ => None,
    };
    let dynamic_fields = (if matches!(&label, Label::Dynamic(_)) { DYNAMIC_TEXT } else { 0 })
      | (if matches!(&icon, Some(ActionIcon::Dynamic(_))) { DYNAMIC_ICON } else { 0 })
      | (if visible.is_some() { DYNAMIC_VISIBLE } else { 0 });
    if let Some(err) =
      state.host.action_register(kind, token, &id, placements, static_text, static_icon, dynamic_fields)
    {
      release_label(ctx, label);
      release_icon(ctx, icon);
      return Err(ctx.throw(crate::api::error::host_error_to_js(ctx, &err)?));
    }
    let def = Rc::new(ActionDef {
      token,
      placements,
      label,
      icon,
      retained_icons: RefCell::new(Vec::new()),
      visible: visible.map(|f| Persistent::save(ctx, f)),
      callback: Persistent::save(ctx, callback),
    });
    if let Some(previous) = registry.insert(token, Some(id), def) {
      state.host.action_unregister(kind, previous.token);
      if let Ok(previous) = Rc::try_unwrap(previous) {
        previous.release(ctx);
      }
    }

    let state = state.clone();
    make_disposer(ctx, move |ctx| {
      let Some(registry) = state.registry(kind) else {
        return;
      };
      if let Some(def) = registry.remove(token) {
        state.host.action_unregister(kind, token);
        if let Ok(def) = Rc::try_unwrap(def) {
          def.release(ctx);
        }
      }
    })
  }
}

fn parse_message_placements<'js>(ctx: &Ctx<'js>, opts: &Object<'js>, what: &str) -> JsResult<i32> {
  let value: Value = opts.get("placements")?;
  if value.is_undefined() {
    return Ok(MESSAGE_PLACEMENT_BUBBLE);
  }
  let array = value
    .as_array()
    .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: 'placements' must be a non-empty array")))?;
  if array.is_empty() {
    return Err(Exception::throw_type(ctx, &format!("{what}: 'placements' must not be empty")));
  }
  let mut placements = 0;
  for value in array.iter::<Value>() {
    let value = value?;
    let placement = value
      .as_string()
      .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: placements must be 'bubble' or 'selection'")))?
      .to_string()?;
    placements |= match placement.as_str() {
      "bubble" => MESSAGE_PLACEMENT_BUBBLE,
      "selection" => MESSAGE_PLACEMENT_SELECTION,
      _ => return Err(Exception::throw_type(ctx, &format!("{what}: unknown placement '{placement}'"))),
    };
  }
  Ok(placements)
}

impl ActionState {
  fn build_context<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    kind: i32,
    surface_json: &str,
  ) -> JsResult<(Object<'js>, i32)> {
    let parsed = json_parse_tl(ctx, surface_json)?;
    let parsed = parsed.as_object().ok_or_else(|| Exception::throw_type(ctx, "action: malformed surface"))?;

    let out = Object::new(ctx.clone())?;
    let account_id: i32 = parsed.get::<_, Option<i32>>("accountId")?.unwrap_or(0);
    out.set("account", crate::api::telegram::account::dispatch_account(ctx, &self.accounts, account_id)?)?;

    if kind != KIND_GLOBAL {
      let dialog_id: f64 = parsed.get::<_, Option<f64>>("dialogId")?.unwrap_or(0.0);
      out.set("dialogId", dialog_id)?;
      if let Some(topic_id) = parsed.get::<_, Option<f64>>("topicId")? {
        out.set("topicId", topic_id)?;
      }
    }
    if kind == KIND_MESSAGE {
      let source: String = parsed.get("source")?;
      let placement = match source.as_str() {
        "bubble" => MESSAGE_PLACEMENT_BUBBLE,
        "selection" => MESSAGE_PLACEMENT_SELECTION,
        _ => return Err(Exception::throw_type(ctx, "action: malformed message source")),
      };
      let messages: Value = parsed.get("messages")?;
      let messages = messages.as_array().ok_or_else(|| Exception::throw_type(ctx, "action: malformed messages"))?;
      let globals = crate::api::Globals::get(ctx)?;
      let message = globals.get_message(ctx)?;
      let wrapped = Array::new(ctx.clone())?;
      for (index, raw) in messages.iter::<Value>().enumerate() {
        let raw = raw?;
        if raw.as_object().is_none() {
          return Err(Exception::throw_type(ctx, "action: malformed message"));
        }
        let wrapper: Object = message.construct((raw,))?;
        wrapped.set(index, wrapper)?;
      }
      out.set("source", source)?;
      out.set("messages", wrapped)?;
      return Ok((out, placement));
    }
    if kind == KIND_EDITOR {
      if self.has_draft_grant() {
        let draft: Value = parsed.get("draft")?;
        out.set("draft", draft)?;
      } else {
        let state2 = self.clone();
        out.prop(
          "draft",
          Accessor::new_get(move |ctx: Ctx<'js>| -> JsResult<Value<'js>> {
            state2.grants.check_grant(&ctx, DRAFT_GRANT, Some(DRAFT_SCOPE), MATCH_EXACT)?;
            Ok(Value::new_undefined(ctx))
          })
          .enumerable()
          .configurable(),
        )?;
      }
      let surface: i64 = parsed.get::<_, Option<f64>>("surface")?.unwrap_or(0.0) as i64;
      for (name, op) in [("replace", EDITOR_REPLACE), ("send", EDITOR_SEND)] {
        let state = self.clone();
        out.set(
          name,
          Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| {
            state.editor_op(&ctx, op, surface, value)
          })?,
        )?;
      }
    }
    Ok((out, ALL_PLACEMENTS))
  }

  fn editor_op<'js>(&self, ctx: &Ctx<'js>, op: i32, surface: i64, value: Value<'js>) -> JsResult<()> {
    let what = if op == EDITOR_REPLACE { "replace" } else { "send" };
    let payload = Object::new(ctx.clone())?;
    if let Some(text) = value.as_string() {
      payload.set("text", text.to_string()?)?;
    } else if let Some(obj) = value.as_object() {
      let text: Value = obj.get("text")?;
      let Some(text) = text.as_string() else {
        return PluginErrorCode::InvalidArgument
          .throw(ctx, &format!("{what}: expected a string or {{ text, entities }}"));
      };
      payload.set("text", text.to_string()?)?;
      let entities: Value = obj.get("entities")?;
      if !entities.is_undefined() && !entities.is_null() {
        if entities.as_array().is_none() {
          return PluginErrorCode::InvalidArgument.throw(ctx, &format!("{what}: entities must be an array"));
        }
        payload.set("entities", entities)?;
      }
    } else {
      return PluginErrorCode::InvalidArgument
        .throw(ctx, &format!("{what}: expected a string or {{ text, entities }}"));
    }
    let json = ctx
      .json_stringify(payload.into_value())?
      .map(|s| s.to_string())
      .transpose()?
      .ok_or_else(|| Exception::throw_message(ctx, &format!("{what}: serialization failed")))?;
    match self.host.action_editor(op, surface, &json) {
      Some(err) => Err(ctx.throw(crate::api::error::host_error_to_js(ctx, &err)?)),
      None => Ok(()),
    }
  }

  fn surface_context<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    kind: i32,
    surface_json: &str,
  ) -> Option<(Object<'js>, i32)> {
    match self.build_context(ctx, kind, surface_json) {
      Ok(obj) => Some(obj),
      Err(rquickjs::Error::Exception) => {
        (self.log)(&format!("{}: bad surface: {}", kind_name(kind), format_exception(ctx)));
        None
      }
      Err(e) => {
        (self.log)(&format!("{}: bad surface: {e:?}", kind_name(kind)));
        None
      }
    }
  }

  fn try_render<'js>(
    &self,
    ctx: &Ctx<'js>,
    kind: i32,
    registry: &Registry<Rc<ActionDef>>,
    defs: Vec<Rc<ActionDef>>,
    context: &Value<'js>,
    placement: i32,
    settings: bool,
  ) -> JsResult<String> {
    let out = Array::new(ctx.clone())?;
    let mut index = 0;
    for def in defs {
      if def.placements & placement == 0 {
        continue;
      }
      if !registry.contains(def.token) {
        continue;
      }
      match self.render_one(ctx, kind, &def, context, settings) {
        Ok(Some((text, icon))) => {
          let row = Object::new(ctx.clone())?;
          row.set("token", def.token)?;
          row.set("text", text)?;
          if let Some(icon) = icon {
            row.set("icon", icon)?;
          }
          out.set(index, row)?;
          index += 1;
        }
        Ok(None) => {}
        Err(rquickjs::Error::Exception) => {
          (self.log)(&format!("{} row threw while rendering: {}", kind_name(kind), format_exception(ctx)));
        }
        Err(e) => (self.log)(&format!("{} row failed to render: {e:?}", kind_name(kind))),
      }
    }
    ctx
      .json_stringify(out.into_value())?
      .map(|s| s.to_string())
      .transpose()?
      .ok_or_else(|| Exception::throw_message(ctx, "render: serialization produced no output"))
  }

  fn render_one<'js>(
    &self,
    ctx: &Ctx<'js>,
    kind: i32,
    def: &Rc<ActionDef>,
    context: &Value<'js>,
    settings: bool,
  ) -> JsResult<Option<(String, Option<String>)>> {
    if !settings {
      if let Some(visible) = def.visible.as_ref() {
        let visible = visible.clone().restore(ctx)?;
        let verdict: Value = visible.call((context.clone(),))?;
        if !verdict.as_bool().unwrap_or(false) {
          return Ok(None);
        }
      }
    }
    let text = match &def.label {
      Label::Static(text) => text.clone(),
      Label::Dynamic(f) => {
        let f = f.clone().restore(ctx)?;
        let text: rquickjs::Coerced<String> = f.call((context.clone(),))?;
        text.0
      }
    };
    let icon = match def.icon.as_ref() {
      None => None,
      Some(ActionIcon::Static { spec, .. }) => Some(spec.clone()),
      Some(ActionIcon::Dynamic(f)) => {
        let f = f.clone().restore(ctx)?;
        let value: Value = f.call((context.clone(),))?;
        let Some(Icon { spec, retained_value }) = icon_from_value(ctx, value, kind_name(kind), self.jvm.as_ref())?
        else {
          return Ok(Some((text, None)));
        };
        if let Some(value) = retained_value {
          let mut retained = def.retained_icons.borrow_mut();
          if retained.len() == 4 {
            let _ = retained.remove(0).restore(ctx);
          }
          retained.push(Persistent::save(ctx, value));
        }
        Some(spec)
      }
    };
    Ok(Some((text, icon)))
  }
}

impl ActionState {
  pub fn render(
    self: &Rc<Self>,
    rt: &Runtime,
    context: &rquickjs::Context,
    kind: i32,
    surface_json: &str,
  ) -> Option<String> {
    let state = self;
    let out = context.with(|ctx| {
      let Some(registry) = state.registry(kind) else {
        (state.log)(&format!("render: unknown action kind {kind}"));
        return None;
      };
      let defs = registry.values();
      if defs.is_empty() {
        return Some("[]".to_string());
      }
      let settings = surface_json == "null";
      let (context, placement) = if settings {
        (Value::new_null(ctx.clone()), ALL_PLACEMENTS)
      } else {
        let (context, placement) = state.surface_context(&ctx, kind, surface_json)?;
        (context.into_value(), placement)
      };
      match state.try_render(&ctx, kind, registry, defs, &context, placement, settings) {
        Ok(json) => Some(json),
        Err(rquickjs::Error::Exception) => {
          (state.log)(&crate::fault(format_args!("{}: render failed: {}", kind_name(kind), format_exception(&ctx))));
          None
        }
        Err(e) => {
          (state.log)(&format!("{}: render failed: {e:?}", kind_name(kind)));
          None
        }
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
    out
  }

  pub fn dispatch(
    self: &Rc<Self>,
    rt: &Runtime,
    context: &rquickjs::Context,
    kind: i32,
    token: u32,
    surface_json: &str,
  ) {
    let state = self;
    if state.lifecycle.is_unloading() {
      return;
    }
    context.with(|ctx| {
      let Some(registry) = state.registry(kind) else {
        return;
      };
      let Some(def) = registry.get(token) else {
        return;
      };
      let callback = match def.callback.clone().restore(&ctx) {
        Ok(f) => f,
        Err(e) => {
          (state.log)(&format!("{}: failed to restore callback: {e:?}", kind_name(kind)));
          return;
        }
      };
      let Some((context_obj, placement)) = state.surface_context(&ctx, kind, surface_json) else {
        return;
      };
      if def.placements & placement == 0 {
        return;
      }
      match callback.call::<_, Value>((context_obj,)) {
        Ok(_) => {}
        Err(rquickjs::Error::Exception) => {
          (state.log)(&crate::fault(format_args!("{} callback threw: {}", kind_name(kind), format_exception(&ctx))));
        }
        Err(e) => (state.log)(&format!("{} callback failed: {e:?}", kind_name(kind))),
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

}

impl Dispose for ActionState {
  fn dispose(&self, context: &rquickjs::Context) {
    let state = self;
    context.with(|ctx| {
      for registry in &state.kinds {
        for def in registry.remove_matching(|_| true) {
          if let Ok(def) = Rc::try_unwrap(def) {
            def.release(&ctx);
          }
        }
      }
    });
  }
}

#[cfg(test)]
#[path = "actions_tests.rs"]
mod tests;

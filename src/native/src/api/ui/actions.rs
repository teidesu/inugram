use std::rc::Rc;

use rquickjs::object::Accessor;
use rquickjs::{Array, Ctx, Exception, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::api::error::throw_plugin_error;
use crate::api::telegram::account::AccountState;
use crate::api::telegram::rpc::{format_exception, pump_jobs};
use crate::sandbox::grants::{check_grant, GrantHost, MATCH_EXACT};
use crate::sandbox::registry::{make_disposer, noop_disposer, Lifecycle, Registry, Token};
use crate::utils::arguments::{field, opt_fn, req_fn, req_str};

pub const KIND_GLOBAL: i32 = 0;
pub const KIND_CHAT: i32 = 1;
pub const KIND_MESSAGE: i32 = 2;
pub const KIND_PROFILE: i32 = 3;
pub const KIND_EDITOR: i32 = 4;

const DRAFT_GRANT: &str = "account.read";
const DRAFT_SCOPE: &str = "draft";

fn has_draft_grant(state: &Rc<ActionState>) -> bool {
    state.grants.is_granted(DRAFT_GRANT, Some(DRAFT_SCOPE), MATCH_EXACT)
}

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
    fn action_register(&self, kind: i32, token: u32, id: &str) -> Option<String>;
    fn action_unregister(&self, kind: i32, token: u32);
    fn action_editor(&self, op: i32, surface: i64, payload_json: &str) -> Option<String>;
}

enum Label {
    Static(String),
    Dynamic(Persistent<Function<'static>>),
}

struct ActionDef {
    token: Token,
    label: Label,
    visible: Option<Persistent<Function<'static>>>,
    callback: Persistent<Function<'static>>,
}

fn release_def(ctx: &Ctx<'_>, def: ActionDef) {
    if let Label::Dynamic(p) = def.label {
        let _ = p.restore(ctx);
    }
    if let Some(p) = def.visible {
        let _ = p.restore(ctx);
    }
    let _ = def.callback.restore(ctx);
}

pub struct ActionState {
    host: Rc<dyn ActionHost>,
    lifecycle: Rc<Lifecycle>,
    log: crate::Log,
    accounts: Option<Rc<AccountState>>,
    grants: Rc<dyn GrantHost>,
    kinds: Vec<Registry<Rc<ActionDef>>>,
}

impl ActionState {
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
    log: crate::Log,
    inu: &Object<'js>,
) -> JsResult<Rc<ActionState>> {
    let state = Rc::new(ActionState {
        host,
        lifecycle,
        log,
        accounts,
        grants,
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
        let f =
            Function::new(ctx.clone(), move |ctx: Ctx<'js>, opts: Object<'js>| js_register(&ctx, &state, kind, opts))?;
        inu.set(name, f)?;
    }
    Ok(state)
}

fn js_register<'js>(ctx: &Ctx<'js>, state: &Rc<ActionState>, kind: i32, opts: Object<'js>) -> JsResult<Function<'js>> {
    let what = kind_name(kind);
    let id = req_str(ctx, &opts, what, "id")?;
    let label = {
        let value = field(ctx, &opts, what, "text")?;
        match value.as_string() {
            Some(s) => Label::Static(s.to_string()?),
            None => match value.into_function() {
                Some(f) => Label::Dynamic(Persistent::save(ctx, f)),
                None => {
                    return Err(Exception::throw_type(ctx, &format!("{what}: 'text' must be a string or a function")))
                }
            },
        }
    };
    let icon = field(ctx, &opts, what, "icon")?;
    if !icon.is_undefined() && !icon.is_null() {
        if let Label::Dynamic(p) = label {
            let _ = p.restore(ctx);
        }
        return throw_plugin_error(
            ctx,
            "unsupported",
            &format!("{what}: an action row does not carry an icon yet"),
            None,
            None,
            None,
        );
    }
    let visible = opt_fn(ctx, &opts, what, "visible")?;
    let callback = req_fn(ctx, &opts, what, "callback")?;

    if state.lifecycle.is_unloading() {
        if let Label::Dynamic(p) = label {
            let _ = p.restore(ctx);
        }
        return noop_disposer(ctx);
    }

    let Some(registry) = state.registry(kind) else {
        return Err(Exception::throw_type(ctx, &format!("{what}: unknown action kind")));
    };
    let token = registry.alloc();
    if let Some(err) = state.host.action_register(kind, token, &id) {
        if let Label::Dynamic(p) = label {
            let _ = p.restore(ctx);
        }
        return Err(ctx.throw(crate::api::error::host_error_to_js(ctx, &err)?));
    }
    let def = Rc::new(ActionDef {
        token,
        label,
        visible: visible.map(|f| Persistent::save(ctx, f)),
        callback: Persistent::save(ctx, callback),
    });
    if let Some(previous) = registry.insert(token, Some(id), def) {
        state.host.action_unregister(kind, previous.token);
        if let Ok(previous) = Rc::try_unwrap(previous) {
            release_def(ctx, previous);
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
                release_def(ctx, def);
            }
        }
    })
}

fn build_context<'js>(ctx: &Ctx<'js>, state: &Rc<ActionState>, kind: i32, surface_json: &str) -> JsResult<Object<'js>> {
    let parsed: Value = ctx.json_parse(surface_json)?;
    let parsed = parsed.as_object().ok_or_else(|| Exception::throw_type(ctx, "action: malformed surface"))?;

    let out = Object::new(ctx.clone())?;
    let account_id: i32 = parsed.get::<_, Option<i32>>("accountId")?.unwrap_or(0);
    out.set("account", crate::api::telegram::account::dispatch_account(ctx, &state.accounts, account_id)?)?;

    if kind != KIND_GLOBAL {
        let dialog_id: f64 = parsed.get::<_, Option<f64>>("dialogId")?.unwrap_or(0.0);
        out.set("dialogId", dialog_id)?;
        if let Some(topic_id) = parsed.get::<_, Option<f64>>("topicId")? {
            out.set("topicId", topic_id)?;
        }
    }
    if kind == KIND_MESSAGE {
        let ids: Value = parsed.get("messageIds")?;
        let ids = ids.as_array().ok_or_else(|| Exception::throw_type(ctx, "action: malformed messageIds"))?;
        out.set("messageIds", ids.clone())?;
    }
    if kind == KIND_EDITOR {
        if has_draft_grant(state) {
            let draft: Value = parsed.get("draft")?;
            out.set("draft", draft)?;
        } else {
            let state2 = state.clone();
            out.prop(
                "draft",
                Accessor::new_get(move |ctx: Ctx<'js>| -> JsResult<Value<'js>> {
                    check_grant(&ctx, &state2.grants, DRAFT_GRANT, Some(DRAFT_SCOPE), MATCH_EXACT)?;
                    Ok(Value::new_undefined(ctx))
                })
                .enumerable()
                .configurable(),
            )?;
        }
        let surface: i64 = parsed.get::<_, Option<f64>>("surface")?.unwrap_or(0.0) as i64;
        for (name, op) in [("replace", EDITOR_REPLACE), ("send", EDITOR_SEND)] {
            let state = state.clone();
            let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| {
                editor_op(&ctx, &state, op, surface, value)
            })?;
            out.set(name, f)?;
        }
    }
    Ok(out)
}

fn editor_op<'js>(ctx: &Ctx<'js>, state: &Rc<ActionState>, op: i32, surface: i64, value: Value<'js>) -> JsResult<()> {
    let what = if op == EDITOR_REPLACE { "replace" } else { "send" };
    let payload = Object::new(ctx.clone())?;
    if let Some(text) = value.as_string() {
        payload.set("text", text.to_string()?)?;
    } else if let Some(obj) = value.as_object() {
        let text: Value = obj.get("text")?;
        let Some(text) = text.as_string() else {
            return throw_plugin_error(
                ctx,
                "invalid-argument",
                &format!("{what}: expected a string or {{ text, entities }}"),
                None,
                None,
                None,
            );
        };
        payload.set("text", text.to_string()?)?;
        let entities: Value = obj.get("entities")?;
        if !entities.is_undefined() && !entities.is_null() {
            if entities.as_array().is_none() {
                return throw_plugin_error(
                    ctx,
                    "invalid-argument",
                    &format!("{what}: entities must be an array"),
                    None,
                    None,
                    None,
                );
            }
            payload.set("entities", entities)?;
        }
    } else {
        return throw_plugin_error(
            ctx,
            "invalid-argument",
            &format!("{what}: expected a string or {{ text, entities }}"),
            None,
            None,
            None,
        );
    }
    let json = ctx
        .json_stringify(payload.into_value())?
        .map(|s| s.to_string())
        .transpose()?
        .ok_or_else(|| Exception::throw_message(ctx, &format!("{what}: serialization failed")))?;
    match state.host.action_editor(op, surface, &json) {
        Some(err) => Err(Exception::throw_message(ctx, &err)),
        None => Ok(()),
    }
}

pub fn render_actions(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<ActionState>,
    kind: i32,
    surface_json: &str,
) -> Option<String> {
    let out = context.with(|ctx| {
        let Some(registry) = state.registry(kind) else {
            (state.log)(&format!("render: unknown action kind {kind}"));
            return None;
        };
        let defs = registry.values();
        if defs.is_empty() {
            return Some("[]".to_string());
        }
        let context_obj = surface_context(&ctx, state, kind, surface_json)?;
        match try_render(&ctx, state, kind, registry, defs, &context_obj) {
            Ok(json) => Some(json),
            Err(rquickjs::Error::Exception) => {
                (state.log)(&crate::fault(format_args!(
                    "{}: render failed: {}",
                    kind_name(kind),
                    format_exception(&ctx)
                )));
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

fn surface_context<'js>(ctx: &Ctx<'js>, state: &Rc<ActionState>, kind: i32, surface_json: &str) -> Option<Object<'js>> {
    match build_context(ctx, state, kind, surface_json) {
        Ok(obj) => Some(obj),
        Err(rquickjs::Error::Exception) => {
            (state.log)(&format!("{}: bad surface: {}", kind_name(kind), format_exception(ctx)));
            None
        }
        Err(e) => {
            (state.log)(&format!("{}: bad surface: {e:?}", kind_name(kind)));
            None
        }
    }
}

fn try_render<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<ActionState>,
    kind: i32,
    registry: &Registry<Rc<ActionDef>>,
    defs: Vec<Rc<ActionDef>>,
    context_obj: &Object<'js>,
) -> JsResult<String> {
    let out = Array::new(ctx.clone())?;
    let mut index = 0;
    for def in defs {
        if !registry.contains(def.token) {
            continue;
        }
        match render_one(ctx, &def, context_obj) {
            Ok(Some(text)) => {
                let row = Object::new(ctx.clone())?;
                row.set("token", def.token)?;
                row.set("text", text)?;
                out.set(index, row)?;
                index += 1;
            }
            Ok(None) => {}
            Err(rquickjs::Error::Exception) => {
                (state.log)(&format!("{} row threw while rendering: {}", kind_name(kind), format_exception(ctx)));
            }
            Err(e) => (state.log)(&format!("{} row failed to render: {e:?}", kind_name(kind))),
        }
    }
    ctx.json_stringify(out.into_value())?
        .map(|s| s.to_string())
        .transpose()?
        .ok_or_else(|| Exception::throw_message(ctx, "render: serialization produced no output"))
}

fn render_one<'js>(ctx: &Ctx<'js>, def: &Rc<ActionDef>, context_obj: &Object<'js>) -> JsResult<Option<String>> {
    if let Some(visible) = def.visible.as_ref() {
        let visible = visible.clone().restore(ctx)?;
        let verdict: Value = visible.call((context_obj.clone(),))?;
        if !verdict.as_bool().unwrap_or(false) {
            return Ok(None);
        }
    }
    let text = match &def.label {
        Label::Static(text) => text.clone(),
        Label::Dynamic(f) => {
            let f = f.clone().restore(ctx)?;
            let text: rquickjs::Coerced<String> = f.call((context_obj.clone(),))?;
            text.0
        }
    };
    Ok(Some(text))
}

pub fn dispatch_action(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<ActionState>,
    kind: i32,
    token: u32,
    surface_json: &str,
) {
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
        let Some(context_obj) = surface_context(&ctx, state, kind, surface_json) else {
            return;
        };
        match callback.call::<_, Value>((context_obj,)) {
            Ok(_) => {}
            Err(rquickjs::Error::Exception) => {
                (state.log)(&crate::fault(format_args!(
                    "{} callback threw: {}",
                    kind_name(kind),
                    format_exception(&ctx)
                )));
            }
            Err(e) => (state.log)(&format!("{} callback failed: {e:?}", kind_name(kind))),
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

pub fn dispose(context: &rquickjs::Context, state: &Rc<ActionState>) {
    context.with(|ctx| {
        for registry in &state.kinds {
            for def in registry.remove_matching(|_| true) {
                if let Ok(def) = Rc::try_unwrap(def) {
                    release_def(&ctx, def);
                }
            }
        }
    });
}

#[cfg(test)]
#[path = "actions_tests.rs"]
mod tests;

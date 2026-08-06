//! `inu.registerAction` and its four narrowings - rows a plugin contributes to menus the app owns.
//!
//! Android ui objects live on the ui thread and an engine may only be entered from `globalQueue`,
//! so nothing here can be answered while a menu is being built. A menu is two crossings: the host
//! asks for a render ([`render_actions`]) and a tap comes back later as [`dispatch_action`]. What
//! is *registered* is known to the host synchronously, so it can size a menu without asking anyone;
//! only `text`/`visible` need the engine, which is what bounds the "park the menu for one hop"
//! pattern in `PluginActions`.
//!
//! Tokens are [`Registry`] tokens, so `registry.rs`'s `Disposer` rules hold here for free - most
//! visibly the keyed one, `ActionOptions.id` being required.

use std::rc::Rc;

use rquickjs::object::Accessor;
use rquickjs::{Array, Ctx, Exception, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::api::{json_parse, json_stringify};
use crate::engine::error::{check_grant, get_or_create_inu, throw_plugin_error, GrantHost, MATCH_EXACT};
use crate::engine::registry::{make_disposer, noop_disposer, Lifecycle, Registry, Token};
use crate::tg::account::AccountState;
use crate::tg::rpc::{format_exception, pump_jobs};

/// keep in sync with Kotlin `PluginActions.KIND_*`
pub const KIND_GLOBAL: i32 = 0;
pub const KIND_CHAT: i32 = 1;
pub const KIND_MESSAGE: i32 = 2;
pub const KIND_PROFILE: i32 = 3;
pub const KIND_EDITOR: i32 = 4;

/// the grant `MessageEditorActionContext.draft` is behind, which is `getDraft`'s own
const DRAFT_GRANT: &str = "account.read";
const DRAFT_SCOPE: &str = "draft";

fn check_draft_grant(state: &Rc<ActionState>) -> Result<(), ()> {
    if state.grants.is_granted(DRAFT_GRANT, Some(DRAFT_SCOPE), MATCH_EXACT) {
        return Ok(());
    }
    Err(())
}
const KIND_COUNT: usize = 5;

/// keep in sync with Kotlin `PluginActions.EDITOR_OP_*`
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

/// stand-in for the action half of the Kotlin `QuickJs.ApiListener`; `Some(msg)` == error
pub trait ActionHost {
    /// a row was registered. the host tracks these to size a menu without entering the engine, so
    /// it needs the id as well as the token: a keyed replacement takes the place its predecessor
    /// held, which is also what keeps a plugin at the row cap able to update its own rows.
    /// `Some(wire)` is a refusal, and it names its own code: the row cap is a `P` wire carrying
    /// `quota-exceeded`, while a JNI-level failure is a bare message and stays an ordinary error.
    fn action_register(&self, kind: i32, token: u32, id: &str) -> Option<String>;
    fn action_unregister(&self, kind: i32, token: u32);
    /// `MessageEditorActionContext.replace`/`send`; `surface` names the composer the dispatch came
    /// from, since the plugin may still be holding the ctx after the menu is gone
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
    /// only `MessageEditorActionContext.draft` is gated, and it is gated where every other read of
    /// a draft is: what is in the composer is the same app state `getDraft` hands over
    grants: Rc<dyn GrantHost>,
    kinds: Vec<Registry<Rc<ActionDef>>>,
}

impl ActionState {
    fn registry(&self, kind: i32) -> Option<&Registry<Rc<ActionDef>>> {
        self.kinds.get(kind as usize)
    }
}

fn field<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Value<'js>> {
    obj.get(key).map_err(|_| Exception::throw_type(ctx, &format!("{what}: cannot read '{key}'")))
}

fn req_str<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<String> {
    let v = field(ctx, obj, what, key)?;
    match v.as_string() {
        Some(s) => Ok(s.to_string()?),
        None => Err(Exception::throw_type(ctx, &format!("{what}: '{key}' must be a string"))),
    }
}

fn req_fn<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Function<'js>> {
    let v = field(ctx, obj, what, key)?;
    v.into_function().ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a function")))
}

fn opt_fn<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Option<Function<'js>>> {
    let v = field(ctx, obj, what, key)?;
    if v.is_undefined() || v.is_null() {
        return Ok(None);
    }
    v.into_function()
        .map(Some)
        .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a function")))
}

pub fn install_actions<'js>(
    ctx: &Ctx<'js>,
    host: Rc<dyn ActionHost>,
    lifecycle: Rc<Lifecycle>,
    accounts: Option<Rc<AccountState>>,
    grants: Rc<dyn GrantHost>,
    log: crate::Log,
) -> JsResult<Rc<ActionState>> {
    let state = Rc::new(ActionState {
        host,
        lifecycle,
        log,
        accounts,
        grants,
        kinds: (0..KIND_COUNT).map(|_| Registry::default()).collect(),
    });

    let inu = get_or_create_inu(ctx)?;
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
    // the menus these rows land in are the app's own and every one of them draws a fixed glyph for
    // a plugin row today; accepting an icon and dropping it would be a promise the row cannot keep
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

    // after the validation, so a malformed registration throws rather than silently doing nothing,
    // and before anything is inserted, so nothing has to be undone
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
        // the row cap arrives as a `P` wire naming its own code; anything else this upcall can
        // answer is a JNI-level failure, which is the host's bad day and not a quota
        return Err(ctx.throw(crate::engine::error::host_error_to_js(ctx, &err)?));
    }
    let def = Rc::new(ActionDef {
        token,
        label,
        visible: visible.map(|f| Persistent::save(ctx, f)),
        callback: Persistent::save(ctx, callback),
    });
    if let Some(previous) = registry.insert(token, Some(id), def) {
        // the host tracks rows by token, so a replacement has to retire the one it displaced or the
        // menu would keep sizing itself for a row nothing can render
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

/// builds the ctx object from what the host knows about the surface. `account` is minted the same
/// way every other dispatch mints one, so an `Account` handed to an action is the same handle an
/// `onUpdate` payload carries.
fn build_context<'js>(ctx: &Ctx<'js>, state: &Rc<ActionState>, kind: i32, surface_json: &str) -> JsResult<Object<'js>> {
    let parsed: Value = json_parse(ctx, surface_json)?;
    let parsed = parsed.as_object().ok_or_else(|| Exception::throw_type(ctx, "action: malformed surface"))?;

    let out = Object::new(ctx.clone())?;
    let account_id: i32 = parsed.get::<_, Option<i32>>("accountId")?.unwrap_or(0);
    out.set("account", crate::tg::account::dispatch_account(ctx, &state.accounts, account_id)?)?;

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
        // the composer's text is the same app state `getDraft` reads, so it costs the same scope.
        // Gated per context rather than at registration, or a row that only wants to `send` would
        // need a read grant to exist at all; the refusal is a throwing accessor rather than an
        // absent member so a plugin is told which grant it is short of.
        if check_draft_grant(state).is_ok() {
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

/// `InputText`: a bare string is unformatted text, exactly as the write surface reads one. entities
/// stay whatever the plugin handed over - a live TL view included, which stringifies through its
/// own `toJSON`.
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
    let json = json_stringify(ctx, payload.into_value())?
        .ok_or_else(|| Exception::throw_message(ctx, &format!("{what}: serialization failed")))?;
    match state.host.action_editor(op, surface, &json) {
        Some(err) => Err(Exception::throw_message(ctx, &err)),
        None => Ok(()),
    }
}

/// evaluates `visible` and `text` for every registered row of [`kind`] and answers the rows the
/// host should draw, as `[{token, text}]`. `None` means the engine could not answer at all, which
/// the host reads as "this plugin contributes nothing to this menu".
///
/// A row whose `visible`/`text` threw is dropped and the throw is an ordinary error, *not* a fault:
/// a render predicate that fails on one chat must not switch off every other feature the plugin
/// provides. An action the user actually clicked is the other way round, and [`dispatch_action`]
/// faults for it.
pub fn render_actions(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<ActionState>,
    kind: i32,
    surface_json: &str,
) -> Option<String> {
    let out = context.with(|ctx| match try_render(&ctx, state, kind, surface_json) {
        Ok(json) => Some(json),
        Err(rquickjs::Error::Exception) => {
            (state.log)(&crate::fault(format_args!("{}: render failed: {}", kind_name(kind), format_exception(&ctx))));
            None
        }
        Err(e) => {
            (state.log)(&format!("{}: render failed: {e:?}", kind_name(kind)));
            None
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
    out
}

fn try_render<'js>(ctx: &Ctx<'js>, state: &Rc<ActionState>, kind: i32, surface_json: &str) -> JsResult<String> {
    let Some(registry) = state.registry(kind) else {
        return Err(Exception::throw_type(ctx, "render: unknown action kind"));
    };
    let defs = registry.values();
    let out = Array::new(ctx.clone())?;
    if defs.is_empty() {
        return json_stringify(ctx, out.into_value())?
            .ok_or_else(|| Exception::throw_message(ctx, "render: serialization produced no output"));
    }
    // one ctx for the whole render: the rows of one menu describe one surface, and minting an
    // `Account` per row would cost a host crossing per row for the same answer
    let context_obj = build_context(ctx, state, kind, surface_json)?;

    let mut index = 0;
    for def in defs {
        // a row disposed by an earlier row's `visible` is not drawn: the walk holds a snapshot, so
        // this is the one liveness question a snapshot cannot answer on its own
        if !registry.contains(def.token) {
            continue;
        }
        match render_one(ctx, &def, &context_obj) {
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
    json_stringify(ctx, out.into_value())?
        .ok_or_else(|| Exception::throw_message(ctx, "render: serialization produced no output"))
}

/// `None` == the row's `visible` said no
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

/// the user tapped the row [`token`] names. A token the registry no longer holds is a no-op: the
/// row was drawn from a render that is now stale (the plugin disposed it, or replaced its id), and
/// the menu it is in cannot be un-drawn.
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
        let context_obj = match build_context(&ctx, state, kind, surface_json) {
            Ok(obj) => obj,
            Err(rquickjs::Error::Exception) => {
                (state.log)(&format!("{}: bad surface: {}", kind_name(kind), format_exception(&ctx)));
                return;
            }
            Err(e) => {
                (state.log)(&format!("{}: bad surface: {e:?}", kind_name(kind)));
                return;
            }
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

/// releases every `Persistent` GC root this state still owns - same contract as [`crate::tg::rpc::dispose`]
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

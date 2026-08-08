//! `inu.kv` / `inu.ui.toast` / `inu.ui.dialog` / `inu.onUnload` / `inu.openUrl` / `inu.clipboard`.
//! JNI-free behind [`ApiHost`]; `lib.rs` wires the JNI-backed host.
//!
//! `kv` upcalls answer a single tagged wire string reusing [`crate::tl::proxy`]'s scalar tags.
//! [`ApiHost::clipboard_read`] is the one upcall here that is **not** tagged and cannot be: it
//! carries whatever the user last copied, so a leading `E` is a plain clipboard far more often than
//! it is an error wire. It answers text or the empty string, and so does a host that cannot read
//! the clipboard.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use rquickjs::{Ctx, Exception, Function, Object, Result as JsResult, Runtime, Value};

use crate::engine::error::{self, check_grant, get_or_create_inu, GrantHost, MATCH_EXACT};
use crate::engine::registry::{make_disposer, noop_disposer, CallbackRegistry, Lifecycle};
use crate::tg::rpc::{format_exception, pump_jobs, PendingSettle};

pub const KV_GET: i32 = 0;
pub const KV_SET: i32 = 1;
pub const KV_DEL: i32 = 2;
pub const KV_KEYS: i32 = 3;
pub const KV_CLEAR: i32 = 4;
pub const KV_GET_ALL: i32 = 5;
pub const KV_INSERT_ALL: i32 = 6;
pub const KV_HAS: i32 = 7;
pub const KV_USAGE: i32 = 8;

/// stand-in for the Kotlin `QuickJs.ApiListener` interface
pub trait ApiHost {
    /// tagged wire string: `S`/`N`/`J`/`E`/`P` (see module doc). unused key/value args are ""
    fn kv(&self, op: i32, key: &str, value: &str) -> String;
    fn ui_toast(&self, text: &str);
    /// `None` == shown (settled later via [`resolve_dialog`]), `Some(msg)` == immediate error
    fn ui_dialog(&self, request_id: i64, options_json: &str) -> Option<String>;
    /// `inu.ui.chooser(options)`; same contract as [`ApiHost::ui_dialog`], settled by
    /// [`resolve_chooser`]. `options_json` is `{title?, multiple, items: [{text, subtitle?,
    /// danger}], selected: [index...]}` - `selected` is a list in both modes, so the host renders
    /// one shape and `multiple` alone decides what comes back
    fn ui_chooser(&self, request_id: i64, options_json: &str) -> Option<String>;
    /// already screened by [`screen_external_url`]; fire-and-forget, there being no ui to fail into
    fn open_url(&self, url: &str);
    /// the clipboard's plain text, or "" for anything this cannot answer. never a wire (module doc)
    fn clipboard_read(&self) -> String;
    fn clipboard_write(&self, text: &str);
}

/// What `inu.openUrl` may hand to the system, and nothing else: [`crate::engine::url`]'s screen,
/// which `fetch` runs too, since both end up handing the string to something that re-parses it.
pub(crate) fn screen_external_url(url: &str) -> Result<(), String> {
    crate::engine::url::parse_http_url("openUrl", url).map(|_| ())
}

pub struct ApiState {
    host: Rc<dyn ApiHost>,
    grants: Rc<dyn GrantHost>,
    lifecycle: Rc<Lifecycle>,
    pub(crate) log: crate::Log,
    next_request_id: crate::engine::registry::RequestIds,
    pending_dialogs: RefCell<HashMap<i64, PendingSettle>>,
    /// a chooser remembers the mode it was opened in: the host answers with a list either way, and
    /// what a single-select promise resolves to is a number
    pending_choosers: RefCell<HashMap<i64, (PendingSettle, bool)>>,
    unload_fns: CallbackRegistry,
    visibility_fns: CallbackRegistry,
    /// what the plugin was last told; a fresh engine starts foreground and the host corrects it
    /// before the plugin's own code runs
    visible: Cell<bool>,
}

/// `JSON` is an ordinary writable global and plugin code shares this context, so reading
/// `parse`/`stringify` off it would hand a plugin every host wire *before* the grant gates that
/// rebuild the exposed object from it run. `JS_ParseJSON`/`JS_JSONStringify` cannot be interposed.
pub(crate) fn json_parse<'js>(ctx: &Ctx<'js>, json: &str) -> JsResult<Value<'js>> {
    ctx.json_parse(json)
}

pub(crate) fn json_stringify<'js>(ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<Option<String>> {
    Ok(match ctx.json_stringify(value)? {
        Some(s) => Some(s.to_string()?),
        None => None,
    })
}

/// decodes a [`ApiHost::kv`] result into a JS value, throwing on an error tag
fn kv_result_to_js<'js>(ctx: &Ctx<'js>, wire: &str) -> JsResult<Value<'js>> {
    use rquickjs::IntoJs;
    if let Some(built) = error::wire_error_to_js(ctx, wire) {
        return Err(ctx.throw(built?));
    }
    let Some(tag) = wire.chars().next() else {
        return Err(Exception::throw_message(ctx, "kv: empty host response"));
    };
    let payload = &wire[tag.len_utf8()..];
    match tag {
        'N' => Ok(Value::new_null(ctx.clone())),
        'S' => payload.into_js(ctx),
        'J' => json_parse(ctx, payload),
        _ => Err(Exception::throw_message(ctx, &format!("kv: malformed host response tag '{tag}'"))),
    }
}

fn kv_call<'js>(ctx: &Ctx<'js>, state: &Rc<ApiState>, op: i32, key: &str, value: &str) -> JsResult<Value<'js>> {
    check_grant(ctx, &state.grants, "kv", None, MATCH_EXACT)?;
    let wire = state.host.kv(op, key, value);
    kv_result_to_js(ctx, &wire)
}

pub fn install_api<'js>(
    ctx: &Ctx<'js>,
    host: Rc<dyn ApiHost>,
    grants: Rc<dyn GrantHost>,
    lifecycle: Rc<Lifecycle>,
    log: crate::Log,
) -> JsResult<Rc<ApiState>> {
    let state = Rc::new(ApiState {
        host,
        grants,
        lifecycle,
        log,
        next_request_id: crate::engine::registry::RequestIds::default(),
        pending_dialogs: RefCell::new(HashMap::new()),
        pending_choosers: RefCell::new(HashMap::new()),
        unload_fns: CallbackRegistry::default(),
        visibility_fns: CallbackRegistry::default(),
        visible: Cell::new(true),
    });

    let inu = get_or_create_inu(ctx)?;

    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, cb: Function<'js>| -> JsResult<Function<'js>> {
            if state2.lifecycle.is_unloading() {
                return noop_disposer(&ctx);
            }
            let token = state2.unload_fns.alloc();
            state2.unload_fns.register(&ctx, token, None, cb);
            let state = state2.clone();
            make_disposer(&ctx, move |ctx| {
                state.unload_fns.dispose(ctx, token);
            })
        })?;
        inu.set("onUnload", f)?;
    }

    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, cb: Function<'js>| -> JsResult<Function<'js>> {
            if state2.lifecycle.is_unloading() {
                return noop_disposer(&ctx);
            }
            check_grant(&ctx, &state2.grants, "onAppVisibilityChange", None, MATCH_EXACT)?;
            let token = state2.visibility_fns.alloc();
            state2.visibility_fns.register(&ctx, token, None, cb);
            let state = state2.clone();
            make_disposer(&ctx, move |ctx| {
                state.visibility_fns.dispose(ctx, token);
            })
        })?;
        inu.set("onAppVisibilityChange", f)?;
    }

    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, url: String| -> JsResult<()> {
            check_grant(&ctx, &state2.grants, "openUrl", None, MATCH_EXACT)?;
            if let Err(why) = screen_external_url(&url) {
                return error::throw_plugin_error(&ctx, "invalid-argument", &why, None, None, None);
            }
            state2.host.open_url(&url);
            Ok(())
        })?;
        inu.set("openUrl", f)?;
    }

    let clipboard = Object::new(ctx.clone())?;
    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<String> {
            check_grant(&ctx, &state2.grants, "clipboard.read", None, MATCH_EXACT)?;
            Ok(state2.host.clipboard_read())
        })?;
        clipboard.set("read", f)?;
    }
    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, text: rquickjs::Coerced<String>| -> JsResult<()> {
            check_grant(&ctx, &state2.grants, "clipboard.write", None, MATCH_EXACT)?;
            state2.host.clipboard_write(&text.0);
            Ok(())
        })?;
        clipboard.set("write", f)?;
    }
    inu.set("clipboard", clipboard)?;

    let ui = Object::new(ctx.clone())?;
    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |text: rquickjs::Coerced<String>| {
            state2.host.ui_toast(&text.0);
        })?;
        ui.set("toast", f)?;
    }
    {
        let state2 = state.clone();
        let f =
            Function::new(ctx.clone(), move |ctx: Ctx<'js>, options: Value<'js>| js_ui_dialog(&ctx, &state2, options))?;
        ui.set("dialog", f)?;
    }
    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, options: Object<'js>| {
            js_ui_chooser(&ctx, &state2, options)
        })?;
        ui.set("chooser", f)?;
    }
    inu.set("ui", ui)?;

    let kv = Object::new(ctx.clone())?;
    for (name, op) in [("get", KV_GET), ("del", KV_DEL), ("has", KV_HAS)] {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, key: String| kv_call(&ctx, &state2, op, &key, ""))?;
        kv.set(name, f)?;
    }
    for (name, op) in [("keys", KV_KEYS), ("clear", KV_CLEAR), ("getAll", KV_GET_ALL), ("usage", KV_USAGE)] {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>| kv_call(&ctx, &state2, op, "", ""))?;
        kv.set(name, f)?;
    }
    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, key: String, value: String| {
            kv_call(&ctx, &state2, KV_SET, &key, &value)
        })?;
        kv.set("set", f)?;
    }
    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, values: Value<'js>| -> JsResult<Value<'js>> {
            if !values.is_object() {
                return Err(Exception::throw_type(&ctx, "kv.insertAll: expected an object"));
            }
            let json = json_stringify(&ctx, values)?
                .ok_or_else(|| Exception::throw_type(&ctx, "kv.insertAll: expected an object"))?;
            kv_call(&ctx, &state2, KV_INSERT_ALL, "", &json)
        })?;
        kv.set("insertAll", f)?;
    }
    inu.set("kv", kv)?;

    Ok(state)
}

fn js_ui_dialog<'js>(ctx: &Ctx<'js>, state: &Rc<ApiState>, options: Value<'js>) -> JsResult<Value<'js>> {
    let Some(obj) = options.as_object() else {
        return Err(Exception::throw_type(ctx, "dialog: expected an options object"));
    };
    // the options cross as JSON, so the only element a body may be is the one that survives it: a
    // `nativeView`, which is a host handle id and nothing else. Every declarative element carries
    // callbacks the crossing would drop, which is why they are refused here rather than rendered
    // into something that quietly does nothing
    let body: Value = obj.get("body").map_err(|_| Exception::throw_type(ctx, "dialog: cannot read 'body'"))?;
    if !body.is_undefined() && !body.is_null() {
        let kind =
            body.as_object().and_then(|o| o.get::<_, Option<String>>(crate::ui::pages::ELEMENT_TAG).ok().flatten());
        match kind.as_deref() {
            Some("native") => {}
            Some(other) => {
                return error::throw_plugin_error(
                    ctx,
                    "unsupported",
                    &format!("dialog: a '{other}' element cannot be a dialog body; only inu.android.nativeView can"),
                    None,
                    None,
                    None,
                );
            }
            None => return Err(Exception::throw_type(ctx, "dialog: 'body' is not an inu.ui element")),
        }
    }
    let json = json_stringify(ctx, options)?
        .ok_or_else(|| Exception::throw_type(ctx, "dialog: expected an options object"))?;

    let request_id = state.next_request_id.alloc();
    let (promise, pending) = PendingSettle::new(ctx)?;
    state.pending_dialogs.borrow_mut().insert(request_id, pending);

    if let Some(err) = state.host.ui_dialog(request_id, &json) {
        if let Some(pending) = state.pending_dialogs.borrow_mut().remove(&request_id) {
            pending.reject_with(ctx, &err)?;
        }
    }
    Ok(promise.into_value())
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
    value.as_bool().ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a boolean")))
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

/// Validates eagerly and hands the host one shape whichever mode it is: `selected` is always a
/// list, because a single-select chooser and a multi-select one differ in what comes *back*, and
/// the mode is what [`resolve_chooser`] reads to decide that.
fn js_ui_chooser<'js>(ctx: &Ctx<'js>, state: &Rc<ApiState>, opts: Object<'js>) -> JsResult<Value<'js>> {
    let out = Object::new(ctx.clone())?;
    if let Some(title) = opt_string(ctx, &opts, "chooser", "title")? {
        out.set("title", title)?;
    }
    let multiple = opt_flag(ctx, &opts, "chooser", "multiple")?;
    out.set("multiple", multiple)?;

    let raw: Value = opts.get("items").map_err(|_| Exception::throw_type(ctx, "chooser: cannot read 'items'"))?;
    let source = raw.as_array().ok_or_else(|| Exception::throw_type(ctx, "chooser: 'items' must be an array"))?;
    let source = crate::engine::argv::array_values(ctx, source, "chooser: 'items'")?;
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
                    crate::engine::argv::array_values(ctx, list, "chooser: 'selected'")?.into_iter().enumerate()
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
                return Err(Exception::throw_type(
                    ctx,
                    "chooser: 'selected' must be a single index unless 'multiple' is set",
                ))
            }
            (false, None) => picked.set(0, chooser_index(ctx, &selected, len)?)?,
        }
    }
    out.set("selected", picked)?;

    let json = json_stringify(ctx, out.into_value())?
        .ok_or_else(|| Exception::throw_message(ctx, "chooser: serialization failed"))?;

    let request_id = state.next_request_id.alloc();
    let (promise, pending) = PendingSettle::new(ctx)?;
    state.pending_choosers.borrow_mut().insert(request_id, (pending, multiple));

    if let Some(err) = state.host.ui_chooser(request_id, &json) {
        if let Some((pending, _)) = state.pending_choosers.borrow_mut().remove(&request_id) {
            pending.reject_with(ctx, &err)?;
        }
    }
    Ok(promise.into_value())
}

/// settles a pending `inu.ui.chooser()`. `picked` is `None` for dismissed (-> null) and otherwise a
/// comma-separated index list: one entry in single mode, any number (including none) in multi.
pub fn resolve_chooser(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<ApiState>,
    request_id: i64,
    picked: Option<&str>,
) {
    context.with(|ctx| {
        let Some((pending, multiple)) = state.pending_choosers.borrow_mut().remove(&request_id) else {
            return;
        };
        let indices: Option<Vec<i32>> = picked.map(|list| {
            list.split(',').filter(|part| !part.is_empty()).filter_map(|part| part.parse::<i32>().ok()).collect()
        });
        let value = match (indices, multiple) {
            (None, _) => Ok(Value::new_null(ctx.clone())),
            (Some(indices), true) => rquickjs::Array::new(ctx.clone()).and_then(|array| {
                for (i, index) in indices.iter().enumerate() {
                    array.set(i, *index)?;
                }
                Ok(array.into_value())
            }),
            // a single-select answer with no index is a host that lost the choice; `null` is what
            // the promise already means by "no choice was made"
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

/// settles a pending `inu.ui.dialog()` promise with the user's action ("positive", "dismissed", ...)
pub fn resolve_dialog(rt: &Runtime, context: &rquickjs::Context, state: &Rc<ApiState>, request_id: i64, result: &str) {
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

/// the app moved to the foreground or the background. fires on a transition only, so a host that
/// re-announces the state it already reported costs the plugin nothing.
///
/// this is the signal a plugin does its catch-up work from: [`crate::engine::timers`] floors the wheel
/// while hidden and a suspended interval fires once on return rather than replaying the backlog,
/// so a timer cannot be read as a clock across a background stretch.
pub fn app_visibility_changed(rt: &Runtime, context: &rquickjs::Context, state: &Rc<ApiState>, visible: bool) {
    if state.visible.replace(visible) == visible {
        return;
    }
    context.with(|ctx| {
        let mode = if visible { "foreground" } else { "background" };
        for f in state.visibility_fns.snapshot(&ctx) {
            match f.call::<_, Value>((mode,)) {
                Ok(_) => {}
                Err(rquickjs::Error::Exception) => {
                    (state.log)(&crate::fault(format_args!(
                        "onAppVisibilityChange callback threw: {}",
                        format_exception(&ctx),
                    )));
                }
                Err(e) => (state.log)(&format!("onAppVisibilityChange callback failed: {e:?}")),
            }
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

/// runs every registered unload callback (in registration order), logging but not propagating
/// throws, then drains microtasks. call once, right before tearing the engine down.
pub fn notify_unload(rt: &Runtime, context: &rquickjs::Context, state: &Rc<ApiState>) {
    state.lifecycle.begin_unload();
    context.with(|ctx| {
        for f in state.unload_fns.take_all(&ctx) {
            match f.call::<_, Value>(()) {
                Ok(_) => {}
                Err(rquickjs::Error::Exception) => {
                    (state.log)(&crate::fault(format_args!("onUnload callback threw: {}", format_exception(&ctx))));
                }
                Err(e) => (state.log)(&format!("onUnload callback failed: {e:?}")),
            }
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

/// releases every `Persistent` GC root this state still owns - same contract as [`crate::tg::rpc::dispose`]
pub fn dispose(context: &rquickjs::Context, state: &Rc<ApiState>) {
    context.with(|ctx| {
        state.unload_fns.release_all(&ctx);
        state.visibility_fns.release_all(&ctx);
        for (_, pending) in state.pending_dialogs.borrow_mut().drain() {
            pending.release(&ctx);
        }
        for (_, (pending, _)) in state.pending_choosers.borrow_mut().drain() {
            pending.release(&ctx);
        }
    });
}

#[cfg(test)]
mod tests;

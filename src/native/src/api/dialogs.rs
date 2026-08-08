//! `inu.ui.toast` / `dialog` / `chooser`: the modals the app owns and a plugin can only ask for.
//!
//! A request crosses as one json shape and is settled later by the host, so everything here is a
//! pending promise keyed by request id - which is why a process with no ui answers `'dismissed'`
//! rather than hanging.

use std::rc::Rc;

use rquickjs::{Ctx, Exception, Function, Object, Result as JsResult, Runtime, Value};

use super::{json_stringify, ApiState};
use crate::sandbox::error;
use crate::telegram::rpc::{format_exception, pump_jobs, PendingSettle};

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
    let source = crate::sandbox::argv::array_values(ctx, source, "chooser: 'items'")?;
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
                    crate::sandbox::argv::array_values(ctx, list, "chooser: 'selected'")?.into_iter().enumerate()
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

pub(super) fn install<'js>(ctx: &Ctx<'js>, state: &Rc<ApiState>, inu: &Object<'js>) -> JsResult<()> {
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
    Ok(())
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

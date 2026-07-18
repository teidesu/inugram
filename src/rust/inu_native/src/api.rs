//! Pure JS-plumbing for `inu.kv` / `inu.ui.toast` / `inu.ui.dialog` / `inu.onUnload`.
//!
//! Same design as [`crate::rpc`]: JNI-free behind [`ApiHost`], so the module is exercised
//! directly with rquickjs in cargo tests. `lib.rs` wires the JNI-backed host.
//!
//! `kv` upcalls return a single tagged wire string reusing [`crate::tl_proxy`]'s scalar tags:
//! `S<value>` a string, `N` null/ok, `J<json>` a plain JSON payload (keys/getAll), `E<message>`
//! an error thrown into JS.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use rquickjs::{Ctx, Exception, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::rpc::{format_exception, get_or_create_inu, pump_jobs, PendingSettle};

pub const KV_GET: i32 = 0;
pub const KV_SET: i32 = 1;
pub const KV_DEL: i32 = 2;
pub const KV_KEYS: i32 = 3;
pub const KV_CLEAR: i32 = 4;
pub const KV_GET_ALL: i32 = 5;
pub const KV_INSERT_ALL: i32 = 6;

/// stand-in for the Kotlin `QuickJs.ApiListener` interface
pub trait ApiHost {
    /// tagged wire string: `S`/`N`/`J`/`E` (see module doc). unused key/value args are ""
    fn kv(&self, op: i32, key: &str, value: &str) -> String;
    fn ui_toast(&self, text: &str);
    /// `None` == shown (settled later via [`resolve_dialog`]), `Some(msg)` == immediate error
    fn ui_dialog(&self, request_id: i64, options_json: &str) -> Option<String>;
}

pub struct ApiState {
    host: Rc<dyn ApiHost>,
    pub(crate) log: Rc<dyn Fn(&str)>,
    next_dialog_id: Cell<i64>,
    pending_dialogs: RefCell<HashMap<i64, PendingSettle>>,
    unload_fns: RefCell<Vec<Persistent<Function<'static>>>>,
}

pub(crate) fn json_parse<'js>(ctx: &Ctx<'js>, json: &str) -> JsResult<Value<'js>> {
    let json_obj: Object = ctx.globals().get("JSON")?;
    let parse: Function = json_obj.get("parse")?;
    parse.call((json,))
}

pub(crate) fn json_stringify<'js>(ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<Option<String>> {
    let json_obj: Object = ctx.globals().get("JSON")?;
    let stringify: Function = json_obj.get("stringify")?;
    stringify.call((value,))
}

/// decodes a [`ApiHost::kv`] result into a JS value, throwing on `E`
fn kv_result_to_js<'js>(ctx: &Ctx<'js>, wire: &str) -> JsResult<Value<'js>> {
    use rquickjs::IntoJs;
    let Some(tag) = wire.chars().next() else {
        return Err(Exception::throw_message(ctx, "kv: empty host response"));
    };
    let payload = &wire[tag.len_utf8()..];
    match tag {
        'N' => Ok(Value::new_null(ctx.clone())),
        'S' => payload.into_js(ctx),
        'J' => json_parse(ctx, payload),
        'E' => Err(Exception::throw_message(ctx, payload)),
        _ => Err(Exception::throw_message(ctx, &format!("kv: malformed host response tag '{tag}'"))),
    }
}

fn kv_call<'js>(ctx: &Ctx<'js>, state: &Rc<ApiState>, op: i32, key: &str, value: &str) -> JsResult<Value<'js>> {
    let wire = state.host.kv(op, key, value);
    kv_result_to_js(ctx, &wire)
}

pub fn install_api<'js>(
    ctx: &Ctx<'js>,
    host: Rc<dyn ApiHost>,
    log: Rc<dyn Fn(&str)>,
    allow_kv: bool,
) -> JsResult<Rc<ApiState>> {
    let state = Rc::new(ApiState {
        host,
        log,
        next_dialog_id: Cell::new(1),
        pending_dialogs: RefCell::new(HashMap::new()),
        unload_fns: RefCell::new(Vec::new()),
    });

    let inu = get_or_create_inu(ctx)?;

    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, cb: Function<'js>| {
            state2.unload_fns.borrow_mut().push(Persistent::save(&ctx, cb));
        })?;
        inu.set("onUnload", f)?;
    }

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
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, options: Value<'js>| {
            js_ui_dialog(&ctx, &state2, options)
        })?;
        ui.set("dialog", f)?;
    }
    inu.set("ui", ui)?;

    if allow_kv {
        let kv = Object::new(ctx.clone())?;
        for (name, op) in [("get", KV_GET), ("del", KV_DEL)] {
            let state2 = state.clone();
            let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, key: String| {
                kv_call(&ctx, &state2, op, &key, "")
            })?;
            kv.set(name, f)?;
        }
        for (name, op) in [("keys", KV_KEYS), ("clear", KV_CLEAR), ("getAll", KV_GET_ALL)] {
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
    }

    Ok(state)
}

// -- inu.ui.dialog --

fn js_ui_dialog<'js>(ctx: &Ctx<'js>, state: &Rc<ApiState>, options: Value<'js>) -> JsResult<Value<'js>> {
    if !options.is_object() {
        return Err(Exception::throw_type(ctx, "dialog: expected an options object"));
    }
    let json = json_stringify(ctx, options)?
        .ok_or_else(|| Exception::throw_type(ctx, "dialog: expected an options object"))?;

    let request_id = state.next_dialog_id.get();
    state.next_dialog_id.set(request_id + 1);
    let (promise, pending) = PendingSettle::new(ctx)?;
    state.pending_dialogs.borrow_mut().insert(request_id, pending);

    if let Some(err) = state.host.ui_dialog(request_id, &json) {
        if let Some(pending) = state.pending_dialogs.borrow_mut().remove(&request_id) {
            pending.reject_with(ctx, &err)?;
        }
    }
    Ok(promise.into_value())
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

// -- inu.onUnload --

/// runs every registered unload callback (in registration order), logging but not propagating
/// throws, then drains microtasks. call once, right before tearing the engine down.
pub fn notify_unload(rt: &Runtime, context: &rquickjs::Context, state: &Rc<ApiState>) {
    context.with(|ctx| {
        let fns: Vec<_> = state.unload_fns.borrow_mut().drain(..).collect();
        for persistent in fns {
            let f = match persistent.restore(&ctx) {
                Ok(f) => f,
                Err(e) => {
                    (state.log)(&format!("onUnload: failed to restore callback: {e:?}"));
                    continue;
                }
            };
            match f.call::<_, Value>(()) {
                Ok(_) => {}
                Err(rquickjs::Error::Exception) => {
                    (state.log)(&format!("onUnload callback threw: {}", format_exception(&ctx)));
                }
                Err(e) => (state.log)(&format!("onUnload callback failed: {e:?}")),
            }
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

/// releases every `Persistent` GC root this state still owns - same contract as [`crate::rpc::dispose`]
pub fn dispose(context: &rquickjs::Context, state: &Rc<ApiState>) {
    context.with(|ctx| {
        for p in state.unload_fns.borrow_mut().drain(..) {
            let _ = p.restore(&ctx);
        }
        for (_, pending) in state.pending_dialogs.borrow_mut().drain() {
            pending.release(&ctx);
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tl_proxy;
    use rquickjs::Context;

    /// in-memory kv + recorded ui calls; kv errors triggerable via `fail_kv`
    #[derive(Default)]
    struct TestApiHost {
        store: RefCell<std::collections::BTreeMap<String, String>>,
        toasts: RefCell<Vec<String>>,
        dialogs: RefCell<Vec<(i64, String)>>,
        fail_kv: RefCell<Option<String>>,
        fail_dialog: RefCell<Option<String>>,
    }

    impl ApiHost for TestApiHost {
        fn kv(&self, op: i32, key: &str, value: &str) -> String {
            if let Some(err) = self.fail_kv.borrow().as_ref() {
                return tl_proxy::encode_error(err);
            }
            let mut store = self.store.borrow_mut();
            match op {
                KV_GET => match store.get(key) {
                    Some(v) => format!("S{v}"),
                    None => "N".to_string(),
                },
                KV_SET => {
                    store.insert(key.to_string(), value.to_string());
                    "N".to_string()
                }
                KV_DEL => {
                    store.remove(key);
                    "N".to_string()
                }
                KV_KEYS => {
                    let keys: Vec<String> = store.keys().map(|k| format!("\"{k}\"")).collect();
                    format!("J[{}]", keys.join(","))
                }
                KV_CLEAR => {
                    store.clear();
                    "N".to_string()
                }
                KV_GET_ALL => {
                    let entries: Vec<String> = store.iter().map(|(k, v)| format!("\"{k}\":\"{v}\"")).collect();
                    format!("J{{{}}}", entries.join(","))
                }
                KV_INSERT_ALL => {
                    // value is a JSON object of string->string; cheap parse good enough for tests
                    let trimmed = value.trim_start_matches('{').trim_end_matches('}');
                    for pair in trimmed.split(',').filter(|p| !p.is_empty()) {
                        let (k, v) = pair.split_once(':').unwrap();
                        store.insert(k.trim_matches('"').to_string(), v.trim_matches('"').to_string());
                    }
                    "N".to_string()
                }
                _ => tl_proxy::encode_error("unknown op"),
            }
        }

        fn ui_toast(&self, text: &str) {
            self.toasts.borrow_mut().push(text.to_string());
        }

        fn ui_dialog(&self, request_id: i64, options_json: &str) -> Option<String> {
            if let Some(err) = self.fail_dialog.borrow().as_ref() {
                return Some(err.clone());
            }
            self.dialogs.borrow_mut().push((request_id, options_json.to_string()));
            None
        }
    }

    fn setup(allow_kv: bool) -> (Runtime, Context, Rc<TestApiHost>, Rc<ApiState>, Rc<RefCell<Vec<String>>>) {
        let rt = Runtime::new().unwrap();
        let ctx = Context::full(&rt).unwrap();
        let host = Rc::new(TestApiHost::default());
        let host_dyn: Rc<dyn ApiHost> = host.clone();
        let logs = Rc::new(RefCell::new(Vec::<String>::new()));
        let logs2 = logs.clone();
        let log: Rc<dyn Fn(&str)> = Rc::new(move |msg: &str| logs2.borrow_mut().push(msg.to_string()));
        let state = ctx.with(|ctx| install_api(&ctx, host_dyn, log, allow_kv).unwrap());
        (rt, ctx, host, state, logs)
    }

    #[test]
    fn kv_round_trips_all_operations() {
        let (_rt, ctx, _host, state, _logs) = setup(true);
        let result: String = ctx.with(|ctx| {
            ctx.eval::<String, _>(
                r#"
                const out = [];
                out.push(inu.kv.get('a'));
                inu.kv.set('a', '1');
                inu.kv.set('b', '2');
                out.push(inu.kv.get('a'));
                out.push(JSON.stringify(inu.kv.keys()));
                out.push(JSON.stringify(inu.kv.getAll()));
                inu.kv.del('a');
                out.push(inu.kv.get('a'));
                inu.kv.insertAll({ c: '3', d: '4' });
                out.push(JSON.stringify(inu.kv.keys()));
                inu.kv.clear();
                out.push(JSON.stringify(inu.kv.keys()));
                JSON.stringify(out);
                "#,
            )
            .unwrap()
        });
        assert_eq!(
            result,
            r#"[null,"1","[\"a\",\"b\"]","{\"a\":\"1\",\"b\":\"2\"}",null,"[\"b\",\"c\",\"d\"]","[]"]"#,
        );
        dispose(&ctx, &state);
    }

    #[test]
    fn kv_error_wire_throws_into_js() {
        let (_rt, ctx, host, state, _logs) = setup(true);
        *host.fail_kv.borrow_mut() = Some("quota exceeded".to_string());
        let caught: String = ctx.with(|ctx| {
            ctx.eval::<String, _>(
                r#"
                let m = 'no-throw';
                try { inu.kv.set('a', '1'); } catch (e) { m = e.message; }
                m;
                "#,
            )
            .unwrap()
        });
        assert_eq!(caught, "quota exceeded");
        dispose(&ctx, &state);
    }

    #[test]
    fn kv_absent_without_grant() {
        let (_rt, ctx, _host, state, _logs) = setup(false);
        let absent: bool = ctx.with(|ctx| ctx.eval("inu.kv === undefined").unwrap());
        assert!(absent);
        dispose(&ctx, &state);
    }

    #[test]
    fn toast_reaches_host_coerced_to_string() {
        let (_rt, ctx, host, state, _logs) = setup(false);
        ctx.with(|ctx| {
            ctx.eval::<(), _>("inu.ui.toast('hello'); inu.ui.toast(42);").unwrap();
        });
        assert_eq!(*host.toasts.borrow(), vec!["hello".to_string(), "42".to_string()]);
        dispose(&ctx, &state);
    }

    #[test]
    fn dialog_resolves_with_user_action() {
        let (rt, ctx, host, state, _logs) = setup(false);
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                globalThis.__result = null;
                inu.ui.dialog({ title: 'T', positive: 'OK' }).then(r => { globalThis.__result = r; });
                "#,
            )
            .unwrap();
        });
        let dialogs = host.dialogs.borrow();
        assert_eq!(dialogs.len(), 1);
        assert_eq!(dialogs[0].1, r#"{"title":"T","positive":"OK"}"#);
        let request_id = dialogs[0].0;
        drop(dialogs);

        resolve_dialog(&rt, &ctx, &state, request_id, "positive");
        let result: String = ctx.with(|ctx| ctx.eval("globalThis.__result").unwrap());
        assert_eq!(result, "positive");
        dispose(&ctx, &state);
    }

    #[test]
    fn dialog_host_error_rejects() {
        let (rt, ctx, host, state, _logs) = setup(false);
        *host.fail_dialog.borrow_mut() = Some("no ui".to_string());
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                globalThis.__err = null;
                inu.ui.dialog({}).catch(e => { globalThis.__err = e.message; });
                "#,
            )
            .unwrap();
        });
        pump_jobs(&rt, &ctx, &|_| {});
        let err: String = ctx.with(|ctx| ctx.eval("globalThis.__err").unwrap());
        assert_eq!(err, "no ui");
        assert!(state.pending_dialogs.borrow().is_empty());
        dispose(&ctx, &state);
    }

    #[test]
    fn dialog_non_object_options_throws() {
        let (_rt, ctx, _host, state, _logs) = setup(false);
        let threw = ctx.with(|ctx| ctx.eval::<(), _>("inu.ui.dialog('nope')").is_err());
        assert!(threw);
        dispose(&ctx, &state);
    }

    #[test]
    fn unload_callbacks_run_in_order_and_survive_throws() {
        let (rt, ctx, _host, state, logs) = setup(false);
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                globalThis.__ran = [];
                inu.onUnload(() => { globalThis.__ran.push(1); });
                inu.onUnload(() => { throw new Error('bye-boom'); });
                inu.onUnload(() => { globalThis.__ran.push(3); });
                "#,
            )
            .unwrap();
        });

        notify_unload(&rt, &ctx, &state);
        let ran: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__ran)").unwrap());
        assert_eq!(ran, "[1,3]");
        assert!(
            logs.borrow().iter().any(|l| l.contains("onUnload callback threw") && l.contains("bye-boom")),
            "expected a logged diagnostic, got: {:?}",
            logs.borrow(),
        );
        dispose(&ctx, &state);
    }

    #[test]
    fn dispose_releases_pending_dialog_and_unload_roots() {
        let (_rt, ctx, _host, state, _logs) = setup(false);
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                inu.onUnload(() => {});
                inu.ui.dialog({ title: 'stuck' });
                "#,
            )
            .unwrap();
        });
        assert_eq!(state.pending_dialogs.borrow().len(), 1);
        dispose(&ctx, &state);
        // rt/ctx drop after this without aborting == roots were released
    }
}

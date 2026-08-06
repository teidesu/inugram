use super::*;
use crate::engine::error::TestGrantHost;
use crate::tl::proxy;
use rquickjs::Context;

/// `PluginKv.MAX_BYTES`. Mirrored rather than shared: the host is java's, and the oracle only
/// asks that a store which enforces *some* cap answers the wire this side decodes.
const KV_TEST_QUOTA: usize = 1 << 20;

/// in-memory kv + recorded ui calls; `fail_kv` holds a verbatim error wire to answer with
#[derive(Default)]
struct TestApiHost {
    store: RefCell<std::collections::BTreeMap<String, String>>,
    toasts: RefCell<Vec<String>>,
    dialogs: RefCell<Vec<(i64, String)>>,
    fail_kv: RefCell<Option<String>>,
    fail_dialog: RefCell<Option<String>>,
    opened: RefCell<Vec<String>>,
    clipboard: RefCell<String>,
    writes: RefCell<Vec<String>>,
    choosers: RefCell<Vec<(i64, String)>>,
    fail_chooser: RefCell<Option<String>>,
}

impl ApiHost for TestApiHost {
    fn kv(&self, op: i32, key: &str, value: &str) -> String {
        if let Some(wire) = self.fail_kv.borrow().as_ref() {
            return wire.clone();
        }
        let mut store = self.store.borrow_mut();
        match op {
            KV_GET => match store.get(key) {
                Some(v) => format!("S{v}"),
                None => "N".to_string(),
            },
            KV_SET => {
                let mut total: usize =
                    store.iter().filter(|(k, _)| k.as_str() != key).map(|(k, v)| k.len() + v.len()).sum();
                total += key.len() + value.len();
                if total > KV_TEST_QUOTA {
                    return format!("Pquota-exceeded\n\n{total}\n{KV_TEST_QUOTA}\nkv: 1 MB per-plugin quota exceeded");
                }
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
            KV_HAS => format!("J{}", store.contains_key(key)),
            KV_USAGE => {
                let total: usize = store.iter().map(|(k, v)| k.len() + v.len()).sum();
                format!("J{total}")
            }
            _ => proxy::encode_error("unknown op"),
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

    fn ui_chooser(&self, request_id: i64, options_json: &str) -> Option<String> {
        if let Some(err) = self.fail_chooser.borrow().as_ref() {
            return Some(err.clone());
        }
        self.choosers.borrow_mut().push((request_id, options_json.to_string()));
        None
    }

    fn open_url(&self, url: &str) {
        self.opened.borrow_mut().push(url.to_string());
    }

    fn clipboard_read(&self) -> String {
        self.clipboard.borrow().clone()
    }

    fn clipboard_write(&self, text: &str) {
        self.writes.borrow_mut().push(text.to_string());
        *self.clipboard.borrow_mut() = text.to_string();
    }
}

/// disposes on drop, so a failing assertion is one failed test rather than an abort in
/// `JS_FreeRuntime` that takes the whole suite's reporting with it
type Disposing = crate::testing::util::DisposeOnDrop<ApiState>;

type Fixture = (Runtime, Context, Rc<TestApiHost>, Disposing, std::sync::Arc<crate::testing::util::Logs>);

fn setup(grants: &[&str]) -> Fixture {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let host = Rc::new(TestApiHost::default());
    let host_dyn: Rc<dyn ApiHost> = host.clone();
    let grants = TestGrantHost::new(grants).as_host();
    let logs = crate::testing::util::Logs::new();
    let log = crate::testing::util::log_sink(&logs);
    let state = ctx.with(|ctx| {
        error::install_plugin_error(&ctx).unwrap();
        install_api(&ctx, host_dyn, grants, Lifecycle::new(), log).unwrap()
    });
    let state = Disposing::new(&ctx, state, dispose);
    (rt, ctx, host, state, logs)
}

#[test]
fn kv_round_trips_all_operations() {
    let (_rt, ctx, _host, _state, _logs) = setup(&["kv"]);
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
    assert_eq!(result, r#"[null,"1","[\"a\",\"b\"]","{\"a\":\"1\",\"b\":\"2\"}",null,"[\"b\",\"c\",\"d\"]","[]"]"#,);
}

#[test]
fn kv_has_and_usage_answer_scalars() {
    let (_rt, ctx, _host, _state, _logs) = setup(&["kv"]);
    let result: String = ctx.with(|ctx| {
        ctx.eval::<String, _>(
            r#"
            const out = [];
            out.push(inu.kv.has('a'));
            out.push(inu.kv.usage());
            inu.kv.set('a', 'xyz');
            out.push(inu.kv.has('a'));
            out.push(inu.kv.usage());
            JSON.stringify(out);
            "#,
        )
        .unwrap()
    });
    assert_eq!(result, "[false,0,true,4]");
}

#[test]
fn kv_has_and_usage_need_the_grant() {
    let (_rt, ctx, _host, _state, _logs) = setup(&[]);
    let caught: String = ctx.with(|ctx| {
        ctx.eval::<String, _>(
            r#"
            const out = [];
            for (const call of [() => inu.kv.has('a'), () => inu.kv.usage()]) {
                try { call(); out.push('no-throw') } catch (e) { out.push(e.code) }
            }
            out.join(',');
            "#,
        )
        .unwrap()
    });
    assert_eq!(caught, "not-granted,not-granted");
}

#[test]
fn kv_error_wire_throws_into_js() {
    let (_rt, ctx, host, _state, _logs) = setup(&["kv"]);
    *host.fail_kv.borrow_mut() = Some(proxy::encode_error("quota exceeded"));
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
}

#[test]
fn kv_without_grant_throws_a_not_granted_plugin_error() {
    let (_rt, ctx, host, _state, _logs) = setup(&[]);
    let caught: String = ctx.with(|ctx| {
        ctx.eval::<String, _>(
            r#"
            let out = 'no-throw';
            try {
                inu.kv.get('a');
            } catch (e) {
                out = JSON.stringify([e instanceof inu.PluginError, e.name, e.code, e.grant]);
            }
            out;
            "#,
        )
        .unwrap()
    });
    assert_eq!(caught, r#"[true,"PluginError","not-granted","kv"]"#);
    assert!(host.store.borrow().is_empty());
}

#[test]
fn kv_with_grant_reaches_the_host() {
    let (_rt, ctx, host, _state, _logs) = setup(&["kv"]);
    ctx.with(|ctx| ctx.eval::<(), _>("inu.kv.set('a', '1');").unwrap());
    assert_eq!(host.store.borrow().get("a").map(String::as_str), Some("1"));
}

#[test]
fn kv_quota_error_wire_throws_a_plugin_error_with_usage_and_quota() {
    let (_rt, ctx, host, _state, _logs) = setup(&["kv"]);
    *host.fail_kv.borrow_mut() = Some("Pquota-exceeded\n\n1048600\n1048576\nkv is full".to_string());
    let caught: String = ctx.with(|ctx| {
        ctx.eval::<String, _>(
            r#"
            let out = 'no-throw';
            try {
                inu.kv.set('a', '1');
            } catch (e) {
                out = JSON.stringify([e.code, e.message, e.usage, e.quota, typeof e.usage]);
            }
            out;
            "#,
        )
        .unwrap()
    });
    assert_eq!(caught, r#"["quota-exceeded","kv is full",1048600,1048576,"number"]"#);
}

#[test]
fn toast_reaches_host_coerced_to_string() {
    let (_rt, ctx, host, _state, _logs) = setup(&[]);
    ctx.with(|ctx| {
        ctx.eval::<(), _>("inu.ui.toast('hello'); inu.ui.toast(42);").unwrap();
    });
    assert_eq!(*host.toasts.borrow(), vec!["hello".to_string(), "42".to_string()]);
}

#[test]
fn dialog_resolves_with_user_action() {
    let (rt, ctx, host, state, _logs) = setup(&[]);
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
}

/// the host reads title/message/buttons and nothing else, and `JSON.stringify` drops the
/// callbacks a `UIElement` hangs off itself, so a body has to be refused rather than dropped
#[test]
fn dialog_body_is_refused_rather_than_silently_dropped() {
    let (_rt, ctx, host, state, _logs) = setup(&[]);
    let code: String = ctx.with(|ctx| {
        ctx.eval(
            r#"(() => {
                   try {
                       inu.ui.dialog({ title: 'T', body: { __inuUi: 'button' } });
                       return 'did not throw';
                   } catch (e) {
                       return `${e instanceof inu.PluginError}:${e.code}`;
                   }
               })()"#,
        )
        .unwrap()
    });
    assert_eq!(code, "true:unsupported");
    assert!(host.dialogs.borrow().is_empty(), "nothing is shown for a refused dialog");
    assert!(state.pending_dialogs.borrow().is_empty(), "and nothing is left pending");
}

#[test]
fn dialog_host_error_rejects() {
    let (rt, ctx, host, state, _logs) = setup(&[]);
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
}

#[test]
fn dialog_non_object_options_throws() {
    let (_rt, ctx, _host, _state, _logs) = setup(&[]);
    let threw = ctx.with(|ctx| ctx.eval::<(), _>("inu.ui.dialog('nope')").is_err());
    assert!(threw);
}

#[test]
fn unload_callbacks_run_in_order_and_survive_throws() {
    let (rt, ctx, _host, state, logs) = setup(&[]);
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
}

#[test]
fn unload_registrations_stack_and_a_disposer_drops_one() {
    let (rt, ctx, _host, state, _logs) = setup(&[]);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"
            globalThis.__ran = [];
            inu.onUnload(() => { __ran.push(1); });
            globalThis.__d = inu.onUnload(() => { __ran.push(2); });
            inu.onUnload(() => { __ran.push(3); });
            __d();
            __d();
            "#,
        )
        .unwrap();
    });
    assert_eq!(state.unload_fns.len(), 2);

    notify_unload(&rt, &ctx, &state);
    let ran: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__ran)").unwrap());
    assert_eq!(ran, "[1,3]");
}

#[test]
fn an_unload_callback_disposed_mid_notify_still_runs_and_a_new_one_never_does() {
    let (rt, ctx, _host, state, _logs) = setup(&[]);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"
            globalThis.__ran = [];
            inu.onUnload(() => {
                __ran.push('first');
                globalThis.__d2();
                globalThis.__late = typeof inu.onUnload(() => { __ran.push('late'); });
            });
            globalThis.__d2 = inu.onUnload(() => { __ran.push('second'); });
            "#,
        )
        .unwrap();
    });

    notify_unload(&rt, &ctx, &state);
    let ran: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__ran)").unwrap());
    assert_eq!(ran, r#"["first","second"]"#);
    let late: String = ctx.with(|ctx| ctx.eval("globalThis.__late").unwrap());
    assert_eq!(late, "function", "registering after unload began returns a no-op disposer");
    assert!(state.unload_fns.is_empty(), "and roots nothing");
}

#[test]
fn a_throwing_lifecycle_callback_faults() {
    let (rt, ctx, _host, state, logs) = setup(&["onAppVisibilityChange"]);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"
            inu.onAppVisibilityChange(() => { throw new Error('visibility-boom'); });
            inu.onUnload(() => { throw new Error('unload-boom'); });
            "#,
        )
        .unwrap();
    });

    app_visibility_changed(&rt, &ctx, &state, false);
    notify_unload(&rt, &ctx, &state);

    let seen: Vec<(i32, String)> = logs
        .borrow()
        .iter()
        .map(|line| {
            let (level, message) = crate::classify_log(line);
            (level, message.to_string())
        })
        .collect();
    for want in ["visibility-boom", "unload-boom"] {
        let Some((level, message)) = seen.iter().find(|(_, message)| message.contains(want)) else {
            panic!("no diagnostic for '{want}', got: {seen:?}");
        };
        assert_eq!(*level, crate::LEVEL_FAULT, "'{message}' must disable the plugin");
    }
}

#[test]
fn visibility_callbacks_fire_on_transitions_only() {
    let (rt, ctx, _host, state, _logs) = setup(&["onAppVisibilityChange"]);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"
            globalThis.__modes = [];
            inu.onAppVisibilityChange(mode => { __modes.push(mode); });
            "#,
        )
        .unwrap();
    });

    app_visibility_changed(&rt, &ctx, &state, true);
    app_visibility_changed(&rt, &ctx, &state, false);
    app_visibility_changed(&rt, &ctx, &state, false);
    app_visibility_changed(&rt, &ctx, &state, true);
    let modes: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__modes)").unwrap());
    assert_eq!(modes, r#"["background","foreground"]"#);
}

#[test]
fn visibility_without_the_grant_throws_and_registers_nothing() {
    let (rt, ctx, _host, state, _logs) = setup(&[]);
    let caught: String = ctx.with(|ctx| {
        ctx.eval::<String, _>(
            r#"
            let out = 'no-throw';
            try {
                inu.onAppVisibilityChange(() => {});
            } catch (e) {
                out = JSON.stringify([e instanceof inu.PluginError, e.code, e.grant]);
            }
            out;
            "#,
        )
        .unwrap()
    });
    assert_eq!(caught, r#"[true,"not-granted","onAppVisibilityChange"]"#);
    assert!(state.visibility_fns.is_empty());
    app_visibility_changed(&rt, &ctx, &state, false);
}

#[test]
fn a_throwing_visibility_callback_is_logged_and_the_rest_still_run() {
    let (rt, ctx, _host, state, logs) = setup(&["onAppVisibilityChange"]);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"
            globalThis.__ran = [];
            inu.onAppVisibilityChange(() => { throw new Error('vis-boom'); });
            globalThis.__d = inu.onAppVisibilityChange(() => { __ran.push('second'); });
            "#,
        )
        .unwrap();
    });

    app_visibility_changed(&rt, &ctx, &state, false);
    let ran: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__ran)").unwrap());
    assert_eq!(ran, r#"["second"]"#);
    assert!(
        logs.borrow().iter().any(|l| l.contains("onAppVisibilityChange callback threw") && l.contains("vis-boom")),
        "got: {:?}",
        logs.borrow(),
    );

    ctx.with(|ctx| ctx.eval::<(), _>("__d(); __d();").unwrap());
    app_visibility_changed(&rt, &ctx, &state, true);
    let ran: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__ran)").unwrap());
    assert_eq!(ran, r#"["second"]"#, "a disposed registration hears nothing more");
}

#[test]
fn open_url_accepts_http_and_https_and_refuses_every_other_shape() {
    let (_rt, ctx, host, _state, _logs) = setup(&["openUrl"]);
    let outcomes: String = ctx.with(|ctx| {
        ctx.eval::<String, _>(
            r#"
            const urls = [
              'https://example.com/a?b=1#c',
              'HTTP://Example.COM',
              'http://[2001:db8::1]:8080/x',
              'tg://resolve?domain=telegram',
              'intent://scan/#Intent;scheme=zxing;end',
              'file:///data/data/org.telegram.messenger/files',
              'content://sms/inbox',
              'javascript:alert(1)',
              'example.com',
              'https://telegram.org@evil.com/',
              'https://evil.com\\@telegram.org/',
              'https:///nohost',
              'https://exam\nple.com/',
            ];
            const out = [];
            for (const url of urls) {
              try { inu.openUrl(url); out.push('opened'); }
              catch (e) { out.push(e instanceof inu.PluginError ? e.code : 'Error'); }
            }
            JSON.stringify(out);
            "#,
        )
        .unwrap()
    });
    assert_eq!(
        outcomes,
        r#"["opened","opened","opened","invalid-argument","invalid-argument","invalid-argument","invalid-argument","invalid-argument","invalid-argument","invalid-argument","invalid-argument","invalid-argument","invalid-argument"]"#,
    );
    assert_eq!(
        *host.opened.borrow(),
        vec![
            "https://example.com/a?b=1#c".to_string(),
            "HTTP://Example.COM".to_string(),
            "http://[2001:db8::1]:8080/x".to_string(),
        ],
        "only the three http(s) urls may reach the host",
    );
}

#[test]
fn open_url_and_the_two_clipboard_halves_are_three_separate_grants() {
    let (_rt, ctx, host, _state, _logs) = setup(&["clipboard.write"]);
    *host.clipboard.borrow_mut() = "hunter2".to_string();
    let got: String = ctx.with(|ctx| {
        ctx.eval::<String, _>(
            r#"
            const out = [];
            const attempt = f => {
                try { out.push(f() ?? 'ok'); }
                catch (e) { out.push(e.code + '/' + e.grant); }
            };
            attempt(() => inu.clipboard.read());
            attempt(() => inu.openUrl('https://example.com'));
            attempt(() => inu.clipboard.write('mine'));
            JSON.stringify(out);
            "#,
        )
        .unwrap()
    });
    assert_eq!(got, r#"["not-granted/clipboard.read","not-granted/openUrl","ok"]"#);
    assert_eq!(*host.writes.borrow(), vec!["mine".to_string()]);
    assert!(host.opened.borrow().is_empty(), "a refused openUrl must not reach the host");
}

/// the clipboard channel carries the user's own text, so it is the one upcall here that cannot
/// be tagged: every one of these would be an error wire on any other channel
#[test]
fn clipboard_read_hands_over_text_no_wire_tag_could_survive() {
    let (_rt, ctx, host, _state, _logs) = setup(&["clipboard.read", "clipboard.write"]);
    for text in ["", "Error while assigning 'peer'", "N", "Pquota-exceeded\n\n\n\nnope", "J{\"a\":1}"] {
        *host.clipboard.borrow_mut() = text.to_string();
        let got: String = ctx.with(|ctx| ctx.eval("inu.clipboard.read()").unwrap());
        assert_eq!(got, text);
    }
    ctx.with(|ctx| ctx.eval::<(), _>("inu.clipboard.write(42)").unwrap());
    assert_eq!(*host.writes.borrow(), vec!["42".to_string()], "write coerces like ui.toast");
}

#[test]
fn chooser_serializes_one_shape_for_both_modes() {
    let (_rt, ctx, host, _state, _logs) = setup(&[]);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"
            inu.ui.chooser({ title: 'Pick', items: ['a', { text: 'b', subtitle: 'bee' }, { text: 'c', danger: true }], selected: 2 });
            inu.ui.chooser({ items: ['a', 'b'], selected: [1, 0], multiple: true });
            "#,
        )
        .unwrap();
    });
    let choosers = host.choosers.borrow();
    assert_eq!(choosers.len(), 2);
    assert_eq!(
        choosers[0].1,
        r#"{"title":"Pick","multiple":false,"items":[{"text":"a","danger":false},{"text":"b","subtitle":"bee","danger":false},{"text":"c","danger":true}],"selected":[2]}"#,
    );
    assert_eq!(
        choosers[1].1,
        r#"{"multiple":true,"items":[{"text":"a","danger":false},{"text":"b","danger":false}],"selected":[1,0]}"#,
    );
}

#[test]
fn chooser_resolves_an_index_a_list_or_null_by_mode() {
    let (rt, ctx, host, state, _logs) = setup(&[]);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"
            globalThis.__results = [];
            const push = tag => r => { globalThis.__results.push([tag, r]); };
            inu.ui.chooser({ items: ['a', 'b', 'c'] }).then(push('single'));
            inu.ui.chooser({ items: ['a', 'b', 'c'], multiple: true }).then(push('multi'));
            inu.ui.chooser({ items: ['a'] }).then(push('dismissed'));
            inu.ui.chooser({ items: ['a', 'b'], multiple: true }).then(push('none'));
            "#,
        )
        .unwrap();
    });
    let ids: Vec<i64> = host.choosers.borrow().iter().map(|(id, _)| *id).collect();
    assert_eq!(ids.len(), 4);

    resolve_chooser(&rt, &ctx, &state, ids[0], Some("2"));
    resolve_chooser(&rt, &ctx, &state, ids[1], Some("0,2"));
    resolve_chooser(&rt, &ctx, &state, ids[2], None);
    resolve_chooser(&rt, &ctx, &state, ids[3], Some(""));

    let results: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__results)").unwrap());
    assert_eq!(results, r#"[["single",2],["multi",[0,2]],["dismissed",null],["none",[]]]"#);
    assert!(state.pending_choosers.borrow().is_empty());

    // a second settle for the same request finds nothing and must not throw
    resolve_chooser(&rt, &ctx, &state, ids[0], Some("1"));
    let unchanged: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__results.length)").unwrap());
    assert_eq!(unchanged, "4");
}

#[test]
fn chooser_validates_its_options_eagerly() {
    let (_rt, ctx, host, _state, _logs) = setup(&[]);
    let errors: String = ctx.with(|ctx| {
        ctx.eval::<String, _>(
            r#"
            const out = [];
            const tryIt = f => { try { f(); out.push('ok'); } catch (e) { out.push(e.message); } };
            tryIt(() => inu.ui.chooser({ items: [] }));
            tryIt(() => inu.ui.chooser({ items: ['a'], selected: 1 }));
            tryIt(() => inu.ui.chooser({ items: ['a'], selected: -1 }));
            tryIt(() => inu.ui.chooser({ items: ['a', 'b'], selected: [0], multiple: false }));
            tryIt(() => inu.ui.chooser({ items: ['a', 'b'], selected: 0, multiple: true }));
            tryIt(() => inu.ui.chooser({ items: [42] }));
            tryIt(() => inu.ui.chooser({ items: [{ subtitle: 'no text' }] }));
            tryIt(() => inu.ui.chooser({ items: 'a' }));
            JSON.stringify(out);
            "#,
        )
        .unwrap()
    });
    assert_eq!(
        errors,
        r#"["chooser: 'items' must not be empty","chooser: 'selected' out of range","chooser: 'selected' out of range","chooser: 'selected' must be a single index unless 'multiple' is set","chooser: 'selected' must be an array of indices when 'multiple' is set","chooser: items must be strings or { text, subtitle?, danger? } objects","chooser item: 'text' must be a string","chooser: 'items' must be an array"]"#,
    );
    assert!(host.choosers.borrow().is_empty(), "nothing invalid may reach the host");
}

#[test]
fn chooser_host_error_rejects() {
    let (rt, ctx, host, state, _logs) = setup(&[]);
    *host.fail_chooser.borrow_mut() = Some("no ui".to_string());
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"
            globalThis.__err = null;
            inu.ui.chooser({ items: ['a'] }).catch(e => { globalThis.__err = e.message; });
            "#,
        )
        .unwrap();
    });
    pump_jobs(&rt, &ctx, &|_| {});
    let err: String = ctx.with(|ctx| ctx.eval("globalThis.__err").unwrap());
    assert_eq!(err, "no ui");
    assert!(state.pending_choosers.borrow().is_empty());
}

#[test]
fn dispose_releases_pending_dialog_and_unload_roots() {
    let (_rt, ctx, _host, state, _logs) = setup(&["onAppVisibilityChange"]);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"
            inu.onUnload(() => {});
            inu.onAppVisibilityChange(() => {});
            inu.ui.dialog({ title: 'stuck' });
            inu.ui.chooser({ items: ['stuck'] });
            "#,
        )
        .unwrap();
    });
    assert_eq!(state.pending_dialogs.borrow().len(), 1);
    assert_eq!(state.pending_choosers.borrow().len(), 1);
    dispose(&ctx, &state);
    // rt/ctx drop after this without aborting == roots were released
}

const API_ORACLE: &str = include_str!("../../../../res/assets-debug/inu_plugins/api-test.js");

/// its last two halves are the ones a device only reaches through a person: the dialog settles
/// when the user picks a button, and the unload callbacks run when the plugin is stopped. Both
/// are driven here, so the count covers the whole file.
#[test]
fn the_bundled_api_test_plugin_passes() {
    let (rt, ctx, host, state, _logs) = setup(&crate::testing::util::manifest_grants(API_ORACLE));
    // `inu.ui` is one object two modules install into, and the oracle asserts on what the
    // *dialog* does with an element the other one builds
    let ui_host: Rc<dyn crate::ui::pages::UiHost> = Rc::new(crate::ui::icons::tests::SilentUiHost);
    let ui = ctx.with(|ctx| {
        crate::ui::pages::install_ui(
            &ctx,
            ui_host,
            Lifecycle::new(),
            crate::testing::util::log_sink(&crate::testing::util::Logs::new()),
            None,
        )
        .unwrap()
    });
    let _ui = crate::testing::util::DisposeOnDrop::new(&ctx, ui, crate::ui::pages::dispose);
    let lines = crate::testing::util::install_capturing_console(&ctx);
    ctx.with(|ctx| match ctx.eval::<(), _>(API_ORACLE) {
        Ok(()) => {}
        Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
        Err(e) => panic!("{e:?}"),
    });

    let request_id = host.dialogs.borrow().last().expect("a dialog was opened").0;
    resolve_dialog(&rt, &ctx, &state, request_id, "positive");
    notify_unload(&rt, &ctx, &state);

    let lines = lines.borrow().clone();
    crate::testing::util::assert_oracle_exact(&lines, "api test done", 13);
    // what "did not throw" cannot say: the refused dialog never reached the host, and the
    // accepted one did
    assert_eq!(host.dialogs.borrow().len(), 1);
    assert_eq!(*host.toasts.borrow(), vec!["api-test loaded (run #1)".to_string(), "dialog: positive".to_string()],);
}

const SHELL_ORACLE: &str = include_str!("../../../../res/assets-debug/inu_plugins/shell-test.js");

/// the bundled oracle is the only test this surface gets on a device. its load-time half runs
/// on its own; the half a device reaches from a button is called here and answered, so the
/// count covers both and is exact - a member that vanished reads as a refusal in a suite
/// written out of `expectThrow`, and only the count tells those apart
#[test]
fn the_bundled_shell_test_plugin_passes() {
    let (rt, ctx, host, state, _logs) = setup(&crate::testing::util::manifest_grants(SHELL_ORACLE));
    let lines = crate::testing::util::install_capturing_console(&ctx);
    ctx.with(|ctx| match ctx.eval::<(), _>(SHELL_ORACLE) {
        Ok(()) => {}
        Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
        Err(e) => panic!("{e:?}"),
    });
    ctx.with(|ctx| {
        ctx.eval::<Value, _>("globalThis.__shell()").unwrap();
    });

    for picked in [Some("2"), Some("0,2"), None] {
        let request_id = host.choosers.borrow().last().expect("a chooser was opened").0;
        resolve_chooser(&rt, &ctx, &state, request_id, picked);
    }

    let lines = lines.borrow().clone();
    crate::testing::util::assert_oracle_exact(&lines, "shell test done", 23);
    // what "did not throw" cannot say: the accepted url and the write reached the host
    assert_eq!(*host.opened.borrow(), vec!["https://telegram.org/".to_string()]);
    assert_eq!(*host.writes.borrow(), vec!["inugram shell test".to_string()]);
}

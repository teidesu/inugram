use super::*;
use crate::api::error::install_plugin_error;
use crate::sandbox::grants::TestGrantHost;
use crate::testing::harness::DisposeOnDrop;
use std::cell::Cell;

/// a miniature heap: ids 1.. name entries, and every op answers the wire the real host would.
/// It is deliberately dumb - the interesting decisions (which class, which overload, whose
/// scope) are Kotlin's and are pinned in `PluginJvmTest`; what these tests are about is the
/// wire and the two rules this side owns.
#[derive(Default)]
struct TestJvmHost {
    calls: RefCell<Vec<String>>,
    next_id: Cell<i64>,
    released: RefCell<Vec<i64>>,
    /// what the next op answers, in place of the default `N`
    answer: RefCell<Option<String>>,
}

impl TestJvmHost {
    fn new() -> Rc<Self> {
        Rc::new(TestJvmHost { next_id: Cell::new(1), ..Default::default() })
    }

    fn as_host(self: &Rc<Self>) -> Rc<dyn JvmHost> {
        self.clone()
    }

    fn answers(self: &Rc<Self>, wire: &str) {
        *self.answer.borrow_mut() = Some(wire.to_string());
    }

    fn calls(&self) -> Vec<String> {
        self.calls.borrow().clone()
    }

    fn mint(&self, kind: char) -> String {
        let id = self.next_id.get();
        self.next_id.set(id + 1);
        format!("G{kind}{id}")
    }
}

impl JvmHost for TestJvmHost {
    fn jvm(&self, op: i32, target: i64, name: &str, args: &[String]) -> String {
        self.calls.borrow_mut().push(format!("{op}|{target}|{name}|{}", args.join(",")));
        if op == OP_RELEASE {
            self.released.borrow_mut().push(target);
            return "N".to_string();
        }
        if let Some(answer) = self.answer.borrow_mut().take() {
            return answer;
        }
        match op {
            OP_CLASS => self.mint('C'),
            OP_NEW | OP_RUNNABLE => self.mint('O'),
            OP_METHOD => self.mint('M'),
            OP_FIELD => self.mint('F'),
            _ => "N".to_string(),
        }
    }
}

struct Fixture {
    _rt: Runtime,
    ctx: Context,
    host: Rc<TestJvmHost>,
    state: DisposeOnDrop<JvmState>,
}

fn setup(grants: &[&str]) -> Fixture {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let host = TestJvmHost::new();
    let state = ctx.with(|ctx| {
        let inu = crate::testing::harness::inu_namespace(&ctx);
        install_plugin_error(&ctx, &inu).unwrap();
        install_jvm(
            &ctx,
            host.as_host(),
            TestGrantHost::new(grants).as_host(),
            Lifecycle::new(),
            std::sync::Arc::new(|_: &str| {}),
            &inu,
        )
        .unwrap()
    });
    let state = DisposeOnDrop::new(&ctx, state, dispose);
    Fixture { _rt: rt, ctx, host, state }
}

fn eval(f: &Fixture, code: &str) -> String {
    f.ctx.with(|ctx| match ctx.eval::<Value, _>(code) {
        Ok(v) => ctx
            .json_stringify(v)
            .ok()
            .flatten()
            .map(|s| s.to_string().unwrap_or_default())
            .unwrap_or_else(|| "undefined".to_string()),
        Err(rquickjs::Error::Exception) => format_exception(&ctx),
        Err(e) => format!("{e:?}"),
    })
}

fn error_code(f: &Fixture, code: &str) -> String {
    f.ctx.with(|ctx| {
        let script = format!(
            "(() => {{ try {{ {code}; return 'no-throw' }} catch (e) {{ \
             return (e instanceof inu.PluginError ? e.code : e.name) + '|' + (e.grant ?? '') }} }})()"
        );
        ctx.eval::<String, _>(script).unwrap()
    })
}

#[test]
fn a_class_outside_the_scope_list_is_refused_before_anything_crosses() {
    let f = setup(&["unsafe.jvm(java.util.*)"]);
    assert_eq!(error_code(&f, "inu.jvm.cls('android.app.Activity')"), "not-granted|unsafe.jvm(android.app.Activity)",);
    assert!(f.host.calls().is_empty(), "a refused class must not reach the host");
    assert_eq!(error_code(&f, "inu.jvm.cls('java.util.ArrayList')"), "no-throw");
}

#[test]
fn a_plugin_holding_no_jvm_grant_is_refused_at_every_entry_point() {
    let f = setup(&["kv"]);
    for code in
        ["inu.jvm.cls('java.util.ArrayList')", "inu.jvm.runnable(() => {})", "inu.jvm.loadDex('/data/local/tmp/x.dex')"]
    {
        assert!(error_code(&f, code).starts_with("not-granted|unsafe.jvm"), "{code}");
    }
    assert!(f.host.calls().is_empty());
}

/// dex code never crosses this bridge again, so a scope list stops describing anything
#[test]
fn load_dex_needs_the_whole_grant_and_not_a_scoped_one() {
    let scoped = setup(&["unsafe.jvm(java.util.*)"]);
    assert_eq!(error_code(&scoped, "inu.jvm.loadDex('/data/local/tmp/x.dex')"), "not-granted|unsafe.jvm(*)",);
    assert!(scoped.host.calls().is_empty(), "a refused dex must not reach the host");

    let whole = setup(&["unsafe.jvm"]);
    assert_eq!(error_code(&whole, "inu.jvm.loadDex('/data/local/tmp/x.dex')"), "no-throw");
    assert_eq!(whole.host.calls(), vec![format!("{OP_LOAD_DEX}|0|/data/local/tmp/x.dex|")]);
}

#[test]
fn load_dex_takes_a_path_or_bytes_and_nothing_else() {
    let f = setup(&["unsafe.jvm"]);
    for code in ["inu.jvm.loadDex(42)", "inu.jvm.loadDex(null)", "inu.jvm.loadDex({})"] {
        assert_eq!(error_code(&f, code), "invalid-argument|", "{code}");
    }
    assert_eq!(eval(&f, "inu.jvm.loadDex(new Uint8Array([1, 2, 3]))"), "undefined");
    assert_eq!(f.host.calls().last().unwrap(), &format!("{OP_LOAD_DEX}|0||YAQID"));
}

#[test]
fn a_dex_past_the_bound_is_refused_before_it_is_copied() {
    let f = setup(&["unsafe.jvm"]);
    let code = format!("inu.jvm.loadDex(new Uint8Array({}))", DEX_LIMIT_BYTES + 1);
    assert_eq!(error_code(&f, &code), "quota-exceeded|");
    assert!(f.host.calls().is_empty());
    // and the one byte below it is not
    let code = format!("inu.jvm.loadDex(new Uint8Array({}))", DEX_LIMIT_BYTES);
    assert_eq!(error_code(&f, &code), "no-throw");
}

#[test]
fn the_two_deferred_members_refuse_rather_than_approximate() {
    let f = setup(&["unsafe.jvm"]);
    assert_eq!(error_code(&f, "inu.jvm.defineClass('a/B', {})"), "unsupported|");
    assert_eq!(error_code(&f, "inu.jvm.callSuper({}, 'toString')"), "unsupported|");
    assert!(f.host.calls().is_empty());
}

#[test]
fn the_shorthands_reach_the_host_as_the_ops_they_name() {
    let f = setup(&["unsafe.jvm"]);
    f.ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"
            const cls = inu.jvm.cls('java.util.ArrayList')
            const obj = new cls(4)
            obj.getField('size')
            obj.setField('size', 2)
            obj.call('add', 'x')
            cls.getStaticField('EMPTY')
            cls.setStaticField('EMPTY', null)
            cls.callStatic('of', 1, 2)
            "#,
        )
        .unwrap()
    });
    assert_eq!(
        f.host.calls(),
        vec![
            format!("{OP_CLASS}|0|java.util.ArrayList|"),
            format!("{OP_NEW}|1||I4"),
            format!("{OP_GET}|2|size|"),
            format!("{OP_SET}|2|size|I2"),
            format!("{OP_CALL}|2|add|Sx"),
            format!("{OP_GET}|1|EMPTY|"),
            format!("{OP_SET}|1|EMPTY|N"),
            format!("{OP_CALL}|1|of|I1,I2"),
        ],
    );
}

#[test]
fn a_method_and_a_field_handle_carry_their_receiver_as_the_first_argument() {
    let f = setup(&["unsafe.jvm"]);
    f.ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"
            const cls = inu.jvm.cls('java.util.ArrayList')
            const obj = new cls()
            cls.getDeclaredMethod('add').invoke(obj, 'x')
            cls.getDeclaredField('size').get(obj)
            cls.getDeclaredField('size').set(null, 3)
            "#,
        )
        .unwrap()
    });
    let calls = f.host.calls();
    assert_eq!(calls[2], format!("{OP_METHOD}|1|add|"));
    assert_eq!(calls[3], format!("{OP_INVOKE}|3||G2,Sx"));
    assert_eq!(calls[5], format!("{OP_MEMBER_GET}|4||G2"));
    assert_eq!(calls[7], format!("{OP_MEMBER_SET}|5||N,I3"));
}

#[test]
fn only_values_java_can_be_handed_without_guessing_cross() {
    let f = setup(&["unsafe.jvm"]);
    f.ctx.with(|ctx| ctx.eval::<(), _>("globalThis.o = new (inu.jvm.cls('java.util.ArrayList'))()").unwrap());
    for (code, wire) in [
        ("o.call('m', null)", "N"),
        ("o.call('m', undefined)", "N"),
        ("o.call('m', true)", "B1"),
        ("o.call('m', 7)", "I7"),
        ("o.call('m', 7.0)", "I7"),
        ("o.call('m', 1.5)", "D1.5"),
        ("o.call('m', 'x')", "Sx"),
        ("o.call('m', 9007199254740993n)", "I9007199254740993"),
        ("o.call('m', new Uint8Array([0]))", "YAA=="),
        ("o.call('m', o)", "G2"),
    ] {
        f.host.calls.borrow_mut().clear();
        assert_eq!(eval(&f, code), "null", "{code}");
        assert_eq!(f.host.calls()[0], format!("{OP_CALL}|2|m|{wire}"), "{code}");
    }
    for code in [
        "o.call('m', {})",
        "o.call('m', [])",
        "o.call('m', () => {})",
        "o.call('m', Symbol())",
        // wider than a java long, which `to_i64` would have truncated
        "o.call('m', 92233720368547758070n)",
    ] {
        assert_eq!(error_code(&f, code), "invalid-argument|", "{code}");
    }
}

#[test]
fn a_value_past_the_bound_is_refused_in_either_direction() {
    let f = setup(&["unsafe.jvm"]);
    f.ctx.with(|ctx| ctx.eval::<(), _>("globalThis.o = new (inu.jvm.cls('java.util.ArrayList'))()").unwrap());
    let over = VALUE_LIMIT_BYTES + 1;
    assert_eq!(error_code(&f, &format!("o.call('m', 'x'.repeat({over}))")), "quota-exceeded|");
    assert_eq!(error_code(&f, &format!("o.call('m', new Uint8Array({over}))")), "quota-exceeded|");
    assert_eq!(error_code(&f, &format!("o.call('m', 'x'.repeat({VALUE_LIMIT_BYTES}))")), "no-throw");
}

/// a java `long` is 64 bits and an `access_hash` uses all of them, so the alternative to a
/// bigint here is a number that is quietly not the one java holds
#[test]
fn a_long_that_a_js_number_cannot_hold_arrives_as_a_bigint() {
    let f = setup(&["unsafe.jvm"]);
    f.ctx.with(|ctx| ctx.eval::<(), _>("globalThis.o = new (inu.jvm.cls('java.util.ArrayList'))()").unwrap());
    f.host.answers("I9007199254740993");
    assert_eq!(eval(&f, "((v) => typeof v + ':' + v)(o.getField('h'))"), r#""bigint:9007199254740993""#);
    f.host.answers("I9007199254740991");
    assert_eq!(eval(&f, "typeof o.getField('h')"), r#""number""#);
    f.host.answers("I-9007199254740993");
    assert_eq!(eval(&f, "typeof o.getField('h')"), r#""bigint""#);
}

#[test]
fn every_scalar_the_host_answers_with_decodes_the_way_the_tl_bridge_decodes_it() {
    let f = setup(&["unsafe.jvm"]);
    f.ctx.with(|ctx| ctx.eval::<(), _>("globalThis.o = new (inu.jvm.cls('java.util.ArrayList'))()").unwrap());
    for (wire, expected) in
        [("N", "null"), ("Shello", r#""hello""#), ("I42", "42"), ("D1.5", "1.5"), ("B1", "true"), ("B0", "false")]
    {
        f.host.answers(wire);
        assert_eq!(eval(&f, "o.getField('x')"), expected, "{wire}");
    }
    f.host.answers("YAAEC");
    assert_eq!(eval(&f, "Array.from(o.getField('x')).join(',')"), r#""0,1,2""#);
}

#[test]
fn a_host_error_wire_throws_the_typed_error_it_names() {
    let f = setup(&["unsafe.jvm"]);
    f.ctx.with(|ctx| ctx.eval::<(), _>("globalThis.o = new (inu.jvm.cls('java.util.ArrayList'))()").unwrap());
    f.host.answers("Phandle-expired\n\n\n\ngone");
    assert_eq!(error_code(&f, "o.getField('x')"), "handle-expired|");
    f.host.answers("Ejava.lang.IllegalStateException: nope");
    assert_eq!(error_code(&f, "o.getField('x')"), "Error|");
}

#[test]
fn a_handle_whose_js_side_is_gone_is_released_to_the_host() {
    let f = setup(&["unsafe.jvm"]);
    f.ctx.with(|ctx| {
        // the class stays reachable, so the only handle that can be collected is the object's
        ctx.eval::<(), _>(
            "globalThis.C = inu.jvm.cls('java.util.ArrayList'); globalThis.o = new C(); globalThis.o = null",
        )
        .unwrap()
    });
    f._rt.run_gc();
    // the finalizer is a job, so it lands on the next drain rather than inside run_gc
    while f._rt.is_job_pending() {
        f._rt.execute_pending_job().ok();
    }
    assert_eq!(*f.host.released.borrow(), vec![2], "the object handle must have been released");
}

#[test]
fn a_runnable_registers_its_callback_only_once_the_host_has_taken_it() {
    let f = setup(&["unsafe.jvm"]);
    assert_eq!(eval(&f, "typeof inu.jvm.runnable(() => {})"), r#""object""#);
    assert_eq!(f.host.calls(), vec![format!("{OP_RUNNABLE}|0||I1")]);
    assert_eq!(error_code(&f, "inu.jvm.runnable('not a function')"), "invalid-argument|");

    f.host.answers("Einternal: no runnable for you");
    assert_eq!(error_code(&f, "inu.jvm.runnable(() => {})"), "Error|");
    // token 2 was allocated for the refused one and never reused
    f.host.calls.borrow_mut().clear();
    assert_eq!(eval(&f, "typeof inu.jvm.runnable(() => {})"), r#""object""#);
    assert_eq!(f.host.calls(), vec![format!("{OP_RUNNABLE}|0||I3")]);
}

#[test]
fn a_runnable_fires_its_callback_when_the_host_says_java_ran_it() {
    let f = setup(&["unsafe.jvm"]);
    f.ctx.with(|ctx| ctx.eval::<(), _>("globalThis.ran = 0; inu.jvm.runnable(() => { globalThis.ran++ })").unwrap());
    dispatch_callback(&f._rt, &f.ctx, &f.state, 1);
    dispatch_callback(&f._rt, &f.ctx, &f.state, 1);
    assert_eq!(eval(&f, "ran"), "2");
    // one nothing was ever registered for is a no-op rather than a failure
    dispatch_callback(&f._rt, &f.ctx, &f.state, 99);
    assert_eq!(eval(&f, "ran"), "2");
}

#[test]
fn a_runnable_made_during_unload_never_fires() {
    let f = setup(&["unsafe.jvm"]);
    f.ctx.with(|ctx| ctx.eval::<(), _>("globalThis.ran = 0").unwrap());
    f.state.lifecycle.begin_unload();
    f.ctx.with(|ctx| ctx.eval::<(), _>("inu.jvm.runnable(() => { globalThis.ran++ })").unwrap());
    dispatch_callback(&f._rt, &f.ctx, &f.state, 1);
    assert_eq!(eval(&f, "ran"), "0");
}

#[test]
fn a_throwing_callback_is_the_plugins_fault() {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let logged = crate::testing::harness::Logs::new();
    let state = ctx.with(|ctx| {
        let inu = crate::testing::harness::inu_namespace(&ctx);
        install_plugin_error(&ctx, &inu).unwrap();
        install_jvm(
            &ctx,
            TestJvmHost::new().as_host(),
            TestGrantHost::new(&["unsafe.jvm"]).as_host(),
            Lifecycle::new(),
            crate::testing::harness::log_sink(&logged),
            &inu,
        )
        .unwrap()
    });
    let state = DisposeOnDrop::new(&ctx, state, dispose);
    ctx.with(|ctx| ctx.eval::<(), _>("inu.jvm.runnable(() => { throw new Error('boom') })").unwrap());
    dispatch_callback(&rt, &ctx, &state, 1);
    let logged = logged.borrow().clone();
    assert_eq!(logged.len(), 1, "{logged:?}");
    assert!(logged[0].starts_with('\u{1}'), "a plugin's own throw must reach the host as a fault: {logged:?}");
}

/// the id lives under a symbol the prelude never publishes, so there is nothing on a handle a
/// plugin could copy onto an object of its own
#[test]
fn a_handle_cannot_be_forged_out_of_what_js_can_see() {
    let f = setup(&["unsafe.jvm"]);
    f.ctx.with(|ctx| ctx.eval::<(), _>("globalThis.o = new (inu.jvm.cls('java.util.ArrayList'))()").unwrap());
    assert_eq!(eval(&f, "JSON.stringify([Object.keys(o), Object.getOwnPropertySymbols(o).length])"), r#""[[],0]""#,);
    assert_eq!(error_code(&f, "({ ...o }).getField('x')"), "TypeError|");
}

/// The bundled oracle is the only thing that runs this surface on a device, and an oracle nobody
/// runs is one nobody notices going green on a broken engine. The host below stands in for
/// `PluginJvm` - what it cannot stand in for (real reflection, the per-class scope checks, dex)
/// is pinned in `PluginJvmTest` instead.
#[cfg(test)]
mod bundled_oracle {
    use super::testing::OracleJvmHost;
    use super::*;
    use crate::api::error::install_plugin_error;
    use crate::sandbox::grants::TestGrantHost;
    use crate::testing::harness::{assert_oracle_exact, install_capturing_console, manifest_grants, DisposeOnDrop};

    const ORACLE: &str = include_str!("../../../../res/assets-debug/inu_plugins/jvm-test.js");

    #[test]
    fn the_bundled_jvm_test_plugin_passes() {
        let rt = Runtime::new().unwrap();
        let ctx = Context::full(&rt).unwrap();
        let lines = install_capturing_console(&ctx);
        let host = OracleJvmHost::new();
        let state = ctx.with(|ctx| {
            let inu = crate::testing::harness::inu_namespace(&ctx);
            install_plugin_error(&ctx, &inu).unwrap();
            install_jvm(
                &ctx,
                host.as_host(),
                // the plugin's own header, so a suite granting what the manifest forgot cannot pass
                TestGrantHost::new(&manifest_grants(ORACLE)).as_host(),
                Lifecycle::new(),
                std::sync::Arc::new(|_: &str| {}),
                &inu,
            )
            .unwrap()
        });
        let state = DisposeOnDrop::new(&ctx, state, dispose);
        ctx.with(|ctx| match ctx.eval::<(), _>(ORACLE) {
            Ok(()) => {}
            Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
            Err(e) => panic!("{e:?}"),
        });
        // the java object the oracle handed to `setOnClickListener` is "run" by the host, which is
        // the only thing that can fire a runnable
        dispatch_callback(&rt, &ctx, &state, host.runnable_token());
        ctx.with(|ctx| {
            let done: Function = ctx.globals().get("__jvmDone").unwrap();
            done.call::<_, ()>(()).unwrap()
        });
        while rt.is_job_pending() {
            rt.execute_pending_job().ok();
        }
        let lines = lines.borrow().clone();
        assert_oracle_exact(&lines, "jvm test done", 35);
    }
}

/// The `JvmHost` a suite that only needs handles minted runs against: the same wire, with an answer
/// per op rather than a settable one. Shared with `xposed`, whose every entry point takes a handle
/// this is what mints.
#[cfg(test)]
pub(crate) mod testing {
    use super::*;
    use std::cell::Cell;

    #[derive(Default)]
    pub(crate) struct OracleJvmHost {
        next_id: Cell<i64>,
        runnable: Cell<u32>,
    }

    impl OracleJvmHost {
        pub(crate) fn new() -> Rc<Self> {
            Rc::new(OracleJvmHost { next_id: Cell::new(1), runnable: Cell::new(0) })
        }

        pub(crate) fn as_host(self: &Rc<Self>) -> Rc<dyn JvmHost> {
            self.clone()
        }

        /// the callback id the oracle's `runnable` was minted with, i.e. what java running it
        /// would come back as
        pub(crate) fn runnable_token(&self) -> u32 {
            self.runnable.get()
        }

        fn mint(&self, kind: char) -> String {
            let id = self.next_id.get();
            self.next_id.set(id + 1);
            format!("G{kind}{id}")
        }
    }

    impl JvmHost for OracleJvmHost {
        fn jvm(&self, op: i32, _target: i64, name: &str, args: &[String]) -> String {
            match op {
                OP_CLASS => self.mint('C'),
                OP_NEW => self.mint('O'),
                OP_METHOD => self.mint('M'),
                OP_FIELD => self.mint('F'),
                OP_RUNNABLE => {
                    if let Some(token) = args.first().and_then(|a| a.strip_prefix('I')).and_then(|t| t.parse().ok()) {
                        self.runnable.set(token);
                    }
                    self.mint('O')
                }
                OP_GET => match name {
                    "size" => "I3".to_string(),
                    "TAG" => "Sinugram".to_string(),
                    "digest" => "YAQID".to_string(),
                    "serialVersionUID" => "I9007199254740993".to_string(),
                    // the far side of the same scope list: only the host knows the runtime class of
                    // what it is about to hand over
                    "out" => "Pnot-granted\nunsafe.jvm(java.io.PrintStream)\n\n\n\
                              java.io.PrintStream is not in this plugin's unsafe.jvm scope list"
                        .to_string(),
                    _ => "N".to_string(),
                },
                OP_CALL => match name {
                    "toString" => "S[1, 2]".to_string(),
                    "add" => "B1".to_string(),
                    "clone" => self.mint('O'),
                    "boom" => "Ejava.lang.IllegalStateException: boom".to_string(),
                    _ => "N".to_string(),
                },
                OP_INVOKE => "B1".to_string(),
                OP_MEMBER_GET => "I3".to_string(),
                _ => "N".to_string(),
            }
        }
    }
}

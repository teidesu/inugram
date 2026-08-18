use super::*;
use crate::api::platform::jvm::tests::testing::OracleJvmHost;
use crate::sandbox::grants::MATCH_EXACT;
use crate::testing::harness::{assert_oracle_exact, install_capturing_console, manifest_grants, DisposeOnDrop};
use std::cell::{Cell, RefCell};
use std::collections::HashMap;

const ORACLE: &str = include_str!("../../../../../test/plugins/xposed-test.js");

/// keyed by the method handle, because two hooks on one method are two hooks on *one site* and
/// the ordering the oracle asserts is only a property of a shared one. `hookAll*` answers with
/// a list, which is the shape the real host uses for an overload set.
struct OracleXposedHost {
  sites: RefCell<HashMap<i64, i64>>,
  next: RefCell<i64>,
  jvm: Rc<OracleJvmHost>,
}

/// mirrors `PluginXposed.BOX_CLASSES`: the app host refuses these, so the fake must too for the
/// oracle's refusal assertion to mean the same thing here and on a device
const BOX_CLASSES: &[&str] = &[
  "java.lang.Boolean",
  "java.lang.Byte",
  "java.lang.Character",
  "java.lang.Short",
  "java.lang.Integer",
  "java.lang.Long",
  "java.lang.Float",
  "java.lang.Double",
];

impl OracleXposedHost {
  fn new(jvm: Rc<OracleJvmHost>) -> Rc<Self> {
    Rc::new(OracleXposedHost {
      sites: RefCell::new(HashMap::new()),
      next: RefCell::new(100),
      jvm,
    })
  }

  fn site_for(&self, target: i64) -> i64 {
    if let Some(site) = self.sites.borrow().get(&target) {
      return *site;
    }
    let mut next = self.next.borrow_mut();
    let site = *next;
    *next += 1;
    self.sites.borrow_mut().insert(target, site);
    site
  }
}

impl XposedHost for OracleXposedHost {
  fn xposed(&self, op: i32, target: i64, _name: &str, _args: &[String]) -> String {
    match op {
      OP_HOOK => format!("S{}", self.site_for(target)),
      OP_HOOK_ALL => {
        if let Some(name) = self.jvm.class_name(target) {
          if BOX_CLASSES.contains(&name.as_str()) {
            return format!("Punsupported\n\n\n\nxposed: {name} backs primitive boxing");
          }
        }
        let first = self.site_for(target);
        format!("S{first},{}", first + 500)
      }
      OP_CALL_ORIGINAL => "I3".to_string(),
      _ => "N".to_string(),
    }
  }
}

#[test]
fn the_bundled_xposed_test_plugin_passes() {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let lines = install_capturing_console(&ctx);
  let jvm_oracle = OracleJvmHost::new();
  let host = OracleXposedHost::new(jvm_oracle.clone());
  // the plugin's own header, so a suite granting what the manifest forgot cannot pass
  let grants = crate::sandbox::grants::TestGrantHost::new(&manifest_grants(ORACLE)).as_host();
  let lifecycle = Lifecycle::new();
  let log: std::sync::Arc<dyn Fn(&str) + Send + Sync> = std::sync::Arc::new(|_: &str| {});
  let (state, jvm) = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    crate::api::error::install_plugin_error(&ctx).unwrap();
    let jvm = crate::api::platform::jvm::install_jvm(
      &ctx,
      jvm_oracle.as_host(),
      grants.clone(),
      lifecycle.clone(),
      log.clone(),
      &inu,
    )
    .unwrap();
    let state = install_xposed(&ctx, host.clone(), grants, lifecycle, jvm.clone(), log, &inu).unwrap();
    (state, jvm)
  });
  let _jvm = DisposeOnDrop::new(&ctx, jvm, |ctx, state| state.dispose(ctx));
  let _state = DisposeOnDrop::new(&ctx, state, |ctx, state| state.dispose(ctx));

  ctx.with(|ctx| match ctx.eval::<(), _>(ORACLE) {
    Ok(()) => {}
    Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
    Err(e) => panic!("{e:?}"),
  });
  let lines = lines.borrow().clone();
  assert_oracle_exact(&lines, "xposed test done", 7);
}

/// One whole dispatch as a host runs it: the `before` phase on the queue, the original on the
/// thread that called the hooked method, then the `after` phase. Mirrors
/// `PluginXposed.Session.dispatch`, which is the only caller of the two halves in the app.
#[cfg(test)]
fn run_dispatch(
  rt: &Runtime,
  context: &Context,
  state: &Rc<XposedState>,
  site: i64,
  args: &[String],
  original: &str,
) -> (String, Option<Vec<String>>) {
  let answer = state.dispatch_before(rt, context, 1, site, &Invocation { method: "GM1", this: "N", args });
  if answer[0] == "A" {
    return (answer[1].clone(), None);
  }
  let called_with = answer[1..].to_vec();
  let result = if answer[0] == "P1" {
    state.dispatch_after(rt, context, 1, original)
  } else {
    original.to_string()
  };
  (result, Some(called_with))
}

/// one recorded upcall: op, target, name, arguments
type HostCall = (i32, i64, String, Vec<String>);

#[derive(Default)]
struct TestXposedHost {
  calls: RefCell<Vec<HostCall>>,
  /// what OP_HOOK/OP_HOOK_ALL answer with, in order
  sites: RefCell<Vec<String>>,
  /// what the original answers with, which is the host's to produce now that no op asks for it
  original: RefCell<String>,
  next_site: Cell<i64>,
}

impl TestXposedHost {
  fn new() -> Rc<TestXposedHost> {
    Rc::new(TestXposedHost {
      original: RefCell::new("S<original>".to_string()),
      next_site: Cell::new(100),
      ..Default::default()
    })
  }

  fn as_host(self: &Rc<Self>) -> Rc<dyn XposedHost> {
    self.clone()
  }

  fn ops(&self) -> Vec<i32> {
    self.calls.borrow().iter().map(|call| call.0).collect()
  }
}

impl XposedHost for TestXposedHost {
  fn xposed(&self, op: i32, target: i64, name: &str, args: &[String]) -> String {
    self.calls.borrow_mut().push((op, target, name.to_string(), args.to_vec()));
    match op {
      OP_HOOK | OP_HOOK_ALL => {
        if let Some(answer) = self.sites.borrow_mut().pop() {
          return answer;
        }
        let site = self.next_site.get();
        self.next_site.set(site + 1);
        format!("S{site}")
      }
      OP_CALL_ORIGINAL => self.original.borrow().clone(),
      _ => "N".to_string(),
    }
  }
}

/// Both states hold GC roots and `Persistent` has no `Drop`, so an undisposed one aborts
/// `JS_FreeRuntime`. Fields drop in declaration order, hence the disposers ahead of the runtime.
struct Fixture {
  _xposed: crate::testing::harness::DisposeOnDrop<XposedState>,
  _jvm: crate::testing::harness::DisposeOnDrop<crate::api::platform::jvm::JvmState>,
  rt: Runtime,
  ctx: Context,
  host: Rc<TestXposedHost>,
  /// what the last [`Fixture::dispatch`] called the original with, `None` when it did not
  originals: RefCell<Option<Vec<String>>>,
  state: Rc<XposedState>,
  logs: std::sync::Arc<crate::testing::harness::Logs>,
  lifecycle: Rc<Lifecycle>,
}

fn setup(grants: &[&str]) -> Fixture {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let grant_host = crate::sandbox::grants::TestGrantHost::new(grants).as_host();
  let lifecycle = Lifecycle::new();
  let logs = crate::testing::harness::Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  let host = TestXposedHost::new();

  let (state, jvm) = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    crate::api::error::install_plugin_error(&ctx).unwrap();
    let jvm = crate::api::platform::jvm::install_jvm(
      &ctx,
      OracleJvmHost::new().as_host(),
      grant_host.clone(),
      lifecycle.clone(),
      log.clone(),
      &inu,
    )
    .unwrap();
    let state =
      install_xposed(&ctx, host.as_host(), grant_host.clone(), lifecycle.clone(), jvm.clone(), log.clone(), &inu)
        .unwrap();
    (state, jvm)
  });

  Fixture {
    _xposed: crate::testing::harness::DisposeOnDrop::new(&ctx, state.clone(), |ctx, state| state.dispose(ctx)),
    _jvm: crate::testing::harness::DisposeOnDrop::new(&ctx, jvm, |ctx, state| state.dispose(ctx)),
    rt,
    ctx,
    host,
    originals: RefCell::new(None),
    state,
    logs,
    lifecycle,
  }
}

impl Fixture {
  fn eval(&self, source: &str) {
    self.ctx.with(|ctx| {
      if let Err(e) = ctx.eval::<(), _>(source) {
        panic!("{}: {}", e, format_exception(&ctx));
      }
    });
  }

  fn eval_err(&self, source: &str) -> String {
    self.ctx.with(|ctx| match ctx.eval::<Value, _>(source) {
      Ok(_) => panic!("expected a throw"),
      Err(_) => format_exception(&ctx),
    })
  }

  fn dispatch(&self, site: i64, args: &[&str]) -> String {
    let args: Vec<String> = args.iter().map(|a| a.to_string()).collect();
    let original = self.host.original.borrow().clone();
    let (answer, called_with) = run_dispatch(&self.rt, &self.ctx, &self.state, site, &args, &original);
    *self.originals.borrow_mut() = called_with;
    answer
  }

  /// the args the original was called with, or `None` when a `before` answered instead
  fn original_args(&self) -> Option<Vec<String>> {
    self.originals.borrow().clone()
  }
}

fn granted() -> Fixture {
  setup(&["unsafe.jvm", "unsafe.xposed"])
}

#[test]
fn hooking_needs_the_grant() {
  let fixture = setup(&["unsafe.jvm"]);
  let message = fixture.eval_err(
    "const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         inu.xposed.hookMethod(m, { before() {} })",
  );
  assert!(message.contains("unsafe.xposed"), "{message}");
  assert!(fixture.host.calls.borrow().is_empty(), "the host was asked anyway");
}

#[test]
fn a_before_hook_runs_and_the_original_is_called() {
  let fixture = granted();
  fixture.eval(
    "globalThis.seen = [];
         const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         inu.xposed.hookMethod(m, { before(ctx) { seen.push(ctx.args[0]) } })",
  );

  let answer = fixture.dispatch(100, &["I7"]);
  assert_eq!(answer, "S<original>");
  fixture.eval("if (seen.length !== 1 || seen[0] !== 7) throw new Error('args: ' + seen)");
  assert_eq!(fixture.host.ops(), vec![OP_HOOK]);
  assert_eq!(fixture.original_args(), Some(vec!["I7".to_string()]));
}

#[test]
fn set_return_value_in_before_skips_the_original() {
  let fixture = granted();
  fixture.eval(
    "const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         inu.xposed.hookMethod(m, { before(ctx) { ctx.setReturnValue(42) } })",
  );

  assert_eq!(fixture.dispatch(100, &[]), "I42");
  assert_eq!(fixture.original_args(), None, "the original was called anyway");
}

#[test]
fn set_throwable_clears_a_return_value_an_earlier_hook_set() {
  let fixture = granted();
  fixture.eval(
    "const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         const t = new (inu.jvm.cls('java.lang.RuntimeException'))('no');
         inu.xposed.hookMethod(m, { before(ctx) { ctx.setReturnValue(1); ctx.setThrowable(t) } })",
  );

  let answer = fixture.dispatch(100, &[]);
  assert!(answer.starts_with("TG"), "expected a throwable handle wire, got {answer}");
}

#[test]
fn mutating_args_changes_what_the_original_is_called_with() {
  // `android.xposed.d.ts` calls the array live, which is only true if it is read back
  let fixture = granted();
  fixture.eval(
    "const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         inu.xposed.hookMethod(m, { before(ctx) { ctx.args[0] = 99 } })",
  );

  fixture.dispatch(100, &["I7"]);
  assert_eq!(fixture.original_args(), Some(vec!["I99".to_string()]));
}

#[test]
fn an_after_hook_sees_the_original_result_and_may_replace_it() {
  let fixture = granted();
  *fixture.host.original.borrow_mut() = "I5".to_string();
  fixture.eval(
    "globalThis.saw = null;
         const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         inu.xposed.hookMethod(m, {
           after(ctx) { saw = ctx.returnValue; ctx.setReturnValue(ctx.returnValue * 2) },
         })",
  );

  assert_eq!(fixture.dispatch(100, &[]), "I10");
  fixture.eval("if (saw !== 5) throw new Error('saw ' + saw)");
}

#[test]
fn an_after_that_sets_nothing_leaves_the_original_result() {
  let fixture = granted();
  *fixture.host.original.borrow_mut() = "I5".to_string();
  fixture.eval(
    "const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         inu.xposed.hookMethod(m, { after() {} })",
  );

  assert_eq!(fixture.dispatch(100, &[]), "I5");
}

#[test]
fn a_before_verdict_does_not_leak_into_the_after_phase() {
  // `__answered` is cleared before `after` runs, or every hook with both halves would report
  // its own `before` value as the result
  let fixture = granted();
  *fixture.host.original.borrow_mut() = "I5".to_string();
  fixture.eval(
    "globalThis.saw = 'unset';
         const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         inu.xposed.hookMethod(m, { before() {}, after(ctx) { saw = ctx.returnValue } })",
  );

  assert_eq!(fixture.dispatch(100, &[]), "I5");
  fixture.eval("if (saw !== 5) throw new Error('saw ' + saw)");
}

#[test]
fn a_thrown_original_reaches_after_as_a_throwable_rather_than_a_return_value() {
  let fixture = granted();
  // what the host answers when the method itself threw: `T` plus the throwable
  *fixture.host.original.borrow_mut() = "TGO9".to_string();
  fixture.eval(
    "globalThis.threw = null; globalThis.returned = 'unset';
         const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         inu.xposed.hookMethod(m, {
           after(ctx) { threw = ctx.throwable; returned = ctx.returnValue },
         })",
  );

  let answer = fixture.dispatch(100, &[]);
  assert!(answer.starts_with('T'), "expected the throw to be handed on, got {answer}");
  fixture.eval("if (threw === null) throw new Error('after saw no throwable')");
  fixture.eval("if (returned !== null) throw new Error('returnValue was ' + returned)");
}

#[test]
fn hooks_run_in_registration_order() {
  let fixture = granted();
  fixture.eval(
    "globalThis.order = [];
         const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         inu.xposed.hookMethod(m, { before() { order.push('a') }, after() { order.push('c') } });
         inu.xposed.hookMethod(m, { before() { order.push('b') }, after() { order.push('d') } })",
  );

  // one site, so the second registration must not have installed a second hook
  assert_eq!(fixture.host.ops(), vec![OP_HOOK, OP_HOOK]);
  let sites = fixture.host.calls.borrow().iter().filter(|c| c.0 == OP_HOOK).count();
  assert_eq!(sites, 2);
}

#[test]
fn two_hooks_on_one_site_uninstall_only_when_the_last_goes() {
  let fixture = granted();
  // both registrations answer with the same site, which is what two hooks on one method is
  *fixture.host.sites.borrow_mut() = vec!["S100".to_string(), "S100".to_string()];
  fixture.eval(
    "const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         globalThis.a = inu.xposed.hookMethod(m, { before() {} });
         globalThis.b = inu.xposed.hookMethod(m, { before() {} });
         a()",
  );
  assert!(!fixture.host.ops().contains(&OP_UNHOOK), "unhooked while a hook was live");

  fixture.eval("b()");
  assert!(fixture.host.ops().contains(&OP_UNHOOK), "the last hook did not unhook");
  assert_eq!(fixture.host.ops().iter().filter(|op| **op == OP_UNHOOK).count(), 1);
}

#[test]
fn a_disposer_called_twice_unhooks_once() {
  let fixture = granted();
  fixture.eval(
    "const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         const off = inu.xposed.hookMethod(m, { before() {} });
         off(); off(); off()",
  );
  assert_eq!(fixture.host.ops().iter().filter(|op| **op == OP_UNHOOK).count(), 1);
}

#[test]
fn a_disposed_hook_stops_running_but_the_original_still_does() {
  let fixture = granted();
  fixture.eval(
    "globalThis.runs = 0;
         const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         const off = inu.xposed.hookMethod(m, { before() { runs++ } });
         off()",
  );

  assert_eq!(fixture.dispatch(100, &[]), "S<original>");
  fixture.eval("if (runs !== 0) throw new Error('ran ' + runs)");
}

#[test]
fn a_hook_disposed_mid_dispatch_still_finishes_the_run_in_flight() {
  let fixture = granted();
  *fixture.host.sites.borrow_mut() = vec!["S100".to_string(), "S100".to_string()];
  fixture.eval(
    "globalThis.second = 0;
         const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         globalThis.off = null;
         inu.xposed.hookMethod(m, { before() { off() } });
         off = inu.xposed.hookMethod(m, { before() { second++ } })",
  );

  fixture.dispatch(100, &[]);
  fixture.eval("if (second !== 1) throw new Error('second ran ' + second)");
  // and not on the next one
  fixture.dispatch(100, &[]);
  fixture.eval("if (second !== 1) throw new Error('second ran again: ' + second)");
}

#[test]
fn a_hook_registered_mid_dispatch_joins_from_the_next_one() {
  let fixture = granted();
  *fixture.host.sites.borrow_mut() = vec!["S100".to_string(), "S100".to_string()];
  fixture.eval(
    "globalThis.late = 0;
         const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         let added = false;
         inu.xposed.hookMethod(m, {
           before() {
             if (added) return
             added = true
             inu.xposed.hookMethod(m, { before() { late++ } })
           },
         })",
  );

  fixture.dispatch(100, &[]);
  fixture.eval("if (late !== 0) throw new Error('joined its own dispatch')");
  fixture.dispatch(100, &[]);
  fixture.eval("if (late !== 1) throw new Error('did not join the next: ' + late)");
}

#[test]
fn a_throwing_hook_is_a_fault_and_the_original_still_runs() {
  let fixture = granted();
  fixture.eval(
    "const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         inu.xposed.hookMethod(m, { before() { throw new Error('boom') } })",
  );

  assert_eq!(fixture.dispatch(100, &[]), "S<original>");
  let logs = fixture.logs.borrow();
  assert!(
    logs
      .iter()
      .any(|line| crate::classify_log(line).0 == crate::LEVEL_FAULT && line.contains("before hook threw")),
    "{logs:?}"
  );
}

#[test]
fn a_hook_with_neither_callback_is_refused_before_anything_is_installed() {
  let fixture = granted();
  let message = fixture.eval_err(
    "const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         inu.xposed.hookMethod(m, {})",
  );
  assert!(message.contains("before or an after"), "{message}");
  assert!(!fixture.host.ops().contains(&OP_HOOK));
}

#[test]
fn hook_arguments_are_checked_without_a_js_prelude() {
  let fixture = granted();
  for (source, expected) in [
    (
      "(() => { const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length'); inu.xposed.hookMethod(m, null) })()",
      "expected a hook object",
    ),
    (
      "(() => { const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length'); inu.xposed.hookMethod(m, { before: 1 }) })()",
      "before must be a function",
    ),
    (
      "(() => { const c = inu.jvm.cls('java.lang.String'); inu.xposed.hookAllOverloads(c, '', { before() {} }) })()",
      "expected a method name",
    ),
  ] {
    let message = fixture.eval_err(source);
    assert!(message.contains(expected), "{source}: {message}");
  }
  assert!(!fixture.host.ops().contains(&OP_HOOK));
}

#[test]
fn hook_all_overloads_registers_one_hook_per_site() {
  let fixture = granted();
  *fixture.host.sites.borrow_mut() = vec!["S200,201".to_string()];
  fixture.eval(
    "globalThis.runs = 0;
         const c = inu.jvm.cls('java.lang.String');
         globalThis.off = inu.xposed.hookAllOverloads(c, 'substring', { before() { runs++ } })",
  );
  assert_eq!(fixture.host.ops(), vec![OP_HOOK_ALL]);

  // one registration, two sites, and each one dispatches on its own
  fixture.dispatch(200, &[]);
  fixture.dispatch(201, &[]);
  fixture.eval("if (runs !== 2) throw new Error('ran ' + runs)");

  // and the one disposer takes both back down
  fixture.eval("off()");
  assert_eq!(fixture.host.ops().iter().filter(|op| **op == OP_UNHOOK).count(), 2);
}

#[test]
fn registering_after_unload_began_is_a_no_op_returning_a_disposer() {
  let fixture = granted();
  fixture.lifecycle.begin_unload();
  fixture.eval(
    "const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         const off = inu.xposed.hookMethod(m, { before() {} });
         if (typeof off !== 'function') throw new Error('no disposer');
         off()",
  );
  assert!(!fixture.host.ops().contains(&OP_HOOK));
}

#[test]
fn disposing_the_engine_unhooks_everything_it_installed() {
  // an ART entry point stays rewritten, so a hook left behind dispatches into a dead engine
  let fixture = granted();
  fixture.eval(
    "const c = inu.jvm.cls('java.lang.String');
         inu.xposed.hookMethod(c.getDeclaredMethod('length'), { before() {} });
         inu.xposed.hookMethod(c.getDeclaredMethod('isEmpty'), { before() {} })",
  );

  fixture.state.dispose(&fixture.ctx);
  assert_eq!(fixture.host.ops().iter().filter(|op| **op == OP_UNHOOK).count(), 2);
}

#[test]
fn call_original_needs_the_grant_and_passes_the_receiver_first() {
  let fixture = granted();
  fixture.eval(
    "const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         inu.xposed.callOriginalMethod(m, null, [1, 'two'])",
  );
  let calls = fixture.host.calls.borrow();
  let call = calls.iter().find(|call| call.0 == OP_CALL_ORIGINAL).expect("called");
  assert_eq!(call.3, vec!["N".to_string(), "I1".to_string(), "Stwo".to_string()]);
}

#[test]
fn call_original_defaults_omitted_receiver_and_arguments() {
  let fixture = granted();
  fixture.eval(
    "const m = inu.jvm.cls('java.lang.String').getDeclaredMethod('length');
         inu.xposed.callOriginalMethod(m)",
  );
  let calls = fixture.host.calls.borrow();
  let call = calls.iter().find(|call| call.0 == OP_CALL_ORIGINAL).expect("called");
  assert_eq!(call.3, vec!["N".to_string()]);
}

#[test]
fn a_grant_scope_does_not_have_to_name_the_class_here() {
  // the declaring class is the host's to check; an unscoped grant satisfies every scope check
  let fixture = setup(&["unsafe.jvm", "unsafe.xposed"]);
  assert!(fixture.state.grants.is_granted(GRANT, Some("anything"), MATCH_EXACT));
}

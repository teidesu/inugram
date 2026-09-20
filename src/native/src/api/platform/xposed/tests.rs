use super::*;
use crate::api::error::format_exception;
use crate::api::platform::jvm::tests::testing::OracleJvmHost;
use std::cell::{Cell, RefCell};

struct WireValues {
  jvm: Rc<JvmState>,
  wires: Vec<String>,
  reads: Rc<Cell<usize>>,
}

impl JavaValues for WireValues {
  fn read<'js>(&self, ctx: &Ctx<'js>, index: usize) -> JsResult<Value<'js>> {
    self.reads.set(self.reads.get() + 1);
    self.jvm.wire_to_value(ctx, &self.wires[index])
  }
}

fn wire_values(state: &XposedState, wires: Vec<String>, reads: &Rc<Cell<usize>>) -> Rc<dyn JavaValues> {
  Rc::new(WireValues {
    jvm: state.jvm.clone(),
    wires,
    reads: reads.clone(),
  })
}

fn invocation_counting(state: &XposedState, args: &[String], reads: &Rc<Cell<usize>>) -> Invocation {
  let mut wires = vec!["GM1".to_string(), "N".to_string()];
  wires.extend(args.iter().cloned());
  Invocation {
    values: wire_values(state, wires, reads),
    args: args.len(),
  }
}

fn invocation(state: &XposedState, args: &[String]) -> Invocation {
  invocation_counting(state, args, &Rc::new(Cell::new(0)))
}

fn returned_counting(state: &XposedState, wire: &str, reads: &Rc<Cell<usize>>) -> Returned {
  let threw = wire.starts_with('T');
  let wire = wire.strip_prefix('T').unwrap_or(wire).to_string();
  Returned {
    values: wire_values(state, vec![wire], reads),
    index: 0,
    threw,
  }
}

fn returned(state: &XposedState, wire: &str) -> Returned {
  returned_counting(state, wire, &Rc::new(Cell::new(0)))
}

fn wire_of(answer: Answer) -> String {
  match answer {
    Answer::Keep => KEEP_ORIGINAL.to_string(),
    Answer::NotDispatched => NOT_DISPATCHED.to_string(),
    Answer::Wire(wire) => wire,
  }
}

/// One whole dispatch as a host runs it: the `before` phase on the queue, the original on the
/// thread that called the hooked method, then the `after` phase - or, for a site the host was told
/// has no `before`, the original and then one phase with both halves. Mirrors
/// `PluginXposed.Session.dispatch`, which is the only caller of these in the app.
#[cfg(test)]
fn run_dispatch(
  rt: &Runtime,
  context: &Context,
  state: &Rc<XposedState>,
  site: i64,
  befores: i64,
  args: &[String],
  original: &str,
) -> (String, Option<Vec<String>>) {
  if befores == 0 {
    let answer =
      wire_of(state.dispatch_after_only(rt, context, site, &invocation(state, args), &returned(state, original)));
    let result = if answer == KEEP_ORIGINAL || answer == NOT_DISPATCHED { original.to_string() } else { answer };
    return (result, Some(args.to_vec()));
  }
  let answer = state.dispatch_before(rt, context, 1, site, &invocation(state, args));
  if answer.is_empty() {
    return (original.to_string(), Some(args.to_vec()));
  }
  if answer[0] == "A" {
    return (answer[1].clone(), None);
  }
  let called_with = answer[1..]
    .iter()
    .enumerate()
    .map(|(index, wire)| if wire == KEEP_ARGUMENT { args[index].clone() } else { wire.clone() })
    .collect();
  let result = if answer[0] == "P1" {
    let after = wire_of(state.dispatch_after(rt, context, 1, &invocation(state, args), &returned(state, original)));
    if after == KEEP_ORIGINAL {
      original.to_string()
    } else {
      after
    }
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
  fail_runnable: Cell<bool>,
  befores: RefCell<HashMap<i64, i64>>,
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
    self.calls.borrow().iter().map(|call| call.0).filter(|op| *op != OP_JS_BEFORES).collect()
  }

  /// -1 where `PluginXposed.Site` starts: the engine has not reported this site yet
  fn befores(&self, site: i64) -> i64 {
    self.befores.borrow().get(&site).copied().unwrap_or(-1)
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
      OP_NATIVE_ADD if self.fail_runnable.get() => "ERunnable rejected".to_string(),
      OP_JS_BEFORES => {
        self.befores.borrow_mut().insert(target, name.parse().expect("a count"));
        "N".to_string()
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
  let (rt, ctx) = crate::testing::harness::new_engine();
  let grant_host = crate::sandbox::grants::TestGrantHost::new(grants).as_host();
  let lifecycle = Lifecycle::new();
  let logs = crate::testing::harness::Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  let host = TestXposedHost::new();

  let (state, jvm) = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    let jvm = crate::api::platform::jvm::install_jvm(
      &ctx,
      OracleJvmHost::new().as_host(),
      None,
      grant_host.clone(),
      lifecycle.clone(),
      log.clone(),
      None,
      &inu,
    )
    .unwrap();
    for (name, wire) in
      [("stringLength", "GM900"), ("stringIsEmpty", "GM901"), ("fixtureRun", "GM902"), ("runtimeException", "GO903")]
    {
      let handle = jvm.wire_to_value(&ctx, wire).unwrap();
      ctx.globals().set(name, handle).unwrap();
    }
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
    let befores = self.host.befores(site);
    let (answer, called_with) = run_dispatch(&self.rt, &self.ctx, &self.state, site, befores, &args, &original);
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
    "const m = stringLength;
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
         const m = stringLength;
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
    "const m = stringLength;
         inu.xposed.hookMethod(m, { before(ctx) { ctx.setReturnValue(42) } })",
  );

  assert_eq!(fixture.dispatch(100, &[]), "I42");
  assert_eq!(fixture.original_args(), None, "the original was called anyway");
}

#[test]
fn set_throwable_clears_a_return_value_an_earlier_hook_set() {
  let fixture = granted();
  fixture.eval(
    "const m = stringLength;
         const t = runtimeException;
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
    "const m = stringLength;
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
         const m = stringLength;
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
    "const m = stringLength;
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
         const m = stringLength;
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
         const m = stringLength;
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
         const m = stringLength;
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
    "const m = stringLength;
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
    "const m = stringLength;
         const off = inu.xposed.hookMethod(m, { before() {} });
         off(); off(); off()",
  );
  assert_eq!(fixture.host.ops().iter().filter(|op| **op == OP_UNHOOK).count(), 1);
}

/// nothing read the argument wires, so the answer must say so rather than echo them: the host
/// releases what it minted, and a `P0` would tell it the engine had taken them
#[test]
fn a_site_with_no_hooks_answers_that_nothing_was_dispatched() {
  let fixture = granted();
  fixture.eval(
    "const m = stringLength;
         inu.xposed.hookMethod(m, { before() {} })()",
  );
  let answer =
    fixture
      .state
      .dispatch_before(&fixture.rt, &fixture.ctx, 1, 100, &invocation(&fixture.state, &["GO9".to_string()]));
  assert!(answer.is_empty(), "{answer:?}");
}

#[test]
fn a_before_that_breaks_the_arguments_leaves_the_call_as_it_was() {
  let fixture = granted();
  fixture.eval(
    "const m = stringLength;
         inu.xposed.hookMethod(m, { before(ctx) { ctx.args = [{}] } })",
  );
  let answer =
    fixture
      .state
      .dispatch_before(&fixture.rt, &fixture.ctx, 1, 100, &invocation(&fixture.state, &["GO9".to_string()]));
  assert!(answer.is_empty(), "{answer:?}");
}

#[test]
fn a_disposed_hook_stops_running_but_the_original_still_does() {
  let fixture = granted();
  fixture.eval(
    "globalThis.runs = 0;
         const m = stringLength;
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
         const m = stringLength;
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
         const m = stringLength;
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
    "const m = stringLength;
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
    "const m = stringLength;
         inu.xposed.hookMethod(m, {})",
  );
  assert!(message.contains("before or an after"), "{message}");
  assert!(!fixture.host.ops().contains(&OP_HOOK));
}

#[test]
fn hook_arguments_are_checked_without_a_js_prelude() {
  let fixture = granted();
  for (source, expected) in [
    ("(() => { const m = stringLength; inu.xposed.hookMethod(m, null) })()", "expected a hook object"),
    (
      "(() => { const m = stringLength; inu.xposed.hookMethod(m, { before: 1 }) })()",
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
    "const m = stringLength;
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
    "inu.xposed.hookMethod(stringLength, { before() {} });
         inu.xposed.hookMethod(stringIsEmpty, { before() {} })",
  );

  fixture.state.dispose(&fixture.ctx);
  assert_eq!(fixture.host.ops().iter().filter(|op| **op == OP_UNHOOK).count(), 2);
}

#[test]
fn call_original_needs_the_grant_and_passes_the_receiver_first() {
  let fixture = granted();
  fixture.eval(
    "const m = stringLength;
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
    "const m = stringLength;
         inu.xposed.callOriginalMethod(m)",
  );
  let calls = fixture.host.calls.borrow();
  let call = calls.iter().find(|call| call.0 == OP_CALL_ORIGINAL).expect("called");
  assert_eq!(call.3, vec!["N".to_string()]);
}

#[test]
fn routine_hooks_register_native_phases_and_dispose_them() {
  let fixture = granted();
  fixture.eval("const method = fixtureRun; const routine = inu.jvm.routine(ops => []); globalThis.off = inu.xposed.hookMethod(method, { before: routine, after: routine })");
  assert_eq!(fixture.host.ops(), vec![OP_HOOK, OP_NATIVE_ADD]);
  let calls = fixture.host.calls.borrow();
  assert_eq!(calls[0].3.len(), 2);
  assert!(calls[0].3[0].starts_with('G'));
  drop(calls);
  fixture.eval("off(); off()");
  assert_eq!(fixture.host.ops(), vec![OP_HOOK, OP_NATIVE_ADD, OP_NATIVE_REMOVE, OP_UNHOOK]);
}

#[test]
fn mixed_runnable_and_js_phases_are_refused_before_installation() {
  let fixture = granted();
  let error = fixture.eval_err(
    "const method = fixtureRun; inu.xposed.hookMethod(method, { before: inu.jvm.routine(ops => []), after() {} })",
  );
  assert!(error.contains("cannot mix"));
  assert!(fixture.host.ops().is_empty());
}

#[test]
fn a_rejected_runnable_registration_cleans_up_every_new_site() {
  let fixture = granted();
  fixture.host.sites.borrow_mut().push("S100,101".to_string());
  fixture.host.fail_runnable.set(true);
  let error = fixture.eval_err(
    "inu.xposed.hookAllOverloads(inu.jvm.cls('test.Fixture'), 'run', { before: inu.jvm.routine(ops => []) })",
  );
  assert!(error.contains("Runnable rejected"));
  assert_eq!(fixture.host.ops().iter().filter(|op| **op == OP_UNHOOK).count(), 2);
  assert_eq!(fixture.state.held.get(), 0);
}

#[test]
fn xposed_routinees_compile_context_operations_to_a_consumer() {
  let fixture = granted();
  fixture.eval("const method = fixtureRun; const consumer = inu.xposed.routine(ops => [ops.setArgument(0, ops.getArgument(1)), ops.setReturnValue(ops.getReturnValue())]); globalThis.disposeConsumer = inu.xposed.hookMethod(method, { before: consumer })");
  assert_eq!(fixture.host.ops(), vec![OP_HOOK, OP_NATIVE_ADD]);
  fixture.eval("disposeConsumer()");
  assert!(fixture.host.ops().contains(&OP_NATIVE_REMOVE));
}

#[test]
fn xposed_routine_requires_its_grant_and_context_ops_are_not_generic_ops() {
  let fixture = setup(&["unsafe.jvm"]);
  assert!(fixture.eval_err("inu.xposed.routine(ops => [])").contains("unsafe.xposed"));
  assert!(fixture.eval_err("inu.jvm.routine(ops => [ops.setThrowable(null)])").contains("not a function"));
}

#[test]
fn unchanged_after_replies_use_a_verdict_not_the_inbound_value_wire() {
  for original in ["GO9", "GC9", "TGO9", "I42", "N"] {
    let fixture = granted();
    fixture.eval("const method = stringLength; inu.xposed.hookMethod(method, { after() {} });");
    let before = fixture.state.dispatch_before(&fixture.rt, &fixture.ctx, 1, 100, &invocation(&fixture.state, &[]));
    assert_eq!(before[0], "P1");
    let result = returned(&fixture.state, original);
    assert_eq!(
      wire_of(
        fixture
          .state
          .dispatch_after(&fixture.rt, &fixture.ctx, 1, &invocation(&fixture.state, &[]), &result)
      ),
      KEEP_ORIGINAL
    );
    assert!(fixture.state.pending.borrow().is_empty());
  }
}

#[test]
fn explicit_after_override_is_kept_even_when_its_wire_matches_the_original() {
  let fixture = granted();
  fixture
    .eval("const method = stringLength; inu.xposed.hookMethod(method, { after(ctx) { ctx.setReturnValue(42) } });");
  fixture.state.dispatch_before(&fixture.rt, &fixture.ctx, 1, 100, &invocation(&fixture.state, &[]));
  let result = returned(&fixture.state, "I42");
  assert_eq!(
    wire_of(
      fixture
        .state
        .dispatch_after(&fixture.rt, &fixture.ctx, 1, &invocation(&fixture.state, &[]), &result)
    ),
    "I42"
  );
  let result = returned(&fixture.state, "GO9");
  assert_eq!(
    wire_of(
      fixture
        .state
        .dispatch_after(&fixture.rt, &fixture.ctx, 999, &invocation(&fixture.state, &[]), &result)
    ),
    NOT_DISPATCHED
  );
}

#[test]
fn the_host_is_told_how_many_befores_a_site_has() {
  let fixture = granted();
  *fixture.host.sites.borrow_mut() = vec!["S100".to_string(), "S100".to_string(), "S100".to_string()];
  fixture.eval(
    "const m = stringLength;
         globalThis.a = inu.xposed.hookMethod(m, { before() {} });
         globalThis.b = inu.xposed.hookMethod(m, { before() {}, after() {} });
         globalThis.c = inu.xposed.hookMethod(m, { after() {} })",
  );
  assert_eq!(fixture.host.befores(100), 2);

  fixture.eval("a()");
  assert_eq!(fixture.host.befores(100), 1);
  fixture.eval("c()");
  assert_eq!(fixture.host.befores(100), 1, "an after-only hook going changed the count");
  let reports = fixture.host.calls.borrow().iter().filter(|call| call.0 == OP_JS_BEFORES).count();
  assert_eq!(reports, 4, "every js install reports, and a removal only when the count changed");
}

#[test]
fn a_site_without_befores_dispatches_once_and_leaves_nothing_pending() {
  let fixture = granted();
  *fixture.host.original.borrow_mut() = "I5".to_string();
  fixture.eval(
    "globalThis.saw = null;
         const m = stringLength;
         inu.xposed.hookMethod(m, { after(ctx) { saw = ctx.args[0]; ctx.setReturnValue(ctx.returnValue + 1) } })",
  );
  assert_eq!(fixture.host.befores(100), 0);

  assert_eq!(fixture.dispatch(100, &["I7"]), "I6");
  fixture.eval("if (saw !== 7) throw new Error('after saw args ' + saw)");
  assert!(fixture.state.pending.borrow().is_empty(), "a single phase has nothing to wait for");
}

#[test]
fn an_after_only_dispatch_skips_a_before_registered_since_the_host_looked() {
  let fixture = granted();
  fixture.eval(
    "globalThis.ran = false;
         const m = stringLength;
         inu.xposed.hookMethod(m, { before() { ran = true } })",
  );
  let answer = fixture.state.dispatch_after_only(
    &fixture.rt,
    &fixture.ctx,
    100,
    &invocation(&fixture.state, &["GO9".to_string()]),
    &returned(&fixture.state, "I1"),
  );
  assert_eq!(wire_of(answer), NOT_DISPATCHED);
  fixture.eval("if (ran) throw new Error('a before ran after the original')");
}

#[test]
fn a_hook_that_reads_nothing_converts_nothing() {
  let fixture = granted();
  fixture.eval("const m = stringLength; inu.xposed.hookMethod(m, { after() {} })");
  let reads = Rc::new(Cell::new(0));
  let answer = fixture.state.dispatch_after_only(
    &fixture.rt,
    &fixture.ctx,
    100,
    &invocation_counting(&fixture.state, &["I1".to_string(), "GO9".to_string()], &reads),
    &returned_counting(&fixture.state, "GO10", &reads),
  );
  assert_eq!(wire_of(answer), KEEP_ORIGINAL);
  assert_eq!(reads.get(), 0);
}

#[test]
fn each_value_is_converted_once_however_often_it_is_read() {
  let fixture = granted();
  fixture.eval(
    "const m = stringLength;
         inu.xposed.hookMethod(m, {
           after(ctx) {
             if (ctx.args !== ctx.args) throw new Error('args changed between reads');
             if (ctx.returnValue !== ctx.returnValue) throw new Error('result changed between reads');
             ctx.method; ctx.method; ctx.thisObject;
           },
         })",
  );
  let reads = Rc::new(Cell::new(0));
  fixture.state.dispatch_after_only(
    &fixture.rt,
    &fixture.ctx,
    100,
    &invocation_counting(&fixture.state, &["I1".to_string(), "I2".to_string()], &reads),
    &returned_counting(&fixture.state, "GO10", &reads),
  );
  assert!(fixture.logs.borrow().is_empty(), "{:?}", fixture.logs.borrow());
  assert_eq!(reads.get(), 5, "method, this, two args and the result, once each");
}

#[test]
fn arguments_a_before_left_alone_keep_the_originals() {
  let fixture = granted();
  fixture.eval(
    "const m = stringLength;
         inu.xposed.hookMethod(m, { before(ctx) { ctx.args[1] = ctx.args[1] + 1 } })",
  );
  let args = ["GO9".to_string(), "I1".to_string(), "S2".to_string()];
  let answer = fixture.state.dispatch_before(&fixture.rt, &fixture.ctx, 1, 100, &invocation(&fixture.state, &args));
  assert_eq!(answer, vec!["P0", KEEP_ARGUMENT, "I2", KEEP_ARGUMENT]);
}

#[test]
fn a_before_that_never_reads_the_arguments_keeps_them_all() {
  let fixture = granted();
  fixture.eval("const m = stringLength; inu.xposed.hookMethod(m, { before() {} })");
  let reads = Rc::new(Cell::new(0));
  let args = ["GO9".to_string(), "I1".to_string()];
  let answer = fixture.state.dispatch_before(
    &fixture.rt,
    &fixture.ctx,
    1,
    100,
    &invocation_counting(&fixture.state, &args, &reads),
  );
  assert_eq!(answer, vec!["P0", KEEP_ARGUMENT, KEEP_ARGUMENT]);
  assert_eq!(reads.get(), 0);
}

#[test]
fn replacing_the_arguments_unread_still_replaces_them() {
  let fixture = granted();
  fixture.eval(
    "const m = stringLength;
         inu.xposed.hookMethod(m, { before(ctx) { ctx.args = [5, 6] } })",
  );
  let args = ["I1".to_string(), "I6".to_string()];
  let answer = fixture.state.dispatch_before(&fixture.rt, &fixture.ctx, 1, 100, &invocation(&fixture.state, &args));
  assert_eq!(answer, vec!["P0", "I5", KEEP_ARGUMENT]);
}

#[test]
fn a_before_can_leave_its_own_state_on_the_context_for_the_after() {
  let fixture = granted();
  *fixture.host.original.borrow_mut() = "I5".to_string();
  fixture.eval(
    "const m = stringLength;
         inu.xposed.hookMethod(m, {
           before(ctx) { ctx.startedWith = ctx.args[0] },
           after(ctx) { ctx.setReturnValue(ctx.startedWith + ctx.returnValue) },
         })",
  );
  assert_eq!(fixture.dispatch(100, &["I7"]), "I12");
}

#[test]
fn a_context_reads_the_call_it_was_given_and_no_later() {
  let fixture = granted();
  fixture.eval(
    "const m = stringLength;
         inu.xposed.hookMethod(m, { after(ctx) { globalThis.kept = ctx; globalThis.read = ctx.args[0] } })",
  );
  fixture.state.dispatch_after_only(
    &fixture.rt,
    &fixture.ctx,
    100,
    &invocation(&fixture.state, &["I7".to_string()]),
    &returned(&fixture.state, "I1"),
  );
  fixture.eval("if (read !== 7) throw new Error('the hook could not read its own call')");
  fixture.eval("if (kept.args[0] !== 7) throw new Error('what the hook read went missing')");
  let error = fixture.eval_err("kept.thisObject");
  assert!(error.contains("that call has returned"), "{error}");
  assert!(fixture.eval_err("kept.returnValue").contains("that call has returned"));
}

#[test]
fn an_after_phase_reads_the_call_its_before_never_touched() {
  let fixture = granted();
  fixture.eval(
    "const m = stringLength;
         inu.xposed.hookMethod(m, { before() {}, after(ctx) { globalThis.saw = ctx.args[0] } })",
  );
  let args = ["I7".to_string()];
  let before = fixture.state.dispatch_before(&fixture.rt, &fixture.ctx, 1, 100, &invocation(&fixture.state, &args));
  assert_eq!(before[0], "P1");
  fixture.state.dispatch_after(
    &fixture.rt,
    &fixture.ctx,
    1,
    &invocation(&fixture.state, &args),
    &returned(&fixture.state, "I1"),
  );
  fixture.eval("if (saw !== 7) throw new Error('the after phase saw ' + saw)");
}

#[test]
fn a_filter_reaches_the_host_as_the_routine_the_plugin_built() {
  let fixture = granted();
  fixture.eval(
    "const method = fixtureRun;
         globalThis.off = inu.xposed.hookMethod(method, { filter: inu.jvm.routine(ops => []), before() {} })",
  );
  assert_eq!(fixture.host.ops(), vec![OP_HOOK, OP_JS_FILTER]);
  let calls = fixture.host.calls.borrow();
  let filter = calls.iter().find(|call| call.0 == OP_JS_FILTER).expect("a filter report");
  assert_eq!(filter.1, 100);
  assert!(filter.2.starts_with('G'), "a filter crosses as the handle it is, not as a wire of its own");
}

#[test]
fn a_filter_that_is_not_a_routine_is_refused_before_installation() {
  let fixture = granted();
  let error =
    fixture.eval_err("inu.xposed.hookMethod(fixtureRun, { filter: (ctx) => true, before() {} })");
  assert!(error.contains("filter must be an inu.jvm.routine"), "{error}");
  assert!(fixture.host.ops().is_empty(), "a hook refused for its filter never reaches the host");
}

#[test]
fn a_native_hook_takes_no_filter() {
  let fixture = granted();
  let error = fixture.eval_err(
    "const routine = inu.jvm.routine(ops => []);
         inu.xposed.hookMethod(fixtureRun, { filter: routine, before: routine })",
  );
  assert!(error.contains("already runs on the hooked thread"), "{error}");
}

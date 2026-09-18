use super::*;
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
  /// what the next op answers, in place of the default `N`
  answer: RefCell<Option<String>>,
  load_answer: RefCell<Option<String>>,
}

impl TestJvmHost {
  fn new() -> Rc<Self> {
    Rc::new(TestJvmHost {
      next_id: Cell::new(1),
      ..Default::default()
    })
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
    if let Some(answer) = self.answer.borrow_mut().take() {
      return answer;
    }
    match op {
      OP_PREPARE_CLASS => "S{\"ticket\":\"9000\",\"name\":\"plugin.Prepared\",\"superclass\":\"Ljava/lang/Object;\",\"interfaces\":[],\"fields\":[],\"methods\":[]}".into(),
      OP_LOAD_CLASS => self.load_answer.borrow_mut().take().unwrap_or_else(|| self.mint('C')),
      OP_CLASS => self.mint('C'),
      OP_RUNNABLE | OP_ROUTINE | OP_XPOSED_ROUTINE => self.mint('O'),
      OP_BUNDLE_METHOD => "SputParcelable".to_string(),
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
  let (rt, ctx) = crate::testing::harness::new_engine();
  let host = TestJvmHost::new();
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    install_jvm(
      &ctx,
      host.as_host(),
      None,
      TestGrantHost::new(grants).as_host(),
      Lifecycle::new(),
      std::sync::Arc::new(|_: &str| {}),
      &inu,
    )
    .unwrap()
  });
  let state = DisposeOnDrop::new(&ctx, state, |ctx, state| state.dispose(ctx));
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
fn a_plugin_holding_no_jvm_grant_is_refused_at_every_entry_point() {
  let f = setup(&["kv"]);
  for code in
    ["inu.jvm.cls('java.util.ArrayList')", "inu.jvm.runnable(() => {})", "inu.jvm.loadDex('/data/local/tmp/x.dex')"]
  {
    assert!(error_code(&f, code).starts_with("not-granted|unsafe.jvm"), "{code}");
  }
  assert!(f.host.calls().is_empty());
}

#[test]
fn load_dex_needs_the_grant() {
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

/// a java object handle the way the host hands one over, for a test that needs something to name
fn object_handle(f: &Fixture, name: &str) {
  f.ctx.with(|ctx| {
    let handle = f.state.wire_to_value(&ctx, "GO7").unwrap();
    ctx.globals().set(name, handle).unwrap();
  });
}

fn thrown_code(ctx: &Ctx<'_>) -> String {
  let thrown = ctx.catch();
  thrown
    .as_object()
    .and_then(|o| o.get::<_, Option<String>>("code").ok().flatten())
    .unwrap_or_else(|| "Error".to_string())
}

/// what a value [code] evaluates to crosses as, or the code of the refusal
fn wire_of(f: &Fixture, code: &str) -> String {
  f.ctx.with(|ctx| {
    let value: Value = ctx.eval(code).unwrap();
    match f.state.arg_to_wire(&ctx, &value) {
      Ok(wire) => wire,
      Err(rquickjs::Error::Exception) => thrown_code(&ctx),
      Err(e) => panic!("{e:?}"),
    }
  })
}

/// [probe] run over what [wire] decodes to, or the code of the refusal
fn decoded(f: &Fixture, wire: &str, probe: &str) -> String {
  f.ctx.with(|ctx| match f.state.wire_to_value(&ctx, wire) {
    Ok(value) => {
      ctx.globals().set("v", value).unwrap();
      ctx.eval::<String, _>(probe).unwrap()
    }
    Err(rquickjs::Error::Exception) => thrown_code(&ctx),
    Err(e) => panic!("{e:?}"),
  })
}

#[test]
fn only_values_java_can_be_handed_without_guessing_cross() {
  let f = setup(&["unsafe.jvm"]);
  object_handle(&f, "o");
  for (code, wire) in [
    ("null", "N"),
    ("undefined", "N"),
    ("true", "B1"),
    ("7", "I7"),
    ("7.0", "I7"),
    ("1.5", "D1.5"),
    ("'x'", "Sx"),
    ("9007199254740993n", "I9007199254740993"),
    ("new Uint8Array([0])", "YAA=="),
    ("o", "G7"),
  ] {
    assert_eq!(wire_of(&f, code), wire, "{code}");
  }
  // wider than a java long, which `to_i64` would have truncated
  for code in ["({})", "[]", "(() => {})", "Symbol()", "92233720368547758070n"] {
    assert_eq!(wire_of(&f, code), "invalid-argument", "{code}");
  }
}

#[test]
fn a_value_past_the_bound_is_refused() {
  let f = setup(&["unsafe.jvm"]);
  let over = VALUE_LIMIT_BYTES + 1;
  assert_eq!(wire_of(&f, &format!("'x'.repeat({over})")), "quota-exceeded");
  assert_eq!(wire_of(&f, &format!("new Uint8Array({over})")), "quota-exceeded");
  assert!(wire_of(&f, &format!("'x'.repeat({VALUE_LIMIT_BYTES})")).starts_with('S'));
}

/// a java `long` is 64 bits and an `access_hash` uses all of them, so the alternative to a
/// bigint here is a number that is quietly not the one java holds
#[test]
fn a_long_that_a_js_number_cannot_hold_arrives_as_a_bigint() {
  let f = setup(&["unsafe.jvm"]);
  assert_eq!(decoded(&f, "I9007199254740993", "typeof v + ':' + v"), "bigint:9007199254740993");
  assert_eq!(decoded(&f, "I9007199254740991", "typeof v"), "number");
  assert_eq!(decoded(&f, "I-9007199254740993", "typeof v"), "bigint");
}

#[test]
fn every_scalar_the_host_answers_with_decodes_the_way_the_tl_bridge_decodes_it() {
  let f = setup(&["unsafe.jvm"]);
  for (wire, expected) in
    [("N", "null"), ("Shello", r#""hello""#), ("I42", "42"), ("D1.5", "1.5"), ("B1", "true"), ("B0", "false")]
  {
    assert_eq!(decoded(&f, wire, "JSON.stringify(v)"), expected, "{wire}");
  }
  assert_eq!(decoded(&f, "YAAEC", "Array.from(v).join(',')"), "0,1,2");
}

#[test]
fn a_host_error_wire_throws_the_typed_error_it_names() {
  let f = setup(&["unsafe.jvm"]);
  assert_eq!(decoded(&f, "Phandle-expired\n\n\n\ngone", "''"), "handle-expired");
  assert_eq!(decoded(&f, "Ejava.lang.IllegalStateException: nope", "''"), "Error");
}

#[test]
fn member_access_without_a_vm_is_refused_rather_than_asked_of_the_host() {
  let f = setup(&["unsafe.jvm"]);
  object_handle(&f, "o");
  for code in ["o.getField('x')", "o.call('m')", "new (inu.jvm.cls('java.lang.Object'))()"] {
    assert_eq!(error_code(&f, code), "unsupported|", "{code}");
  }
  assert!(f.host.calls().iter().all(|call| call.starts_with(&format!("{OP_CLASS}|"))));
}

#[test]
fn call_super_refuses_rather_than_approximates() {
  let f = setup(&["unsafe.jvm"]);
  assert_eq!(error_code(&f, "inu.jvm.callSuper({}, 'toString')"), "unsupported|");
  assert!(f.host.calls().is_empty());
}

/// the table is the plugin's only hold on a java reference, and a handle whose js side is gone is
/// one nothing can name again - so the reference goes with it, without a finalizer job
#[test]
fn a_handle_whose_js_side_is_gone_releases_its_reference() {
  let f = setup(&["unsafe.jvm"]);
  let id = f.state.refs().mint(jni::objects::Global::null(), b'O').unwrap();
  assert_eq!(f.state.refs().len(), 1);
  f.ctx.with(|ctx| {
    let handle = f.state.wire_to_value(&ctx, &format!("GO{id}")).unwrap();
    ctx.globals().set("o", handle).unwrap();
    assert_eq!(f.state.refs().len(), 1, "a live handle keeps its entry");
    ctx.eval::<(), _>("globalThis.o = null").unwrap();
  });
  f._rt.run_gc();
  assert_eq!(f.state.refs().len(), 0, "the object handle must have released its reference");
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
  f.ctx
    .with(|ctx| ctx.eval::<(), _>("globalThis.ran = 0; inu.jvm.runnable(() => { globalThis.ran++ })").unwrap());
  f.state.dispatch_callback(&f._rt, &f.ctx, 1);
  f.state.dispatch_callback(&f._rt, &f.ctx, 1);
  assert_eq!(eval(&f, "ran"), "2");
  // one nothing was ever registered for is a no-op rather than a failure
  f.state.dispatch_callback(&f._rt, &f.ctx, 99);
  assert_eq!(eval(&f, "ran"), "2");
}

#[test]
fn a_runnable_made_during_unload_never_fires() {
  let f = setup(&["unsafe.jvm"]);
  f.ctx.with(|ctx| ctx.eval::<(), _>("globalThis.ran = 0").unwrap());
  f.state.lifecycle.begin_unload();
  f.ctx.with(|ctx| ctx.eval::<(), _>("inu.jvm.runnable(() => { globalThis.ran++ })").unwrap());
  f.state.dispatch_callback(&f._rt, &f.ctx, 1);
  assert_eq!(eval(&f, "ran"), "0");
}

#[test]
fn a_throwing_callback_is_the_plugins_fault() {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let logged = crate::testing::harness::Logs::new();
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    install_jvm(
      &ctx,
      TestJvmHost::new().as_host(),
      None,
      TestGrantHost::new(&["unsafe.jvm"]).as_host(),
      Lifecycle::new(),
      crate::testing::harness::log_sink(&logged),
      &inu,
    )
    .unwrap()
  });
  let state = DisposeOnDrop::new(&ctx, state, |ctx, state| state.dispose(ctx));
  ctx.with(|ctx| ctx.eval::<(), _>("inu.jvm.runnable(() => { throw new Error('boom') })").unwrap());
  state.dispatch_callback(&rt, &ctx, 1);
  let logged = logged.borrow().clone();
  assert_eq!(logged.len(), 1, "{logged:?}");
  assert!(logged[0].starts_with('\u{1}'), "a plugin's own throw must reach the host as a fault: {logged:?}");
}

/// the id lives under a symbol the prelude never publishes, so there is nothing on a handle a
/// plugin could copy onto an object of its own
#[test]
fn a_handle_cannot_be_forged_out_of_what_js_can_see() {
  let f = setup(&["unsafe.jvm"]);
  object_handle(&f, "o");
  assert_eq!(eval(&f, "JSON.stringify([Object.keys(o), Object.getOwnPropertySymbols(o).length])"), r#""[[],0]""#,);
  assert_eq!(error_code(&f, "({ ...o }).getField('x')"), "TypeError|");
}

/// The bundled oracle is the only thing that runs this surface on a device, and an oracle nobody
/// runs is one nobody notices going green on a broken engine. The host below stands in for
/// `PluginJvm` - what it cannot stand in for (real reflection, the per-class scope checks, dex)
/// is pinned in `PluginJvmTest` instead.
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
  }

  impl OracleJvmHost {
    pub(crate) fn new() -> Rc<Self> {
      Rc::new(OracleJvmHost { next_id: Cell::new(1) })
    }

    pub(crate) fn as_host(self: &Rc<Self>) -> Rc<dyn JvmHost> {
      self.clone()
    }

    fn mint(&self, kind: char) -> String {
      let id = self.next_id.get();
      self.next_id.set(id + 1);
      format!("G{kind}{id}")
    }
  }

  impl JvmHost for OracleJvmHost {
    fn jvm(&self, op: i32, _target: i64, _name: &str, _args: &[String]) -> String {
      match op {
        OP_CLASS => self.mint('C'),
        OP_RUNNABLE | OP_ROUTINE | OP_XPOSED_ROUTINE => self.mint('O'),
        _ => "N".to_string(),
      }
    }
  }
}

#[test]
fn routinees_build_one_host_program_without_executing_members() {
  let f = setup(&["unsafe.jvm"]);
  object_handle(&f, "obj");
  let before = f.host.calls().len();
  eval(&f, "inu.jvm.routine(ops => { const x = ops.getField(obj, 'count'); return [ops.setField(obj, 'count', ops.math('+', x, 2))] })");
  let calls = f.host.calls();
  assert_eq!(calls.len(), before + 1);
  assert!(calls.last().unwrap().starts_with("16|0|"));
  assert!(calls.last().unwrap().contains("\"math\",\"+\""));
}

#[test]
fn routine_tokens_are_scoped_and_builders_close_on_success_and_failure() {
  let f = setup(&["unsafe.jvm"]);
  eval(
    &f,
    "inu.jvm.routine(ops => { globalThis.saved = ops; globalThis.token = ops.math('+', 1, 2); return [token] })",
  );
  for code in [
    "saved.math('+', 1, 2)",
    "inu.jvm.routine(ops => [token])",
    "inu.jvm.routine(ops => [ops.math('+', token, 2)])",
    "inu.jvm.routine(ops => [ops.math('?', 1, 2)])",
    "inu.jvm.routine(ops => Promise.resolve([]))",
  ] {
    assert!(eval(&f, code).contains("routine:"), "{code}");
  }
  eval(&f, "try { inu.jvm.routine(ops => { globalThis.failed = ops; throw Error('stop') }) } catch {} ");
  assert!(eval(&f, "failed.math('+', 1, 2)").contains("closed"));
}

#[test]
fn routine_comparisons_and_logic_build_symbolic_nodes() {
  let f = setup(&["unsafe.jvm"]);
  eval(&f, "inu.jvm.routine(ops => [ops.and(ops.compare('>', 3, 2), ops.or(false, ops.not(null)))])");
  let calls = f.host.calls();
  assert_eq!(calls.len(), 1);
  for kind in ["compare", "and", "or", "not"] {
    assert!(calls[0].contains(&format!("\"{kind}\"")));
  }
  assert!(eval(&f, "inu.jvm.routine(ops => [ops.compare('===', 1, 1)])").contains("unsupported comparison"));
  eval(&f, "inu.jvm.routine(ops => { globalThis.foreign = ops.not(false); return [] })");
  assert!(eval(&f, "inu.jvm.routine(ops => [ops.and(true, foreign)])").contains("foreign"));
}

#[test]
fn routine_locals_use_names_and_closed_builders_are_refused() {
  let f = setup(&["unsafe.jvm"]);
  eval(&f, "inu.jvm.routine(ops => { globalThis.localOps = ops; return [ops.set('count', 1), ops.set('count', ops.math('+', ops.get('count'), 2))] })");
  let calls = f.host.calls();
  assert_eq!(calls.len(), 1);
  for kind in ["getLocal", "setLocal"] {
    assert!(calls[0].contains(&format!("\"{kind}\"")));
  }
  for code in [
    "localOps.get('count')",
    "localOps.set('count', 2)",
    "inu.jvm.routine(ops => [ops.get('')])",
    "inu.jvm.routine(ops => [ops.set(3, 2)])",
  ] {
    let error = eval(&f, code);
    assert!(error.contains("routine:") || error.contains("expected a name"), "{code}");
  }
}

#[test]
fn define_class_serializes_members_and_registers_synchronous_bodies() {
  let f = setup(&["unsafe.jvm"]);
  assert_eq!(eval(&f, "typeof inu.jvm.defineClass('plugin.Test', { fields: { count: 'int' }, staticFields: { label: 'java.lang.String' }, methods: { add: { params: ['int'], returns: 'int', body: (self, value) => value + 2 } }, constructors: [{ params: ['int'], super: [], init: self => {} }] })"), "\"function\"");
  let call = f.host.calls().into_iter().find(|call| call.starts_with("18|")).unwrap();
  assert!(call.contains("\"fields\":[[\"count\",\"int\",false],[\"label\",\"java.lang.String\",true]]"));
  assert!(call.contains("\"body\":[\"js\",0]"));
  assert!(call.ends_with("|I1,I2"));
  f.ctx.with(|ctx| {
    assert_eq!(f.state.dispatch_method(&ctx, 1, "N", &["I40".into()]), "I42");
  });
}

#[test]
fn define_class_cleans_up_callbacks_when_the_host_refuses() {
  let f = setup(&["unsafe.jvm"]);
  f.host.answers("Pinvalid-argument\n\n\n\ninvalid class");
  assert_eq!(
    error_code(&f, "inu.jvm.defineClass('plugin.Bad', { methods: { run: self => 42 } })"),
    "invalid-argument|"
  );
  f.ctx.with(|ctx| assert!(f.state.callbacks.restore(&ctx, 1).is_none()));
}

#[test]
fn define_class_refuses_malformed_specs() {
  let f = setup(&["unsafe.jvm"]);
  for code in [
    "inu.jvm.defineClass('plugin.Test', null)",
    "inu.jvm.defineClass('plugin.Test', { typo: true })",
    "inu.jvm.defineClass('plugin.Test', { interfaces: {} })",
    "inu.jvm.defineClass('plugin.Test', { methods: { run: { body: 42 } } })",
    "inu.jvm.defineClass('plugin.Test', { constructors: [{ super: [{ arg: -1 }] }] })",
    "inu.jvm.defineClass('plugin.Test', { constructors: [{ super: [{ value: () => 1 }] }] })",
  ] {
    assert_eq!(error_code(&f, code), "invalid-argument|", "{code}");
  }
  assert!(f.host.calls().is_empty());
}

#[test]
fn define_class_refuses_promise_results_and_transports_exceptions() {
  let f = setup(&["unsafe.jvm"]);
  eval(&f, "inu.jvm.defineClass('plugin.Test', { methods: { asyncResult: () => Promise.resolve(42), throwing: () => { throw Error('method failed') } } })");
  f.ctx.with(|ctx| {
    assert!(f.state.dispatch_method(&ctx, 1, "N", &[]).contains("must be synchronous"));
    assert!(f.state.dispatch_method(&ctx, 2, "N", &[]).contains("method failed"));
    assert!(f.state.dispatch_method(&ctx, 999, "N", &[]).contains("expired"));
  });
}

#[test]
fn method_routines_build_interpreted_receiver_argument_and_result_operations() {
  let f = setup(&["unsafe.jvm"]);
  eval(
    &f,
    "inu.jvm.routine(ops => [ops.getThisObject(), ops.setReturnValue(ops.math('+', ops.getArgument(0), 2))])",
  );
  let calls = f.host.calls();
  assert_eq!(calls.len(), 1);
  assert!(calls[0].contains("methodThis"));
  assert!(calls[0].contains("methodArgument"));
  assert!(calls[0].contains("methodSetResult"));
}

#[test]
fn define_class_keeps_callable_java_classes_as_handles_and_routines_as_handles() {
  let f = setup(&["unsafe.jvm"]);
  eval(&f, "const base = inu.jvm.cls('java.lang.Object'); const body = inu.jvm.routine(ops => []); inu.jvm.defineClass('plugin.Test', { superclass: base, methods: { run: { body } } })");
  let call = f.host.calls().into_iter().find(|call| call.starts_with("18|")).unwrap();
  assert!(call.ends_with("|G1,G2"), "{call}");
  assert!(f.state.callbacks.is_empty());
}

#[test]
fn define_class_emits_between_preparation_and_loading() {
  let f = setup(&["unsafe.jvm"]);
  eval(&f, "inu.jvm.defineClass('plugin.Test', {})");
  let calls = f.host.calls();
  assert_eq!(calls.len(), 2);
  assert!(calls[0].starts_with("18|0|"));
  let encoded = calls[1].strip_prefix("20|9000||Y").unwrap();
  let bytes = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, encoded).unwrap();
  assert!(bytes.starts_with(b"dex\n035\0"));
  assert!(!calls.iter().any(|call| call.starts_with("21|")));
}

#[test]
fn emission_and_load_failures_cancel_preparation_and_callbacks() {
  let f = setup(&["unsafe.jvm"]);
  f.host.answers("S{\"ticket\":\"9000\",\"name\":\"plugin.Bad\",\"superclass\":\"invalid\",\"interfaces\":[],\"fields\":[],\"methods\":[]}");
  assert_eq!(
    error_code(&f, "inu.jvm.defineClass('plugin.Bad', { methods: { run: () => {} } })"),
    "invalid-argument|"
  );
  assert!(f.host.calls().iter().any(|call| call == "21|9000||"));
  assert!(!f.host.calls().iter().any(|call| call.starts_with("20|")));
  assert!(f.state.callbacks.is_empty());

  let f = setup(&["unsafe.jvm"]);
  *f.host.load_answer.borrow_mut() = Some("Pinvalid-argument\n\n\n\nload failed".into());
  assert_eq!(
    error_code(&f, "inu.jvm.defineClass('plugin.Bad', { methods: { run: () => {} } })"),
    "invalid-argument|"
  );
  assert!(f.host.calls().iter().any(|call| call == "21|9000||"));
  assert!(f.state.callbacks.is_empty());
}

#[test]
fn only_runnables_created_during_cleanup_are_admitted_after_stop() {
  let f = setup(&["unsafe.jvm"]);
  eval(&f, "inu.jvm.runnable(() => {})");
  f.state.lifecycle.begin_cleanup();
  eval(&f, "inu.jvm.runnable(() => {})");
  assert!(!f.state.accepts_cleanup_callback(1));
  assert!(f.state.accepts_cleanup_callback(2));
  f.state.lifecycle.finish_cleanup();
  assert!(!f.state.accepts_cleanup_callback(2));
}

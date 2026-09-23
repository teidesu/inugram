use super::*;
use crate::sandbox::grants::CachedGrantHost;
use crate::testing::harness::DisposeOnDrop;
use std::cell::Cell;

/// Deliberately dumb: which class, overload or scope is Kotlin's decision, pinned in `PluginJvmTest`.
/// Shared with `xposed`, whose every entry point takes a handle this mints.
#[derive(Default)]
pub(crate) struct TestJvmHost {
  calls: RefCell<Vec<String>>,
  next_id: Cell<i64>,
  /// what the next op answers, in place of the default `N`
  answer: RefCell<Option<String>>,
  load_answer: RefCell<Option<String>>,
}

impl TestJvmHost {
  pub(crate) fn new() -> Rc<Self> {
    Rc::new(TestJvmHost {
      next_id: Cell::new(1),
      ..Default::default()
    })
  }

  pub(crate) fn as_host(self: &Rc<Self>) -> Rc<dyn JvmHost> {
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
  logs: std::sync::Arc<crate::testing::harness::Logs>,
}

fn setup(grants: &[&str]) -> Fixture {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let host = TestJvmHost::new();
  let logs = crate::testing::harness::Logs::new();
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    install_jvm(
      &ctx,
      host.as_host(),
      None,
      CachedGrantHost::new(grants),
      Lifecycle::new(),
      crate::testing::harness::log_sink(&logs),
      None,
      &inu,
    )
    .unwrap()
  });
  let state = DisposeOnDrop::new(&ctx, state, |ctx, state| state.dispose(ctx));
  Fixture { _rt: rt, ctx, host, state, logs }
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
  let f = setup(&["openUrl"]);
  for code in [
    "inu.jvm.cls('java.util.ArrayList')",
    "inu.jvm.runnable(() => {})",
    "inu.jvm.loadDex('/data/local/tmp/x.dex')",
    "inu.jvm.callSuper({}, {}, 'toString')",
    "inu.jvm.fromTl({ _: 'messageEntityBold' })",
    "inu.jvm.toTl({})",
  ] {
    assert!(error_code(&f, code).starts_with("not-granted|unsafe.jvm"), "{code}");
  }
  assert!(f.host.calls().is_empty());
}

/// anything but a java handle names no java object, so the answer is `false` without asking the vm.
/// What a real handle answers is `Class.isInstance` and is pinned on a device
#[test]
fn is_instance_answers_false_for_anything_but_a_handle_without_reaching_the_vm() {
  let f = setup(&["unsafe.jvm"]);
  eval(&f, "globalThis.chat = inu.jvm.cls('org.telegram.tgnet.TLRPC$Chat')");
  let calls = f.host.calls().len();
  for value in
    ["null", "undefined", "7", "'text'", "true", "1.5", "2n ** 70n", "new Uint8Array([1])", "({})", "() => {}"]
  {
    assert_eq!(eval(&f, &format!("chat.isInstance({value})")), "false", "{value}");
  }
  assert_eq!(f.host.calls().len(), calls, "the vm was asked");
}

/// what crosses for each shape: a view goes as the handle it already names, a plain object as its
/// json, and neither is interpreted on this side
#[test]
fn from_tl_sends_a_view_as_its_handle_and_an_object_as_json() {
  let f = setup(&["unsafe.jvm"]);
  f.host.answers("GO7");
  assert_eq!(eval(&f, "typeof inu.jvm.fromTl({ _: 'messageEntityBold', offset: 0, length: 2 })"), r#""object""#);
  assert_eq!(
    f.host.calls().last().unwrap(),
    &format!(r#"{OP_FROM_TL}|0|J{{"_":"messageEntityBold","offset":0,"length":2}}|"#),
  );
  for bad in ["inu.jvm.fromTl(null)", "inu.jvm.fromTl(7)", "inu.jvm.fromTl('x')"] {
    assert_eq!(error_code(&f, bad), "invalid-argument|", "{bad}");
  }
}

#[test]
fn to_tl_takes_a_handle_and_nothing_else() {
  let f = setup(&["unsafe.jvm"]);
  for bad in ["inu.jvm.toTl(null)", "inu.jvm.toTl(7)", "inu.jvm.toTl({ _: 'messageEntityBold' })"] {
    assert_eq!(error_code(&f, bad), "invalid-argument|", "{bad}");
  }
  // a real handle gets past that and finds this fixture has no view table, which is the one thing
  // `toTl` cannot do without
  assert_eq!(error_code(&f, "inu.jvm.toTl(inu.jvm.cls('java.lang.Object'))"), "unsupported|");
}

#[test]
fn load_dex_takes_a_path_or_bytes_and_nothing_else() {
  let f = setup(&["unsafe.jvm"]);
  for code in ["inu.jvm.loadDex(42)", "inu.jvm.loadDex(null)", "inu.jvm.loadDex({})"] {
    assert_eq!(error_code(&f, code), "invalid-argument|", "{code}");
  }
  assert_eq!(eval(&f, "inu.jvm.loadDex('/data/local/tmp/x.dex')"), "undefined");
  assert_eq!(f.host.calls().last().unwrap(), &format!("{OP_LOAD_DEX}|0|/data/local/tmp/x.dex|"));
  assert_eq!(eval(&f, "inu.jvm.loadDex(new Uint8Array([1, 2, 3]))"), "undefined");
  assert_eq!(f.host.calls().last().unwrap(), &format!("{OP_LOAD_DEX}|0||YAQID"));
}

#[test]
fn a_dex_past_the_bound_is_refused_before_it_is_copied() {
  let f = setup(&["unsafe.jvm"]);
  let code = format!("inu.jvm.loadDex(new Uint8Array({}))", DEX_LIMIT_BYTES + 1);
  assert_eq!(error_code(&f, &code), "quota-exceeded|");
  assert!(f.host.calls().is_empty());
  let code = format!("inu.jvm.loadDex(new Uint8Array({}))", DEX_LIMIT_BYTES);
  assert_eq!(error_code(&f, &code), "no-throw");
}

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

fn encode_arg_wire(f: &Fixture, code: &str) -> String {
  f.ctx.with(|ctx| {
    let value: Value = ctx.eval(code).unwrap();
    match f.state.arg_to_wire(&ctx, &value) {
      Ok(wire) => wire,
      Err(rquickjs::Error::Exception) => thrown_code(&ctx),
      Err(e) => panic!("{e:?}"),
    }
  })
}

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
    assert_eq!(encode_arg_wire(&f, code), wire, "{code}");
  }
  // wider than a java long, which `to_i64` would have truncated
  for code in ["({})", "[]", "(() => {})", "Symbol()", "92233720368547758070n"] {
    assert_eq!(encode_arg_wire(&f, code), "invalid-argument", "{code}");
  }
}

#[test]
fn a_value_past_the_bound_is_refused() {
  let f = setup(&["unsafe.jvm"]);
  let over = VALUE_LIMIT_BYTES + 1;
  assert_eq!(encode_arg_wire(&f, &format!("'x'.repeat({over})")), "quota-exceeded");
  assert_eq!(encode_arg_wire(&f, &format!("new Uint8Array({over})")), "quota-exceeded");
  assert!(encode_arg_wire(&f, &format!("'x'.repeat({VALUE_LIMIT_BYTES})")).starts_with('S'));
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
fn call_super_validates_its_arguments_before_reaching_the_vm() {
  let f = setup(&["unsafe.jvm"]);
  object_handle(&f, "o");
  let cls = "inu.jvm.cls('java.lang.Object')";
  for code in [
    format!("inu.jvm.callSuper({{}}, o, 'toString')"),
    format!("inu.jvm.callSuper({cls}, o, '')"),
    format!("inu.jvm.callSuper({cls}, o, 7)"),
  ] {
    assert_eq!(error_code(&f, &code), "invalid-argument|", "{code}");
  }
  assert_eq!(error_code(&f, &format!("inu.jvm.callSuper({cls}, o, 'toString')")), "unsupported|");
  assert!(f.host.calls().iter().all(|call| call.starts_with(&format!("{OP_CLASS}|"))));
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
  let f = setup(&["unsafe.jvm"]);
  eval(&f, "inu.jvm.runnable(() => { throw new Error('boom') })");
  f.state.dispatch_callback(&f._rt, &f.ctx, 1);
  let logged = f.logs.borrow().clone();
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

pub(crate) const EMPTY_ROUTINE: &str =
  "{ v: 1, source: 'function () {}', captures: [], slots: 0, code: [['this']], tries: [] }";

#[test]
fn a_compiled_routine_crosses_as_one_program_the_host_can_read() {
  let f = setup(&["unsafe.jvm"]);
  object_handle(&f, "obj");
  let before = f.host.calls().len();
  eval(
    &f,
    "inu.jvm.routine({ v: 1, source: 'function () {}', captures: ['obj'], slots: 0, \
       code: [['capture', 0], ['get', 0, ['count']], ['this'], ['arg', [0]], ['add', 1, [2]], ['set', 0, ['count'], 2], \
       ['return', 2]], tries: [] }, [obj])",
  );
  let calls = f.host.calls();
  assert_eq!(calls.len(), before + 1);
  let call = calls.last().unwrap();
  assert!(call.starts_with("16|0|"), "{call}");
  for instruction in ["capture", "get", "this", "arg", "add", "set", "return"] {
    assert!(call.contains(&format!("\"{instruction}\"")), "{call}");
  }
  assert!(!call.contains("source"), "the source stays in the bundle, it is not the host's: {call}");
}

#[test]
fn an_array_capture_crosses_flattened_under_the_shape_it_had() {
  let f = setup(&["unsafe.jvm"]);
  object_handle(&f, "obj");
  eval(
    &f,
    "inu.jvm.routine({ v: 1, source: '', captures: ['obj', 'table'], slots: 0, code: [['capture', 1]], tries: [] }, \
       [obj, [1, ['deep', 2]]])",
  );
  let call = f.host.calls().into_iter().next_back().unwrap();
  assert!(call.contains("\"layout\":[-1,[-1,[-1,-1]]]"), "{call}");
  let wires = call.rsplit('|').next().unwrap();
  assert_eq!(wires.split(',').count(), 4, "one wire per leaf: {call}");
}

#[test]
fn a_routine_whose_shape_or_captures_do_not_fit_is_refused() {
  let f = setup(&["unsafe.jvm"]);
  assert!(eval(&f, "inu.jvm.routine(() => {})").contains("@inugram/cli"));
  for code in [
    "inu.jvm.routine(42)",
    "inu.jvm.routine({ v: 2, captures: [], code: [] })",
    "inu.jvm.routine({ v: 1, captures: [], code: 'nope' })",
    "inu.jvm.routine({ v: 1, captures: ['a'], code: [] }, [])",
    "inu.jvm.routine({ v: 1, captures: [], code: [] }, 7)",
    "inu.jvm.routine({ v: 1, captures: ['a'], code: [] }, [() => {}])",
  ] {
    assert!(eval(&f, code).contains("routine:"), "{code}");
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
    "inu.jvm.defineClass('plugin.Test', { constructors: [{ super: 'x' }] })",
    "inu.jvm.defineClass('plugin.Test', { constructors: [{ super: [], superParams: ['int'] }] })",
    "inu.jvm.defineClass('plugin.Test', { methods: { run: [{ body: () => {} }] } })",
    "inu.jvm.defineClass('plugin.Test', { methods: { run: [() => {}] } })",
  ] {
    assert_eq!(error_code(&f, code), "invalid-argument|", "{code}");
  }
  assert!(f.host.calls().is_empty());
}

#[test]
fn define_class_serializes_each_overload_as_its_own_method() {
  let f = setup(&["unsafe.jvm"]);
  eval(
    &f,
    "inu.jvm.defineClass('plugin.Test', { methods: { add: [{ params: ['int'], returns: 'int', body: (self, a) => a + 1 }, { params: ['java.lang.String'], returns: 'java.lang.String', body: (self, a) => a + '!' }] } })",
  );
  let call = f.host.calls().into_iter().find(|call| call.starts_with("18|")).unwrap();
  assert!(call.contains("{\"name\":\"add\",\"params\":[\"int\"]"), "{call}");
  assert!(call.contains("{\"name\":\"add\",\"params\":[\"java.lang.String\"]"), "{call}");
  f.ctx.with(|ctx| {
    assert_eq!(f.state.dispatch_method(&ctx, 1, "N", &["I1".into()]), "I2");
    assert_eq!(f.state.dispatch_method(&ctx, 2, "N", &["Sa".into()]), "Sa!");
  });
}

/// the super function runs before the object exists, so it is handed the constructor's arguments
/// without a receiver, and its array crosses as one list
#[test]
fn define_class_serializes_a_super_function_and_hands_it_the_arguments_alone() {
  let f = setup(&["unsafe.jvm"]);
  eval(
    &f,
    "inu.jvm.defineClass('plugin.Test', { constructors: [{ params: ['int'], superParams: ['java.lang.String', 'int'], super: (n) => [`item ${n}`, n * 2] }] })",
  );
  let call = f.host.calls().into_iter().find(|call| call.starts_with("18|")).unwrap();
  assert!(
    call.contains("\"super\":[],\"superBody\":[\"js\",0],\"superParams\":[\"java.lang.String\",\"int\"]"),
    "{call}"
  );
  f.ctx.with(|ctx| {
    assert_eq!(f.state.dispatch_method(&ctx, 1, "N", &["I4".into()]), r#"L["Sitem 4","I8"]"#);
  });
}

#[test]
fn an_array_result_crosses_as_a_list_and_refuses_nesting_and_expired_handles() {
  let f = setup(&["unsafe.jvm"]);
  eval(
    &f,
    "globalThis.cls = inu.jvm.cls('java.lang.Object'); inu.jvm.defineClass('plugin.Test', { methods: { flat: () => [1, 'a', null, true], nested: () => [1, [1]], expired: () => [1, cls] } })",
  );
  f.ctx.with(|ctx| {
    assert_eq!(f.state.dispatch_method(&ctx, 1, "N", &[]), r#"L["I1","Sa","N","B1"]"#);
    assert!(f.state.dispatch_method(&ctx, 2, "N", &[]).contains("cannot nest arrays"));
    assert!(
      f.state.dispatch_method(&ctx, 3, "N", &[]).contains("released"),
      "a handle the table no longer has fails the whole list"
    );
  });
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
fn define_class_keeps_callable_java_classes_as_handles_and_routines_as_handles() {
  let f = setup(&["unsafe.jvm"]);
  eval(
    &f,
    &format!(
      "const base = inu.jvm.cls('java.lang.Object'); const body = inu.jvm.routine({EMPTY_ROUTINE}); \
       inu.jvm.defineClass('plugin.Test', {{ superclass: base, methods: {{ run: {{ body }} }} }})"
    ),
  );
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
fn a_class_reports_the_name_the_host_settled_on_whether_or_not_it_asked_for_one() {
  let f = setup(&["unsafe.jvm"]);
  assert_eq!(eval(&f, "inu.jvm.defineClass({ methods: { run: () => {} } }).name"), "\"plugin.Prepared\"");
  assert_eq!(eval(&f, "inu.jvm.defineClass('plugin.Test', {}).name"), "\"plugin.Prepared\"");
  let asked: Vec<String> = f.host.calls().into_iter().filter(|call| call.starts_with("18|")).collect();
  assert!(asked[0].contains("\"name\":null"), "{}", asked[0]);
  assert!(asked[1].contains("\"name\":\"plugin.Test\""), "{}", asked[1]);
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

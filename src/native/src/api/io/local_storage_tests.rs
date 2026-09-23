use super::*;
use crate::api::error::install_plugin_error;
use crate::testing::harness::{get_api_globals, TempPath};
use rquickjs::{Context, Runtime};

/// the context drops before its runtime, and the store with it
struct Fixture {
  ctx: Context,
  _rt: Runtime,
}

fn open(path: &Path) -> Fixture {
  let (rt, ctx) = crate::testing::harness::new_engine();
  ctx.with(|ctx| {
    install_plugin_error(&ctx).unwrap();
    install_local_storage(&ctx, path.to_path_buf()).unwrap();
  });
  Fixture { ctx, _rt: rt }
}

impl Fixture {
  /// a string as itself, anything else as json, a throw as `throws <code>` for a plugin error and
  /// `throws <name>` for anything else
  fn eval(&self, code: &str) -> String {
    self.ctx.with(|ctx| match ctx.eval::<Value, _>(code) {
      Ok(value) => match value.as_string() {
        Some(text) => text.to_string().unwrap(),
        None => ctx.json_stringify(value).unwrap().map_or("undefined".to_string(), |s| s.to_string().unwrap()),
      },
      Err(rquickjs::Error::Exception) => {
        let thrown = ctx.catch();
        let object = thrown.as_object().unwrap();
        let code = object.get::<_, Value>("code").unwrap();
        match code.as_string() {
          Some(code) => format!("throws {}", code.to_string().unwrap()),
          None => format!("throws {}", object.get::<_, String>("name").unwrap()),
        }
      }
      Err(e) => panic!("{e:?}"),
    })
  }
}

const Q: usize = QUOTA_BYTES;

#[test]
fn every_member_sees_the_same_store() {
  let file = TempPath::default();
  let ls = open(&file.0);
  assert_eq!(ls.eval("localStorage.getItem('a')"), "null");
  ls.eval("localStorage.setItem('a', '1'); localStorage.setItem('b', '2')");
  assert_eq!(ls.eval("localStorage.getItem('a')"), "1");
  assert_eq!(
    ls.eval("[localStorage.length, localStorage.key(0), localStorage.key(1), localStorage.key(2)]"),
    r#"[2,"a","b",null]"#
  );
  ls.eval("localStorage.removeItem('a')");
  assert_eq!(ls.eval("localStorage.getItem('a')"), "null");
  ls.eval("localStorage.setItems({ c: '3', d: '4' })");
  assert_eq!(ls.eval("Object.keys(localStorage)"), r#"["b","c","d"]"#);
  ls.eval("localStorage.clear()");
  assert_eq!(ls.eval("localStorage.length"), "0");
}

#[test]
fn an_item_is_a_property_to_read_write_delete_and_enumerate() {
  let file = TempPath::default();
  let ls = open(&file.0);
  ls.eval("localStorage.a = 1; localStorage['b'] = 'two'; localStorage[3] = 'three'");
  assert_eq!(
    ls.eval("[localStorage.a, localStorage.b, localStorage['3'], localStorage.missing]"),
    r#"["1","two","three",null]"#
  );
  assert_eq!(ls.eval("typeof localStorage.missing"), "undefined");
  assert_eq!(ls.eval("['a' in localStorage, 'missing' in localStorage]"), "[true,false]");
  assert_eq!(ls.eval("JSON.stringify(localStorage)"), r#"{"3":"three","a":"1","b":"two"}"#);
  assert_eq!(ls.eval("Object.entries(localStorage)"), r#"[["3","three"],["a","1"],["b","two"]]"#);
  assert_eq!(
    ls.eval("Object.getOwnPropertyDescriptor(localStorage, 'a')"),
    r#"{"value":"1","writable":true,"enumerable":true,"configurable":true}"#
  );
  assert_eq!(
    ls.eval("[delete localStorage.a, delete localStorage.missing, localStorage.getItem('a')]"),
    "[true,true,null]"
  );
}

/// WebIDL: a member of the prototype chain hides an item of its name from property reads, and
/// never the other way around; an assignment is still a write to the item
#[test]
fn an_item_named_like_a_member_hides_behind_it() {
  let file = TempPath::default();
  let ls = open(&file.0);
  ls.eval("localStorage.setItem('getItem', 'x'); localStorage.setItem('length', 'y'); localStorage.setItem('__proto__', 'z'); localStorage.setItem('plain', 'p')");
  assert_eq!(
    ls.eval("[typeof localStorage.getItem, localStorage.length, typeof localStorage.toString]"),
    r#"["function",4,"function"]"#
  );
  assert_eq!(ls.eval("Object.getPrototypeOf(localStorage) === Storage.prototype"), "true");
  assert_eq!(
    ls.eval("[localStorage.getItem('getItem'), localStorage.getItem('length'), localStorage.getItem('__proto__')]"),
    r#"["x","y","z"]"#
  );
  assert_eq!(ls.eval("Object.keys(localStorage)"), r#"["plain"]"#);
  assert_eq!(ls.eval("Object.getOwnPropertyDescriptor(localStorage, 'getItem')"), "undefined");
  ls.eval("delete localStorage.getItem");
  assert_eq!(ls.eval("localStorage.getItem('getItem')"), "x");
  ls.eval("localStorage.getItem = 'assigned'");
  assert_eq!(
    ls.eval("[typeof localStorage.getItem, localStorage.getItem('getItem')]"),
    r#"["function","assigned"]"#
  );
}

#[test]
fn keys_and_values_are_converted_to_strings() {
  let file = TempPath::default();
  let ls = open(&file.0);
  ls.eval("localStorage.setItem(1, {}); localStorage.setItem('n', null); localStorage.setItem(undefined, 2)");
  assert_eq!(ls.eval("[localStorage.getItem('1'), localStorage.getItem('n'), localStorage.getItem('undefined'), localStorage.getItem(1)]"), r#"["[object Object]","null","2","[object Object]"]"#);
  assert_eq!(ls.eval("localStorage.setItem(Symbol(), '1')"), "throws TypeError");
  assert_eq!(ls.eval("localStorage.setItem('s', Symbol())"), "throws TypeError");
  assert_eq!(ls.eval("localStorage.length"), "3");
}

#[test]
fn a_missing_argument_is_refused_but_an_undefined_one_is_not() {
  let file = TempPath::default();
  let ls = open(&file.0);
  for code in [
    "localStorage.getItem()",
    "localStorage.setItem('a')",
    "localStorage.removeItem()",
    "localStorage.key()",
    "localStorage.setItems()",
  ] {
    assert_eq!(ls.eval(code), "throws TypeError", "{code}");
  }
  assert_eq!(ls.eval("localStorage.length"), "0");
  assert_eq!(ls.eval("localStorage.setItem('a', undefined); localStorage.getItem('a')"), "undefined");
}

#[test]
fn a_key_index_converts_as_an_unsigned_long() {
  let file = TempPath::default();
  let ls = open(&file.0);
  ls.eval("localStorage.setItem('a', '1'); localStorage.setItem('b', '2')");
  assert_eq!(
    ls.eval("[localStorage.key(0.9), localStorage.key(NaN), localStorage.key('1'), localStorage.key(2 ** 32 + 1), localStorage.key(-1), localStorage.key(Infinity)]"),
    r#"["a","a","b","b",null,"a"]"#,
  );
}

#[test]
fn walking_key_follows_every_write_between_steps() {
  let file = TempPath::default();
  let ls = open(&file.0);
  ls.eval("for (const k of ['a', 'b', 'c', 'd']) localStorage.setItem(k, k)");
  let walked = ls.eval(
    "const out = []; for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); out.push(k); \
     if (k === 'b') { localStorage.removeItem('a'); localStorage.setItem('bb', '') } } out",
  );
  assert_eq!(walked, r#"["a","b","c","d"]"#);
  assert_eq!(
    ls.eval("[localStorage.key(3), localStorage.key(3), localStorage.key(0), localStorage.key(1)]"),
    r#"["d","d","b","bb"]"#
  );
}

#[test]
fn storage_is_a_class_no_plugin_can_construct_or_forge() {
  let file = TempPath::default();
  let ls = open(&file.0);
  assert_eq!(
    ls.eval("[localStorage instanceof Storage, Object.prototype.toString.call(localStorage), Storage.name]"),
    r#"[true,"[object Storage]","Storage"]"#
  );
  assert_eq!(ls.eval("new Storage()"), "throws TypeError");
  assert_eq!(ls.eval("Storage.prototype.getItem.call({}, 'a')"), "throws TypeError");
  assert_eq!(ls.eval("Storage.prototype.length"), "throws TypeError");
}

/// WebIDL: a data descriptor is a write through the named setter; anything an item cannot be is refused
#[test]
fn a_defined_property_is_an_item_when_an_item_can_be_what_it_describes() {
  let file = TempPath::default();
  let ls = open(&file.0);
  ls.eval("localStorage.setItem('a', '1')");
  assert_eq!(ls.eval("Object.defineProperty(localStorage, 'a', { value: 2 }); localStorage.getItem('a')"), "2");
  assert_eq!(
    ls.eval("Object.defineProperty(localStorage, 'getItem', { value: 'x' }); localStorage.getItem('getItem')"),
    "x"
  );
  for code in [
    "Object.defineProperty(localStorage, 'b', { get() { return 'b' } })",
    "Object.defineProperty(localStorage, 'b', { value: 'b', configurable: false })",
    "Object.preventExtensions(localStorage)",
    "Object.freeze(localStorage)",
  ] {
    assert_eq!(ls.eval(code), "throws TypeError", "{code}");
  }
  assert_eq!(
    ls.eval("[localStorage.getItem('b'), Object.isExtensible(localStorage), Object.keys(localStorage)]"),
    r#"[null,true,["a"]]"#
  );
}

#[test]
fn a_symbol_keyed_property_is_an_ordinary_one_and_no_item() {
  let file = TempPath::default();
  let ls = open(&file.0);
  assert_eq!(
    ls.eval("const s = Symbol('s'); localStorage[s] = 1; [localStorage[s], s in localStorage, Object.getOwnPropertySymbols(localStorage).length, localStorage.length, delete localStorage[s], localStorage[s]]"),
    "[1,true,1,0,true,null]",
  );
}

#[test]
fn an_object_inheriting_from_storage_gets_an_ordinary_set() {
  let file = TempPath::default();
  let ls = open(&file.0);
  ls.eval("localStorage.setItem('a', '1')");
  assert_eq!(
    ls.eval("const o = Object.create(localStorage); o.a = 'mine'; o.b = 2; [o.a, o.b, Object.keys(o), localStorage.a, localStorage.getItem('b')]"),
    r#"["mine",2,["a","b"],"1",null]"#,
  );
}

/// every conversion and every look up the prototype chain can be plugin code; none of it may
/// find the store borrowed
#[test]
fn plugin_code_reached_from_inside_an_access_can_reenter() {
  let file = TempPath::default();
  let ls = open(&file.0);
  ls.eval(
    "localStorage.setItem('k', { toString() { localStorage.setItem('from-value', '1'); return 'v' } }); \
     Object.setPrototypeOf(Storage.prototype, new Proxy(Object.prototype, { \
       has(target, key) { if (key === 'probe') localStorage.setItem('from-has', '1'); return Reflect.has(target, key) } \
     })); undefined",
  );
  assert_eq!(
    ls.eval("[localStorage.probe, 'probe' in localStorage, Object.keys(localStorage)]"),
    r#"[null,false,["from-has","from-value","k"]]"#
  );
  ls.eval("localStorage.setItem('probe', 'p')");
  assert_eq!(ls.eval("[localStorage.probe, Object.keys(localStorage).length]"), r#"["p",4]"#);
}

#[test]
fn a_store_outlives_its_engine() {
  let file = TempPath::default();
  {
    let ls = open(&file.0);
    ls.eval("localStorage.a = '1'; localStorage.setItems({ b: '2', c: '3' }); delete localStorage.c; localStorage.setItem('a', 'again')");
  }
  assert_eq!(open(&file.0).eval("JSON.stringify(localStorage)"), r#"{"a":"again","b":"2"}"#);
}

/// the store is written when the turn's microtasks run, not only when the engine goes away
#[test]
fn a_turn_s_writes_reach_the_file_once_its_jobs_run() {
  let file = TempPath::default();
  let ls = open(&file.0);
  ls.eval("for (let i = 0; i < 100; i++) localStorage.setItem('k', String(i)); localStorage.setItem('q', 'a\"b\\\\c\\n\\u0001é')");
  while ls._rt.execute_pending_job().unwrap() {}
  let written: serde_json::Value = serde_json::from_slice(&fs::read(&file.0).unwrap()).unwrap();
  assert_eq!(written, serde_json::json!({ "k": "99", "q": "a\"b\\c\n\u{1}é" }));
  ls.eval("localStorage.clear()");
  while ls._rt.execute_pending_job().unwrap() {}
  assert!(!file.0.exists());
}

#[test]
fn a_read_alone_creates_no_file() {
  let file = TempPath::default();
  let ls = open(&file.0);
  ls.eval("localStorage.getItem('a'); localStorage.b; Object.keys(localStorage); localStorage.removeItem('a'); delete localStorage.c");
  assert!(!file.0.exists());
}

#[test]
fn a_write_past_the_quota_is_refused_as_a_dom_exception_and_lands_nowhere() {
  let file = TempPath::default();
  let ls = open(&file.0);
  let outcome = ls.eval(&format!(
    "try {{ localStorage.setItem('big', 'x'.repeat({})); 'no-throw' }} catch (e) {{ JSON.stringify([e instanceof DOMException, e.name, e.code]) }}",
    Q + 1
  ));
  assert_eq!(outcome, r#"[true,"QuotaExceededError",22]"#);
  assert_eq!(ls.eval(&format!("localStorage.big = 'x'.repeat({})", Q + 1)), "throws QuotaExceededError");
  assert_eq!(ls.eval("localStorage.getItem('big')"), "null");
  drop(ls);
  assert_eq!(open(&file.0).eval("localStorage.length"), "0");
}

#[test]
fn a_write_that_exactly_fills_the_quota_is_allowed() {
  let file = TempPath::default();
  let ls = open(&file.0);
  assert_eq!(ls.eval(&format!("localStorage.setItem('k', 'x'.repeat({}))", Q - 1)), "undefined");
  assert_eq!(ls.eval("localStorage.setItem('l', '')"), "throws QuotaExceededError");
}

#[test]
fn the_key_s_own_bytes_count_against_the_quota() {
  let file = TempPath::default();
  let ls = open(&file.0);
  assert_eq!(
    ls.eval(&format!("localStorage.setItem('k'.repeat(16), 'x'.repeat({}))", Q - 15)),
    "throws QuotaExceededError"
  );
}

/// a store at the cap could never be shrunk if a rewrite counted the old value and the new one at once
#[test]
fn replacing_a_value_charges_the_difference() {
  let file = TempPath::default();
  let ls = open(&file.0);
  let big = format!("'x'.repeat({})", Q - 1);
  assert_eq!(ls.eval(&format!("localStorage.setItem('k', {big})")), "undefined");
  assert_eq!(ls.eval(&format!("localStorage.setItem('k', {big})")), "undefined");
  assert_eq!(ls.eval("localStorage.setItem('k', 'small')"), "undefined");
  assert_eq!(ls.eval(&format!("localStorage.setItem('j', 'x'.repeat({}))", Q - 7)), "undefined");
}

#[test]
fn a_store_cleared_under_the_cap_accepts_what_it_refused() {
  let file = TempPath::default();
  let ls = open(&file.0);
  ls.eval(&format!("localStorage.setItem('a', 'x'.repeat({}))", Q - 1));
  assert_eq!(ls.eval("localStorage.setItem('b', 'y')"), "throws QuotaExceededError");
  ls.eval("localStorage.clear()");
  assert_eq!(ls.eval("localStorage.setItem('b', 'y')"), "undefined");
}

#[test]
fn set_items_is_measured_as_one_write_and_lands_whole_or_not_at_all() {
  let file = TempPath::default();
  let ls = open(&file.0);
  let half = format!("'x'.repeat({})", Q / 2);
  assert_eq!(
    ls.eval(&format!("localStorage.setItems({{ a: {half}, b: {half}, c: {half} }})")),
    "throws QuotaExceededError"
  );
  assert_eq!(ls.eval("localStorage.length"), "0");
  for code in [
    "localStorage.setItems(['1'])",
    "localStorage.setItems('ab')",
    "localStorage.setItems(null)",
    "localStorage.setItems(() => {})",
  ] {
    assert_eq!(ls.eval(code), "throws TypeError", "{code}");
  }
  assert_eq!(ls.eval("localStorage.setItems({ a: '1', n: 5, o: null })"), "undefined");
  assert_eq!(ls.eval("JSON.stringify(localStorage)"), r#"{"a":"1","n":"5","o":"null"}"#);
}

/// a getter is plugin code, and a store borrowed across it would panic the engine on reentry
#[test]
fn set_items_reads_its_values_before_it_touches_the_store() {
  let file = TempPath::default();
  let ls = open(&file.0);
  ls.eval(
    "localStorage.setItems({ get a() { localStorage.setItem('b', localStorage.getItem('b') ?? '2'); return '1' } })",
  );
  assert_eq!(ls.eval("JSON.stringify(localStorage)"), r#"{"a":"1","b":"2"}"#);
}

#[test]
fn a_file_that_is_not_a_store_is_moved_aside_and_the_store_starts_empty() {
  let file = TempPath::default();
  fs::write(&file.0, b"{\"a\": 1").unwrap();
  let ls = open(&file.0);
  assert_eq!(ls.eval("Object.keys(localStorage)"), "[]");
  ls.eval("localStorage.setItem('a', '1')");
  drop(ls);
  assert_eq!(open(&file.0).eval("localStorage.getItem('a')"), "1");
  assert_eq!(fs::read(file.0.with_added_extension("corrupt")).unwrap(), b"{\"a\": 1");
}

#[test]
fn a_store_that_cannot_be_read_throws_internal() {
  let file = TempPath::default();
  fs::create_dir(&file.0).unwrap();
  assert_eq!(open(&file.0).eval("localStorage.getItem('a')"), "throws internal");
}

#[test]
fn an_engine_with_no_store_path_throws_internal() {
  assert_eq!(open(Path::new("")).eval("localStorage.getItem('a')"), "throws internal");
}

const API_ORACLE: &str = crate::testing::test_plugin!("api-test.js");

/// its last two halves are the ones a device only reaches through a person: the dialog settles
/// when the user picks a button, and the unload callbacks run when the plugin is stopped. Both
/// are driven here, so the count covers the whole file.
#[test]
fn the_bundled_api_test_plugin_passes() {
  let (rt, ctx, host, lifecycle, dialogs, _logs) =
    crate::testing::harness::setup_apis(&crate::testing::harness::manifest_grants(API_ORACLE));
  // `inu.ui` is one object two modules install into, and the oracle asserts on what the
  // *dialog* does with an element the other one builds
  let ui_host: Rc<dyn crate::api::ui::pages::UiHost> = Rc::new(crate::api::ui::icons::tests::SilentUiHost);
  let ui = ctx.with(|ctx| {
    crate::api::ui::pages::install_ui(
      &ctx,
      ui_host,
      crate::sandbox::registry::Lifecycle::new(),
      crate::testing::harness::log_sink(&crate::testing::harness::Logs::new()),
      None,
      &get_api_globals(&ctx),
    )
    .unwrap()
  });
  let _ui = crate::testing::harness::DisposeOnDrop::new(&ctx, ui, |ctx, state| {
    use crate::runtime::Dispose;
    state.dispose(ctx)
  });
  let lines = crate::testing::harness::install_capturing_console(&ctx);
  crate::testing::harness::eval_unit(&ctx, API_ORACLE);

  let request_id = host.dialogs.borrow().last().expect("a dialog was opened").0;
  dialogs.settle(&rt, &ctx, request_id, "Spositive");
  lifecycle.notify_unload(&rt, &ctx);

  let lines = lines.borrow().clone();
  crate::testing::harness::assert_oracle_exact(&lines, "api test done", 13);
  // what "did not throw" cannot say: the refused dialog never reached the host, and the
  // accepted one did
  assert_eq!(host.dialogs.borrow().len(), 1);
  assert_eq!(*host.toasts.borrow(), vec!["api-test loaded (run #1)".to_string(), "dialog: positive".to_string()],);
}

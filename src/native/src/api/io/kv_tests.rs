use super::*;
use crate::api::error::install_plugin_error;
use crate::sandbox::grants::TestGrantHost;
use crate::testing::harness::{get_api_globals, TempPath};
use rquickjs::{Context, Runtime};

/// the context drops before its runtime, and the store with it
struct Fixture {
  ctx: Context,
  _rt: Runtime,
}

fn open(path: &Path, grants: &[&str]) -> Fixture {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  ctx.with(|ctx| {
    install_plugin_error(&ctx).unwrap();
    install_kv(&ctx, path.to_path_buf(), TestGrantHost::new(grants).as_host(), &get_api_globals(&ctx)).unwrap();
  });
  Fixture { ctx, _rt: rt }
}

impl Fixture {
  /// a string as itself, anything else as json, a throw as `throws <code>`
  fn eval(&self, code: &str) -> String {
    self.ctx.with(|ctx| match ctx.eval::<Value, _>(code) {
      Ok(value) => match value.as_string() {
        Some(text) => text.to_string().unwrap(),
        None => ctx.json_stringify(value).unwrap().map_or("undefined".to_string(), |s| s.to_string().unwrap()),
      },
      Err(rquickjs::Error::Exception) => {
        let thrown = ctx.catch();
        let object = thrown.as_object().unwrap();
        format!("throws {}", object.get::<_, String>("code").unwrap())
      }
      Err(e) => panic!("{e:?}"),
    })
  }
}

const Q: usize = QUOTA_BYTES;

#[test]
fn every_operation_sees_the_same_store() {
  let file = TempPath::default();
  let kv = open(&file.0, &["kv"]);
  assert_eq!(kv.eval("inu.kv.get('a')"), "null");
  kv.eval("inu.kv.set('a', '1'); inu.kv.set('b', '2')");
  assert_eq!(kv.eval("inu.kv.get('a')"), "1");
  assert_eq!(kv.eval("inu.kv.keys()"), r#"["a","b"]"#);
  assert_eq!(kv.eval("inu.kv.getAll()"), r#"{"a":"1","b":"2"}"#);
  kv.eval("inu.kv.del('a')");
  assert_eq!(kv.eval("inu.kv.get('a')"), "null");
  kv.eval("inu.kv.insertAll({ c: '3', d: '4' })");
  assert_eq!(kv.eval("inu.kv.keys()"), r#"["b","c","d"]"#);
  kv.eval("inu.kv.clear()");
  assert_eq!(kv.eval("inu.kv.keys()"), "[]");
}

#[test]
fn has_and_usage_count_keys_and_values_in_bytes() {
  let file = TempPath::default();
  let kv = open(&file.0, &["kv"]);
  assert_eq!(kv.eval("[inu.kv.has('a'), inu.kv.usage()]"), "[false,0]");
  kv.eval("inu.kv.set('a', 'xyz')");
  assert_eq!(kv.eval("[inu.kv.has('a'), inu.kv.usage()]"), "[true,4]");
  kv.eval("inu.kv.set('ключ', 'é')");
  assert_eq!(kv.eval("inu.kv.usage()"), "14");
}

#[test]
fn every_member_needs_the_grant_and_touches_nothing_without_it() {
  let file = TempPath::default();
  let kv = open(&file.0, &[]);
  for code in [
    "inu.kv.get('a')",
    "inu.kv.has('a')",
    "inu.kv.set('a', '1')",
    "inu.kv.del('a')",
    "inu.kv.keys()",
    "inu.kv.clear()",
    "inu.kv.getAll()",
    "inu.kv.insertAll({ a: '1' })",
    "inu.kv.usage()",
  ] {
    assert_eq!(kv.eval(code), "throws not-granted", "{code}");
  }
  assert!(!file.0.exists());
}

#[test]
fn a_store_outlives_its_engine() {
  let file = TempPath::default();
  {
    let kv = open(&file.0, &["kv"]);
    kv.eval("inu.kv.set('a', '1'); inu.kv.insertAll({ b: '2', c: '3' }); inu.kv.del('c'); inu.kv.set('a', 'again')");
  }
  let kv = open(&file.0, &["kv"]);
  assert_eq!(kv.eval("inu.kv.getAll()"), r#"{"a":"again","b":"2"}"#);
  assert_eq!(kv.eval("inu.kv.usage()"), "8");
}

#[test]
fn a_read_alone_creates_no_file() {
  let file = TempPath::default();
  let kv = open(&file.0, &["kv"]);
  kv.eval("inu.kv.get('a'); inu.kv.keys(); inu.kv.del('a')");
  assert!(!file.0.exists());
}

#[test]
fn a_write_past_the_quota_is_refused_with_its_usage_and_lands_nowhere() {
  let file = TempPath::default();
  let kv = open(&file.0, &["kv"]);
  let outcome = kv.eval(&format!(
    "try {{ inu.kv.set('big', 'x'.repeat({})); 'no-throw' }} catch (e) {{ JSON.stringify([e.code, e.usage, e.quota]) }}",
    Q + 1
  ));
  assert_eq!(outcome, format!(r#"["quota-exceeded",{},{Q}]"#, Q + 4));
  assert_eq!(kv.eval("inu.kv.get('big')"), "null");
  drop(kv);
  assert_eq!(open(&file.0, &["kv"]).eval("inu.kv.keys()"), "[]");
}

#[test]
fn a_write_that_exactly_fills_the_quota_is_allowed() {
  let file = TempPath::default();
  let kv = open(&file.0, &["kv"]);
  assert_eq!(kv.eval(&format!("inu.kv.set('k', 'x'.repeat({}))", Q - 1)), "undefined");
  assert_eq!(kv.eval("inu.kv.usage()"), Q.to_string());
}

#[test]
fn the_key_s_own_bytes_count_against_the_quota() {
  let file = TempPath::default();
  let kv = open(&file.0, &["kv"]);
  assert_eq!(kv.eval(&format!("inu.kv.set('k'.repeat(16), 'x'.repeat({}))", Q - 15)), "throws quota-exceeded");
}

/// a store at the cap could never be shrunk if a rewrite counted the old value and the new one at once
#[test]
fn replacing_a_value_charges_the_difference() {
  let file = TempPath::default();
  let kv = open(&file.0, &["kv"]);
  let big = format!("'x'.repeat({})", Q - 1);
  assert_eq!(kv.eval(&format!("inu.kv.set('k', {big})")), "undefined");
  assert_eq!(kv.eval(&format!("inu.kv.set('k', {big})")), "undefined");
  assert_eq!(kv.eval("inu.kv.set('k', 'small')"), "undefined");
  assert_eq!(kv.eval("inu.kv.usage()"), "6");
}

#[test]
fn a_store_cleared_under_the_cap_accepts_what_it_refused() {
  let file = TempPath::default();
  let kv = open(&file.0, &["kv"]);
  kv.eval(&format!("inu.kv.set('a', 'x'.repeat({}))", Q - 1));
  assert_eq!(kv.eval("inu.kv.set('b', 'y')"), "throws quota-exceeded");
  kv.eval("inu.kv.clear()");
  assert!(!file.0.exists());
  assert_eq!(kv.eval("inu.kv.set('b', 'y')"), "undefined");
}

#[test]
fn insert_all_is_measured_as_one_write_and_lands_whole_or_not_at_all() {
  let file = TempPath::default();
  let kv = open(&file.0, &["kv"]);
  let half = format!("'x'.repeat({})", Q / 2);
  assert_eq!(
    kv.eval(&format!("inu.kv.insertAll({{ a: {half}, b: {half}, c: {half} }})")),
    "throws quota-exceeded"
  );
  assert_eq!(kv.eval("inu.kv.keys()"), "[]");
  for code in [
    "inu.kv.insertAll({ a: '1', n: 5 })",
    "inu.kv.insertAll(['1'])",
    "inu.kv.insertAll('ab')",
    "inu.kv.insertAll(null)",
  ] {
    assert_eq!(kv.eval(code), "throws invalid-argument", "{code}");
  }
  assert_eq!(kv.eval("inu.kv.keys()"), "[]");
}

/// a getter is plugin code, and a store borrowed across it would panic the engine on reentry
#[test]
fn insert_all_reads_its_values_before_it_touches_the_store() {
  let file = TempPath::default();
  let kv = open(&file.0, &["kv"]);
  kv.eval("inu.kv.insertAll({ get a() { inu.kv.set('b', inu.kv.get('b') ?? '2'); return '1' } })");
  assert_eq!(kv.eval("inu.kv.getAll()"), r#"{"a":"1","b":"2"}"#);
}

/// process death mid-append leaves a partial frame: replay keeps everything before it, and the
/// writes after it must not land behind bytes the next replay stops at
#[test]
fn a_torn_frame_loses_itself_and_nothing_written_after() {
  let file = TempPath::default();
  open(&file.0, &["kv"]).eval("inu.kv.set('a', '1'); inu.kv.set('b', '2')");
  let mut log = OpenOptions::new().append(true).open(&file.0).unwrap();
  log.write_all(&[9, 0, 0, 0, TAG_SET, 1]).unwrap();
  drop(log);

  let kv = open(&file.0, &["kv"]);
  assert_eq!(kv.eval("inu.kv.getAll()"), r#"{"a":"1","b":"2"}"#);
  kv.eval("inu.kv.set('c', '3')");
  drop(kv);
  assert_eq!(open(&file.0, &["kv"]).eval("inu.kv.getAll()"), r#"{"a":"1","b":"2","c":"3"}"#);
}

#[test]
fn a_file_that_is_not_a_store_reads_as_empty_and_is_replaced_on_open() {
  let file = TempPath::default();
  fs::write(&file.0, b"not a store").unwrap();
  let kv = open(&file.0, &["kv"]);
  assert_eq!(kv.eval("inu.kv.keys()"), "[]");
  kv.eval("inu.kv.set('a', '1')");
  drop(kv);
  assert_eq!(open(&file.0, &["kv"]).eval("inu.kv.get('a')"), "1");
}

#[test]
fn rewriting_one_key_keeps_the_file_near_what_it_holds() {
  let file = TempPath::default();
  let kv = open(&file.0, &["kv"]);
  kv.eval("for (let i = 0; i < 300; i++) inu.kv.set('k', String(i).padStart(4096, 'x'))");
  let size = fs::metadata(&file.0).unwrap().len();
  assert!(size < COMPACT_FLOOR + 2 * 4200, "{size}");
  drop(kv);
  assert_eq!(open(&file.0, &["kv"]).eval("inu.kv.get('k').slice(-3)"), "299");
}

#[test]
fn a_store_that_cannot_be_read_throws_internal() {
  let file = TempPath::default();
  fs::create_dir(&file.0).unwrap();
  assert_eq!(open(&file.0, &["kv"]).eval("inu.kv.get('a')"), "throws internal");
}

#[test]
fn an_engine_with_no_store_path_throws_internal() {
  assert_eq!(open(Path::new(""), &["kv"]).eval("inu.kv.get('a')"), "throws internal");
}

const API_ORACLE: &str = include_str!("../../../../test/plugins/api-test.js");

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
  ctx.with(|ctx| match ctx.eval::<(), _>(API_ORACLE) {
    Ok(()) => {}
    Err(rquickjs::Error::Exception) => panic!("{}", crate::api::error::format_exception(&ctx)),
    Err(e) => panic!("{e:?}"),
  });

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

#[test]
fn a_write_after_the_log_was_lost_keeps_everything_already_stored() {
  let file = TempPath::default();
  let mut store = Store::open(&file.0).unwrap();
  assert!(store.set_all(&[("a".to_string(), "1".to_string())]).is_ok());
  store.log = None;
  assert!(store.set_all(&[("b".to_string(), "2".to_string())]).is_ok());
  drop(store);
  assert_eq!(open(&file.0, &["kv"]).eval("inu.kv.getAll()"), r#"{"a":"1","b":"2"}"#);
}

#[test]
fn a_delete_after_the_log_was_lost_keeps_the_other_entries() {
  let file = TempPath::default();
  let mut store = Store::open(&file.0).unwrap();
  assert!(store.set_all(&[("a".to_string(), "1".to_string()), ("b".to_string(), "2".to_string())]).is_ok());
  store.log = None;
  store.delete("a").unwrap();
  drop(store);
  assert_eq!(open(&file.0, &["kv"]).eval("inu.kv.getAll()"), r#"{"b":"2"}"#);
}

#[test]
fn get_all_keeps_a_key_named_like_the_prototype() {
  let file = TempPath::default();
  let kv = open(&file.0, &["kv"]);
  kv.eval("inu.kv.set('__proto__', 'x')");
  assert_eq!(kv.eval("Object.keys(inu.kv.getAll())"), r#"["__proto__"]"#);
  assert_eq!(kv.eval("inu.kv.getAll()['__proto__']"), "x");
}

#[test]
fn keys_defines_its_elements_past_an_array_prototype_setter() {
  let file = TempPath::default();
  let kv = open(&file.0, &["kv"]);
  kv.eval("inu.kv.set('a', '1')");
  let listed = kv.eval(
    "Object.defineProperty(Array.prototype, '0', { set(v) { inu.kv.set('seen', v) }, configurable: true }); \
     const keys = inu.kv.keys(); delete Array.prototype[0]; JSON.stringify([keys.length, inu.kv.get('seen')])",
  );
  assert_eq!(listed, r#"[1,null]"#);
}

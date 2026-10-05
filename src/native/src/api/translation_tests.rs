use super::*;
use std::cell::RefCell;

use rquickjs::{Context, Runtime};

use crate::testing::harness::{catch_json, eval_json, eval_unit as eval, Logs};

#[derive(Default)]
struct TestHost {
  registered: RefCell<Vec<(u32, String, String)>>,
  unregistered: RefCell<Vec<u32>>,
  results: RefCell<Vec<(i64, String)>>,
  refuse_register: RefCell<Option<String>>,
}

impl TranslationHost for TestHost {
  fn translation_register(&self, token: u32, id: &str, name: &str) -> Option<String> {
    if let Some(err) = self.refuse_register.borrow().clone() {
      return Some(err);
    }
    self.registered.borrow_mut().push((token, id.to_string(), name.to_string()));
    None
  }
  fn translation_unregister(&self, token: u32) {
    self.unregistered.borrow_mut().push(token);
  }
  fn translation_result(&self, dispatch_id: i64, wire: &str) {
    self.results.borrow_mut().push((dispatch_id, wire.to_string()));
  }
}

type Disposing = crate::testing::harness::DisposeOnDrop<TranslationState>;

fn setup() -> (Runtime, Context, Rc<TestHost>, Disposing, std::sync::Arc<Logs>) {
  setup_with(Lifecycle::new())
}

fn setup_with(lifecycle: Rc<Lifecycle>) -> (Runtime, Context, Rc<TestHost>, Disposing, std::sync::Arc<Logs>) {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let host = Rc::new(TestHost::default());
  let logs = Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    crate::testing::harness::install_sandbox_globals(&ctx, std::path::Path::new("")).unwrap();
    install_translation(&ctx, host.clone(), lifecycle, log, &inu).unwrap()
  });
  let state = Disposing::new(&ctx, state, |ctx, state| state.dispose(ctx));
  (rt, ctx, host, state, logs)
}

fn register(ctx: &Context, format: &str, translate: &str) {
  eval(
    ctx,
    &format!("globalThis.__dispose = inu.registerTranslationProvider({{ id: 'p', name: 'Provider', format: {format}, translate: {translate} }})"),
  );
}

fn token(host: &TestHost) -> u32 {
  host.registered.borrow().last().unwrap().0
}

const REQUEST: &str = r#"{"texts":[{"text":"hello\nworld","entities":[{"_":"messageEntityBold","offset":0,"length":5}]},{"text":"two","entities":[]}],"from":["en",null],"to":"de"}"#;

#[test]
fn the_host_learns_of_each_provider_and_of_its_disposal() {
  let (_rt, ctx, host, _state, _logs) = setup();
  register(&ctx, "undefined", "() => []");
  assert_eq!(*host.registered.borrow(), vec![(1, "p".to_string(), "Provider".to_string())]);
  register(&ctx, "undefined", "() => []");
  assert_eq!(*host.unregistered.borrow(), vec![1], "a second provider with the same id replaces the first");
  eval(&ctx, "__dispose(); __dispose()");
  assert_eq!(*host.unregistered.borrow(), vec![1, 2]);
}

#[test]
fn a_malformed_provider_is_refused() {
  let (_rt, ctx, host, _state, _logs) = setup();
  for options in [
    "{ id: '', name: 'n', translate() {} }",
    "{ id: 'p', name: ' ', translate() {} }",
    "{ id: 'p', name: 'n', format: 'markdown', translate() {} }",
    "{ id: 'p', name: 'n' }",
  ] {
    let thrown = catch_json(&ctx, &format!("inu.registerTranslationProvider({options})"));
    assert!(thrown.contains("TypeError"), "{options}: {thrown}");
  }
  *host.refuse_register.borrow_mut() = Some("Pquota-exceeded\n\n\n\ntoo many".into());
  assert_eq!(
    catch_json(&ctx, "inu.registerTranslationProvider({ id: 'p', name: 'n', translate() {} })"),
    r#"[true,"quota-exceeded",null,"too many"]"#,
  );
  assert!(host.registered.borrow().is_empty());
}

#[test]
fn an_unloading_plugin_registers_nothing() {
  let lifecycle = Lifecycle::new();
  lifecycle.begin_unload();
  let (_rt, ctx, host, _state, _logs) = setup_with(lifecycle);
  register(&ctx, "undefined", "() => []");
  assert!(host.registered.borrow().is_empty());
}

#[test]
fn each_format_hands_the_provider_its_kind_of_text_and_reads_the_same_kind_back() {
  for (format, seen, translate, answer) in [
    (
      "'plain'",
      r#"["hello\nworld","two"]"#,
      "({ texts }) => texts.map(t => t.toUpperCase())",
      r#"S[{"text":"HELLO\nWORLD","entities":[]},{"text":"TWO","entities":[]}]"#,
    ),
    (
      "'html'",
      r#"["<b>hello</b>\nworld","two"]"#,
      "({ texts }) => ['<i>hallo</i>\\n  welt', 'zwei']",
      r#"S[{"text":"hallo\n  welt","entities":[{"_":"messageEntityItalic","offset":0,"length":5}]},{"text":"zwei","entities":[]}]"#,
    ),
    (
      "'entities'",
      r#"[{"text":"hello\nworld","entities":[{"_":"messageEntityBold","offset":0,"length":5}]},{"text":"two","entities":[]}]"#,
      "async ({ texts }) => [{ text: 'hallo', entities: [{ _: 'messageEntityCode', offset: 1, length: 4 }] }, 'zwei']",
      r#"S[{"text":"hallo","entities":[{"_":"messageEntityCode","offset":1,"length":4}]},{"text":"zwei","entities":[]}]"#,
    ),
  ] {
    let (_rt, ctx, host, state, logs) = setup();
    register(
      &ctx,
      format,
      &format!("(request) => {{ globalThis.__seen = request; return ({translate})(request) }}"),
    );
    state.dispatch(&ctx, token(&host), 7, REQUEST);
    assert_eq!(eval_json(&ctx, "__seen.texts"), seen, "{format}");
    assert_eq!(
      eval_json(&ctx, "[__seen.from, __seen.to, __seen.tone ?? null]"),
      r#"[["en",null],"de",null]"#,
      "{format}"
    );
    assert_eq!(*host.results.borrow(), vec![(7, answer.to_string())], "{format}");
    assert!(logs.borrow().is_empty(), "{format}: {:?}", logs.borrow());
  }
}

#[test]
fn a_tone_reaches_the_provider() {
  let (_rt, ctx, host, state, _logs) = setup();
  register(&ctx, "undefined", "({ texts, tone }) => texts.map(() => tone)");
  state.dispatch(&ctx, token(&host), 1, r#"{"texts":[{"text":"hi"}],"from":[null],"to":"en","tone":"formal"}"#);
  assert_eq!(*host.results.borrow(), vec![(1, r#"S[{"text":"formal","entities":[]}]"#.to_string())]);
}

#[test]
fn an_unusable_answer_fails_the_translation() {
  for (translate, reason) in [
    ("() => ['only one']", "translate returned 1 translations for 2 texts"),
    ("() => 'nope'", "translate must return an array, one translation per text"),
    ("() => [1, 2]", "translate: [0] must be a string or { text, entities }"),
    (
      "() => [{ text: 'hi', entities: [{ _: 'messageEntityBold', offset: 1, length: 5 }] }, 'x']",
      "translate: [0] has a messageEntityBold entity at 1+5 outside its 2 UTF-16 units",
    ),
    ("() => { throw new Error('rate limited') }", "rate limited"),
    ("async () => { throw new Error('offline') }", "offline"),
  ] {
    let (_rt, ctx, host, state, logs) = setup();
    register(&ctx, "'entities'", translate);
    state.dispatch(&ctx, token(&host), 3, REQUEST);
    let results = host.results.borrow();
    assert_eq!(results.len(), 1, "{translate}");
    assert_eq!(results[0].0, 3);
    assert!(results[0].1.starts_with('E') && results[0].1.contains(reason), "{translate}: {}", results[0].1);
    assert_eq!(logs.borrow().len(), 1, "{translate}: {:?}", logs.borrow());
  }
}

#[test]
fn a_disposed_provider_fails_what_was_sent_to_it() {
  let (_rt, ctx, host, state, _logs) = setup();
  register(&ctx, "undefined", "({ texts }) => texts");
  let token = token(&host);
  eval(&ctx, "__dispose()");
  state.dispatch(&ctx, token, 4, REQUEST);
  assert_eq!(*host.results.borrow(), vec![(4, "Ethe translation provider was disposed".to_string())]);
}

#[test]
fn an_abandoned_translation_aborts_its_signal_and_never_answers() {
  for (timed_out, code) in [(true, "timed-out"), (false, "aborted")] {
    let (_rt, ctx, host, state, logs) = setup();
    register(
      &ctx,
      "undefined",
      "({ signal }) => { globalThis.__signal = signal; return new Promise((resolve, reject) => { globalThis.__resolve = resolve; globalThis.__reject = reject }) }",
    );
    state.dispatch(&ctx, token(&host), 9, REQUEST);
    assert_eq!(eval_json(&ctx, "__signal.aborted"), "false");
    state.abandon(&ctx, 9, timed_out);
    assert_eq!(
      eval_json(&ctx, "[__signal.aborted, __signal.reason instanceof inu.PluginError, __signal.reason.code]"),
      format!(r#"[true,true,"{code}"]"#)
    );
    eval(&ctx, "__resolve(['a', 'b']); __reject(new Error('late'))");
    crate::runtime::pump_jobs(&ctx, &|_| {});
    assert!(host.results.borrow().is_empty(), "{code}");
    assert!(logs.borrow().is_empty(), "{code}: {:?}", logs.borrow());
  }
}

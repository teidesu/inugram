use super::*;
use std::cell::RefCell;

use rquickjs::{Context, Runtime};

use crate::testing::harness::{catch_json, eval_json, eval_string, eval_unit as eval, Logs};

#[derive(Default)]
struct TestHost {
  changes: RefCell<Vec<i32>>,
  settled: RefCell<Vec<i64>>,
}

impl TextEntityHost for TestHost {
  fn text_entities_changed(&self, needs: i32) {
    self.changes.borrow_mut().push(needs);
  }

  fn text_entity_settled(&self, tap_id: i64) {
    self.settled.borrow_mut().push(tap_id);
  }
}

type Disposing = crate::testing::harness::DisposeOnDrop<TextEntityState>;

fn setup() -> (Runtime, Context, Rc<TestHost>, Disposing, std::sync::Arc<Logs>) {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let host = Rc::new(TestHost::default());
  let logs = Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    crate::testing::harness::install_sandbox_globals(&ctx, std::path::Path::new("")).unwrap();
    let shared = crate::api::tl::utils::install_utils(&ctx, &inu).unwrap();
    crate::api::tl::message::install_message(&ctx, &shared, &inu).unwrap();
    install_text_entities(&ctx, host.clone(), Lifecycle::new(), None, None, log, &inu).unwrap()
  });
  let state = Disposing::new(&ctx, state, |ctx, state| state.dispose(ctx));
  (rt, ctx, host, state, logs)
}

fn probe(peer: i64) -> Probe<'static> {
  Probe {
    text: "",
    entities_json: "",
    buttons: "",
    sender: 0,
    peer,
    peer_type: 2,
    media: "",
    forward: false,
  }
}

fn matches(state: &TextEntityState, text: &str, peer: i64) -> Json {
  let found = state.entities.matches(text, &probe(peer), &mut None);
  if found.is_empty() {
    Json::Null
  } else {
    Json::Array(found)
  }
}

#[test]
fn an_issue_key_links_to_the_tracker_of_the_chat_it_is_written_in() {
  let (_rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"inu.registerTextEntity({
      pattern: /#(\d+)|(?<key>[A-Z]+-\d+)/,
      filter: { peer: -100 },
      url: 'https://tracker.example/$<key>$1?q=$&&raw=$$1$9',
    })"#,
  );
  assert_eq!(*host.changes.borrow(), vec![crate::api::telegram::filters::NEEDS_ANY]);

  assert_eq!(
    matches(&state, "🐕 see #12 and INU-7", -100),
    json!([
      { "token": 1, "start": 7, "end": 10, "url": "https://tracker.example/12?q=%2312&raw=$1$9" },
      { "token": 1, "start": 15, "end": 20, "url": "https://tracker.example/INU-7?q=INU-7&raw=$1$9" },
    ]),
    "offsets count utf-16 units, and each group is encoded"
  );
  assert_eq!(matches(&state, "see #12", -200), Json::Null, "another chat has another tracker");
}

#[test]
fn a_tapped_amount_reaches_the_callback_with_its_groups() {
  let (_rt, ctx, host, state, logs) = setup();
  eval(
    &ctx,
    r#"
      globalThis.__dispose = inu.registerTextEntity({
        pattern: /(?<amount>\d+)\s?(?<currency>USD|CNY)(!)?/,
        callback: (tap) => { globalThis.__tap = { ...tap, kind: 'tap', text: tap.message.text } },
        secondaryCallback: (tap) => { globalThis.__tap = { kind: 'long tap' } },
      })
    "#,
  );
  let matched = matches(&state, "100 USD", 1);
  assert_eq!(matched[0]["match"], json!(["100 USD", "100", "USD", null]));
  assert_eq!(matched[0]["groups"], json!({ "amount": "100", "currency": "USD" }));

  let tap = json!({
    "accountId": 0,
    "dialogId": 1,
    "message": { "_": "message", "id": 1, "message": "100 USD", "date": 0 },
    "match": matched[0]["match"],
    "groups": matched[0]["groups"],
  });
  state.dispatch(&ctx, 1, false, 1, &tap.to_string());
  assert_eq!(
    eval_json(&ctx, "({ ...__tap, message: undefined, signal: undefined, match: __tap.match.map(String) })"),
    r#"{"dialogId":1,"groups":{"amount":"100","currency":"USD"},"match":["100 USD","100","USD","undefined"],"kind":"tap","text":"100 USD"}"#,
  );
  state.dispatch(&ctx, 1, true, 2, &tap.to_string());
  assert_eq!(eval_string(&ctx, "__tap.kind"), "long tap");
  assert_eq!(*host.settled.borrow(), vec![1, 2], "a callback without a promise is over once it returns");
  assert!(logs.borrow().is_empty(), "{:?}", logs.borrow());

  eval(&ctx, "__dispose()");
  assert_eq!(matches(&state, "100 USD", 1), Json::Null);
}

#[test]
fn a_slow_lookup_is_awaited_and_aborted_once_the_user_taps_elsewhere() {
  let (_rt, ctx, host, state, logs) = setup();
  eval(
    &ctx,
    r#"
      globalThis.__lookups = []
      inu.registerTextEntity({
        pattern: /\d+ USD/,
        callback: ({ signal }) => new Promise((resolve, reject) => {
          __lookups.push({ resolve, signal })
          signal.addEventListener('abort', () => reject(signal.reason))
        }),
      })
    "#,
  );
  let tap = json!({
    "accountId": 0,
    "dialogId": 1,
    "message": { "_": "message", "id": 1, "message": "100 USD", "date": 0 },
    "match": ["100 USD"],
    "groups": {},
  })
  .to_string();

  state.dispatch(&ctx, 1, false, 1, &tap);
  state.dispatch(&ctx, 1, false, 2, &tap);
  assert!(host.settled.borrow().is_empty(), "the link loads while the lookup runs");

  eval(&ctx, "__lookups[0].resolve()");
  crate::runtime::pump_jobs(&ctx, &|_| {});
  assert_eq!(*host.settled.borrow(), vec![1]);

  state.abandon(&ctx, 2, false);
  assert_eq!(eval_string(&ctx, "__lookups[1].signal.reason.code"), "aborted");
  assert_eq!(*host.settled.borrow(), vec![1], "an abandoned tap is not reported, nor its rejection logged");
  assert!(logs.borrow().is_empty(), "{:?}", logs.borrow());
}

#[test]
fn an_entity_without_exactly_one_target_is_refused() {
  let (_rt, ctx, host, _state, _logs) = setup();
  for registration in [
    "inu.registerTextEntity({ pattern: /x/ })",
    "inu.registerTextEntity({ pattern: /x/, url: 'https://x', callback() {} })",
    "inu.registerTextEntity({ pattern: /x/, url: 'https://x', secondaryCallback() {} })",
    "inu.registerTextEntity({ pattern: 'x', url: 'https://x' })",
    "inu.registerTextEntity({ pattern: /x/, uri: 'https://x' })",
  ] {
    let thrown = catch_json(&ctx, registration);
    assert!(thrown.contains("TypeError"), "{registration}: {thrown}");
  }
  assert!(host.changes.borrow().is_empty());
}

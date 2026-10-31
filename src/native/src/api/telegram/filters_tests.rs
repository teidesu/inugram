use super::*;
use std::cell::RefCell;

use rquickjs::{Context, Runtime};

use crate::sandbox::grants::CachedGrantHost;
use crate::testing::harness::{catch_json, eval_unit as eval, Logs};

#[derive(Default)]
struct TestHost {
  changes: RefCell<Vec<i32>>,
}

impl FilterHost for TestHost {
  fn message_filters_changed(&self, needs: i32) {
    self.changes.borrow_mut().push(needs);
  }
}

type Disposing = crate::testing::harness::DisposeOnDrop<FilterState>;

fn setup() -> (Runtime, Context, Rc<TestHost>, Disposing, std::sync::Arc<Logs>) {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let host = Rc::new(TestHost::default());
  let logs = Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    let shared = crate::api::tl::utils::install_utils(&ctx, &inu).unwrap();
    crate::api::tl::message::install_message(&ctx, &shared, &inu).unwrap();
    let grants = CachedGrantHost::new(&["filterMessages"]);
    install_filters(&ctx, host.clone(), grants, Lifecycle::new(), None, log, &inu).unwrap()
  });
  let state = Disposing::new(&ctx, state, |ctx, state| state.dispose(ctx));
  (rt, ctx, host, state, logs)
}

fn probe(text: &str) -> Probe<'_> {
  Probe {
    text,
    entities_json: "",
    buttons: "",
    sender: 0,
    peer: -1001234567890,
    peer_type: 4,
    media: "",
    forward: false,
  }
}

#[test]
fn an_ad_marked_only_in_a_link_is_hidden_without_asking_the_plugin() {
  let (_rt, ctx, host, state, _logs) = setup();
  eval(&ctx, "inu.filterMessages([{ html: /erid=/i, peer: 'broadcast' }, { buttons: /erid=/ }], 'hide')");
  assert_eq!(*host.changes.borrow(), vec![NEEDS_ANY | NEEDS_ENTITIES | NEEDS_BUTTONS]);

  let entities = r#"[{"_":"messageEntityTextUrl","offset":0,"length":4,"url":"https://shop.example/?ERID=2Vtzqx"}]"#;
  let ad = Probe {
    entities_json: entities,
    ..probe("shop now")
  };
  assert_eq!(state.rules.matches(&ad), vec![MATCHED_HIDE]);
  let in_a_group = Probe {
    peer_type: 2,
    ..Probe {
      entities_json: entities,
      ..probe("shop now")
    }
  };
  assert_eq!(state.rules.matches(&in_a_group), vec![MATCHED_NONE]);
  let ad_button = Probe {
    buttons: "https://t.me/x\nhttps://shop.example/?erid=1",
    ..probe("plain post")
  };
  assert_eq!(state.rules.matches(&ad_button), vec![MATCHED_HIDE]);
  assert_eq!(state.rules.matches(&probe("plain text with erid= in it is html too")), vec![MATCHED_HIDE]);
  assert_eq!(state.rules.matches(&probe("nothing to see")), vec![MATCHED_NONE]);
}

#[test]
fn a_rule_that_would_match_more_than_written_is_refused() {
  let (_rt, ctx, host, _state, _logs) = setup();
  for registration in [
    "inu.filterMessages({ txt: /ad/ }, 'hide')",
    "inu.filterMessages({ text: /(?<=#)ad/ }, 'hide')",
    "inu.filterMessages({ text: /ad/v }, 'hide')",
    "inu.filterMessages([], 'hide')",
    "inu.filterMessages({}, 'hide')",
    "inu.filterMessages({ sender: [], media: [] }, 'hide')",
    "inu.filterMessages({ text: /ad/ }, 'drop')",
  ] {
    let thrown = catch_json(&ctx, registration);
    assert!(thrown.contains("TypeError"), "{registration}: {thrown}");
  }
  assert!(host.changes.borrow().is_empty());
}

#[test]
fn a_callback_rewrites_or_hides_the_messages_its_rule_lets_through() {
  let (_rt, ctx, host, state, logs) = setup();
  eval(
    &ctx,
    r#"
      inu.filterMessages({ text: /secret/ }, (message) => message.text.includes('hide') ? 'hide' : {
        rewrite: { text: 'redacted', entities: [{ _: 'messageEntityItalic', offset: 0, length: 8 }] },
      })
      inu.filterMessages({ text: /secret/ }, () => 'spoiler')
      globalThis.__dispose = inu.filterMessages({ text: /secret/ }, async () => 'hide')
    "#,
  );
  let callbacks = state.rules.matches(&probe("a secret"));
  assert_eq!(callbacks.len(), 4, "every callback whose rule matched is named: {callbacks:?}");
  assert_eq!(state.rules.matches(&probe("public")), vec![MATCHED_NONE]);

  let message = |text: &str| format!(r#"{{"_":"message","id":1,"message":"{text}","date":0}}"#);
  let context = ctx.clone();
  let verdict = state.dispatch(&context, &callbacks[1..], 0, &message("a secret"));
  assert_eq!(
    verdict, r#"S{"text":"redacted","entities":[{"_":"messageEntityItalic","offset":0,"length":8}]}"#,
    "the rewrite and the spoiler both apply"
  );
  assert!(logs.borrow().iter().any(|line| line.contains("synchronously")), "{:?}", logs.borrow());
  assert_eq!(state.dispatch(&context, &callbacks[1..], 0, &message("a secret to hide")), "H");

  eval(&ctx, "__dispose(); __dispose()");
  assert_eq!(host.changes.borrow().len(), 4, "a second dispose changes nothing");
  assert_eq!(state.rules.matches(&probe("a secret")).len(), 3);
}

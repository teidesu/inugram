use super::*;
use std::cell::{Cell, RefCell};

use rquickjs::Context;

/// mirrors Kotlin `ActionRegistry` rather than flagging refusals, because the two rules only
/// interact: rows are keyed by id per kind and the cap is consulted only for an id the host does
/// not already hold. `limit` 0 is unlimited.
#[derive(Default)]
pub(crate) struct TestActionHost {
  registered: RefCell<Vec<(i32, u32, String, Option<String>, Option<String>, i32)>>,
  unregistered: RefCell<Vec<(i32, u32)>>,
  editor: RefCell<Vec<(i32, i64, String)>>,
  rows: RefCell<Vec<(i32, String, u32, i32)>>,
  pub(crate) limit: Cell<usize>,
  refuse_register: RefCell<Option<String>>,
  refuse_editor: RefCell<Option<String>>,
}

impl TestActionHost {
  pub(crate) fn editor_ops(&self) -> Vec<(i32, i64, String)> {
    self.editor.borrow().clone()
  }
}

impl ActionHost for TestActionHost {
  fn action_register(
    &self,
    kind: i32,
    token: u32,
    id: &str,
    placements: i32,
    text: Option<&str>,
    icon: Option<&str>,
    dynamic_fields: i32,
  ) -> Option<String> {
    if let Some(err) = self.refuse_register.borrow().clone() {
      return Some(err);
    }
    let mut rows = self.rows.borrow_mut();
    if self.limit.get() != 0 {
      for bit in 0..i32::BITS {
        let placement = 1_i32.wrapping_shl(bit);
        if placements & placement == 0 {
          continue;
        }
        let count = rows.iter().filter(|r| r.0 == kind && r.1 != id && r.3 & placement != 0).count();
        if count >= self.limit.get() {
          return Some(format!("Pquota-exceeded\n\n\n\n'{id}' would be row {}", count + 1));
        }
      }
    }
    match rows.iter_mut().find(|r| r.0 == kind && r.1 == id) {
      Some(row) => {
        row.2 = token;
        row.3 = placements;
      }
      None => rows.push((kind, id.to_string(), token, placements)),
    }
    self.registered.borrow_mut().push((
      kind,
      token,
      id.to_string(),
      text.map(str::to_string),
      icon.map(str::to_string),
      dynamic_fields,
    ));
    None
  }
  fn action_unregister(&self, kind: i32, token: u32) {
    self.rows.borrow_mut().retain(|r| !(r.0 == kind && r.2 == token));
    self.unregistered.borrow_mut().push((kind, token));
  }
  fn action_editor(&self, op: i32, surface: i64, payload_json: &str) -> Option<String> {
    if let Some(err) = self.refuse_editor.borrow().clone() {
      return Some(err);
    }
    self.editor.borrow_mut().push((op, surface, payload_json.to_string()));
    None
  }
}

pub(crate) type Disposing = crate::testing::harness::DisposeOnDrop<ActionState>;
pub(crate) type Fixture =
  (Runtime, Context, Rc<TestActionHost>, Disposing, std::sync::Arc<crate::testing::harness::Logs>);

pub(crate) fn setup() -> Fixture {
  setup_with(Lifecycle::new(), &["account.read(draft)"])
}

fn setup_with(lifecycle: Rc<Lifecycle>, grants: &[&str]) -> Fixture {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let host = Rc::new(TestActionHost::default());
  let host_dyn: Rc<dyn ActionHost> = host.clone();
  let logs = crate::testing::harness::Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    let shared = crate::api::tl::utils::install_utils(&ctx, &inu).unwrap();
    crate::api::tl::message::install_message(&ctx, &shared, &inu).unwrap();
    let grants = crate::sandbox::grants::CachedGrantHost::new(grants);
    install_actions(&ctx, host_dyn, lifecycle, None, grants, None, log, &inu).unwrap()
  });
  let state = Disposing::new(&ctx, state, |ctx, state| state.dispose(ctx));
  (rt, ctx, host, state, logs)
}

use crate::testing::harness::eval_unit as eval;

fn eval_err(ctx: &Context, source: &str) -> String {
  ctx.with(|ctx| match ctx.eval::<Value, _>(source) {
    Ok(_) => panic!("expected a throw"),
    Err(rquickjs::Error::Exception) => format_exception(&ctx),
    Err(e) => panic!("{e:?}"),
  })
}

fn read_log(ctx: &Context) -> String {
  crate::testing::harness::eval_json(&ctx, "globalThis.__log")
}

pub(crate) const CHAT_SURFACE: &str = r#"{"accountId":0,"dialogId":-100,"topicId":7}"#;
pub(crate) const MESSAGE_SURFACE: &str = r#"{"accountId":0,"dialogId":-100,"source":"bubble","messages":[{"_":"message","id":11,"date":1,"message":"one","peer_id":{"_":"peerChat","chat_id":"100"},"dialog_id":"-100","grouped_id":"77"},{"_":"message","id":12,"date":2,"message":"two","peer_id":{"_":"peerChat","chat_id":"100"},"dialog_id":"-100","grouped_id":"77"}]}"#;
pub(crate) const SELECTION_SURFACE: &str = r#"{"accountId":0,"dialogId":-100,"topicId":7,"source":"selection","messages":[{"_":"message","id":3,"date":1,"message":"three","peer_id":{"_":"peerChat","chat_id":"200"},"dialog_id":"-200"},{"_":"message","id":14,"date":2,"message":"fourteen","peer_id":{"_":"peerChat","chat_id":"100"},"dialog_id":"-100"}]}"#;

pub(crate) fn row(token: u32, text: &str) -> String {
  format!(r#"{{"token":{token},"text":"{text}"}}"#)
}

pub(crate) fn rows(entries: &[String]) -> String {
  format!("[{}]", entries.join(","))
}

#[test]
fn a_static_row_renders_without_running_plugin_code() {
  let (rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      globalThis.__log = [];
      inu.registerChatAction({ id: 'a', text: 'Alpha', callback: () => { __log.push('a') } })
    "#,
  );
  assert_eq!(host.registered.borrow().len(), 1);
  assert_eq!(host.registered.borrow()[0], (KIND_CHAT, 1, "a".to_string(), Some("Alpha".to_string()), None, 0),);
  let json = state.render(&rt, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(1, "Alpha")]));
}

#[test]
fn a_dynamic_label_and_visible_see_the_surface() {
  let (rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      inu.registerMessageAction({
        id: 'a',
        text: ctx => `${ctx.source} ${ctx.messages.map(message => message.id).join('+')} in ${ctx.dialogId}`,
        visible: ctx => ctx.messages.length === 2,
        callback: () => {},
      });
      inu.registerMessageAction({
        id: 'b', text: 'never', visible: () => false, callback: () => {},
      })
    "#,
  );
  assert_eq!(host.registered.borrow()[0].3.as_deref(), None);
  assert_eq!(host.registered.borrow()[0].5, DYNAMIC_TEXT | DYNAMIC_VISIBLE);
  assert_eq!(host.registered.borrow()[1].3.as_deref(), Some("never"));
  assert_eq!(host.registered.borrow()[1].5, DYNAMIC_VISIBLE);
  let json = state.render(&rt, &ctx, KIND_MESSAGE, MESSAGE_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(1, "bubble 11+12 in -100")]));
}

#[test]
fn message_placements_filter_each_surface_and_settings_include_every_placement() {
  let (rt, ctx, _host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      inu.registerMessageAction({ id: 'bubble', text: 'bubble', callback: () => {} });
      inu.registerMessageAction({ id: 'selection', placements: ['selection'], text: 'selection', callback: () => {} });
      inu.registerMessageAction({ id: 'both', placements: ['bubble', 'selection'], text: 'both', callback: () => {} })
    "#,
  );

  assert_eq!(
    state.render(&rt, &ctx, KIND_MESSAGE, MESSAGE_SURFACE).unwrap(),
    rows(&[row(1, "bubble"), row(3, "both")])
  );
  assert_eq!(
    state.render(&rt, &ctx, KIND_MESSAGE, SELECTION_SURFACE).unwrap(),
    rows(&[row(2, "selection"), row(3, "both")]),
  );
  assert_eq!(
    state.render(&rt, &ctx, KIND_MESSAGE, "null").unwrap(),
    rows(&[row(1, "bubble"), row(2, "selection"), row(3, "both")]),
  );
}

#[test]
fn message_placements_reject_empty_unknown_and_non_array_values() {
  let (_rt, ctx, _host, _state, _logs) = setup();
  for source in [
    "inu.registerMessageAction({ id: 'a', placements: [], text: 'a', callback: () => {} })",
    "inu.registerMessageAction({ id: 'a', placements: ['other'], text: 'a', callback: () => {} })",
    "inu.registerMessageAction({ id: 'a', placements: 'bubble', text: 'a', callback: () => {} })",
  ] {
    let error = eval_err(&ctx, source);
    assert!(error.contains("placement"), "{source}: {error}");
  }
}

#[test]
fn re_registering_an_id_replaces_the_row_in_place_and_retires_its_token() {
  let (rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      inu.registerChatAction({ id: 'a', text: 'A', callback: () => {} });
      inu.registerChatAction({ id: 'b', text: 'B', callback: () => {} });
      globalThis.__disposeFirst = null;
      inu.registerChatAction({ id: 'a', text: 'A2', callback: () => {} })
    "#,
  );
  let json = state.render(&rt, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(3, "A2"), row(2, "B")]), "the replacement keeps its predecessor's position");
  assert_eq!(
    *host.unregistered.borrow(),
    vec![(KIND_CHAT, 1)],
    "the host is told the displaced row is gone, or it keeps sizing a menu for it"
  );
}

#[test]
fn a_disposer_removes_the_row_and_tells_the_host_once() {
  let (rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      globalThis.__d = inu.registerChatAction({ id: 'a', text: 'A', callback: () => {} });
      inu.registerChatAction({ id: 'b', text: 'B', callback: () => {} });
      __d(); __d()
    "#,
  );
  let json = state.render(&rt, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(2, "B")]));
  assert_eq!(*host.unregistered.borrow(), vec![(KIND_CHAT, 1)]);
}

#[test]
fn registering_after_unload_began_registers_nothing() {
  let lifecycle = Lifecycle::new();
  lifecycle.begin_unload();
  let (rt, ctx, host, state, _logs) = setup_with(lifecycle, &["account.read(draft)"]);
  eval(
    &ctx,
    r#"
      globalThis.__d = inu.registerChatAction({ id: 'a', text: 'A', callback: () => {} });
      __d()
    "#,
  );
  assert!(host.registered.borrow().is_empty());
  let json = state.render(&rt, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, "[]");
}

#[test]
fn a_malformed_registration_throws_even_while_unloading() {
  let lifecycle = Lifecycle::new();
  lifecycle.begin_unload();
  let (_rt, ctx, _host, _state, _logs) = setup_with(lifecycle, &["account.read(draft)"]);
  let err = eval_err(&ctx, "inu.registerChatAction({ id: 'a', text: 'A' })");
  assert!(err.contains("'callback' must be a function"), "{err}");
}

#[test]
fn static_and_dynamic_icons_are_rendered() {
  let (rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      inu.registerChatAction({
        id: 'a', text: 'A', icon: { __inuIcon: 'rmsg_settings' }, callback: () => {},
      });
      inu.registerChatAction({
        id: 'b', text: 'B', icon: () => ({ __inuIcon: 'rmsg_pin' }), callback: () => {},
      })
    "#,
  );
  assert_eq!(host.registered.borrow()[0].4.as_deref(), Some("rmsg_settings"));
  assert_eq!(host.registered.borrow()[0].5, 0);
  assert_eq!(host.registered.borrow()[1].4.as_deref(), None);
  assert_eq!(host.registered.borrow()[1].5, DYNAMIC_ICON);
  let json = state.render(&rt, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, r#"[{"token":1,"text":"A","icon":"rmsg_settings"},{"token":2,"text":"B","icon":"rmsg_pin"}]"#,);
}

#[test]
fn chat_and_message_getters_receive_null_in_settings() {
  let (rt, ctx, _host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      inu.registerChatAction({
        id: 'a', text: ctx => ctx === null ? 'chat settings' : 'chat', callback: () => {},
      });
      inu.registerMessageAction({
        id: 'b', text: ctx => ctx === null ? 'message settings' : 'message', callback: () => {},
      })
    "#,
  );
  assert_eq!(state.render(&rt, &ctx, KIND_CHAT, "null").unwrap(), rows(&[row(1, "chat settings")]));
  assert_eq!(state.render(&rt, &ctx, KIND_MESSAGE, "null").unwrap(), rows(&[row(1, "message settings")]));
}

/// a JNI failure is the bridge's, so the plugin is told `internal` rather than to shed rows
#[test]
fn a_host_that_refuses_a_registration_throws_internal_and_leaves_nothing_behind() {
  let (rt, ctx, host, state, _logs) = setup();
  *host.refuse_register.borrow_mut() = Some("registerAction: JNI env unavailable".to_string());
  let caught = crate::testing::harness::catch_json(
    &ctx,
    "inu.registerChatAction({ id: 'a', text: ctx => 'A', callback: () => {} })",
  );
  assert!(
    caught.starts_with(r#"[true,"internal",null,"#) && caught.contains("JNI env unavailable"),
    "{caught}"
  );
  assert_eq!(state.render(&rt, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap(), "[]");
}

#[test]
fn a_callback_that_throws_is_the_plugins_fault() {
  let (rt, ctx, _host, state, logs) = setup();
  eval(
    &ctx,
    r#"inu.registerChatAction({ id: 'a', text: 'A', callback: () => { throw new Error('nope') } })"#,
  );
  state.dispatch(&rt, &ctx, KIND_CHAT, 1, CHAT_SURFACE);
  let logs = logs.borrow();
  assert!(logs.iter().any(|l| l.starts_with(crate::FAULT_PREFIX) && l.contains("nope")), "{logs:#?}");
}

/// tokens are never reused, so a row drawn before a replacement cannot be answered by the row
/// that replaced it, which is what makes a menu left open across a reload safe
#[test]
fn a_dispatch_for_a_disposed_or_replaced_row_does_nothing() {
  let (rt, ctx, _host, state, logs) = setup();
  eval(
    &ctx,
    r#"
      globalThis.__log = [];
      inu.registerChatAction({ id: 'a', text: 'A', callback: () => { __log.push('first') } });
      globalThis.__d = inu.registerChatAction({ id: 'a', text: 'A2', callback: () => { __log.push('second') } })
    "#,
  );
  state.dispatch(&rt, &ctx, KIND_CHAT, 1, CHAT_SURFACE);
  assert_eq!(read_log(&ctx), "[]");
  state.dispatch(&rt, &ctx, KIND_CHAT, 2, CHAT_SURFACE);
  assert_eq!(read_log(&ctx), r#"["second"]"#);
  eval(&ctx, "__d()");
  state.dispatch(&rt, &ctx, KIND_CHAT, 2, CHAT_SURFACE);
  assert_eq!(read_log(&ctx), r#"["second"]"#, "the stale row is inert");
  assert!(logs.borrow().is_empty());
}

#[test]
fn a_row_disposed_by_an_earlier_rows_visible_is_not_drawn() {
  let (rt, ctx, _host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      globalThis.__d = null;
      inu.registerChatAction({ id: 'a', text: 'A', visible: () => { __d(); return true }, callback: () => {} });
      globalThis.__d = inu.registerChatAction({ id: 'b', text: 'B', callback: () => {} })
    "#,
  );
  let json = state.render(&rt, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(1, "A")]));
}

#[test]
fn a_row_registered_mid_render_joins_the_next_one() {
  let (rt, ctx, _host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      inu.registerChatAction({
        id: 'a', text: 'A',
        visible: () => {
          if (!globalThis.__added) {
            globalThis.__added = true;
            inu.registerChatAction({ id: 'b', text: 'B', callback: () => {} });
          }
          return true;
        },
        callback: () => {},
      })
    "#,
  );
  let json = state.render(&rt, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(1, "A")]));
  let json = state.render(&rt, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(1, "A"), row(2, "B")]));
}

#[test]
fn kinds_are_separate_registries() {
  let (rt, ctx, _host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      inu.registerChatAction({ id: 'a', text: 'chat', callback: () => {} });
      inu.registerMessageAction({ id: 'a', text: 'message', callback: () => {} })
    "#,
  );
  assert_eq!(state.render(&rt, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap(), rows(&[row(1, "chat")]));
  assert_eq!(state.render(&rt, &ctx, KIND_MESSAGE, MESSAGE_SURFACE).unwrap(), rows(&[row(1, "message")]));
}

/// the composer's text is app state, and `getDraft` charges `account.read(draft)` for the very
/// same thing - so a row a plugin drew is not a way around that gate. `send` still works, or
/// the refusal would take the whole api away from a row that never reads anything.
#[test]
fn reading_the_draft_needs_the_same_grant_get_draft_does() {
  let (rt, ctx, host, state, _logs) = setup_with(Lifecycle::new(), &[]);
  eval(
    &ctx,
    r#"
      globalThis.__log = [];
      inu.registerMessageEditorAction({
        id: 'a', text: 'Shout',
        callback: ctx => {
          try {
            __log.push(ctx.draft.text)
          } catch (e) {
            __log.push(e.code + ':' + e.message)
          }
          ctx.replace('written anyway');
        },
      })
    "#,
  );
  let surface = r#"{"accountId":0,"dialogId":5,"surface":42,"draft":{"text":"hi there"}}"#;
  state.dispatch(&rt, &ctx, KIND_EDITOR, 1, surface);
  let log = read_log(&ctx);
  assert!(log.contains("not-granted"), "{log}");
  assert!(log.contains("account.read(draft)"), "{log}");
  assert_eq!(host.editor.borrow().len(), 1, "the write half is not gated on a read scope");
}

#[test]
fn a_bad_editor_argument_is_an_invalid_argument_error() {
  let (rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      globalThis.__log = [];
      inu.registerMessageEditorAction({
        id: 'a', text: 'x',
        callback: ctx => {
          try { ctx.replace(42) } catch (e) { __log.push(`${e.name}:${e.code}`) }
          try { ctx.send({ text: 'ok', entities: 'no' }) } catch (e) { __log.push(`${e.name}:${e.code}`) }
        },
      })
    "#,
  );
  state.dispatch(&rt, &ctx, KIND_EDITOR, 1, r#"{"accountId":0,"dialogId":5,"surface":1,"draft":{"text":""}}"#);
  assert_eq!(read_log(&ctx), r#"["PluginError:invalid-argument","PluginError:invalid-argument"]"#);
  assert!(host.editor.borrow().is_empty());
}

#[test]
fn a_host_that_refuses_an_editor_op_throws_into_the_callback() {
  let (rt, ctx, host, state, _logs) = setup();
  *host.refuse_editor.borrow_mut() = Some("the composer is gone".to_string());
  eval(
    &ctx,
    r#"
      globalThis.__log = [];
      inu.registerMessageEditorAction({
        id: 'a', text: 'x',
        callback: ctx => { try { ctx.send('hi') } catch (e) { __log.push(e.message) } },
      })
    "#,
  );
  state.dispatch(&rt, &ctx, KIND_EDITOR, 1, r#"{"accountId":0,"dialogId":5,"surface":1,"draft":{"text":""}}"#);
  assert_eq!(read_log(&ctx), r#"["the composer is gone"]"#);
}

#[test]
fn the_bundled_actions_test_plugin_passes() {
  let (rt, ctx, host, state, logs) = setup();
  // the oracle asserts the cap, so the harness has to have one; the device's is the same 8
  host.limit.set(8);
  let lines = crate::testing::harness::install_capturing_console(&ctx);
  eval(&ctx, crate::testing::test_plugin!("actions-test.js"));

  assert_eq!(state.render(&rt, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap(), rows(&[row(2, "Chat row")]));
  assert_eq!(state.render(&rt, &ctx, KIND_MESSAGE, MESSAGE_SURFACE).unwrap(), rows(&[row(1, "Message row")]));
  state.dispatch(&rt, &ctx, KIND_MESSAGE, 1, MESSAGE_SURFACE);
  state.dispatch(&rt, &ctx, KIND_MESSAGE, 1, SELECTION_SURFACE);
  state.dispatch(&rt, &ctx, KIND_PROFILE, 1, CHAT_SURFACE);
  state.dispatch(&rt, &ctx, KIND_GLOBAL, 1, r#"{"accountId":0}"#);
  let editor_surface = r#"{"accountId":0,"dialogId":-100,"surface":42,"draft":{"text":"hello"}}"#;
  state.dispatch(&rt, &ctx, KIND_EDITOR, 1, editor_surface);

  while rt.is_job_pending() {
    rt.execute_pending_job().ok();
  }
  let lines = lines.borrow().clone();
  crate::testing::harness::assert_oracle_exact(&lines, "actions test done", 19);
  let editor = host.editor_ops();
  assert_eq!(editor.len(), 2);
  assert_eq!(editor[0], (EDITOR_REPLACE, 42, r#"{"text":"replaced"}"#.to_string()));
  assert_eq!((editor[1].0, editor[1].1), (EDITOR_SEND, 42));
  assert!(editor[1].2.contains("messageEntityBold"), "{}", editor[1].2);
  // what the oracle cannot see about itself: its throwing row was dropped from the render
  // above and left the plugin running, so every assertion after it still ran
  let logs = logs.borrow();
  assert!(logs.iter().any(|l| l.contains("visible blew up")), "{logs:#?}");
  assert!(!logs.iter().any(|l| l.starts_with(crate::FAULT_PREFIX)), "{logs:#?}");
}

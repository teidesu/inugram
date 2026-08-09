use super::*;
use std::cell::{Cell, RefCell};

use rquickjs::Context;

/// mirrors Kotlin `ActionRegistry` rather than flagging refusals, because the two rules only
/// interact: rows are keyed by id per kind and the cap is consulted only for an id the host does
/// not already hold. `limit` 0 is unlimited.
#[derive(Default)]
pub(crate) struct TestActionHost {
  registered: RefCell<Vec<(i32, u32, String)>>,
  unregistered: RefCell<Vec<(i32, u32)>>,
  editor: RefCell<Vec<(i32, i64, String)>>,
  rows: RefCell<Vec<(i32, String, u32)>>,
  pub(crate) limit: Cell<usize>,
  refuse_register: RefCell<Option<String>>,
  refuse_editor: RefCell<Option<String>>,
}

impl TestActionHost {
  pub(crate) fn editor_ops(&self) -> Vec<(i32, i64, String)> {
    self.editor.borrow().clone()
  }

  fn row_count(&self, kind: i32) -> usize {
    self.rows.borrow().iter().filter(|r| r.0 == kind).count()
  }
}

impl ActionHost for TestActionHost {
  fn action_register(&self, kind: i32, token: u32, id: &str) -> Option<String> {
    if let Some(err) = self.refuse_register.borrow().clone() {
      return Some(err);
    }
    let mut rows = self.rows.borrow_mut();
    match rows.iter_mut().find(|r| r.0 == kind && r.1 == id) {
      Some(row) => row.2 = token,
      None => {
        let count = rows.iter().filter(|r| r.0 == kind).count();
        if self.limit.get() != 0 && count >= self.limit.get() {
          // `PluginActions.register` wraps the registry's refusal exactly like this
          return Some(format!("Pquota-exceeded\n\n\n\n'{id}' would be row {}", count + 1));
        }
        rows.push((kind, id.to_string(), token));
      }
    }
    self.registered.borrow_mut().push((kind, token, id.to_string()));
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
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let host = Rc::new(TestActionHost::default());
  let host_dyn: Rc<dyn ActionHost> = host.clone();
  let logs = crate::testing::harness::Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::inu_namespace(&ctx);
    crate::api::error::install_plugin_error(&ctx, &inu).unwrap();
    let grants = crate::sandbox::grants::TestGrantHost::new(grants).as_host();
    install_actions(&ctx, host_dyn, lifecycle, None, grants, log, &inu).unwrap()
  });
  let state = Disposing::new(&ctx, state, dispose);
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
  ctx.with(|ctx| ctx.eval::<String, _>("JSON.stringify(globalThis.__log)").unwrap())
}

pub(crate) const CHAT_SURFACE: &str = r#"{"accountId":0,"dialogId":-100,"topicId":7}"#;
pub(crate) const MESSAGE_SURFACE: &str = r#"{"accountId":0,"dialogId":-100,"messageIds":[11,12]}"#;

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
    r#"globalThis.__log = [];
           inu.registerChatAction({ id: 'a', text: 'Alpha', callback: () => { __log.push('a') } })"#,
  );
  assert_eq!(host.registered.borrow().len(), 1);
  let json = render_actions(&rt, &ctx, &state, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(1, "Alpha")]));
}

#[test]
fn a_dynamic_label_and_visible_see_the_surface() {
  let (rt, ctx, _host, state, _logs) = setup();
  eval(
    &ctx,
    r#"inu.registerMessageAction({
               id: 'a',
               text: ctx => `msg ${ctx.messageIds.join('+')} in ${ctx.dialogId}`,
               visible: ctx => ctx.messageIds.length === 2,
               callback: () => {},
           });
           inu.registerMessageAction({
               id: 'b', text: 'never', visible: () => false, callback: () => {},
           })"#,
  );
  let json = render_actions(&rt, &ctx, &state, KIND_MESSAGE, MESSAGE_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(1, "msg 11+12 in -100")]));
}

#[test]
fn a_topic_is_absent_rather_than_zero_when_the_surface_has_none() {
  let (rt, ctx, _host, state, _logs) = setup();
  eval(
    &ctx,
    r#"inu.registerChatAction({
               id: 'a', text: ctx => String(ctx.topicId === undefined), callback: () => {},
           })"#,
  );
  let json = render_actions(&rt, &ctx, &state, KIND_CHAT, r#"{"accountId":0,"dialogId":5}"#).unwrap();
  assert_eq!(json, rows(&[row(1, "true")]));
  let json = render_actions(&rt, &ctx, &state, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(1, "false")]));
}

#[test]
fn a_global_action_gets_no_dialog_at_all() {
  let (rt, ctx, _host, state, _logs) = setup();
  eval(
    &ctx,
    r#"inu.registerAction({
               id: 'a',
               text: ctx => `${'dialogId' in ctx}/${'messageIds' in ctx}`,
               callback: () => {},
           })"#,
  );
  let json = render_actions(&rt, &ctx, &state, KIND_GLOBAL, r#"{"accountId":2}"#).unwrap();
  assert_eq!(json, rows(&[row(1, "false/false")]));
}

#[test]
fn rows_render_in_registration_order() {
  let (rt, ctx, _host, state, _logs) = setup();
  eval(
    &ctx,
    r#"for (const id of ['c', 'a', 'b']) {
               inu.registerChatAction({ id, text: id.toUpperCase(), callback: () => {} })
           }"#,
  );
  let json = render_actions(&rt, &ctx, &state, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(1, "C"), row(2, "A"), row(3, "B")]));
}

#[test]
fn re_registering_an_id_replaces_the_row_in_place_and_retires_its_token() {
  let (rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"inu.registerChatAction({ id: 'a', text: 'A', callback: () => {} });
           inu.registerChatAction({ id: 'b', text: 'B', callback: () => {} });
           globalThis.__disposeFirst = null;
           inu.registerChatAction({ id: 'a', text: 'A2', callback: () => {} })"#,
  );
  let json = render_actions(&rt, &ctx, &state, KIND_CHAT, CHAT_SURFACE).unwrap();
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
    r#"globalThis.__d = inu.registerChatAction({ id: 'a', text: 'A', callback: () => {} });
           inu.registerChatAction({ id: 'b', text: 'B', callback: () => {} });
           __d(); __d()"#,
  );
  let json = render_actions(&rt, &ctx, &state, KIND_CHAT, CHAT_SURFACE).unwrap();
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
    r#"globalThis.__d = inu.registerChatAction({ id: 'a', text: 'A', callback: () => {} });
           __d()"#,
  );
  assert!(host.registered.borrow().is_empty());
  let json = render_actions(&rt, &ctx, &state, KIND_CHAT, CHAT_SURFACE).unwrap();
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
fn an_icon_is_refused_rather_than_accepted_and_dropped() {
  let (_rt, ctx, host, _state, _logs) = setup();
  let err = eval_err(&ctx, "inu.registerChatAction({ id: 'a', text: 'A', icon: {}, callback: () => {} })");
  assert!(err.contains("does not carry an icon"), "{err}");
  assert!(host.registered.borrow().is_empty(), "nothing is registered for a refused row");
}

#[test]
fn a_host_that_refuses_a_registration_leaves_nothing_behind() {
  let (rt, ctx, host, state, _logs) = setup();
  *host.refuse_register.borrow_mut() = Some("too many rows".to_string());
  let err = eval_err(&ctx, "inu.registerChatAction({ id: 'a', text: ctx => 'A', callback: () => {} })");
  assert!(err.contains("too many rows"), "{err}");
  let json = render_actions(&rt, &ctx, &state, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, "[]");
}

/// a limit is `quota-exceeded` in this contract's vocabulary, like every other one, so a plugin
/// can branch on it rather than matching the host's wording
#[test]
fn the_row_cap_reaches_the_plugin_as_a_quota_exceeded_plugin_error() {
  let (_rt, ctx, host, _state, _logs) = setup();
  host.limit.set(2);
  let code = ctx.with(|ctx| {
    ctx
      .eval::<String, _>(
        r#"(() => {
                   for (const id of ['a', 'b']) {
                       inu.registerChatAction({ id, text: id, callback: () => {} })
                   }
                   try {
                       inu.registerChatAction({ id: 'c', text: 'C', callback: () => {} });
                       return 'did not throw';
                   } catch (e) {
                       return `${e instanceof inu.PluginError}:${e.code}:${e.message.includes("'c' would be row 3")}`;
                   }
               })()"#,
      )
      .unwrap()
  });
  assert_eq!(code, "true:quota-exceeded:true");
}

/// the other half of that rule. `action_register` answers a JNI-level failure the same way
/// every other upcall does, and reporting one as `quota-exceeded` tells a plugin to back off a
/// row count it is nowhere near while blaming the author for the host's bad day.
#[test]
fn a_host_failure_is_not_reported_to_the_plugin_as_a_quota() {
  let (_rt, ctx, host, _state, _logs) = setup();
  *host.refuse_register.borrow_mut() = Some("registerAction: JNI env unavailable".to_string());
  let code = ctx.with(|ctx| {
    ctx
      .eval::<String, _>(
        r#"(() => {
                   try {
                       inu.registerChatAction({ id: 'a', text: 'A', callback: () => {} });
                       return 'did not throw';
                   } catch (e) {
                       return `${e instanceof inu.PluginError}:${e.message.includes('JNI env unavailable')}`;
                   }
               })()"#,
      )
      .unwrap()
  });
  assert_eq!(code, "false:true");
}

/// re-registering an id is the documented way to change a row, and the replacement's token is
/// allocated before the displaced one is retired - so a cap consulted on the count alone leaves
/// a plugin at the cap unable to update any of its rows for the rest of the process
#[test]
fn a_keyed_re_registration_at_the_cap_replaces_rather_than_being_refused() {
  let (rt, ctx, host, state, _logs) = setup();
  host.limit.set(8);
  eval(
    &ctx,
    r#"for (const id of ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h']) {
               inu.registerChatAction({ id, text: id.toUpperCase(), callback: () => {} })
           }
           inu.registerChatAction({ id: 'a', text: 'A2', callback: () => {} })"#,
  );
  assert_eq!(host.row_count(KIND_CHAT), 8, "the replacement took the place its predecessor held");
  let json = render_actions(&rt, &ctx, &state, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(
    json,
    rows(&[row(9, "A2"), row(2, "B"), row(3, "C"), row(4, "D"), row(5, "E"), row(6, "F"), row(7, "G"), row(8, "H"),])
  );
  let err = eval_err(&ctx, "inu.registerChatAction({ id: 'i', text: 'I', callback: () => {} })");
  assert!(err.contains("would be row 9"), "a genuinely new row is still refused: {err}");
}

/// `common.d.ts`: a throwing `visible`/`text` drops that one row. It is not a fault, or a
/// predicate that fails on one chat would switch off every other feature the plugin provides.
#[test]
fn a_row_whose_visible_throws_is_dropped_without_disabling_the_plugin() {
  let (rt, ctx, _host, state, logs) = setup();
  eval(
    &ctx,
    r#"inu.registerChatAction({ id: 'a', text: 'A', visible: () => { throw new Error('boom') }, callback: () => {} });
           inu.registerChatAction({ id: 'b', text: () => { throw new Error('bang') }, callback: () => {} });
           inu.registerChatAction({ id: 'c', text: 'C', callback: () => {} })"#,
  );
  let json = render_actions(&rt, &ctx, &state, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(3, "C")]), "the other rows still render");
  let logs = logs.borrow();
  assert!(logs.iter().any(|l| l.contains("boom")), "{logs:#?}");
  assert!(logs.iter().any(|l| l.contains("bang")), "{logs:#?}");
  assert!(
    !logs.iter().any(|l| l.starts_with(crate::FAULT_PREFIX)),
    "a dropped row must not disable the plugin: {logs:#?}"
  );
}

#[test]
fn a_callback_that_throws_is_the_plugins_fault() {
  let (rt, ctx, _host, state, logs) = setup();
  eval(
    &ctx,
    r#"inu.registerChatAction({ id: 'a', text: 'A', callback: () => { throw new Error('nope') } })"#,
  );
  dispatch_action(&rt, &ctx, &state, KIND_CHAT, 1, CHAT_SURFACE);
  let logs = logs.borrow();
  assert!(logs.iter().any(|l| l.starts_with(crate::FAULT_PREFIX) && l.contains("nope")), "{logs:#?}");
}

#[test]
fn a_dispatch_for_a_disposed_row_does_nothing() {
  let (rt, ctx, _host, state, logs) = setup();
  eval(
    &ctx,
    r#"globalThis.__log = [];
           globalThis.__d = inu.registerChatAction({ id: 'a', text: 'A', callback: () => { __log.push('ran') } })"#,
  );
  dispatch_action(&rt, &ctx, &state, KIND_CHAT, 1, CHAT_SURFACE);
  assert_eq!(read_log(&ctx), r#"["ran"]"#);
  eval(&ctx, "__d()");
  dispatch_action(&rt, &ctx, &state, KIND_CHAT, 1, CHAT_SURFACE);
  assert_eq!(read_log(&ctx), r#"["ran"]"#, "the stale row is inert");
  assert!(logs.borrow().is_empty());
}

/// tokens are never reused, so a row drawn before a replacement cannot be answered by the row
/// that replaced it - which is the whole reason a menu left open across a reload is safe
#[test]
fn a_dispatch_for_a_replaced_row_does_not_reach_its_replacement() {
  let (rt, ctx, _host, state, _logs) = setup();
  eval(
    &ctx,
    r#"globalThis.__log = [];
           inu.registerChatAction({ id: 'a', text: 'A', callback: () => { __log.push('first') } });
           inu.registerChatAction({ id: 'a', text: 'A2', callback: () => { __log.push('second') } })"#,
  );
  dispatch_action(&rt, &ctx, &state, KIND_CHAT, 1, CHAT_SURFACE);
  assert_eq!(read_log(&ctx), "[]");
  dispatch_action(&rt, &ctx, &state, KIND_CHAT, 2, CHAT_SURFACE);
  assert_eq!(read_log(&ctx), r#"["second"]"#);
}

#[test]
fn a_row_disposed_by_an_earlier_rows_visible_is_not_drawn() {
  let (rt, ctx, _host, state, _logs) = setup();
  eval(
    &ctx,
    r#"globalThis.__d = null;
           inu.registerChatAction({ id: 'a', text: 'A', visible: () => { __d(); return true }, callback: () => {} });
           globalThis.__d = inu.registerChatAction({ id: 'b', text: 'B', callback: () => {} })"#,
  );
  let json = render_actions(&rt, &ctx, &state, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(1, "A")]));
}

#[test]
fn a_row_registered_mid_render_joins_the_next_one() {
  let (rt, ctx, _host, state, _logs) = setup();
  eval(
    &ctx,
    r#"inu.registerChatAction({
               id: 'a', text: 'A',
               visible: () => {
                   if (!globalThis.__added) {
                       globalThis.__added = true;
                       inu.registerChatAction({ id: 'b', text: 'B', callback: () => {} });
                   }
                   return true;
               },
               callback: () => {},
           })"#,
  );
  let json = render_actions(&rt, &ctx, &state, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(1, "A")]));
  let json = render_actions(&rt, &ctx, &state, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(1, "A"), row(2, "B")]));
}

#[test]
fn kinds_are_separate_registries() {
  let (rt, ctx, _host, state, _logs) = setup();
  eval(
    &ctx,
    r#"inu.registerChatAction({ id: 'a', text: 'chat', callback: () => {} });
           inu.registerMessageAction({ id: 'a', text: 'message', callback: () => {} })"#,
  );
  assert_eq!(render_actions(&rt, &ctx, &state, KIND_CHAT, CHAT_SURFACE).unwrap(), rows(&[row(1, "chat")]));
  assert_eq!(
    render_actions(&rt, &ctx, &state, KIND_MESSAGE, MESSAGE_SURFACE).unwrap(),
    rows(&[row(1, "message")])
  );
}

#[test]
fn the_editor_context_carries_the_draft_and_crosses_replace_and_send() {
  let (rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"globalThis.__log = [];
           inu.registerMessageEditorAction({
               id: 'a', text: 'Shout',
               callback: ctx => {
                   __log.push(ctx.draft.text);
                   ctx.replace(ctx.draft.text.toUpperCase());
                   ctx.send({ text: 'sent', entities: [{ _: 'messageEntityBold', offset: 0, length: 4 }] });
               },
           })"#,
  );
  let surface = r#"{"accountId":0,"dialogId":5,"surface":42,"draft":{"text":"hi there"}}"#;
  dispatch_action(&rt, &ctx, &state, KIND_EDITOR, 1, surface);
  assert_eq!(read_log(&ctx), r#"["hi there"]"#);
  let editor = host.editor.borrow();
  assert_eq!(editor.len(), 2);
  assert_eq!(editor[0].0, EDITOR_REPLACE);
  assert_eq!(editor[0].1, 42);
  assert_eq!(editor[0].2, r#"{"text":"HI THERE"}"#);
  assert_eq!(editor[1].0, EDITOR_SEND);
  assert!(editor[1].2.contains("messageEntityBold"), "{}", editor[1].2);
}

/// the composer's text is app state, and `getDraft` charges `account.read(draft)` for the very
/// same thing - so a row a plugin drew is not a way around that gate. `send` still works, or
/// the refusal would take the whole api away from a row that never reads anything.
#[test]
fn reading_the_draft_needs_the_same_grant_get_draft_does() {
  let (rt, ctx, host, state, _logs) = setup_with(Lifecycle::new(), &[]);
  eval(
    &ctx,
    r#"globalThis.__log = [];
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
           })"#,
  );
  let surface = r#"{"accountId":0,"dialogId":5,"surface":42,"draft":{"text":"hi there"}}"#;
  dispatch_action(&rt, &ctx, &state, KIND_EDITOR, 1, surface);
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
    r#"globalThis.__log = [];
           inu.registerMessageEditorAction({
               id: 'a', text: 'x',
               callback: ctx => {
                   try { ctx.replace(42) } catch (e) { __log.push(`${e.name}:${e.code}`) }
                   try { ctx.send({ text: 'ok', entities: 'no' }) } catch (e) { __log.push(`${e.name}:${e.code}`) }
               },
           })"#,
  );
  dispatch_action(&rt, &ctx, &state, KIND_EDITOR, 1, r#"{"accountId":0,"dialogId":5,"surface":1,"draft":{"text":""}}"#);
  assert_eq!(read_log(&ctx), r#"["PluginError:invalid-argument","PluginError:invalid-argument"]"#);
  assert!(host.editor.borrow().is_empty());
}

#[test]
fn a_host_that_refuses_an_editor_op_throws_into_the_callback() {
  let (rt, ctx, host, state, _logs) = setup();
  *host.refuse_editor.borrow_mut() = Some("the composer is gone".to_string());
  eval(
    &ctx,
    r#"globalThis.__log = [];
           inu.registerMessageEditorAction({
               id: 'a', text: 'x',
               callback: ctx => { try { ctx.send('hi') } catch (e) { __log.push(e.message) } },
           })"#,
  );
  dispatch_action(&rt, &ctx, &state, KIND_EDITOR, 1, r#"{"accountId":0,"dialogId":5,"surface":1,"draft":{"text":""}}"#);
  assert_eq!(read_log(&ctx), r#"["the composer is gone"]"#);
}

#[cfg(test)]
mod bundled_oracle {
  use super::*;

  const ORACLE: &str = include_str!("../../../../res/assets-debug/inu_plugins/actions-test.js");

  #[test]
  fn the_bundled_actions_test_plugin_passes() {
    let (rt, ctx, host, state, logs) = setup();
    // the oracle asserts the cap, so the harness has to have one; the device's is the same 8
    host.limit.set(8);
    let lines = crate::testing::harness::install_capturing_console(&ctx);
    ctx.with(|ctx| match ctx.eval::<(), _>(ORACLE) {
      Ok(()) => {}
      Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
      Err(e) => panic!("{e:?}"),
    });

    assert_eq!(render_actions(&rt, &ctx, &state, KIND_CHAT, CHAT_SURFACE).unwrap(), rows(&[row(2, "Chat row")]),);
    assert_eq!(
      render_actions(&rt, &ctx, &state, KIND_MESSAGE, MESSAGE_SURFACE).unwrap(),
      rows(&[row(1, "Message row")]),
    );
    dispatch_action(&rt, &ctx, &state, KIND_MESSAGE, 1, MESSAGE_SURFACE);
    dispatch_action(&rt, &ctx, &state, KIND_PROFILE, 1, CHAT_SURFACE);
    dispatch_action(&rt, &ctx, &state, KIND_GLOBAL, 1, r#"{"accountId":0}"#);
    dispatch_action(
      &rt,
      &ctx,
      &state,
      KIND_EDITOR,
      1,
      r#"{"accountId":0,"dialogId":-100,"surface":1,"draft":{"text":"hello"}}"#,
    );

    while rt.is_job_pending() {
      rt.execute_pending_job().ok();
    }
    let lines = lines.borrow().clone();
    crate::testing::harness::assert_oracle_exact(&lines, "actions test done", ORACLE_ASSERTIONS);
    assert_eq!(host.editor_ops().len(), 2);
    // what the oracle cannot see about itself: its throwing row was dropped from the render
    // above and left the plugin running, so every assertion after it still ran
    let logs = logs.borrow();
    assert!(logs.iter().any(|l| l.contains("visible blew up")), "{logs:#?}");
    assert!(!logs.iter().any(|l| l.starts_with(crate::FAULT_PREFIX)), "{logs:#?}");
  }

  const ORACLE_ASSERTIONS: usize = 17;
}

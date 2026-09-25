use super::*;
use crate::api::error::install_rejection_tracker;
use crate::api::telegram::account::tests::{TestAccountHost, TWO_ACCOUNTS};
use crate::api::tl::proxy::TlHost;
use crate::sandbox::grants::CachedGrantHost;
use rquickjs::Context;

#[derive(Default)]
pub(crate) struct TestHost {
  pub(crate) registered: RefCell<Vec<(Vec<String>, u32, String, bool, String)>>,
  pub(crate) unregistered: RefCell<Vec<u32>>,
  pub(crate) next_calls: RefCell<Vec<(i64, String)>>,
  pub(crate) invoke_calls: RefCell<Vec<(i64, i32, String)>>,
  pub(crate) raw_calls: RefCell<Vec<(i64, i32, Vec<u8>)>>,
  pub(crate) takeout_calls: RefCell<Vec<(i64, i32, i32, String, String)>>,
  pub(crate) update_registered: RefCell<Vec<(u32, Vec<String>, String)>>,
  pub(crate) update_unregistered: RefCell<Vec<u32>>,
  pub(crate) intercept_update_registered: RefCell<Vec<(u32, Vec<String>)>>,
  pub(crate) intercept_update_unregistered: RefCell<Vec<u32>>,
  pub(crate) verdicts: RefCell<Vec<(i64, bool)>>,
  pub(crate) intercept_update_register_err: RefCell<Option<String>>,
  pub(crate) completes: RefCell<Vec<(i64, String)>>,
  pub(crate) register_err: RefCell<Option<String>>,
  pub(crate) update_register_err: RefCell<Option<String>>,
  pub(crate) next_err: RefCell<Option<String>>,
  pub(crate) tl_fields: RefCell<HashMap<String, String>>,
  pub(crate) tl_released: RefCell<Vec<i64>>,
}

impl RpcHost for TestHost {
  fn on_register(
    &self,
    methods: &[String],
    callback_id: u32,
    scope: &str,
    strict: bool,
    filter_json: &str,
  ) -> Option<String> {
    self.registered.borrow_mut().push((
      methods.to_vec(),
      callback_id,
      scope.to_string(),
      strict,
      filter_json.to_string(),
    ));
    self.register_err.borrow().clone()
  }
  fn on_unregister(&self, callback_id: u32) {
    self.unregistered.borrow_mut().push(callback_id);
  }
  fn on_invoke(&self, invoke_id: i64, slot: i32, request_wire: &str) -> Option<String> {
    self.invoke_calls.borrow_mut().push((invoke_id, slot, request_wire.to_string()));
    None
  }
  fn on_invoke_raw(&self, invoke_id: i64, slot: i32, method: &[u8]) -> Option<String> {
    self.raw_calls.borrow_mut().push((invoke_id, slot, method.to_vec()));
    None
  }
  fn on_takeout(&self, invoke_id: i64, slot: i32, op: i32, takeout_id: &str, arg: &str) -> Option<String> {
    self.takeout_calls.borrow_mut().push((invoke_id, slot, op, takeout_id.to_string(), arg.to_string()));
    None
  }
  fn on_next(&self, dispatch_id: i64, request_wire: &str) -> Option<String> {
    self.next_calls.borrow_mut().push((dispatch_id, request_wire.to_string()));
    self.next_err.borrow().clone()
  }
  fn on_complete(&self, dispatch_id: i64, result_wire: &str) {
    self.completes.borrow_mut().push((dispatch_id, result_wire.to_string()));
  }
  fn on_update_register(&self, callback_id: u32, types: &[String], scope: &str) -> Option<String> {
    self.update_registered.borrow_mut().push((callback_id, types.to_vec(), scope.to_string()));
    self.update_register_err.borrow().clone()
  }
  fn on_update_unregister(&self, callback_id: u32) {
    self.update_unregistered.borrow_mut().push(callback_id);
  }
  fn on_intercept_update_register(&self, callback_id: u32, types: &[String]) -> Option<String> {
    self.intercept_update_registered.borrow_mut().push((callback_id, types.to_vec()));
    self.intercept_update_register_err.borrow().clone()
  }
  fn on_intercept_update_unregister(&self, callback_id: u32) {
    self.intercept_update_unregistered.borrow_mut().push(callback_id);
  }
  fn on_update_verdict(&self, dispatch_id: i64, deliver: bool) {
    self.verdicts.borrow_mut().push((dispatch_id, deliver));
  }
}

/// the single handle [`TestHost`] answers `TlHost` upcalls for; anything else reads as expired.
/// `tl/proxy.rs` owns the exhaustive trap tests - these only cover which wires reach JS as views.
const TEST_HANDLE: i64 = 77;

impl TlHost for TestHost {
  fn tl_get(&self, handle: i64, key: &str) -> String {
    if handle != TEST_HANDLE {
      return proxy::encode_error("TestHost: no such handle");
    }
    match self.tl_fields.borrow().get(key) {
      Some(wire) => wire.clone(),
      None => proxy::encode_error(&format!("no such field '{key}'")),
    }
  }
  fn tl_set(&self, handle: i64, key: &str, value_wire: &str) -> Option<String> {
    if handle != TEST_HANDLE {
      return Some("TestHost: no such handle".to_string());
    }
    self.tl_fields.borrow_mut().insert(key.to_string(), value_wire.to_string());
    None
  }
  fn tl_set_bytes(&self, handle: i64, key: &str, value: &[u8]) -> Option<String> {
    self.tl_set(handle, key, &crate::api::tl::proxy::encode_bytes_wire(value))
  }
  fn tl_has(&self, handle: i64, key: &str) -> i32 {
    if handle != TEST_HANDLE {
      return -1;
    }
    if self.tl_fields.borrow().contains_key(key) {
      1
    } else {
      0
    }
  }
  fn tl_own_keys(&self, handle: i64) -> Option<String> {
    if handle != TEST_HANDLE {
      return None;
    }
    let mut keys: Vec<String> = self.tl_fields.borrow().keys().cloned().collect();
    keys.sort();
    Some(keys.join(","))
  }
  fn tl_copy(&self, _handle: i64) -> Option<String> {
    None
  }
  fn tl_release(&self, handle: i64) {
    self.tl_released.borrow_mut().push(handle);
  }
}

type Disposing = crate::testing::harness::DisposeOnDrop<RpcState>;

type Fixture = (Runtime, Context, Rc<TestHost>, Disposing, std::sync::Arc<crate::testing::harness::Logs>);

use crate::testing::harness::eval_unit as eval;

use crate::testing::harness::eval_json;

fn setup(grants: &[&str]) -> Fixture {
  setup_engine(grants, false, Some(TestAccountHost::with(TWO_ACCOUNTS)))
}

fn dispose_with_accounts(ctx: &Context, state: &Rc<RpcState>) {
  state.dispose(ctx);
  if let Some(accounts) = &state.accounts {
    accounts.dispose(ctx);
  }
}

/// the engine every suite here runs against, whichever fake stands in for the app. `installApi` runs
/// before `installRpc` on a device, which is where the demuxed events find `inu.Message`
fn setup_engine<H: RpcHost + TlHost + Default + 'static>(
  grants: &[&str],
  with_globals: bool,
  accounts_host: Option<Rc<TestAccountHost>>,
) -> (Runtime, Context, Rc<H>, Disposing, std::sync::Arc<crate::testing::harness::Logs>) {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let host = Rc::new(H::default());
  let tl = TlViews::new(host.clone());
  let grant_host = CachedGrantHost::new(grants);
  let logs = crate::testing::harness::Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    if with_globals {
      crate::testing::harness::install_sandbox_globals(&ctx, std::path::Path::new("")).unwrap();
    }
    let shared = crate::api::tl::utils::install_utils(&ctx, &inu).unwrap();
    crate::api::tl::message::install_message(&ctx, &shared, &inu).unwrap();
    let accounts = accounts_host.map(|accounts_host| {
      crate::api::telegram::account::install_account(
        &ctx,
        accounts_host,
        grant_host.clone(),
        Lifecycle::new(),
        log.clone(),
        &inu,
      )
      .unwrap()
    });
    install_rpc(&ctx, host.clone(), tl, grant_host.clone(), Lifecycle::new(), accounts, shared, log, &inu).unwrap()
  });
  let state = Disposing::new(&ctx, state, dispose_with_accounts);
  (rt, ctx, host, state, logs)
}

#[test]
fn a_middleware_decides_what_goes_out_and_what_settles() {
  const REQUEST: &str = r#"J{"_":"foo.bar","x":1}"#;
  const BUMPED: &str = r#"J{"_":"foo.bar","x":2}"#;
  const CATCH: &str = "({ request }, next) => next(request).catch(e => ({ _: 'foo.bar', caught: [e instanceof inu.RpcError, e instanceof inu.PluginError, e.code ?? null, e.text ?? null, e.message] }))";
  for (middleware, answer, sent, settled) in [
    ("({ request }, next) => { request.x++; return next(request); }", Some(r#"J{"_":"foo.bar","ok":true}"#), Some(BUMPED), r#"J{"_":"foo.bar","ok":true}"#),
    ("({ request }, next) => { request.x++; return next(); }", Some("N"), Some(BUMPED), "N"),
    ("({ request }, next) => { next(request); }", Some(r#"J{"_":"foo.bar","done":true}"#), Some(REQUEST), r#"J{"_":"foo.bar","done":true}"#),
    ("() => ({ _: 'foo.bar', short: true })", None, None, r#"J{"_":"foo.bar","short":true}"#),
    ("() => new inu.RpcError(403, 'FORBIDDEN')", None, None, "R403:FORBIDDEN"),
    (
      "({ request }, next) => { next(request); try { next(request) } catch (e) { return { _: 'foo.bar', again: e.message } } }",
      None,
      Some(REQUEST),
      r#"J{"_":"foo.bar","again":"next() may only be called once"}"#,
    ),
    (CATCH, Some("R400:PEER_ID_INVALID"), Some(REQUEST), r#"J{"_":"foo.bar","caught":[true,false,400,"PEER_ID_INVALID","400: PEER_ID_INVALID"]}"#),
    (CATCH, Some("Eboom"), Some(REQUEST), r#"J{"_":"foo.bar","caught":[false,false,null,null,"boom"]}"#),
    (
      CATCH,
      Some("Pforbidden\n\n\n\nblocked by policy"),
      Some(REQUEST),
      r#"J{"_":"foo.bar","caught":[false,true,"forbidden",null,"blocked by policy"]}"#,
    ),
  ] {
    let (rt, ctx, host, state, _logs) = setup(&["interceptRpc"]);
    eval(&ctx, &format!("inu.interceptRpc('foo.bar', {middleware});"));
    state.dispatch(&rt, &ctx, 1, 100, "foo.bar", 0, REQUEST);
    if let Some(answer) = answer {
      assert!(host.completes.borrow().is_empty(), "settled before next() answered: {middleware}");
      state.complete_next(&rt, &ctx, 100, answer);
    }
    let sent_wires: Vec<String> = host.next_calls.borrow().iter().map(|(_, wire)| wire.clone()).collect();
    assert_eq!(sent_wires, sent.map(str::to_string).into_iter().collect::<Vec<_>>(), "{middleware}");
    assert_eq!(host.completes.borrow().as_slice(), [(100, settled.to_string())], "{middleware}");
  }
}

#[test]
fn a_stage_settling_after_it_was_abandoned_answers_nothing_and_logs_nothing() {
  for middleware in [
    "async (_, next) => { try { await next(); } catch (e) {} return { _: 'foo.bar', late: true }; }",
    "async (_, next) => await next()",
  ] {
    let (rt, ctx, host, state, logs) = setup(&["interceptRpc"]);
    eval(&ctx, &format!("inu.interceptRpc('foo.bar', {middleware});"));
    state.dispatch(&rt, &ctx, 1, 951, "foo.bar", 0, r#"J{"_":"foo.bar"}"#);
    state.abandon_dispatch(&rt, &ctx, 951, "R-1000:INTERCEPTOR_CANCELLED");
    assert!(host.completes.borrow().is_empty(), "{middleware}");
    assert!(logs.borrow().is_empty(), "{middleware}: {:?}", logs.borrow());
  }
  // the batch moved on without this stage, so a late 'drop' must not retro-drop an applied update
  for settle in ["__settle('drop')", "__reject(new Error('gave up'))"] {
    let (rt, ctx, host, state, logs) =
      setup_engine::<TestHost>(&["interceptUpdate(updateNewMessage)"], true, Some(TestAccountHost::with(TWO_ACCOUNTS)));
    eval(
      &ctx,
      "inu.interceptUpdate('updateNewMessage', ({ signal }) => { globalThis.__signal = signal; return new Promise((resolve, reject) => { globalThis.__settle = resolve; globalThis.__reject = reject; }); });",
    );
    dispatch_intercept(&rt, &ctx, &state, &host, 5, "updateNewMessage", NEW_MESSAGE);
    assert!(host.verdicts.borrow().is_empty());
    state.abandon_update_dispatch(&rt, &ctx, 5, "R-1000:INTERCEPTOR_TIMEOUT");
    assert_eq!(eval_json(&ctx, "[__signal.aborted, __signal.reason.code]"), r#"[true,"timed-out"]"#);
    eval(&ctx, settle);
    pump_jobs(&rt, &ctx, &|_| {});
    assert!(host.verdicts.borrow().is_empty(), "{settle}");
    assert!(logs.borrow().is_empty(), "{settle}: {:?}", logs.borrow());
  }
}

/// the signal is minted lazily, so one first read after the abandon must already be aborted
#[test]
fn next_after_abandon_throws_by_the_reason_it_was_abandoned_for() {
  for (reason, code, thrown) in [
    (
      "R-1000:INTERCEPTOR_TIMEOUT",
      "timed-out",
      r#"[true,"timed-out",null,"next(): the interceptor chain's budget expired and this stage was abandoned"]"#,
    ),
    (
      "R-1000:INTERCEPTOR_ABANDONED",
      "aborted",
      r#"[true,"aborted",null,"next(): the interceptor chain was torn down and this stage was abandoned"]"#,
    ),
  ] {
    let (rt, ctx, _host, state, _logs) =
      setup_engine::<TestHost>(&["interceptRpc"], true, Some(TestAccountHost::with(TWO_ACCOUNTS)));
    eval(
      &ctx,
      "inu.interceptRpc('foo.bar', (context, next) => { globalThis.__context = context; globalThis.__next = next; return new Promise(() => {}); });",
    );

    state.dispatch(&rt, &ctx, 1, 952, "foo.bar", 0, r#"J{"_":"foo.bar"}"#);
    state.abandon_dispatch(&rt, &ctx, 952, reason);

    assert_eq!(
      eval_json(
        &ctx,
        "[__context.signal.aborted, __context.signal.reason.code, __context.signal === __context.signal]"
      ),
      format!(r#"[true,"{code}",true]"#),
    );
    assert_eq!(catch_json(&ctx, "globalThis.__next({ _: 'foo.bar' })"), thrown);
  }
}

#[test]
fn a_stage_that_already_settled_neither_aborts_nor_forwards() {
  let (rt, ctx, host, state, _logs) =
    setup_engine::<TestHost>(&["interceptRpc"], true, Some(TestAccountHost::with(TWO_ACCOUNTS)));
  eval(
    &ctx,
    "inu.interceptRpc('foo.bar', ({ signal }, next) => { globalThis.__signal = signal; globalThis.__next = next; return { _: 'foo.bar' }; });",
  );

  state.dispatch(&rt, &ctx, 1, 962, "foo.bar", 0, r#"J{"_":"foo.bar"}"#);
  assert_eq!(host.completes.borrow().len(), 1);
  state.abandon_dispatch(&rt, &ctx, 962, "R-1000:INTERCEPTOR_ABANDONED");

  assert_eq!(eval_json(&ctx, "__signal.aborted"), "false");
  assert_eq!(
    catch_json(&ctx, "globalThis.__next({ _: 'foo.bar' })"),
    r#"[true,"invalid-argument",null,"next(): this dispatch already settled"]"#,
  );
  assert!(host.next_calls.borrow().is_empty());
}

#[test]
fn abandoning_a_stage_aborts_its_signal_before_rejecting_next() {
  let (rt, ctx, _host, state, _logs) =
    setup_engine::<TestHost>(&["interceptRpc"], true, Some(TestAccountHost::with(TWO_ACCOUNTS)));
  eval(
    &ctx,
    r#"
      globalThis.__order = [];
      inu.interceptRpc('foo.bar', async ({ signal }, next) => {
        signal.addEventListener('abort', () => {
          __order.push(['abort', signal.reason instanceof inu.PluginError, signal.reason.code, signal.reason.message]);
        });
        try { return await next(); } catch (e) { __order.push(['next', e instanceof inu.RpcError, e.code, e.text]); throw e; }
      });
    "#,
  );

  state.dispatch(&rt, &ctx, 1, 960, "foo.bar", 0, r#"J{"_":"foo.bar"}"#);
  state.abandon_dispatch(&rt, &ctx, 960, "R-1000:INTERCEPTOR_CANCELLED");

  assert_eq!(
    eval_json(&ctx, "__order"),
    r#"[["abort",true,"aborted","the app cancelled the request and this stage was abandoned"],["next",true,-1000,"INTERCEPTOR_CANCELLED"]]"#,
  );
}

#[test]
fn an_invoke_settles_by_the_wire_the_host_answers_with() {
  let (rt, ctx, host, state, _logs) = setup(&["invokeRpc"]);
  eval(
    &ctx,
    r#"
      globalThis.__real = inu.RpcError;
      inu.RpcError = class Impostor extends Error {};
      globalThis.__seen = [];
      for (let i = 0; i < 4; i++) {
        inu.invokeRpc({ _: 'foo.bar' }).then(r => { __seen[i] = r }, e => {
          __seen[i] = [e instanceof __real, e instanceof inu.PluginError, e.code ?? null, e.text ?? null, e.usage ?? null, e.quota ?? null, e.message];
        });
      }
    "#,
  );
  let ids: Vec<i64> = host.invoke_calls.borrow().iter().map(|(id, _, _)| *id).collect();
  let wires = [
    r#"J{"_":"foo.bar","ok":true}"#,
    "EBAD_REQUEST: oops",
    "R400:PEER_ID_INVALID",
    "Pquota-exceeded\n\n64\n32\ntoo big",
  ];
  for (id, wire) in ids.into_iter().zip(wires) {
    state.settle(&rt, &ctx, id, wire);
  }
  assert_eq!(
    eval_json(&ctx, "__seen"),
    r#"[{"_":"foo.bar","ok":true},[false,false,null,null,null,null,"BAD_REQUEST: oops"],[true,false,400,"PEER_ID_INVALID",null,null,"400: PEER_ID_INVALID"],[false,true,"quota-exceeded",null,64,32,"too big"]]"#,
  );
}

#[test]
fn a_refused_registration_or_call_never_reaches_the_host() {
  for (grants, code, thrown) in [
    (
      "interceptRpc(messages.sendMessage)",
      "inu.interceptRpc(['messages.sendMessage', 'users.getUsers'], () => {})",
      r#"[true,"not-granted","interceptRpc(users.getUsers)","missing grant: interceptRpc(users.getUsers)"]"#,
    ),
    (
      "interceptRpc(users.getUsers)",
      "inu.interceptRpc('users.getUsers', () => {}, true)",
      r#"[false,null,null,"interceptRpc: options must be an object"]"#,
    ),
    (
      "interceptRpc(users.getUsers)",
      "inu.interceptRpc('users.getUsers', () => {}, { strict: 'yes' })",
      r#"[false,null,null,"interceptRpc: 'strict' must be a boolean"]"#,
    ),
    (
      "invokeRpc",
      "inu.invokeRpc({})",
      r#"[true,"invalid-argument",null,"invokeRpc: the request must carry its method name in '_'"]"#,
    ),
    (
      "invokeRpc",
      "inu.invokeRpc({ _: 42 })",
      r#"[true,"invalid-argument",null,"invokeRpc: the request must carry its method name in '_'"]"#,
    ),
    (
      "invokeRpc(users.getUsers)",
      "inu.invokeRpc({ _: 'messages.sendMessage' })",
      r#"[true,"not-granted","invokeRpc(messages.sendMessage)","missing grant: invokeRpc(messages.sendMessage)"]"#,
    ),
    (
      "onUpdate(updateNewMessage)",
      "inu.onUpdate(['updateNewMessage', 'updateUserTyping'], () => {})",
      r#"[true,"not-granted","onUpdate(updateUserTyping)","missing grant: onUpdate(updateUserTyping)"]"#,
    ),
    (
      "onUpdate",
      "inu.onUpdate([], () => {})",
      r#"[false,null,null,"onUpdate: type list must not be empty"]"#,
    ),
    (
      "onUpdate",
      "inu.onUpdate([1], () => {})",
      r#"[false,null,null,"onUpdate: type list must contain only strings"]"#,
    ),
    // the two halves of the scope vocabulary do not imply each other, in either direction
    (
      "onUpdate(updateNewMessage)",
      "inu.onNewMessage(() => {})",
      r#"[true,"not-granted","onUpdate(new_message)","missing grant: onUpdate(new_message)"]"#,
    ),
    (
      "onUpdate(new_message)",
      "inu.onUpdate('updateNewMessage', () => {})",
      r#"[true,"not-granted","onUpdate(updateNewMessage)","missing grant: onUpdate(updateNewMessage)"]"#,
    ),
    (
      "interceptRpc(messages.sendMessage)",
      "inu.interceptSendMessage(() => 'send')",
      r#"[true,"not-granted","interceptSendMessage","missing grant: interceptSendMessage"]"#,
    ),
    (
      "interceptUpdate(updateEditMessage)",
      "inu.interceptUpdate('updateNewMessage', () => 'deliver')",
      r#"[true,"not-granted","interceptUpdate(updateNewMessage)","missing grant: interceptUpdate(updateNewMessage)"]"#,
    ),
    (
      "interceptUpdate(updateEditMessage)",
      "inu.interceptUpdate([], () => 'deliver')",
      r#"[false,null,null,"interceptUpdate: type list must not be empty"]"#,
    ),
    (
      "invokeRpc",
      "inu.invokeRaw(new Uint8Array([1, 2, 3, 4]))",
      r#"[true,"not-granted","unsafe.invokeRaw","missing grant: unsafe.invokeRaw"]"#,
    ),
    (
      "unsafe.invokeRaw",
      "inu.invokeRaw({ _: 'foo.bar' })",
      r#"[true,"invalid-argument",null,"invokeRaw: expected the serialized method as a Uint8Array"]"#,
    ),
    (
      "invokeRpc account.read(self)",
      "inu.account(1).initTakeoutSession()",
      r#"[true,"not-granted","takeout","missing grant: takeout"]"#,
    ),
  ] {
    let (_rt, ctx, host, state, _logs) = setup(&grants.split(' ').collect::<Vec<_>>());
    assert_eq!(catch_json(&ctx, code), thrown, "{code}");
    let reached = !host.registered.borrow().is_empty()
      || !host.update_registered.borrow().is_empty()
      || !host.intercept_update_registered.borrow().is_empty()
      || !host.invoke_calls.borrow().is_empty()
      || !host.raw_calls.borrow().is_empty()
      || !host.takeout_calls.borrow().is_empty();
    assert!(!reached, "{code} reached the host");
    assert!(state.intercept_fns.is_empty() && state.update_fns.is_empty(), "{code}");
  }
}

#[test]
fn a_handler_is_handed_the_account_its_update_or_request_arrived_on() {
  let (rt, ctx, host, state, _logs) = setup(&["onUpdate", "interceptRpc", "account.read(self)"]);
  eval(
    &ctx,
    r#"
      globalThis.__seen = [];
      globalThis.__record = (account) => __seen.push([account.id, account.userId, account.isCurrent()]);
      inu.onUpdate('updateNewMessage', (update, account) => __record(account));
      inu.interceptRpc('foo.bar', ({ request, account }, next) => { __record(account); return next(request); });
    "#,
  );
  state.dispatch_update(&rt, &ctx, UPDATE_TYPE, 1, UPDATE_WIRE);
  state.dispatch_update(&rt, &ctx, UPDATE_TYPE, 0, UPDATE_WIRE);
  let callback_id = host.registered.borrow()[0].1;
  state.dispatch(&rt, &ctx, callback_id, 990, "foo.bar", 1, r#"J{"_":"foo.bar"}"#);
  assert_eq!(eval_json(&ctx, "__seen"), "[[1,222,false],[0,111,true],[1,222,false]]");
  assert_eq!(host.next_calls.borrow().len(), 1);
}

fn seed_tl_object(host: &Rc<TestHost>, type_name: &str) {
  let mut fields = host.tl_fields.borrow_mut();
  fields.insert("_".to_string(), format!("S{type_name}"));
  fields.insert("x".to_string(), "I1".to_string());
}

#[test]
fn invoke_result_handle_resolves_to_a_writable_plugin_lifetime_view() {
  let (rt, ctx, host, state, _logs) = setup(&["invokeRpc"]);
  seed_tl_object(&host, "foo.bar");
  eval(&ctx, r#"inu.invokeRpc({_:'foo.bar'}).then(r => { globalThis.__got = r; });"#);

  let invoke_id = host.invoke_calls.borrow()[0].0;
  state.settle(&rt, &ctx, invoke_id, &format!("HOW{TEST_HANDLE}"));

  ctx.with(|ctx| {
    assert_eq!(ctx.eval::<String, _>("globalThis.__got._").unwrap(), "foo.bar");
    ctx.eval::<(), _>("globalThis.__got.x = 5").unwrap();
    assert_eq!(ctx.eval::<i64, _>("globalThis.__got.x").unwrap(), 5);
    ctx.eval::<(), _>("globalThis.__got = undefined").unwrap();
  });

  assert_eq!(host.tl_fields.borrow().get("x").unwrap(), "J5");
  assert_eq!(*host.tl_released.borrow(), vec![TEST_HANDLE]);
}

#[test]
fn update_payload_handle_resolves_to_a_read_only_view() {
  let (rt, ctx, host, state, _logs) = setup(&["onUpdate"]);
  seed_tl_object(&host, "updateFoo");
  state.dispatch_update(&rt, &ctx, "updateFoo", 0, &format!("HOR{TEST_HANDLE}"));
  assert_eq!(
    *host.tl_released.borrow(),
    vec![TEST_HANDLE],
    "an update nobody listens to still releases its handle"
  );

  eval(&ctx, "inu.onUpdate('updateFoo', (u) => { globalThis.__seen = u; });");
  state.dispatch_update(&rt, &ctx, "updateFoo", 0, &format!("HOR{TEST_HANDLE}"));

  ctx.with(|ctx| {
    assert_eq!(ctx.eval::<String, _>("globalThis.__seen._").unwrap(), "updateFoo");
    let caught: String = ctx
      .eval(
        r#"
          (() => {
            try { globalThis.__seen.x = 5; return 'no-throw' }
            catch (e) { return [e instanceof inu.PluginError, e.code].join('|') }
          })()
        "#,
      )
      .unwrap();
    assert_eq!(caught, "true|forbidden");
  });

  assert_eq!(host.tl_fields.borrow().get("x").unwrap(), "I1");
}

use crate::testing::harness::catch_json;

#[test]
fn an_intercept_registration_reaches_the_host_and_drops_its_callback_when_refused() {
  let (_rt, ctx, host, state, _logs) = setup(&["interceptRpc(users.getUsers)"]);
  eval(
    &ctx,
    "inu.interceptRpc('users.getUsers', () => {}); inu.interceptRpc('users.getUsers', () => {}, { strict: true });",
  );
  assert_eq!(host.registered.borrow()[0].0, vec!["users.getUsers".to_string()]);
  assert!(!host.registered.borrow()[0].3);
  assert!(host.registered.borrow()[1].3);

  *host.register_err.borrow_mut() = Some("not granted".to_string());
  assert!(ctx.with(|ctx| ctx.eval::<(), _>("inu.interceptRpc('users.getUsers', () => {})").is_err()));
  assert_eq!(state.intercept_fns.len(), 2, "a registration the host refused keeps its callback");
}

#[test]
fn an_update_only_reaches_the_registrations_that_named_its_type() {
  let (rt, ctx, host, state, _logs) = setup(&["onUpdate"]);
  eval(
    &ctx,
    r#"
      globalThis.__ran = [];
      inu.onUpdate('updateNewMessage', () => { __ran.push('new'); });
      inu.onUpdate(['updateUserTyping', 'updateNewMessage'], (u) => { __ran.push(`both:${u._}`); });
      inu.onUpdate('updateUserTyping', () => { __ran.push('typing'); });
    "#,
  );
  assert_eq!(
    host.update_registered.borrow()[1].1,
    vec!["updateUserTyping".to_string(), "updateNewMessage".to_string()],
    "the host is told the list so it can filter before minting a handle",
  );

  state.dispatch_update(&rt, &ctx, UPDATE_TYPE, 0, UPDATE_WIRE);
  assert_eq!(eval_json(&ctx, "__ran"), r#"["new","both:updateNewMessage"]"#);

  state.dispatch_update(&rt, &ctx, "updateUserTyping", 0, "J{\"_\":\"updateUserTyping\"}");
  assert_eq!(eval_json(&ctx, "__ran"), r#"["new","both:updateNewMessage","both:updateUserTyping","typing"]"#,);

  assert!(state.update_fns.len() == 3);
}

/// the account api is installed before the rpc one, but nothing forces it to have succeeded -
/// and a dispatch that cannot name its account still has to run rather than fail the app's call
#[test]
fn without_the_account_api_a_dispatch_hands_over_undefined() {
  let (rt, ctx, host, state, _logs) = setup_engine::<TestHost>(&["onUpdate", "interceptRpc"], false, None);

  eval(
    &ctx,
    r#"
      globalThis.__seen = [];
      inu.onUpdate('updateNewMessage', (u, account) => { __seen.push(typeof account); });
      inu.interceptRpc('foo.bar', ({ request: req, account }, next) => { __seen.push(typeof account); return next(req); });
    "#,
  );
  state.dispatch_update(&rt, &ctx, UPDATE_TYPE, 0, UPDATE_WIRE);
  state.dispatch(&rt, &ctx, 1, 991, "foo.bar", 0, r#"J{"_":"foo.bar"}"#);
  assert_eq!(eval_json(&ctx, "__seen"), r#"["undefined","undefined"]"#);
  assert_eq!(host.next_calls.borrow().len(), 1);
}

/// classifies a captured diagnostic exactly as the bridge's sink does, so a test reads the
/// level the host would act on rather than the marker that carried it there
fn levels(logs: &std::sync::Arc<crate::testing::harness::Logs>) -> Vec<(i32, String)> {
  logs
    .borrow()
    .iter()
    .map(|line| {
      let (level, message) = crate::classify_log(line);
      (level, message.to_string())
    })
    .collect()
}

#[test]
fn a_plugin_throw_faults_where_a_host_failure_does_not() {
  let (rt, ctx, host, state, logs) = setup(&["interceptRpc", "onUpdate"]);
  install_rejection_tracker(&rt, &ctx, crate::testing::harness::log_sink(&logs)).unwrap();
  eval(
    &ctx,
    r#"
      inu.interceptRpc('foo.bar', () => { throw new Error('kaboom'); });
      inu.interceptRpc('foo.baz', async () => { throw new Error('rejected'); });
      inu.onUpdate('updateFoo', () => { throw new Error('boom'); });
      inu.onUpdate('updateFoo', () => { globalThis.__survived = true; });
      inu.onUpdate('updateBar', async () => { throw new Error('unawaited'); });
    "#,
  );
  let ids: Vec<u32> = host.registered.borrow().iter().map(|(_, id, _, _, _)| *id).collect();

  state.dispatch(&rt, &ctx, ids[0], 700, "foo.bar", 0, r#"J{"_":"foo.bar"}"#);
  state.dispatch(&rt, &ctx, ids[1], 701, "foo.baz", 0, r#"J{"_":"foo.baz"}"#);
  state.dispatch_update(&rt, &ctx, "updateFoo", 0, r#"J{"_":"updateFoo"}"#);
  state.dispatch_update(&rt, &ctx, "updateBar", 0, r#"J{"_":"updateBar"}"#);

  let completes = host.completes.borrow().clone();
  assert_eq!(completes.iter().map(|(id, _)| *id).collect::<Vec<_>>(), vec![700, 701]);
  assert!(completes[0].1.starts_with('E') && completes[0].1.contains("kaboom"), "{completes:?}");
  assert!(completes[1].1.starts_with('E') && completes[1].1.contains("rejected"), "{completes:?}");
  assert_eq!(eval_json(&ctx, "__survived"), "true", "a throwing handler stopped the fan-out");

  let seen = levels(&logs);
  let wanted = [
    "interceptRpc(foo.bar) callback threw",
    "interceptRpc(foo.baz) callback rejected",
    "onUpdate callback threw",
    "unhandled promise rejection",
  ];
  for want in wanted {
    let found = seen.iter().find(|(_, message)| message.contains(want));
    let Some((level, message)) = found else {
      panic!("no diagnostic for '{want}', got: {seen:?}");
    };
    assert_eq!(*level, crate::LEVEL_FAULT, "'{message}' must disable the plugin");
  }
  assert_eq!(seen.len(), wanted.len(), "got: {seen:?}");

  // a wire the bridge cannot decode is the engine's problem: the plugin never ran
  logs.borrow_mut().clear();
  state.dispatch_update(&rt, &ctx, "updateFoo", 0, "Qnope");
  assert_eq!(levels(&logs).first().map(|(level, _)| *level), Some(crate::LEVEL_ERROR), "got: {:?}", levels(&logs),);
}

#[test]
fn an_rpc_error_out_of_a_stage_is_control_flow_not_a_fault() {
  let (rt, ctx, host, state, logs) = setup(&["interceptRpc"]);
  eval(&ctx, "inu.interceptRpc('foo.bar', () => { throw new inu.RpcError(420, 'FLOOD_WAIT_3'); });");

  state.dispatch(&rt, &ctx, 1, 800, "foo.bar", 0, r#"J{"_":"foo.bar"}"#);
  assert_eq!(host.completes.borrow().as_slice(), [(800, "R420:FLOOD_WAIT_3".to_string())]);

  for (level, message) in levels(&logs) {
    assert_ne!(level, crate::LEVEL_FAULT, "'{message}' must not disable the plugin",);
  }
}

/// the wedge the execution deadline exists for: the app is blocked on this dispatch, so the
/// spinning stage has to be cut down *and* the request answered
#[test]
fn a_spinning_middleware_is_interrupted_and_the_request_is_still_answered() {
  let (rt, ctx, host, state, logs) = setup(&["interceptRpc"]);
  crate::sandbox::limits::install_interrupt_handler(&rt, std::sync::Arc::new(|_: &str| {}));
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>("inu.interceptRpc('foo.bar', ({ request: req }, next) => { while (true) {} });")
      .unwrap();
  });

  {
    let _deadline = crate::sandbox::limits::arm(50);
    state.dispatch(&rt, &ctx, 1, 980, "foo.bar", 0, r#"J{"_":"foo.bar"}"#);
  }

  let completes = host.completes.borrow();
  assert_eq!(completes.len(), 1);
  assert_eq!(completes[0].0, 980);
  assert!(completes[0].1.starts_with('E'), "got: {}", completes[0].1);
  assert!(completes[0].1.contains("interrupted"), "got: {}", completes[0].1);
  drop(completes);
  assert!(
    logs.borrow().iter().any(|l| l.contains("interceptRpc(foo.bar) callback threw")),
    "expected a logged diagnostic, got: {:?}",
    logs.borrow(),
  );
}

/// a failing assertion must cost one failed test, not the whole binary: without disposal on the
/// unwind path the engine's still-rooted `Persistent`s reach `JS_FreeRuntime` and abort
#[test]
fn a_panicking_test_body_still_releases_its_roots() {
  let (rt, ctx, _host, state, _logs) = setup(&["interceptRpc", "invokeRpc"]);
  let engine = Rc::clone(&state);
  eval(
    &ctx,
    r#"
      inu.interceptRpc('foo.bar', ({ request: req }, next) => new Promise(() => {}));
      inu.invokeRpc({ _: 'foo.baz' });
    "#,
  );
  state.dispatch(&rt, &ctx, 1, 970, "foo.bar", 0, r#"J{"_":"foo.bar"}"#);
  assert_eq!(engine.intercept_fns.len(), 1);
  assert_eq!(engine.invokes.len(), 1);
  assert_eq!(engine.dispatches.borrow().len(), 1);

  let panicked = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
    let _state = state;
    panic!("deliberate: stands in for a failed assertion");
  }));

  assert!(panicked.is_err());
  assert!(engine.intercept_fns.is_empty(), "disposal must run on the unwind path");
  assert!(engine.invokes.is_empty());
  assert!(engine.dispatches.borrow().is_empty());
}

const UPDATE_TYPE: &str = "updateNewMessage";
const UPDATE_WIRE: &str = "J{\"_\":\"updateNewMessage\"}";

#[test]
fn unkeyed_on_update_registrations_stack_and_dispose_individually() {
  let (rt, ctx, host, state, _logs) = setup(&["onUpdate"]);
  eval(
    &ctx,
    r#"
      globalThis.__ran = [];
      globalThis.__d1 = inu.onUpdate('updateNewMessage', () => { __ran.push(1); });
      globalThis.__d2 = inu.onUpdate('updateNewMessage', () => { __ran.push(2); });
    "#,
  );
  assert_eq!(state.update_fns.len(), 2, "unkeyed registrations stack");

  state.dispatch_update(&rt, &ctx, UPDATE_TYPE, 0, UPDATE_WIRE);
  assert_eq!(eval_json(&ctx, "__ran"), "[1,2]");

  let ids: Vec<u32> = host.update_registered.borrow().iter().map(|(id, _, _)| *id).collect();
  eval(&ctx, "__d1(); __d1();");
  assert_eq!(
    *host.update_unregistered.borrow(),
    vec![ids[0]],
    "a disposer called twice reaches the host once, naming its own registration",
  );

  state.dispatch_update(&rt, &ctx, UPDATE_TYPE, 0, UPDATE_WIRE);
  assert_eq!(eval_json(&ctx, "__ran"), "[1,2,2]");

  eval(&ctx, "__d2(); __d2();");
  assert_eq!(*host.update_unregistered.borrow(), vec![ids[0], ids[1]]);
}

#[test]
fn a_dispatch_walks_the_handlers_it_started_with() {
  let (rt, ctx, host, state, _logs) = setup(&["onUpdate"]);
  eval(
    &ctx,
    r#"
      globalThis.__ran = [];
      globalThis.__first = inu.onUpdate('updateNewMessage', () => {
        __first();
        __first();
        __second();
        inu.onUpdate('updateNewMessage', () => { __ran.push('added'); });
        __ran.push('first');
      });
      globalThis.__second = inu.onUpdate('updateNewMessage', () => { __ran.push('second'); });
    "#,
  );

  state.dispatch_update(&rt, &ctx, UPDATE_TYPE, 0, UPDATE_WIRE);
  assert_eq!(eval_json(&ctx, "__ran"), r#"["first","second"]"#);
  assert_eq!(host.update_unregistered.borrow().len(), 2);
  assert_eq!(state.update_fns.len(), 1);

  state.dispatch_update(&rt, &ctx, UPDATE_TYPE, 0, UPDATE_WIRE);
  assert_eq!(eval_json(&ctx, "__ran"), r#"["first","second","added"]"#);
}

#[test]
fn registering_after_unload_began_is_a_no_op_returning_a_no_op_disposer() {
  let (rt, ctx, host, state, _logs) =
    setup(&["onUpdate", "interceptRpc", "interceptUpdate(updateNewMessage)", "interceptSendMessage"]);
  state.lifecycle.begin_unload();
  eval(
    &ctx,
    r#"
      globalThis.__ran = [];
      globalThis.__shapes = [
        typeof inu.onUpdate('updateNewMessage', () => { __ran.push('update'); }),
        typeof inu.onNewMessage(() => { __ran.push('new'); }),
        typeof inu.interceptRpc('foo.bar', ({ request: req }, next) => next(req)),
        typeof inu.interceptUpdate('updateNewMessage', () => 'drop'),
        typeof inu.interceptSendMessage(() => 'drop'),
      ];
      inu.onUpdate('updateNewMessage', () => {})();
    "#,
  );
  assert_eq!(eval_json(&ctx, "__shapes"), r#"["function","function","function","function","function"]"#);
  assert!(host.registered.borrow().is_empty(), "the host must not hear about it either");
  assert!(host.update_registered.borrow().is_empty());
  assert!(host.intercept_update_registered.borrow().is_empty());
  assert!(state.update_fns.is_empty());
  assert!(state.intercept_fns.is_empty());

  state.dispatch_update(&rt, &ctx, UPDATE_TYPE, 0, UPDATE_WIRE);
  assert_eq!(eval_json(&ctx, "__ran"), "[]");
}

const NEW_MESSAGE: &str = r#"J{"_":"updateNewMessage","message":{"_":"message","id":42,"message":"hi","peer_id":{"_":"peerUser","user_id":"7"}},"pts":9}"#;

/// what the host is told a demuxed registration listens for, and what it gates it on. The scope
/// is the whole reason the upcall carries one: `onUpdate(new_message)` is not
/// `onUpdate(updateNewMessage)`, and only the host knows which the plugin declared.
#[test]
fn a_demuxed_registration_names_its_own_constructors_and_its_own_grant_scope() {
  let (_rt, ctx, host, state, _logs) = setup(&["onUpdate(new_message,edit_message,delete_message)"]);
  eval(
    &ctx,
    r#"
      inu.onNewMessage(() => {});
      inu.onMessageEdited(() => {});
      inu.onMessageDeleted(() => {});
    "#,
  );

  let registered = host.update_registered.borrow();
  let seen: Vec<(Vec<String>, String)> =
    registered.iter().map(|(_, types, scope)| (types.clone(), scope.clone())).collect();
  assert_eq!(
    seen,
    vec![
      (
        vec!["updateNewMessage".to_string(), "updateNewChannelMessage".to_string()],
        "new_message".to_string()
      ),
      (
        vec!["updateEditMessage".to_string(), "updateEditChannelMessage".to_string()],
        "edit_message".to_string()
      ),
      (
        vec!["updateDeleteMessages".to_string(), "updateDeleteChannelMessages".to_string()],
        "delete_message".to_string()
      ),
    ],
  );
  assert_eq!(state.update_fns.len(), 3);
}

#[test]
fn a_new_message_arrives_as_a_message_wrapper_over_the_update_s_own_message() {
  let (rt, ctx, _host, state, _logs) = setup(&["onUpdate(new_message)"]);
  eval(
    &ctx,
    r#"
      globalThis.__real = inu.Message;
      inu.Message = class Impostor { constructor() { this.id = -1 } };
      globalThis.__seen = [];
      inu.onNewMessage((m, account) => {
        __seen.push([m instanceof __real, m.id, m.text, m.dialogId, account.id, m.raw._]);
      });
    "#,
  );

  state.dispatch_update(&rt, &ctx, "updateNewMessage", 1, NEW_MESSAGE);

  assert_eq!(eval_json(&ctx, "__seen"), r#"[[true,42,"hi",7,1,"message"]]"#);
}

/// the oracle is the only test the js surface gets on a device; everything it asserts is a
/// function of the updates handed to it, so it runs here against synthesised ones
#[test]
fn the_bundled_events_test_plugin_passes() {
  const ORACLE: &str = crate::testing::test_plugin!("events-test.js");
  let (rt, ctx, _host, state, _logs) = setup(&crate::testing::harness::manifest_grants(ORACLE));
  let lines = crate::testing::harness::install_capturing_console(&ctx);
  eval(&ctx, ORACLE);

  for (type_name, wire) in [
    ("updateNewMessage", NEW_MESSAGE),
    (
      "updateNewChannelMessage",
      r#"J{"_":"updateNewChannelMessage","message":{"_":"message","id":43,"message":"in a channel","peer_id":{"_":"peerChannel","channel_id":"99"}}}"#,
    ),
    (
      "updateEditMessage",
      r#"J{"_":"updateEditMessage","message":{"_":"message","id":42,"message":"hi (edited)","edit_date":1715540700,"peer_id":{"_":"peerUser","user_id":"7"}}}"#,
    ),
    (
      "updateDeleteChannelMessages",
      r#"J{"_":"updateDeleteChannelMessages","channel_id":"99","messages":[43]}"#,
    ),
    ("updateDeleteMessages", r#"J{"_":"updateDeleteMessages","messages":[42]}"#),
  ] {
    state.dispatch_update(&rt, &ctx, type_name, 0, wire);
  }

  let lines = lines.borrow();
  // "done" is printed by the oracle itself, once each of the three events has been
  // cross-checked against the raw stream - a marker printed at the end of the file would be
  // printed before a single update had arrived
  crate::testing::harness::assert_oracle_exact(&lines, "events test done", 10);
}

/// the account oracle's reactive half is the half a device only reaches by switching accounts,
/// and the update it ends on only arrives if someone's status changes. Both are synthesised
/// here, so the count covers the whole file rather than its load-time third.
#[test]
fn the_bundled_accounts_test_plugin_passes() {
  const ORACLE: &str = crate::testing::test_plugin!("accounts-test.js");
  const SWITCHED: &str = r#"[{"id":0,"userId":111,"isCurrent":false,"isPremium":false},{"id":1,"userId":222,"isCurrent":true,"isPremium":true}]"#;

  let accounts_host = TestAccountHost::with(TWO_ACCOUNTS);
  let (rt, ctx, _host, state, _logs) =
    setup_engine::<TestHost>(&crate::testing::harness::manifest_grants(ORACLE), false, Some(accounts_host.clone()));
  let accounts = state.accounts.clone().unwrap();

  let lines = crate::testing::harness::install_capturing_console(&ctx);
  eval(&ctx, ORACLE);

  *accounts_host.json.borrow_mut() = SWITCHED.to_string();
  accounts.accounts_changed(&rt, &ctx);
  state.dispatch_update(
    &rt,
    &ctx,
    "updateUserStatus",
    1,
    r#"J{"_":"updateUserStatus","user_id":"111","status":{"_":"userStatusOnline"}}"#,
  );

  let lines = lines.borrow().clone();
  crate::testing::harness::assert_oracle_exact(&lines, "accounts test done", 16);
}

#[test]
fn intercept_disposer_unregisters_once_and_a_later_dispatch_passes_through() {
  let (rt, ctx, host, state, logs) = setup(&["interceptRpc"]);
  eval(
    &ctx,
    r#"
      globalThis.__ran = 0;
      globalThis.__d = inu.interceptRpc('foo.bar', ({ request: req }, next) => { __ran++; return next(req); });
    "#,
  );
  let callback_id = host.registered.borrow()[0].1;

  eval(&ctx, "__d(); __d();");
  assert_eq!(*host.unregistered.borrow(), vec![callback_id]);
  assert!(state.intercept_fns.is_empty());

  // the host picked its chain before it saw the disposal: the stage goes transparent rather
  // than failing the app's request
  state.dispatch(&rt, &ctx, callback_id, 980, "foo.bar", 0, r#"J{"_":"foo.bar"}"#);
  assert_eq!(eval_json(&ctx, "__ran"), "0");
  assert_eq!(host.next_calls.borrow().len(), 1);
  assert!(host.completes.borrow().is_empty());

  state.complete_next(&rt, &ctx, 980, r#"J{"_":"foo.bar","ok":true}"#);
  assert_eq!(host.completes.borrow().as_slice(), [(980, r#"J{"_":"foo.bar","ok":true}"#.to_string())]);
  assert!(
    logs.borrow().iter().any(|l| l.contains("disposed interceptor")),
    "expected a logged diagnostic, got: {:?}",
    logs.borrow(),
  );

  // a structured wire the host refuses the passthrough with is not re-tagged
  *host.next_err.borrow_mut() = Some("R420:FLOOD_WAIT_5".to_string());
  state.dispatch(&rt, &ctx, callback_id, 981, "foo.bar", 0, r#"J{"_":"foo.bar"}"#);
  assert_eq!(host.completes.borrow().last(), Some(&(981, "R420:FLOOD_WAIT_5".to_string())));
}

#[test]
fn an_interceptor_disposing_itself_mid_dispatch_still_finishes_that_dispatch() {
  let (rt, ctx, host, state, _logs) = setup(&["interceptRpc"]);
  eval(
    &ctx,
    r#"
      globalThis.__d = inu.interceptRpc('foo.bar', async ({ request: req }, next) => {
        globalThis.__d();
        const r = await next(req);
        return { _: 'foo.bar', finished: true };
      });
    "#,
  );

  state.dispatch(&rt, &ctx, 1, 982, "foo.bar", 0, r#"J{"_":"foo.bar"}"#);
  assert_eq!(host.next_calls.borrow().len(), 1, "the in-flight run keeps going after its own disposal");
  assert!(state.intercept_fns.is_empty());

  state.complete_next(&rt, &ctx, 982, r#"J{"_":"foo.bar"}"#);
  let completes = host.completes.borrow();
  assert_eq!(completes.len(), 1);
  assert_eq!(completes[0], (982, r#"J{"_":"foo.bar","finished":true}"#.to_string()));
}

const SEND_TEXT: &str = r#"{"_":"messages.sendMessage","peer":{"_":"inputPeerUser","user_id":"7","access_hash":"3"},"message":"hi","random_id":"1"}"#;
const SEND_MEDIA: &str = r#"{"_":"messages.sendMedia","peer":{"_":"inputPeerChannel","channel_id":"9","access_hash":"3"},"message":"cap","media":{"_":"inputMediaEmpty"},"random_id":"1","silent":true}"#;
const SEND_ALBUM: &str = r#"{"_":"messages.sendMultiMedia","peer":{"_":"inputPeerChat","chat_id":"5"},"multi_media":[{"_":"inputSingleMedia","media":{"_":"inputMediaEmpty"},"message":"one","random_id":"1"},{"_":"inputSingleMedia","media":{"_":"inputMediaEmpty"},"message":"two","random_id":"2"}]}"#;
const EDIT: &str = r#"{"_":"messages.editMessage","peer":{"_":"inputPeerUser","user_id":"7","access_hash":"3"},"id":42,"message":"fixed","media":{"_":"inputMediaEmpty"}}"#;

/// the one live registration. Chaining is the host's job, so these tests dispatch a single
/// stage; a fixture that registered twice would silently exercise only one of them.
fn send_callback_id(host: &Rc<TestHost>) -> u32 {
  let registered = host.registered.borrow();
  let disposed = host.unregistered.borrow();
  let live: Vec<_> = registered.iter().filter(|e| !disposed.contains(&e.1)).collect();
  assert_eq!(live.len(), 1, "expected exactly one live interceptSendMessage registration");
  let entry = live[0];
  assert_eq!(entry.2, "interceptSendMessage", "the api's own grant, not the four methods'");
  assert_eq!(
    entry.0,
    vec![
      "messages.sendMessage".to_string(),
      "messages.sendMedia".to_string(),
      "messages.sendMultiMedia".to_string(),
      "messages.editMessage".to_string(),
    ],
  );
  entry.1
}

fn run_send(
  rt: &Runtime,
  ctx: &Context,
  state: &Rc<RpcState>,
  host: &Rc<TestHost>,
  method: &str,
  json: &str,
) -> (Option<String>, Option<String>) {
  let callback_id = send_callback_id(host);
  let before_next = host.next_calls.borrow().len();
  let before_complete = host.completes.borrow().len();
  // every send ends in exactly one of the two, so this is a fresh id per call - and it has to
  // be, since a dispatch that parked in `next()` still holds its resolvers until dispose
  let dispatch_id = (before_next + before_complete + 1) as i64;
  state.dispatch(rt, ctx, callback_id, dispatch_id, method, 0, &format!("J{json}"));
  let next = host.next_calls.borrow().get(before_next).map(|(_, wire)| wire.clone());
  let complete = host.completes.borrow().get(before_complete).map(|(_, wire)| wire.clone());
  (next, complete)
}

#[test]
fn a_rewrite_reaches_the_request_that_actually_goes_out() {
  let (rt, ctx, host, state, _logs) = setup(&["interceptSendMessage"]);
  eval(
    &ctx,
    r#"
      inu.interceptSendMessage(async ({ message: m }) => {
        await Promise.resolve();
        m.text = { text: 'rewritten', entities: [{ _: 'messageEntityBold', offset: 0, length: 2 }] };
        m.silent = true;
        m.replyToMessageId = 11;
        m.scheduleDate = 1700000000;
        return 'send';
      });
    "#,
  );
  let (next, complete) = run_send(&rt, &ctx, &state, &host, "messages.sendMessage", SEND_TEXT);
  assert_eq!(complete, None, "a 'send' verdict settles from next(), not from the stage");
  let next = next.expect("the send never went out");
  assert!(next.contains(r#""message":"rewritten""#), "{next}");
  assert!(next.contains(r#""_":"messageEntityBold""#), "{next}");
  assert!(next.contains(r#""silent":true"#), "{next}");
  assert!(next.contains(r#""reply_to_msg_id":11"#), "{next}");
  assert!(next.contains(r#""schedule_date":1700000000"#), "{next}");
}

#[test]
fn a_send_goes_out_only_on_a_send_verdict() {
  for (middleware, prefix, text, fault) in [
    ("() => 'drop'", "R-1000:MESSAGE_DROPPED_BY_PLUGIN", "", false),
    ("async () => 'drop'", "R-1000:MESSAGE_DROPPED_BY_PLUGIN", "", false),
    ("() => { throw new Error('boom') }", "E", "boom", true),
    ("() => {}", "E", "expected 'send' or 'drop'", true),
  ] {
    let (rt, ctx, host, state, logs) = setup(&["interceptSendMessage"]);
    eval(&ctx, &format!("inu.interceptSendMessage({middleware});"));
    let (next, complete) = run_send(&rt, &ctx, &state, &host, "messages.sendMessage", SEND_TEXT);
    assert_eq!(next, None, "{middleware}");
    let complete = complete.unwrap_or_default();
    assert!(complete.starts_with(prefix) && complete.contains(text), "{middleware}: {complete}");
    let faulted = levels(&logs).iter().any(|(level, _)| *level == crate::LEVEL_FAULT);
    assert_eq!(faulted, fault, "{middleware}: {:?}", levels(&logs));
  }
}

/// a Saved Messages send carries `inputPeerSelf`, the one peer form `peer` cannot read out of the
/// request, so it resolves through the account's own `userId`; a topic-only reply is not a reply
/// anyone wrote
#[test]
fn the_send_view_reads_the_peer_and_the_reply_the_user_meant() {
  let (rt, ctx, host, state, _logs) = setup(&["interceptSendMessage"]);
  eval(
    &ctx,
    r#"
      globalThis.__seen = [];
      inu.interceptSendMessage(({ message: m }) => { __seen.push([m.peer, m.replyToMessageId, m.topicId]); return 'send' });
    "#,
  );
  let to_self = r#"{"_":"messages.sendMessage","peer":{"_":"inputPeerSelf"},"message":"note","random_id":"1"}"#;
  let in_topic = r#"{"_":"messages.sendMessage","peer":{"_":"inputPeerChannel","channel_id":"9","access_hash":"3"},"message":"hi","random_id":"1","reply_to":{"_":"inputReplyToMessage","reply_to_msg_id":20,"top_msg_id":20}}"#;
  let replying = r#"{"_":"messages.sendMessage","peer":{"_":"inputPeerChannel","channel_id":"9","access_hash":"3"},"message":"hi","random_id":"1","reply_to":{"_":"inputReplyToMessage","reply_to_msg_id":33,"top_msg_id":20}}"#;
  for send in [to_self, in_topic, replying] {
    let (next, _) = run_send(&rt, &ctx, &state, &host, "messages.sendMessage", send);
    assert!(next.is_some(), "the user's message never reached the network: {send}");
  }
  assert_eq!(eval_json(&ctx, "__seen"), "[[111,null,null],[-1000000000009,null,20],[-1000000000009,33,20]]");
}

/// callback syntax does not predict whether a verdict arrives before the draw deadline, so it
/// must not change what the host is told to filter on
#[test]
fn interceptsendmessage_serializes_its_filter_whatever_the_callback_syntax() {
  let (_rt, ctx, host, _state, _logs) = setup(&["interceptSendMessage"]);
  eval(
    &ctx,
    r#"
      inu.interceptSendMessage(async () => 'send');
      inu.interceptSendMessage({ text: /^\.stats$/i, isEdit: false }, async () => 'send');
      inu.interceptSendMessage(() => 'send');
      inu.interceptSendMessage({ text: /^\.stats$/i, isEdit: false }, () => 'send');
    "#,
  );
  let filters: Vec<String> = host.registered.borrow().iter().map(|entry| entry.4.clone()).collect();
  assert_eq!(filters[1], r#"{"isEdit":false,"text":{"source":"^\\.stats$","flags":"i"}}"#);
  assert_eq!((&filters[0], &filters[1]), (&filters[2], &filters[3]));
}

/// `next()` refuses to rewrite the method the app is already awaiting a response type for, so a
/// length change is refused where it is decidable rather than at the bridge
#[test]
fn media_may_be_replaced_but_not_added_or_removed() {
  let (rt, ctx, host, state, _logs) = setup(&["interceptSendMessage"]);
  eval(
    &ctx,
    r#"
      globalThis.__errors = [];
      inu.interceptSendMessage(({ message: m }) => {
        try { m.media = [{ _: 'inputMediaEmpty' }] } catch (e) { __errors.push([e.code, m.isEdit]) }
        return 'send';
      });
    "#,
  );
  run_send(&rt, &ctx, &state, &host, "messages.sendMessage", SEND_TEXT);
  run_send(&rt, &ctx, &state, &host, "messages.sendMultiMedia", SEND_ALBUM);
  assert_eq!(eval_json(&ctx, "__errors"), r#"[["unsupported",false],["unsupported",false]]"#);

  let (next, _) = run_send(&rt, &ctx, &state, &host, "messages.sendMedia", SEND_MEDIA);
  assert!(next.unwrap().contains(r#""media":{"_":"inputMediaEmpty"}"#), "one-for-one is allowed");
}

#[test]
fn the_bundled_send_intercept_test_plugin_passes() {
  const ORACLE: &str = crate::testing::test_plugin!("send-intercept-test.js");
  let (rt, ctx, host, state, _logs) = setup(&crate::testing::harness::manifest_grants(ORACLE));
  let lines = crate::testing::harness::install_capturing_console(&ctx);
  eval(&ctx, ORACLE);
  for (method, json) in [
    ("messages.sendMessage", SEND_TEXT),
    ("messages.sendMedia", SEND_MEDIA),
    ("messages.sendMultiMedia", SEND_ALBUM),
    ("messages.editMessage", EDIT),
    (
      "messages.sendMessage",
      r#"{"_":"messages.sendMessage","peer":{"_":"inputPeerUser","user_id":"7","access_hash":"3"},"message":"drop me","random_id":"2"}"#,
    ),
  ] {
    run_send(&rt, &ctx, &state, &host, method, json);
  }
  let sent: Vec<String> = host
    .next_calls
    .borrow()
    .iter()
    // the payloads are plain json, so a rust debug string is a valid js string literal
    .map(|(_, wire)| format!("{:?}", wire.trim_start_matches('J')))
    .collect();
  eval(&ctx, &format!("__report([{}])", sent.join(",")));
  let lines = lines.borrow();
  crate::testing::harness::assert_oracle_exact(&lines, "send intercept test done", 27);
}

fn update_callback_id(host: &Rc<TestHost>) -> u32 {
  let registered = host.intercept_update_registered.borrow();
  let disposed = host.intercept_update_unregistered.borrow();
  let live: Vec<_> = registered.iter().filter(|e| !disposed.contains(&e.0)).collect();
  assert_eq!(live.len(), 1, "expected exactly one live interceptUpdate registration");
  live[0].0
}

fn dispatch_intercept(
  rt: &Runtime,
  ctx: &Context,
  state: &Rc<RpcState>,
  host: &Rc<TestHost>,
  dispatch_id: i64,
  type_name: &str,
  wire: &str,
) {
  let callback_id = update_callback_id(host);
  state.dispatch_update_intercept(rt, ctx, callback_id, dispatch_id, type_name, 0, wire);
}

#[test]
fn an_update_interceptor_writes_through_to_the_app_s_object_and_answers_a_verdict() {
  let (rt, ctx, host, state, _logs) = setup(&["interceptUpdate(updateNewMessage)"]);
  seed_tl_object(&host, "updateNewMessage");
  eval(
    &ctx,
    "globalThis.__seen = []; inu.interceptUpdate('updateNewMessage', ({ update: u }) => { __seen.push(u._); u.x = 12; return 'deliver' });",
  );
  assert_eq!(host.intercept_update_registered.borrow()[0].1, vec!["updateNewMessage".to_string()]);
  dispatch_intercept(&rt, &ctx, &state, &host, 5, "updateNewMessage", &format!("HOW{TEST_HANDLE}"));
  assert_eq!(eval_json(&ctx, "__seen"), r#"["updateNewMessage"]"#);
  assert_eq!(host.tl_fields.borrow().get("x").map(String::as_str), Some("J12"));
  assert_eq!(host.verdicts.borrow().as_slice(), [(5, true)]);
}

/// dropping is the one verdict that desyncs pts, so a plugin's bug must never produce it
#[test]
fn a_failing_update_middleware_delivers_and_is_the_plugins_fault() {
  for middleware in ["() => { throw new Error('boom') }", "async () => { throw new Error('boom') }", "() => {}"] {
    let (rt, ctx, host, state, logs) = setup(&["interceptUpdate(updateNewMessage)"]);
    eval(&ctx, &format!("inu.interceptUpdate('updateNewMessage', {middleware});"));
    dispatch_intercept(&rt, &ctx, &state, &host, 5, "updateNewMessage", NEW_MESSAGE);
    assert_eq!(host.verdicts.borrow().as_slice(), [(5, true)], "{middleware}");
    assert_eq!(levels(&logs).first().map(|(level, _)| *level), Some(crate::LEVEL_FAULT), "{middleware}");
  }
}

#[test]
fn a_dispatch_naming_a_disposed_update_interceptor_delivers() {
  let (rt, ctx, host, state, _logs) = setup(&["interceptUpdate(updateNewMessage)"]);
  eval(
    &ctx,
    "globalThis.__ran = 0; globalThis.__d = inu.interceptUpdate('updateNewMessage', () => { __ran++; return 'drop' });",
  );
  let callback_id = update_callback_id(&host);
  eval(&ctx, "__d(); __d();");
  assert_eq!(host.intercept_update_unregistered.borrow().as_slice(), [callback_id]);
  state.dispatch_update_intercept(&rt, &ctx, callback_id, 5, "updateNewMessage", 0, NEW_MESSAGE);
  assert_eq!(eval_json(&ctx, "__ran"), "0");
  assert_eq!(host.verdicts.borrow().as_slice(), [(5, true)]);
}

#[test]
fn the_bundled_update_intercept_test_plugin_passes() {
  const ORACLE: &str = crate::testing::test_plugin!("update-intercept-test.js");
  let (rt, ctx, host, state, _logs) = setup(&crate::testing::harness::manifest_grants(ORACLE));
  let lines = crate::testing::harness::install_capturing_console(&ctx);
  eval(&ctx, ORACLE);
  for (id, type_name, wire) in [
    (1i64, "updateNewMessage", NEW_MESSAGE),
    (
      2,
      "updateNewMessage",
      r#"J{"_":"updateNewMessage","message":{"_":"message","id":43,"message":"drop me","peer_id":{"_":"peerUser","user_id":"7"}},"pts":10}"#,
    ),
    (
      3,
      "updateEditMessage",
      r#"J{"_":"updateEditMessage","message":{"_":"message","id":42,"message":"edited","peer_id":{"_":"peerUser","user_id":"7"}},"pts":11}"#,
    ),
  ] {
    dispatch_intercept(&rt, &ctx, &state, &host, id, type_name, wire);
  }
  eval(&ctx, &format!("__report({})", serde_verdicts(&host)));
  let lines = lines.borrow();
  crate::testing::harness::assert_oracle_exact(&lines, "update intercept test done", 16);
}

fn serde_verdicts(host: &Rc<TestHost>) -> String {
  let verdicts: Vec<String> = host.verdicts.borrow().iter().map(|(id, deliver)| format!("[{id},{deliver}]")).collect();
  format!("[{}]", verdicts.join(","))
}

/// The TL-view oracles need nesting, vectors and per-field read-only-ness that [`TestHost`]'s single
/// flat handle cannot express, so they get their own host.
mod bundled_oracles {
  use super::*;
  use crate::api::tl::proxy::TlHost;
  use rquickjs::Context;
  use std::collections::HashSet;

  /// what a handle names: a TL object or a vector, shared by `Rc` so two handles over the same
  /// node are the same node, exactly as two handles over one `TLObject` are in `TlHandles`
  enum Node {
    Object { type_name: String, fields: Vec<(String, Val)> },
    Vector(Vec<Val>),
  }

  #[derive(Clone)]
  enum Val {
    Wire(String),
    Ref(Rc<RefCell<Node>>),
  }

  fn object(type_name: &str, fields: &[(&str, Val)]) -> Rc<RefCell<Node>> {
    Rc::new(RefCell::new(Node::Object {
      type_name: type_name.to_string(),
      fields: fields.iter().map(|(k, v)| (k.to_string(), v.clone())).collect(),
    }))
  }

  fn vector(items: &[Val]) -> Rc<RefCell<Node>> {
    Rc::new(RefCell::new(Node::Vector(items.to_vec())))
  }

  fn wire(value: &str) -> Val {
    Val::Wire(value.to_string())
  }

  fn node(value: &Rc<RefCell<Node>>) -> Val {
    Val::Ref(value.clone())
  }

  /// mirrors `PluginWire.encodePluginError` Kotlin-side: code, grant, usage, quota, message
  fn plugin_error(code: &str, message: &str) -> String {
    format!("P{code}\n\n\n\n{message}")
  }

  fn read_request_method(host: &OracleHost, request_wire: &str) -> String {
    if let Some(json) = request_wire.strip_prefix('J') {
      let at = json.find(r#""_":""#).expect("a request names its method");
      let rest = &json[at + r#""_":""#.len()..];
      return rest[..rest.find('"').unwrap()].to_string();
    }
    let id: i64 = request_wire[3..].parse().expect("a handle wire ends in an id");
    let (node, _) = host.lookup(id).expect("a live handle");
    let name = match &*node.borrow() {
      Node::Object { type_name, .. } => type_name.clone(),
      Node::Vector(_) => panic!("a request is never a vector"),
    };
    name
  }

  type Held = (Rc<RefCell<Node>>, bool);

  #[derive(Default)]
  struct OracleHost {
    handles: RefCell<HashMap<i64, Held>>,
    next_id: Cell<i64>,
    /// what `TlFilter.HIDDEN_FIELDS` does to a field: absent on every read path, and a write
    /// refused so the hiding is not write-through
    hidden: RefCell<HashSet<String>>,
    /// what `TlFilter`'s redaction evidence does: writes refused, and the value handed
    /// out read-only so the verdict cannot be changed one level down either
    sealed: RefCell<HashSet<String>>,
    /// the `auth.*`/`account.*` takeover list, which no grant lifts
    refused: RefCell<HashSet<String>>,
    chain_method: RefCell<String>,
    invokes: RefCell<Vec<(i64, i32, String)>>,
    nexts: RefCell<Vec<(i64, String)>>,
    registered: RefCell<Vec<(Vec<String>, u32)>>,
    completes: RefCell<Vec<(i64, String)>>,
    released: RefCell<Vec<i64>>,
  }

  impl OracleHost {
    fn mint(&self, node: &Rc<RefCell<Node>>, read_only: bool) -> String {
      let id = self.next_id.get() + 1;
      self.next_id.set(id);
      self.handles.borrow_mut().insert(id, (node.clone(), read_only));
      let kind = if matches!(&*node.borrow(), Node::Vector(_)) { 'V' } else { 'O' };
      format!("H{kind}{}{id}", if read_only { 'R' } else { 'W' })
    }

    fn lookup(&self, handle: i64) -> Option<Held> {
      self.handles.borrow().get(&handle).cloned()
    }

    fn value_wire(&self, value: &Val, read_only: bool, key: &str) -> String {
      match value {
        Val::Wire(w) => w.clone(),
        Val::Ref(child) => self.mint(child, read_only || self.sealed.borrow().contains(key)),
      }
    }
  }

  impl TlHost for OracleHost {
    fn tl_get(&self, handle: i64, key: &str) -> String {
      let Some((node, read_only)) = self.lookup(handle) else {
        return plugin_error("handle-expired", "no such handle");
      };
      let result = match &*node.borrow() {
        Node::Object { type_name, fields } => {
          if key == "_" {
            return format!("S{type_name}");
          }
          // a stripped field reads exactly like a cleared flag bit, per `common.d.ts`
          if self.hidden.borrow().contains(key) {
            return "N".to_string();
          }
          match fields.iter().find(|(k, _)| k == key) {
            Some((_, value)) => self.value_wire(value, read_only, key),
            None => proxy::encode_error(&format!("no such field '{key}'")),
          }
        }
        Node::Vector(items) => {
          if key == "length" {
            return format!("I{}", items.len());
          }
          match key.parse::<usize>().ok().and_then(|i| items.get(i)) {
            Some(value) => self.value_wire(value, read_only, key),
            None => proxy::encode_error("index out of range"),
          }
        }
      };
      result
    }

    fn tl_set_bytes(&self, handle: i64, key: &str, value: &[u8]) -> Option<String> {
      self.tl_set(handle, key, &crate::api::tl::proxy::encode_bytes_wire(value))
    }

    fn tl_set(&self, handle: i64, key: &str, value_wire: &str) -> Option<String> {
      let (node, read_only) = self.lookup(handle)?;
      if read_only {
        return Some(plugin_error("forbidden", "this view is read-only"));
      }
      // the two refusals differ, and `common.d.ts` says so: a stripped field is refused the
      // way a field the type does not have is, a sealed one with `forbidden`
      if self.hidden.borrow().contains(key) {
        return Some(format!("Pinvalid-argument\n\n\n\nno such field '{key}'"));
      }
      if self.sealed.borrow().contains(key) {
        return Some(plugin_error("forbidden", &format!("'{key}' is sealed while api filtering is on")));
      }
      // a read-only handle coming back over the bridge would be re-minted writable
      if value_wire.starts_with("HOR") || value_wire.starts_with("HVR") {
        return Some(plugin_error("forbidden", "this TL view is read-only"));
      }
      let result = match &mut *node.borrow_mut() {
        Node::Object { fields, .. } => {
          let value = Val::Wire(value_wire.to_string());
          match fields.iter_mut().find(|(k, _)| k == key) {
            Some(slot) => slot.1 = value,
            None => fields.push((key.to_string(), value)),
          }
          None
        }
        Node::Vector(_) => Some(plugin_error("forbidden", "a vector view is read-only")),
      };
      result
    }

    fn tl_has(&self, handle: i64, key: &str) -> i32 {
      let Some((node, _)) = self.lookup(handle) else {
        return -1;
      };
      if self.hidden.borrow().contains(key) {
        return 0;
      }
      let result = match &*node.borrow() {
        Node::Object { fields, .. } => i32::from(key == "_" || fields.iter().any(|(k, _)| k == key)),
        Node::Vector(items) => i32::from(key.parse::<usize>().is_ok_and(|i| i < items.len())),
      };
      result
    }

    fn tl_own_keys(&self, handle: i64) -> Option<String> {
      let (node, _) = self.lookup(handle)?;
      let hidden = self.hidden.borrow();
      let keys: Vec<String> = match &*node.borrow() {
        Node::Object { fields, .. } => fields.iter().map(|(k, _)| k.clone()).filter(|k| !hidden.contains(k)).collect(),
        Node::Vector(items) => (0..items.len()).map(|i| i.to_string()).collect(),
      };
      Some(keys.join(","))
    }

    fn tl_copy(&self, handle: i64) -> Option<String> {
      let (node, _) = self.lookup(handle)?;
      Some(self.to_json(&node))
    }

    fn tl_release(&self, handle: i64) {
      self.released.borrow_mut().push(handle);
      self.handles.borrow_mut().remove(&handle);
    }
  }

  impl OracleHost {
    fn to_json(&self, node: &Rc<RefCell<Node>>) -> String {
      let hidden = self.hidden.borrow();
      match &*node.borrow() {
        Node::Object { type_name, fields } => {
          let body: Vec<String> = fields
            .iter()
            .filter(|(k, _)| !hidden.contains(k))
            .map(|(k, v)| format!("\"{k}\":{}", self.encode_value_json(v)))
            .collect();
          format!("{{\"_\":\"{type_name}\"{}{}}}", if body.is_empty() { "" } else { "," }, body.join(","))
        }
        Node::Vector(items) => {
          format!("[{}]", items.iter().map(|v| self.encode_value_json(v)).collect::<Vec<_>>().join(","))
        }
      }
    }

    fn encode_value_json(&self, value: &Val) -> String {
      match value {
        Val::Ref(child) => self.to_json(child),
        Val::Wire(w) => {
          let (tag, payload) = w.split_at(1);
          match tag {
            "S" => format!("\"{payload}\""),
            "I" | "D" | "J" => payload.to_string(),
            "B" => if payload == "1" { "true" } else { "false" }.to_string(),
            _ => "null".to_string(),
          }
        }
      }
    }
  }

  impl RpcHost for OracleHost {
    fn on_register(
      &self,
      methods: &[String],
      callback_id: u32,
      _scope: &str,
      _strict: bool,
      _filter_json: &str,
    ) -> Option<String> {
      for method in methods {
        if self.refused.borrow().contains(method) {
          return Some(plugin_error("forbidden", &format!("'{method}' is not interceptable")));
        }
      }
      self.registered.borrow_mut().push((methods.to_vec(), callback_id));
      None
    }
    fn on_unregister(&self, _callback_id: u32) {}
    fn on_invoke(&self, invoke_id: i64, slot: i32, request_wire: &str) -> Option<String> {
      let method = read_request_method(self, request_wire);
      if self.refused.borrow().contains(&method) {
        return Some(plugin_error("forbidden", &format!("'{method}' is not callable by a plugin")));
      }
      self.invokes.borrow_mut().push((invoke_id, slot, method));
      None
    }
    fn on_invoke_raw(&self, _invoke_id: i64, _slot: i32, _method: &[u8]) -> Option<String> {
      Some(plugin_error("unsupported", "the oracle host sends no raw requests"))
    }
    fn on_takeout(&self, _invoke_id: i64, _slot: i32, _op: i32, _takeout_id: &str, _arg: &str) -> Option<String> {
      Some(plugin_error("unsupported", "the oracle host opens no takeout sessions"))
    }
    fn on_next(&self, dispatch_id: i64, request_wire: &str) -> Option<String> {
      let method = read_request_method(self, request_wire);
      let expected = self.chain_method.borrow().clone();
      if !expected.is_empty() && method != expected {
        return Some(plugin_error("forbidden", &format!("next(): expected a '{expected}' request, got '{method}'")));
      }
      self.nexts.borrow_mut().push((dispatch_id, method));
      None
    }
    fn on_complete(&self, dispatch_id: i64, result_wire: &str) {
      self.completes.borrow_mut().push((dispatch_id, result_wire.to_string()));
    }
    fn on_update_register(&self, _callback_id: u32, _types: &[String], _scope: &str) -> Option<String> {
      None
    }
    fn on_update_unregister(&self, _callback_id: u32) {}
    fn on_intercept_update_register(&self, _callback_id: u32, _types: &[String]) -> Option<String> {
      None
    }
    fn on_intercept_update_unregister(&self, _callback_id: u32) {}
    fn on_update_verdict(&self, _dispatch_id: i64, _deliver: bool) {}
  }

  type Disposing = crate::testing::harness::DisposeOnDrop<RpcState>;
  type Fixture = (Runtime, Context, Rc<OracleHost>, Disposing, std::sync::Arc<crate::testing::harness::Logs>);

  fn setup(grants: &[&str], with_globals: bool) -> Fixture {
    super::setup_engine::<OracleHost>(grants, with_globals, Some(TestAccountHost::with(TWO_ACCOUNTS)))
  }

  const GLOBALS_ORACLE: &str = crate::testing::test_plugin!("globals-test.js");
  const LAZY_TL_ORACLE: &str = crate::testing::test_plugin!("lazy-tl-test.js");
  const TAKEOVER_ORACLE: &str = crate::testing::test_plugin!("takeover-test.js");
  const API_FILTER_ORACLE: &str = crate::testing::test_plugin!("api-filter-test.js");

  /// `help.getConfig` as the app would answer it, with the one field the takeover filter strips
  fn config_node() -> Rc<RefCell<Node>> {
    object(
      "config",
      &[
        ("this_dc", wire("I2")),
        ("test_mode", wire("B0")),
        ("autologin_token", wire("Ssecret")),
        ("dc_options", node(&vector(&[node(&object("dcOption", &[("id", wire("I2"))]))]))),
      ],
    )
  }

  #[test]
  fn the_bundled_globals_test_plugin_passes() {
    let (rt, ctx, host, state, _logs) = setup(&crate::testing::harness::manifest_grants(GLOBALS_ORACLE), true);
    let lines = crate::testing::harness::install_capturing_console(&ctx);
    eval(&ctx, GLOBALS_ORACLE);

    let (invoke_id, _, _) = host.invokes.borrow()[0].clone();
    let view = host.mint(&config_node(), false);
    state.settle(&rt, &ctx, invoke_id, &view);

    let lines = lines.borrow();
    crate::testing::harness::assert_oracle_exact(&lines, "globals test done", 38);
  }

  #[test]
  fn the_bundled_lazy_tl_test_plugin_passes() {
    let (rt, ctx, host, state, _logs) = setup(&crate::testing::harness::manifest_grants(LAZY_TL_ORACLE), false);
    let lines = crate::testing::harness::install_capturing_console(&ctx);
    eval(&ctx, LAZY_TL_ORACLE);

    // the invoke settles first: the update half's last assertion is that a read-only view is
    // refused as a *writable* view's field value, and it needs one to try it on
    let (invoke_id, _, _) = host.invokes.borrow()[0].clone();
    let config = host.mint(&config_node(), false);
    state.settle(&rt, &ctx, invoke_id, &config);

    let update = object(
      "updateNewMessage",
      &[("pts", wire("I7")), ("message", node(&object("message", &[("id", wire("I42"))])))],
    );
    let update_wire = host.mint(&update, true);
    state.dispatch_update(&rt, &ctx, "updateNewMessage", 0, &update_wire);

    let lines = lines.borrow();
    crate::testing::harness::assert_oracle_exact(&lines, "lazy tl test done", 10);
  }

  /// The list itself is the host's (`GrantCatalog`); this covers a `P` wire on each of the three refusal
  /// channels (`on_invoke`, `on_register`, `on_next`) reaching the plugin as an `inu.PluginError`.
  #[test]
  fn the_bundled_takeover_test_plugin_passes() {
    let (rt, ctx, host, state, _logs) = setup(&crate::testing::harness::manifest_grants(TAKEOVER_ORACLE), false);
    for method in ["auth.exportLoginToken", "auth.signIn", "account.getAuthorizations", "account.deleteAccount"] {
      host.refused.borrow_mut().insert(method.to_string());
    }
    *host.chain_method.borrow_mut() = "help.getConfig".to_string();
    let lines = crate::testing::harness::install_capturing_console(&ctx);
    eval(&ctx, TAKEOVER_ORACLE);
    pump_jobs(&rt, &ctx, state.log.as_ref());

    let callback_id = host.registered.borrow()[0].1;
    let request = host.mint(&object("help.getConfig", &[]), false);
    state.dispatch(&rt, &ctx, callback_id, 500, "help.getConfig", 0, &request);
    let response = host.mint(&config_node(), false);
    state.complete_next(&rt, &ctx, 500, &response);

    let lines = lines.borrow();
    crate::testing::harness::assert_oracle_exact(&lines, "takeover test done", 6);
  }

  /// `TlFilter`'s field lists are the host's; this covers `in`, `Object.keys`, a read, `toJSON()` and an
  /// assignment all agreeing with what the host said about a field.
  #[test]
  fn the_bundled_api_filter_test_plugin_passes() {
    let (rt, ctx, host, state, _logs) = setup(&crate::testing::harness::manifest_grants(API_FILTER_ORACLE), false);
    host.hidden.borrow_mut().insert("autologin_token".to_string());
    for field in ["from_id", "peer_id", "fwd_from", "out"] {
      host.sealed.borrow_mut().insert(field.to_string());
    }
    let lines = crate::testing::harness::install_capturing_console(&ctx);
    eval(&ctx, API_FILTER_ORACLE);

    for (invoke_id, _, method) in host.invokes.borrow().clone() {
      let answer = match method.as_str() {
        "help.getConfig" => host.mint(&config_node(), false),
        "messages.getHistory" => host.mint(&history_node(), false),
        other => panic!("the oracle asked for {other}"),
      };
      state.settle(&rt, &ctx, invoke_id, &answer);
    }

    let update = object("updateNewMessage", &[("message", node(&service_message_node()))]);
    let update_wire = host.mint(&update, true);
    state.dispatch_update(&rt, &ctx, "updateNewMessage", 0, &update_wire);

    let lines = lines.borrow();
    crate::testing::harness::assert_oracle_exact(&lines, "api filter test done", 3);
  }

  fn history_node() -> Rc<RefCell<Node>> {
    let message = object(
      "message",
      &[
        ("id", wire("I42")),
        ("out", wire("B0")),
        ("message", wire("Shi")),
        ("from_id", node(&object("peerUser", &[("user_id", wire("S7"))]))),
        ("peer_id", node(&object("peerUser", &[("user_id", wire("S7"))]))),
        ("fwd_from", wire("N")),
      ],
    );
    object("messages.messages", &[("messages", node(&vector(&[node(&message)])))])
  }

  /// what the takeover filter leaves of a login code: same length, so entity offsets still line
  /// up, and nothing code-shaped left for the oracle's regex to find
  fn service_message_node() -> Rc<RefCell<Node>> {
    object(
      "message",
      &[
        ("id", wire("I43")),
        ("out", wire("B0")),
        ("message", wire("SLogin code: *****")),
        ("from_id", node(&object("peerUser", &[("user_id", wire("S777000"))]))),
        ("peer_id", node(&object("peerUser", &[("user_id", wire("S777000"))]))),
        ("fwd_from", wire("N")),
      ],
    )
  }
}

/// the account-less form carries `ANY_ACCOUNT` and the host decides what that means; the
/// `Account` form carries the slot its handle is pinned to
#[test]
fn an_invoke_carries_the_account_it_was_called_through_and_raw_bytes_cross_as_bytes() {
  let (rt, ctx, host, state, _logs) = setup(&["invokeRpc", "unsafe.invokeRaw", "account.read(self)"]);
  eval(
    &ctx,
    r#"
      inu.invokeRpc({ _: 'foo.bar' });
      inu.account(1).invokeRpc({ _: 'foo.bar' });
      inu.invokeRaw(new Uint8Array([0x2e, 0xfd, 0xa9, 0xac, 1])).then(r => { globalThis.__raw = r; });
      inu.account(1).invokeRaw(new Uint8Array([1, 2, 3, 4]));
    "#,
  );

  let slots: Vec<i32> = host.invoke_calls.borrow().iter().map(|(_, slot, _)| *slot).collect();
  assert_eq!(slots, vec![ANY_ACCOUNT, 1]);
  let calls = host.raw_calls.borrow().clone();
  assert_eq!(calls.iter().map(|(_, slot, _)| *slot).collect::<Vec<_>>(), vec![ANY_ACCOUNT, 1]);
  assert_eq!(calls[0].2, vec![0x2e, 0xfd, 0xa9, 0xac, 1]);
  assert_eq!(calls[1].2, vec![1, 2, 3, 4]);

  state.settle_bytes(&rt, &ctx, calls[0].0, &[5, 6, 7, 8]);
  assert_eq!(eval_json(&ctx, "[Array.from(__raw), __raw instanceof Uint8Array]"), "[[5,6,7,8],true]");
}

#[test]
fn a_takeout_session_carries_its_id_into_every_op() {
  let (rt, ctx, host, state, _logs) = setup(&["takeout", "invokeRpc(messages.getHistory)", "account.read(self)"]);
  eval(
    &ctx,
    r#"
      globalThis.__session = null;
      inu.account(1).initTakeoutSession({ messageUsers: true, fileMaxSize: 1500000 })
        .then(s => { globalThis.__session = s; });
    "#,
  );

  let init = host.takeout_calls.borrow()[0].clone();
  assert_eq!((init.1, init.2, init.3.as_str()), (1, OP_TAKEOUT_INIT, ""));
  assert_eq!(
    init.4,
    r#"{"contacts":false,"messageUsers":true,"messageChats":false,"messageMegagroups":false,"messageChannels":false,"fileMaxSize":1500000}"#,
  );

  state.settle(&rt, &ctx, init.0, "S8123456789");
  assert_eq!(eval_json(&ctx, "globalThis.__session.id"), r#""8123456789""#);

  eval(&ctx, "globalThis.__session.invokeRpc({ _: 'messages.getHistory' }); globalThis.__session.finish()");
  let calls = host.takeout_calls.borrow().clone();
  assert_eq!(
    calls.iter().map(|(_, slot, op, id, arg)| (*slot, *op, id.clone(), arg.clone())).collect::<Vec<_>>(),
    vec![
      (1, OP_TAKEOUT_INIT, String::new(), init.4.clone()),
      (1, OP_TAKEOUT_INVOKE, "8123456789".to_string(), r#"J{"_":"messages.getHistory"}"#.to_string()),
      (1, OP_TAKEOUT_FINISH, "8123456789".to_string(), "1".to_string()),
    ],
  );
}

#[test]
fn a_wrapped_call_still_needs_its_method_grant() {
  let (rt, ctx, host, state, _logs) = setup(&["takeout", "invokeRpc(users.getUsers)", "account.read(self)"]);
  eval(&ctx, "inu.account(1).initTakeoutSession().then(s => { globalThis.__s = s; })");
  let init_id = host.takeout_calls.borrow()[0].0;
  state.settle(&rt, &ctx, init_id, "S77");

  assert_eq!(
    catch_json(&ctx, "globalThis.__s.invokeRpc({ _: 'messages.getHistory' })"),
    r#"[true,"not-granted","invokeRpc(messages.getHistory)","missing grant: invokeRpc(messages.getHistory)"]"#,
  );
  assert_eq!(host.takeout_calls.borrow().len(), 1, "the refused call must never reach the host");
}

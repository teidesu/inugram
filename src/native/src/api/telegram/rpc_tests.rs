use super::*;
use crate::api::tl::proxy::TlHost;
use crate::sandbox::grants::TestGrantHost;
use rquickjs::Context;

#[derive(Default)]
struct TestHost {
  registered: RefCell<Vec<(Vec<String>, u32, String)>>,
  unregistered: RefCell<Vec<u32>>,
  next_calls: RefCell<Vec<(i64, String)>>,
  invoke_calls: RefCell<Vec<(i64, i32, String)>>,
  update_registered: RefCell<Vec<(u32, Vec<String>, String)>>,
  update_unregistered: RefCell<Vec<u32>>,
  intercept_update_registered: RefCell<Vec<(u32, Vec<String>)>>,
  intercept_update_unregistered: RefCell<Vec<u32>>,
  verdicts: RefCell<Vec<(i64, bool)>>,
  intercept_update_register_err: RefCell<Option<String>>,
  completes: RefCell<Vec<(i64, String)>>,
  register_err: RefCell<Option<String>>,
  update_register_err: RefCell<Option<String>>,
  next_err: RefCell<Option<String>>,
  tl_fields: RefCell<HashMap<String, String>>,
  tl_released: RefCell<Vec<i64>>,
}

impl RpcHost for TestHost {
  fn on_register(&self, methods: &[String], callback_id: u32, scope: &str) -> Option<String> {
    self.registered.borrow_mut().push((methods.to_vec(), callback_id, scope.to_string()));
    self.register_err.borrow().clone()
  }
  fn on_unregister(&self, callback_id: u32) {
    self.unregistered.borrow_mut().push(callback_id);
  }
  fn on_invoke(&self, invoke_id: i64, slot: i32, request_wire: &str) -> Option<String> {
    self.invoke_calls.borrow_mut().push((invoke_id, slot, request_wire.to_string()));
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
/// `tl_proxy.rs` owns the exhaustive trap tests - these only cover which wires reach JS as views.
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

fn wire_json(json: &str) -> String {
  format!("J{json}")
}

/// disposes on drop, so a failing assertion is one failed test rather than an abort in
/// `JS_FreeRuntime` that takes the whole suite's reporting with it
type Disposing = crate::testing::harness::DisposeOnDrop<RpcState>;

type LoggingFixture = (Runtime, Context, Rc<TestHost>, Disposing, std::sync::Arc<crate::testing::harness::Logs>);

fn setup(grants: &[&str]) -> (Runtime, Context, Rc<TestHost>, Disposing) {
  let (rt, ctx, host, state, _log) = setup_logging(grants);
  (rt, ctx, host, state)
}

use crate::testing::harness::eval_unit as eval;

use crate::testing::harness::eval_json;

/// like [`setup`] but captures every `log()` upcall so tests can assert on emitted diagnostics
fn setup_logging(grants: &[&str]) -> LoggingFixture {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let host = Rc::new(TestHost::default());
  let host_dyn: Rc<dyn RpcHost> = host.clone();
  let tl = TlViews::new(host.clone());
  let grant_host = TestGrantHost::new(grants);
  let logs = crate::testing::harness::Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  // the account api is installed for every engine, so every dispatch has a handle to hand over
  let accounts_host: Rc<dyn crate::api::telegram::account::AccountHost> =
    crate::api::telegram::account::tests::TestAccountHost::with(crate::api::telegram::account::tests::TWO_ACCOUNTS);
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::inu_namespace(&ctx);
    crate::api::error::install_plugin_error(&ctx, &inu).unwrap();
    // installApi runs before installRpc on a device, and the demuxed events read the
    // `inu.Message` it leaves behind
    let shared = crate::api::tl::utils::install_utils(&ctx, &inu).unwrap();
    crate::api::tl::message::install_message(&ctx, &shared, &inu).unwrap();
    let accounts = crate::api::telegram::account::install_account(
      &ctx,
      accounts_host,
      grant_host.as_host(),
      Lifecycle::new(),
      log.clone(),
      &inu,
    )
    .unwrap();
    install_rpc(&ctx, host_dyn, tl, grant_host.as_host(), Lifecycle::new(), Some(accounts), shared, log, &inu).unwrap()
  });
  let state = Disposing::new(&ctx, state, dispose);
  (rt, ctx, host, state, logs)
}

#[test]
fn middleware_transforms_request_then_passes_through_next_response() {
  let (rt, ctx, host, state) = setup(&["interceptRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.interceptRpc('foo.bar', (req, next) => {
                req.x = req.x + 1;
                return next(req);
            });
            "#,
      )
      .unwrap();
  });

  dispatch_rpc(&rt, &ctx, &state, 1, 100, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar","x":1}"#));

  assert_eq!(host.next_calls.borrow().len(), 1);
  assert_eq!(host.next_calls.borrow()[0].1, wire_json(r#"{"_":"foo.bar","x":2}"#));
  assert!(host.completes.borrow().is_empty());

  complete_next(&rt, &ctx, &state, 100, &wire_json(r#"{"_":"foo.bar","x":2,"ok":true}"#));

  let completes = host.completes.borrow();
  assert_eq!(completes.len(), 1);
  assert_eq!(completes[0], (100, wire_json(r#"{"_":"foo.bar","x":2,"ok":true}"#)));
}

#[test]
fn middleware_returns_undefined_without_awaiting_passes_through_next() {
  let (rt, ctx, host, state) = setup(&["interceptRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.interceptRpc('foo.bar', (req, next) => {
                next(req);
            });
            "#,
      )
      .unwrap();
  });

  dispatch_rpc(&rt, &ctx, &state, 1, 500, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  assert!(host.completes.borrow().is_empty());

  complete_next(&rt, &ctx, &state, 500, &wire_json(r#"{"_":"foo.bar","done":true}"#));
  let completes = host.completes.borrow();
  assert_eq!(completes.len(), 1);
  assert_eq!(completes[0], (500, wire_json(r#"{"_":"foo.bar","done":true}"#)));
}

#[test]
fn short_circuit_without_next() {
  let (rt, ctx, host, state) = setup(&["interceptRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.interceptRpc('foo.bar', (req, next) => {
                return { _: 'foo.bar', short: true };
            });
            "#,
      )
      .unwrap();
  });

  dispatch_rpc(&rt, &ctx, &state, 1, 200, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));

  assert!(host.next_calls.borrow().is_empty());
  let completes = host.completes.borrow();
  assert_eq!(completes.len(), 1);
  assert_eq!(completes[0], (200, wire_json(r#"{"_":"foo.bar","short":true}"#)));
}

#[test]
fn async_middleware_promise_result() {
  let (rt, ctx, host, state) = setup(&["interceptRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.interceptRpc('foo.bar', async (req, next) => {
                await Promise.resolve();
                return { _: 'foo.bar', async: true };
            });
            "#,
      )
      .unwrap();
  });

  dispatch_rpc(&rt, &ctx, &state, 1, 300, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));

  let completes = host.completes.borrow();
  assert_eq!(completes.len(), 1);
  assert_eq!(completes[0], (300, wire_json(r#"{"_":"foo.bar","async":true}"#)));
}

#[test]
fn next_called_twice_throws_type_error() {
  let (rt, ctx, host, state) = setup(&["interceptRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.interceptRpc('foo.bar', (req, next) => {
                next(req);
                next(req);
            });
            "#,
      )
      .unwrap();
  });

  dispatch_rpc(&rt, &ctx, &state, 1, 400, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));

  let completes = host.completes.borrow();
  assert_eq!(completes.len(), 1);
  assert_eq!(completes[0].0, 400);
  assert!(completes[0].1.starts_with('E'));
  assert!(completes[0].1.contains("only be called once"));
}

#[test]
fn abandon_rejects_the_parked_next_with_the_supplied_wire() {
  let (rt, ctx, host, state) = setup(&["interceptRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__caught = null;
            inu.interceptRpc('foo.bar', async (req, next) => {
                try { return await next(req); } catch (e) {
                    globalThis.__caught = [e instanceof inu.RpcError, e.code, e.text];
                    throw e;
                }
            });
            "#,
      )
      .unwrap();
  });

  dispatch_rpc(&rt, &ctx, &state, 1, 950, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  assert_eq!(host.next_calls.borrow().len(), 1);

  abandon_dispatch(&rt, &ctx, &state, 950, "R-1000:INTERCEPTOR_TIMEOUT");

  let caught: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__caught)").unwrap());
  assert_eq!(caught, r#"[true,-1000,"INTERCEPTOR_TIMEOUT"]"#);
}

#[test]
fn a_middleware_settling_after_abandon_never_completes() {
  let (rt, ctx, host, state, logs) = setup_logging(&["interceptRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.interceptRpc('foo.bar', async (req, next) => {
                try { await next(req); } catch (e) {}
                return { _: 'foo.bar', late: true };
            });
            "#,
      )
      .unwrap();
  });

  dispatch_rpc(&rt, &ctx, &state, 1, 951, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  abandon_dispatch(&rt, &ctx, &state, 951, "R-1000:INTERCEPTOR_TIMEOUT");

  assert!(host.completes.borrow().is_empty(), "an abandoned dispatch must never answer the host");
  assert!(
    logs.borrow().iter().any(|l| l.contains("settled after being abandoned")),
    "expected a logged diagnostic, got: {:?}",
    logs.borrow(),
  );
}

#[test]
fn next_after_abandon_throws_timed_out() {
  let (rt, ctx, _host, state) = setup(&["interceptRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.interceptRpc('foo.bar', (req, next) => {
                globalThis.__next = next;
                return new Promise(() => {});
            });
            "#,
      )
      .unwrap();
  });

  dispatch_rpc(&rt, &ctx, &state, 1, 952, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  abandon_dispatch(&rt, &ctx, &state, 952, "R-1000:INTERCEPTOR_TIMEOUT");

  assert_eq!(
    catch_json(&ctx, "globalThis.__next({ _: 'foo.bar' })"),
    r#"[true,"timed-out",null,"next(): the interceptor chain's budget expired and this stage was abandoned"]"#,
  );
}

#[test]
fn next_after_a_non_timeout_teardown_does_not_blame_the_budget() {
  let (rt, ctx, _host, state) = setup(&["interceptRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.interceptRpc('foo.bar', (req, next) => {
                globalThis.__next = next;
                return new Promise(() => {});
            });
            "#,
      )
      .unwrap();
  });

  dispatch_rpc(&rt, &ctx, &state, 1, 954, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  abandon_dispatch(&rt, &ctx, &state, 954, "R-1000:INTERCEPTOR_ABANDONED");

  assert_eq!(
    catch_json(&ctx, "globalThis.__next({ _: 'foo.bar' })"),
    r#"[true,"aborted",null,"next(): the interceptor chain was torn down and this stage was abandoned"]"#,
  );
}

#[test]
fn next_after_its_own_settle_throws_invalid_argument() {
  let (rt, ctx, host, state) = setup(&["interceptRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.interceptRpc('foo.bar', (req, next) => {
                globalThis.__next = next;
                return { _: 'foo.bar', short: true };
            });
            "#,
      )
      .unwrap();
  });

  dispatch_rpc(&rt, &ctx, &state, 1, 953, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  assert_eq!(host.completes.borrow().len(), 1);

  assert_eq!(
    catch_json(&ctx, "globalThis.__next({ _: 'foo.bar' })"),
    r#"[true,"invalid-argument",null,"next(): this dispatch already settled"]"#,
  );
  assert!(host.next_calls.borrow().is_empty());
}

/// the account-less form names no account, so it carries `ANY_ACCOUNT` and the host is the one
/// that decides what that means; the `Account` form carries the slot its handle is pinned to
#[test]
fn invoke_rpc_carries_the_account_it_was_called_through() {
  let (_rt, ctx, host, _state) = setup(&["invokeRpc", "account.read(self)"]);
  eval(
    &ctx,
    r#"
        inu.invokeRpc({ _: 'foo.bar' });
        inu.account(1).invokeRpc({ _: 'foo.bar' });
        "#,
  );
  let slots: Vec<i32> = host.invoke_calls.borrow().iter().map(|(_, slot, _)| *slot).collect();
  assert_eq!(slots, vec![ANY_ACCOUNT, 1]);
}

/// one prototype serves every slot, so a method torn off a handle has to fail by name rather
/// than send on slot 0 - `utils.js`'s rule for every other member of the surface
#[test]
fn a_torn_off_invoke_rpc_refuses_rather_than_picking_a_slot() {
  let (_rt, ctx, host, _state) = setup(&["invokeRpc", "account.read(self)"]);
  assert_eq!(
    eval_json(
      &ctx,
      "(() => { const f = inu.account(1).invokeRpc; try { f({ _: 'foo.bar' }) } catch (e) { return [e.code, e.message] } })()"
    ),
    r#"["invalid-argument","invokeRpc: not called on an account handle; use inu.account().invokeRpc(...)"]"#,
  );
  assert!(host.invoke_calls.borrow().is_empty());
}

#[test]
fn invoke_rpc_resolves_and_rejects() {
  let (rt, ctx, host, state) = setup(&["invokeRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__ok = null;
            globalThis.__err = null;
            inu.invokeRpc({_:'foo.bar'}).then(r => { globalThis.__ok = r; });
            "#,
      )
      .unwrap();
  });

  assert_eq!(host.invoke_calls.borrow().len(), 1);
  let invoke_id = host.invoke_calls.borrow()[0].0;
  resolve_invoke(&rt, &ctx, &state, invoke_id, &wire_json(r#"{"_":"foo.bar","ok":true}"#));
  let ok: String = ctx.with(|ctx| ctx.eval::<String, _>("JSON.stringify(globalThis.__ok)").unwrap());
  assert_eq!(ok, r#"{"_":"foo.bar","ok":true}"#);

  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(r#"inu.invokeRpc({_:'foo.baz'}).catch(e => { globalThis.__err = e.message; });"#)
      .unwrap();
  });
  assert_eq!(host.invoke_calls.borrow().len(), 2);
  let invoke_id2 = host.invoke_calls.borrow()[1].0;
  resolve_invoke(&rt, &ctx, &state, invoke_id2, "EBAD_REQUEST: oops");
  let err: String = ctx.with(|ctx| ctx.eval::<String, _>("globalThis.__err").unwrap());
  assert_eq!(err, "BAD_REQUEST: oops");
}

#[test]
fn rpc_error_wire_rejects_as_rpc_error_instance_and_rethrow_round_trips() {
  let (rt, ctx, host, state) = setup(&["interceptRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__caught = null;
            inu.interceptRpc('foo.bar', async (req, next) => {
                try {
                    return await next(req);
                } catch (e) {
                    globalThis.__caught = { isRpc: e instanceof inu.RpcError, code: e.code, text: e.text, message: e.message };
                    throw e;
                }
            });
            "#,
      )
      .unwrap();
  });

  dispatch_rpc(&rt, &ctx, &state, 1, 900, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  complete_next(&rt, &ctx, &state, 900, "R400:PEER_ID_INVALID");

  let caught: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__caught)").unwrap());
  assert_eq!(caught, r#"{"isRpc":true,"code":400,"text":"PEER_ID_INVALID","message":"400: PEER_ID_INVALID"}"#);
  let completes = host.completes.borrow();
  assert_eq!(completes.len(), 1);
  assert_eq!(completes[0], (900, "R400:PEER_ID_INVALID".to_string()));
}

#[test]
fn thrown_rpc_error_completes_with_r_wire() {
  let (rt, ctx, host, state) = setup(&["interceptRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.interceptRpc('foo.bar', (req, next) => {
                throw new inu.RpcError(420, 'FLOOD_WAIT_3');
            });
            "#,
      )
      .unwrap();
  });

  dispatch_rpc(&rt, &ctx, &state, 1, 901, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  let completes = host.completes.borrow();
  assert_eq!(completes.len(), 1);
  assert_eq!(completes[0], (901, "R420:FLOOD_WAIT_3".to_string()));
}

#[test]
fn returned_rpc_error_completes_with_r_wire() {
  let (rt, ctx, host, state) = setup(&["interceptRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.interceptRpc('foo.bar', (req, next) => {
                return new inu.RpcError(403, 'FORBIDDEN');
            });
            "#,
      )
      .unwrap();
  });

  dispatch_rpc(&rt, &ctx, &state, 1, 902, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  let completes = host.completes.borrow();
  assert_eq!(completes.len(), 1);
  assert_eq!(completes[0], (902, "R403:FORBIDDEN".to_string()));
}

#[test]
fn invoke_rejection_with_rpc_error_wire_is_an_rpc_error_instance() {
  let (rt, ctx, host, state) = setup(&["invokeRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__caught = null;
            inu.invokeRpc({_:'foo.bar'}).catch(e => {
                globalThis.__caught = { isRpc: e instanceof inu.RpcError, code: e.code, text: e.text };
            });
            "#,
      )
      .unwrap();
  });

  let invoke_id = host.invoke_calls.borrow()[0].0;
  resolve_invoke(&rt, &ctx, &state, invoke_id, "R-503:Timeout");
  let caught: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__caught)").unwrap());
  assert_eq!(caught, r#"{"isRpc":true,"code":-503,"text":"Timeout"}"#);
}

#[test]
fn null_completion_resolves_next_as_null_and_round_trips() {
  let (rt, ctx, host, state) = setup(&["interceptRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__got = 'unset';
            inu.interceptRpc('foo.bar', async (req, next) => {
                const r = await next(req);
                globalThis.__got = r;
                return r;
            });
            "#,
      )
      .unwrap();
  });

  dispatch_rpc(&rt, &ctx, &state, 1, 903, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  complete_next(&rt, &ctx, &state, 903, "N");

  let got_is_null: bool = ctx.with(|ctx| ctx.eval("globalThis.__got === null").unwrap());
  assert!(got_is_null);
  let completes = host.completes.borrow();
  assert_eq!(completes.len(), 1);
  assert_eq!(completes[0], (903, "N".to_string()));
}

fn seed_tl_object(host: &Rc<TestHost>, type_name: &str) {
  let mut fields = host.tl_fields.borrow_mut();
  fields.insert("_".to_string(), format!("S{type_name}"));
  fields.insert("x".to_string(), "I1".to_string());
}

#[test]
fn invoke_result_handle_resolves_to_a_writable_plugin_lifetime_view() {
  let (rt, ctx, host, state) = setup(&["invokeRpc"]);
  seed_tl_object(&host, "foo.bar");
  ctx.with(|ctx| {
    ctx.eval::<(), _>(r#"inu.invokeRpc({_:'foo.bar'}).then(r => { globalThis.__got = r; });"#).unwrap();
  });

  let invoke_id = host.invoke_calls.borrow()[0].0;
  resolve_invoke(&rt, &ctx, &state, invoke_id, &format!("HOW{TEST_HANDLE}"));

  ctx.with(|ctx| {
    assert_eq!(ctx.eval::<String, _>("globalThis.__got._").unwrap(), "foo.bar");
    ctx.eval::<(), _>("globalThis.__got.x = 5").unwrap();
    // the view outlives its dispatch: still readable after the promise settled
    assert_eq!(ctx.eval::<i64, _>("globalThis.__got.x").unwrap(), 5);
    ctx.eval::<(), _>("globalThis.__got = undefined").unwrap();
  });

  assert_eq!(host.tl_fields.borrow().get("x").unwrap(), "J5");
  assert_eq!(*host.tl_released.borrow(), vec![TEST_HANDLE]);
}

#[test]
fn update_payload_handle_resolves_to_a_read_only_view() {
  let (rt, ctx, host, state) = setup(&["onUpdate"]);
  seed_tl_object(&host, "updateFoo");
  ctx.with(|ctx| {
    ctx.eval::<(), _>("inu.onUpdate('updateFoo', (u) => { globalThis.__seen = u; });").unwrap();
  });

  dispatch_update(&rt, &ctx, &state, "updateFoo", 0, &format!("HOR{TEST_HANDLE}"));

  ctx.with(|ctx| {
    assert_eq!(ctx.eval::<String, _>("globalThis.__seen._").unwrap(), "updateFoo");
    let caught: String = ctx
      .eval(
        r#"(() => {
                    try { globalThis.__seen.x = 5; return 'no-throw' }
                    catch (e) { return [e instanceof inu.PluginError, e.code].join('|') }
                })()"#,
      )
      .unwrap();
    assert_eq!(caught, "true|forbidden");
  });

  assert_eq!(host.tl_fields.borrow().get("x").unwrap(), "I1");
}

#[test]
fn an_update_with_no_listeners_still_releases_its_handle() {
  let (rt, ctx, host, state) = setup(&[]);
  seed_tl_object(&host, "updateFoo");

  dispatch_update(&rt, &ctx, &state, "updateFoo", 0, &format!("HOR{TEST_HANDLE}"));

  assert_eq!(*host.tl_released.borrow(), vec![TEST_HANDLE]);
}

#[test]
fn on_update_fan_out_survives_throwing_callback() {
  let (rt, ctx, host, state) = setup(&["onUpdate"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__seen = null;
            inu.onUpdate('updateFoo', (u) => { throw new Error('boom'); });
            inu.onUpdate('updateFoo', (u) => { globalThis.__seen = u.a; });
            "#,
      )
      .unwrap();
  });

  assert_eq!(host.update_registered.borrow().len(), 2);
  dispatch_update(&rt, &ctx, &state, "updateFoo", 0, &wire_json(r#"{"_":"updateFoo","a":42}"#));
  let seen: i32 = ctx.with(|ctx| ctx.eval::<i32, _>("globalThis.__seen").unwrap());
  assert_eq!(seen, 42);
}

/// evaluates `code`, returning the caught error as `[isPluginError, code, grant, message]` json
use crate::testing::harness::catch_json;

#[test]
fn intercept_registration_without_a_scoped_grant_throws_not_granted() {
  let (_rt, ctx, host, state) = setup(&["interceptRpc(messages.sendMessage)"]);
  let caught = catch_json(&ctx, "inu.interceptRpc(['messages.sendMessage', 'users.getUsers'], () => {})");
  assert_eq!(
    caught,
    r#"[true,"not-granted","interceptRpc(users.getUsers)","missing grant: interceptRpc(users.getUsers)"]"#,
  );
  assert!(host.registered.borrow().is_empty(), "a denied method must never reach the host");
  assert!(state.intercept_fns.is_empty());
}

#[test]
fn intercept_registration_with_a_scoped_grant_reaches_the_host() {
  let (_rt, ctx, host, _state) = setup(&["interceptRpc(users.getUsers)"]);
  ctx.with(|ctx| ctx.eval::<(), _>("inu.interceptRpc('users.getUsers', () => {});").unwrap());
  assert_eq!(host.registered.borrow()[0].0, vec!["users.getUsers".to_string()]);
}

#[test]
fn invoke_without_a_method_name_throws_invalid_argument() {
  let (_rt, ctx, host, _state) = setup(&["invokeRpc"]);
  assert_eq!(
    catch_json(&ctx, "inu.invokeRpc({})"),
    r#"[true,"invalid-argument",null,"invokeRpc: the request must carry its method name in '_'"]"#,
  );
  assert_eq!(
    catch_json(&ctx, "inu.invokeRpc({ _: 42 })"),
    r#"[true,"invalid-argument",null,"invokeRpc: the request must carry its method name in '_'"]"#,
  );
  assert!(host.invoke_calls.borrow().is_empty());
}

#[test]
fn invoke_of_an_ungranted_method_throws_not_granted() {
  let (_rt, ctx, host, _state) = setup(&["invokeRpc(users.getUsers)"]);
  assert_eq!(
    catch_json(&ctx, "inu.invokeRpc({ _: 'messages.sendMessage' })"),
    r#"[true,"not-granted","invokeRpc(messages.sendMessage)","missing grant: invokeRpc(messages.sendMessage)"]"#,
  );
  assert!(host.invoke_calls.borrow().is_empty());
}

#[test]
fn on_update_without_a_scoped_grant_throws_not_granted() {
  let (_rt, ctx, host, state) = setup(&["onUpdate(updateNewMessage)"]);
  assert_eq!(
    catch_json(&ctx, "inu.onUpdate(['updateNewMessage', 'updateUserTyping'], () => {})"),
    r#"[true,"not-granted","onUpdate(updateUserTyping)","missing grant: onUpdate(updateUserTyping)"]"#,
  );
  assert!(host.update_registered.borrow().is_empty(), "a denied type must never reach the host");
  assert!(state.update_fns.is_empty());
}

#[test]
fn on_update_needs_a_type_list() {
  let (_rt, ctx, host, _state) = setup(&["onUpdate"]);
  for code in ["inu.onUpdate(() => {})", "inu.onUpdate([], () => {})", "inu.onUpdate([1], () => {})"] {
    let threw = ctx.with(|ctx| ctx.eval::<(), _>(code).is_err());
    assert!(threw, "expected a TypeError from: {code}");
  }
  assert!(host.update_registered.borrow().is_empty());
}

#[test]
fn an_update_only_reaches_the_registrations_that_named_its_type() {
  let (rt, ctx, host, state) = setup(&["onUpdate"]);
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

  dispatch_update(&rt, &ctx, &state, UPDATE_TYPE, 0, UPDATE_WIRE);
  assert_eq!(eval_json(&ctx, "__ran"), r#"["new","both:updateNewMessage"]"#);

  dispatch_update(&rt, &ctx, &state, "updateUserTyping", 0, "J{\"_\":\"updateUserTyping\"}");
  assert_eq!(eval_json(&ctx, "__ran"), r#"["new","both:updateNewMessage","both:updateUserTyping","typing"]"#,);

  assert!(state.update_fns.len() == 3);
}

#[test]
fn an_update_handler_is_handed_the_account_it_arrived_on() {
  let (rt, ctx, _host, state) = setup(&["onUpdate", "account.read(self)"]);
  eval(
    &ctx,
    r#"
        globalThis.__seen = null;
        inu.onUpdate('updateNewMessage', (update, account) => {
            __seen = [account.id, account.userId, account.isCurrent()];
        });
        "#,
  );
  dispatch_update(&rt, &ctx, &state, UPDATE_TYPE, 1, UPDATE_WIRE);
  assert_eq!(eval_json(&ctx, "__seen"), "[1,222,false]");

  dispatch_update(&rt, &ctx, &state, UPDATE_TYPE, 0, UPDATE_WIRE);
  assert_eq!(eval_json(&ctx, "__seen"), "[0,111,true]");
}

#[test]
fn an_interceptor_is_handed_the_account_the_request_is_on() {
  let (rt, ctx, host, state) = setup(&["interceptRpc", "account.read(self)"]);
  eval(
    &ctx,
    r#"
        globalThis.__seen = null;
        inu.interceptRpc('foo.bar', (req, next, account) => {
            __seen = [account.id, account.userId, account.isCurrent()];
            return next(req);
        });
        "#,
  );
  dispatch_rpc(&rt, &ctx, &state, 1, 990, "foo.bar", 1, &wire_json(r#"{"_":"foo.bar"}"#));
  assert_eq!(eval_json(&ctx, "__seen"), "[1,222,false]");
  assert_eq!(host.next_calls.borrow().len(), 1);
}

/// the account api is installed before the rpc one, but nothing forces it to have succeeded -
/// and a dispatch that cannot name its account still has to run rather than fail the app's call
#[test]
fn without_the_account_api_a_dispatch_hands_over_undefined() {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let host = Rc::new(TestHost::default());
  let host_dyn: Rc<dyn RpcHost> = host.clone();
  let tl = TlViews::new(host.clone());
  let grants = TestGrantHost::new(&["onUpdate", "interceptRpc"]);
  let log: crate::Log = std::sync::Arc::new(|_: &str| {});
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::inu_namespace(&ctx);
    crate::api::error::install_plugin_error(&ctx, &inu).unwrap();
    let shared = crate::api::tl::utils::install_utils(&ctx, &inu).unwrap();
    crate::api::tl::message::install_message(&ctx, &shared, &inu).unwrap();
    install_rpc(&ctx, host_dyn, tl, grants.as_host(), Lifecycle::new(), None, shared, log, &inu).unwrap()
  });
  let state = Disposing::new(&ctx, state, dispose);

  eval(
    &ctx,
    r#"
        globalThis.__seen = [];
        inu.onUpdate('updateNewMessage', (u, account) => { __seen.push(typeof account); });
        inu.interceptRpc('foo.bar', (req, next, account) => { __seen.push(typeof account); return next(req); });
        "#,
  );
  dispatch_update(&rt, &ctx, &state, UPDATE_TYPE, 0, UPDATE_WIRE);
  dispatch_rpc(&rt, &ctx, &state, 1, 991, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  assert_eq!(eval_json(&ctx, "__seen"), r#"["undefined","undefined"]"#);
  assert_eq!(host.next_calls.borrow().len(), 1);
}

#[test]
fn invoke_rejection_with_a_plugin_error_wire_carries_usage_and_quota() {
  let (rt, ctx, host, state) = setup(&["invokeRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__caught = null;
            inu.invokeRpc({_:'foo.bar'}).catch(e => {
                globalThis.__caught = [e instanceof inu.PluginError, e.code, e.usage, e.quota, typeof e.usage, e.message];
            });
            "#,
      )
      .unwrap();
  });

  let invoke_id = host.invoke_calls.borrow()[0].0;
  resolve_invoke(&rt, &ctx, &state, invoke_id, "Pquota-exceeded\n\n64\n32\ntoo big");
  let caught: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__caught)").unwrap());
  assert_eq!(caught, r#"[true,"quota-exceeded",64,32,"number","too big"]"#);
}

#[test]
fn a_plugin_error_wire_from_the_host_rejects_next_as_a_plugin_error() {
  let (rt, ctx, _host, state) = setup(&["interceptRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__caught = null;
            inu.interceptRpc('foo.bar', async (req, next) => {
                try { return await next(req); } catch (e) {
                    globalThis.__caught = [e instanceof inu.PluginError, e.code, e.grant];
                    return { _: 'foo.bar' };
                }
            });
            "#,
      )
      .unwrap();
  });

  dispatch_rpc(&rt, &ctx, &state, 1, 904, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  complete_next(&rt, &ctx, &state, 904, "Pforbidden\n\n\n\nblocked by policy");

  let caught: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__caught)").unwrap());
  assert_eq!(caught, r#"[true,"forbidden",null]"#);
}

#[test]
fn registration_rejected_drops_callback() {
  let (_rt, ctx, _host, state) = setup(&["interceptRpc"]);
  *_host.register_err.borrow_mut() = Some("not granted".to_string());
  let threw = ctx.with(|ctx| ctx.eval::<(), _>("inu.interceptRpc('foo.bar', (req,next) => req);").is_err());
  assert!(threw);
  assert!(state.intercept_fns.is_empty());
}

#[test]
fn middleware_error_rejects_next_and_completes_with_error_wire() {
  let (rt, ctx, host, state) = setup(&["interceptRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.interceptRpc('foo.bar', (req, next) => {
                return next(req).catch(e => ({ _: 'foo.bar', caught: e.message }));
            });
            "#,
      )
      .unwrap();
  });

  dispatch_rpc(&rt, &ctx, &state, 1, 600, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  complete_next(&rt, &ctx, &state, 600, "Eboom");

  let completes = host.completes.borrow();
  assert_eq!(completes.len(), 1);
  assert_eq!(completes[0], (600, wire_json(r#"{"_":"foo.bar","caught":"boom"}"#)));
}

#[test]
fn throwing_interceptor_is_logged_and_completes_with_error() {
  let (rt, ctx, host, state, logs) = setup_logging(&["interceptRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.interceptRpc('foo.bar', (req, next) => {
                throw new Error('kaboom');
            });
            "#,
      )
      .unwrap();
  });

  dispatch_rpc(&rt, &ctx, &state, 1, 700, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));

  let completes = host.completes.borrow();
  assert_eq!(completes.len(), 1);
  assert_eq!(completes[0].0, 700);
  assert!(completes[0].1.starts_with('E'));
  assert!(completes[0].1.contains("kaboom"));
  drop(completes);

  assert!(
    logs
      .borrow()
      .iter()
      .any(|l| l.contains("interceptRpc(foo.bar) callback threw") && l.contains("kaboom")),
    "expected a logged diagnostic, got: {:?}",
    logs.borrow(),
  );
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

/// the 0.11 failure policy: the host stores a fault and switches the plugin off, so a
/// middleware that fails every `messages.sendMessage` has to be distinguishable from the engine
/// having a bad day - it fails the send once instead of forever
#[test]
fn a_plugin_throw_faults_where_a_host_failure_does_not() {
  let (rt, ctx, host, state, logs) = setup_logging(&["interceptRpc", "onUpdate"]);
  install_rejection_tracker(&rt, crate::testing::harness::log_sink(&logs));
  eval(
    &ctx,
    r#"
        inu.interceptRpc('foo.bar', () => { throw new Error('kaboom'); });
        inu.interceptRpc('foo.baz', async () => { throw new Error('rejected'); });
        inu.onUpdate('updateFoo', () => { throw new Error('boom'); });
        inu.onUpdate('updateBar', async () => { throw new Error('unawaited'); });
        "#,
  );
  let ids: Vec<u32> = host.registered.borrow().iter().map(|(_, id, _)| *id).collect();

  dispatch_rpc(&rt, &ctx, &state, ids[0], 700, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  dispatch_rpc(&rt, &ctx, &state, ids[1], 701, "foo.baz", 0, &wire_json(r#"{"_":"foo.baz"}"#));
  dispatch_update(&rt, &ctx, &state, "updateFoo", 0, &wire_json(r#"{"_":"updateFoo"}"#));
  dispatch_update(&rt, &ctx, &state, "updateBar", 0, &wire_json(r#"{"_":"updateBar"}"#));

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
  dispatch_update(&rt, &ctx, &state, "updateFoo", 0, "Qnope");
  assert_eq!(levels(&logs).first().map(|(level, _)| *level), Some(crate::LEVEL_ERROR), "got: {:?}", levels(&logs),);
}

/// throwing an `inu.RpcError` is the documented way to fail an intercepted request, and an
/// abandoned stage's parked next() rejects with one the plugin did not cause, so neither may
/// disable it: the first would punish using the api as written, the second would let one
/// plugin's stall switch off another
#[test]
fn an_rpc_error_out_of_a_stage_is_control_flow_not_a_fault() {
  let (rt, ctx, host, state, logs) = setup_logging(&["interceptRpc"]);
  eval(
    &ctx,
    r#"
        inu.interceptRpc('foo.bar', () => { throw new inu.RpcError(420, 'FLOOD_WAIT_3'); });
        inu.interceptRpc('foo.baz', async (req, next) => { await next(req); });
        "#,
  );
  let ids: Vec<u32> = host.registered.borrow().iter().map(|(_, id, _)| *id).collect();

  dispatch_rpc(&rt, &ctx, &state, ids[0], 800, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  // the host abandons the stage parked in await next(), exactly as a chain collapse does
  dispatch_rpc(&rt, &ctx, &state, ids[1], 801, "foo.baz", 0, &wire_json(r#"{"_":"foo.baz"}"#));
  abandon_dispatch(&rt, &ctx, &state, 801, &proxy::encode_rpc_error(-1000, "INTERCEPTOR_ABANDONED"));

  for (level, message) in levels(&logs) {
    assert_ne!(level, crate::LEVEL_FAULT, "'{message}' must not disable the plugin",);
  }
}

/// the wedge the execution deadline exists for: the app is blocked on this dispatch, so the
/// spinning stage has to be cut down *and* the request answered
#[test]
fn a_spinning_middleware_is_interrupted_and_the_request_is_still_answered() {
  let (rt, ctx, host, state, logs) = setup_logging(&["interceptRpc"]);
  crate::sandbox::limits::install_interrupt_handler(&rt, std::sync::Arc::new(|_: &str| {}));
  ctx.with(|ctx| {
    ctx.eval::<(), _>("inu.interceptRpc('foo.bar', (req, next) => { while (true) {} });").unwrap();
  });

  {
    let _deadline = crate::sandbox::limits::arm(50);
    dispatch_rpc(&rt, &ctx, &state, 1, 980, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
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

#[test]
fn rejecting_interceptor_is_logged_and_completes_with_error() {
  let (rt, ctx, host, state, logs) = setup_logging(&["interceptRpc"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.interceptRpc('foo.bar', async (req, next) => {
                throw new Error('async-boom');
            });
            "#,
      )
      .unwrap();
  });

  dispatch_rpc(&rt, &ctx, &state, 1, 800, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));

  let completes = host.completes.borrow();
  assert_eq!(completes.len(), 1);
  assert!(completes[0].1.starts_with('E'));
  assert!(completes[0].1.contains("async-boom"));
  drop(completes);

  assert!(
    logs
      .borrow()
      .iter()
      .any(|l| l.contains("interceptRpc(foo.bar) callback rejected") && l.contains("async-boom")),
    "expected a logged diagnostic, got: {:?}",
    logs.borrow(),
  );
}

/// a failing assertion must cost one failed test, not the whole binary: without disposal on the
/// unwind path the engine's still-rooted `Persistent`s reach `JS_FreeRuntime` and abort
#[test]
fn a_panicking_test_body_still_releases_its_roots() {
  let (rt, ctx, _host, state) = setup(&["interceptRpc", "invokeRpc"]);
  let engine = Rc::clone(&state);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.interceptRpc('foo.bar', (req, next) => new Promise(() => {}));
            inu.invokeRpc({ _: 'foo.baz' });
            "#,
      )
      .unwrap();
  });
  dispatch_rpc(&rt, &ctx, &state, 1, 970, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  assert_eq!(engine.intercept_fns.len(), 1);
  assert_eq!(engine.pending_invoke.borrow().len(), 1);
  assert_eq!(engine.dispatches.borrow().len(), 1);

  let panicked = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
    let _state = state;
    panic!("deliberate: stands in for a failed assertion");
  }));

  assert!(panicked.is_err());
  assert!(engine.intercept_fns.is_empty(), "disposal must run on the unwind path");
  assert!(engine.pending_invoke.borrow().is_empty());
  assert!(engine.dispatches.borrow().is_empty());
}

#[test]
fn unhandled_rejection_is_logged() {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let logs = crate::testing::harness::Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  install_rejection_tracker(&rt, log.clone());

  ctx.with(|ctx| {
    // an async handler that throws with nothing awaiting/catching it (the async onUpdate case)
    ctx.eval::<(), _>(r#"(async () => { throw new Error('nope'); })();"#).unwrap();
  });
  pump_jobs(&rt, &ctx, log.as_ref());

  assert!(
    logs.borrow().iter().any(|l| l.contains("unhandled promise rejection") && l.contains("nope")),
    "expected an unhandled-rejection diagnostic, got: {:?}",
    logs.borrow(),
  );
}

#[test]
fn a_reason_that_raises_while_being_formatted_leaves_nothing_pending() {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let logs = crate::testing::harness::Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  install_rejection_tracker(&rt, log.clone());

  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"Promise.reject({
                toString() { throw new Error('nested'); },
                get stack() { throw new Error('nested'); },
            });"#,
      )
      .unwrap();
  });
  pump_jobs(&rt, &ctx, log.as_ref());

  assert!(!logs.borrow().is_empty(), "the rejection still has to be reported");
  // the tracker returns straight into quickjs, so a raise left pending here would surface at
  // whatever unrelated call touched the context next
  ctx.with(|ctx| {
    let leftover = ctx.catch();
    assert_eq!(
      leftover.type_of(),
      rquickjs::Type::Uninitialized,
      "formatting left an exception pending: {leftover:?}"
    );
  });
}

#[test]
fn handled_rejection_is_not_logged() {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let logs = crate::testing::harness::Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  install_rejection_tracker(&rt, log.clone());

  ctx.with(|ctx| {
    ctx.eval::<(), _>(r#"Promise.reject(new Error('caught')).catch(() => {});"#).unwrap();
  });
  pump_jobs(&rt, &ctx, log.as_ref());

  assert!(logs.borrow().is_empty(), "a caught rejection must not log, got: {:?}", logs.borrow());
}

const UPDATE_TYPE: &str = "updateNewMessage";
const UPDATE_WIRE: &str = "J{\"_\":\"updateNewMessage\"}";

#[test]
fn unkeyed_on_update_registrations_stack_and_dispose_individually() {
  let (rt, ctx, host, state) = setup(&["onUpdate"]);
  eval(
    &ctx,
    r#"
        globalThis.__ran = [];
        globalThis.__d1 = inu.onUpdate('updateNewMessage', () => { __ran.push(1); });
        globalThis.__d2 = inu.onUpdate('updateNewMessage', () => { __ran.push(2); });
        "#,
  );
  assert_eq!(state.update_fns.len(), 2, "unkeyed registrations stack");

  dispatch_update(&rt, &ctx, &state, UPDATE_TYPE, 0, UPDATE_WIRE);
  assert_eq!(eval_json(&ctx, "__ran"), "[1,2]");

  let ids: Vec<u32> = host.update_registered.borrow().iter().map(|(id, _, _)| *id).collect();
  eval(&ctx, "__d1(); __d1();");
  assert_eq!(
    *host.update_unregistered.borrow(),
    vec![ids[0]],
    "a disposer called twice reaches the host once, naming its own registration",
  );

  dispatch_update(&rt, &ctx, &state, UPDATE_TYPE, 0, UPDATE_WIRE);
  assert_eq!(eval_json(&ctx, "__ran"), "[1,2,2]");

  eval(&ctx, "__d2(); __d2();");
  assert_eq!(*host.update_unregistered.borrow(), vec![ids[0], ids[1]]);
}

#[test]
fn an_update_handler_registered_during_a_dispatch_joins_the_next_one() {
  let (rt, ctx, _host, state) = setup(&["onUpdate"]);
  eval(
    &ctx,
    r#"
        globalThis.__ran = [];
        inu.onUpdate('updateNewMessage', () => {
            __ran.push('outer');
            if (!globalThis.__added) {
                globalThis.__added = true;
                inu.onUpdate('updateNewMessage', () => { __ran.push('inner'); });
            }
        });
        "#,
  );

  dispatch_update(&rt, &ctx, &state, UPDATE_TYPE, 0, UPDATE_WIRE);
  assert_eq!(eval_json(&ctx, "__ran"), r#"["outer"]"#, "the dispatch walks the snapshot it started with");

  dispatch_update(&rt, &ctx, &state, UPDATE_TYPE, 0, UPDATE_WIRE);
  assert_eq!(eval_json(&ctx, "__ran"), r#"["outer","outer","inner"]"#);
}

#[test]
fn an_update_handler_disposed_mid_dispatch_still_runs_in_that_dispatch() {
  let (rt, ctx, _host, state) = setup(&["onUpdate"]);
  eval(
    &ctx,
    r#"
        globalThis.__ran = [];
        inu.onUpdate('updateNewMessage', () => { __ran.push('first'); globalThis.__d2(); });
        globalThis.__d2 = inu.onUpdate('updateNewMessage', () => { __ran.push('second'); });
        "#,
  );

  dispatch_update(&rt, &ctx, &state, UPDATE_TYPE, 0, UPDATE_WIRE);
  assert_eq!(eval_json(&ctx, "__ran"), r#"["first","second"]"#, "the in-flight run still completes");

  dispatch_update(&rt, &ctx, &state, UPDATE_TYPE, 0, UPDATE_WIRE);
  assert_eq!(eval_json(&ctx, "__ran"), r#"["first","second","first"]"#);
}

#[test]
fn a_handler_disposing_itself_from_its_own_callback_finishes_that_call() {
  let (rt, ctx, host, state) = setup(&["onUpdate"]);
  eval(
    &ctx,
    r#"
        globalThis.__ran = [];
        globalThis.__d = inu.onUpdate('updateNewMessage', () => {
            globalThis.__d();
            globalThis.__d();
            __ran.push('done');
        });
        "#,
  );

  dispatch_update(&rt, &ctx, &state, UPDATE_TYPE, 0, UPDATE_WIRE);
  assert_eq!(eval_json(&ctx, "__ran"), r#"["done"]"#);
  assert_eq!(host.update_unregistered.borrow().len(), 1);
  assert!(state.update_fns.is_empty());

  dispatch_update(&rt, &ctx, &state, UPDATE_TYPE, 0, UPDATE_WIRE);
  assert_eq!(eval_json(&ctx, "__ran"), r#"["done"]"#);
}

#[test]
fn registering_after_unload_began_is_a_no_op_returning_a_no_op_disposer() {
  let (rt, ctx, host, state) = setup(&["onUpdate", "interceptRpc"]);
  state.lifecycle.begin_unload();
  eval(
    &ctx,
    r#"
        globalThis.__ran = [];
        globalThis.__shapes = [
            typeof inu.onUpdate('updateNewMessage', () => { __ran.push('update'); }),
            typeof inu.interceptRpc('foo.bar', (req, next) => next(req)),
        ];
        inu.onUpdate('updateNewMessage', () => {})();
        "#,
  );
  assert_eq!(eval_json(&ctx, "__shapes"), r#"["function","function"]"#);
  assert!(host.registered.borrow().is_empty(), "the host must not hear about it either");
  assert!(host.update_registered.borrow().is_empty());
  assert!(state.update_fns.is_empty());
  assert!(state.intercept_fns.is_empty());

  dispatch_update(&rt, &ctx, &state, UPDATE_TYPE, 0, UPDATE_WIRE);
  assert_eq!(eval_json(&ctx, "__ran"), "[]");
}

const NEW_MESSAGE: &str = r#"J{"_":"updateNewMessage","message":{"_":"message","id":42,"message":"hi","peer_id":{"_":"peerUser","user_id":"7"}},"pts":9}"#;

/// what the host is told a demuxed registration listens for, and what it gates it on. The scope
/// is the whole reason the upcall carries one: `onUpdate(new_message)` is not
/// `onUpdate(updateNewMessage)`, and only the host knows which the plugin declared.
#[test]
fn a_demuxed_registration_names_its_own_constructors_and_its_own_grant_scope() {
  let (_rt, ctx, host, state) = setup(&["onUpdate(new_message,edit_message,delete_message)"]);
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

/// the two halves of the `onUpdate` scope vocabulary buy different things, in both directions
#[test]
fn the_demuxed_scope_and_the_constructor_scope_do_not_imply_each_other() {
  let (_rt, ctx, host, state) = setup(&["onUpdate(updateNewMessage)"]);
  assert_eq!(
    catch_json(&ctx, "inu.onNewMessage(() => {})"),
    r#"[true,"not-granted","onUpdate(new_message)","missing grant: onUpdate(new_message)"]"#,
  );
  assert!(host.update_registered.borrow().is_empty(), "a denied event must never reach the host");
  assert!(state.update_fns.is_empty());

  let (_rt, ctx, host, state) = setup(&["onUpdate(new_message)"]);
  assert_eq!(
    catch_json(&ctx, "inu.onUpdate('updateNewMessage', () => {})"),
    r#"[true,"not-granted","onUpdate(updateNewMessage)","missing grant: onUpdate(updateNewMessage)"]"#,
  );
  assert!(host.update_registered.borrow().is_empty());
  assert!(state.update_fns.is_empty());
}

#[test]
fn an_unscoped_on_update_grant_covers_the_demuxed_events_too() {
  let (_rt, ctx, host, _state) = setup(&["onUpdate"]);
  eval(&ctx, "inu.onNewMessage(() => {});");
  assert_eq!(host.update_registered.borrow().len(), 1);
}

#[test]
fn a_new_message_arrives_as_a_message_wrapper_over_the_update_s_own_message() {
  let (rt, ctx, _host, state) = setup(&["onUpdate(new_message)"]);
  eval(
    &ctx,
    r#"
        globalThis.__seen = [];
        inu.onNewMessage((m, account) => {
            __seen.push([m instanceof inu.Message, m.id, m.text, m.dialogId, account.id, m.raw._]);
        });
        "#,
  );

  dispatch_update(&rt, &ctx, &state, "updateNewMessage", 1, NEW_MESSAGE);

  assert_eq!(eval_json(&ctx, "__seen"), r#"[[true,42,"hi",7,1,"message"]]"#);
}

/// a channel's messages arrive under their own constructor, and the whole point of the demuxed
/// form is that the plugin never has to know which of the two it was
#[test]
fn both_constructors_of_an_event_reach_one_handler() {
  let (rt, ctx, _host, state) = setup(&["onUpdate(new_message,edit_message)"]);
  eval(
    &ctx,
    r#"
        globalThis.__new = [];
        globalThis.__edited = [];
        inu.onNewMessage(m => __new.push(m.id));
        inu.onMessageEdited(m => __edited.push(m.id));
        "#,
  );

  dispatch_update(&rt, &ctx, &state, "updateNewMessage", 0, NEW_MESSAGE);
  dispatch_update(
    &rt,
    &ctx,
    &state,
    "updateNewChannelMessage",
    0,
    r#"J{"_":"updateNewChannelMessage","message":{"_":"message","id":43}}"#,
  );
  dispatch_update(
    &rt,
    &ctx,
    &state,
    "updateEditChannelMessage",
    0,
    r#"J{"_":"updateEditChannelMessage","message":{"_":"message","id":44}}"#,
  );

  assert_eq!(eval_json(&ctx, "[__new, __edited]"), "[[42,43],[44]]");
}

#[test]
fn a_deleted_message_names_its_dialog_only_when_the_update_carried_one() {
  let (rt, ctx, _host, state) = setup(&["onUpdate(delete_message)"]);
  eval(
    &ctx,
    r#"
        globalThis.__seen = [];
        inu.onMessageDeleted((dialogId, ids, account) => __seen.push([dialogId, ids, account.id]));
        "#,
  );

  dispatch_update(
    &rt,
    &ctx,
    &state,
    "updateDeleteChannelMessages",
    0,
    r#"J{"_":"updateDeleteChannelMessages","channel_id":"99","messages":[5,6]}"#,
  );
  dispatch_update(&rt, &ctx, &state, "updateDeleteMessages", 0, r#"J{"_":"updateDeleteMessages","messages":[7]}"#);

  assert_eq!(eval_json(&ctx, "__seen"), "[[-99,[5,6],0],[null,[7],0]]");
}

/// the demuxed events are a *narrowing* of the same registry, not a second stream: one
/// materialized update reaches each registration that named its constructor once, whichever
/// form registered it
#[test]
fn one_dispatch_reaches_the_raw_and_the_demuxed_handler_once_each() {
  let (rt, ctx, _host, state) = setup(&["onUpdate"]);
  eval(
    &ctx,
    r#"
        globalThis.__ran = [];
        inu.onUpdate('updateNewMessage', u => __ran.push(`raw:${u.message.id}`));
        inu.onNewMessage(m => __ran.push(`demux:${m.id}`));
        inu.onNewMessage(m => __ran.push(`demux2:${m.id}`));
        "#,
  );

  dispatch_update(&rt, &ctx, &state, "updateNewMessage", 0, NEW_MESSAGE);

  assert_eq!(eval_json(&ctx, "__ran"), r#"["raw:42","demux:42","demux2:42"]"#);
}

#[test]
fn a_demuxed_handler_ignores_the_events_it_did_not_ask_for() {
  let (rt, ctx, _host, state) = setup(&["onUpdate"]);
  eval(
    &ctx,
    r#"
        globalThis.__ran = [];
        inu.onNewMessage(() => __ran.push('new'));
        inu.onMessageDeleted(() => __ran.push('deleted'));
        "#,
  );

  dispatch_update(
    &rt,
    &ctx,
    &state,
    "updateEditMessage",
    0,
    r#"J{"_":"updateEditMessage","message":{"_":"message","id":1}}"#,
  );

  assert_eq!(eval_json(&ctx, "__ran"), "[]");
}

#[test]
fn a_demuxed_disposer_behaves_like_every_other_one() {
  let (rt, ctx, host, state) = setup(&["onUpdate"]);
  eval(
    &ctx,
    r#"
        globalThis.__ran = 0;
        globalThis.__d = inu.onNewMessage(() => { __ran++; });
        "#,
  );
  let callback_id = host.update_registered.borrow()[0].0;

  dispatch_update(&rt, &ctx, &state, "updateNewMessage", 0, NEW_MESSAGE);
  eval(&ctx, "__d(); __d();");
  dispatch_update(&rt, &ctx, &state, "updateNewMessage", 0, NEW_MESSAGE);

  assert_eq!(eval_json(&ctx, "__ran"), "1");
  assert_eq!(*host.update_unregistered.borrow(), vec![callback_id]);
  assert!(state.update_fns.is_empty());
}

#[test]
fn registering_a_demuxed_event_after_unload_began_is_a_no_op() {
  let (rt, ctx, host, state) = setup(&["onUpdate"]);
  state.lifecycle.begin_unload();
  eval(
    &ctx,
    r#"
        globalThis.__ran = 0;
        globalThis.__shape = typeof inu.onNewMessage(() => { __ran++; });
        "#,
  );

  assert_eq!(eval_json(&ctx, "__shape"), r#""function""#);
  assert!(host.update_registered.borrow().is_empty());
  assert!(state.update_fns.is_empty());

  dispatch_update(&rt, &ctx, &state, "updateNewMessage", 0, NEW_MESSAGE);
  assert_eq!(eval_json(&ctx, "__ran"), "0");
}

/// the wrapper is built from the class this engine installed, so a plugin cannot decide what
/// its own handler is handed by assigning over `inu.Message`
#[test]
fn replacing_inu_message_does_not_change_what_a_handler_receives() {
  let (rt, ctx, _host, state) = setup(&["onUpdate"]);
  eval(
    &ctx,
    r#"
        globalThis.__real = inu.Message;
        inu.Message = class Impostor { constructor() { this.id = -1 } };
        globalThis.__seen = [];
        inu.onNewMessage(m => __seen.push([m instanceof __real, m.id]));
        "#,
  );

  dispatch_update(&rt, &ctx, &state, "updateNewMessage", 0, NEW_MESSAGE);

  assert_eq!(eval_json(&ctx, "__seen"), "[[true,42]]");
}

#[test]
fn a_throwing_demuxed_handler_does_not_stop_the_next_one() {
  let (rt, ctx, _host, state, logs) = setup_logging(&["onUpdate"]);
  eval(
    &ctx,
    r#"
        globalThis.__ran = [];
        inu.onNewMessage(() => { throw new Error('boom') });
        inu.onNewMessage(m => __ran.push(m.id));
        "#,
  );

  dispatch_update(&rt, &ctx, &state, "updateNewMessage", 0, NEW_MESSAGE);

  assert_eq!(eval_json(&ctx, "__ran"), "[42]");
  assert!(logs.borrow().iter().any(|l| l.contains("callback threw")), "{:?}", logs.borrow());
}

/// the oracle is the only test the js surface gets on a device; everything it asserts is a
/// function of the updates handed to it, so it runs here against synthesised ones
#[test]
fn the_bundled_events_test_plugin_passes() {
  const ORACLE: &str = include_str!("../../../../res/assets-debug/inu_plugins/events-test.js");
  let (rt, ctx, _host, state) = setup(&crate::testing::harness::manifest_grants(ORACLE));
  let lines = crate::testing::harness::install_capturing_console(&ctx);
  ctx.with(|ctx| match ctx.eval::<(), _>(ORACLE) {
    Ok(()) => {}
    Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
    Err(e) => panic!("{e:?}"),
  });

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
    dispatch_update(&rt, &ctx, &state, type_name, 0, wire);
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
  const ORACLE: &str = include_str!("../../../../res/assets-debug/inu_plugins/accounts-test.js");
  const SWITCHED: &str = r#"[{"id":0,"userId":111,"isCurrent":false,"isPremium":false},{"id":1,"userId":222,"isCurrent":true,"isPremium":true}]"#;

  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let host = Rc::new(TestHost::default());
  let host_dyn: Rc<dyn RpcHost> = host.clone();
  let tl = TlViews::new(host.clone());
  let grant_host = TestGrantHost::new(&crate::testing::harness::manifest_grants(ORACLE));
  let log: crate::Log = std::sync::Arc::new(|_: &str| {});
  let accounts_host =
    crate::api::telegram::account::tests::TestAccountHost::with(crate::api::telegram::account::tests::TWO_ACCOUNTS);
  let accounts_dyn: Rc<dyn crate::api::telegram::account::AccountHost> = accounts_host.clone();
  let (state, accounts) = ctx.with(|ctx| {
    let inu = crate::testing::harness::inu_namespace(&ctx);
    crate::api::error::install_plugin_error(&ctx, &inu).unwrap();
    let shared = crate::api::tl::utils::install_utils(&ctx, &inu).unwrap();
    crate::api::tl::message::install_message(&ctx, &shared, &inu).unwrap();
    let accounts = crate::api::telegram::account::install_account(
      &ctx,
      accounts_dyn,
      grant_host.as_host(),
      Lifecycle::new(),
      log.clone(),
      &inu,
    )
    .unwrap();
    let state = install_rpc(
      &ctx,
      host_dyn,
      tl,
      grant_host.as_host(),
      Lifecycle::new(),
      Some(accounts.clone()),
      shared,
      log,
      &inu,
    )
    .unwrap();
    (state, accounts)
  });
  // the oracle registers onAccountsChanged and withCurrentAccount, so the account api holds
  // roots of its own and JS_FreeRuntime aborts if only the rpc half is disposed
  let _accounts_disposer =
    crate::testing::harness::DisposeOnDrop::new(&ctx, accounts.clone(), |ctx, state| state.dispose(ctx));
  let state = Disposing::new(&ctx, state, dispose);

  let lines = crate::testing::harness::install_capturing_console(&ctx);
  ctx.with(|ctx| match ctx.eval::<(), _>(ORACLE) {
    Ok(()) => {}
    Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
    Err(e) => panic!("{e:?}"),
  });

  *accounts_host.json.borrow_mut() = SWITCHED.to_string();
  accounts.accounts_changed(&rt, &ctx);
  dispatch_update(
    &rt,
    &ctx,
    &state,
    "updateUserStatus",
    1,
    &wire_json(r#"{"_":"updateUserStatus","user_id":"111","status":{"_":"userStatusOnline"}}"#),
  );

  let lines = lines.borrow().clone();
  crate::testing::harness::assert_oracle_exact(&lines, "accounts test done", 15);
}

#[test]
fn intercept_disposer_unregisters_once_and_a_later_dispatch_passes_through() {
  let (rt, ctx, host, state, logs) = setup_logging(&["interceptRpc"]);
  eval(
    &ctx,
    r#"
        globalThis.__ran = 0;
        globalThis.__d = inu.interceptRpc('foo.bar', (req, next) => { __ran++; return next(req); });
        "#,
  );
  let callback_id = host.registered.borrow()[0].1;

  eval(&ctx, "__d(); __d();");
  assert_eq!(*host.unregistered.borrow(), vec![callback_id]);
  assert!(state.intercept_fns.is_empty());

  // the host picked its chain before it saw the disposal: the stage goes transparent rather
  // than failing the app's request
  dispatch_rpc(&rt, &ctx, &state, callback_id, 980, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  assert_eq!(eval_json(&ctx, "__ran"), "0");
  assert_eq!(host.next_calls.borrow().len(), 1);
  assert!(host.completes.borrow().is_empty());

  complete_next(&rt, &ctx, &state, 980, &wire_json(r#"{"_":"foo.bar","ok":true}"#));
  let completes = host.completes.borrow();
  assert_eq!(completes.len(), 1);
  assert_eq!(completes[0], (980, wire_json(r#"{"_":"foo.bar","ok":true}"#)));
  assert!(
    logs.borrow().iter().any(|l| l.contains("disposed interceptor")),
    "expected a logged diagnostic, got: {:?}",
    logs.borrow(),
  );
}

#[test]
fn a_passthrough_dispatch_the_host_refuses_completes_with_that_error() {
  let (rt, ctx, host, state) = setup(&["interceptRpc"]);
  eval(&ctx, "globalThis.__d = inu.interceptRpc('foo.bar', (req, next) => next(req)); __d();");
  *host.next_err.borrow_mut() = Some("R420:FLOOD_WAIT_5".to_string());

  dispatch_rpc(&rt, &ctx, &state, 1, 981, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  let completes = host.completes.borrow();
  assert_eq!(completes.len(), 1);
  assert_eq!(completes[0], (981, "R420:FLOOD_WAIT_5".to_string()), "a structured wire must not be re-tagged");
}

#[test]
fn an_interceptor_disposing_itself_mid_dispatch_still_finishes_that_dispatch() {
  let (rt, ctx, host, state) = setup(&["interceptRpc"]);
  eval(
    &ctx,
    r#"
        globalThis.__d = inu.interceptRpc('foo.bar', async (req, next) => {
            globalThis.__d();
            const r = await next(req);
            return { _: 'foo.bar', finished: true };
        });
        "#,
  );

  dispatch_rpc(&rt, &ctx, &state, 1, 982, "foo.bar", 0, &wire_json(r#"{"_":"foo.bar"}"#));
  assert_eq!(host.next_calls.borrow().len(), 1, "the in-flight run keeps going after its own disposal");
  assert!(state.intercept_fns.is_empty());

  complete_next(&rt, &ctx, &state, 982, &wire_json(r#"{"_":"foo.bar"}"#));
  let completes = host.completes.borrow();
  assert_eq!(completes.len(), 1);
  assert_eq!(completes[0], (982, wire_json(r#"{"_":"foo.bar","finished":true}"#)));
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

/// runs one send through the chain and hands back `(what next() was given, what settled)`
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
  dispatch_rpc(rt, ctx, state, callback_id, dispatch_id, method, 0, &wire_json(json));
  let next = host.next_calls.borrow().get(before_next).map(|(_, wire)| wire.clone());
  let complete = host.completes.borrow().get(before_complete).map(|(_, wire)| wire.clone());
  (next, complete)
}

#[test]
fn one_outgoing_shape_covers_all_four_send_methods() {
  let (rt, ctx, host, state) = setup(&["interceptSendMessage"]);
  eval(
    &ctx,
    r#"
        globalThis.__seen = [];
        inu.interceptSendMessage((m) => {
            __seen.push({
                peer: m.peer,
                text: m.text.text,
                media: m.media.length,
                silent: m.silent,
                isEdit: m.isEdit,
                editMessageId: m.editMessageId,
            });
            return 'send';
        });
        "#,
  );
  for (method, json) in [
    ("messages.sendMessage", SEND_TEXT),
    ("messages.sendMedia", SEND_MEDIA),
    ("messages.sendMultiMedia", SEND_ALBUM),
    ("messages.editMessage", EDIT),
  ] {
    run_send(&rt, &ctx, &state, &host, method, json);
  }
  assert_eq!(
    eval_json(&ctx, "__seen"),
    r#"[{"peer":7,"text":"hi","media":0,"silent":false,"isEdit":false,"editMessageId":null},"#.to_owned()
      + r#"{"peer":-9,"text":"cap","media":1,"silent":true,"isEdit":false,"editMessageId":null},"#
      + r#"{"peer":-5,"text":"one","media":2,"silent":false,"isEdit":false,"editMessageId":null},"#
      + r#"{"peer":7,"text":"fixed","media":1,"silent":false,"isEdit":true,"editMessageId":42}]"#,
  );
}

/// a send to Saved Messages carries `inputPeerSelf`, the one peer form `peer` cannot read out
/// of the request itself. Resolving it through the account handle's own `userId` costs
/// `account.read(self)`, which this plugin does not hold - and a getter that throws here does
/// not fail the plugin, it fails the *user's* message and switches the plugin off.
#[test]
fn reading_the_peer_of_a_saved_messages_send_needs_no_grant() {
  let (rt, ctx, host, state) = setup(&["interceptSendMessage"]);
  eval(
    &ctx,
    r#"
        globalThis.__seen = null;
        inu.interceptSendMessage((m) => { __seen = m.peer; return 'send' });
        "#,
  );
  let to_self = r#"{"_":"messages.sendMessage","peer":{"_":"inputPeerSelf"},"message":"note","random_id":"1"}"#;
  let (next, complete) = run_send(&rt, &ctx, &state, &host, "messages.sendMessage", to_self);

  assert_eq!(eval_json(&ctx, "__seen"), "111", "the send's own peer was unreadable");
  assert!(next.is_some(), "the user's message never reached the network");
  assert!(complete.is_none(), "the send was answered instead of passed on: {complete:?}");
}

#[test]
fn a_rewrite_reaches_the_request_that_actually_goes_out() {
  let (rt, ctx, host, state) = setup(&["interceptSendMessage"]);
  eval(
    &ctx,
    r#"inu.interceptSendMessage((m) => {
            m.text = { text: 'rewritten', entities: [{ _: 'messageEntityBold', offset: 0, length: 2 }] };
            m.silent = true;
            m.replyToMessageId = 11;
            m.scheduleDate = 1700000000;
            return 'send';
        });"#,
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
fn a_dropped_send_never_goes_out_and_the_app_is_told_it_failed() {
  let (rt, ctx, host, state) = setup(&["interceptSendMessage"]);
  eval(&ctx, "inu.interceptSendMessage(() => 'drop');");
  let (next, complete) = run_send(&rt, &ctx, &state, &host, "messages.sendMessage", SEND_TEXT);
  assert_eq!(next, None, "a dropped send must not reach the network");
  assert_eq!(complete.as_deref(), Some("R-1000:MESSAGE_DROPPED_BY_PLUGIN"));
}

#[test]
fn a_throwing_send_middleware_drops_the_send_and_is_the_plugins_fault() {
  let (rt, ctx, host, state, logs) = setup_logging(&["interceptSendMessage"]);
  eval(&ctx, "inu.interceptSendMessage(() => { throw new Error('boom') });");
  let (next, complete) = run_send(&rt, &ctx, &state, &host, "messages.sendMessage", SEND_TEXT);
  assert_eq!(next, None);
  assert!(complete.unwrap().starts_with("E"), "a throw fails the send");
  assert_eq!(levels(&logs).first().map(|(level, _)| *level), Some(crate::LEVEL_FAULT));
}

/// the verdict is what makes the choice total, so a middleware that fell off the end must not
/// read as consent to send - and, unlike `drop`, it is a bug and says so
#[test]
fn a_send_middleware_that_returns_nothing_drops_rather_than_sending() {
  let (rt, ctx, host, state, logs) = setup_logging(&["interceptSendMessage"]);
  eval(&ctx, "inu.interceptSendMessage(() => {});");
  let (next, complete) = run_send(&rt, &ctx, &state, &host, "messages.sendMessage", SEND_TEXT);
  assert_eq!(next, None);
  assert!(complete.unwrap().contains("expected 'send' or 'drop'"));
  assert_eq!(levels(&logs).first().map(|(level, _)| *level), Some(crate::LEVEL_FAULT));
}

#[test]
fn an_async_send_middleware_is_awaited_before_the_request_goes_out() {
  let (rt, ctx, host, state) = setup(&["interceptSendMessage"]);
  eval(
    &ctx,
    r#"inu.interceptSendMessage(async (m) => {
            await Promise.resolve();
            m.text = 'awaited';
            return 'send';
        });"#,
  );
  let (next, _) = run_send(&rt, &ctx, &state, &host, "messages.sendMessage", SEND_TEXT);
  assert!(next.unwrap().contains(r#""message":"awaited""#));
}

/// `next()` refuses to rewrite the method the app is already awaiting a response type for, so a
/// length change is refused where it is decidable rather than at the bridge
#[test]
fn media_may_be_replaced_but_not_added_or_removed() {
  let (rt, ctx, host, state) = setup(&["interceptSendMessage"]);
  eval(
    &ctx,
    r#"
        globalThis.__errors = [];
        inu.interceptSendMessage((m) => {
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
fn a_topic_only_reply_does_not_read_as_a_reply_anyone_wrote() {
  let (rt, ctx, host, state) = setup(&["interceptSendMessage"]);
  eval(
    &ctx,
    r#"
        globalThis.__seen = [];
        inu.interceptSendMessage((m) => {
            __seen.push([m.replyToMessageId, m.topicId]);
            return 'send';
        });
        "#,
  );
  let in_topic = r#"{"_":"messages.sendMessage","peer":{"_":"inputPeerChannel","channel_id":"9","access_hash":"3"},"message":"hi","random_id":"1","reply_to":{"_":"inputReplyToMessage","reply_to_msg_id":20,"top_msg_id":20}}"#;
  let replying = r#"{"_":"messages.sendMessage","peer":{"_":"inputPeerChannel","channel_id":"9","access_hash":"3"},"message":"hi","random_id":"1","reply_to":{"_":"inputReplyToMessage","reply_to_msg_id":33,"top_msg_id":20}}"#;
  run_send(&rt, &ctx, &state, &host, "messages.sendMessage", in_topic);
  run_send(&rt, &ctx, &state, &host, "messages.sendMessage", replying);
  assert_eq!(eval_json(&ctx, "__seen"), "[[null,20],[33,20]]");
}

#[test]
fn interceptsendmessage_is_gated_on_its_own_grant_and_not_on_the_methods_it_covers() {
  let (_rt, ctx, _host, _state) = setup(&["interceptRpc(messages.sendMessage)"]);
  assert_eq!(
    catch_json(&ctx, "inu.interceptSendMessage(() => 'send')"),
    r#"[true,"not-granted","interceptSendMessage","missing grant: interceptSendMessage"]"#,
  );
}

#[test]
fn a_send_disposer_takes_the_stage_out_of_the_chain() {
  let (rt, ctx, host, state) = setup(&["interceptSendMessage"]);
  eval(
    &ctx,
    "globalThis.__ran = 0; globalThis.__d = inu.interceptSendMessage(() => { __ran++; return 'drop' });",
  );
  let callback_id = send_callback_id(&host);
  eval(&ctx, "__d(); __d();");
  assert_eq!(host.unregistered.borrow().as_slice(), [callback_id]);
  // the host picked its chain before it could see the disposal; the stage goes transparent
  dispatch_rpc(&rt, &ctx, &state, callback_id, 7, "messages.sendMessage", 0, &wire_json(SEND_TEXT));
  assert_eq!(eval_json(&ctx, "__ran"), "0");
  assert_eq!(host.next_calls.borrow().len(), 1, "a disposed stage passes the send through");
}

#[test]
fn the_bundled_send_intercept_test_plugin_passes() {
  const ORACLE: &str = include_str!("../../../../res/assets-debug/inu_plugins/send-intercept-test.js");
  let (rt, ctx, host, state) = setup(&crate::testing::harness::manifest_grants(ORACLE));
  let lines = crate::testing::harness::install_capturing_console(&ctx);
  ctx.with(|ctx| match ctx.eval::<(), _>(ORACLE) {
    Ok(()) => {}
    Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
    Err(e) => panic!("{e:?}"),
  });
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
  ctx.with(|ctx| match ctx.eval::<(), _>(format!("__report([{}])", sent.join(","))) {
    Ok(()) => {}
    Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
    Err(e) => panic!("{e:?}"),
  });
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
  dispatch_update_intercept(rt, ctx, state, callback_id, dispatch_id, type_name, 0, wire);
}

#[test]
fn an_update_interceptor_names_its_constructors_and_answers_a_verdict() {
  let (rt, ctx, host, state) = setup(&["interceptUpdate(updateNewMessage)"]);
  eval(
    &ctx,
    "globalThis.__seen = []; inu.interceptUpdate('updateNewMessage', (u) => { __seen.push(u.message.id); return 'deliver' });",
  );
  assert_eq!(host.intercept_update_registered.borrow()[0].1, vec!["updateNewMessage".to_string()],);
  dispatch_intercept(&rt, &ctx, &state, &host, 5, "updateNewMessage", NEW_MESSAGE);
  assert_eq!(eval_json(&ctx, "__seen"), "[42]");
  assert_eq!(host.verdicts.borrow().as_slice(), [(5, true)]);
}

#[test]
fn a_drop_verdict_reaches_the_host_as_one() {
  let (rt, ctx, host, state) = setup(&["interceptUpdate(updateNewMessage)"]);
  eval(&ctx, "inu.interceptUpdate('updateNewMessage', () => 'drop');");
  dispatch_intercept(&rt, &ctx, &state, &host, 5, "updateNewMessage", NEW_MESSAGE);
  assert_eq!(host.verdicts.borrow().as_slice(), [(5, false)]);
}

#[test]
fn an_update_view_is_writable_and_the_write_reaches_the_app_s_object() {
  let (rt, ctx, host, state) = setup(&["interceptUpdate(updateNewMessage)"]);
  host.tl_fields.borrow_mut().insert("pts".to_string(), "J9".to_string());
  eval(&ctx, "inu.interceptUpdate('updateNewMessage', (u) => { u.pts = 12; return 'deliver' });");
  dispatch_intercept(&rt, &ctx, &state, &host, 5, "updateNewMessage", "HOW77");
  assert_eq!(host.tl_fields.borrow().get("pts").map(String::as_str), Some("J12"));
  assert_eq!(host.verdicts.borrow().as_slice(), [(5, true)]);
}

/// dropping is the one verdict that desyncs pts, so a plugin's bug must never produce it
#[test]
fn a_throwing_update_middleware_delivers_and_is_the_plugins_fault() {
  let (rt, ctx, host, state, logs) = setup_logging(&["interceptUpdate(updateNewMessage)"]);
  eval(&ctx, "inu.interceptUpdate('updateNewMessage', () => { throw new Error('boom') });");
  dispatch_intercept(&rt, &ctx, &state, &host, 5, "updateNewMessage", NEW_MESSAGE);
  assert_eq!(host.verdicts.borrow().as_slice(), [(5, true)]);
  assert_eq!(levels(&logs).first().map(|(level, _)| *level), Some(crate::LEVEL_FAULT));
}

#[test]
fn a_rejecting_update_middleware_delivers_and_is_the_plugins_fault() {
  let (rt, ctx, host, state, logs) = setup_logging(&["interceptUpdate(updateNewMessage)"]);
  eval(&ctx, "inu.interceptUpdate('updateNewMessage', async () => { throw new Error('boom') });");
  dispatch_intercept(&rt, &ctx, &state, &host, 5, "updateNewMessage", NEW_MESSAGE);
  assert_eq!(host.verdicts.borrow().as_slice(), [(5, true)]);
  assert_eq!(levels(&logs).first().map(|(level, _)| *level), Some(crate::LEVEL_FAULT));
}

#[test]
fn an_unknown_update_verdict_delivers_rather_than_dropping() {
  let (rt, ctx, host, state, logs) = setup_logging(&["interceptUpdate(updateNewMessage)"]);
  eval(&ctx, "inu.interceptUpdate('updateNewMessage', () => {});");
  dispatch_intercept(&rt, &ctx, &state, &host, 5, "updateNewMessage", NEW_MESSAGE);
  assert_eq!(host.verdicts.borrow().as_slice(), [(5, true)]);
  assert_eq!(levels(&logs).first().map(|(level, _)| *level), Some(crate::LEVEL_FAULT));
}

/// the batch moved on without this stage, so a verdict that arrives afterwards must not retro-
/// drop an update the app has already applied
#[test]
fn a_middleware_settling_after_its_stage_was_abandoned_answers_nothing() {
  let (rt, ctx, host, state) = setup(&["interceptUpdate(updateNewMessage)"]);
  eval(
    &ctx,
    "globalThis.__settle = null; inu.interceptUpdate('updateNewMessage', () => new Promise((r) => { __settle = r }));",
  );
  dispatch_intercept(&rt, &ctx, &state, &host, 5, "updateNewMessage", NEW_MESSAGE);
  assert!(host.verdicts.borrow().is_empty(), "a parked middleware has not answered yet");

  abandon_update_dispatch(&rt, &ctx, &state, 5);
  eval(&ctx, "__settle('drop')");
  pump_jobs(&rt, &ctx, &|_| {});
  assert!(host.verdicts.borrow().is_empty(), "the abandoned stage answered anyway");
}

#[test]
fn a_dispatch_naming_a_disposed_update_interceptor_delivers() {
  let (rt, ctx, host, state) = setup(&["interceptUpdate(updateNewMessage)"]);
  eval(
    &ctx,
    "globalThis.__ran = 0; globalThis.__d = inu.interceptUpdate('updateNewMessage', () => { __ran++; return 'drop' });",
  );
  let callback_id = update_callback_id(&host);
  eval(&ctx, "__d(); __d();");
  assert_eq!(host.intercept_update_unregistered.borrow().as_slice(), [callback_id]);
  dispatch_update_intercept(&rt, &ctx, &state, callback_id, 5, "updateNewMessage", 0, NEW_MESSAGE);
  assert_eq!(eval_json(&ctx, "__ran"), "0");
  assert_eq!(host.verdicts.borrow().as_slice(), [(5, true)]);
}

#[test]
fn intercept_update_needs_a_scoped_grant_and_a_type_list() {
  let (_rt, ctx, _host, _state) = setup(&["interceptUpdate(updateEditMessage)"]);
  assert_eq!(
    catch_json(&ctx, "inu.interceptUpdate('updateNewMessage', () => 'deliver')"),
    r#"[true,"not-granted","interceptUpdate(updateNewMessage)","missing grant: interceptUpdate(updateNewMessage)"]"#,
  );
  assert_eq!(
    catch_json(&ctx, "inu.interceptUpdate([], () => 'deliver')"),
    r#"[false,null,null,"interceptUpdate: type list must not be empty"]"#,
  );
}

#[test]
fn registering_an_update_interceptor_after_unload_began_is_a_no_op() {
  let (_rt, ctx, host, state) = setup(&["interceptUpdate(updateNewMessage)"]);
  state.lifecycle.begin_unload();
  eval(
    &ctx,
    "globalThis.__d = inu.interceptUpdate('updateNewMessage', () => 'drop'); globalThis.__t = typeof __d; __d();",
  );
  assert_eq!(eval_json(&ctx, "__t"), r#""function""#);
  assert!(host.intercept_update_registered.borrow().is_empty());
}

#[test]
fn the_bundled_update_intercept_test_plugin_passes() {
  const ORACLE: &str = include_str!("../../../../res/assets-debug/inu_plugins/update-intercept-test.js");
  let (rt, ctx, host, state) = setup(&crate::testing::harness::manifest_grants(ORACLE));
  let lines = crate::testing::harness::install_capturing_console(&ctx);
  ctx.with(|ctx| match ctx.eval::<(), _>(ORACLE) {
    Ok(()) => {}
    Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
    Err(e) => panic!("{e:?}"),
  });
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
  ctx.with(|ctx| match ctx.eval::<(), _>(format!("__report({})", serde_verdicts(&host))) {
    Ok(()) => {}
    Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
    Err(e) => panic!("{e:?}"),
  });
  let lines = lines.borrow();
  crate::testing::harness::assert_oracle_exact(&lines, "update intercept test done", 16);
}

/// the verdicts the host recorded, as the json the oracle cross-checks its own bookkeeping
/// against - the part of the contract that only the host can see
fn serde_verdicts(host: &Rc<TestHost>) -> String {
  let verdicts: Vec<String> = host.verdicts.borrow().iter().map(|(id, deliver)| format!("[{id},{deliver}]")).collect();
  format!("[{}]", verdicts.join(","))
}

/// The oracles whose subject is a TL *view* rather than the chain: what a plugin can read, write
/// and enumerate on one. They need a handle table with nesting, vectors and per-field read-only-ness
/// that `tests::TestHost`'s single flat handle cannot express, so they get their own host - and
/// nothing else in the crate runs them, which is what
/// [`crate::testing::harness::assert_oracle_exact`]'s exact count is for.
#[cfg(test)]
mod bundled_oracles {
  use super::*;
  use crate::api::globals::RandomHost;
  use crate::api::tl::proxy::TlHost;
  use crate::sandbox::grants::TestGrantHost;
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

  /// the `_` of a request, whichever channel it arrived on: a plain object crosses as `J<json>`,
  /// a view the plugin passed straight through as a handle
  fn method_of(host: &OracleHost, request_wire: &str) -> String {
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

  /// what a handle resolves to: the node it names, and whether writes through it are refused
  type Held = (Rc<RefCell<Node>>, bool);

  #[derive(Default)]
  struct OracleHost {
    handles: RefCell<HashMap<i64, Held>>,
    next_id: Cell<i64>,
    /// what `ApiFilter.HIDDEN_FIELDS` does to a field: absent on every read path, and a write
    /// refused so the hiding is not write-through
    hidden: RefCell<HashSet<String>>,
    /// what `ApiFilter.REDACTION_EVIDENCE_FIELDS` does: writes refused, and the value handed
    /// out read-only so the verdict cannot be changed one level down either
    sealed: RefCell<HashSet<String>>,
    /// the `auth.*`/`account.*` takeover list, which no grant lifts
    refused: RefCell<HashSet<String>>,
    /// what a `next()` may not change the method away from
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

    fn tl_set(&self, handle: i64, key: &str, value_wire: &str) -> Option<String> {
      let (node, read_only) = self.lookup(handle)?;
      if read_only {
        return Some(plugin_error("forbidden", "this view is read-only"));
      }
      // the two refusals differ, and `common.d.ts` says so: a stripped field is refused the
      // way a field the type does not have is, a sealed one with `forbidden`
      if self.hidden.borrow().contains(key) {
        return Some(format!("no such field '{key}'"));
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
            .map(|(k, v)| format!("\"{k}\":{}", self.json_of(v)))
            .collect();
          format!("{{\"_\":\"{type_name}\"{}{}}}", if body.is_empty() { "" } else { "," }, body.join(","))
        }
        Node::Vector(items) => {
          format!("[{}]", items.iter().map(|v| self.json_of(v)).collect::<Vec<_>>().join(","))
        }
      }
    }

    fn json_of(&self, value: &Val) -> String {
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
    fn on_register(&self, methods: &[String], callback_id: u32, _scope: &str) -> Option<String> {
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
      let method = method_of(self, request_wire);
      if self.refused.borrow().contains(&method) {
        return Some(plugin_error("forbidden", &format!("'{method}' is not callable by a plugin")));
      }
      self.invokes.borrow_mut().push((invoke_id, slot, method));
      None
    }
    fn on_next(&self, dispatch_id: i64, request_wire: &str) -> Option<String> {
      let method = method_of(self, request_wire);
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

  /// counts up from a seed, so `crypto.getRandomValues` really writes through and two draws differ
  #[derive(Default)]
  struct CountingRandom(Cell<u8>);

  impl RandomHost for CountingRandom {
    fn random_bytes(&self, out: &mut [u8]) -> bool {
      for byte in out.iter_mut() {
        self.0.set(self.0.get().wrapping_add(1));
        *byte = self.0.get();
      }
      true
    }
  }

  type Disposing = crate::testing::harness::DisposeOnDrop<RpcState>;
  type Fixture = (Runtime, Context, Rc<OracleHost>, Disposing, std::sync::Arc<crate::testing::harness::Logs>);

  fn setup(grants: &[&str], with_globals: bool) -> Fixture {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let host = Rc::new(OracleHost::default());
    let host_dyn: Rc<dyn RpcHost> = host.clone();
    let tl = TlViews::new(host.clone());
    let grant_host = TestGrantHost::new(grants);
    let logs = crate::testing::harness::Logs::new();
    let log = crate::testing::harness::log_sink(&logs);
    let accounts_host: Rc<dyn crate::api::telegram::account::AccountHost> =
      crate::api::telegram::account::tests::TestAccountHost::with(crate::api::telegram::account::tests::TWO_ACCOUNTS);
    let state = ctx.with(|ctx| {
      let inu = crate::testing::harness::inu_namespace(&ctx);
      crate::api::error::install_plugin_error(&ctx, &inu).unwrap();
      if with_globals {
        let random: Rc<dyn RandomHost> = Rc::new(CountingRandom::default());
        crate::api::globals::install_globals(
          &ctx,
          random,
          std::path::Path::new(""),
          crate::sandbox::limits::ExternalMemory::new(),
        )
        .unwrap();
      }
      let shared = crate::api::tl::utils::install_utils(&ctx, &inu).unwrap();
      crate::api::tl::message::install_message(&ctx, &shared, &inu).unwrap();
      let accounts = crate::api::telegram::account::install_account(
        &ctx,
        accounts_host,
        grant_host.as_host(),
        Lifecycle::new(),
        log.clone(),
        &inu,
      )
      .unwrap();
      install_rpc(&ctx, host_dyn, tl, grant_host.as_host(), Lifecycle::new(), Some(accounts), shared, log, &inu)
        .unwrap()
    });
    let state = Disposing::new(&ctx, state, dispose);
    (rt, ctx, host, state, logs)
  }

  fn run_oracle(ctx: &Context, source: &str) {
    ctx.with(|ctx| match ctx.eval::<(), _>(source) {
      Ok(()) => {}
      Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
      Err(e) => panic!("{e:?}"),
    });
  }

  const GLOBALS_ORACLE: &str = include_str!("../../../../res/assets-debug/inu_plugins/globals-test.js");
  const LAZY_TL_ORACLE: &str = include_str!("../../../../res/assets-debug/inu_plugins/lazy-tl-test.js");
  const TAKEOVER_ORACLE: &str = include_str!("../../../../res/assets-debug/inu_plugins/takeover-test.js");
  const API_FILTER_ORACLE: &str = include_str!("../../../../res/assets-debug/inu_plugins/api-filter-test.js");

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
    run_oracle(&ctx, GLOBALS_ORACLE);

    let (invoke_id, _, _) = host.invokes.borrow()[0].clone();
    let view = host.mint(&config_node(), false);
    resolve_invoke(&rt, &ctx, &state, invoke_id, &view);

    let lines = lines.borrow();
    crate::testing::harness::assert_oracle_exact(&lines, "globals test done", 38);
  }

  #[test]
  fn the_bundled_lazy_tl_test_plugin_passes() {
    let (rt, ctx, host, state, _logs) = setup(&crate::testing::harness::manifest_grants(LAZY_TL_ORACLE), false);
    let lines = crate::testing::harness::install_capturing_console(&ctx);
    run_oracle(&ctx, LAZY_TL_ORACLE);

    // the invoke settles first: the update half's last assertion is that a read-only view is
    // refused as a *writable* view's field value, and it needs one to try it on
    let (invoke_id, _, _) = host.invokes.borrow()[0].clone();
    let config = host.mint(&config_node(), false);
    resolve_invoke(&rt, &ctx, &state, invoke_id, &config);

    let update = object(
      "updateNewMessage",
      &[("pts", wire("I7")), ("message", node(&object("message", &[("id", wire("I42"))])))],
    );
    let update_wire = host.mint(&update, true);
    dispatch_update(&rt, &ctx, &state, "updateNewMessage", 0, &update_wire);

    let lines = lines.borrow();
    crate::testing::harness::assert_oracle_exact(&lines, "lazy tl test done", 10);
  }

  /// The list itself is the host's (`TakeoverMethods`, pinned by `:InuCore`'s own suite); what
  /// this covers is the half only an engine can: a `P` wire on each of the three channels a
  /// refusal can arrive over - `on_invoke`, `on_register` and the `String?` error channel
  /// `on_next` - reaching plugin code as an `inu.PluginError` with the code the host named.
  #[test]
  fn the_bundled_takeover_test_plugin_passes() {
    let (rt, ctx, host, state, _logs) = setup(&crate::testing::harness::manifest_grants(TAKEOVER_ORACLE), false);
    for method in ["auth.exportLoginToken", "auth.signIn", "account.getAuthorizations", "account.deleteAccount"] {
      host.refused.borrow_mut().insert(method.to_string());
    }
    *host.chain_method.borrow_mut() = "help.getConfig".to_string();
    let lines = crate::testing::harness::install_capturing_console(&ctx);
    run_oracle(&ctx, TAKEOVER_ORACLE);
    pump_jobs(&rt, &ctx, state.log.as_ref());

    let callback_id = host.registered.borrow()[0].1;
    let request = host.mint(&object("help.getConfig", &[]), false);
    dispatch_rpc(&rt, &ctx, &state, callback_id, 500, "help.getConfig", 0, &request);
    let response = host.mint(&config_node(), false);
    complete_next(&rt, &ctx, &state, 500, &response);

    let lines = lines.borrow();
    crate::testing::harness::assert_oracle_exact(&lines, "takeover test done", 6);
  }

  /// Same division: `ApiFilter`'s field lists are the host's, and what runs here is the half
  /// that lives in the view - that `in`, `Object.keys`, a read, a `toJSON()` snapshot and an
  /// assignment all agree with what the host said about a field, rather than three of the five
  /// agreeing and the plugin reading the token off the fourth.
  #[test]
  fn the_bundled_api_filter_test_plugin_passes() {
    let (rt, ctx, host, state, _logs) = setup(&crate::testing::harness::manifest_grants(API_FILTER_ORACLE), false);
    host.hidden.borrow_mut().insert("autologin_token".to_string());
    for field in ["from_id", "peer_id", "fwd_from", "out"] {
      host.sealed.borrow_mut().insert(field.to_string());
    }
    let lines = crate::testing::harness::install_capturing_console(&ctx);
    run_oracle(&ctx, API_FILTER_ORACLE);

    for (invoke_id, _, method) in host.invokes.borrow().clone() {
      let answer = match method.as_str() {
        "help.getConfig" => host.mint(&config_node(), false),
        "messages.getHistory" => host.mint(&history_node(), false),
        other => panic!("the oracle asked for {other}"),
      };
      resolve_invoke(&rt, &ctx, &state, invoke_id, &answer);
    }

    let update = object("updateNewMessage", &[("message", node(&service_message_node()))]);
    let update_wire = host.mint(&update, true);
    dispatch_update(&rt, &ctx, &state, "updateNewMessage", 0, &update_wire);

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

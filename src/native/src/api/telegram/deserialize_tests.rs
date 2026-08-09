use super::*;
use crate::api::error::install_plugin_error;
use crate::api::tl::proxy::TlHost;
use crate::sandbox::grants::TestGrantHost;
use rquickjs::{Context, Runtime};
use std::cell::RefCell;
use std::collections::HashMap;

#[derive(Default)]
pub(super) struct TestHost {
  pub(super) registered: RefCell<Vec<(u32, String)>>,
  pub(super) unregistered: RefCell<Vec<u32>>,
  pub(super) refuse_with: RefCell<Option<String>>,
  pub(super) middlewares: RefCell<Vec<(u32, String)>>,
  pub(super) middlewares_gone: RefCell<Vec<u32>>,
}

impl DeserializeHost for TestHost {
  fn on_rules_register(&self, callback_id: u32, rules_json: &str) -> Option<String> {
    if let Some(err) = self.refuse_with.borrow().clone() {
      return Some(err);
    }
    self.registered.borrow_mut().push((callback_id, rules_json.to_string()));
    None
  }

  fn on_rules_unregister(&self, callback_id: u32) {
    self.unregistered.borrow_mut().push(callback_id);
  }

  fn on_middleware_register(&self, callback_id: u32, types_json: &str) -> Option<String> {
    if let Some(err) = self.refuse_with.borrow().clone() {
      return Some(err);
    }
    self.middlewares.borrow_mut().push((callback_id, types_json.to_string()));
    None
  }

  fn on_middleware_unregister(&self, callback_id: u32) {
    self.middlewares_gone.borrow_mut().push(callback_id);
  }
}

/// one writable `user`, so a middleware dispatch can be asserted on what it wrote rather than
/// on having been called
#[derive(Default)]
pub(super) struct TestTl {
  fields: RefCell<HashMap<String, String>>,
}

impl TlHost for TestTl {
  fn tl_get(&self, _handle: i64, key: &str) -> String {
    if key == "_" {
      return "Suser".to_string();
    }
    self.fields.borrow().get(key).cloned().unwrap_or_else(|| "N".to_string())
  }

  fn tl_set(&self, _handle: i64, key: &str, value_wire: &str) -> Option<String> {
    self.fields.borrow_mut().insert(key.to_string(), value_wire.to_string());
    None
  }

  fn tl_has(&self, _handle: i64, key: &str) -> i32 {
    i32::from(self.fields.borrow().contains_key(key))
  }

  fn tl_own_keys(&self, _handle: i64) -> Option<String> {
    Some(self.fields.borrow().keys().cloned().collect::<Vec<_>>().join("\n"))
  }

  fn tl_copy(&self, _handle: i64) -> Option<String> {
    Some(r#"{"_":"user"}"#.to_string())
  }

  fn tl_release(&self, _handle: i64) {}
}

struct Fixture {
  _rt: Runtime,
  ctx: Context,
  host: Rc<TestHost>,
  lifecycle: Rc<Lifecycle>,
  state: Rc<DeserializeState>,
  tl: Rc<TestTl>,
  logs: std::sync::Arc<std::sync::Mutex<Vec<String>>>,
}

impl Drop for Fixture {
  fn drop(&mut self) {
    dispose(&self.ctx, &self.state);
  }
}

fn setup(grants: &[&str]) -> Fixture {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let host = Rc::new(TestHost::default());
  let host_dyn: Rc<dyn DeserializeHost> = host.clone();
  let lifecycle = Lifecycle::new();
  let grants = TestGrantHost::new(grants).as_host();
  let tl = Rc::new(TestTl::default());
  let views = TlViews::new(tl.clone() as Rc<dyn TlHost>);
  let logs = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
  let sink = logs.clone();
  let log: std::sync::Arc<dyn Fn(&str) + Send + Sync> =
    std::sync::Arc::new(move |line: &str| sink.lock().unwrap().push(line.to_string()));
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::inu_namespace(&ctx);
    install_plugin_error(&ctx, &inu).unwrap();
    install_deserialize(&ctx, host_dyn, grants, lifecycle.clone(), views, log, &inu).unwrap()
  });
  Fixture {
    _rt: rt,
    ctx,
    host,
    lifecycle,
    state,
    tl,
    logs,
  }
}

/// `[code, message]` of whatever `code` threw, or `["", ""]` when it did not throw
fn catch(f: &Fixture, code: &str) -> (String, String) {
  f.ctx.with(|ctx| {
    let script = format!(
      r#"(() => {{
                try {{ {code}; return ['', '']; }}
                catch (e) {{ return [e instanceof inu.PluginError ? e.code : 'not-a-PluginError', String(e.message)]; }}
            }})()"#
    );
    let pair: Vec<String> = ctx.eval(script).unwrap();
    (pair[0].clone(), pair[1].clone())
  })
}

fn eval(f: &Fixture, code: &str) -> String {
  crate::testing::harness::eval_string(&f.ctx, code)
}

#[test]
fn a_rule_crosses_as_normalized_json() {
  let f = setup(&[GRANT]);
  eval(
    &f,
    "inu.interceptDeserialize([{ type: 'user', when: { self: true }, set: { premium: true } }]), 'ok'",
  );
  assert_eq!(
    f.host.registered.borrow()[0].1,
    r#"[{"type":["user"],"when":{"self":true},"set":{"premium":true}}]"#,
  );
}

#[test]
fn every_constant_kind_survives_the_wire() {
  let f = setup(&[GRANT]);
  eval(
    &f,
    "inu.interceptDeserialize([{ type: ['user', 'channel'], set: { \
         a: 'x\"y\\n', b: 7, c: 1.5, d: false, e: null, g: '12345678901234567' } }]), 'ok'",
  );
  assert_eq!(
    f.host.registered.borrow()[0].1,
    r#"[{"type":["user","channel"],"when":{},"set":{"a":"x\"y\n","b":7,"c":1.5,"d":false,"e":null,"g":"12345678901234567"}}]"#,
  );
}

/// a rules object may carry getters, so anything re-read after the grant check could say
/// something else the second time. What crosses is built from the read that was checked.
#[test]
fn the_rule_is_read_once_and_the_wire_is_built_from_that_read() {
  let f = setup(&[GRANT]);
  let reads: i32 = f.ctx.with(|ctx| {
    ctx
      .eval(
        r#"(() => {
                let reads = 0;
                const rule = {
                    get type() { return ++reads === 1 ? 'user' : 'auth.exportLoginToken' },
                    set: { premium: true },
                };
                inu.interceptDeserialize([rule]);
                return reads;
            })()"#,
      )
      .unwrap()
  });
  assert_eq!(reads, 1);
  assert!(f.host.registered.borrow()[0].1.contains(r#""type":["user"]"#));
}

#[test]
fn the_middleware_form_hands_the_host_the_constructors_it_named() {
  let f = setup(&[GRANT]);
  f.ctx.with(|ctx| {
    ctx.eval::<(), _>("globalThis.__d = inu.interceptDeserialize(['user', 'chat'], () => {})").unwrap();
  });
  assert_eq!(f.host.middlewares.borrow().len(), 1);
  assert_eq!(f.host.middlewares.borrow()[0].1, r#"["user","chat"]"#);
  // one token space with the rule form, so neither tier can answer to the other's id
  assert!(f.host.registered.borrow().is_empty());
  f.ctx.with(|ctx| ctx.eval::<(), _>("__d(); __d()").unwrap());
  assert_eq!(*f.host.middlewares_gone.borrow(), vec![1], "disposing twice is one upcall");
}

/// the dispatch a device does per parsed object: the middleware is handed a writable view, and
/// what it writes is what the host is told to write - there is no return value to honour
#[test]
fn a_dispatch_reaches_the_middleware_and_its_writes_cross() {
  let f = setup(&[GRANT]);
  f.ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"globalThis.__seen = [];
               inu.interceptDeserialize(['user'], (user) => { __seen.push(user._); user.premium = true })"#,
      )
      .unwrap();
  });
  dispatch_middleware(&f.ctx, &f.state, 1, "HOW7");
  f.ctx.with(|ctx| {
    let seen: Vec<String> = ctx.eval("__seen").unwrap();
    assert_eq!(seen, vec!["user".to_string()]);
  });
  assert!(f.tl.fields.borrow().contains_key("premium"), "{:?}", f.tl.fields.borrow());
}

#[test]
fn a_disposed_middleware_is_never_dispatched_again() {
  let f = setup(&[GRANT]);
  f.ctx.with(|ctx| {
    ctx
      .eval::<(), _>("globalThis.__runs = 0; globalThis.__d = inu.interceptDeserialize(['user'], () => { __runs++ })")
      .unwrap();
  });
  dispatch_middleware(&f.ctx, &f.state, 1, "HOW7");
  f.ctx.with(|ctx| ctx.eval::<(), _>("__d()").unwrap());
  dispatch_middleware(&f.ctx, &f.state, 1, "HOW7");
  f.ctx.with(|ctx| assert_eq!(ctx.eval::<i32, _>("__runs").unwrap(), 1));
}

/// a middleware is plugin code the app is blocked on, so a throw out of one is the plugin's
/// fault in the sense `lib.rs` means it - the row of `crate::fault(` call sites `CLAUDE.md`
/// derives the disable list from
#[test]
fn a_throwing_middleware_is_a_fault() {
  let f = setup(&[GRANT]);
  f.ctx.with(|ctx| {
    ctx.eval::<(), _>("inu.interceptDeserialize(['user'], () => { throw new Error('boom') })").unwrap();
  });
  dispatch_middleware(&f.ctx, &f.state, 1, "HOW7");
  let logs = f.logs.lock().unwrap();
  assert!(logs.iter().any(|line| line.starts_with("\u{1}") && line.contains("boom")), "{logs:?}",);
}

#[test]
fn a_middleware_that_is_not_a_function_is_refused() {
  let f = setup(&[GRANT]);
  assert_eq!(catch(&f, "inu.interceptDeserialize(['user'], 'nope')").0, "invalid-argument");
  assert!(f.host.middlewares.borrow().is_empty());
}

#[test]
fn a_middleware_names_at_least_one_constructor_and_only_constructors() {
  let f = setup(&[GRANT]);
  assert_eq!(catch(&f, "inu.interceptDeserialize([], () => {})").0, "invalid-argument");
  assert_eq!(catch(&f, "inu.interceptDeserialize([7], () => {})").0, "invalid-argument");
  assert_eq!(catch(&f, "inu.interceptDeserialize('user', () => {})").0, "invalid-argument");
  assert!(f.host.middlewares.borrow().is_empty());
}

#[test]
fn a_middleware_over_an_ungranted_type_is_not_granted() {
  let f = setup(&["interceptDeserialize(user)"]);
  assert_eq!(catch(&f, "inu.interceptDeserialize(['chat'], () => {})").0, "not-granted");
  assert!(f.host.middlewares.borrow().is_empty());
}

/// both tiers spend the same budget, because both cost the app the same thing: one more
/// constructor id the parse of every object is tested against
#[test]
fn a_middleware_counts_against_the_same_rule_budget() {
  let f = setup(&[GRANT]);
  let types: Vec<String> = (0..MAX_RULES).map(|_| "'user'".to_string()).collect();
  f.ctx.with(|ctx| {
    ctx.eval::<(), _>(format!("inu.interceptDeserialize([{}], () => {{}})", types.join(","))).unwrap();
  });
  assert_eq!(catch(&f, "inu.interceptDeserialize(['user'], () => {})").0, "quota-exceeded");
  assert_eq!(
    catch(&f, "inu.interceptDeserialize([{ type: 'user', set: { premium: true } }])").0,
    "quota-exceeded",
  );
}

#[test]
fn a_middleware_registered_after_unload_began_is_a_no_op() {
  let f = setup(&[GRANT]);
  f.lifecycle.begin_unload();
  f.ctx.with(|ctx| {
    ctx.eval::<(), _>("inu.interceptDeserialize(['user'], () => {})()").unwrap();
  });
  assert!(f.host.middlewares.borrow().is_empty());
}

#[test]
fn a_type_outside_the_grant_scope_is_not_granted() {
  let f = setup(&["interceptDeserialize(user)"]);
  assert_eq!(catch(&f, "inu.interceptDeserialize([{ type: 'user', set: { premium: true } }])").0, "",);
  let (code, message) = catch(&f, "inu.interceptDeserialize([{ type: ['user', 'message'], set: { pinned: true } }])");
  assert_eq!(code, "not-granted");
  assert_eq!(message, "missing grant: interceptDeserialize(message)");
  assert_eq!(f.host.registered.borrow().len(), 1, "nothing crosses for a refused rule set");
}

#[test]
fn the_shape_refusals_all_name_the_rule_that_broke() {
  let f = setup(&[GRANT]);
  for (code, expected) in [
    ("inu.interceptDeserialize('user')", "the declarative form takes an array of rules"),
    ("inu.interceptDeserialize([])", "a rule set with no rules in it"),
    ("inu.interceptDeserialize([1])", "rules[0] is not a rule object"),
    ("inu.interceptDeserialize([[]])", "rules[0] is not a rule object"),
    (
      "inu.interceptDeserialize([{ type: 'user', where: { self: true }, set: { premium: true } }])",
      "rules[0] carries an unknown key 'where'",
    ),
    (
      "inu.interceptDeserialize([{ set: { premium: true } }])",
      "rules[0].type must be a constructor name or an array of them",
    ),
    (
      "inu.interceptDeserialize([{ type: [], set: { premium: true } }])",
      "rules[0].type names no constructor",
    ),
    (
      "inu.interceptDeserialize([{ type: [1], set: { premium: true } }])",
      "rules[0].type must contain only constructor names",
    ),
    (
      "inu.interceptDeserialize([{ type: 'user' }])",
      "rules[0].set must be an object of field names to constants",
    ),
    ("inu.interceptDeserialize([{ type: 'user', set: {} }])", "rules[0].set rewrites nothing"),
    (
      "inu.interceptDeserialize([{ type: 'user', set: { premium: {} } }])",
      "rules[0].set.premium must be a string, number, boolean or null",
    ),
    (
      "inu.interceptDeserialize([{ type: 'user', set: { premium: undefined } }])",
      "rules[0].set.premium must be a string, number, boolean or null",
    ),
    (
      "inu.interceptDeserialize([{ type: 'user', set: { premium: 1/0 } }])",
      "rules[0].set.premium is not a finite number",
    ),
    (
      "inu.interceptDeserialize([{ type: 'user', when: 5, set: { premium: true } }])",
      "rules[0].when must be an object of field names to constants",
    ),
  ] {
    let (thrown, message) = catch(&f, code);
    assert_eq!(thrown, "invalid-argument", "{code}");
    assert!(message.contains(expected), "{code}: got {message}");
  }
  assert!(f.host.registered.borrow().is_empty(), "a refused rule set must not cross");
}

#[test]
fn a_host_refusal_reaches_the_plugin_as_its_own_error() {
  let f = setup(&[GRANT]);
  *f.host.refuse_with.borrow_mut() =
    Some("Pforbidden\n\n\n\ninterceptDeserialize: 'access_hash' addresses the object".to_string());
  let (code, message) = catch(&f, "inu.interceptDeserialize([{ type: 'user', set: { access_hash: '1' } }])");
  assert_eq!(code, "forbidden");
  assert!(message.contains("addresses the object"), "{message}");
  assert!(f.host.registered.borrow().is_empty(), "a refused registration must not be recorded");
}

/// a registration the host refused must leave nothing behind, or its rules count against
/// [`MAX_RULES`] forever
#[test]
fn a_refused_registration_holds_no_rules() {
  let f = setup(&[GRANT]);
  *f.host.refuse_with.borrow_mut() = Some("no".to_string());
  catch(&f, "inu.interceptDeserialize([{ type: 'user', set: { premium: true } }])");
  *f.host.refuse_with.borrow_mut() = None;
  let one = format!("[{}]", vec!["{ type: 'user', set: { premium: true } }"; MAX_RULES].join(","));
  assert_eq!(catch(&f, &format!("inu.interceptDeserialize({one})")).0, "");
}

#[test]
fn the_rule_ceiling_counts_every_live_registration() {
  let f = setup(&[GRANT]);
  let rules = |n: usize| format!("[{}]", vec!["{ type: 'user', set: { premium: true } }"; n].join(","));
  eval(&f, &format!("globalThis.d = inu.interceptDeserialize({}), 'ok'", rules(MAX_RULES - 1)));
  assert_eq!(catch(&f, &format!("inu.interceptDeserialize({})", rules(1))).0, "");
  let (code, message) = catch(&f, &format!("inu.interceptDeserialize({})", rules(1)));
  assert_eq!(code, "quota-exceeded");
  assert!(message.contains(&format!("at most {MAX_RULES} rules")), "{message}");

  // disposing gives the budget back, or a plugin that reloads its rules can never do it twice
  eval(&f, "d(), 'ok'");
  assert_eq!(catch(&f, &format!("inu.interceptDeserialize({})", rules(MAX_RULES - 2))).0, "");
}

#[test]
fn the_disposer_unregisters_once() {
  let f = setup(&[GRANT]);
  eval(&f, "globalThis.d = inu.interceptDeserialize([{ type: 'user', set: { premium: true } }]), 'ok'");
  let callback_id = f.host.registered.borrow()[0].0;
  eval(&f, "d(), d(), d(), 'ok'");
  assert_eq!(*f.host.unregistered.borrow(), vec![callback_id]);
}

#[test]
fn registering_after_unload_began_registers_nothing() {
  let f = setup(&[GRANT]);
  f.lifecycle.begin_unload();
  eval(&f, "globalThis.d = inu.interceptDeserialize([{ type: 'user', set: { premium: true } }]), 'ok'");
  assert!(f.host.registered.borrow().is_empty());
  eval(&f, "d(), 'ok'");
  assert!(f.host.unregistered.borrow().is_empty(), "the no-op disposer answers to nothing");
}

/// The bundled oracle, which is the only thing that runs this surface on a device. What the *host*
/// refuses is `PluginDeserialize`'s and is pinned by the bridge suite; what this drives is the half
/// the engine decides, plus the one channel they share: a host refusal arriving as the plugin error
/// the host named.
#[cfg(test)]
mod bundled_oracle {
  use super::*;
  use crate::api::error::install_plugin_error;
  use crate::sandbox::grants::TestGrantHost;
  use rquickjs::{Context, Runtime};
  use std::cell::RefCell;

  const ORACLE: &str = include_str!("../../../../res/assets-debug/inu_plugins/deserialize-test.js");

  /// the refusals the oracle asks the host for, and the only thing this double decides. Every one
  /// of them is a verdict the real `PluginDeserialize` reaches for the same rule and the bridge
  /// suite pins one by one (`PluginDeserializeTest`), so what is doubled here is the wire the
  /// plugin catches rather than the decision behind it - which is why each arm keys on the rule
  /// the oracle actually sends.
  #[derive(Default)]
  struct OracleHost {
    unregistered: RefCell<Vec<u32>>,
  }

  impl DeserializeHost for OracleHost {
    fn on_rules_register(&self, _callback_id: u32, rules_json: &str) -> Option<String> {
      let refusal = |code: &str, message: &str| Some(format!("P{code}\n\n\n\n{message}"));
      if rules_json.contains("\"nope\"") {
        return refusal("invalid-argument", "interceptDeserialize: 'user' has no field 'nope'");
      }
      if rules_json.contains("\"access_hash\"") {
        return refusal("forbidden", "interceptDeserialize: 'access_hash' addresses the object");
      }
      if rules_json.contains("\"flags\"") {
        return refusal("forbidden", "interceptDeserialize: 'flags' is managed by the bridge");
      }
      if rules_json.contains("\"encryptedMessage\"") {
        return refusal("forbidden", "interceptDeserialize: 'encryptedMessage' is secret-chat traffic");
      }
      if rules_json.contains("\"when\":{\"message\"") {
        return refusal("forbidden", "interceptDeserialize: matching on a value is reading it");
      }
      None
    }

    fn on_rules_unregister(&self, callback_id: u32) {
      self.unregistered.borrow_mut().push(callback_id);
    }

    fn on_middleware_register(&self, _callback_id: u32, _types_json: &str) -> Option<String> {
      None
    }

    fn on_middleware_unregister(&self, _callback_id: u32) {}
  }

  #[test]
  fn the_bundled_deserialize_test_plugin_passes() {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let host = Rc::new(OracleHost::default());
    let host_dyn: Rc<dyn DeserializeHost> = host.clone();
    let grants = TestGrantHost::new(&crate::testing::harness::manifest_grants(ORACLE)).as_host();
    ctx.with(|ctx| {
      let inu = crate::testing::harness::inu_namespace(&ctx);
      install_plugin_error(&ctx, &inu).unwrap();
      let tl = TlViews::new(Rc::new(super::tests::TestTl::default()) as Rc<dyn crate::api::tl::proxy::TlHost>);
      let log: std::sync::Arc<dyn Fn(&str) + Send + Sync> = std::sync::Arc::new(|_: &str| {});
      install_deserialize(&ctx, host_dyn, grants, Lifecycle::new(), tl, log, &inu).unwrap();
    });

    let lines = crate::testing::harness::run_capturing_console(&rt, &ctx, ORACLE);
    crate::testing::harness::assert_oracle_exact(&lines, "deserialize test done", 21);
    assert_eq!(host.unregistered.borrow().len(), 2, "the oracle disposes both of its registrations");
  }
}

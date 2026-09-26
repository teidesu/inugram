use super::*;
use crate::runtime::pump_jobs;
use crate::testing::harness::{install_sandbox_globals, TestDir};
use rquickjs::{Context, Runtime};
use std::cell::Cell;
use std::cell::RefCell;

/// Mirrors `PluginPermissions.allows(..., ScopeMatch.DOMAIN)`, which is what really answers
/// `onCheckGrant` here. The shared `CachedGrantHost` compares scopes literally, and a fixture that
/// did that would call `fetch(example.com)` a refusal of `api.example.com` - passing the
/// subdomain rule by never running it.
pub(crate) struct TestDomainGrants {
  scopes: Option<Vec<String>>,
}

impl TestDomainGrants {
  pub(crate) fn new(token: Option<&str>) -> Rc<Self> {
    let scopes = token.map(|token| match token.split_once('(') {
      Some((_, rest)) => rest.trim_end_matches(')').split(',').map(|s| s.trim().to_string()).collect(),
      None => Vec::new(),
    });
    Rc::new(TestDomainGrants { scopes })
  }

  pub(crate) fn as_host(self: &Rc<Self>) -> Rc<dyn GrantHost> {
    self.clone()
  }
}

impl GrantHost for TestDomainGrants {
  fn is_granted(&self, name: &str, target: Option<&str>, mode: i32) -> bool {
    if name != "fetch" {
      return false;
    }
    let Some(scopes) = self.scopes.as_ref() else {
      return false;
    };
    if scopes.is_empty() {
      return true;
    }
    assert_eq!(mode, MATCH_DOMAIN, "the fetch grant is domain-matched");
    let Some(target) = target else { return false };
    scopes.iter().any(|scope| target == scope || target.ends_with(&format!(".{scope}")))
  }
}

struct Sent {
  id: i64,
  url: String,
  spec: String,
  body: Option<Vec<u8>>,
}

#[derive(Default)]
struct TestFetchHost {
  sent: RefCell<Vec<Sent>>,
  aborted: RefCell<Vec<i64>>,
}

impl FetchHost for TestFetchHost {
  fn send(&self, request_id: i64, url: &str, spec: &Spec, body: Option<&[u8]>) -> Option<String> {
    self.sent.borrow_mut().push(Sent {
      id: request_id,
      url: url.to_string(),
      spec: format!("{} {} {:?}", spec.method, spec.redirect, spec.headers),
      body: body.map(<[u8]>::to_vec),
    });
    None
  }

  fn abort(&self, request_id: i64) {
    self.aborted.borrow_mut().push(request_id);
  }
}

type Disposing = crate::testing::harness::DisposeOnDrop<FetchState>;
type DisposingTimers = crate::testing::harness::DisposeOnDrop<crate::api::timers::TimerState>;

/// the wheel `timeout` is measured on, with a clock the test moves. `schedule_wake` is a no-op
/// because there is no queue here to post the wake to - [`Fixture::advance`] is that queue.
#[derive(Default)]
pub(crate) struct TestClock {
  now: Cell<u64>,
}

impl TestClock {
  pub(crate) fn advance(&self, millis: u64) {
    self.now.set(self.now.get() + millis);
  }
}

impl crate::api::timers::TimerHost for TestClock {
  fn schedule_wake(&self, _delay_ms: i64) {}

  fn now_ms(&self) -> u64 {
    self.now.get()
  }
}

struct Fixture {
  _rt: Runtime,
  ctx: Context,
  host: Rc<TestFetchHost>,
  dir: TestDir,
  clock: Rc<TestClock>,
  timers: DisposingTimers,
  state: Disposing,
}

impl Fixture {
  fn advance(&self, millis: u64) {
    self.clock.advance(millis);
    self.timers.run_due(&self.ctx);
  }
}

fn setup(grant: Option<&str>) -> Fixture {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let dir = TestDir::new("fetch");
  let host = Rc::new(TestFetchHost::default());
  let host_dyn: Rc<dyn FetchHost> = host.clone();
  let clock = Rc::new(TestClock::default());
  let clock_dyn: Rc<dyn crate::api::timers::TimerHost> = clock.clone();
  let grants = TestDomainGrants::new(grant).as_host();
  let log: crate::Log = std::sync::Arc::new(|_| {});
  let (timers, state) = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    install_sandbox_globals(&ctx, dir.path()).unwrap();
    let timers =
      crate::api::timers::install_timers(&ctx, clock_dyn, crate::sandbox::registry::Lifecycle::new(), log.clone())
        .unwrap();
    let state = install_fetch(&ctx, host_dyn, grants, log.clone(), &inu).unwrap();
    (timers, state)
  });
  let timers = DisposingTimers::new(&ctx, timers, |ctx, state| state.dispose(ctx));
  let state = Disposing::new(&ctx, state, |ctx, state| state.dispose(ctx));
  Fixture {
    _rt: rt,
    ctx,
    host,
    dir,
    clock,
    timers,
    state,
  }
}

fn eval(f: &Fixture, code: &str) -> String {
  crate::testing::harness::eval_string(&f.ctx, code)
}

fn run(f: &Fixture, code: &str) {
  crate::testing::harness::eval_unit(&f.ctx, code);
  pump_jobs(&f.ctx, &|_| {});
}

fn start(f: &Fixture, call: &str) {
  run(
    f,
    &format!(
      r#"
        globalThis.__out = null
        globalThis.__res = null
        ;{call}.then(
          r => {{ globalThis.__res = r; globalThis.__out = 'ok' }},
          e => {{ globalThis.__out = `${{e.name}}|${{e.code}}|${{e.message}}` }},
        )
      "#,
    ),
  );
}

fn out(f: &Fixture) -> String {
  eval(f, "String(globalThis.__out)")
}

fn answer(f: &Fixture, request_id: i64, status: i32, headers: &str, body: &str) {
  let path = f.dir.path().join(format!("body-{request_id}"));
  std::fs::write(&path, body).unwrap();
  let wire = format!(
    r#"J{{"status":{status},"statusText":"OK","url":"https://api.example.com/x","headers":{headers},"body":{{"path":{:?},"type":"text/plain"}}}}"#,
    path.to_string_lossy(),
  );
  f.state.settle(&f.ctx, request_id, &wire);
}

#[test]
fn a_refused_request_never_crosses() {
  let mut cases: Vec<(Option<&str>, String, &str)> = vec![
    (Some("fetch(example.com)"), "fetch('https://evil.com/x')".into(), "PluginError|not-granted|missing grant: fetch(evil.com)"),
    (None, "fetch('https://example.com/x')".into(), "PluginError|not-granted|missing grant: fetch(example.com)"),
    (Some("fetch"), "fetch('https://example.com/x', { method: 'GET /x HTTP/1.1' })".into(), "PluginError|invalid-argument|"),
    (
      Some("fetch"),
      "(() => { const b = new Blob(['x']); b.dispose(); return fetch('https://example.com/x', { body: b, method: 'POST' }) })()".into(),
      "PluginError|handle-expired|",
    ),
    (
      Some("fetch"),
      "(() => { const c = new AbortController(); c.abort(); return fetch('https://example.com/x', { signal: c.signal }) })()".into(),
      "PluginError|aborted|the request was aborted",
    ),
  ];
  for url in [
    "https://example.com@127.0.0.1/x",
    "file:///etc/hosts",
    "content://media/external/x",
    "ftp://example.com/x",
    "notaurl",
    "https:///x",
  ] {
    cases.push((Some("fetch"), format!("fetch({url:?})"), "PluginError|invalid-argument|"));
  }
  for header in ["Host", "content-length", "Transfer-Encoding", "connection"] {
    cases.push((
      Some("fetch"),
      format!("fetch('https://example.com/x', {{ headers: {{ {header:?}: 'x' }} }})"),
      "PluginError|invalid-argument|",
    ));
  }
  for init in [
    r#"{ headers: { 'x y': 'a' } }"#,
    r#"{ headers: { 'x-one:': 'a' } }"#,
    r#"{ headers: { '': 'a' } }"#,
    r#"{ headers: { 'x-one': 'a\u0000b' } }"#,
    r#"{ headers: { 'x-one': 'a\u007fb' } }"#,
    r#"{ headers: { 'x-one': 'ключ' } }"#,
    r#"{ headers: 'x-one: a' }"#,
    r#"{ headers: [['x-one']] }"#,
    r#"{ headers: [['x-one', 'a', 'b']] }"#,
  ] {
    cases.push((Some("fetch"), format!("fetch('https://example.com/x', {init})"), "TypeError|"));
  }
  for (grant, call, prefix) in cases {
    let f = setup(grant);
    start(&f, &call);
    assert!(out(&f).starts_with(prefix), "{call}: {}", out(&f));
    assert!(f.host.sent.borrow().is_empty(), "{call} crossed");
  }
}

#[test]
fn the_host_the_grant_is_matched_on_is_the_one_the_url_connects_to() {
  let f = setup(Some("fetch(example.com)"));
  // the port, a trailing dot and the case are all the same name; none of them may widen it
  for url in ["https://EXAMPLE.com:8443/x", "https://example.com./x", "https://api.EXAMPLE.COM/x?q=example.com"] {
    start(&f, &format!("fetch({url:?})"));
    assert_eq!(out(&f), "null", "'{url}' was refused");
  }
  // and a host that only *contains* the granted one in its path or query is not it
  start(&f, "fetch('https://evil.com/?to=example.com')");
  assert_eq!(out(&f), "PluginError|not-granted|missing grant: fetch(evil.com)");
}

#[test]
fn an_ipv6_literal_keeps_its_brackets_out_of_the_name() {
  let f = setup(Some("fetch(example.com)"));
  start(&f, "fetch('http://[::1]:8080/x')");
  assert_eq!(out(&f), "PluginError|not-granted|missing grant: fetch(::1)");
}

#[test]
fn an_unscoped_grant_reaches_any_host() {
  let f = setup(Some("fetch"));
  start(&f, "fetch('https://anything.example/x')");
  assert_eq!(out(&f), "null");
  let sent = f.host.sent.borrow();
  assert_eq!(sent[0].url, "https://anything.example/x");
  assert_eq!(sent[0].id, 1, "the id the host is given is the one an abort would name");
}

/// every failure arrives in the `catch`, including the ones this module decides synchronously -
/// `fetch(...).catch(...)` is the only shape anybody writes
#[test]
fn a_refusal_rejects_rather_than_throwing_at_the_call_site() {
  let f = setup(None);
  let threw = f.ctx.with(|ctx| ctx.eval::<(), _>("fetch('https://example.com/x').catch(() => {})").is_err());
  assert!(!threw);
}

#[test]
fn the_spec_carries_the_method_headers_and_redirect_mode() {
  let f = setup(Some("fetch"));
  start(
    &f,
    r#"
      fetch('https://example.com/x', {
        method: 'post',
        headers: { 'X-One': 'a', 'X-Many': ['b', 'c'] },
        redirect: 'manual',
        body: 'hello',
      })
    "#,
  );
  let sent = f.host.sent.borrow();
  assert_eq!(sent[0].url, "https://example.com/x");
  assert_eq!(sent[0].spec, r#"POST manual ["x-one", "a", "x-many", "b", "x-many", "c"]"#);
  assert_eq!(sent[0].body.as_deref(), Some(b"hello".as_slice()));
}

/// the prelude runs in the plugin's realm, so a check made there is one the plugin can switch off
#[test]
fn a_plugin_that_patches_its_realm_still_cannot_forge_a_header() {
  let f = setup(Some("fetch"));
  start(
    &f,
    r#"
      (() => {
        RegExp.prototype.test = () => true
        Array.prototype.toJSON = () => ['internal.corp']
        JSON.stringify = () => '{"headers":{"host":["internal.corp"]}}'
        return fetch('https://example.com/x', { headers: { 'X-One': ['a\r\nHost: internal.corp'] } })
      })()
    "#,
  );
  assert!(out(&f).starts_with("TypeError|"), "{}", out(&f));
  assert!(f.host.sent.borrow().is_empty());
}

/// `Headers` keeps its pairs in a private field, but fills it through `Array.prototype.push`: a
/// plugin that patches that forges the list, and the send path must still refuse it
#[test]
fn a_forged_headers_list_is_refused_on_the_send_path() {
  let f = setup(Some("fetch"));
  start(
    &f,
    r#"
      (() => {
          const push = Array.prototype.push
          Array.prototype.push = function (...items) {
            if (Array.isArray(items[0]) && items[0][0] === 'x-one') return push.call(this, ['x-one', 'a\r\nHost: internal.corp'])
            return push.apply(this, items)
          }
          return fetch('https://example.com/x', { headers: { 'X-One': 'a' } })
      })()
    "#,
  );
  assert!(out(&f).starts_with("TypeError|"), "{}", out(&f));
  start(
    &f,
    r#"
      (() => {
          const push = Array.prototype.push
          Array.prototype.push = function (...items) {
            if (Array.isArray(items[0]) && items[0][0] === 'x-one') return push.call(this, ['host', 'internal.corp'])
            return push.apply(this, items)
          }
          return fetch('https://example.com/x', { headers: { 'X-One': 'a' } })
      })()
    "#,
  );
  assert!(out(&f).starts_with("PluginError|invalid-argument|"), "{}", out(&f));
  assert!(f.host.sent.borrow().is_empty());
}

#[test]
fn headers_may_be_pairs_a_record_or_a_headers_and_values_are_coerced_and_trimmed() {
  let f = setup(Some("fetch"));
  start(&f, "fetch('https://example.com/x', { headers: [['X-One', ' a '], ['x-one', 7]] })");
  start(&f, "fetch('https://example.com/x', { headers: new Headers({ 'X-Two': 'b' }) })");
  start(&f, "fetch('https://example.com/x', { headers: new Map([['X-Three', 'c']]) })");
  let sent = f.host.sent.borrow();
  assert_eq!(sent[0].spec, r#"GET follow ["x-one", "a", "x-one", "7"]"#);
  assert_eq!(sent[1].spec, r#"GET follow ["x-two", "b"]"#);
  assert_eq!(sent[2].spec, r#"GET follow ["x-three", "c"]"#);
}

#[test]
fn headers_reads_combine_repeats_and_iterate_sorted() {
  let f = setup(None);
  let got = eval(
    &f,
    r#"
      (() => {
          const h = new Headers([['X-B', '1'], ['x-a', '2'], ['X-B', '3'], ['Set-Cookie', 'a=1'], ['set-cookie', 'b=2']])
          return JSON.stringify([
            h.get('x-b'), h.get('X-MISSING'), h.has('X-A'), h.getSetCookie(), h.get('set-cookie'),
            [...h], [...h.keys()], [...h.values()], String(h),
          ])
      })()
    "#,
  );
  assert_eq!(
    got,
    r#"["1, 3",null,true,["a=1","b=2"],"a=1, b=2",[["set-cookie","a=1"],["set-cookie","b=2"],["x-a","2"],["x-b","1, 3"]],["set-cookie","set-cookie","x-a","x-b"],["a=1","b=2","2","1, 3"],"[object Headers]"]"#,
  );
}

#[test]
fn headers_set_replaces_every_value_in_place_and_delete_drops_them() {
  let f = setup(None);
  let got = eval(
    &f,
    r#"
      (() => {
        const h = new Headers([['x-a', '1'], ['x-b', '2'], ['x-a', '3']])
        h.set('X-A', '4')
        const afterSet = [...h]
        h.append('x-c', '5')
        h.delete('X-B')
        const seen = []
        h.forEach(function (value, name, self) { seen.push([name, value, self === h, this.tag]) }, { tag: 't' })
        return JSON.stringify([afterSet, seen])
      })()
    "#,
  );
  assert_eq!(got, r#"[[["x-a","4"],["x-b","2"]],[["x-a","4",true,"t"],["x-c","5",true,"t"]]]"#);
}

#[test]
fn headers_refuse_a_bad_name_or_value_and_a_non_headers_receiver() {
  let f = setup(None);
  let got = eval(
    &f,
    r#"
      (() => {
          const h = new Headers()
          const threw = (fn) => { try { fn(); return 'no' } catch (e) { return e.name } }
          return JSON.stringify([
            threw(() => h.append('x y', 'a')),
            threw(() => h.set('x-a', 'a\nb')),
            threw(() => h.set('x-a', 'a\u0001b')),
            threw(() => h.get('')),
            threw(() => new Headers(null)),
            threw(() => new Headers([['x-a']])),
            threw(() => Headers.prototype.get.call({}, 'x-a')),
          ])
      })()
    "#,
  );
  assert_eq!(got, r#"["TypeError","TypeError","TypeError","TypeError","TypeError","TypeError","TypeError"]"#);
}

/// names are case-insensitive, so two spellings of one are one header with both values
#[test]
fn header_names_are_lowercased_and_a_tab_is_an_ordinary_value_character() {
  let f = setup(Some("fetch"));
  start(&f, "fetch('https://example.com/x', { headers: { 'X-One': 'a', 'x-one': ['b\tc'] } })");
  assert_eq!(f.host.sent.borrow()[0].spec, r#"GET follow ["x-one", "a", "x-one", "b\tc"]"#);
}

#[test]
fn a_body_may_be_bytes_or_a_blob_and_a_blob_is_read_on_this_side() {
  let f = setup(Some("fetch"));
  start(&f, "fetch('https://example.com/x', { method: 'PUT', body: new Uint8Array([1, 2, 3]) })");
  assert_eq!(f.host.sent.borrow()[0].body.as_deref(), Some([1u8, 2, 3].as_slice()));

  start(&f, "fetch('https://example.com/x', { method: 'PUT', body: new Blob(['abc']).slice(1) })");
  assert_eq!(f.host.sent.borrow()[1].body.as_deref(), Some(b"bc".as_slice()));
}

#[test]
fn a_response_carries_the_status_headers_and_body() {
  let f = setup(Some("fetch"));
  start(&f, "fetch('https://example.com/x')");
  answer(&f, 1, 200, r#"{"content-type":["text/plain"],"set-cookie":["a=1","b=2"]}"#, "hello body");
  assert_eq!(out(&f), "ok");
  let got = eval(
    &f,
    r#"JSON.stringify([__res.ok, __res.status, __res.statusText, __res.url, __res.headers instanceof Headers, [...__res.headers]])"#,
  );
  assert_eq!(
    got,
    r#"[true,200,"OK","https://api.example.com/x",true,[["content-type","text/plain"],["set-cookie","a=1"],["set-cookie","b=2"]]]"#,
  );
  run(&f, "__res.text().then(t => { globalThis.__body = t })");
  assert_eq!(eval(&f, "globalThis.__body"), "hello body");
}

#[test]
fn a_failing_status_is_a_resolved_response_that_says_so() {
  let f = setup(Some("fetch"));
  start(&f, "fetch('https://example.com/x')");
  answer(&f, 1, 404, "{}", "nope");
  assert_eq!(out(&f), "ok", "an http error is not a rejection");
  assert_eq!(eval(&f, "JSON.stringify([__res.ok, __res.status])"), "[false,404]");
}

#[test]
fn an_empty_body_still_reads_as_the_empty_string() {
  let f = setup(Some("fetch"));
  start(&f, "fetch('https://example.com/x')");
  answer(&f, 1, 204, "{}", "");
  run(&f, "__res.text().then(t => { globalThis.__body = `[${t}]` })");
  assert_eq!(eval(&f, "globalThis.__body"), "[]");
}

#[test]
fn an_answer_for_a_request_nobody_is_waiting_on_is_dropped() {
  let f = setup(Some("fetch"));
  answer(&f, 99, 200, "{}", "x");
  // no panic, no root left behind; `dispose` on drop would abort if one were
}

#[test]
fn an_abort_signal_rejects_and_tells_the_host_to_stop() {
  let f = setup(Some("fetch"));
  start(
    &f,
    "(() => { globalThis.__c = new AbortController(); return fetch('https://example.com/x', { signal: __c.signal }) })()",
  );
  assert_eq!(out(&f), "null");
  run(&f, "__c.abort()");
  assert_eq!(out(&f), "PluginError|aborted|the request was aborted");
  assert_eq!(*f.host.aborted.borrow(), vec![1]);
}

#[test]
fn a_timeout_rejects_and_tells_the_host_to_stop() {
  let f = setup(Some("fetch"));
  start(&f, "fetch('https://example.com/x', { timeout: 50 })");
  assert_eq!(out(&f), "null");
  f.advance(49);
  assert_eq!(out(&f), "null", "and not before it elapses");
  f.advance(1);
  assert_eq!(out(&f), "PluginError|timed-out|the request timed out after 50 ms");
  assert_eq!(*f.host.aborted.borrow(), vec![1]);
}

#[test]
fn an_answer_that_arrives_first_wins_and_nothing_is_aborted() {
  let f = setup(Some("fetch"));
  start(
    &f,
    "(() => { globalThis.__c = new AbortController(); return fetch('https://example.com/x', { signal: __c.signal, timeout: 50 }) })()",
  );
  answer(&f, 1, 200, "{}", "done");
  assert_eq!(out(&f), "ok");
  run(&f, "__c.abort()");
  assert_eq!(out(&f), "ok", "a settled request cannot be un-settled");
  assert!(f.host.aborted.borrow().is_empty());
}

#[test]
fn an_answer_after_an_abort_is_dropped() {
  let f = setup(Some("fetch"));
  start(
    &f,
    "(() => { globalThis.__c = new AbortController(); return fetch('https://example.com/x', { signal: __c.signal }) })()",
  );
  run(&f, "__c.abort()");
  answer(&f, 1, 200, "{}", "late");
  assert_eq!(out(&f), "PluginError|aborted|the request was aborted");
}

/// the fetch oracle's only other run is on a device
mod bundled_oracle {
  use super::*;
  use crate::testing::harness::{install_sandbox_globals, TestDir};

  const ORACLE: &str = crate::testing::test_plugin!("fetch-test.js");

  /// [`TestDomainGrants`] holds one `fetch` token, so a manifest that grew a second one has to be
  /// noticed here rather than silently running under the first
  fn oracle_grant() -> Option<&'static str> {
    match crate::testing::harness::manifest_grants(ORACLE).as_slice() {
      [only] => Some(*only),
      other => panic!("the oracle's manifest asks for {other:?}, which this fixture cannot install"),
    }
  }

  /// answers every request the same way, so the oracle's assertions are about the *engine* and
  /// the egress screening the host does is asserted where it lives (`PluginFetchTest`)
  struct OracleHost {
    dir: std::path::PathBuf,
    answers: RefCell<Vec<i64>>,
  }

  impl FetchHost for OracleHost {
    fn send(&self, request_id: i64, url: &str, _spec: &Spec, _body: Option<&[u8]>) -> Option<String> {
      if url.contains("/refuse") {
        return Some("Pforbidden\n\n\n\nthat address is not a place this api goes".to_string());
      }
      if url.contains("/never") {
        return None;
      }
      self.answers.borrow_mut().push(request_id);
      None
    }

    fn abort(&self, _request_id: i64) {}
  }

  #[test]
  fn the_bundled_fetch_test_plugin_passes() {
    let (_rt, ctx) = crate::testing::harness::new_engine();
    let dir = TestDir::new("fetch-oracle");
    let host = Rc::new(OracleHost {
      dir: dir.path().to_path_buf(),
      answers: RefCell::new(Vec::new()),
    });
    let host_dyn: Rc<dyn FetchHost> = host.clone();
    let log: crate::Log = std::sync::Arc::new(|_| {});
    let lines = crate::testing::harness::install_capturing_console(&ctx);
    let clock = Rc::new(super::tests::TestClock::default());
    let clock_dyn: Rc<dyn crate::api::timers::TimerHost> = clock.clone();
    let (timers, state) = ctx.with(|ctx| {
      let inu = crate::testing::harness::get_api_globals(&ctx);
      install_sandbox_globals(&ctx, dir.path()).unwrap();
      let timers =
        crate::api::timers::install_timers(&ctx, clock_dyn, crate::sandbox::registry::Lifecycle::new(), log.clone())
          .unwrap();
      let state =
        install_fetch(&ctx, host_dyn, super::tests::TestDomainGrants::new(oracle_grant()).as_host(), log.clone(), &inu)
          .unwrap();
      (timers, state)
    });
    crate::testing::harness::eval_unit(&ctx, ORACLE);

    // the oracle awaits one call at a time, so driving it is a loop rather than a drain: settle
    // whatever the fake accepted since the last pass, then move the clock so a `timeout` the
    // oracle is parked on can come due
    for _ in 0..64 {
      pump_jobs(&ctx, &|_| {});
      let due: Vec<i64> = host.answers.borrow_mut().drain(..).collect();
      for request_id in due {
        answer_ok(&ctx, &state, &host.dir, request_id);
      }
      clock.advance(1000);
      timers.run_due(&ctx);
      if lines.borrow().iter().any(|l| l == "fetch test done") {
        break;
      }
    }

    state.dispose(&ctx);
    timers.dispose(&ctx);
    let lines = lines.borrow().clone();
    crate::testing::harness::assert_oracle_exact(&lines, "fetch test done", 37);
  }

  /// the one answer the fake ever gives, with a real file behind the body so the oracle's `blob()`
  /// assertions run against the same app-file backing a device would hand them
  fn answer_ok(ctx: &rquickjs::Context, state: &Rc<FetchState>, dir: &std::path::Path, request_id: i64) {
    let path = dir.join(format!("body-{request_id}"));
    std::fs::write(&path, r#"{"hello":"world"}"#).unwrap();
    let wire = format!(
      r#"J{{"status":200,"statusText":"OK","url":"https://example.com/final","headers":{{"content-type":["application/json"],"set-cookie":["a=1","b=2"]}},"body":{{"path":{:?},"type":"application/json"}}}}"#,
      path.to_string_lossy(),
    );
    state.settle(ctx, request_id, &wire);
  }
}

use super::*;
use crate::engine::error::install_plugin_error;
use crate::io::fs::tests::{install_sandbox_globals, TestDir};
use rquickjs::Context;
use std::cell::Cell;

/// Mirrors `PluginPermissions.allows(..., ScopeMatch.DOMAIN)`, which is what really answers
/// `onCheckGrant` here. The shared `TestGrantHost` compares scopes literally, and a fixture that
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

/// one request as it crossed
struct Sent {
    id: i64,
    url: String,
    spec: String,
    body: Option<Vec<u8>>,
}

/// records what crossed and answers whatever the test told it to
#[derive(Default)]
struct TestFetchHost {
    sent: RefCell<Vec<Sent>>,
    aborted: RefCell<Vec<i64>>,
    refuse: RefCell<Option<String>>,
}

impl FetchHost for TestFetchHost {
    fn send(&self, request_id: i64, url: &str, spec_json: &str, body: Option<&[u8]>) -> Option<String> {
        self.sent.borrow_mut().push(Sent {
            id: request_id,
            url: url.to_string(),
            spec: spec_json.to_string(),
            body: body.map(<[u8]>::to_vec),
        });
        self.refuse.borrow().clone()
    }

    fn abort(&self, request_id: i64) {
        self.aborted.borrow_mut().push(request_id);
    }
}

type Disposing = crate::testing::util::DisposeOnDrop<FetchState>;
type DisposingTimers = crate::testing::util::DisposeOnDrop<crate::engine::timers::TimerState>;

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

impl crate::engine::timers::TimerHost for TestClock {
    fn schedule_wake(&self, _delay_ms: i64) {}

    fn now_ms(&self) -> u64 {
        self.now.get()
    }
}

struct Fixture {
    rt: Runtime,
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
        crate::engine::timers::run_due(&self.rt, &self.ctx, &self.timers);
    }
}

fn setup(grant: Option<&str>) -> Fixture {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let dir = TestDir::new("fetch");
    let host = Rc::new(TestFetchHost::default());
    let host_dyn: Rc<dyn FetchHost> = host.clone();
    let clock = Rc::new(TestClock::default());
    let clock_dyn: Rc<dyn crate::engine::timers::TimerHost> = clock.clone();
    let grants = TestDomainGrants::new(grant).as_host();
    let log: crate::Log = std::sync::Arc::new(|_| {});
    let (timers, state) = ctx.with(|ctx| {
        install_plugin_error(&ctx).unwrap();
        let blobs = install_sandbox_globals(&ctx, dir.path()).unwrap();
        let timers = crate::engine::timers::install_timers(
            &ctx,
            clock_dyn,
            crate::engine::registry::Lifecycle::new(),
            log.clone(),
        )
        .unwrap();
        let state = install_fetch(&ctx, host_dyn, grants, blobs, log.clone()).unwrap();
        (timers, state)
    });
    let timers = DisposingTimers::new(&ctx, timers, crate::engine::timers::dispose);
    let state = Disposing::new(&ctx, state, dispose);
    Fixture { rt, ctx, host, dir, clock, timers, state }
}

fn eval(f: &Fixture, code: &str) -> String {
    crate::testing::util::eval_string(&f.ctx, code)
}

fn run(f: &Fixture, code: &str) {
    crate::testing::util::eval_unit(&f.ctx, code);
    pump_jobs(&f.rt, &f.ctx, &|_| {});
}

/// starts a fetch whose settlement is recorded on `globalThis.__out`
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

/// the answer a real host would give, with a body file the engine mints its blob over
fn answer(f: &Fixture, request_id: i64, status: i32, headers: &str, body: &str) {
    let path = f.dir.path().join(format!("body-{request_id}"));
    std::fs::write(&path, body).unwrap();
    let wire = format!(
        r#"J{{"status":{status},"statusText":"OK","url":"https://api.example.com/x","headers":{headers},"body":{{"path":{:?},"type":"text/plain"}}}}"#,
        path.to_string_lossy(),
    );
    fetch_result(&f.rt, &f.ctx, &f.state, request_id, &wire);
}

#[test]
fn a_host_outside_the_grants_domains_never_crosses() {
    let f = setup(Some("fetch(example.com)"));
    start(&f, "fetch('https://evil.com/x')");
    assert_eq!(out(&f), "PluginError|not-granted|missing grant: fetch(evil.com)");
    assert!(f.host.sent.borrow().is_empty(), "a refused call must not reach the host");
}

#[test]
fn a_subdomain_of_a_granted_domain_is_covered_and_a_lookalike_is_not() {
    let f = setup(Some("fetch(example.com)"));
    start(&f, "fetch('https://api.example.com/x')");
    assert_eq!(out(&f), "null", "still in flight");
    assert_eq!(f.host.sent.borrow().len(), 1);

    start(&f, "fetch('https://notexample.com/x')");
    assert_eq!(out(&f), "PluginError|not-granted|missing grant: fetch(notexample.com)");
}

#[test]
fn a_url_whose_host_is_not_what_it_reads_as_is_refused() {
    let f = setup(Some("fetch"));
    for url in [
        "https://example.com@127.0.0.1/x",
        "file:///etc/hosts",
        "content://media/external/x",
        "ftp://example.com/x",
        "notaurl",
        "https:///x",
    ] {
        start(&f, &format!("fetch({url:?})"));
        assert!(out(&f).starts_with("PluginError|invalid-argument|"), "'{url}' answered {}", out(&f),);
    }
    assert!(f.host.sent.borrow().is_empty());
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

#[test]
fn without_the_grant_nothing_is_reachable() {
    let f = setup(None);
    start(&f, "fetch('https://example.com/x')");
    assert_eq!(out(&f), "PluginError|not-granted|missing grant: fetch(example.com)");
    assert!(f.host.sent.borrow().is_empty());
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
        r#"fetch('https://example.com/x', {
            method: 'post',
            headers: { 'X-One': 'a', 'X-Many': ['b', 'c'] },
            redirect: 'manual',
            body: 'hello',
        })"#,
    );
    let sent = f.host.sent.borrow();
    assert_eq!(sent[0].url, "https://example.com/x");
    assert_eq!(sent[0].spec, r#"{"method":"POST","headers":{"x-one":["a"],"x-many":["b","c"]},"redirect":"manual"}"#,);
    assert_eq!(sent[0].body.as_deref(), Some(b"hello".as_slice()));
}

/// the prelude runs in the plugin's realm, so a spec it serialized itself would be whatever the
/// plugin's `JSON.stringify` felt like returning - and the refusals `normalizeHeaders` states would
/// be advisory
#[test]
fn reassigning_json_stringify_does_not_decide_what_the_host_is_sent() {
    let f = setup(Some("fetch"));
    start(
        &f,
        r#"(() => {
            JSON.stringify = () => '{"method":"POST","headers":{"host":["internal.corp"]},"redirect":"follow"}'
            return fetch('https://example.com/x', { headers: { 'X-One': 'a' } })
        })()"#,
    );
    assert_eq!(f.host.sent.borrow()[0].spec, r#"{"method":"GET","headers":{"x-one":["a"]},"redirect":"follow"}"#);
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
fn a_disposed_body_blob_is_refused_before_anything_crosses() {
    let f = setup(Some("fetch"));
    start(
        &f,
        "(() => { const b = new Blob(['x']); b.dispose(); return fetch('https://example.com/x', { body: b, method: 'POST' }) })()",
    );
    assert!(out(&f).starts_with("PluginError|handle-expired|"), "{}", out(&f));
    assert!(f.host.sent.borrow().is_empty());
}

#[test]
fn a_body_that_is_not_content_is_refused() {
    let f = setup(Some("fetch"));
    start(&f, "fetch('https://example.com/x', { method: 'POST', body: { a: 1 } })");
    assert!(out(&f).starts_with("PluginError|invalid-argument|"), "{}", out(&f));
}

#[test]
fn a_header_the_transport_owns_is_refused() {
    let f = setup(Some("fetch"));
    for header in ["Host", "content-length", "Transfer-Encoding", "connection"] {
        start(&f, &format!("fetch('https://example.com/x', {{ headers: {{ {header:?}: 'x' }} }})"));
        assert!(out(&f).starts_with("PluginError|invalid-argument|"), "'{header}': {}", out(&f));
    }
    assert!(f.host.sent.borrow().is_empty());
}

#[test]
fn a_redirect_mode_the_api_does_not_have_is_refused() {
    let f = setup(Some("fetch"));
    start(&f, "fetch('https://example.com/x', { redirect: 'ignore' })");
    assert!(out(&f).starts_with("PluginError|invalid-argument|"), "{}", out(&f));
}

#[test]
fn a_response_carries_the_status_headers_and_body() {
    let f = setup(Some("fetch"));
    start(&f, "fetch('https://example.com/x')");
    answer(&f, 1, 200, r#"{"content-type":["text/plain"],"set-cookie":["a=1","b=2"]}"#, "hello body");
    assert_eq!(out(&f), "ok");
    let got = eval(&f, r#"JSON.stringify([__res.ok, __res.status, __res.statusText, __res.url, __res.headers])"#);
    assert_eq!(
        got, r#"[true,200,"OK","https://api.example.com/x",{"content-type":"text/plain","set-cookie":["a=1","b=2"]}]"#,
        "a header that appeared once is a string, one that repeated is an array",
    );
    run(&f, "__res.text().then(t => { globalThis.__body = t })");
    assert_eq!(eval(&f, "globalThis.__body"), "hello body");
}

#[test]
fn the_body_is_a_blob_over_the_hosts_file_rather_than_bytes_in_the_heap() {
    let f = setup(Some("fetch"));
    start(&f, "fetch('https://example.com/x')");
    answer(&f, 1, 200, "{}", "0123456789");
    run(
        &f,
        r#"
        const b = __res.blob()
        b.then(async blob => {
          globalThis.__shape = [blob instanceof Blob, blob.size, blob.type, await blob.slice(2, 5).text()].join('|')
        })
        "#,
    );
    assert_eq!(eval(&f, "globalThis.__shape"), "true|10|text/plain|234");
}

#[test]
fn json_parses_the_body_and_bytes_answers_the_same_content() {
    let f = setup(Some("fetch"));
    start(&f, "fetch('https://example.com/x')");
    answer(&f, 1, 200, "{}", r#"{"a":[1,2]}"#);
    run(
        &f,
        r#"
        __res.json().then(async v => {
          const bytes = await __res.bytes()
          globalThis.__shape = `${v.a[1]}|${bytes.length}|${bytes instanceof Uint8Array}`
        })
        "#,
    );
    assert_eq!(eval(&f, "globalThis.__shape"), "2|11|true");
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
fn a_host_refusal_rejects_with_what_it_named() {
    let f = setup(Some("fetch"));
    *f.host.refuse.borrow_mut() = Some("Pforbidden\n\n\n\n127.0.0.1 is not a place this api goes".to_string());
    start(&f, "fetch('https://localtest.me/x')");
    assert_eq!(
        out(&f),
        "PluginError|forbidden|127.0.0.1 is not a place this api goes",
        "the address refusal is the host's to make and reaches the plugin verbatim",
    );
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
fn a_signal_that_already_fired_never_sends() {
    let f = setup(Some("fetch"));
    start(
        &f,
        "(() => { const c = new AbortController(); c.abort(); return fetch('https://example.com/x', { signal: c.signal }) })()",
    );
    assert_eq!(out(&f), "PluginError|aborted|the request was aborted");
    assert!(f.host.sent.borrow().is_empty());
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

/// The bundled oracle runs here too, against a fake host: on a device it is the only thing that
/// exercises this surface at all, and an oracle nobody runs is one nobody notices going green.
#[cfg(test)]
mod bundled_oracle {
    use super::*;
    use crate::engine::error::install_plugin_error;
    use crate::io::fs::tests::{install_sandbox_globals, TestDir};
    use rquickjs::Context;

    const ORACLE: &str = include_str!("../../../../res/assets-debug/inu_plugins/fetch-test.js");

    /// [`TestDomainGrants`] holds one `fetch` token, so a manifest that grew a second one has to be
    /// noticed here rather than silently running under the first
    fn oracle_grant() -> Option<&'static str> {
        match crate::testing::util::manifest_grants(ORACLE).as_slice() {
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
        fn send(&self, request_id: i64, url: &str, _spec_json: &str, _body: Option<&[u8]>) -> Option<String> {
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
        let rt = Runtime::new().unwrap();
        let ctx = Context::full(&rt).unwrap();
        let dir = TestDir::new("fetch-oracle");
        let host = Rc::new(OracleHost { dir: dir.path().to_path_buf(), answers: RefCell::new(Vec::new()) });
        let host_dyn: Rc<dyn FetchHost> = host.clone();
        let log: crate::Log = std::sync::Arc::new(|_| {});
        let lines = crate::testing::util::install_capturing_console(&ctx);
        let clock = Rc::new(super::tests::TestClock::default());
        let clock_dyn: Rc<dyn crate::engine::timers::TimerHost> = clock.clone();
        let (timers, state) = ctx.with(|ctx| {
            install_plugin_error(&ctx).unwrap();
            let blobs = install_sandbox_globals(&ctx, dir.path()).unwrap();
            let timers = crate::engine::timers::install_timers(
                &ctx,
                clock_dyn,
                crate::engine::registry::Lifecycle::new(),
                log.clone(),
            )
            .unwrap();
            let state = install_fetch(
                &ctx,
                host_dyn,
                super::tests::TestDomainGrants::new(oracle_grant()).as_host(),
                blobs,
                log.clone(),
            )
            .unwrap();
            (timers, state)
        });
        ctx.with(|ctx| match ctx.eval::<(), _>(ORACLE) {
            Ok(()) => {}
            Err(rquickjs::Error::Exception) => panic!("{}", crate::tg::rpc::format_exception(&ctx)),
            Err(e) => panic!("{e:?}"),
        });

        // the oracle awaits one call at a time, so driving it is a loop rather than a drain: settle
        // whatever the fake accepted since the last pass, then move the clock so a `timeout` the
        // oracle is parked on can come due
        for _ in 0..64 {
            pump_jobs(&rt, &ctx, &|_| {});
            let due: Vec<i64> = host.answers.borrow_mut().drain(..).collect();
            for request_id in due {
                answer_ok(&rt, &ctx, &state, &host.dir, request_id);
            }
            clock.advance(1000);
            crate::engine::timers::run_due(&rt, &ctx, &timers);
            if lines.borrow().iter().any(|l| l == "fetch test done") {
                break;
            }
        }

        dispose(&ctx, &state);
        crate::engine::timers::dispose(&ctx, &timers);
        let lines = lines.borrow().clone();
        crate::testing::util::assert_oracle_exact(&lines, "fetch test done", 35);
    }

    /// the one answer the fake ever gives, with a real file behind the body so the oracle's `blob()`
    /// assertions run against the same app-file backing a device would hand them
    fn answer_ok(
        rt: &Runtime,
        ctx: &rquickjs::Context,
        state: &Rc<FetchState>,
        dir: &std::path::Path,
        request_id: i64,
    ) {
        let path = dir.join(format!("body-{request_id}"));
        std::fs::write(&path, r#"{"hello":"world"}"#).unwrap();
        let wire = format!(
            r#"J{{"status":200,"statusText":"OK","url":"https://example.com/final","headers":{{"content-type":["application/json"],"set-cookie":["a=1","b=2"]}},"body":{{"path":{:?},"type":"application/json"}}}}"#,
            path.to_string_lossy(),
        );
        fetch_result(rt, ctx, state, request_id, &wire);
    }
}

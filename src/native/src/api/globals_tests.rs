use super::*;
use rquickjs::{Context, Runtime};
use std::cell::RefCell;

/// counts up from a seed, so a test can assert the array really was written through
struct TestRandomHost {
  next: RefCell<u8>,
  available: RefCell<bool>,
}

impl Default for TestRandomHost {
  fn default() -> Self {
    TestRandomHost {
      next: RefCell::new(1),
      available: RefCell::new(true),
    }
  }
}

impl RandomHost for TestRandomHost {
  fn random_bytes(&self, out: &mut [u8]) -> bool {
    if !*self.available.borrow() {
      return false;
    }
    let mut next = self.next.borrow_mut();
    for byte in out.iter_mut() {
      *byte = *next;
      *next = next.wrapping_add(1);
    }
    true
  }
}

fn setup() -> (Runtime, Context, Rc<TestRandomHost>) {
  setup_in(Path::new(""))
}

fn setup_in(spill_dir: &Path) -> (Runtime, Context, Rc<TestRandomHost>) {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let host = Rc::new(TestRandomHost::default());
  let host_dyn: Rc<dyn RandomHost> = host.clone();
  ctx.with(|ctx| install_globals(&ctx, host_dyn, spill_dir, ExternalMemory::new()).unwrap());
  (rt, ctx, host)
}

/// a scratch directory that goes away with the test, failing path included
struct TestDir(std::path::PathBuf);

impl TestDir {
  fn new(name: &str) -> Self {
    let path = std::env::temp_dir().join(format!("inu-globals-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&path);
    std::fs::create_dir_all(&path).unwrap();
    TestDir(path)
  }

  fn entries(&self) -> Vec<std::path::PathBuf> {
    let Ok(read) = std::fs::read_dir(&self.0) else {
      return Vec::new();
    };
    read.filter_map(|e| e.ok().map(|e| e.path())).collect()
  }
}

impl Drop for TestDir {
  fn drop(&mut self) {
    let _ = std::fs::remove_dir_all(&self.0);
  }
}

/// drains the microtask queue and hands back what the promise settled to
fn settle(rt: &Runtime, ctx: &Context, expr: &str) -> String {
  run(
    ctx,
    &format!(
      r#"
            globalThis.__out = 'pending';
            ({expr}).then(
                v => {{ globalThis.__out = String(v); }},
                e => {{ globalThis.__out = `${{e.name}}:${{e.code}}`; }},
            );
            "#,
    ),
  );
  while rt.is_job_pending() {
    rt.execute_pending_job().ok();
  }
  eval(ctx, "globalThis.__out")
}

use crate::testing::harness::eval_string as eval;

use crate::testing::harness::eval_unit as run;

/// the guard that pins the *engine's* context rather than this fixture's: `Context::base` is
/// what any narrower constructor looks like from here, and installing into one has to fail
/// loudly instead of leaving a sandbox missing globals `common.d.ts` promises
#[test]
fn installing_into_a_context_without_the_intrinsics_refuses() {
  let rt = Runtime::new().unwrap();
  let ctx = Context::base(&rt).unwrap();
  let host: Rc<dyn RandomHost> = Rc::new(TestRandomHost::default());
  let message = ctx.with(|ctx| {
    let Err(err) = install_globals(&ctx, host, Path::new(""), ExternalMemory::new()) else {
      panic!("a base context must be refused");
    };
    assert!(matches!(err, rquickjs::Error::Exception), "got: {err:?}");
    crate::api::telegram::rpc::format_exception(&ctx)
  });
  assert!(message.contains("Context::full"), "got: {message}");
  assert!(REQUIRED_INTRINSICS.iter().any(|name| message.contains(&format!("no '{name}'"))), "got: {message}",);
  ctx.with(|ctx| assert!(ctx.globals().get::<_, Value>("TextEncoder").unwrap().is_undefined()));
}

/// the free half of the sandbox surface: everything here ships with quickjs-ng, and this is
/// what would break if the engine ever moved off `Context::full`
#[test]
fn quickjs_already_provides_the_intrinsics_the_doc_promises() {
  let (_rt, ctx, _host) = setup();
  let missing = eval(
    &ctx,
    r#"
        ['atob', 'btoa', 'queueMicrotask', 'performance', 'DOMException', 'BigInt', 'Proxy',
         'Reflect', 'WeakRef', 'FinalizationRegistry', 'Promise', 'Map', 'Set', 'JSON', 'Date',
         'RegExp', 'Uint8Array', 'DataView', 'globalThis']
            .filter(name => globalThis[name] === undefined).join(',');
        "#,
  );
  assert_eq!(missing, "");
  assert_eq!(eval(&ctx, "atob(btoa('inugram'))"), "inugram");
  assert_eq!(eval(&ctx, "new DOMException('m', 'AbortError').name"), "AbortError");
  // documented as absent, and `inu.utils.format*` exists because of it
  assert_eq!(eval(&ctx, "typeof globalThis.Intl"), "undefined");
}

#[test]
fn text_encoder_round_trips_non_ascii() {
  let (_rt, ctx, _host) = setup();
  let out = eval(
    &ctx,
    r#"
        const text = 'привет 🐕 日本語';
        const bytes = new TextEncoder().encode(text);
        JSON.stringify([
            bytes instanceof Uint8Array,
            bytes.length,
            new TextDecoder().decode(bytes) === text,
            new TextDecoder('UTF-8').decode(bytes.buffer) === text,
            new TextEncoder().encode().length,
            new TextDecoder().decode(),
            new TextDecoder().encoding,
        ]);
        "#,
  );
  assert_eq!(out, r#"[true,27,true,true,0,"","utf-8"]"#);
}

#[test]
fn text_decoder_refuses_an_encoding_it_cannot_do() {
  let (_rt, ctx, _host) = setup();
  let out = eval(
    &ctx,
    r#"
        let name = 'no-throw';
        try { new TextDecoder('utf-16'); } catch (e) { name = e.constructor.name; }
        name;
        "#,
  );
  assert_eq!(out, "RangeError");
}

#[test]
fn get_random_values_fills_the_caller_s_array_in_place() {
  let (_rt, ctx, _host) = setup();
  let out = eval(
    &ctx,
    r#"
        const array = new Uint8Array(4);
        const returned = crypto.getRandomValues(array);
        JSON.stringify([returned === array, Array.from(array)]);
        "#,
  );
  assert_eq!(out, "[true,[1,2,3,4]]");
}

/// a view over part of a buffer is the shape a `Uint8Array` subarray has, and the only bytes it
/// may touch are its own
#[test]
fn get_random_values_writes_only_an_offset_view_s_own_window() {
  let (_rt, ctx, _host) = setup();
  let out = eval(
    &ctx,
    r#"
        const buffer = new ArrayBuffer(8);
        const whole = new Uint8Array(buffer);
        const window = new Uint8Array(buffer, 2, 3);
        const returned = crypto.getRandomValues(window);
        JSON.stringify([returned === window, Array.from(window), Array.from(whole)]);
        "#,
  );
  assert_eq!(out, "[true,[1,2,3],[0,0,1,2,3,0,0,0]]");
}

#[test]
fn get_random_values_refuses_a_wrong_type_and_an_oversized_array() {
  let (_rt, ctx, _host) = setup();
  let out = eval(
    &ctx,
    r#"
        const caught = [];
        try { crypto.getRandomValues([0, 0]); } catch (e) { caught.push(e.constructor.name); }
        try { crypto.getRandomValues(new Uint8Array(65537)); } catch (e) { caught.push(e.name); }
        JSON.stringify(caught);
        "#,
  );
  assert_eq!(out, r#"["TypeError","QuotaExceededError"]"#);
}

#[test]
fn a_host_with_no_randomness_throws_rather_than_handing_back_zeroes() {
  let (_rt, ctx, host) = setup();
  *host.available.borrow_mut() = false;
  let out = eval(
    &ctx,
    r#"
        const array = new Uint8Array(4);
        let message = 'no-throw';
        try { crypto.getRandomValues(array); } catch (e) { message = e.message; }
        JSON.stringify([message, Array.from(array)]);
        "#,
  );
  assert_eq!(out, r#"["getRandomValues: the host has no randomness to give",[0,0,0,0]]"#);
}

#[test]
fn random_uuid_is_a_v4_uuid() {
  let (_rt, ctx, _host) = setup();
  let out = eval(
    &ctx,
    r#"
        const uuid = crypto.randomUUID();
        JSON.stringify([
            /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(uuid),
            uuid.length,
            crypto.randomUUID() !== uuid,
        ]);
        "#,
  );
  assert_eq!(out, "[true,36,true]");
}

#[test]
fn abort_fires_every_listener_exactly_once() {
  let (_rt, ctx, _host) = setup();
  let out = eval(
    &ctx,
    r#"
        const controller = new AbortController();
        const fired = [];
        const removed = () => fired.push('removed');
        controller.signal.addEventListener('abort', () => fired.push('a'));
        controller.signal.addEventListener('abort', removed);
        controller.signal.addEventListener('abort', () => fired.push('b'));
        controller.signal.removeEventListener('abort', removed);
        controller.signal.addEventListener('ignored', () => fired.push('wrong-type'));
        controller.abort();
        controller.abort('second');
        controller.signal.addEventListener('abort', () => fired.push('late'));
        JSON.stringify([
            fired,
            controller.signal.aborted,
            controller.signal.reason.name,
            controller.signal.reason instanceof DOMException,
        ]);
        "#,
  );
  assert_eq!(out, r#"[["a","b"],true,"AbortError",true]"#);
}

/// the other half of "fired once": the list is emptied, so a signal a plugin keeps around for
/// the life of the process does not pin every closure it was ever handed
#[test]
fn abort_drops_the_listeners_it_fired() {
  let (rt, ctx, _host) = setup();
  run(
    &ctx,
    r#"
        globalThis.__controller = new AbortController();
        globalThis.__ref = null;
        (() => {
            const listener = () => {};
            __ref = new WeakRef(listener);
            __controller.signal.addEventListener('abort', listener);
        })();
        "#,
  );
  rt.run_gc();
  assert_eq!(eval(&ctx, "String(typeof __ref.deref())"), "function", "held while registered");

  run(&ctx, "__controller.abort();");
  rt.run_gc();
  assert_eq!(eval(&ctx, "String(typeof __ref.deref())"), "undefined");
}

#[test]
fn an_explicit_reason_wins_and_a_throwing_listener_does_not_stop_the_rest() {
  let (_rt, ctx, _host) = setup();
  let out = eval(
    &ctx,
    r#"
        globalThis.console = { error: () => {} };
        const controller = new AbortController();
        const fired = [];
        controller.signal.addEventListener('abort', () => { throw new Error('boom'); });
        controller.signal.addEventListener('abort', () => fired.push('after'));
        controller.abort('cancelled by user');
        let illegal = 'no-throw';
        try { new AbortSignal(); } catch (e) { illegal = e.constructor.name; }
        JSON.stringify([fired, controller.signal.reason, illegal]);
        "#,
  );
  assert_eq!(out, r#"[["after"],"cancelled by user","TypeError"]"#);
}

#[test]
fn structured_clone_deep_copies_and_keeps_cycles() {
  let (_rt, ctx, _host) = setup();
  let out = eval(
    &ctx,
    r#"
        const source = { n: 1, nested: { list: [1, { deep: true }] }, when: new Date(1700000000000) };
        source.self = source;
        source.map = new Map([['k', { v: 1 }]]);
        source.set = new Set([1, 2]);
        source.re = /ab+c/gi;
        source.bytes = new Uint8Array([1, 2, 3]);
        const clone = structuredClone(source);
        clone.nested.list[1].deep = false;
        clone.bytes[0] = 9;
        JSON.stringify([
            clone !== source,
            clone.self === clone,
            source.nested.list[1].deep,
            clone.when instanceof Date && clone.when.getTime() === 1700000000000,
            clone.map.get('k').v === 1 && clone.map.get('k') !== source.map.get('k'),
            [...clone.set],
            clone.re.source === 'ab+c' && clone.re.flags === 'gi',
            Array.from(source.bytes),
            Array.from(clone.bytes),
        ]);
        "#,
  );
  assert_eq!(out, r#"[true,true,true,true,true,[1,2],true,[1,2,3],[9,2,3]]"#,);
}

#[test]
fn structured_clone_refuses_what_it_cannot_copy() {
  let (_rt, ctx, _host) = setup();
  let out = eval(
    &ctx,
    r#"
        const refused = (value) => {
            try { structuredClone(value); return 'no-throw'; }
            catch (e) { return `${e.name}:${e instanceof DOMException}`; }
        };
        JSON.stringify([
            refused(() => {}),
            refused(Symbol('s')),
            refused({ fn: () => {} }),
            refused(Promise.resolve()),
            refused(new WeakMap()),
        ]);
        "#,
  );
  assert_eq!(
    out,
    r#"["DataCloneError:true","DataCloneError:true","DataCloneError:true","DataCloneError:true","DataCloneError:true"]"#,
  );
}

/// the web version preserves reference identity, not just cycles: a graph that shared a node
/// before the clone shares one after it
#[test]
fn structured_clone_keeps_shared_references_shared() {
  let (_rt, ctx, _host) = setup();
  let out = eval(
    &ctx,
    r#"
        const shared = { n: 1 };
        const when = new Date(0);
        const buffer = new ArrayBuffer(4);
        const source = {
            a: shared, b: shared, when, alsoWhen: when, buffer,
            view: new Uint8Array(buffer), re: /x/g,
        };
        source.reAgain = source.re;
        const clone = structuredClone(source);
        clone.view[0] = 7;
        JSON.stringify([
            clone.a === clone.b && clone.a !== shared,
            clone.when === clone.alsoWhen && clone.when !== when,
            clone.re === clone.reAgain,
            clone.view.buffer === clone.buffer,
            new Uint8Array(clone.buffer)[0],
            new Uint8Array(buffer)[0],
        ]);
        "#,
  );
  assert_eq!(out, "[true,true,true,true,7,0]");
}

/// answers every trap out of one immutable object, which is all a view needs to be reachable
/// from JS - the traps themselves are `tl_proxy`'s own tests' business
struct OneMessageTl;

impl crate::api::tl::proxy::TlHost for OneMessageTl {
  fn tl_get(&self, _handle: i64, key: &str) -> String {
    match key {
      "_" => "Smessage".to_string(),
      "message" => "Shi".to_string(),
      _ => "N".to_string(),
    }
  }

  fn tl_set(&self, _handle: i64, _key: &str, _value_wire: &str) -> Option<String> {
    Some("not writable in this fixture".to_string())
  }

  fn tl_has(&self, _handle: i64, key: &str) -> i32 {
    i32::from(key == "message")
  }

  fn tl_own_keys(&self, _handle: i64) -> Option<String> {
    Some("message".to_string())
  }

  fn tl_copy(&self, _handle: i64) -> Option<String> {
    Some(r#"{"_":"message","message":"hi"}"#.to_string())
  }

  fn tl_release(&self, _handle: i64) {}
}

/// the decision recorded in `common.d.ts`: a view is a host object, so cloning one throws
/// instead of quietly walking the whole graph over the bridge. Against a real proxy rather than
/// an object carrying the marker, because what is being pinned is that the duck-typing and what
/// `tl_proxy` answers still agree.
#[test]
fn structured_clone_throws_on_a_tl_view() {
  use crate::api::tl::proxy::{wire_to_js_value, TlHost, TlViews, ViewLife};

  let (_rt, ctx, _host) = setup();
  let views = TlViews::new(Rc::new(OneMessageTl) as Rc<dyn TlHost>);
  let out = ctx.with(|ctx| {
    let view = wire_to_js_value(&ctx, &views, "HOW1", ViewLife::Plugin).unwrap();
    ctx.globals().set("__view", view).unwrap();
    match ctx.eval::<String, _>(
      r#"
            let name = 'no-throw';
            try { structuredClone(__view); } catch (e) { name = e.name; }
            JSON.stringify([
                __view.message,
                name,
                structuredClone({ detached: __view.toJSON() }).detached.message,
                structuredClone({ plain: 'hi' }).plain,
            ]);
            "#,
    ) {
      Ok(s) => s,
      Err(rquickjs::Error::Exception) => {
        panic!("{}", crate::api::telegram::rpc::format_exception(&ctx))
      }
      Err(e) => panic!("{e:?}"),
    }
  });
  assert_eq!(out, r#"["hi","DataCloneError","hi","hi"]"#);
}

/// installed from here rather than from a call of their own, and the fixture's spill directory
/// is empty on purpose: an engine that cannot spill still has working in-memory blobs
#[test]
fn blob_and_file_come_with_the_globals_and_work_without_a_spill_directory() {
  let (_rt, ctx, _host) = setup();
  let out = eval(
    &ctx,
    r#"
        const blob = new Blob(['hi'], { type: 'text/plain' });
        const file = new File(['x'], 'n.txt', { lastModified: 7 });
        JSON.stringify([
            typeof Blob, typeof File,
            blob.size, blob.type, blob.slice(1).size,
            file instanceof Blob, file.name, file.lastModified,
        ]);
        "#,
  );
  assert_eq!(out, r#"["function","function",2,"text/plain",1,true,"n.txt",7]"#);
}

/// the prelude's own type table would otherwise clone a blob into `{}`, silently
#[test]
fn structured_clone_hands_back_a_second_handle_over_the_same_blob() {
  let (_rt, ctx, _host) = setup();
  let out = eval(
    &ctx,
    r#"
        const blob = new Blob(['hello']);
        const graph = structuredClone({ a: blob, b: blob, f: new File(['x'], 'n.txt') });
        let impostor = 'no-throw';
        try { structuredClone(Object.create(Blob.prototype)); } catch (e) { impostor = e.name; }
        JSON.stringify([
            graph.a instanceof Blob, graph.a !== blob, graph.a.size,
            graph.a === graph.b,
            graph.f instanceof File, graph.f.name,
            impostor,
        ]);
        "#,
  );
  assert_eq!(out, r#"[true,true,5,true,true,"n.txt","DataCloneError"]"#);
}

fn file_mtime_millis(path: &Path) -> i64 {
  let modified = std::fs::metadata(path).unwrap().modified().unwrap();
  modified.duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as i64
}

/// media the app downloaded is handed over as a blob over the app's own file, so the engine
/// must never be what deletes it - not on `dispose()`, not on collection, not on teardown
#[test]
fn an_app_owned_file_outlives_every_blob_over_it() {
  let dir = TestDir::new("app-file-life");
  let path = dir.0.join("photo.jpg");
  std::fs::write(&path, b"0123456789").unwrap();
  {
    let (rt, ctx, _host) = setup();
    ctx.with(|ctx| {
      let value =
        crate::api::io::blob::mint_app_file(&ctx, &path, 10, "image/jpeg", Some("photo.jpg"), file_mtime_millis(&path))
          .unwrap();
      ctx.globals().set("__f", value).unwrap();
    });
    assert_eq!(settle(&rt, &ctx, "__f.text()"), "0123456789", "the blob is really over that file");

    run(&ctx, "globalThis.__kept = __f.slice(0, 2); __f.dispose();");
    assert!(path.exists(), "dispose freed the app's own file");

    run(&ctx, "globalThis.__f = null; globalThis.__kept = null;");
    rt.run_gc();
    assert!(path.exists(), "collecting the handle freed the app's own file");
  }
  assert!(path.exists(), "dropping the engine freed the app's own file");
  assert_eq!(std::fs::read(&path).unwrap(), b"0123456789");
}

/// the other way a spill ends: a build that opened one and then threw partway through. The
/// bytes already written are nobody's, and nothing will come back for them.
#[test]
fn a_build_that_throws_after_it_started_spilling_leaves_nothing_behind() {
  let dir = TestDir::new("half-written");
  let (_rt, ctx, _host) = setup_in(&dir.0);
  let out = eval(
    &ctx,
    &format!(
      r#"
            let out = 'no-throw';
            const past = new Uint8Array({});
            try {{ new Blob([past, {{ toString() {{ throw new Error('halfway') }} }}]); }}
            catch (e) {{ out = e.message; }}
            out;
            "#,
      crate::api::io::blob::SPILL_THRESHOLD_BYTES + 1,
    ),
  );
  assert_eq!(out, "halfway");
  assert!(dir.entries().is_empty(), "the half-written spill is still there: {:?}", dir.entries());

  // and the room it had reserved came back with it, or the next build inherits the debt
  run(
    &ctx,
    &format!("globalThis.__b = new Blob([new Uint8Array({})]);", crate::api::io::blob::SPILL_THRESHOLD_BYTES + 1),
  );
  assert_eq!(dir.entries().len(), 1);
}

/// the exception `common.d.ts` has to admit to: an engine the host could not make a spill
/// directory for. Everywhere else a refused charge is a routing decision, here it is a failure.
#[test]
fn with_nowhere_to_spill_a_full_native_budget_is_the_one_way_building_a_blob_fails() {
  let (_rt, ctx, _host) = setup();
  ctx.with(|ctx| crate::api::error::install_plugin_error(&ctx, &crate::testing::harness::inu_namespace(&ctx)).unwrap());
  run(
    &ctx,
    &format!(
      r#"
            globalThis.__held = [];
            const chunk = new Uint8Array({});
            for (let i = 0; i < {}; i++) __held.push(new Blob([chunk]));
            "#,
      crate::api::io::blob::SPILL_THRESHOLD_BYTES,
      crate::sandbox::limits::EXTERNAL_LIMIT_BYTES as u64 / crate::api::io::blob::SPILL_THRESHOLD_BYTES,
    ),
  );
  let out = eval(
    &ctx,
    r#"
        let out = 'no-throw';
        try { new Blob(['x']); } catch (e) { out = [e instanceof inu.PluginError, e.code].join('|'); }
        out;
        "#,
  );
  assert_eq!(out, "true|quota-exceeded");
}

#[test]
fn structured_clone_copies_errors_and_boxed_primitives() {
  let (_rt, ctx, _host) = setup();
  let out = eval(
    &ctx,
    r#"
        const err = new TypeError('nope');
        err.cause = { why: 1 };
        const clone = structuredClone({ err, boxed: new Number(7) });
        JSON.stringify([
            clone.err instanceof TypeError,
            clone.err.message,
            clone.err.cause.why === 1 && clone.err.cause !== err.cause,
            clone.boxed.valueOf(),
            typeof clone.boxed,
        ]);
        "#,
  );
  assert_eq!(out, r#"[true,"nope",true,7,"object"]"#);
}

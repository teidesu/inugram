use super::*;
use crate::testing::harness::{CountingRandom, TestDir};
use rquickjs::{Context, Runtime};

fn setup() -> (Runtime, Context, Rc<CountingRandom>) {
  setup_in(Path::new(""))
}

fn setup_in(spill_dir: &Path) -> (Runtime, Context, Rc<CountingRandom>) {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let host = Rc::new(CountingRandom::default());
  let host_dyn: Rc<dyn RandomHost> = host.clone();
  ctx.with(|ctx| install_globals(&ctx, host_dyn, spill_dir, ExternalMemory::new()).unwrap());
  (rt, ctx, host)
}

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

#[test]
fn installing_into_a_context_without_the_intrinsics_refuses() {
  let rt = Runtime::new().unwrap();
  let ctx = Context::base(&rt).unwrap();
  let host: Rc<dyn RandomHost> = Rc::new(CountingRandom::default());
  let message = ctx.with(|ctx| {
    let Err(err) = install_globals(&ctx, host, Path::new(""), ExternalMemory::new()) else {
      panic!("a base context must be refused");
    };
    assert!(matches!(err, rquickjs::Error::Exception), "got: {err:?}");
    crate::api::error::format_exception(&ctx)
  });
  assert!(message.contains("Context::full"), "got: {message}");
  assert!(REQUIRED_INTRINSICS.iter().any(|name| message.contains(&format!("no '{name}'"))), "got: {message}",);
  ctx.with(|ctx| assert!(ctx.globals().get::<_, Value>("TextEncoder").unwrap().is_undefined()));
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
fn a_host_with_no_randomness_throws_rather_than_handing_back_zeroes() {
  let (_rt, ctx, host) = setup();
  host.available.set(false);
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
        clone.re === clone.reAgain && clone.re.flags === 'g',
        clone.view.buffer === clone.buffer,
        new Uint8Array(clone.buffer)[0],
        new Uint8Array(buffer)[0],
      ]);
    "#,
  );
  assert_eq!(out, "[true,true,true,true,7,0]");
}

/// A blob wrapping app-downloaded media must never delete the app's file: on dispose, garbage
/// collection, or engine teardown.
#[test]
fn an_app_owned_file_outlives_every_blob_over_it() {
  let dir = TestDir::new("app-file-life");
  let path = dir.path().join("photo.jpg");
  std::fs::write(&path, b"0123456789").unwrap();
  {
    let (rt, ctx, _host) = setup();
    ctx.with(|ctx| {
      let value = crate::api::io::blob::mint_app_file(
        &ctx,
        &path,
        10,
        "image/jpeg",
        Some("photo.jpg"),
        crate::api::io::blob::mtime_millis(std::fs::metadata(&path).unwrap().modified().ok()),
      )
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

/// A failed blob build must remove partial spill data; no handle exists to clean it up later.
#[test]
fn a_build_that_throws_after_it_started_spilling_leaves_nothing_behind() {
  let dir = TestDir::new("half-written");
  let (_rt, ctx, _host) = setup_in(dir.path());
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

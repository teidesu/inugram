use super::*;
use rquickjs::Context;

#[derive(Default)]
struct TestTimerHost {
  now: Cell<u64>,
  wakes: RefCell<Vec<i64>>,
}

impl TimerHost for TestTimerHost {
  fn schedule_wake(&self, delay_ms: i64) {
    self.wakes.borrow_mut().push(delay_ms);
  }

  fn now_ms(&self) -> u64 {
    self.now.get()
  }
}

type Disposing = crate::testing::harness::DisposeOnDrop<TimerState>;
type Fixture = (
  Runtime,
  Context,
  Rc<TestTimerHost>,
  Rc<Lifecycle>,
  Disposing,
  std::sync::Arc<crate::testing::harness::Logs>,
);

fn setup() -> Fixture {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let host = Rc::new(TestTimerHost::default());
  let host_dyn: Rc<dyn TimerHost> = host.clone();
  let lifecycle = Lifecycle::new();
  let logs = crate::testing::harness::Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  let state = ctx.with(|ctx| {
    crate::api::error::install_plugin_error(&ctx).unwrap();
    install_timers(&ctx, host_dyn, lifecycle.clone(), log).unwrap()
  });
  let state = Disposing::new(&ctx, state, |ctx, state| state.dispose(ctx));
  (rt, ctx, host, lifecycle, state, logs)
}

use crate::testing::harness::eval_string as eval;

use crate::testing::harness::eval_unit as run;

#[test]
fn a_timeout_fires_once_its_delay_has_passed() {
  let (rt, ctx, host, _lifecycle, state, _logs) = setup();
  run(&ctx, "globalThis.__fired = []; setTimeout(() => __fired.push('a'), 50);");

  host.now.set(49);
  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "JSON.stringify(__fired)"), "[]");

  host.now.set(50);
  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "JSON.stringify(__fired)"), r#"["a"]"#);

  host.now.set(200);
  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "JSON.stringify(__fired)"), r#"["a"]"#, "a timeout fires once");
  assert!(state.timers.is_empty());
}

#[test]
fn due_timers_fire_by_deadline_then_by_arming_order() {
  let (rt, ctx, host, _lifecycle, state, _logs) = setup();
  run(
    &ctx,
    r#"
        globalThis.__fired = [];
        setTimeout(() => __fired.push('late'), 20);
        setTimeout(() => __fired.push('first-of-10'), 10);
        setTimeout(() => __fired.push('second-of-10'), 10);
        setTimeout(() => __fired.push('now'), 0);
        "#,
  );
  host.now.set(100);
  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "JSON.stringify(__fired)"), r#"["now","first-of-10","second-of-10","late"]"#,);
}

#[test]
fn an_interval_repeats_and_a_missed_run_is_not_replayed() {
  let (rt, ctx, host, _lifecycle, state, _logs) = setup();
  run(&ctx, "globalThis.__ticks = 0; globalThis.__id = setInterval(() => { __ticks++; }, 100);");

  host.now.set(100);
  state.run_due(&rt, &ctx);
  host.now.set(200);
  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "String(__ticks)"), "2");

  // nothing ticked for a while (the app was in the background): one catch-up run, not five
  host.now.set(700);
  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "String(__ticks)"), "3");
  assert_eq!(eval(&ctx, "String(__id > 0)"), "true");

  // and the period restarts from the catch-up run rather than from the deadline it missed:
  // 300 + 100 would have been due long ago, 700 + 100 is not
  assert_eq!(*host.wakes.borrow().last().unwrap(), 100);
  host.now.set(799);
  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "String(__ticks)"), "3", "re-armed from now, not from the missed deadline");
  host.now.set(800);
  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "String(__ticks)"), "4");

  run(&ctx, "clearInterval(__id);");
  host.now.set(1500);
  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "String(__ticks)"), "4");
  assert!(state.timers.is_empty());
}

#[test]
fn an_interval_asking_for_zero_repeats_at_the_floor() {
  let floor = MIN_INTERVAL_MS;

  let (rt, ctx, host, _lifecycle, state, _logs) = setup();
  run(&ctx, "globalThis.__ticks = 0; globalThis.__id = setInterval(() => { __ticks++; }, 0);");
  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "String(__ticks)"), "1");
  // asking for zero must not have asked for the shared queue straight back
  assert_eq!(*host.wakes.borrow().last().unwrap(), floor as i64);

  host.now.set(floor - 1);
  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "String(__ticks)"), "1", "the period is floored, not the first run");
  host.now.set(floor);
  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "String(__ticks)"), "2");

  run(&ctx, "clearInterval(__id);");
  assert!(state.timers.is_empty());
}

#[test]
fn clearing_from_inside_a_callback_stops_a_timer_due_in_the_same_tick() {
  let (rt, ctx, host, _lifecycle, state, _logs) = setup();
  run(
    &ctx,
    r#"
        globalThis.__fired = [];
        const doomed = setTimeout(() => __fired.push('doomed'), 20);
        setTimeout(() => { __fired.push('first'); clearTimeout(doomed); }, 10);
        "#,
  );
  host.now.set(50);
  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "JSON.stringify(__fired)"), r#"["first"]"#);
}

#[test]
fn a_timer_armed_inside_a_callback_waits_for_the_next_tick() {
  let (rt, ctx, host, _lifecycle, state, _logs) = setup();
  run(
    &ctx,
    r#"
        globalThis.__fired = [];
        setTimeout(() => {
            __fired.push('outer');
            setTimeout(() => __fired.push('inner'), 0);
        }, 10);
        "#,
  );
  host.now.set(10);
  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "JSON.stringify(__fired)"), r#"["outer"]"#);

  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "JSON.stringify(__fired)"), r#"["outer","inner"]"#);
}

#[test]
fn unloading_cancels_every_pending_timer() {
  let (rt, ctx, host, lifecycle, state, _logs) = setup();
  run(
    &ctx,
    r#"
        globalThis.__fired = [];
        setInterval(() => __fired.push('interval'), 10);
        setTimeout(() => __fired.push('timeout'), 10);
        "#,
  );
  assert!(!state.timers.is_empty());

  lifecycle.begin_unload();
  state.notify_unload(&ctx);
  assert!(state.timers.is_empty());
  assert_eq!(*host.wakes.borrow().last().unwrap(), CANCEL_WAKE);

  host.now.set(1000);
  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "JSON.stringify(__fired)"), "[]");

  // and nothing can arm a new one afterwards
  run(&ctx, "globalThis.__late = setTimeout(() => __fired.push('late'), 0);");
  assert_eq!(eval(&ctx, "String(__late)"), "0");
  assert!(state.timers.is_empty());
}

/// an engine destroyed without ever being told to unload: the wake outlives the runtime, and the
/// runnable answering it holds the whole plugin
#[test]
fn destroying_an_engine_that_was_never_unloaded_withdraws_its_wake() {
  let (_rt, ctx, host, _lifecycle, state, _logs) = setup();
  run(&ctx, "setInterval(() => {}, 100);");
  assert_eq!(*host.wakes.borrow(), vec![100]);

  state.dispose(&ctx);
  assert!(state.timers.is_empty());
  assert_eq!(*host.wakes.borrow(), vec![100, CANCEL_WAKE]);
}

#[test]
fn the_host_is_woken_for_the_earliest_deadline_only() {
  let (rt, ctx, host, _lifecycle, state, _logs) = setup();
  run(&ctx, "globalThis.__a = setTimeout(() => {}, 500);");
  assert_eq!(*host.wakes.borrow(), vec![500]);

  run(&ctx, "setTimeout(() => {}, 900);");
  assert_eq!(*host.wakes.borrow(), vec![500], "a later deadline re-arms nothing");

  run(&ctx, "setTimeout(() => {}, 100);");
  assert_eq!(*host.wakes.borrow(), vec![500, 100]);

  host.now.set(100);
  state.run_due(&rt, &ctx);
  assert_eq!(*host.wakes.borrow(), vec![500, 100, 400], "the next wake is relative to now");

  run(&ctx, "clearTimeout(__a);");
  assert_eq!(*host.wakes.borrow(), vec![500, 100, 400, 800]);

  host.now.set(900);
  state.run_due(&rt, &ctx);
  assert!(state.timers.is_empty());
  assert_eq!(
    *host.wakes.borrow(),
    vec![500, 100, 400, 800],
    "the wake that just fired is spent, so emptying the wheel inside a tick withdraws nothing",
  );

  run(&ctx, "globalThis.__b = setTimeout(() => {}, 10); clearTimeout(__b);");
  assert_eq!(
    *host.wakes.borrow(),
    vec![500, 100, 400, 800, 10, CANCEL_WAKE],
    "clearing the last timer outside a tick does withdraw",
  );
}

#[test]
fn backgrounding_floors_the_wheel_and_returning_lifts_it() {
  let (rt, ctx, host, _lifecycle, state, _logs) = setup();
  run(&ctx, "globalThis.__ticks = 0; setInterval(() => { __ticks++; }, 100);");

  host.now.set(100);
  state.run_due(&rt, &ctx);
  assert_eq!(*host.wakes.borrow().last().unwrap(), 100, "the foreground wake is the timer's own");

  host.now.set(200);
  state.set_visible(false);
  assert_eq!(
    *host.wakes.borrow().last().unwrap(),
    BACKGROUND_MIN_INTERVAL_MS as i64 - 100,
    "hiding re-arms for one tick per background interval, measured from the last tick",
  );

  host.now.set(1100);
  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "String(__ticks)"), "2", "ten missed periods collapse into one tick");
  assert_eq!(*host.wakes.borrow().last().unwrap(), BACKGROUND_MIN_INTERVAL_MS as i64);

  host.now.set(1150);
  state.set_visible(true);
  assert_eq!(*host.wakes.borrow().last().unwrap(), 50, "returning restores the timer's own deadline");
  host.now.set(1200);
  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "String(__ticks)"), "3");
}

#[test]
fn a_timer_armed_while_hidden_is_floored_too() {
  let (rt, ctx, host, _lifecycle, state, _logs) = setup();
  state.set_visible(false);
  host.now.set(500);
  state.run_due(&rt, &ctx);

  run(&ctx, "setTimeout(() => {}, 0);");
  assert_eq!(
    *host.wakes.borrow().last().unwrap(),
    BACKGROUND_MIN_INTERVAL_MS as i64,
    "a zero-delay timer waits for the wheel's next allowed tick rather than spinning it",
  );
}

#[test]
fn a_long_hidden_app_drops_to_the_intensive_floor() {
  let (rt, ctx, host, _lifecycle, state, _logs) = setup();
  run(&ctx, "setInterval(() => {}, 100);");
  state.set_visible(false);

  host.now.set(BACKGROUND_INTENSIVE_AFTER_MS - 1);
  state.run_due(&rt, &ctx);
  assert_eq!(*host.wakes.borrow().last().unwrap(), BACKGROUND_MIN_INTERVAL_MS as i64);

  host.now.set(BACKGROUND_INTENSIVE_AFTER_MS);
  state.run_due(&rt, &ctx);
  assert_eq!(*host.wakes.borrow().last().unwrap(), BACKGROUND_INTENSIVE_INTERVAL_MS as i64);
}

/// the app's own request is parked behind this plugin, and the chain that carries it dies after
/// 10s: a floor of up to a minute would report a committed send as failed
#[test]
fn a_dispatch_the_app_is_parked_behind_lifts_the_floor() {
  let (rt, ctx, host, lifecycle, state, _logs) = setup();
  state.set_visible(false);
  host.now.set(BACKGROUND_INTENSIVE_AFTER_MS);
  state.run_due(&rt, &ctx);

  lifecycle.set_blocking_dispatches(1);
  run(&ctx, "globalThis.__resumed = false; setTimeout(() => { __resumed = true; }, 50);");
  assert_eq!(
    *host.wakes.borrow().last().unwrap(),
    50,
    "an interceptor awaiting a timer gets its own deadline, hidden or not",
  );

  host.now.set(BACKGROUND_INTENSIVE_AFTER_MS + 50);
  state.run_due(&rt, &ctx);
  assert_eq!(eval(&ctx, "String(__resumed)"), "true");

  lifecycle.set_blocking_dispatches(0);
  run(&ctx, "setInterval(() => {}, 10);");
  assert_eq!(
    *host.wakes.borrow().last().unwrap(),
    BACKGROUND_INTENSIVE_INTERVAL_MS as i64,
    "and the floor is back once nothing is waiting on the plugin",
  );
}

#[test]
fn re_announcing_the_state_the_wheel_is_already_in_changes_nothing() {
  let (rt, ctx, host, _lifecycle, state, _logs) = setup();
  run(&ctx, "setInterval(() => {}, 100);");
  state.set_visible(false);
  host.now.set(BACKGROUND_INTENSIVE_AFTER_MS - 1);
  state.run_due(&rt, &ctx);

  let before = host.wakes.borrow().len();
  state.set_visible(false);
  assert_eq!(host.wakes.borrow().len(), before, "no re-arm");

  // and the clock the intensive stage is measured from was not restarted by it
  host.now.set(BACKGROUND_INTENSIVE_AFTER_MS);
  state.run_due(&rt, &ctx);
  assert_eq!(*host.wakes.borrow().last().unwrap(), BACKGROUND_INTENSIVE_INTERVAL_MS as i64);
}

#[test]
fn a_throwing_callback_is_logged_and_the_rest_still_run() {
  let (rt, ctx, host, _lifecycle, state, logs) = setup();
  run(
    &ctx,
    r#"
        globalThis.__fired = [];
        setTimeout(() => { throw new Error('tick-boom'); }, 10);
        setTimeout(() => __fired.push('after'), 20);
        "#,
  );
  host.now.set(50);
  state.run_due(&rt, &ctx);

  assert_eq!(eval(&ctx, "JSON.stringify(__fired)"), r#"["after"]"#);
  assert!(
    logs.borrow().iter().any(|l| l.contains("timer callback threw") && l.contains("tick-boom")),
    "got: {:?}",
    logs.borrow(),
  );
}

#[test]
fn ids_are_unique_never_reused_and_clearing_a_stale_one_is_a_no_op() {
  let (_rt, ctx, _host, _lifecycle, state, _logs) = setup();
  let out = eval(
    &ctx,
    r#"
        const first = setTimeout(() => {}, 10);
        const second = setInterval(() => {}, 10);
        clearTimeout(first);
        const third = setTimeout(() => {}, 10);
        clearInterval(second);
        clearTimeout(first);
        clearTimeout(undefined);
        clearTimeout(0);
        clearTimeout('nonsense');
        JSON.stringify([first !== second, second !== third, first !== third]);
        "#,
  );
  assert_eq!(out, "[true,true,true]");
  assert_eq!(state.timers.len(), 1, "only the live timer is left");
}

#[test]
fn a_non_function_callback_throws_a_type_error() {
  let (_rt, ctx, _host, _lifecycle, _state, _logs) = setup();
  let out = eval(
    &ctx,
    r#"
        const caught = [];
        try { setTimeout('alert(1)', 0); } catch (e) { caught.push(`${e.constructor.name}:${e.message}`); }
        try { setInterval(undefined, 0); } catch (e) { caught.push(`${e.constructor.name}:${e.message}`); }
        JSON.stringify(caught);
        "#,
  );
  assert_eq!(
    out,
    r#"["TypeError:setTimeout: callback must be a function","TypeError:setInterval: callback must be a function"]"#,
  );
}

#[test]
fn live_timers_are_capped_and_clearing_one_restores_capacity() {
  let (_rt, ctx, _host, _lifecycle, state, _logs) = setup();
  let out = eval(
    &ctx,
    &format!(
      r#"
        const ids = [];
        for (let i = 0; i < {TIMER_LIMIT}; i++) ids.push(setInterval(() => {{}}, 100));
        let refused;
        try {{ setTimeout(() => {{}}, 100); }} catch (e) {{ refused = [e.code, e.usage, e.quota]; }}
        clearInterval(ids.pop());
        const replacement = setTimeout(() => {{}}, 100);
        JSON.stringify([refused, replacement > 0]);
        "#,
    ),
  );
  assert_eq!(out, format!(r#"[["quota-exceeded",{},{TIMER_LIMIT}],true]"#, TIMER_LIMIT + 1));
  assert_eq!(state.timers.len(), TIMER_LIMIT);
}

#[test]
fn a_nonsense_delay_is_taken_as_zero_and_a_huge_one_is_clamped() {
  let (_rt, ctx, host, _lifecycle, _state, _logs) = setup();
  run(
    &ctx,
    r#"
        setTimeout(() => {}, NaN);
        setTimeout(() => {}, -5);
        setTimeout(() => {});
        setTimeout(() => {}, 1e30);
        "#,
  );
  assert_eq!(*host.wakes.borrow(), vec![0]);
  assert_eq!(clamp_delay(Some(1e30)), MAX_DELAY_MS);
  assert_eq!(clamp_delay(Some(f64::NAN)), 0);
}

/// Runs the bundled timers oracle with a timer wheel, `localStorage`, `inu.onUnload`, and a host that
/// serves wakes. Uses a separate fixture because it needs all three. The clock must be real: the
/// oracle measures elapsed time with `performance.now()`.
#[cfg(test)]
mod bundled_oracle {
  use super::*;
  use crate::sandbox::grants::TestGrantHost;
  use rquickjs::Context;

  const ORACLE: &str = include_str!("../../../test/plugins/timers-test.js");

  /// A Rust port of TimerThrottle for the oracle's pacing assertion. It tests this driver against
  /// the rule, not the Android throttle on globalQueue; `TimerThrottle.kt` needs its own test.
  /// Other assertions exercise the real timer wheel.
  const DUTY_PERCENT: u128 = 10;
  const MIN_WAKE_GAP_MS: u64 = 4;

  /// the wake the engine last asked for, in real `monotonic_now_ms` terms
  #[derive(Default)]
  struct WakeHost {
    wanted_at: Cell<Option<u64>>,
  }

  impl TimerHost for WakeHost {
    fn schedule_wake(&self, delay_ms: i64) {
      self.wanted_at.set(if delay_ms < 0 { None } else { Some(monotonic_now_ms() + delay_ms as u64) });
    }

    fn now_ms(&self) -> u64 {
      monotonic_now_ms()
    }
  }

  #[test]
  fn the_bundled_timers_test_plugin_passes() {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let lifecycle = Lifecycle::new();
    let wakes = Rc::new(WakeHost::default());
    let wakes_dyn: Rc<dyn TimerHost> = wakes.clone();
    let storage_file = crate::testing::harness::TempPath::default();
    let grants = TestGrantHost::new(&crate::testing::harness::manifest_grants(ORACLE)).as_host();
    let log: crate::Log = std::sync::Arc::new(|_| {});

    let (api, timers) = ctx.with(|ctx| {
      let inu = crate::testing::harness::get_api_globals(&ctx);
      crate::api::error::install_plugin_error(&ctx).unwrap();
      let api =
        crate::api::lifecycle::install_lifecycle(&ctx, grants.clone(), lifecycle.clone(), log.clone(), &inu).unwrap();
      crate::api::io::local_storage::install_local_storage(&ctx, storage_file.0.clone()).unwrap();
      let timers = install_timers(&ctx, wakes_dyn, lifecycle.clone(), log.clone()).unwrap();
      (api, timers)
    });

    let lines = crate::testing::harness::install_capturing_console(&ctx);
    crate::testing::harness::eval_unit(&ctx, ORACLE);

    // driven until the oracle says it is done rather than until the wheel is empty: it
    // deliberately leaves a 30 s timer armed, which is the one the unload half is about
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    let mut ready_at = 0u64;
    while let Some(wanted_at) = wakes.wanted_at.get() {
      if lines.borrow().iter().any(|l| l == "timers test done") {
        break;
      }
      assert!(std::time::Instant::now() < deadline, "the oracle never finished");
      let at = wanted_at.max(ready_at);
      let now = monotonic_now_ms();
      if at > now {
        std::thread::sleep(std::time::Duration::from_millis(at - now));
      }
      wakes.wanted_at.set(None);
      let started = std::time::Instant::now();
      timers.run_due(&rt, &ctx);
      let cost = started.elapsed().as_nanos();
      let cooldown = (cost / DUTY_PERCENT * (100 - DUTY_PERCENT)).div_ceil(1_000_000) as u64;
      ready_at = monotonic_now_ms() + cooldown.max(MIN_WAKE_GAP_MS);
    }

    api.notify_unload(&rt, &ctx);
    api.dispose(&ctx);
    timers.dispose(&ctx);

    let lines = lines.borrow();
    crate::testing::harness::assert_oracle_exact(&lines, "timers test done", 5);
  }

  const VISIBILITY_ORACLE: &str = include_str!("../../../test/plugins/visibility-test.js");

  /// A WakeHost with a manually advanced clock. The visibility oracle tests when ticks are allowed,
  /// so this avoids four seconds of real waiting. Its near-zero `performance.now()` readings make
  /// the derived bound stricter.
  #[derive(Default)]
  struct SteppedWakeHost {
    now: Cell<u64>,
    wanted_at: Cell<Option<u64>>,
  }

  impl TimerHost for SteppedWakeHost {
    fn schedule_wake(&self, delay_ms: i64) {
      self.wanted_at.set(if delay_ms < 0 { None } else { Some(self.now.get() + delay_ms as u64) });
    }

    fn now_ms(&self) -> u64 {
      self.now.get()
    }
  }

  /// serves every wake the engine asks for up to `to`, exactly as the host's queue would
  fn advance(rt: &Runtime, ctx: &Context, timers: &Rc<TimerState>, host: &Rc<SteppedWakeHost>, to: u64) {
    while let Some(at) = host.wanted_at.get() {
      if at > to {
        break;
      }
      host.now.set(at.max(host.now.get()));
      host.wanted_at.set(None);
      timers.run_due(rt, ctx);
    }
    host.now.set(to);
  }

  /// the wheel first, then the callbacks - the order `nativeAppVisibilityChanged` fixes, so a
  /// callback arming a timer arms it against the new floor
  fn set_app_visible(
    rt: &Runtime,
    ctx: &Context,
    api: &Rc<crate::api::lifecycle::LifecycleState>,
    timers: &Rc<TimerState>,
    visible: bool,
  ) {
    timers.set_visible(visible);
    api.app_visibility_changed(
      rt,
      ctx,
      if visible {
        crate::api::lifecycle::AppMode::Foreground
      } else {
        crate::api::lifecycle::AppMode::Background
      },
    );
  }

  /// `inu.onAppVisibilityChange` and the background floor are one feature split across two
  /// modules, and this oracle is the only thing that asserts they add up: it counts a 100 ms
  /// interval's ticks across a hidden stretch and holds them to what one wake per second allows.
  #[test]
  fn the_bundled_visibility_test_plugin_passes() {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let lifecycle = Lifecycle::new();
    let wakes = Rc::new(SteppedWakeHost::default());
    let wakes_dyn: Rc<dyn TimerHost> = wakes.clone();
    let grants = TestGrantHost::new(&crate::testing::harness::manifest_grants(VISIBILITY_ORACLE)).as_host();
    let log: crate::Log = std::sync::Arc::new(|_| {});

    let (api, timers) = ctx.with(|ctx| {
      let inu = crate::testing::harness::get_api_globals(&ctx);
      crate::api::error::install_plugin_error(&ctx).unwrap();
      let api =
        crate::api::lifecycle::install_lifecycle(&ctx, grants.clone(), lifecycle.clone(), log.clone(), &inu).unwrap();
      let timers = install_timers(&ctx, wakes_dyn, lifecycle.clone(), log.clone()).unwrap();
      (api, timers)
    });

    let lines = crate::testing::harness::install_capturing_console(&ctx);
    crate::testing::harness::eval_unit(&ctx, VISIBILITY_ORACLE);

    // two full round trips: the oracle disposes after the fourth transition, and each return to
    // the foreground leaves a one-second timer behind that reports the recovered tick rate
    for round in 0..2u64 {
      let base = round * 2 * BACKGROUND_MIN_INTERVAL_MS;
      set_app_visible(&rt, &ctx, &api, &timers, false);
      advance(&rt, &ctx, &timers, &wakes, base + BACKGROUND_MIN_INTERVAL_MS);
      set_app_visible(&rt, &ctx, &api, &timers, true);
      advance(&rt, &ctx, &timers, &wakes, base + 2 * BACKGROUND_MIN_INTERVAL_MS);
    }
    // a fifth and sixth transition, which the disposed registration must not hear
    set_app_visible(&rt, &ctx, &api, &timers, false);
    set_app_visible(&rt, &ctx, &api, &timers, true);

    api.dispose(&ctx);
    timers.dispose(&ctx);

    let lines = lines.borrow();
    crate::testing::harness::assert_oracle_exact(&lines, "visibility test done", 12);
  }
}

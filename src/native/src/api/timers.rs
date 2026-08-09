use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::sync::OnceLock;
use std::time::Instant;

use rquickjs::function::Opt;
use rquickjs::{Coerced, Ctx, Exception, Function, Persistent, Result as JsResult, Runtime, Value};

use crate::api::telegram::rpc::{format_exception, pump_jobs};
use crate::sandbox::registry::{Lifecycle, Registry, Token};

pub const CANCEL_WAKE: i64 = -1;

const MAX_DELAY_MS: u64 = i32::MAX as u64;

const MIN_INTERVAL_MS: u64 = 4;

pub const BACKGROUND_MIN_INTERVAL_MS: u64 = 1_000;

pub const BACKGROUND_INTENSIVE_AFTER_MS: u64 = 5 * 60_000;
pub const BACKGROUND_INTENSIVE_INTERVAL_MS: u64 = 60_000;

pub trait TimerHost {
  fn schedule_wake(&self, delay_ms: i64);

  fn now_ms(&self) -> u64 {
    monotonic_now_ms()
  }
}

pub(crate) fn monotonic_now_ms() -> u64 {
  static START: OnceLock<Instant> = OnceLock::new();
  START.get_or_init(Instant::now).elapsed().as_millis() as u64
}

struct Timer {
  id: Token,
  callback: RefCell<Option<Persistent<Function<'static>>>>,
  interval_ms: Option<u64>,
  due: Cell<u64>,
  seq: Cell<u64>,
}

impl Timer {
  fn release(&self, ctx: &Ctx<'_>) {
    if let Some(persistent) = self.callback.borrow_mut().take() {
      let _ = persistent.restore(ctx);
    }
  }
}

pub struct TimerState {
  host: Rc<dyn TimerHost>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  timers: Registry<Rc<Timer>>,
  next_seq: Cell<u64>,
  armed: Cell<Option<u64>>,
  visible: Cell<bool>,
  hidden_since: Cell<u64>,
  last_tick: Cell<u64>,
}

impl TimerState {
  fn next_seq(&self) -> u64 {
    let seq = self.next_seq.get();
    self.next_seq.set(seq + 1);
    seq
  }
}

pub fn install_timers<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn TimerHost>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
) -> JsResult<Rc<TimerState>> {
  let state = Rc::new(TimerState {
    host,
    lifecycle,
    log,
    timers: Registry::default(),
    next_seq: Cell::new(1),
    armed: Cell::new(None),
    visible: Cell::new(true),
    hidden_since: Cell::new(0),
    last_tick: Cell::new(0),
  });

  let globals = ctx.globals();
  for (name, repeats) in [("setTimeout", false), ("setInterval", true)] {
    let state2 = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, callback: Value<'js>, delay: Opt<Coerced<f64>>| {
      arm_timer(&ctx, &state2, name, callback, delay.0.map(|d| d.0), repeats)
    })?;
    globals.set(name, f)?;
  }
  for name in ["clearTimeout", "clearInterval"] {
    let state2 = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, id: Opt<Coerced<f64>>| {
      clear_timer(&ctx, &state2, id.0.map(|i| i.0));
    })?;
    globals.set(name, f)?;
  }

  Ok(state)
}

fn clamp_delay(delay: Option<f64>) -> u64 {
  match delay {
    Some(ms) if ms.is_finite() && ms > 0.0 => (ms as u64).min(MAX_DELAY_MS),
    _ => 0,
  }
}

fn arm_timer<'js>(
  ctx: &Ctx<'js>,
  state: &Rc<TimerState>,
  what: &str,
  callback: Value<'js>,
  delay: Option<f64>,
  repeats: bool,
) -> JsResult<Token> {
  let Some(callback) = callback.into_function() else {
    return Err(Exception::throw_type(ctx, &format!("{what}: callback must be a function")));
  };
  if state.lifecycle.is_unloading() {
    return Ok(0);
  }

  let delay = clamp_delay(delay);
  let id = state.timers.alloc();
  state.timers.insert(
    id,
    None,
    Rc::new(Timer {
      id,
      callback: RefCell::new(Some(Persistent::save(ctx, callback))),
      interval_ms: repeats.then(|| delay.max(MIN_INTERVAL_MS)),
      due: Cell::new(state.host.now_ms().saturating_add(delay)),
      seq: Cell::new(state.next_seq()),
    }),
  );
  sync_wake(state);
  Ok(id)
}

fn clear_timer(ctx: &Ctx<'_>, state: &Rc<TimerState>, id: Option<f64>) {
  let Some(id) = id else { return };
  if !(id.is_finite() && id >= 1.0 && id <= Token::MAX as f64) {
    return;
  }
  if let Some(timer) = state.timers.remove(id as Token) {
    timer.release(ctx);
    sync_wake(state);
  }
}

fn tick_floor(state: &Rc<TimerState>, now: u64) -> Option<u64> {
  if state.visible.get() || state.lifecycle.has_blocking_dispatches() {
    return None;
  }
  let hidden_for = now.saturating_sub(state.hidden_since.get());
  Some(if hidden_for >= BACKGROUND_INTENSIVE_AFTER_MS {
    BACKGROUND_INTENSIVE_INTERVAL_MS
  } else {
    BACKGROUND_MIN_INTERVAL_MS
  })
}

fn sync_wake(state: &Rc<TimerState>) {
  let earliest = state.timers.values().iter().map(|t| t.due.get()).min();
  match earliest {
    None => {
      if state.armed.replace(None).is_some() {
        state.host.schedule_wake(CANCEL_WAKE);
      }
    }
    Some(due) => {
      let now = state.host.now_ms();
      let due = match tick_floor(state, now) {
        Some(floor) => due.max(state.last_tick.get().saturating_add(floor)),
        None => due,
      };
      if state.armed.get() != Some(due) {
        state.armed.set(Some(due));
        state.host.schedule_wake(due.saturating_sub(now) as i64);
      }
    }
  }
}

pub fn set_visible(state: &Rc<TimerState>, visible: bool) {
  if state.visible.replace(visible) == visible {
    return;
  }
  if !visible {
    state.hidden_since.set(state.host.now_ms());
  }
  sync_wake(state);
}

fn release_all(ctx: &Ctx<'_>, state: &Rc<TimerState>) {
  for timer in state.timers.remove_matching(|_| true) {
    timer.release(ctx);
  }
}

pub fn run_due(rt: &Runtime, context: &rquickjs::Context, state: &Rc<TimerState>) {
  state.armed.set(None);
  context.with(|ctx| {
    if state.lifecycle.is_unloading() {
      release_all(&ctx, state);
      return;
    }
    let now = state.host.now_ms();
    state.last_tick.set(now);
    let mut due: Vec<Rc<Timer>> = state.timers.values().into_iter().filter(|t| t.due.get() <= now).collect();
    due.sort_by_key(|t| (t.due.get(), t.seq.get()));

    for timer in due {
      if !state.timers.contains(timer.id) || timer.due.get() > now {
        continue;
      }
      let saved = timer.callback.borrow().clone();
      match timer.interval_ms {
        Some(period) => {
          timer.due.set(now.saturating_add(period));
          timer.seq.set(state.next_seq());
        }
        None => {
          state.timers.remove(timer.id);
          timer.release(&ctx);
        }
      }
      let Some(callback) = saved.and_then(|p| p.restore(&ctx).ok()) else {
        continue;
      };
      match callback.call::<_, Value>(()) {
        Ok(_) => {}
        Err(rquickjs::Error::Exception) => {
          (state.log)(&crate::fault(format_args!("timer callback threw: {}", format_exception(&ctx))));
        }
        Err(e) => (state.log)(&format!("timer callback failed: {e:?}")),
      }
    }
  });
  sync_wake(state);
  pump_jobs(rt, context, state.log.as_ref());
}

pub fn notify_unload(context: &rquickjs::Context, state: &Rc<TimerState>) {
  dispose(context, state);
}

pub fn dispose(context: &rquickjs::Context, state: &Rc<TimerState>) {
  context.with(|ctx| release_all(&ctx, state));
  sync_wake(state);
}

#[cfg(test)]
#[path = "timers_tests.rs"]
mod tests;

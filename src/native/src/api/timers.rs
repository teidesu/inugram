use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::sync::OnceLock;
use std::time::Instant;

use rquickjs::function::Opt;
use rquickjs::{Coerced, Ctx, Exception, Function, Persistent, Result as JsResult, Runtime, Value};

use crate::api::error::PluginErrorCode;
use crate::api::telegram::rpc::{format_exception, pump_jobs};
use crate::sandbox::registry::{Lifecycle, Registry, Token};

pub const CANCEL_WAKE: i64 = -1;

const MAX_DELAY_MS: u64 = i32::MAX as u64;

const MIN_INTERVAL_MS: u64 = 4;

const TIMER_LIMIT: usize = 512;

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
  u64::try_from(START.get_or_init(Instant::now).elapsed().as_millis()).unwrap_or(u64::MAX)
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
    globals.set(
      name,
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, callback: Value<'js>, delay: Opt<Coerced<f64>>| {
        state2.arm_timer(&ctx, name, callback, delay.0.map(|d| d.0), repeats)
      })?,
    )?;
  }
  for name in ["clearTimeout", "clearInterval"] {
    let state2 = state.clone();
    globals.set(
      name,
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, id: Opt<Coerced<f64>>| {
        state2.clear_timer(&ctx, id.0.map(|i| i.0));
      })?,
    )?;
  }

  Ok(state)
}

fn clamp_delay(delay: Option<f64>) -> u64 {
  match delay {
    Some(ms) if ms.is_finite() && ms > 0.0 => (ms as u64).min(MAX_DELAY_MS),
    _ => 0,
  }
}

impl TimerState {
  fn arm_timer<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    what: &str,
    callback: Value<'js>,
    delay: Option<f64>,
    repeats: bool,
  ) -> JsResult<Token> {
    let Some(callback) = callback.into_function() else {
      return Err(Exception::throw_type(ctx, &format!("{what}: callback must be a function")));
    };
    if self.lifecycle.is_unloading() {
      return Ok(0);
    }
    let wanted = self.timers.len().saturating_add(1);
    if wanted > TIMER_LIMIT {
      return PluginErrorCode::QuotaExceeded(wanted as i64, TIMER_LIMIT as i64)
        .throw(ctx, &format!("{what}: this plugin may hold at most {TIMER_LIMIT} live timers"));
    }

    let delay = clamp_delay(delay);
    let id = self.timers.alloc();
    self.timers.insert(
      id,
      None,
      Rc::new(Timer {
        id,
        callback: RefCell::new(Some(Persistent::save(ctx, callback))),
        interval_ms: repeats.then(|| delay.max(MIN_INTERVAL_MS)),
        due: Cell::new(self.host.now_ms().saturating_add(delay)),
        seq: Cell::new(self.next_seq()),
      }),
    );
    self.sync_wake();
    Ok(id)
  }

  fn clear_timer(self: &Rc<Self>, ctx: &Ctx<'_>, id: Option<f64>) {
    let Some(id) = id else { return };
    if !(id.is_finite() && id >= 1.0 && id <= Token::MAX as f64) {
      return;
    }
    if let Some(timer) = self.timers.remove(id as Token) {
      timer.release(ctx);
      self.sync_wake();
    }
  }

  fn tick_floor(&self, now: u64) -> Option<u64> {
    if self.visible.get() || self.lifecycle.has_blocking_dispatches() {
      return None;
    }
    let hidden_for = now.saturating_sub(self.hidden_since.get());
    Some(if hidden_for >= BACKGROUND_INTENSIVE_AFTER_MS {
      BACKGROUND_INTENSIVE_INTERVAL_MS
    } else {
      BACKGROUND_MIN_INTERVAL_MS
    })
  }

  fn sync_wake(&self) {
    let earliest = self.timers.values().iter().map(|t| t.due.get()).min();
    match earliest {
      None => {
        if self.armed.replace(None).is_some() {
          self.host.schedule_wake(CANCEL_WAKE);
        }
      }
      Some(due) => {
        let now = self.host.now_ms();
        let due = match self.tick_floor(now) {
          Some(floor) => due.max(self.last_tick.get().saturating_add(floor)),
          None => due,
        };
        if self.armed.get() != Some(due) {
          self.armed.set(Some(due));
          self.host.schedule_wake(due.saturating_sub(now) as i64);
        }
      }
    }
  }

  fn release_all(&self, ctx: &Ctx<'_>) {
    for timer in self.timers.remove_matching(|_| true) {
      timer.release(ctx);
    }
  }
}

impl TimerState {
  pub fn set_visible(self: &Rc<Self>, visible: bool) {
    let state = self;
    if state.visible.replace(visible) == visible {
      return;
    }
    if !visible {
      state.hidden_since.set(state.host.now_ms());
    }
    state.sync_wake();
  }

  pub fn run_due(self: &Rc<Self>, rt: &Runtime, context: &rquickjs::Context) {
    let state = self;
    state.armed.set(None);
    context.with(|ctx| {
      if state.lifecycle.is_unloading() {
        state.release_all(&ctx);
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
    state.sync_wake();
    pump_jobs(rt, context, state.log.as_ref());
  }

  pub fn notify_unload(self: &Rc<Self>, context: &rquickjs::Context) {
    let state = self;
    state.dispose(context);
  }

  pub fn dispose(self: &Rc<Self>, context: &rquickjs::Context) {
    let state = self;
    context.with(|ctx| state.release_all(&ctx));
    state.sync_wake();
  }
}

#[cfg(test)]
#[path = "timers_tests.rs"]
mod tests;

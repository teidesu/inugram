use std::cell::RefCell;
use std::rc::Rc;

use rquickjs::{Ctx, Function, Persistent};

use crate::api::error::call_callback;
use crate::api::timers::monotonic_now_ms;

pub const PROGRESS_INTERVAL_MS: u64 = 100;

#[derive(Default)]
pub struct ProgressThrottle {
  interval_ms: u64,
  last_emit_ms: Option<u64>,
  last_sent: Option<(i64, i64)>,
  withheld: Option<(i64, i64)>,
}

impl ProgressThrottle {
  pub fn new(interval_ms: u64) -> ProgressThrottle {
    ProgressThrottle {
      interval_ms,
      ..ProgressThrottle::default()
    }
  }

  pub fn offer(&mut self, now: u64, loaded: i64, total: i64) -> Option<(i64, i64)> {
    let report = (loaded, total);
    if self.last_sent == Some(report) {
      self.withheld = None;
      return None;
    }
    let due = self.last_emit_ms.is_none_or(|last| now.saturating_sub(last) >= self.interval_ms);
    if !due {
      self.withheld = Some(report);
      return None;
    }
    self.deliver(now, report)
  }

  pub fn force(&mut self, now: u64, loaded: i64, total: i64) -> Option<(i64, i64)> {
    self.withheld = None;
    self.deliver(now, (loaded, total))
  }

  pub fn flush(&mut self, now: u64) -> Option<(i64, i64)> {
    let withheld = self.withheld.take()?;
    self.deliver(now, withheld)
  }

  fn deliver(&mut self, now: u64, report: (i64, i64)) -> Option<(i64, i64)> {
    if self.last_sent == Some(report) {
      return None;
    }
    self.last_emit_ms = Some(now);
    self.last_sent = Some(report);
    self.withheld = None;
    Some(report)
  }
}

pub struct ProgressReporter {
  callback: RefCell<Option<Persistent<Function<'static>>>>,
  throttle: RefCell<ProgressThrottle>,
  log: crate::Log,
}

impl ProgressReporter {
  pub fn new<'js>(ctx: &Ctx<'js>, callback: Function<'js>, log: crate::Log) -> Rc<ProgressReporter> {
    Self::with_interval(ctx, callback, PROGRESS_INTERVAL_MS, log)
  }

  fn with_interval<'js>(
    ctx: &Ctx<'js>,
    callback: Function<'js>,
    interval_ms: u64,
    log: crate::Log,
  ) -> Rc<ProgressReporter> {
    Rc::new(ProgressReporter {
      callback: RefCell::new(Some(Persistent::save(ctx, callback))),
      throttle: RefCell::new(ProgressThrottle::new(interval_ms)),
      log,
    })
  }

  pub fn report(&self, ctx: &Ctx<'_>, loaded: i64, total: i64) {
    let emit = self.throttle.borrow_mut().offer(monotonic_now_ms(), loaded, total);
    if let Some((loaded, total)) = emit {
      self.call(ctx, loaded, total);
    }
  }

  pub fn finish(&self, ctx: &Ctx<'_>, loaded: i64, total: i64) {
    let emit = self.throttle.borrow_mut().force(monotonic_now_ms(), loaded, total);
    if let Some((loaded, total)) = emit {
      self.call(ctx, loaded, total);
    }
    self.release(ctx);
  }

  pub fn abandon(&self, ctx: &Ctx<'_>) {
    let emit = self.throttle.borrow_mut().flush(monotonic_now_ms());
    if let Some((loaded, total)) = emit {
      self.call(ctx, loaded, total);
    }
    self.release(ctx);
  }

  pub fn release(&self, ctx: &Ctx<'_>) {
    if let Some(persistent) = self.callback.borrow_mut().take() {
      let _ = persistent.restore(ctx);
    }
  }

  fn call(&self, ctx: &Ctx<'_>, loaded: i64, total: i64) {
    let saved = self.callback.borrow().clone();
    let Some(callback) = saved.and_then(|p| p.restore(ctx).ok()) else {
      return;
    };
    call_callback(ctx, &self.log, "onProgress callback", &callback, (loaded as f64, total as f64));
  }
}

#[cfg(test)]
#[path = "progress_tests.rs"]
mod tests;

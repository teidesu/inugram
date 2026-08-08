//! Coalescing for the `onProgress` callbacks the transfer apis take.
//!
//! A transfer reports per chunk and a chunk is 32 KB, so a 200 MB download is ~6400 upcalls for a
//! pair of numbers nothing redraws faster than display rate. What falls inside a window is
//! *withheld* rather than dropped, and nothing here is driven by a clock of its own, so every
//! transfer owes the throttle exactly one terminal call.

use std::cell::RefCell;
use std::rc::Rc;

use rquickjs::{Ctx, Function, Persistent, Value};

use crate::sandbox::timers::monotonic_now_ms;
use crate::telegram::rpc::format_exception;

/// 10 reports a second: fast enough that a progress bar driven off it never looks stepped, slow
/// enough that the rate is the same for a 32 KB chunk stream as for a 4 MB one
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
        ProgressThrottle { interval_ms, ..ProgressThrottle::default() }
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

/// one transfer's `onProgress`, held as a GC root until the transfer ends. All three terminal
/// calls are idempotent, so every way a transfer can end has exactly one to make and cannot make
/// it twice.
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

    /// `total` is 0 when the size is not known yet, which the plugin sees exactly as the web does.
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
        match callback.call::<_, Value>((loaded as f64, total as f64)) {
            Ok(_) => {}
            Err(rquickjs::Error::Exception) => {
                (self.log)(&crate::fault(format_args!("onProgress callback threw: {}", format_exception(ctx))));
            }
            Err(e) => (self.log)(&format!("onProgress callback failed: {e:?}")),
        }
    }
}

#[cfg(test)]
#[path = "progress_tests.rs"]
mod tests;

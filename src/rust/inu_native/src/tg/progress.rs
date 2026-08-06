//! Coalescing for the `onProgress` callbacks the transfer apis take.
//!
//! A transfer reports per chunk and a chunk is 32 KB, so a 200 MB download is ~6400 upcalls for a
//! pair of numbers nothing redraws faster than display rate. What falls inside a window is
//! *withheld* rather than dropped, and nothing here is driven by a clock of its own, so every
//! transfer owes the throttle exactly one terminal call.

use std::cell::RefCell;
use std::rc::Rc;

use rquickjs::{Ctx, Function, Persistent, Value};

use crate::engine::timers::monotonic_now_ms;
use crate::tg::rpc::format_exception;

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
                (self.log)(&format!("onProgress callback threw: {}", format_exception(ctx)));
            }
            Err(e) => (self.log)(&format!("onProgress callback failed: {e:?}")),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rquickjs::{Context, Runtime};

    /// every other test here injects its own interval, so the shipped constant is otherwise pinned
    /// by nothing
    #[test]
    fn the_shipped_interval_is_the_one_the_contract_states() {
        use crate::testing::util::{stated_number, CONTRACT};
        assert_eq!(PROGRESS_INTERVAL_MS, stated_number(CONTRACT, "at most one per {} ms"));
    }

    #[test]
    fn the_first_report_is_delivered_and_the_rest_of_the_window_is_not() {
        let mut throttle = ProgressThrottle::new(100);
        assert_eq!(throttle.offer(1_000, 32, 6400), Some((32, 6400)));
        assert_eq!(throttle.offer(1_010, 64, 6400), None);
        assert_eq!(throttle.offer(1_099, 96, 6400), None);
        assert_eq!(throttle.offer(1_100, 128, 6400), Some((128, 6400)));
    }

    #[test]
    fn a_chunk_stream_costs_one_call_per_interval() {
        let mut throttle = ProgressThrottle::new(100);
        let mut delivered = 0;
        // the 200 MB download the coalescing exists for
        for chunk in 1..=6400i64 {
            let now = chunk as u64 * 60_000 / 6400;
            if throttle.offer(now, chunk * 32_768, 6400 * 32_768).is_some() {
                delivered += 1;
            }
        }
        // a window closes on the first chunk past it, so 100 ms of wall clock is 100..109 ms of
        // reports
        assert!((570..=600).contains(&delivered), "6400 reports collapsed to {delivered}");
    }

    #[test]
    fn the_last_withheld_report_survives_to_the_end_of_the_transfer() {
        let mut throttle = ProgressThrottle::new(100);
        throttle.offer(0, 10, 100);
        assert_eq!(throttle.offer(10, 40, 100), None);
        assert_eq!(throttle.offer(20, 70, 100), None, "a withheld report is replaced, not queued");
        assert_eq!(
            throttle.flush(20),
            Some((70, 100)),
            "a transfer ending with no numbers of its own ends on the last one it made",
        );

        let mut throttle = ProgressThrottle::new(100);
        throttle.offer(0, 10, 100);
        throttle.offer(10, 70, 100);
        assert_eq!(throttle.force(20, 100, 100), Some((100, 100)), "a final report supersedes it");
        assert_eq!(throttle.flush(20), None, "and leaves nothing behind for a later flush");
    }

    #[test]
    fn flushing_a_window_that_withheld_nothing_reports_nothing() {
        let mut throttle = ProgressThrottle::new(100);
        assert_eq!(throttle.flush(0), None, "nothing was ever offered");
        assert_eq!(throttle.offer(0, 10, 100), Some((10, 100)));
        assert_eq!(throttle.flush(10), None, "the window's only report was delivered");
        assert_eq!(throttle.offer(10, 10, 100), None, "a repeat is not withheld either");
        assert_eq!(throttle.flush(10), None);
    }

    #[test]
    fn nothing_is_delivered_twice() {
        let mut throttle = ProgressThrottle::new(100);
        assert_eq!(throttle.offer(0, 100, 100), Some((100, 100)));
        assert_eq!(throttle.offer(500, 100, 100), None, "an unchanged report is not news");
        assert_eq!(throttle.force(500, 100, 100), None, "and neither is the finish that repeats it");
    }

    #[test]
    fn a_transfer_whose_size_is_learned_late_reports_the_change() {
        let mut throttle = ProgressThrottle::new(100);
        assert_eq!(throttle.offer(0, 0, 0), Some((0, 0)));
        assert_eq!(throttle.force(0, 0, 4096), Some((0, 4096)), "same loaded, new total");
    }

    type Fixture = (Runtime, Context, std::sync::Arc<crate::testing::util::Logs>, crate::Log);

    fn setup() -> Fixture {
        let rt = Runtime::new().unwrap();
        let ctx = Context::full(&rt).unwrap();
        let logs = crate::testing::util::Logs::new();
        let log = crate::testing::util::log_sink(&logs);
        (rt, ctx, logs, log)
    }

    #[test]
    fn the_callback_sees_only_the_reports_the_throttle_passed() {
        let (_rt, ctx, _logs, log) = setup();
        ctx.with(|ctx| {
            let cb: Function =
                ctx.eval("globalThis.__seen = []; (loaded, total) => { __seen.push([loaded, total]); }").unwrap();
            let reporter = ProgressReporter::with_interval(&ctx, cb, u64::MAX, log);
            reporter.report(&ctx, 10, 100);
            reporter.report(&ctx, 40, 100);
            reporter.report(&ctx, 70, 100);
            reporter.finish(&ctx, 100, 100);

            let seen: String = ctx.eval("JSON.stringify(__seen)").unwrap();
            assert_eq!(seen, "[[10,100],[100,100]]");
        });
    }

    #[test]
    fn a_cancelled_transfer_leaves_the_plugin_on_the_last_number_it_saw() {
        let (_rt, ctx, _logs, log) = setup();
        ctx.with(|ctx| {
            let cb: Function =
                ctx.eval("globalThis.__seen = []; (loaded, total) => { __seen.push([loaded, total]); }").unwrap();
            let reporter = ProgressReporter::with_interval(&ctx, cb, u64::MAX, log);
            reporter.report(&ctx, 10, 100);
            reporter.report(&ctx, 37, 100);
            reporter.abandon(&ctx);
            reporter.abandon(&ctx);
            reporter.report(&ctx, 50, 100);

            let seen: String = ctx.eval("JSON.stringify(__seen)").unwrap();
            assert_eq!(seen, "[[10,100],[37,100]]");
        });
    }

    #[test]
    fn reporting_after_the_transfer_ended_reaches_nothing() {
        let (_rt, ctx, _logs, log) = setup();
        ctx.with(|ctx| {
            let cb: Function = ctx.eval("globalThis.__calls = 0; () => { __calls++; }").unwrap();
            let reporter = ProgressReporter::with_interval(&ctx, cb, 0, log);
            reporter.finish(&ctx, 100, 100);
            reporter.finish(&ctx, 100, 100);
            reporter.release(&ctx);
            reporter.report(&ctx, 200, 200);

            let calls: String = ctx.eval("String(__calls)").unwrap();
            assert_eq!(calls, "1");
        });
    }

    #[test]
    fn a_throwing_callback_is_logged_and_the_transfer_carries_on() {
        let (_rt, ctx, logs, log) = setup();
        ctx.with(|ctx| {
            let cb: Function =
                ctx.eval("globalThis.__calls = 0; () => { __calls++; throw new Error('progress-boom'); }").unwrap();
            let reporter = ProgressReporter::with_interval(&ctx, cb, 0, log);
            reporter.report(&ctx, 10, 100);
            reporter.finish(&ctx, 100, 100);

            let calls: String = ctx.eval("String(__calls)").unwrap();
            assert_eq!(calls, "2");
        });
        assert!(
            logs.borrow().iter().all(|l| l.contains("onProgress callback threw") && l.contains("progress-boom")),
            "got: {:?}",
            logs.borrow(),
        );
        assert_eq!(logs.borrow().len(), 2);
    }
}

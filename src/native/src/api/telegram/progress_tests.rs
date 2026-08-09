use super::*;
use rquickjs::{Context, Runtime};

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

type Fixture = (Runtime, Context, std::sync::Arc<crate::testing::harness::Logs>, crate::Log);

fn setup() -> Fixture {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let logs = crate::testing::harness::Logs::new();
    let log = crate::testing::harness::log_sink(&logs);
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

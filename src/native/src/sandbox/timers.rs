//! `setTimeout`/`setInterval`/`clearTimeout`/`clearInterval`, which quickjs-ng has no intrinsic for.
//!
//! One outstanding host wake at a time, always for the earliest deadline. An interval that came due
//! more than once while nothing was ticking fires **once** and re-arms from now rather than
//! replaying, which is what lets background throttling change only *when* the wake is asked for.
//!
//! [`set_visible`] floors the wake while the app is hidden - on the wheel, not on each timer's
//! delay, so ten intervals cost one wake per period rather than ten. The floor is suspended while
//! the app is parked behind this plugin ([`Lifecycle::has_blocking_dispatches`]): an `interceptRpc`
//! stage that `await`s a timer is the ordinary way to write a debounce, and a floor of up to a
//! minute would expire the 10 s chain budget (`PluginRpc.CHAIN_BUDGET_MS`) and fail the user's own
//! send because they switched apps. Taking or releasing the hold is not itself a re-sync; the next
//! [`sync_wake`] picks it up.

use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::sync::OnceLock;
use std::time::Instant;

use rquickjs::function::Opt;
use rquickjs::{Coerced, Ctx, Exception, Function, Persistent, Result as JsResult, Runtime, Value};

use crate::sandbox::registry::{Lifecycle, Registry, Token};
use crate::telegram::rpc::{format_exception, pump_jobs};

/// a negative delay withdraws the outstanding wake without arming a new one
pub const CANCEL_WAKE: i64 = -1;

/// browsers wrap anything past this back to 0; clamping instead means a plugin asking for a
/// 40-day timer gets ~24 days rather than an immediate fire
const MAX_DELAY_MS: u64 = i32::MAX as u64;

/// the floor browsers put under a repeating timer, so `setInterval(f, 0)` is a fast timer rather
/// than a spin on the engine's queue
const MIN_INTERVAL_MS: u64 = 4;

/// what a browser clamps a hidden tab's timers to
pub const BACKGROUND_MIN_INTERVAL_MS: u64 = 1_000;

/// chrome drops a tab that has been hidden this long to one wake a minute ("intensive throttling");
/// an app the user left five minutes ago is the same bet
pub const BACKGROUND_INTENSIVE_AFTER_MS: u64 = 5 * 60_000;
pub const BACKGROUND_INTENSIVE_INTERVAL_MS: u64 = 60_000;

/// stand-in for the Kotlin `QuickJs.onTimerSchedule` upcall
pub trait TimerHost {
    /// re-enter [`run_due`] in `delay_ms`, replacing whatever wake was requested before;
    /// [`CANCEL_WAKE`] only withdraws. never called back into the engine synchronously.
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
    /// `None` once released, which is what makes releasing one twice safe
    callback: RefCell<Option<Persistent<Function<'static>>>>,
    /// `None` for a one-shot
    interval_ms: Option<u64>,
    due: Cell<u64>,
    /// tiebreaks equal deadlines, so timers armed for the same millisecond fire in arming order
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
    /// the deadline the host was last asked to wake at, so an unchanged earliest deadline costs
    /// no upcall. it is the *floored* deadline, not the earliest due one, or a hidden wheel would
    /// re-arm on every sync.
    armed: Cell<Option<u64>>,
    visible: Cell<bool>,
    /// when the app was last backgrounded, which decides which of the two floors applies
    hidden_since: Cell<u64>,
    /// when [`run_due`] last ran, which the background floor is measured from
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

/// how long the wheel must leave between ticks right now, or `None` when nothing is floored
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

/// the app moved to the foreground or the background.
///
/// Hiding floors how often the wheel may tick; showing lifts the floor, so everything that came due
/// while hidden fires on the next tick - once each, since [`run_due`] re-arms an interval from now
/// rather than replaying what it missed. Only timers are affected: updates, interceptors and host
/// callbacks reach the engine on their own paths and keep their timing in both states.
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

/// fires every callback due as of entry, in deadline order. Only the timers already due when the
/// tick started run: one armed (or re-armed) by a callback waits for the next wake, so a
/// zero-delay timer arming another cannot hold the queue for a whole entry deadline.
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
            // a callback already run this tick may have cleared this one, or its interval may have
            // been re-armed past `now` - liveness is never implied by having been live earlier
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

/// drops every armed timer and withdraws the host's wake. Call after the `inu.onUnload` callbacks
/// have run, so one clearing its own timers still sees them.
///
/// [`dispose`]'s work exactly: a timer holds nothing but its own callback root and the outstanding
/// wake, so unloading a plugin and destroying its engine give back the same two things.
pub fn notify_unload(context: &rquickjs::Context, state: &Rc<TimerState>) {
    dispose(context, state);
}

/// releases every `Persistent` GC root this state still owns - same contract as [`crate::telegram::rpc::dispose`].
/// Withdraws the outstanding wake too: an engine destroyed without [`notify_unload`] would otherwise
/// leave a queued host runnable holding the `QuickJs` (and through it the whole `Plugin`) until it
/// came due.
pub fn dispose(context: &rquickjs::Context, state: &Rc<TimerState>) {
    context.with(|ctx| release_all(&ctx, state));
    sync_wake(state);
}

#[cfg(test)]
#[path = "timers_tests.rs"]
mod tests;

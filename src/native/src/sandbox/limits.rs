//! The two resource ceilings one plugin runs under: cpu per engine entry, memory per engine.
//!
//! Everything that could otherwise stop a runaway plugin shares `Utilities.globalQueue` with it, so
//! a `while (true) {}` has to be broken from inside quickjs, which polls the interrupt handler on
//! interpreter back-edges. Returning `true` raises `InternalError: interrupted` marked uncatchable
//! (`JS_SetUncatchableError`), so a plugin's `catch` re-throws instead of spinning on, and the
//! handler keeps returning `true` for the rest of the entry so a looping `finally` is cut down too.
//! It is thread-local rather than per-engine: engines share the queue thread and are never entered
//! re-entrantly, so at most one is armed.
//!
//! Back-edge polling is also its limit - nothing polls while a *native* op runs, so any op that can
//! move an unbounded number of bytes needs its own bound in its own unit
//! ([`crate::io::blob::BUILD_LIMIT_BYTES`]). A *loop* of bounded ops is still cut down, the deadline
//! being wall clock rather than cpu spent in js.
//!
//! The js heap is bounded per runtime (`JS_SetMemoryLimit`), its failure mode on
//! [`describe_heap_exhaustion`]. Native memory behind a js object ([`ExternalMemory`]) is counted
//! separately, quickjs scheduling its mark-sweep off js heap growth and not seeing those bytes at
//! all - megabytes of bitmap behind a few dozen bytes of js object leave the engine believing it is
//! idle.

use std::cell::Cell;
use std::rc::Rc;
use std::time::{Duration, Instant};

use rquickjs::{Ctx, Result as JsResult, Runtime, Value};

/// ceiling on the *uninterrupted* JS one engine entry may run. Any sync-CPU budget is a heuristic:
/// the host cannot tell an expensive-but-honest loop from a runaway one, it can only bound how
/// long the queue stays blocked. 2s is far past anything legitimate plugin code does in a single
/// turn (a chain stage, an onUpdate handler, one settings-page render) while still being shorter
/// than the interceptor chain's own 10s budget, so a spinning stage is stopped early enough for
/// the chain to collapse and answer the app normally.
pub const ENTRY_DEADLINE_MS: u64 = 2_000;

/// top-level evaluation parses, compiles and runs the plugin's whole bundle once, off any user
/// interaction, so it gets the looser ceiling
pub const EVAL_DEADLINE_MS: u64 = 10_000;

#[derive(Clone, Copy)]
struct Armed {
    deadline: Instant,
    limit_ms: u64,
}

thread_local! {
    static ARMED: Cell<Option<Armed>> = const { Cell::new(None) };
    static TRIPPED: Cell<bool> = const { Cell::new(false) };
}

/// disarms on drop, so the next entry starts from a clean deadline whether this one returned
/// normally or unwound
pub struct Deadline {
    previous: Option<Armed>,
    previously_tripped: bool,
}

pub fn arm_entry_deadline() -> Deadline {
    arm(ENTRY_DEADLINE_MS)
}

pub fn arm_eval_deadline() -> Deadline {
    arm(EVAL_DEADLINE_MS)
}

pub(crate) fn arm(limit_ms: u64) -> Deadline {
    let previous = ARMED.with(|a| a.get());
    let mut armed = Armed { deadline: Instant::now() + Duration::from_millis(limit_ms), limit_ms };
    // an entry nested in another one may only tighten its deadline, never extend it
    if let Some(previous) = previous {
        if previous.deadline < armed.deadline {
            armed = previous;
        }
    }
    ARMED.with(|a| a.set(Some(armed)));
    Deadline { previous, previously_tripped: TRIPPED.with(|t| t.replace(false)) }
}

impl Deadline {
    #[cfg(test)]
    pub fn tripped(&self) -> bool {
        TRIPPED.with(|t| t.get())
    }
}

impl Drop for Deadline {
    fn drop(&mut self) {
        ARMED.with(|a| a.set(self.previous));
        let tripped = self.previously_tripped || TRIPPED.with(|t| t.get());
        TRIPPED.with(|t| t.set(tripped));
    }
}

/// must run outside `Context::with`: it locks the runtime, which `with` already holds
pub fn install_interrupt_handler(rt: &Runtime, log: crate::Log) {
    rt.set_interrupt_handler(Some(Box::new(move || {
        let Some(armed) = ARMED.with(|a| a.get()) else {
            return false;
        };
        if Instant::now() < armed.deadline {
            return false;
        }
        if !TRIPPED.with(|t| t.replace(true)) {
            log(&format!(
                "execution budget exceeded: this call ran more than {}ms of uninterrupted JS and was stopped",
                armed.limit_ms,
            ));
        }
        true
    })));
}

/// ceiling on one plugin's js heap. quickjs objects are small and shape-shared (a `{x: 1}` is
/// ~100 bytes), so 32 MiB is hundreds of thousands of live objects or ~16M characters of string:
/// orders of magnitude past what a plugin holding a dialog list, a settings page and its own state
/// actually keeps, while low enough that a plugin hitting its ceiling still leaves the app the
/// several hundred MB it needs to stay alive. Bytes a plugin wants to *move* rather than hold
/// (media, downloads) belong in `inu.fs` or a `Blob` spill, neither of which is js heap.
pub const HEAP_LIMIT_BYTES: usize = 32 * 1024 * 1024;

/// ceiling on the native memory one plugin may hold behind js objects. One 4096x4096 RGBA surface
/// is exactly 64 MiB, so the budget admits the largest single canvas the api can describe and
/// refuses a second one held at the same time. Surfaces that big exist to export one image, not to
/// be kept around.
pub const EXTERNAL_LIMIT_BYTES: usize = 64 * 1024 * 1024;

/// how much external memory may be charged between two collections. A cycle holding a canvas is
/// only freed by a mark-sweep, and quickjs has no reason to run one: the js side of that cycle is
/// a few dozen bytes.
const EXTERNAL_GC_STEP_BYTES: usize = 8 * 1024 * 1024;

/// must run outside `Context::with`, and after the context exists: the setter locks the runtime,
/// and building a context's intrinsics allocates
pub fn apply_heap_limit(rt: &Runtime) {
    rt.set_memory_limit(HEAP_LIMIT_BYTES);
}

/// Names the heap ceiling for the host-side report, since what the engine raises does not. quickjs
/// refuses the allocation and throws one of two things:
///
/// - `InternalError: out of memory`, when there is still room to build the error object;
/// - a bare `null`, when building that error would allocate too (`JS_ThrowOutOfMemory` ->
///   `JS_ThrowInternalError` fails -> quickjs throws `JS_NULL` rather than recurse). This is what
///   a gradually grown heap hits, and by the time anything can look the failed turn has unwound,
///   so it is indistinguishable from a plugin's own `throw null` and the answer says both.
///
/// Neither can become an `inu.PluginError` where the plugin sees it: constructing one needs an
/// allocation the engine has just refused.
pub fn describe_heap_exhaustion<'js>(exception: &Value<'js>) -> Option<String> {
    let ceiling_mb = HEAP_LIMIT_BYTES / (1024 * 1024);
    if exception.is_null() {
        return Some(format!(
            "null was thrown, which is also what this plugin's javascript heap ceiling of {ceiling_mb} MB raises when it has no room left to build an error object",
        ));
    }
    let obj = exception.as_object()?;
    let out_of_memory = obj.get::<_, String>("name").ok().as_deref() == Some("InternalError")
        && obj.get::<_, String>("message").ok().as_deref() == Some("out of memory");
    if !out_of_memory {
        return None;
    }
    Some(format!(
        "quota-exceeded: this plugin's javascript heap ceiling of {ceiling_mb} MB was reached and the allocation was refused",
    ))
}

/// Accounting for native memory a plugin holds behind a js object - an `OffscreenCanvas`'s bitmap,
/// an `ImageBitmap`, a `Blob`'s bytes. It is charged against its own budget rather than the heap's,
/// because neither counter can see the other's bytes: quickjs would have to be asked for a
/// whole-heap walk on every charge, and a shared pool could leave the heap ceiling *below* the
/// heap's current size, which nothing running in js can recover from.
///
/// One per engine, held by whatever mints the native-backed objects.
pub struct ExternalMemory {
    // `Cell`, not `RefCell`: a release runs from a quickjs finalizer, which fires inside the
    // `run_gc` below, so a borrow held across it would panic
    charged: Cell<usize>,
    next_gc_at: Cell<usize>,
}

impl ExternalMemory {
    pub fn new() -> Rc<Self> {
        Rc::new(ExternalMemory { charged: Cell::new(0), next_gc_at: Cell::new(EXTERNAL_GC_STEP_BYTES) })
    }

    pub fn charged_bytes(&self) -> usize {
        self.charged.get()
    }

    /// Reserves `bytes` for the lifetime of the returned charge, or reserves nothing and answers
    /// `None` without raising. For a caller with somewhere else to put the bytes - a `Blob` large
    /// enough to spill to disk - the budget is a routing decision rather than a failure, and a
    /// refusal that threw would make the spill unreachable exactly when it is wanted.
    pub fn try_charge(self: &Rc<Self>, ctx: &Ctx<'_>, bytes: usize) -> Option<ExternalCharge> {
        self.take(ctx, bytes).then(|| ExternalCharge { owner: self.clone(), bytes })
    }

    fn take(&self, ctx: &Ctx<'_>, bytes: usize) -> bool {
        let mut wanted = self.charged.get().saturating_add(bytes);
        if wanted > EXTERNAL_LIMIT_BYTES || wanted >= self.next_gc_at.get() {
            ctx.run_gc();
            wanted = self.charged.get().saturating_add(bytes);
        }
        if wanted > EXTERNAL_LIMIT_BYTES {
            return false;
        }
        self.charged.set(wanted);
        self.next_gc_at.set(wanted.saturating_add(EXTERNAL_GC_STEP_BYTES));
        true
    }

    /// Reserves `bytes` for the lifetime of the returned charge, or throws `quota-exceeded` and
    /// reserves nothing. The refusal is a real `inu.PluginError` because the allocation has not
    /// happened yet: a plugin can catch it, drop a surface it is done with and try again.
    pub fn charge<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, bytes: usize) -> JsResult<ExternalCharge> {
        if let Some(charge) = self.try_charge(ctx, bytes) {
            return Ok(charge);
        }
        crate::sandbox::error::throw_plugin_error(
            ctx,
            "quota-exceeded",
            &format!(
                "this plugin holds {:.1} MB of native memory and asked for {:.1} MB more, past its ceiling of {} MB",
                self.charged.get() as f64 / (1024.0 * 1024.0),
                bytes as f64 / (1024.0 * 1024.0),
                EXTERNAL_LIMIT_BYTES / (1024 * 1024),
            ),
            None,
            Some(self.charged.get().saturating_add(bytes) as i64),
            Some(EXTERNAL_LIMIT_BYTES as i64),
        )
    }

    fn release(&self, bytes: usize) {
        let left = self.charged.get().saturating_sub(bytes);
        self.charged.set(left);
        // the next collection follows the live total back down, or one peak would leave a plugin
        // collecting on every charge it makes afterwards
        let step = left.saturating_add(EXTERNAL_GC_STEP_BYTES);
        if self.next_gc_at.get() > step {
            self.next_gc_at.set(step);
        }
    }
}

/// Releases on drop, so a native-backed object charges by holding one of these and needs no
/// teardown path of its own: the `Class` finalizer that frees the bitmap drops this with it.
pub struct ExternalCharge {
    owner: Rc<ExternalMemory>,
    bytes: usize,
}

impl ExternalCharge {
    pub fn bytes(&self) -> usize {
        self.bytes
    }

    /// Raises this reservation by `extra`, or leaves it exactly where it was and answers `false`.
    /// A buffer that grows in place needs this rather than a second charge, so that what it holds
    /// stays one number the whole way: charging per growth step would leave a caller unable to say
    /// how much it has reserved without adding up its own history.
    pub fn try_grow(&mut self, ctx: &Ctx<'_>, extra: usize) -> bool {
        if !self.owner.take(ctx, extra) {
            return false;
        }
        self.bytes = self.bytes.saturating_add(extra);
        true
    }

    /// hands back everything past `bytes`, for a buffer that ended up smaller than it reserved
    pub fn shrink_to(&mut self, bytes: usize) {
        let Some(back) = self.bytes.checked_sub(bytes) else {
            return;
        };
        self.owner.release(back);
        self.bytes = bytes;
    }
}

impl Drop for ExternalCharge {
    fn drop(&mut self) {
        self.owner.release(self.bytes);
    }
}

#[cfg(test)]
#[path = "limits_tests.rs"]
mod tests;

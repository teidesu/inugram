use std::cell::Cell;
use std::rc::Rc;
use std::time::{Duration, Instant};

use rquickjs::{Ctx, Result as JsResult, Runtime, Value};

use crate::api::error::PluginErrorCode;

pub const ENTRY_DEADLINE_MS: u64 = 2_000;

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

pub struct Deadline {
  previous: Option<Armed>,
  previously_tripped: bool,
}

pub(crate) fn arm(limit_ms: u64) -> Deadline {
  let previous = ARMED.with(|a| a.get());
  let mut armed = Armed {
    deadline: Instant::now() + Duration::from_millis(limit_ms),
    limit_ms,
  };
  if let Some(previous) = previous {
    if previous.deadline < armed.deadline {
      armed = previous;
    }
  }
  ARMED.with(|a| a.set(Some(armed)));
  Deadline {
    previous,
    previously_tripped: TRIPPED.with(|t| t.replace(false)),
  }
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

/// What an entry leaves below the JS limit for native frames: host upcalls through JNI into ART,
/// and quickjs's own C frames between two checks.
pub const STACK_MARGIN_BYTES: usize = 256 * 1024;

thread_local! {
    static STACK_LOW: Cell<Option<usize>> = const { Cell::new(None) };
}

/// The lowest address of this thread's stack. Cached: a thread's stack never moves, and on the
/// main thread bionic answers by reading `/proc/self/maps`.
fn stack_low() -> Option<usize> {
  if let Some(low) = STACK_LOW.with(|s| s.get()) {
    return Some(low);
  }
  let low = query_stack_low()?;
  STACK_LOW.with(|s| s.set(Some(low)));
  Some(low)
}

#[cfg(not(target_vendor = "apple"))]
fn query_stack_low() -> Option<usize> {
  // SAFETY: `attr` is initialized by `pthread_getattr_np` before it is read, and destroyed once
  unsafe {
    let mut attr: libc::pthread_attr_t = std::mem::zeroed();
    if libc::pthread_getattr_np(libc::pthread_self(), &mut attr) != 0 {
      return None;
    }
    let mut addr: *mut libc::c_void = std::ptr::null_mut();
    let mut size: libc::size_t = 0;
    let got = libc::pthread_attr_getstack(&attr, &mut addr, &mut size);
    libc::pthread_attr_destroy(&mut attr);
    (got == 0).then_some(addr as usize)
  }
}

#[cfg(target_vendor = "apple")]
fn query_stack_low() -> Option<usize> {
  // SAFETY: both only read the calling thread's own attributes
  unsafe {
    let this = libc::pthread_self();
    (libc::pthread_get_stackaddr_np(this) as usize).checked_sub(libc::pthread_get_stacksize_np(this))
  }
}

/// Fits quickjs's stack limit to the thread about to enter the runtime. quickjs measures from where
/// the entry starts and defaults to 1 MB, which is more than an app thread entered part-way down
/// its own 1 MB stack has left, so deep recursion would fault instead of throwing `RangeError`.
/// Call before `Context::with`, which takes the lock this does.
pub fn fit_stack_limit(rt: &Runtime) {
  let Some(low) = stack_low() else {
    return;
  };
  let here = 0u8;
  let left = (&here as *const u8 as usize).saturating_sub(low);
  // 0 would lift the limit altogether
  rt.set_max_stack_size(left.saturating_sub(STACK_MARGIN_BYTES).max(1));
}

pub const HEAP_LIMIT_BYTES: usize = 32 * 1024 * 1024;

pub const EXTERNAL_LIMIT_BYTES: usize = 64 * 1024 * 1024;

const EXTERNAL_GC_STEP_BYTES: usize = 8 * 1024 * 1024;

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

pub struct ExternalMemory {
  charged: Cell<usize>,
  next_gc_at: Cell<usize>,
}

impl ExternalMemory {
  pub fn new() -> Rc<Self> {
    Rc::new(ExternalMemory {
      charged: Cell::new(0),
      next_gc_at: Cell::new(EXTERNAL_GC_STEP_BYTES),
    })
  }

  pub fn charged_bytes(&self) -> usize {
    self.charged.get()
  }

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

  pub fn charge<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, bytes: usize) -> JsResult<ExternalCharge> {
    if let Some(charge) = self.try_charge(ctx, bytes) {
      return Ok(charge);
    }
    PluginErrorCode::QuotaExceeded(self.charged.get().saturating_add(bytes) as i64, EXTERNAL_LIMIT_BYTES as i64).throw(
      ctx,
      &format!(
        "this plugin holds {:.1} MB of native memory and asked for {:.1} MB more, past its ceiling of {} MB",
        self.charged.get() as f64 / (1024.0 * 1024.0),
        bytes as f64 / (1024.0 * 1024.0),
        EXTERNAL_LIMIT_BYTES / (1024 * 1024),
      ),
    )
  }

  fn release(&self, bytes: usize) {
    let left = self.charged.get().saturating_sub(bytes);
    self.charged.set(left);
    let step = left.saturating_add(EXTERNAL_GC_STEP_BYTES);
    if self.next_gc_at.get() > step {
      self.next_gc_at.set(step);
    }
  }
}

pub struct ExternalCharge {
  owner: Rc<ExternalMemory>,
  bytes: usize,
}

impl ExternalCharge {
  pub fn bytes(&self) -> usize {
    self.bytes
  }

  pub fn try_grow(&mut self, ctx: &Ctx<'_>, extra: usize) -> bool {
    if !self.owner.take(ctx, extra) {
      return false;
    }
    self.bytes = self.bytes.saturating_add(extra);
    true
  }

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

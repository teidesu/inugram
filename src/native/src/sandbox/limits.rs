use std::cell::Cell;
use std::rc::Rc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use rquickjs::allocator::{Allocator, RustAllocator};
use rquickjs::{qjs, Context, Ctx, Result as JsResult, Runtime, Value};

use crate::api::error::PluginErrorCode;

pub const ENTRY_DEADLINE_MS: u64 = 2_000;

pub const EVAL_DEADLINE_MS: u64 = 10_000;

#[derive(Clone, Copy)]
struct Armed {
  deadline: Instant,
  limit_ms: u64,
  entry: u64,
}

static NEXT_ENTRY: AtomicU64 = AtomicU64::new(1);

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
    entry: previous.map_or_else(|| NEXT_ENTRY.fetch_add(1, Ordering::Relaxed), |previous| previous.entry),
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
    is_tripped()
  }
}

/// whether the innermost armed deadline has stopped JS; the handler already counted the strike
pub(crate) fn is_tripped() -> bool {
  TRIPPED.with(|t| t.get())
}

impl Drop for Deadline {
  fn drop(&mut self) {
    ARMED.with(|a| a.set(self.previous));
    let tripped = self.previously_tripped || TRIPPED.with(|t| t.get());
    TRIPPED.with(|t| t.set(tripped));
  }
}

/// for native work that runs between two checks of the interrupt handler
pub(crate) fn is_past_deadline() -> bool {
  ARMED.with(|a| a.get()).is_some_and(|armed| Instant::now() >= armed.deadline)
}

pub const MAX_BUDGET_STRIKES: u32 = 3;

pub fn install_interrupt_handler(rt: &Runtime, log: crate::Log) {
  let mut strikes = 0;
  rt.set_interrupt_handler(Some(Box::new(move || {
    let Some(armed) = ARMED.with(|a| a.get()) else {
      return false;
    };
    if Instant::now() < armed.deadline {
      return false;
    }
    if !TRIPPED.with(|t| t.replace(true)) {
      strikes += 1;
      let message = format!(
        "execution budget exceeded: this call ran more than {}ms of uninterrupted JS and was stopped ({strikes}/{MAX_BUDGET_STRIKES})",
        armed.limit_ms,
      );
      if strikes >= MAX_BUDGET_STRIKES {
        log(&crate::fault(message));
      } else {
        log(&message);
      }
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

/// Fits quickjs's stack limit to the calling thread. quickjs measures from the stack top it last
/// recorded and defaults to 1 MB, which is more than an app thread entered part-way down its own
/// 1 MB stack has left, so deep recursion would fault instead of throwing `RangeError`. The limit
/// this sets is `low + margin` whatever the depth, so refitting on the same thread is idempotent.
pub fn fit_stack_limit(context: &Context) {
  // SAFETY: the context's runtime lives as long as the context; both calls only store numbers
  let rt = unsafe { qjs::JS_GetRuntime(context.as_raw().as_ptr()) };
  unsafe { qjs::JS_UpdateStackTop(rt) };
  let Some(low) = stack_low() else {
    return;
  };
  let here = 0u8;
  let left = (&here as *const u8 as usize).saturating_sub(low);
  // 0 would lift the limit altogether
  unsafe { qjs::JS_SetMaxStackSize(rt, left.saturating_sub(STACK_MARGIN_BYTES).max(1) as _) };
}

/// Runs `f` with no deadline armed on this thread, then restores the previous one pushed back by the
/// extension `f` answers.
pub(crate) fn suspend_deadline<R>(f: impl FnOnce() -> (R, Duration)) -> R {
  let armed = ARMED.with(|a| a.take());
  let (result, extension) = f();
  ARMED.with(|a| {
    a.set(armed.map(|mut armed| {
      armed.deadline += extension;
      armed
    }))
  });
  result
}

pub const HEAP_LIMIT_BYTES: usize = 32 * 1024 * 1024;

pub const EXTERNAL_LIMIT_BYTES: usize = 64 * 1024 * 1024;

const EXTERNAL_GC_STEP_BYTES: usize = 8 * 1024 * 1024;

pub fn describe_heap_exhaustion<'js>(exception: &Value<'js>) -> Option<String> {
  let ceiling_mb = HEAP_LIMIT_BYTES / (1024 * 1024);
  let obj = exception.as_object()?;
  let out_of_memory = obj.get::<_, String>("name").ok().as_deref() == Some("InternalError")
    && obj.get::<_, String>("message").ok().as_deref() == Some("out of memory");
  if !out_of_memory {
    return None;
  }
  let mut message = format!(
    "quota-exceeded: this plugin's javascript heap ceiling of {ceiling_mb} MB was reached and the allocation was refused",
  );
  if let Ok(stack) = obj.get::<_, String>("stack") {
    if !stack.is_empty() {
      message.push('\n');
      message.push_str(&stack);
    }
  }
  Some(message)
}

/// What the first entry to reach the heap ceiling may allocate past it: the out-of-memory error
/// and the backtrace the interpreter attaches to it while unwinding.
pub const HEAP_RESERVE_BYTES: usize = 256 * 1024;

/// Holds the heap ceiling in place of `JS_SetMemoryLimit`, under which quickjs refuses the
/// allocations for the out-of-memory error as well and throws a bare `null`.
pub struct HeapAllocator {
  ceiling: usize,
  used: usize,
  reserve_entry: Option<u64>,
}

impl HeapAllocator {
  pub fn new(ceiling: usize) -> Self {
    HeapAllocator { ceiling, used: 0, reserve_entry: None }
  }

  fn admit(&mut self, growth: usize) -> bool {
    let wanted = self.used.saturating_add(growth);
    if wanted <= self.ceiling {
      return true;
    }
    let entry = ARMED.with(|a| a.get()).map_or(0, |armed| armed.entry);
    if self.reserve_entry == Some(entry) {
      return wanted <= self.ceiling + HEAP_RESERVE_BYTES;
    }
    self.reserve_entry = Some(entry);
    false
  }

  fn track(&mut self, ptr: *mut u8) -> *mut u8 {
    if !ptr.is_null() {
      // SAFETY: a live allocation just made by `RustAllocator`
      self.used += unsafe { RustAllocator::usable_size(ptr) };
    }
    ptr
  }
}

// SAFETY: every pointer comes from and goes back to `RustAllocator` unchanged
unsafe impl Allocator for HeapAllocator {
  fn alloc(&mut self, size: usize) -> *mut u8 {
    if !self.admit(size) {
      return std::ptr::null_mut();
    }
    let ptr = RustAllocator.alloc(size);
    self.track(ptr)
  }

  fn calloc(&mut self, count: usize, size: usize) -> *mut u8 {
    let Some(total) = count.checked_mul(size) else {
      return std::ptr::null_mut();
    };
    if !self.admit(total) {
      return std::ptr::null_mut();
    }
    let ptr = RustAllocator.calloc(count, size);
    self.track(ptr)
  }

  unsafe fn dealloc(&mut self, ptr: *mut u8) {
    self.used -= RustAllocator::usable_size(ptr);
    RustAllocator.dealloc(ptr);
  }

  unsafe fn realloc(&mut self, ptr: *mut u8, new_size: usize) -> *mut u8 {
    let old_size = RustAllocator::usable_size(ptr);
    if !self.admit(new_size.saturating_sub(old_size)) {
      return std::ptr::null_mut();
    }
    let new_ptr = RustAllocator.realloc(ptr, new_size);
    if new_ptr.is_null() {
      return new_ptr;
    }
    self.used -= old_size;
    self.track(new_ptr)
  }

  unsafe fn usable_size(ptr: *mut u8) -> usize {
    RustAllocator::usable_size(ptr)
  }
}

/// `inu memory` and the plugin info page read this. Walks every live object, so it is not for a hot path
pub fn describe_memory_usage(context: &Context, external: &ExternalMemory) -> String {
  let mut usage = std::mem::MaybeUninit::<qjs::JSMemoryUsage>::uninit();
  // SAFETY: under the engine lease; quickjs fills every field
  let usage = unsafe {
    qjs::JS_ComputeMemoryUsage(qjs::JS_GetRuntime(context.as_raw().as_ptr()), usage.as_mut_ptr());
    usage.assume_init()
  };
  serde_json::json!({
    "heapBytes": usage.malloc_size,
    "heapLimitBytes": HEAP_LIMIT_BYTES,
    "nativeBytes": external.charged_bytes(),
    "nativeLimitBytes": EXTERNAL_LIMIT_BYTES,
    "objects": usage.obj_count,
    "arrays": usage.array_count,
    "functions": usage.js_func_count,
    "strings": usage.str_count,
    "stringBytes": usage.str_size,
    "shapes": usage.shape_count,
    "atoms": usage.atom_count,
  })
  .to_string()
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

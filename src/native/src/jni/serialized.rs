use std::marker::PhantomData;
use std::ops::Deref;
use std::panic::{self, AssertUnwindSafe};
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread::{self, ThreadId};
use std::time::{Duration, Instant};

#[derive(Debug, PartialEq, Eq)]
pub(super) enum EntryError {
  Closed,
  Reentrant,
  Busy,
}

/// The value a parked owner lent out. Boxed, so its address outlives moves of the owner's [Lease].
struct Lent<T>(*const T);

// SAFETY: a borrower dereferences it only while the lender is parked in [Serialized::lend], which does
// not return (so the owner's lease cannot drop) until every borrower has left
unsafe impl<T: Send> Send for Lent<T> {}

struct State<T> {
  value: Option<Box<T>>,
  owner: Option<ThreadId>,
  lent: Option<Lent<T>>,
  lender: Option<ThreadId>,
  closed: bool,
  waiting: usize,
}

pub(super) struct Serialized<T> {
  state: Mutex<State<T>>,
  changed: Condvar,
  admitting: AtomicBool,
}

impl<T> Serialized<T> {
  pub(super) fn new(value: T) -> Arc<Self> {
    Arc::new(Self {
      state: Mutex::new(State {
        value: Some(Box::new(value)),
        owner: None,
        lent: None,
        lender: None,
        closed: false,
        waiting: 0,
      }),
      changed: Condvar::new(),
      admitting: AtomicBool::new(true),
    })
  }

  /// Flipped without the lease: the holder may be an app thread inside an unbounded Java call.
  pub(super) fn stop_admitting(&self) {
    self.admitting.store(false, Ordering::Release);
  }

  pub(super) fn is_admitting(&self) -> bool {
    self.admitting.load(Ordering::Acquire)
  }

  pub(super) fn enter(self: &Arc<Self>, timeout: Option<Duration>) -> Result<Lease<T>, EntryError> {
    let deadline = timeout.map(|timeout| Instant::now() + timeout);
    let mut state = self.lock();
    loop {
      if state.closed {
        return Err(EntryError::Closed);
      }
      if state.owner == Some(thread::current().id()) {
        return Err(EntryError::Reentrant);
      }
      if state.owner.is_none() {
        let value = match (state.value.take(), &state.lent) {
          (Some(value), _) => Some(Held::Owned(value)),
          (None, Some(lent)) => Some(Held::Borrowed(lent.0)),
          (None, None) => None,
        };
        if let Some(value) = value {
          state.owner = Some(thread::current().id());
          return Ok(Lease {
            slot: self.clone(),
            value: Some(value),
            thread: PhantomData,
          });
        }
      }
      state = if let Some(deadline) = deadline {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
          return Err(EntryError::Busy);
        }
        self.wait(state, Some(remaining))
      } else {
        self.wait(state, None)
      };
    }
  }

  /// Runs `f` with the owner's value open to one borrower at a time, then takes it back once the current
  /// borrower has left. Answers how long that took past `f`.
  ///
  /// # Safety
  /// The calling thread owns this slot's lease, not a borrowed one, and `value` is the value it holds.
  pub(super) unsafe fn lend<R>(&self, value: *const T, f: impl FnOnce() -> R) -> (R, Duration) {
    {
      let mut state = self.lock();
      state.owner = None;
      state.lent = Some(Lent(value));
      state.lender = Some(thread::current().id());
      self.wake(&state);
    }
    let result = panic::catch_unwind(AssertUnwindSafe(f));
    let returned = Instant::now();
    let mut state = self.lock();
    state.lent = None;
    while state.owner.is_some() {
      state = self.wait(state, None);
    }
    state.lender = None;
    state.owner = Some(thread::current().id());
    drop(state);
    match result {
      Ok(result) => (result, returned.elapsed()),
      Err(panic) => panic::resume_unwind(panic),
    }
  }

  fn lock(&self) -> MutexGuard<'_, State<T>> {
    self.state.lock().unwrap_or_else(|e| e.into_inner())
  }

  /// Tracks waiters so lease release can skip `notify_all` when none exist. Every callback acquires
  /// and releases the lease; an unnecessary notification would add a futex syscall.
  fn wait<'a>(&self, mut state: MutexGuard<'a, State<T>>, remaining: Option<Duration>) -> MutexGuard<'a, State<T>> {
    state.waiting += 1;
    let mut state = match remaining {
      Some(remaining) => self.changed.wait_timeout(state, remaining).unwrap_or_else(|e| e.into_inner()).0,
      None => self.changed.wait(state).unwrap_or_else(|e| e.into_inner()),
    };
    state.waiting -= 1;
    state
  }

  fn wake(&self, state: &State<T>) {
    if state.waiting != 0 {
      self.changed.notify_all();
    }
  }

  pub(super) fn close(&self) -> Result<Option<T>, EntryError> {
    let mut state = self.lock();
    let current = Some(thread::current().id());
    if state.owner == current || state.lender == current {
      return Err(EntryError::Reentrant);
    }
    state.closed = true;
    self.wake(&state);
    while state.owner.is_some() || state.lender.is_some() {
      state = self.wait(state, None);
    }
    Ok(state.value.take().map(|value| *value))
  }
}

enum Held<T> {
  Owned(Box<T>),
  Borrowed(*const T),
}

pub(super) struct Lease<T> {
  slot: Arc<Serialized<T>>,
  value: Option<Held<T>>,
  thread: PhantomData<Rc<()>>,
}

impl<T> Lease<T> {
  pub(super) fn is_admitting(&self) -> bool {
    self.slot.is_admitting()
  }

  pub(super) fn is_borrowed(&self) -> bool {
    matches!(self.value, Some(Held::Borrowed(_)))
  }

  pub(super) fn slot(&self) -> &Arc<Serialized<T>> {
    &self.slot
  }
}

impl<T> Deref for Lease<T> {
  type Target = T;

  fn deref(&self) -> &T {
    match self.value.as_ref().unwrap() {
      Held::Owned(value) => value,
      // SAFETY: see [Lent]
      Held::Borrowed(value) => unsafe { &**value },
    }
  }
}

impl<T> Drop for Lease<T> {
  fn drop(&mut self) {
    let mut state = self.slot.lock();
    if let Some(Held::Owned(value)) = self.value.take() {
      state.value = Some(value);
    }
    state.owner = None;
    self.slot.wake(&state);
  }
}

#[cfg(test)]
#[path = "serialized_tests.rs"]
mod tests;

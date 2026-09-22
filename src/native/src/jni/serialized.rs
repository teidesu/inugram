use std::marker::PhantomData;
use std::ops::Deref;
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

struct State<T> {
  value: Option<T>,
  owner: Option<ThreadId>,
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
        value: Some(value),
        owner: None,
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
    let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
    loop {
      if state.closed {
        return Err(EntryError::Closed);
      }
      if state.owner == Some(thread::current().id()) {
        return Err(EntryError::Reentrant);
      }
      if let Some(value) = state.value.take() {
        state.owner = Some(thread::current().id());
        return Ok(Lease {
          slot: self.clone(),
          value: Some(value),
          thread: PhantomData,
        });
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
    let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
    if state.owner == Some(thread::current().id()) {
      return Err(EntryError::Reentrant);
    }
    state.closed = true;
    self.wake(&state);
    while state.owner.is_some() {
      state = self.wait(state, None);
    }
    Ok(state.value.take())
  }
}

pub(super) struct Lease<T> {
  slot: Arc<Serialized<T>>,
  value: Option<T>,
  thread: PhantomData<Rc<()>>,
}

impl<T> Lease<T> {
  pub(super) fn is_admitting(&self) -> bool {
    self.slot.is_admitting()
  }
}

impl<T> Deref for Lease<T> {
  type Target = T;

  fn deref(&self) -> &T {
    self.value.as_ref().unwrap()
  }
}

impl<T> Drop for Lease<T> {
  fn drop(&mut self) {
    let mut state = self.slot.state.lock().unwrap_or_else(|e| e.into_inner());
    state.value = self.value.take();
    state.owner = None;
    self.slot.wake(&state);
  }
}

#[cfg(test)]
#[path = "serialized_tests.rs"]
mod tests;

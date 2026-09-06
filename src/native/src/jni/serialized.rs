use std::marker::PhantomData;
use std::ops::Deref;
use std::rc::Rc;
use std::sync::{Arc, Condvar, Mutex};
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
}

pub(super) struct Serialized<T> {
  state: Mutex<State<T>>,
  changed: Condvar,
}

impl<T> Serialized<T> {
  pub(super) fn new(value: T) -> Arc<Self> {
    Arc::new(Self {
      state: Mutex::new(State {
        value: Some(value),
        owner: None,
        closed: false,
      }),
      changed: Condvar::new(),
    })
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
        self.changed.wait_timeout(state, remaining).unwrap_or_else(|e| e.into_inner()).0
      } else {
        self.changed.wait(state).unwrap_or_else(|e| e.into_inner())
      };
    }
  }

  pub(super) fn close(&self) -> Result<Option<T>, EntryError> {
    let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
    if state.owner == Some(thread::current().id()) {
      return Err(EntryError::Reentrant);
    }
    state.closed = true;
    self.changed.notify_all();
    while state.owner.is_some() {
      state = self.changed.wait(state).unwrap_or_else(|e| e.into_inner());
    }
    Ok(state.value.take())
  }
}

pub(super) struct Lease<T> {
  slot: Arc<Serialized<T>>,
  value: Option<T>,
  thread: PhantomData<Rc<()>>,
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
    self.slot.changed.notify_all();
  }
}

#[cfg(test)]
#[path = "serialized_tests.rs"]
mod tests;

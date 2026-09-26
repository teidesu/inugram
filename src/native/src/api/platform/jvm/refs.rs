use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex};

use jni::objects::JObject;
use jni::refs::Global;
use rquickjs::class::{Trace, Tracer};
use rquickjs::JsLifetime;

use super::native::Pinned;

pub(crate) const KIND_CLASS: u8 = b'C';
pub(crate) const KIND_OBJECT: u8 = b'O';
pub(crate) const KIND_METHOD: u8 = b'M';
pub(crate) const KIND_CONSTRUCTOR: u8 = b'K';
pub(crate) const KIND_FIELD: u8 = b'F';

#[derive(Clone)]
pub(crate) struct Entry {
  pub(crate) obj: Arc<Global<JObject<'static>>>,
  pub(crate) kind: u8,
}

/// Kotlin reaches this through `nativeJvm*` without the engine lease (a hooked thread may encode
/// while another owns the engine), so the table has its own mutex.
pub(crate) struct RefTable {
  entries: Mutex<HashMap<i64, Entry>>,
  next: AtomicI64,
  closed: AtomicBool,
}

impl RefTable {
  pub(crate) fn new() -> Arc<Self> {
    Arc::new(Self {
      entries: Mutex::new(HashMap::new()),
      next: AtomicI64::new(1),
      closed: AtomicBool::new(false),
    })
  }

  /// Insert under the same lock used by `close`, so a concurrent mint cannot add an entry after the
  /// table has been drained.
  pub(crate) fn mint(&self, obj: Global<JObject<'static>>, kind: u8) -> Option<i64> {
    self.insert(Entry { obj: Arc::new(obj), kind })
  }

  fn insert(&self, entry: Entry) -> Option<i64> {
    let mut entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
    if self.closed.load(Ordering::Acquire) {
      drop(entries);
      drop(entry);
      return None;
    }
    let id = self.next.fetch_add(1, Ordering::Relaxed);
    entries.insert(id, entry);
    Some(id)
  }

  /// a second id for the same reference, for a handoff whose original the other side releases
  pub(crate) fn copy(&self, id: i64) -> Option<i64> {
    let entry = self.get(id)?;
    self.insert(entry)
  }

  pub(crate) fn get(&self, id: i64) -> Option<Entry> {
    self.entries.lock().unwrap_or_else(|e| e.into_inner()).get(&id).cloned()
  }

  pub(crate) fn release(&self, id: i64) {
    let removed = self.entries.lock().unwrap_or_else(|e| e.into_inner()).remove(&id);
    drop(removed);
  }

  pub(crate) fn is_closed(&self) -> bool {
    self.closed.load(Ordering::Acquire)
  }

  /// Expire all handles together, then drop global references outside the lock because releasing
  /// them enters the VM.
  pub(crate) fn close(&self) {
    let drained = {
      let mut entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
      self.closed.store(true, Ordering::Release);
      std::mem::take(&mut *entries)
    };
    drop(drained);
  }

  #[cfg(test)]
  pub(crate) fn len(&self) -> usize {
    self.entries.lock().unwrap_or_else(|e| e.into_inner()).len()
  }
}

#[derive(JsLifetime)]
#[rquickjs::class(frozen)]
pub(crate) struct JvmRef {
  pub(crate) id: i64,
  refs: Arc<RefTable>,
  pub(crate) class_key: Cell<Option<usize>>,
  pub(crate) pinned: RefCell<Option<Rc<Pinned>>>,
}

impl JvmRef {
  pub(crate) fn new(refs: Arc<RefTable>, id: i64) -> Self {
    Self {
      id,
      refs,
      class_key: Cell::new(None),
      pinned: RefCell::new(None),
    }
  }
}

impl Drop for JvmRef {
  fn drop(&mut self) {
    self.refs.release(self.id);
  }
}

impl<'js> Trace<'js> for JvmRef {
  fn trace<'a>(&self, _tracer: Tracer<'a, 'js>) {}
}

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex};

use jni::objects::JObject;
use jni::refs::Global;
use rquickjs::class::{JsClass, Readable, Trace, Tracer};
use rquickjs::{Constructor, Ctx, JsLifetime, Result as JsResult};

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

/// The java references a plugin holds, keyed by the id its js handles carry.
///
/// Owned here rather than on the kotlin side because a call needs the `jobject` itself, not a
/// number naming one: with the table beside the caller, an argument is a lookup and a result is a
/// `NewGlobalRef`, and nothing about a reference crosses as text. Kotlin reaches the same table
/// through the `nativeJvm*` exports, without the engine lease - the hooked thread encoding an
/// `inu.xposed` argument holds no lease and must not wait for one - which is why this is a mutex
/// and not engine state.
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

  /// inserted under the same lock `close` drains under, so a mint racing the close can never
  /// land an entry nothing will drop again
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

  /// every handle expires at once; the references are dropped outside the lock, since a global
  /// ref's release re-enters the vm
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

/// What a js handle *is*: the id of a table entry, read off the object with no js call. The class
/// cache key and the pinned member are the two answers a handle is asked for repeatedly and never
/// change, so they live on the handle.
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

// SAFETY: `JvmRef` holds no JavaScript-lifetime-bound data.
unsafe impl JsLifetime<'_> for JvmRef {
  type Changed<'to> = Self;
}

impl<'js> JsClass<'js> for JvmRef {
  const NAME: &'static str = "JvmRef";
  type Mutable = Readable;

  fn constructor(_ctx: &Ctx<'js>) -> JsResult<Option<Constructor<'js>>> {
    Ok(None)
  }
}

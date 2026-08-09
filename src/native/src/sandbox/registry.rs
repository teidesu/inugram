use std::cell::{Cell, RefCell};
use std::rc::Rc;

use rquickjs::{Ctx, Function, Persistent, Result as JsResult};

pub type Token = u32;

pub struct RequestIds(Cell<i64>);

impl Default for RequestIds {
  fn default() -> Self {
    RequestIds(Cell::new(1))
  }
}

impl RequestIds {
  pub fn alloc(&self) -> i64 {
    let id = self.0.get();
    self.0.set(id + 1);
    id
  }
}

struct Entry<T> {
  token: Token,
  key: Option<String>,
  value: T,
}

pub struct Registry<T> {
  entries: RefCell<Vec<Entry<T>>>,
  next_token: Cell<Token>,
}

impl<T> Default for Registry<T> {
  fn default() -> Self {
    Registry {
      entries: RefCell::new(Vec::new()),
      next_token: Cell::new(1),
    }
  }
}

impl<T> Registry<T> {
  pub fn alloc(&self) -> Token {
    let token = self.next_token.get();
    self.next_token.set(token.wrapping_add(1));
    token
  }

  pub fn insert(&self, token: Token, key: Option<String>, value: T) -> Option<T> {
    let mut entries = self.entries.borrow_mut();
    if let Some(key) = key.as_deref() {
      if let Some(slot) = entries.iter_mut().find(|e| e.key.as_deref() == Some(key)) {
        slot.token = token;
        return Some(std::mem::replace(&mut slot.value, value));
      }
    }
    entries.push(Entry { token, key, value });
    None
  }

  pub fn remove(&self, token: Token) -> Option<T> {
    let mut entries = self.entries.borrow_mut();
    let index = entries.iter().position(|e| e.token == token)?;
    Some(entries.remove(index).value)
  }

  pub fn get(&self, token: Token) -> Option<T>
  where
    T: Clone,
  {
    self.entries.borrow().iter().find(|e| e.token == token).map(|e| e.value.clone())
  }

  pub fn contains(&self, token: Token) -> bool {
    self.entries.borrow().iter().any(|e| e.token == token)
  }

  pub fn remove_matching(&self, predicate: impl Fn(&T) -> bool) -> Vec<T> {
    let mut entries = self.entries.borrow_mut();
    let mut retained = Vec::with_capacity(entries.len());
    let mut removed = Vec::with_capacity(entries.len());
    for entry in std::mem::take(&mut *entries) {
      if predicate(&entry.value) {
        removed.push(entry.value);
      } else {
        retained.push(entry);
      }
    }
    *entries = retained;
    removed
  }

  pub fn values(&self) -> Vec<T>
  where
    T: Clone,
  {
    self.entries.borrow().iter().map(|e| e.value.clone()).collect()
  }

  pub fn take_values(&self) -> Vec<T> {
    self.entries.borrow_mut().drain(..).map(|e| e.value).collect()
  }

  pub fn is_empty(&self) -> bool {
    self.entries.borrow().is_empty()
  }

  pub fn len(&self) -> usize {
    self.entries.borrow().len()
  }
}

pub type CallbackRegistry = Registry<Persistent<Function<'static>>>;

impl CallbackRegistry {
  pub fn register<'js>(&self, ctx: &Ctx<'js>, token: Token, key: Option<String>, callback: Function<'js>) {
    if let Some(previous) = self.insert(token, key, Persistent::save(ctx, callback)) {
      let _ = previous.restore(ctx);
    }
  }

  pub fn restore<'js>(&self, ctx: &Ctx<'js>, token: Token) -> Option<Function<'js>> {
    let persistent = self.entries.borrow().iter().find(|e| e.token == token).map(|e| e.value.clone())?;
    persistent.restore(ctx).ok()
  }

  pub fn snapshot<'js>(&self, ctx: &Ctx<'js>) -> Vec<Function<'js>> {
    let persistents: Vec<_> = self.entries.borrow().iter().map(|e| e.value.clone()).collect();
    persistents.into_iter().filter_map(|p| p.restore(ctx).ok()).collect()
  }

  pub fn dispose(&self, ctx: &Ctx<'_>, token: Token) -> bool {
    match self.remove(token) {
      Some(persistent) => {
        let _ = persistent.restore(ctx);
        true
      }
      None => false,
    }
  }

  pub fn take_all<'js>(&self, ctx: &Ctx<'js>) -> Vec<Function<'js>> {
    let persistents: Vec<_> = self.entries.borrow_mut().drain(..).map(|e| e.value).collect();
    persistents.into_iter().filter_map(|p| p.restore(ctx).ok()).collect()
  }

  pub fn release_all(&self, ctx: &Ctx<'_>) {
    for persistent in self.entries.borrow_mut().drain(..) {
      let _ = persistent.value.restore(ctx);
    }
  }
}

#[derive(Default)]
pub struct Lifecycle {
  unloading: Cell<bool>,
  blocking_dispatches: Cell<usize>,
}

impl Lifecycle {
  pub fn new() -> Rc<Lifecycle> {
    Rc::new(Lifecycle::default())
  }

  pub fn begin_unload(&self) {
    self.unloading.set(true);
  }

  pub fn is_unloading(&self) -> bool {
    self.unloading.get()
  }

  pub fn set_blocking_dispatches(&self, count: usize) {
    self.blocking_dispatches.set(count);
  }

  pub fn has_blocking_dispatches(&self) -> bool {
    self.blocking_dispatches.get() != 0
  }
}

pub fn noop_disposer<'js>(ctx: &Ctx<'js>) -> JsResult<Function<'js>> {
  Function::new(ctx.clone(), || {})
}

pub fn make_disposer<'js>(ctx: &Ctx<'js>, dispose: impl Fn(&Ctx<'js>) + 'js) -> JsResult<Function<'js>> {
  Function::new(ctx.clone(), move |ctx: Ctx<'js>| dispose(&ctx))
}

#[cfg(test)]
#[path = "registry_tests.rs"]
mod registry_tests;

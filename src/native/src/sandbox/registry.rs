use std::cell::{Cell, RefCell};
use std::rc::Rc;

use rquickjs::function::This;
use rquickjs::{Ctx, Function, Persistent, Result as JsResult, Value};

use crate::utils::qjs::qjs_symbol_dispose_atom;

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
  token: u32,
  key: Option<String>,
  value: T,
}

pub struct Registry<T> {
  entries: RefCell<Vec<Entry<T>>>,
  next_token: Cell<u32>,
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
  pub fn alloc(&self) -> u32 {
    let token = self.next_token.get();
    self.next_token.set(token.wrapping_add(1));
    token
  }

  pub fn insert(&self, token: u32, key: Option<String>, value: T) -> Option<T> {
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

  pub fn remove(&self, token: u32) -> Option<T> {
    let mut entries = self.entries.borrow_mut();
    let index = entries.iter().position(|e| e.token == token)?;
    Some(entries.remove(index).value)
  }

  pub fn get(&self, token: u32) -> Option<T>
  where
    T: Clone,
  {
    self.entries.borrow().iter().find(|e| e.token == token).map(|e| e.value.clone())
  }

  pub fn contains(&self, token: u32) -> bool {
    self.entries.borrow().iter().any(|e| e.token == token)
  }

  pub fn remove_matching(&self, predicate: impl Fn(&T) -> bool) -> Vec<T> {
    self.entries.borrow_mut().extract_if(.., |e| predicate(&e.value)).map(|e| e.value).collect()
  }

  pub fn values(&self) -> Vec<T>
  where
    T: Clone,
  {
    self.entries.borrow().iter().map(|e| e.value.clone()).collect()
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
  pub fn register<'js>(&self, ctx: &Ctx<'js>, token: u32, callback: Function<'js>) {
    self.insert(token, None, Persistent::save(ctx, callback));
  }

  /// `callback` until its disposer runs, or nothing once the plugin is unloading
  pub fn subscribe<'js, S: 'js>(
    ctx: &Ctx<'js>,
    owner: &Rc<S>,
    lifecycle: &Lifecycle,
    registry: fn(&S) -> &CallbackRegistry,
    callback: Function<'js>,
  ) -> JsResult<Function<'js>> {
    if lifecycle.is_unloading() {
      return noop_disposer(ctx);
    }
    let token = registry(owner).alloc();
    registry(owner).register(ctx, token, callback);
    let owner = owner.clone();
    make_disposer(ctx, move |ctx| {
      registry(&owner).dispose(ctx, token);
    })
  }

  pub fn restore<'js>(&self, ctx: &Ctx<'js>, token: u32) -> Option<Function<'js>> {
    let persistent = self.entries.borrow().iter().find(|e| e.token == token).map(|e| e.value.clone())?;
    persistent.restore(ctx).ok()
  }

  pub fn snapshot<'js>(&self, ctx: &Ctx<'js>) -> Vec<Function<'js>> {
    let persistents: Vec<_> = self.entries.borrow().iter().map(|e| e.value.clone()).collect();
    persistents.into_iter().filter_map(|p| p.restore(ctx).ok()).collect()
  }

  pub fn dispose(&self, _ctx: &Ctx<'_>, token: u32) -> bool {
    self.remove(token).is_some()
  }

  pub fn take_all<'js>(&self, ctx: &Ctx<'js>) -> Vec<Function<'js>> {
    let persistents: Vec<_> = self.entries.borrow_mut().drain(..).map(|e| e.value).collect();
    persistents.into_iter().filter_map(|p| p.restore(ctx).ok()).collect()
  }

  pub fn release_all(&self, _ctx: &Ctx<'_>) {
    let entries = std::mem::take(&mut *self.entries.borrow_mut());
    drop(entries);
  }
}

#[derive(Default)]
pub struct Lifecycle {
  unloading: Cell<bool>,
  cleanup_deadline: Cell<Option<std::time::Instant>>,
  blocking_dispatches: Cell<usize>,
}

impl Lifecycle {
  pub fn new() -> Rc<Lifecycle> {
    Rc::new(Lifecycle::default())
  }

  pub fn begin_unload(&self) {
    self.unloading.set(true);
  }

  pub fn begin_cleanup(&self) {
    self.begin_unload();
    self.cleanup_deadline.set(Some(std::time::Instant::now() + std::time::Duration::from_secs(2)));
  }

  pub fn finish_cleanup(&self) {
    self.cleanup_deadline.set(None);
  }

  pub fn is_cleaning_up(&self) -> bool {
    self.cleanup_deadline.get().is_some_and(|deadline| std::time::Instant::now() < deadline)
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
  disposable(ctx, Function::new(ctx.clone(), || {})?)
}

pub fn make_disposer<'js>(ctx: &Ctx<'js>, dispose: impl Fn(&Ctx<'js>) + 'js) -> JsResult<Function<'js>> {
  disposable(ctx, Function::new(ctx.clone(), move |ctx: Ctx<'js>| dispose(&ctx))?)
}

/// every disposer is also a `Disposable`, so `using` and `DisposableStack` take one as they are
fn disposable<'js>(ctx: &Ctx<'js>, f: Function<'js>) -> JsResult<Function<'js>> {
  f.set(qjs_symbol_dispose_atom(ctx)?, f.clone())?;
  Ok(f)
}

/// what a callback hands back to be torn down later: a plain function, or any `Disposable`, whose
/// method has to keep its object as `this` - which is what `bind` is for
pub fn resolve_disposer<'js>(ctx: &Ctx<'js>, value: Value<'js>) -> Option<Function<'js>> {
  if let Some(function) = value.as_function() {
    return Some(function.clone());
  }
  let object = value.as_object()?;
  let dispose: Function<'js> = object.get(qjs_symbol_dispose_atom(ctx).ok()?).ok()?;
  let bind: Function<'js> = dispose.get("bind").ok()?;
  bind.call((This(dispose), object.clone())).ok()
}

#[cfg(test)]
#[path = "registry_tests.rs"]
mod registry_tests;

use std::cell::RefCell;
use std::collections::HashMap;

use rquickjs::{Ctx, Function, Persistent, Result as JsResult, Runtime, Value};

use crate::api::error;
use crate::jni::is_caller_entry;
use crate::sandbox::registry::RequestIds;

/// Selects the host response table; keep in sync with `QuickJs.SETTLE_*`.
pub(crate) const SETTLE_FETCH: i32 = 0;
pub(crate) const SETTLE_CANVAS: i32 = 1;
pub(crate) const SETTLE_MODAL: i32 = 2;
pub(crate) const SETTLE_FILES: i32 = 3;
pub(crate) const SETTLE_READS: i32 = 4;
pub(crate) const SETTLE_WRITES: i32 = 5;
pub(crate) const SETTLE_INVOKE: i32 = 6;

pub(crate) struct PendingSettle {
  pub(crate) resolve: Persistent<Function<'static>>,
  pub(crate) reject: Persistent<Function<'static>>,
}

impl PendingSettle {
  pub(crate) fn new<'js>(ctx: &Ctx<'js>) -> JsResult<(rquickjs::Promise<'js>, Self)> {
    let (promise, resolve, reject) = rquickjs::Promise::new(ctx)?;
    Ok((
      promise,
      PendingSettle {
        resolve: Persistent::save(ctx, resolve),
        reject: Persistent::save(ctx, reject),
      },
    ))
  }

  pub(crate) fn reject_with(self, ctx: &Ctx<'_>, msg: &str) -> JsResult<()> {
    let error_val = match error::host_error_to_js(ctx, msg) {
      Ok(v) => v,
      Err(e) => {
        self.release(ctx);
        return Err(e);
      }
    };
    self.reject_with_value(ctx, error_val)
  }

  pub(crate) fn reject_with_value<'js>(self, ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<()> {
    let reject = self.reject.restore(ctx)?;
    let _ = self.resolve.restore(ctx);
    reject.call::<_, Value>((value,))?;
    Ok(())
  }

  pub(crate) fn resolve_with<'js>(self, ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<()> {
    let resolve = self.resolve.restore(ctx)?;
    let _ = self.reject.restore(ctx);
    resolve.call::<_, Value>((value,))?;
    Ok(())
  }

  pub(crate) fn release(self, ctx: &Ctx<'_>) {
    let _ = self.resolve.restore(ctx);
    let _ = self.reject.restore(ctx);
  }
}

pub fn pump_jobs(rt: &Runtime, context: &rquickjs::Context, log: &dyn Fn(&str)) {
  if is_caller_entry() {
    return;
  }
  loop {
    match rt.execute_pending_job() {
      Ok(true) => continue,
      Ok(false) => break,
      Err(e) => {
        log(&format!("unhandled error running microtask: {e:?}"));
        break;
      }
    }
  }
  context.with(|ctx| error::report_rejections(&ctx));
}

/// State released by `nativeDestroy` before the runtime is destroyed.
pub(crate) trait Dispose {
  fn dispose(&self, context: &rquickjs::Context);
}

/// Resources held with a pending promise and their cleanup outcome.
pub(crate) trait Parked: Sized {
  /// the request failed: refused before it crossed, or answered with an error
  fn reject(self, _ctx: &Ctx<'_>) {}

  /// the engine is going away with the request still out
  fn release(self, _ctx: &Ctx<'_>) {}
}

impl Parked for () {}

struct Entry<T> {
  settle: Option<PendingSettle>,
  parked: T,
}

/// Tracks pending host requests by API, including their IDs, promises, and resources. Ignores
/// settlements for unknown IDs, including duplicate responses and responses after abort.
pub(crate) struct PendingTable<T: Parked> {
  ids: RequestIds,
  entries: RefCell<HashMap<i64, Entry<T>>>,
}

impl<T: Parked> Default for PendingTable<T> {
  fn default() -> Self {
    PendingTable {
      ids: RequestIds::default(),
      entries: RefCell::new(HashMap::new()),
    }
  }
}

impl<T: Parked> PendingTable<T> {
  /// Registers before calling `ask`, so synchronous host responses can find the request. An error
  /// returned by `ask` rejects the same promise.
  pub(crate) fn park<'js>(
    &self,
    ctx: &Ctx<'js>,
    parked: T,
    ask: impl FnOnce(i64) -> Option<String>,
  ) -> JsResult<rquickjs::Promise<'js>> {
    let id = self.ids.alloc();
    let (promise, settle) = PendingSettle::new(ctx)?;
    self.entries.borrow_mut().insert(id, Entry { settle: Some(settle), parked });
    if let Some(refusal) = ask(id) {
      let removed = self.entries.borrow_mut().remove(&id);
      if let Some(entry) = removed {
        entry.parked.reject(ctx);
        if let Some(settle) = entry.settle {
          settle.reject_with(ctx, &refusal)?;
        }
      }
    }
    Ok(promise)
  }

  /// Settles a request with the host's `wire`: an error wire rejects, anything else is `decode`d.
  pub(crate) fn settle<'js>(
    &self,
    ctx: &Ctx<'js>,
    id: i64,
    wire: &str,
    keep: bool,
    decode: impl FnOnce(&Ctx<'js>, &mut T, &str) -> JsResult<Value<'js>>,
  ) -> Result<(), String> {
    self.settle_with(ctx, id, keep, |ctx, parked| {
      if let Some(error) = error::wire_error_to_js(ctx, wire) {
        return Err(ctx.throw(error?));
      }
      decode(ctx, parked, wire)
    })
  }

  /// Settles from `produce`; rejects if it throws or returns an unreadable response.
  ///
  /// With `keep`, settles the promise but retains resources until a second response with the same
  /// ID. This supports responses delivered in two stages.
  pub(crate) fn settle_with<'js>(
    &self,
    ctx: &Ctx<'js>,
    id: i64,
    keep: bool,
    produce: impl FnOnce(&Ctx<'js>, &mut T) -> JsResult<Value<'js>>,
  ) -> Result<(), String> {
    let removed = self.entries.borrow_mut().remove(&id);
    let Some(mut entry) = removed else {
      return Ok(());
    };
    let Some(settle) = entry.settle.take() else {
      if keep {
        self.entries.borrow_mut().insert(id, entry);
      } else {
        entry.parked.release(ctx);
      }
      return Ok(());
    };
    let produced = produce(ctx, &mut entry.parked);
    let rejection = match &produced {
      Ok(_) => None,
      Err(e) if e.is_exception() => Some(Ok(ctx.catch())),
      Err(e) => Some(error::make_plugin_error(
        ctx,
        "internal",
        &format!("the host's answer could not be read: {e}"),
        None,
        None,
        None,
      )),
    };
    if keep {
      self.entries.borrow_mut().insert(id, entry);
    } else if rejection.is_none() {
      drop(entry);
    } else {
      entry.parked.reject(ctx);
    }
    let settled = match (produced, rejection) {
      (Ok(value), _) => settle.resolve_with(ctx, value),
      (Err(_), Some(Ok(value))) => settle.reject_with_value(ctx, value),
      (Err(_), Some(Err(e))) | (Err(e), None) => {
        settle.release(ctx);
        return Err(format!("{e:?}"));
      }
    };
    settled.map_err(|_| error::format_exception(ctx))
  }

  /// Removes a request without settling its promise when its response is no longer needed.
  pub(crate) fn forget(&self, ctx: &Ctx<'_>, id: i64) {
    let removed = self.entries.borrow_mut().remove(&id);
    if let Some(entry) = removed {
      if let Some(settle) = entry.settle {
        settle.release(ctx);
      }
      entry.parked.release(ctx);
    }
  }

  pub(crate) fn with_parked<R>(&self, id: i64, read: impl FnOnce(&T) -> R) -> Option<R> {
    self.entries.borrow().get(&id).map(|entry| read(&entry.parked))
  }

  pub(crate) fn count(&self, matches: impl Fn(&T) -> bool) -> usize {
    self.entries.borrow().values().filter(|entry| matches(&entry.parked)).count()
  }

  #[cfg(test)]
  pub(crate) fn is_empty(&self) -> bool {
    self.entries.borrow().is_empty()
  }

  #[cfg(test)]
  pub(crate) fn len(&self) -> usize {
    self.entries.borrow().len()
  }

  pub(crate) fn dispose(&self, ctx: &Ctx<'_>) {
    let drained: Vec<_> = self.entries.borrow_mut().drain().collect();
    for (_, entry) in drained {
      if let Some(settle) = entry.settle {
        settle.release(ctx);
      }
      entry.parked.release(ctx);
    }
  }
}

#[cfg(test)]
#[path = "runtime_tests.rs"]
mod runtime_tests;

//! The bookkeeping every `on*`/`intercept*`/`register*` entry point shares, so the `Disposer` rules
//! `src/plugins/common.d.ts` states hold identically for all of them rather than per entry point:
//!
//! - a dispatch walks a [`CallbackRegistry::snapshot`] taken before the first handler runs, so a
//!   registration made mid-dispatch joins from the *next* one and a disposal mid-dispatch still
//!   lets the in-flight run finish;
//! - a keyed registration replaces the entry holding that id, in place; an unkeyed one stacks;
//! - once [`Lifecycle::begin_unload`] has run every entry point answers [`noop_disposer`].
//!
//! Tokens are never reused, so a dispatch in flight for a disposed registration cannot be mistaken
//! for a later one's.

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use rquickjs::{Ctx, Function, Persistent, Result as JsResult};

pub type Token = u32;

/// The id a host call is answered by, handed out by whichever state owns that call's pending table.
///
/// Not a [`Registry`] token: those name a registration the host can dispose, these name one
/// outstanding request, and both must be able to run out of a *different* id space per state.
/// Starts at 1, so a state that has answered nothing is distinguishable from one id.
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
        Registry { entries: RefCell::new(Vec::new()), next_token: Cell::new(1) }
    }
}

impl<T> Registry<T> {
    /// reserve the token before the entry exists: the host upcalls that hand a registration to
    /// Kotlin need the id, and must be able to refuse without leaving anything behind
    pub fn alloc(&self) -> Token {
        let token = self.next_token.get();
        self.next_token.set(token.wrapping_add(1));
        token
    }

    /// `Some(previous)` when `key` was already taken, and the caller owns releasing it
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

    /// the entry a host upcall named, or `None` once it has been disposed. Tokens are not reused,
    /// so this can never answer with a later registration's entry.
    pub fn get(&self, token: Token) -> Option<T>
    where
        T: Clone,
    {
        self.entries.borrow().iter().find(|e| e.token == token).map(|e| e.value.clone())
    }

    /// `false` once the entry is gone, and it never comes back: tokens are not reused, so this is
    /// the liveness answer for a walk that runs plugin code between iterations
    pub fn contains(&self, token: Token) -> bool {
        self.entries.borrow().iter().any(|e| e.token == token)
    }

    pub fn remove_matching(&self, predicate: impl Fn(&T) -> bool) -> Vec<T> {
        let mut entries = self.entries.borrow_mut();
        let mut removed = Vec::new();
        let mut index = 0;
        while index < entries.len() {
            if predicate(&entries[index].value) {
                removed.push(entries.remove(index).value);
            } else {
                index += 1;
            }
        }
        removed
    }

    /// the entry list one dispatch walks, in registration order. cloned up front for the same
    /// reason [`CallbackRegistry::snapshot`] restores up front: the walk must not observe its own
    /// registrations or disposals.
    pub fn values(&self) -> Vec<T>
    where
        T: Clone,
    {
        self.entries.borrow().iter().map(|e| e.value.clone()).collect()
    }

    /// empties the registry and hands every entry over, for a teardown that has to undo each one
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

/// callbacks are held as GC roots, so every path that drops one has to restore it (`Persistent`
/// has no `Drop`, and an unreleased root aborts `JS_FreeRuntime`)
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

    /// the handler list one dispatch walks. restored up front rather than kept as roots: a
    /// `Function<'js>` releases itself on drop, so bailing out mid-walk cannot strand one.
    pub fn snapshot<'js>(&self, ctx: &Ctx<'js>) -> Vec<Function<'js>> {
        let persistents: Vec<_> = self.entries.borrow().iter().map(|e| e.value.clone()).collect();
        persistents.into_iter().filter_map(|p| p.restore(ctx).ok()).collect()
    }

    /// `false` when `token` is already gone, which is what makes a disposer called twice (or after
    /// the registration was replaced) a no-op instead of a second host upcall
    pub fn dispose(&self, ctx: &Ctx<'_>, token: Token) -> bool {
        match self.remove(token) {
            Some(persistent) => {
                let _ = persistent.restore(ctx);
                true
            }
            None => false,
        }
    }

    /// empties the registry and hands the callbacks over, for the one dispatch that consumes them
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

/// engine-wide teardown latch, shared by every install so that "registering after unload has begun
/// is a no-op" means the same thing across `rpc`/`api`/`ui`, which own separate states.
///
/// It also carries the one other piece of state more than one install needs: how many `interceptRpc`
/// dispatches this engine is holding, i.e. how many of the app's own requests are parked behind it.
/// `rpc` is where that is known and [`crate::api::timers`] is where it matters, and neither owns the
/// other.
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

    /// how many dispatches the engine holds *right now* - set from the size of `rpc`'s dispatch
    /// table after every mutation of it, rather than counted up and down, so a path that forgets to
    /// call this is corrected by the next one that does instead of leaking a hold forever.
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

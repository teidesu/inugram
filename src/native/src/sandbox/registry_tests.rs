use super::*;

fn values<T: Copy>(registry: &Registry<T>) -> Vec<T> {
  registry.entries.borrow().iter().map(|e| e.value).collect()
}

#[test]
fn unkeyed_registrations_stack_in_order() {
  let registry = Registry::<i32>::default();
  for value in [10, 20, 30] {
    let token = registry.alloc();
    assert!(registry.insert(token, None, value).is_none());
  }
  assert_eq!(values(&registry), vec![10, 20, 30]);
}

#[test]
fn keyed_registrations_replace_in_place_and_retire_the_old_token() {
  let registry = Registry::<i32>::default();
  let first = registry.alloc();
  registry.insert(first, Some("menu".to_string()), 10);
  let other = registry.alloc();
  registry.insert(other, Some("other".to_string()), 20);

  let second = registry.alloc();
  assert_eq!(registry.insert(second, Some("menu".to_string()), 11), Some(10));
  assert_eq!(values(&registry), vec![11, 20], "a replacement keeps its predecessor's position");

  assert!(registry.remove(first).is_none(), "the replaced registration's disposer is inert");
  assert_eq!(registry.remove(second), Some(11));
}

#[test]
fn removing_a_token_twice_is_a_no_op() {
  let registry = Registry::<i32>::default();
  let token = registry.alloc();
  registry.insert(token, None, 7);
  assert_eq!(registry.remove(token), Some(7));
  assert_eq!(registry.remove(token), None);
}

#[test]
fn contains_answers_liveness_for_every_way_an_entry_leaves() {
  let registry = Registry::<i32>::default();
  let token = registry.alloc();
  assert!(!registry.contains(token), "an allocated token is not a registration yet");
  registry.insert(token, None, 7);
  assert!(registry.contains(token));
  registry.remove(token);
  assert!(!registry.contains(token));

  let token = registry.alloc();
  registry.insert(token, None, 8);
  registry.remove_matching(|_| true);
  assert!(!registry.contains(token));

  let keyed = registry.alloc();
  registry.insert(keyed, Some("k".to_string()), 9);
  let replacing = registry.alloc();
  registry.insert(replacing, Some("k".to_string()), 10);
  assert!(!registry.contains(keyed), "a replaced registration is no longer live");
  assert!(registry.contains(replacing));
}

/// a dispatch in flight for a disposed registration must never be answered by a later one
#[test]
fn tokens_are_never_reused() {
  let registry = Registry::<i32>::default();
  let first = registry.alloc();
  registry.insert(first, None, 1);
  registry.remove(first);
  let second = registry.alloc();
  assert_ne!(first, second);
}

#[test]
fn unloading_latches() {
  let lifecycle = Lifecycle::new();
  assert!(!lifecycle.is_unloading());
  lifecycle.begin_unload();
  lifecycle.begin_unload();
  assert!(lifecycle.is_unloading());
}

#[test]
fn the_dispatch_hold_is_a_count_and_not_a_latch() {
  let lifecycle = Lifecycle::new();
  assert!(!lifecycle.has_blocking_dispatches());
  lifecycle.set_blocking_dispatches(2);
  assert!(lifecycle.has_blocking_dispatches());
  lifecycle.set_blocking_dispatches(1);
  assert!(lifecycle.has_blocking_dispatches(), "a nested stage settling does not release it");
  lifecycle.set_blocking_dispatches(0);
  assert!(!lifecycle.has_blocking_dispatches());
}

/// a plugin can hold a disposer with `using` or a `DisposableStack` instead of by hand, which is
/// only true while it carries `Symbol.dispose`
#[test]
fn a_disposer_is_also_a_disposable() {
  let rt = rquickjs::Runtime::new().unwrap();
  let ctx = rquickjs::Context::full(&rt).unwrap();
  let calls = Rc::new(Cell::new(0));
  let seen = calls.clone();
  ctx.with(|ctx| {
    let disposer = make_disposer(&ctx, move |_| seen.set(seen.get() + 1)).unwrap();
    ctx.globals().set("d", disposer).unwrap();
    assert_eq!(
      ctx.eval::<String, _>("typeof d[Symbol.dispose]").unwrap(),
      "function",
      "a disposer must be usable as a Disposable",
    );
    ctx.eval::<(), _>("{ using held = d; }").unwrap();
    assert_eq!(calls.get(), 1, "leaving the block disposes it");
    ctx.eval::<(), _>("const s = new DisposableStack(); s.use(d); s.dispose();").unwrap();
    assert_eq!(calls.get(), 2, "a stack disposes what it was given");
  });
}

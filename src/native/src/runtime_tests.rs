use super::*;
use crate::api::tl::proxy;
use rquickjs::Context;
use std::cell::Cell;
use std::rc::Rc;

const QUICK_JS_KT: &str = include_str!("../../fork/helpers/plugins/QuickJs.kt");

fn setup() -> (Runtime, Context) {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  ctx.with(|ctx| crate::api::error::install_plugin_error(&ctx).unwrap());
  (rt, ctx)
}

fn outcome_of(rt: &Runtime, ctx: &Context) -> String {
  pump_jobs(rt, ctx, &|_| {});
  ctx.with(|ctx| ctx.eval::<String, _>("String(globalThis.out)").unwrap())
}

fn watch<'js>(ctx: &Ctx<'js>, promise: rquickjs::Promise<'js>) {
  ctx.globals().set("p", promise).unwrap();
  ctx
    .eval::<(), _>("globalThis.out = 'pending'; p.then(v => { out = `ok:${v}` }, e => { out = `err:${e.code ?? e.message}` })")
    .unwrap();
}

#[derive(Default)]
struct Probe {
  rejected: Rc<Cell<u32>>,
  released: Rc<Cell<u32>>,
}

impl Parked for Probe {
  fn reject(self, _ctx: &Ctx<'_>) {
    self.rejected.set(self.rejected.get() + 1);
  }

  fn release(self, _ctx: &Ctx<'_>) {
    self.released.set(self.released.get() + 1);
  }
}

#[test]
fn the_settle_apis_are_the_ones_the_host_names() {
  let declared = |name: &str| -> i32 {
    let marker = format!("const val {name} = ");
    let at = QUICK_JS_KT.find(&marker).unwrap_or_else(|| panic!("QuickJs.kt declares no {name}")) + marker.len();
    QUICK_JS_KT[at..].lines().next().unwrap().trim().parse().unwrap()
  };
  assert_eq!(declared("SETTLE_FETCH"), SETTLE_FETCH);
  assert_eq!(declared("SETTLE_CANVAS"), SETTLE_CANVAS);
  assert_eq!(declared("SETTLE_MODAL"), SETTLE_MODAL);
  assert_eq!(declared("SETTLE_FILES"), SETTLE_FILES);
  assert_eq!(declared("SETTLE_READS"), SETTLE_READS);
  assert_eq!(declared("SETTLE_WRITES"), SETTLE_WRITES);
  assert_eq!(declared("SETTLE_INVOKE"), SETTLE_INVOKE);
}

#[test]
fn a_refusal_rejects_the_same_promise_and_leaves_nothing_parked() {
  let (rt, ctx) = setup();
  let table = PendingTable::<Probe>::default();
  let probe = Probe::default();
  let rejected = probe.rejected.clone();
  ctx.with(|ctx| {
    let promise = table.park(&ctx, probe, |_| Some("Pforbidden\n\n\n\nno".to_string())).unwrap();
    watch(&ctx, promise);
  });
  assert_eq!(outcome_of(&rt, &ctx), "err:forbidden");
  assert!(table.is_empty());
  assert_eq!(rejected.get(), 1);
}

#[test]
fn an_answer_settles_once_and_a_second_one_is_dropped() {
  let (rt, ctx) = setup();
  let table = PendingTable::<()>::default();
  let mut id = 0;
  ctx.with(|ctx| {
    let promise = table
      .park(&ctx, (), |asked| {
        id = asked;
        None
      })
      .unwrap();
    watch(&ctx, promise);
    table.settle(&ctx, id, "S1", false, |ctx, _, wire| proxy::plain_wire_to_js(ctx, wire)).unwrap();
    table.settle(&ctx, id, "S2", false, |ctx, _, wire| proxy::plain_wire_to_js(ctx, wire)).unwrap();
  });
  assert_eq!(outcome_of(&rt, &ctx), "ok:1");
  assert!(table.is_empty());
}

#[test]
fn an_error_wire_or_an_unreadable_answer_rejects_rather_than_hanging() {
  let (rt, ctx) = setup();
  let table = PendingTable::<Probe>::default();
  let probe = Probe::default();
  let rejected = probe.rejected.clone();
  let mut id = 0;
  ctx.with(|ctx| {
    let promise = table
      .park(&ctx, probe, |asked| {
        id = asked;
        None
      })
      .unwrap();
    watch(&ctx, promise);
    table.settle(&ctx, id, "Pnot-found\n\n\n\ngone", false, |_, _, _| unreachable!()).unwrap();
  });
  assert_eq!(outcome_of(&rt, &ctx), "err:not-found");
  assert_eq!(rejected.get(), 1);

  ctx.with(|ctx| {
    let promise = table
      .park(&ctx, Probe::default(), |asked| {
        id = asked;
        None
      })
      .unwrap();
    watch(&ctx, promise);
    table.settle(&ctx, id, "Q?", false, |ctx, _, wire| proxy::plain_wire_to_js(ctx, wire)).unwrap();
  });
  assert!(outcome_of(&rt, &ctx).starts_with("err:wire:"));
  assert!(table.is_empty());
}

/// an answer in two halves: the first settles the promise and keeps what was parked, the second
/// lets it go without settling anything twice
#[test]
fn a_kept_request_is_released_by_the_answer_after_it() {
  let (rt, ctx) = setup();
  let table = PendingTable::<Probe>::default();
  let probe = Probe::default();
  let (rejected, released) = (probe.rejected.clone(), probe.released.clone());
  let mut id = 0;
  ctx.with(|ctx| {
    let promise = table
      .park(&ctx, probe, |asked| {
        id = asked;
        None
      })
      .unwrap();
    watch(&ctx, promise);
    table.settle(&ctx, id, "N", true, |ctx, _, wire| proxy::plain_wire_to_js(ctx, wire)).unwrap();
  });
  assert_eq!(outcome_of(&rt, &ctx), "ok:null");
  assert_eq!(table.len(), 1);
  ctx.with(|ctx| table.settle(&ctx, id, "N", true, |_, _, _| unreachable!()).unwrap());
  assert_eq!(table.len(), 1, "a repeated first half keeps it too");
  ctx.with(|ctx| table.settle(&ctx, id, "", false, |_, _, _| unreachable!()).unwrap());
  assert!(table.is_empty());
  assert_eq!((rejected.get(), released.get()), (0, 1));
}

#[test]
fn dispose_releases_every_outstanding_request() {
  let (_rt, ctx) = setup();
  let table = PendingTable::<Probe>::default();
  let released = Rc::new(Cell::new(0));
  ctx.with(|ctx| {
    for _ in 0..3 {
      let probe = Probe { released: released.clone(), ..Probe::default() };
      table.park(&ctx, probe, |_| None).unwrap();
    }
    table.dispose(&ctx);
  });
  assert!(table.is_empty());
  assert_eq!(released.get(), 3);
}

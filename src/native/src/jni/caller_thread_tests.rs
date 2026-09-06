use super::serialized::{EntryError, Serialized};
use std::sync::{mpsc, Arc};
use std::thread;
use std::time::Duration;

use rquickjs::{Context, Ctx, Function, Runtime};

struct CallerRuntime {
  context: Arc<Serialized<Context>>,
}

impl CallerRuntime {
  fn new() -> Self {
    let runtime = Runtime::new().unwrap();
    Self {
      context: Serialized::new(Context::full(&runtime).unwrap()),
    }
  }

  fn enter<T>(&self, run: impl FnOnce(Ctx<'_>) -> T) -> Result<T, &'static str> {
    let context = self.context.enter(Some(Duration::ZERO)).map_err(|error| match error {
      EntryError::Closed => "engine closed",
      _ => "engine busy",
    })?;
    Ok(context.with(run))
  }

  fn close(&self) -> Result<(), &'static str> {
    let lease = self.context.enter(Some(Duration::ZERO)).map_err(|_| "engine busy")?;
    drop(lease);
    self.context.close().map(|_| ()).map_err(|_| "engine busy")
  }
}

#[test]
fn closures_and_host_calls_follow_the_caller_thread() {
  let engine = CallerRuntime::new();
  let (send, receive) = mpsc::channel();
  engine
    .enter(|ctx| {
      ctx
        .globals()
        .set(
          "recordThread",
          Function::new(ctx.clone(), move || {
            send.send(thread::current().id()).unwrap();
          })
          .unwrap(),
        )
        .unwrap();
      ctx
        .eval::<(), _>("globalThis.run = (() => { let count = 0; return () => { recordThread(); return ++count } })()")
        .unwrap();
    })
    .unwrap();
  for expected in 1..=8 {
    thread::scope(|scope| {
      let worker = scope.spawn(|| {
        let count = engine
          .enter(|ctx| ctx.globals().get::<_, Function>("run").unwrap().call::<_, i32>(()).unwrap())
          .unwrap();
        assert_eq!(count, expected);
        thread::current().id()
      });
      assert_eq!(receive.recv_timeout(Duration::from_secs(5)).unwrap(), worker.join().unwrap());
    });
  }
}

#[test]
fn recursive_entry_fails_without_relocking_quickjs() {
  let engine = Arc::new(CallerRuntime::new());
  let weak = Arc::downgrade(&engine);
  engine
    .enter(|ctx| {
      ctx
        .globals()
        .set(
          "reenter",
          Function::new(ctx.clone(), move || {
            assert_eq!(weak.upgrade().unwrap().enter(|_| ()), Err("engine busy"));
          })
          .unwrap(),
        )
        .unwrap();
      ctx.eval::<(), _>("reenter()").unwrap();
    })
    .unwrap();
  assert!(engine.enter(|_| ()).is_ok());
}

#[test]
fn nested_host_callbacks_can_reuse_the_active_context() {
  let engine = CallerRuntime::new();
  engine
    .enter(|ctx| {
      ctx
        .globals()
        .set(
          "invokeNested",
          Function::new(ctx.clone(), |ctx: Ctx<'_>| ctx.globals().get::<_, Function>("nested")?.call::<_, i32>(()))
            .unwrap(),
        )
        .unwrap();
      let value: i32 = ctx.eval("globalThis.nested = () => 42; invokeNested()").unwrap();
      assert_eq!(value, 42);
    })
    .unwrap();
}

#[test]
fn contention_and_close_do_not_wait_on_an_active_callback() {
  let engine = CallerRuntime::new();
  let (entered, receive_entered) = mpsc::channel();
  let (release, receive_release) = mpsc::channel();
  thread::scope(|scope| {
    let engine = &engine;
    let worker = scope.spawn(move || {
      engine.enter(|ctx| {
        ctx.eval::<(), _>("globalThis.value = 42").unwrap();
        entered.send(()).unwrap();
        receive_release.recv_timeout(Duration::from_secs(5)).unwrap();
      })
    });
    receive_entered.recv_timeout(Duration::from_secs(5)).unwrap();
    assert_eq!(engine.enter(|_| ()), Err("engine busy"));
    assert_eq!(engine.close(), Err("engine busy"));
    release.send(()).unwrap();
    worker.join().unwrap().unwrap();
  });
  engine.close().unwrap();
  assert_eq!(engine.enter(|_| ()), Err("engine closed"));
}

#[test]
fn promise_jobs_can_be_left_for_the_engine_queue() {
  let engine = CallerRuntime::new();
  thread::scope(|scope| {
    scope
      .spawn(|| {
        engine
          .enter(|ctx| {
            ctx
              .eval::<(), _>("globalThis.finished = false; Promise.resolve().then(() => { finished = true })")
              .unwrap();
            assert!(!ctx.globals().get::<_, bool>("finished").unwrap());
          })
          .unwrap()
      })
      .join()
      .unwrap();
  });
  engine
    .enter(|ctx| {
      assert!(!ctx.globals().get::<_, bool>("finished").unwrap());
      while ctx.execute_pending_job() {}
      assert!(ctx.globals().get::<_, bool>("finished").unwrap());
    })
    .unwrap();
}

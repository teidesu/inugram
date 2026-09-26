use super::serialized::Serialized;
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use rquickjs::{Context, Function, Runtime};

#[test]
fn closures_and_host_calls_follow_the_caller_thread() {
  let runtime = Runtime::new().unwrap();
  let engine = Serialized::new(Context::full(&runtime).unwrap());
  let (send, receive) = mpsc::channel();
  engine.enter(Some(Duration::ZERO)).unwrap().with(|ctx| {
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
  });
  for expected in 1..=8 {
    thread::scope(|scope| {
      let worker = scope.spawn(|| {
        let count = engine
          .enter(Some(Duration::ZERO))
          .unwrap()
          .with(|ctx| ctx.globals().get::<_, Function>("run").unwrap().call::<_, i32>(()).unwrap());
        assert_eq!(count, expected);
        thread::current().id()
      });
      assert_eq!(receive.recv_timeout(Duration::from_secs(5)).unwrap(), worker.join().unwrap());
    });
  }
}

#[test]
fn a_caller_thread_runs_js_while_the_engine_thread_is_parked_in_a_lent_call() {
  let runtime = Runtime::new().unwrap();
  let engine = Serialized::new(Context::full(&runtime).unwrap());
  let lease = engine.enter(None).unwrap();
  let context = &*lease as *const Context;
  let slot = engine.clone();
  let depth = "globalThis.depth = n => n === 0 ? 0 : 1 + depth(n - 1)";
  crate::runtime::enter_js(&lease, |ctx| {
    ctx.eval::<(), _>(depth).unwrap();
    ctx
      .globals()
      .set(
        "callJava",
        Function::new(ctx.clone(), move || {
          let (answer, _) = unsafe {
            slot.lend(context, || {
              thread::scope(|scope| {
                scope
                  .spawn(|| {
                    let borrowed = slot.enter(Some(Duration::from_secs(5))).unwrap();
                    crate::runtime::enter_js(&borrowed, |ctx| {
                      ctx.eval::<i32, _>("globalThis.fromCaller = depth(50); fromCaller").unwrap()
                    })
                  })
                  .join()
                  .unwrap()
              })
            })
          };
          crate::sandbox::limits::fit_stack_limit(unsafe { &*context });
          answer
        })
        .unwrap(),
      )
      .unwrap();
    let got: Vec<i32> = ctx.eval("[callJava(), fromCaller, depth(50)]").unwrap();
    assert_eq!(got, [50, 50, 50]);
  });
}

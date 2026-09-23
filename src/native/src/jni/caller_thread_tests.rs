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

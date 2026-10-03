use std::cell::RefCell;
use std::rc::Rc;

use rquickjs::{Context, Runtime};

use super::{evaluate_inspected, EvalReply, DROPPED};
use crate::testing::harness::install_capturing_console;

type Replies = Rc<RefCell<Vec<(bool, String)>>>;

fn start(rt: &Runtime, ctx: &Context, code: &str) -> Replies {
  install_capturing_console(ctx);
  let replies: Replies = Rc::default();
  let sink = replies.clone();
  let reply = EvalReply::new(move |ok, text| sink.borrow_mut().push((ok, text.to_string())));
  ctx.with(|ctx| evaluate_inspected(&ctx, code.as_bytes().to_vec(), reply));
  while rt.is_job_pending() {
    rt.execute_pending_job().ok();
  }
  replies
}

fn evaluate(code: &str) -> (bool, String) {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let replies = start(&rt, &ctx, code);
  let replies = replies.borrow();
  assert_eq!(replies.len(), 1, "{replies:?}");
  replies[0].clone()
}

#[test]
fn a_plain_value_is_printed_like_console_log_prints_it() {
  assert_eq!(evaluate("({ a: 1, b: 'x' })"), (true, "{ a: 1, b: 'x' }".to_string()));
}

#[test]
fn a_promise_is_awaited() {
  assert_eq!(evaluate("(async () => { await null; return [1, 2] })()"), (true, "[ 1, 2 ]".to_string()));
}

#[test]
fn a_rejection_answers_with_the_error_and_its_stack() {
  let (ok, text) = evaluate("(async () => { throw new Error('boom') })()");
  assert!(!ok);
  assert!(text.starts_with("Error: boom\n"), "{text}");
}

#[test]
fn a_syntax_error_fails_without_running_anything() {
  let (ok, text) = evaluate("globalThis.ran = true; )(");
  assert!(!ok);
  assert!(text.contains("SyntaxError"), "{text}");
}

#[test]
fn top_level_bindings_of_an_earlier_script_are_visible() {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  ctx.with(|ctx| ctx.eval::<(), _>("let counter = 41; const bump = () => ++counter").unwrap());
  let replies = start(&rt, &ctx, "(async () => bump())()");
  assert_eq!(*replies.borrow(), vec![(true, "42".to_string())]);
}

#[test]
fn a_promise_nothing_can_settle_answers_dropped() {
  assert_eq!(evaluate("new Promise(() => {})"), (false, DROPPED.to_string()));
}

#[test]
fn a_promise_still_pending_when_the_engine_closes_answers_dropped() {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let replies = start(&rt, &ctx, "new Promise(resolve => { globalThis.resolve = resolve })");
  assert!(replies.borrow().is_empty());
  drop(ctx);
  drop(rt);
  assert_eq!(*replies.borrow(), vec![(false, DROPPED.to_string())]);
}

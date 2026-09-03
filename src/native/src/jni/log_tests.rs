use super::*;
use rquickjs::{Context, Runtime};

#[test]
fn console_levels_format_an_error_stack() {
  let runtime = Runtime::new().unwrap();
  let context = Context::full(&runtime).unwrap();
  context.with(|ctx| {
    let error: Value = ctx
      .eval("(() => { const error = new Error('boom'); error.stack = 'trace one\\ntrace two'; return error })()")
      .unwrap();
    for level in 0..=4 {
      assert_eq!(format_console_value(&ctx, &error), "Error: boom\ntrace one\ntrace two", "level {level}");
    }
  });
}

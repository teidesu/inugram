use super::*;

/// `Array::len()` panics on invalid input. With panic=abort this would kill the test process, so
/// the guard must run first.
#[test]
fn a_length_past_the_limit_is_refused_rather_than_aborting() {
  let (_rt, context) = crate::testing::harness::new_engine();
  context.with(|ctx| {
    crate::api::error::install_plugin_error(&ctx).unwrap();
    for length in [4294967295, ARRAY_LIMIT as u64 + 1] {
      let array: Array = ctx.eval(format!("(() => {{ const a = []; a.length = {length}; return a }})()")).unwrap();
      assert!(matches!(array_values(&ctx, &array, "test"), Err(rquickjs::Error::Exception)));
      let message = ctx.catch().as_object().and_then(|o| o.get::<_, String>("message").ok()).unwrap_or_default();
      assert!(message.contains("at most"), "{length}: {message}");
    }
  });
}

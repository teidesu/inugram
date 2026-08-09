use super::*;
use rquickjs::{Context, Runtime};

fn with_ctx(body: impl FnOnce(Ctx<'_>)) {
  let rt = Runtime::new().unwrap();
  let context = Context::full(&rt).unwrap();
  context.with(|ctx| {
    crate::api::error::install_plugin_error(&ctx, &crate::testing::harness::inu_namespace(&ctx)).unwrap();
    body(ctx);
  });
}

fn refusal(ctx: &Ctx<'_>, err: rquickjs::Error) -> String {
  assert!(matches!(err, rquickjs::Error::Exception));
  ctx.catch().as_object().and_then(|o| o.get::<_, String>("message").ok()).unwrap_or_default()
}

/// `Array::len()` asserts rather than answers, and the crate aborts on panic, so this is a
/// process kill before the guard rather than a failed assertion
#[test]
fn an_absurd_length_is_refused_rather_than_aborting() {
  with_ctx(|ctx| {
    let array: Array = ctx.eval("(() => { const a = []; a.length = 4294967295; return a })()").unwrap();
    let err = array_values(&ctx, &array, "test").unwrap_err();
    assert!(refusal(&ctx, err).contains("at most"));
  });
}

#[test]
fn a_length_one_past_the_limit_is_refused() {
  with_ctx(|ctx| {
    let src = format!("(() => {{ const a = []; a.length = {}; return a }})()", ARRAY_LIMIT + 1);
    let array: Array = ctx.eval(src).unwrap();
    assert!(array_values(&ctx, &array, "test").is_err());
  });
}

#[test]
fn an_ordinary_array_reads_through() {
  with_ctx(|ctx| {
    let array: Array = ctx.eval("[1, 2, 3]").unwrap();
    assert_eq!(array_values(&ctx, &array, "test").unwrap().len(), 3);
  });
}

#[test]
fn an_explicit_undefined_reads_as_absent_and_null_does_not() {
  with_ctx(|ctx| {
    assert!(opt(Opt(Some(Value::new_undefined(ctx.clone())))).is_none());
    assert!(opt(Opt(None::<Value>)).is_none());
    assert!(opt(Opt(Some(Value::new_null(ctx.clone())))).is_some());
  });
}

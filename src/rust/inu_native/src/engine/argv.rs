//! Reading the arguments a plugin handed a native binding. Two hazards, both reachable from one
//! line of ordinary-looking JS.
//!
//! `rquickjs`'s [`Opt`] is *arity*, not webidl: an argument passed explicitly as `undefined`
//! arrives as `Some`, so a `Coerced<f64>` default is silently replaced by `NaN` and a
//! `Coerced<String>` one by the literal text `"undefined"`. Hence [`opt`].
//!
//! `Array::len()` is an `assert!` that the length fits a machine int and this crate builds with
//! `panic = "abort"`, so `a.length = 2 ** 32 - 1` is a process kill rather than a refusal - and
//! just below that the length is a real int `Vec::with_capacity` cannot serve. Hence
//! [`array_values`], which reads the length as a plain property and refuses past [`ARRAY_LIMIT`].

use rquickjs::function::Opt;
use rquickjs::{Array, Ctx, Result as JsResult, Value};

/// the most elements any argument list, item list or rule set a plugin passes may hold. Stated in
/// `common.d.ts`; the number is structural rather than per-api, every real use being orders of
/// magnitude below it.
pub const ARRAY_LIMIT: usize = 65536;

/// webidl's reading of an optional argument: omitted and explicitly `undefined` are the same thing
pub fn opt<'js>(value: Opt<Value<'js>>) -> Option<Value<'js>> {
    value.0.filter(|v| !v.is_undefined())
}

/// the elements of a plugin-supplied array, refusing a length that cannot be served
pub fn array_values<'js>(ctx: &Ctx<'js>, array: &Array<'js>, what: &str) -> JsResult<Vec<Value<'js>>> {
    let len = array_len(ctx, array, what)?;
    let mut out = Vec::with_capacity(len);
    for index in 0..len {
        out.push(array.get::<Value>(index)?);
    }
    Ok(out)
}

pub fn array_len(ctx: &Ctx<'_>, array: &Array<'_>, what: &str) -> JsResult<usize> {
    // read as a property rather than through `Array::len`, which asserts rather than answers
    let len: f64 = array.as_object().get("length")?;
    if !(0.0..=ARRAY_LIMIT as f64).contains(&len) {
        crate::engine::error::throw_plugin_error(
            ctx,
            "invalid-argument",
            &format!("{what}: at most {ARRAY_LIMIT} elements"),
            None,
            None,
            None,
        )?;
    }
    Ok(len as usize)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rquickjs::{Context, Runtime};

    fn with_ctx(body: impl FnOnce(Ctx<'_>)) {
        let rt = Runtime::new().unwrap();
        let context = Context::full(&rt).unwrap();
        context.with(|ctx| {
            crate::engine::error::install_plugin_error(&ctx).unwrap();
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

    #[test]
    fn the_limit_is_the_one_the_contract_states() {
        assert_eq!(
            crate::testing::util::stated_number(crate::testing::util::CONTRACT, "at most {} elements"),
            ARRAY_LIMIT as u64,
        );
    }
}

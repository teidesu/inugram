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
use rquickjs::{Array, Ctx, Exception, Function, Object, Result as JsResult, Value};

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

/// Reading one property off a plugin-supplied options object. Every refusal names the object and
/// the key, because a `TypeError` from inside a factory is otherwise indistinguishable between the
/// several objects one call passes.
///
/// `req_*` refuses what it cannot read; `opt_*` treats `undefined` and `null` alike, for the same
/// reason [`opt`] does.
pub fn field<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Value<'js>> {
    obj.get(key).map_err(|_| Exception::throw_type(ctx, &format!("{what}: cannot read '{key}'")))
}

pub fn req_str<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<String> {
    let v = field(ctx, obj, what, key)?;
    match v.as_string() {
        Some(s) => Ok(s.to_string()?),
        None => Err(Exception::throw_type(ctx, &format!("{what}: '{key}' must be a string"))),
    }
}

pub fn opt_str<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Option<String>> {
    let v = field(ctx, obj, what, key)?;
    if v.is_undefined() || v.is_null() {
        return Ok(None);
    }
    match v.as_string() {
        Some(s) => Ok(Some(s.to_string()?)),
        None => Err(Exception::throw_type(ctx, &format!("{what}: '{key}' must be a string"))),
    }
}

pub fn req_bool<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<bool> {
    let v = field(ctx, obj, what, key)?;
    v.as_bool().ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a boolean")))
}

pub fn opt_bool<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<bool> {
    let v = field(ctx, obj, what, key)?;
    if v.is_undefined() || v.is_null() {
        return Ok(false);
    }
    v.as_bool().ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a boolean")))
}

pub fn req_num<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<f64> {
    let v = field(ctx, obj, what, key)?;
    if let Some(i) = v.as_int() {
        return Ok(i as f64);
    }
    v.as_float().ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a number")))
}

pub fn opt_num<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Option<f64>> {
    let v = field(ctx, obj, what, key)?;
    if v.is_undefined() || v.is_null() {
        return Ok(None);
    }
    if let Some(i) = v.as_int() {
        return Ok(Some(i as f64));
    }
    v.as_float().map(Some).ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a number")))
}

pub fn req_fn<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Function<'js>> {
    let v = field(ctx, obj, what, key)?;
    v.into_function().ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a function")))
}

pub fn opt_fn<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Option<Function<'js>>> {
    let v = field(ctx, obj, what, key)?;
    if v.is_undefined() || v.is_null() {
        return Ok(None);
    }
    v.into_function()
        .map(Some)
        .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a function")))
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

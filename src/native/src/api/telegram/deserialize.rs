//! `inu.interceptDeserialize`, declarative tier: a rule is validated here, handed to the host once
//! at registration, and evaluated by the host alone. No plugin code runs per object - that is the
//! entire point of the tier, deserialization happening on the app's network and storage threads
//! where an engine may not be entered at all. The middleware overload is the same hook one blocking
//! queue hop further on, which is why the two are different apis rather than sugar for each other.
//!
//! What crosses is a normalized JSON array built *here*, from the values this module read and
//! validated - never a re-stringification of the plugin's own object, which may carry getters that
//! would say something else on a second read.

use std::rc::Rc;

use rquickjs::function::Opt;
use rquickjs::{Ctx, Function, Object, Result as JsResult, Value};

use crate::api::error;
use crate::api::telegram::rpc::format_exception;
use crate::api::telegram::writes::json_string;
use crate::api::tl::proxy::{self, TlViews, ViewLife};
use crate::sandbox::grants::{check_grant, GrantHost, MATCH_EXACT};
use crate::sandbox::registry::{make_disposer, noop_disposer, CallbackRegistry, Lifecycle, Registry};

/// how many rules one plugin may hold live at once, across every registration. Stated in
/// `common.d.ts`, and bounded here rather than host-side because this is the layer that can refuse
/// before anything crosses: every rule is one more per-object test on the app's cold-start path.
pub const MAX_RULES: usize = 32;

const GRANT: &str = "interceptDeserialize";

/// stand-in for the Kotlin `QuickJs.DeserializeListener`; `None` == ok, `Some(msg)` == error.
/// Never an `E` wire, for the reason `QuickJs.RpcListener` gives.
pub trait DeserializeHost {
    /// the whole registration as one normalized JSON array. The host owns compiling it against the
    /// real TL classes - which constructors and fields exist, which of them it refuses - so this
    /// answers with the error a plugin sees.
    fn on_rules_register(&self, callback_id: u32, rules_json: &str) -> Option<String>;
    /// the disposer ran: drop [`DeserializeHost::on_rules_register`]'s rules. Never fired twice for
    /// one registration, and never for one the host refused.
    fn on_rules_unregister(&self, callback_id: u32);
    /// the middleware form: the host owns turning constructor names into the ids it matches on, and
    /// refuses the same names a rule may not target. `None` == ok.
    fn on_middleware_register(&self, callback_id: u32, types_json: &str) -> Option<String>;
    fn on_middleware_unregister(&self, callback_id: u32);
}

pub struct DeserializeState {
    host: Rc<dyn DeserializeHost>,
    grants: Rc<dyn GrantHost>,
    lifecycle: Rc<Lifecycle>,
    /// one entry per live registration of *either* tier, holding how many rules it carries - which
    /// is what [`MAX_RULES`] counts, and the only reason this registry holds a value at all. One
    /// token space for both, so a middleware and a rule set can never answer to the same id.
    registrations: Registry<usize>,
    /// the middleware functions, keyed by the token their entry in `registrations` holds
    middlewares: CallbackRegistry,
    tl: Rc<TlViews>,
    log: std::sync::Arc<dyn Fn(&str) + Send + Sync>,
}

enum Const {
    Str(String),
    Num(f64),
    Bool(bool),
    Null,
}

impl Const {
    fn to_json(&self) -> String {
        match self {
            Const::Str(s) => json_string(s),
            // f64 Display is the shortest round-tripping form, so an integral value stays integral
            // and the host reads it back as an int rather than as 1.0
            Const::Num(n) => n.to_string(),
            Const::Bool(b) => b.to_string(),
            Const::Null => "null".to_string(),
        }
    }
}

struct Rule {
    types: Vec<String>,
    when: Vec<(String, Const)>,
    set: Vec<(String, Const)>,
}

fn refuse<T>(ctx: &Ctx<'_>, message: &str) -> JsResult<T> {
    error::throw_plugin_error(ctx, "invalid-argument", &format!("{GRANT}: {message}"), None, None, None)
}

fn read_const<'js>(ctx: &Ctx<'js>, at: &str, value: Value<'js>) -> JsResult<Const> {
    if value.is_null() {
        return Ok(Const::Null);
    }
    if let Some(b) = value.as_bool() {
        return Ok(Const::Bool(b));
    }
    if let Some(i) = value.as_int() {
        return Ok(Const::Num(i as f64));
    }
    if let Some(f) = value.as_float() {
        if !f.is_finite() {
            return refuse(ctx, &format!("{at} is not a finite number"));
        }
        return Ok(Const::Num(f));
    }
    if let Some(s) = value.as_string() {
        return Ok(Const::Str(s.to_string()?));
    }
    refuse(ctx, &format!("{at} must be a string, number, boolean or null - a rule compares against constants only"))
}

/// an object literal and nothing else: an array and a function are both objects to quickjs, and
/// neither is a rule or a field map
fn as_plain_object<'a, 'js>(value: &'a Value<'js>) -> Option<&'a Object<'js>> {
    if value.is_array() || value.is_function() {
        return None;
    }
    value.as_object()
}

fn read_terms<'js>(ctx: &Ctx<'js>, at: &str, value: Value<'js>) -> JsResult<Vec<(String, Const)>> {
    let Some(object) = as_plain_object(&value) else {
        return refuse(ctx, &format!("{at} must be an object of field names to constants"));
    };
    let mut out = Vec::new();
    for key in object.keys::<String>() {
        let key = key?;
        let term = read_const(ctx, &format!("{at}.{key}"), object.get(key.as_str())?)?;
        out.push((key, term));
    }
    Ok(out)
}

fn read_types<'js>(ctx: &Ctx<'js>, at: &str, value: Value<'js>) -> JsResult<Vec<String>> {
    if let Some(s) = value.as_string() {
        return Ok(vec![s.to_string()?]);
    }
    let Some(array) = value.as_array() else {
        return refuse(ctx, &format!("{at}.type must be a constructor name or an array of them"));
    };
    let mut out = Vec::new();
    for item in crate::utils::arguments::array_values(ctx, array, &format!("{at}.type"))? {
        let Some(name) = item.as_string() else {
            return refuse(ctx, &format!("{at}.type must contain only constructor names"));
        };
        out.push(name.to_string()?);
    }
    if out.is_empty() {
        return refuse(ctx, &format!("{at}.type names no constructor"));
    }
    Ok(out)
}

fn read_rule<'js>(ctx: &Ctx<'js>, index: usize, value: Value<'js>) -> JsResult<Rule> {
    let at = format!("rules[{index}]");
    let Some(object) = as_plain_object(&value) else {
        return refuse(ctx, &format!("{at} is not a rule object"));
    };
    // an unknown key is refused rather than ignored: `where` for `when` would otherwise be a rule
    // that rewrites every object of its type instead of the ones the plugin meant
    for key in object.keys::<String>() {
        let key = key?;
        if key != "type" && key != "when" && key != "set" {
            return refuse(ctx, &format!("{at} carries an unknown key '{key}'; a rule is type/when/set"));
        }
    }
    let types = read_types(ctx, &at, object.get("type")?)?;
    let when = match object.get::<_, Value>("when")? {
        value if value.is_undefined() || value.is_null() => Vec::new(),
        value => read_terms(ctx, &format!("{at}.when"), value)?,
    };
    let set = read_terms(ctx, &format!("{at}.set"), object.get("set")?)?;
    if set.is_empty() {
        return refuse(ctx, &format!("{at}.set rewrites nothing"));
    }
    Ok(Rule { types, when, set })
}

fn read_rules<'js>(ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<Vec<Rule>> {
    let Some(array) = value.as_array() else {
        return refuse(ctx, "the declarative form takes an array of rules");
    };
    let rules = crate::utils::arguments::array_values(ctx, array, "interceptDeserialize")?;
    if rules.is_empty() {
        return refuse(ctx, "a rule set with no rules in it");
    }
    let mut out = Vec::with_capacity(rules.len());
    for (index, item) in rules.into_iter().enumerate() {
        out.push(read_rule(ctx, index, item)?);
    }
    Ok(out)
}

fn encode_terms(terms: &[(String, Const)]) -> String {
    let body: Vec<String> =
        terms.iter().map(|(name, value)| format!("{}:{}", json_string(name), value.to_json())).collect();
    format!("{{{}}}", body.join(","))
}

fn encode_rules(rules: &[Rule]) -> String {
    let body: Vec<String> = rules
        .iter()
        .map(|rule| {
            let types: Vec<String> = rule.types.iter().map(|t| json_string(t)).collect();
            format!(
                "{{\"type\":[{}],\"when\":{},\"set\":{}}}",
                types.join(","),
                encode_terms(&rule.when),
                encode_terms(&rule.set),
            )
        })
        .collect();
    format!("[{}]", body.join(","))
}

fn live_rules(state: &DeserializeState) -> usize {
    state.registrations.values().iter().sum()
}

fn js_intercept_deserialize<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<DeserializeState>,
    rules: Value<'js>,
    middleware: Option<Value<'js>>,
) -> JsResult<Function<'js>> {
    if state.lifecycle.is_unloading() {
        return noop_disposer(ctx);
    }
    if let Some(middleware) = middleware.filter(|value| !value.is_undefined()) {
        return register_middleware(ctx, state, rules, middleware);
    }
    let parsed = read_rules(ctx, rules)?;
    for rule in &parsed {
        for name in &rule.types {
            check_grant(ctx, &state.grants, GRANT, Some(name), MATCH_EXACT)?;
        }
    }
    let wanted = live_rules(state) + parsed.len();
    if wanted > MAX_RULES {
        return error::throw_plugin_error(
            ctx,
            "quota-exceeded",
            &format!("{GRANT}: at most {MAX_RULES} rules may be live at once"),
            None,
            Some(wanted as i64),
            Some(MAX_RULES as i64),
        );
    }

    let json = encode_rules(&parsed);
    let callback_id = state.registrations.alloc();
    if let Some(err) = state.host.on_rules_register(callback_id, &json) {
        return Err(ctx.throw(error::host_error_to_js(ctx, &err)?));
    }
    state.registrations.insert(callback_id, None, parsed.len());

    let state = state.clone();
    make_disposer(ctx, move |_ctx| {
        if state.registrations.remove(callback_id).is_some() {
            state.host.on_rules_unregister(callback_id);
        }
    })
}

/// the middleware form. Its first argument is a plain list of constructor names, so it shares the
/// grant check and the [`MAX_RULES`] budget with the rule form (one named constructor is one rule:
/// each is one more entry in the table the app probes per parsed object) and nothing else.
fn register_middleware<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<DeserializeState>,
    objects: Value<'js>,
    middleware: Value<'js>,
) -> JsResult<Function<'js>> {
    let Some(middleware) = middleware.as_function().cloned() else {
        return refuse(ctx, "the middleware form takes a function");
    };
    let Some(array) = objects.as_array() else {
        return refuse(ctx, "the middleware form takes an array of constructor names");
    };
    let mut types = Vec::new();
    for item in crate::utils::arguments::array_values(ctx, array, "interceptDeserialize")? {
        let Some(name) = item.as_string() else {
            return refuse(ctx, "the middleware form's first argument must contain only constructor names");
        };
        types.push(name.to_string()?);
    }
    if types.is_empty() {
        return refuse(ctx, "the middleware form names no constructor");
    }
    for name in &types {
        check_grant(ctx, &state.grants, GRANT, Some(name), MATCH_EXACT)?;
    }
    let wanted = live_rules(state) + types.len();
    if wanted > MAX_RULES {
        return error::throw_plugin_error(
            ctx,
            "quota-exceeded",
            &format!("{GRANT}: at most {MAX_RULES} rules may be live at once"),
            None,
            Some(wanted as i64),
            Some(MAX_RULES as i64),
        );
    }

    let json: Vec<String> = types.iter().map(|t| json_string(t)).collect();
    let callback_id = state.registrations.alloc();
    if let Some(err) = state.host.on_middleware_register(callback_id, &format!("[{}]", json.join(","))) {
        return Err(ctx.throw(error::host_error_to_js(ctx, &err)?));
    }
    state.registrations.insert(callback_id, None, types.len());
    state.middlewares.register(ctx, callback_id, None, middleware);

    let state = state.clone();
    make_disposer(ctx, move |ctx| {
        if state.registrations.remove(callback_id).is_some() {
            state.middlewares.dispose(ctx, callback_id);
            state.host.on_middleware_unregister(callback_id);
        }
    })
}

/// runs one middleware over one object the app just parsed. The host is blocked on this returning,
/// so nothing here may park: the view is dispatch-scoped and released by the host the moment this
/// answers, and a middleware that returns a promise is not waited on.
pub fn dispatch_middleware(
    context: &rquickjs::Context,
    state: &Rc<DeserializeState>,
    callback_id: u32,
    object_wire: &str,
) {
    context.with(|ctx| {
        let Some(f) = state.middlewares.restore(&ctx, callback_id) else {
            return;
        };
        let value = match proxy::wire_to_js_value(&ctx, &state.tl, object_wire, ViewLife::Dispatch) {
            Ok(v) => v,
            Err(e) => {
                let msg = match e {
                    rquickjs::Error::Exception => format_exception(&ctx),
                    other => other.to_string(),
                };
                (state.log)(&format!("interceptDeserialize: bad object wire: {msg}"));
                return;
            }
        };
        match f.call::<_, Value>((value,)) {
            Ok(_) => {}
            Err(rquickjs::Error::Exception) => {
                (state.log)(&crate::fault(format_args!(
                    "interceptDeserialize middleware threw: {}",
                    format_exception(&ctx)
                )));
            }
            Err(e) => (state.log)(&format!("interceptDeserialize middleware failed: {e:?}")),
        }
    });
}

pub fn dispose(context: &rquickjs::Context, state: &Rc<DeserializeState>) {
    context.with(|ctx| state.middlewares.release_all(&ctx));
}

pub fn install_deserialize<'js>(
    ctx: &Ctx<'js>,
    host: Rc<dyn DeserializeHost>,
    grants: Rc<dyn GrantHost>,
    lifecycle: Rc<Lifecycle>,
    tl: Rc<TlViews>,
    log: std::sync::Arc<dyn Fn(&str) + Send + Sync>,
    inu: &Object<'js>,
) -> JsResult<Rc<DeserializeState>> {
    let state = Rc::new(DeserializeState {
        host,
        grants,
        lifecycle,
        registrations: Registry::default(),
        middlewares: CallbackRegistry::default(),
        tl,
        log,
    });

    let state2 = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, rules: Value<'js>, middleware: Opt<Value<'js>>| {
        js_intercept_deserialize(&ctx, &state2, rules, middleware.0)
    })?;
    inu.set("interceptDeserialize", f)?;
    Ok(state)
}

#[cfg(test)]
#[path = "deserialize_tests.rs"]
mod tests;

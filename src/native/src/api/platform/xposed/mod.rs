pub(crate) mod elf;
pub(crate) mod lsplant;

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use rquickjs::function::Opt;
use rquickjs::{Array, Context, Ctx, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::api::error::PluginErrorCode;
use crate::api::platform::jvm::{arg_to_wire, handle_id, wire_to_value, JvmState};
use crate::sandbox::grants::{check_grant, GrantHost, MATCH_NAMESPACE};
use crate::sandbox::registry::{make_disposer, noop_disposer, Lifecycle, Registry};
use crate::utils::arguments::array_values;
use rquickjs::function::This;

use crate::api::telegram::rpc::{format_exception, pump_jobs};

pub trait XposedHost {
  fn xposed(&self, op: i32, target: i64, name: &str, args: &[String]) -> String;
}

const OP_HOOK: i32 = 0;
const OP_HOOK_ALL: i32 = 1;
const OP_UNHOOK: i32 = 2;
const OP_CALL_ORIGINAL: i32 = 3;
const OP_ALLOCATE: i32 = 4;
const OP_DISABLE_PROFILE_SAVER: i32 = 5;

pub const GRANT: &str = "unsafe.xposed";

pub const HOOK_LIMIT: usize = 512;

pub const HOOK_BUDGET_MS: i64 = 250;

#[derive(Clone)]
struct Hook {
  site: i64,
  before: Option<Persistent<Function<'static>>>,
  after: Option<Persistent<Function<'static>>>,
}

pub struct XposedState {
  host: Rc<dyn XposedHost>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  jvm: Rc<JvmState>,
  log: crate::Log,
  hooks: Registry<Hook>,
  sites: RefCell<HashMap<i64, usize>>,
  pending: RefCell<HashMap<i64, PendingDispatch>>,
}

struct PendingDispatch {
  context: Persistent<Object<'static>>,
  after: Vec<Persistent<Function<'static>>>,
}

impl XposedState {
  fn release(&self, ctx: &Ctx<'_>, hook: Hook) {
    if let Some(before) = hook.before {
      let _ = before.restore(ctx);
    }
    if let Some(after) = hook.after {
      let _ = after.restore(ctx);
    }
    let mut sites = self.sites.borrow_mut();
    let Some(count) = sites.get_mut(&hook.site) else {
      return;
    };
    *count -= 1;
    if *count == 0 {
      sites.remove(&hook.site);
      drop(sites);
      self.host.xposed(OP_UNHOOK, hook.site, "", &[]);
    }
  }

  fn snapshot<'js>(&self, ctx: &Ctx<'js>, site: i64) -> Vec<Phase<'js>> {
    self
      .hooks
      .values()
      .into_iter()
      .filter(|hook| hook.site == site)
      .map(|hook| Phase {
        before: hook.before.and_then(|f| f.restore(ctx).ok()),
        after: hook.after.and_then(|f| f.restore(ctx).ok()),
      })
      .collect()
  }
}

struct Phase<'js> {
  before: Option<Function<'js>>,
  after: Option<Function<'js>>,
}

fn ask<'js>(
  ctx: &Ctx<'js>,
  state: &Rc<XposedState>,
  op: i32,
  target: i64,
  name: &str,
  args: &[String],
) -> JsResult<Value<'js>> {
  let wire = state.host.xposed(op, target, name, args);
  wire_to_value(ctx, &state.jvm, &wire)
}

fn require_handle<'js>(ctx: &Ctx<'js>, state: &Rc<XposedState>, value: &Value<'js>, what: &str) -> JsResult<i64> {
  let id = handle_id(ctx, &state.jvm, value)?;
  if id < 0 {
    return PluginErrorCode::InvalidArgument.throw(ctx, &format!("xposed: {what} expected a java class or method"));
  }
  Ok(id)
}

fn sites_from<'js>(ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<Vec<i64>> {
  let listed = value.as_string().and_then(|text| text.to_string().ok()).unwrap_or_default();
  let sites: Option<Vec<i64>> =
    listed.split(',').filter(|part| !part.is_empty()).map(|part| part.parse().ok()).collect();
  match sites {
    Some(sites) if !sites.is_empty() => Ok(sites),
    _ => PluginErrorCode::Internal.throw(ctx, "xposed: the host installed no hook site"),
  }
}

struct Callbacks<'js> {
  before: Option<Function<'js>>,
  after: Option<Function<'js>>,
}

fn callbacks_of<'js>(ctx: &Ctx<'js>, hook: &Object<'js>, what: &str) -> JsResult<Callbacks<'js>> {
  let callback = |phase| -> JsResult<Option<Function<'js>>> {
    let value: Value = hook.get(phase)?;
    if value.is_undefined() {
      return Ok(None);
    }
    let Some(callback) = value.as_function() else {
      return PluginErrorCode::InvalidArgument.throw(ctx, &format!("{what}: {phase} must be a function"));
    };
    Ok(Some(callback.clone()))
  };
  let before = callback("before")?;
  let after = callback("after")?;
  if before.is_none() && after.is_none() {
    return PluginErrorCode::InvalidArgument.throw(ctx, "xposed: a hook needs a before or an after callback");
  }
  Ok(Callbacks { before, after })
}

fn install_hooks<'js>(
  ctx: &Ctx<'js>,
  state: &Rc<XposedState>,
  sites: Vec<i64>,
  callbacks: Callbacks<'js>,
) -> JsResult<Function<'js>> {
  let mut tokens = Vec::with_capacity(sites.len());
  for site in sites {
    let token = state.hooks.alloc();
    *state.sites.borrow_mut().entry(site).or_insert(0) += 1;
    state.hooks.insert(
      token,
      None,
      Hook {
        site,
        before: callbacks.before.clone().map(|f| Persistent::save(ctx, f)),
        after: callbacks.after.clone().map(|f| Persistent::save(ctx, f)),
      },
    );
    tokens.push(token);
  }

  let held = state.hooks.len();
  if held > HOOK_LIMIT {
    for token in &tokens {
      if let Some(hook) = state.hooks.remove(*token) {
        state.release(ctx, hook);
      }
    }
    return PluginErrorCode::QuotaExceeded(held as i64, HOOK_LIMIT as i64)
      .throw(ctx, &format!("xposed: this plugin may hold at most {HOOK_LIMIT} hooks"));
  }

  let state = state.clone();
  make_disposer(ctx, move |ctx| {
    for token in &tokens {
      if let Some(hook) = state.hooks.remove(*token) {
        state.release(ctx, hook);
      }
    }
  })
}

fn js_hook<'js>(
  ctx: &Ctx<'js>,
  state: &Rc<XposedState>,
  op: i32,
  target: Value<'js>,
  name: &str,
  hook: Value<'js>,
  what: &str,
) -> JsResult<Function<'js>> {
  check_grant(ctx, &state.grants, GRANT, None, MATCH_NAMESPACE)?;
  let Some(hook) = hook.as_object() else {
    return PluginErrorCode::InvalidArgument.throw(ctx, &format!("{what}: expected a hook object"));
  };
  let callbacks = callbacks_of(ctx, hook, what)?;
  if state.lifecycle.is_unloading() {
    return noop_disposer(ctx);
  }
  let target = require_handle(ctx, state, &target, "hook")?;
  let answered = ask(ctx, state, op, target, name, &[])?;
  let sites = sites_from(ctx, answered)?;
  install_hooks(ctx, state, sites, callbacks)
}

fn js_call_original<'js>(
  ctx: &Ctx<'js>,
  state: &Rc<XposedState>,
  method: Value<'js>,
  this: Opt<Value<'js>>,
  args: Opt<Value<'js>>,
) -> JsResult<Value<'js>> {
  check_grant(ctx, &state.grants, GRANT, None, MATCH_NAMESPACE)?;
  let method = require_handle(ctx, state, &method, "callOriginalMethod")?;
  let this = this
    .0
    .filter(|value| !value.is_null() && !value.is_undefined())
    .unwrap_or_else(|| Value::new_null(ctx.clone()));
  let args = match args.0.filter(|value| !value.is_null() && !value.is_undefined()) {
    None => Vec::new(),
    Some(args) => {
      let Some(args) = args.as_array() else {
        return PluginErrorCode::InvalidArgument.throw(ctx, "callOriginalMethod: expected an array of arguments");
      };
      array_values(ctx, args, "callOriginalMethod")?
    }
  };
  let mut wires = vec![arg_to_wire(ctx, &state.jvm, &this)?];
  for arg in args {
    wires.push(arg_to_wire(ctx, &state.jvm, &arg)?);
  }
  ask(ctx, state, OP_CALL_ORIGINAL, method, "", &wires)
}

fn js_allocate<'js>(ctx: &Ctx<'js>, state: &Rc<XposedState>, class: Value<'js>) -> JsResult<Value<'js>> {
  check_grant(ctx, &state.grants, GRANT, None, MATCH_NAMESPACE)?;
  let class = require_handle(ctx, state, &class, "allocateInstance")?;
  ask(ctx, state, OP_ALLOCATE, class, "", &[])
}

fn js_disable_profile_saver<'js>(ctx: &Ctx<'js>, state: &Rc<XposedState>) -> JsResult<Value<'js>> {
  check_grant(ctx, &state.grants, GRANT, None, MATCH_NAMESPACE)?;
  ask(ctx, state, OP_DISABLE_PROFILE_SAVER, 0, "", &[])
}

pub fn install_xposed<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn XposedHost>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  jvm: Rc<JvmState>,
  log: crate::Log,
  inu: &Object<'js>,
) -> JsResult<Rc<XposedState>> {
  let state = Rc::new(XposedState {
    host,
    grants,
    lifecycle,
    jvm,
    log,
    hooks: Registry::default(),
    sites: RefCell::new(HashMap::new()),
    pending: RefCell::new(HashMap::new()),
  });

  let xposed = Object::new(ctx.clone())?;
  {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, method: Opt<Value<'js>>, hook: Opt<Value<'js>>| {
      js_hook(
        &ctx,
        &state,
        OP_HOOK,
        method.0.unwrap_or_else(|| Value::new_undefined(ctx.clone())),
        "",
        hook.0.unwrap_or_else(|| Value::new_undefined(ctx.clone())),
        "hookMethod",
      )
    })?;
    xposed.set("hookMethod", f)?;
  }
  {
    let state = state.clone();
    let f = Function::new(
      ctx.clone(),
      move |ctx: Ctx<'js>, class: Opt<Value<'js>>, name: Opt<Value<'js>>, hook: Opt<Value<'js>>| {
        let Some(name) = name
          .0
          .and_then(|name| name.as_string().and_then(|name| name.to_string().ok()))
          .filter(|name| !name.is_empty())
        else {
          return PluginErrorCode::InvalidArgument.throw(&ctx, "hookAllOverloads: expected a method name");
        };
        js_hook(
          &ctx,
          &state,
          OP_HOOK_ALL,
          class.0.unwrap_or_else(|| Value::new_undefined(ctx.clone())),
          &name,
          hook.0.unwrap_or_else(|| Value::new_undefined(ctx.clone())),
          "hookAllOverloads",
        )
      },
    )?;
    xposed.set("hookAllOverloads", f)?;
  }
  {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, class: Opt<Value<'js>>, hook: Opt<Value<'js>>| {
      js_hook(
        &ctx,
        &state,
        OP_HOOK_ALL,
        class.0.unwrap_or_else(|| Value::new_undefined(ctx.clone())),
        "",
        hook.0.unwrap_or_else(|| Value::new_undefined(ctx.clone())),
        "hookAllConstructors",
      )
    })?;
    xposed.set("hookAllConstructors", f)?;
  }
  {
    let state = state.clone();
    let f = Function::new(
      ctx.clone(),
      move |ctx: Ctx<'js>, method: Opt<Value<'js>>, this: Opt<Value<'js>>, args: Opt<Value<'js>>| {
        js_call_original(&ctx, &state, method.0.unwrap_or_else(|| Value::new_undefined(ctx.clone())), this, args)
      },
    )?;
    xposed.set("callOriginalMethod", f)?;
  }
  {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, class: Opt<Value<'js>>| {
      js_allocate(&ctx, &state, class.0.unwrap_or_else(|| Value::new_undefined(ctx.clone())))
    })?;
    xposed.set("allocateInstance", f)?;
  }
  {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>| js_disable_profile_saver(&ctx, &state))?;
    xposed.set("disableProfileSaver", f)?;
  }
  inu.set("xposed", xposed)?;

  Ok(state)
}

enum Verdict {
  Proceed,
  Answered(String),
}

fn read_context<'js>(ctx: &Ctx<'js>, state: &Rc<XposedState>, context: &Object<'js>) -> JsResult<Verdict> {
  let answered: bool = context.get("__answered").unwrap_or(false);
  if !answered {
    return Ok(Verdict::Proceed);
  }
  let thrown: Option<Value> = context.get("__throwable").ok().flatten();
  if let Some(thrown) = thrown {
    if !thrown.is_null() && !thrown.is_undefined() {
      return Ok(Verdict::Answered(format!("T{}", arg_to_wire(ctx, &state.jvm, &thrown)?)));
    }
  }
  let value: Value = context.get("returnValue")?;
  Ok(Verdict::Answered(arg_to_wire(ctx, &state.jvm, &value)?))
}

fn run_callback<'js>(
  ctx: &Ctx<'js>,
  state: &Rc<XposedState>,
  callback: &Function<'js>,
  context: &Object<'js>,
  phase: &str,
) {
  match callback.call::<_, Value>((context.clone(),)) {
    Ok(_) => {}
    Err(rquickjs::Error::Exception) => {
      (state.log)(&crate::fault(format_args!("xposed {phase} hook threw: {}", format_exception(ctx))));
    }
    Err(e) => (state.log)(&format!("xposed {phase} hook failed: {e:?}")),
  }
}

pub struct Invocation<'a> {
  pub method: &'a str,
  pub this: &'a str,
  pub args: &'a [String],
}

fn proceed_with(wants_after: bool, args: &[String]) -> Vec<String> {
  let mut out = Vec::with_capacity(args.len() + 1);
  out.push(if wants_after { "P1" } else { "P0" }.to_string());
  out.extend(args.iter().cloned());
  out
}

fn run_after<'js>(
  ctx: &Ctx<'js>,
  state: &Rc<XposedState>,
  afters: &[&Function<'js>],
  context_object: &Object<'js>,
  result: &str,
) -> JsResult<String> {
  if afters.is_empty() {
    return Ok(result.to_string());
  }
  publish_result(ctx, state, context_object, result)?;
  for after in afters {
    run_callback(ctx, state, after, context_object, "after");
  }
  Ok(match read_context(ctx, state, context_object)? {
    Verdict::Answered(wire) => wire,
    Verdict::Proceed => result.to_string(),
  })
}

fn build_context<'js>(
  ctx: &Ctx<'js>,
  state: &Rc<XposedState>,
  method: &str,
  this: &str,
  args: &[String],
) -> JsResult<Object<'js>> {
  let object = Object::new(ctx.clone())?;
  object.set("method", wire_to_value(ctx, &state.jvm, method)?)?;
  object.set("thisObject", wire_to_value(ctx, &state.jvm, this)?)?;
  let array = Array::new(ctx.clone())?;
  for (index, arg) in args.iter().enumerate() {
    array.set(index, wire_to_value(ctx, &state.jvm, arg)?)?;
  }
  object.set("args", array)?;
  object.set("returnValue", Value::new_null(ctx.clone()))?;
  object.set("throwable", Value::new_null(ctx.clone()))?;
  object.set("__answered", false)?;
  object.set("__throwable", Value::new_null(ctx.clone()))?;

  {
    let f = Function::new(ctx.clone(), |this: This<Object<'js>>, value: Value<'js>| -> JsResult<()> {
      let null = Value::new_null(this.0.ctx().clone());
      this.0.set("returnValue", value)?;
      this.0.set("__throwable", null)?;
      this.0.set("__answered", true)
    })?;
    object.set("setReturnValue", f)?;
  }
  {
    let f = Function::new(ctx.clone(), |this: This<Object<'js>>, value: Value<'js>| -> JsResult<()> {
      let null = Value::new_null(this.0.ctx().clone());
      this.0.set("returnValue", null)?;
      this.0.set("__throwable", value)?;
      this.0.set("__answered", true)
    })?;
    object.set("setThrowable", f)?;
  }
  Ok(object)
}

fn read_args<'js>(ctx: &Ctx<'js>, state: &Rc<XposedState>, context: &Object<'js>) -> JsResult<Vec<String>> {
  let array: Array = context.get("args")?;
  let mut wires = Vec::new();
  for value in array_values(ctx, &array, "xposed: 'args'")? {
    wires.push(arg_to_wire(ctx, &state.jvm, &value)?);
  }
  Ok(wires)
}

fn publish_result<'js>(ctx: &Ctx<'js>, state: &Rc<XposedState>, context: &Object<'js>, result: &str) -> JsResult<()> {
  context.set("__answered", false)?;
  context.set("__throwable", Value::new_null(ctx.clone()))?;
  match result.strip_prefix('T') {
    Some(thrown) => {
      context.set("returnValue", Value::new_null(ctx.clone()))?;
      context.set("throwable", wire_to_value(ctx, &state.jvm, thrown)?)
    }
    None => {
      context.set("throwable", Value::new_null(ctx.clone()))?;
      context.set("returnValue", wire_to_value(ctx, &state.jvm, result)?)
    }
  }
}

impl XposedState {
  pub fn dispatch_before(
    self: &Rc<Self>,
    rt: &Runtime,
    context: &Context,
    dispatch_id: i64,
    site: i64,
    call: &Invocation<'_>,
  ) -> Vec<String> {
    let state = self;
    let Invocation { method, this, args } = *call;
    let answer = context.with(|ctx| -> JsResult<Vec<String>> {
      let hooks = state.snapshot(&ctx, site);
      if hooks.is_empty() {
        return Ok(proceed_with(false, args));
      }

      let context_object = build_context(&ctx, state, method, this, args)?;

      let mut verdict = Verdict::Proceed;
      for hook in &hooks {
        let Some(before) = &hook.before else { continue };
        run_callback(&ctx, state, before, &context_object, "before");
        if let Verdict::Answered(wire) = read_context(&ctx, state, &context_object)? {
          verdict = Verdict::Answered(wire);
          break;
        }
      }

      let wants_after = hooks.iter().any(|hook| hook.after.is_some());
      if let Verdict::Answered(wire) = verdict {
        let afters: Vec<&Function> = hooks.iter().filter_map(|hook| hook.after.as_ref()).collect();
        let wire = run_after(&ctx, state, &afters, &context_object, &wire)?;
        return Ok(vec!["A".to_string(), wire]);
      }

      let call_args = read_args(&ctx, state, &context_object)?;
      if wants_after {
        let after = hooks
          .iter()
          .filter_map(|hook| hook.after.as_ref())
          .map(|f| Persistent::save(&ctx, f.clone()))
          .collect();
        state.pending.borrow_mut().insert(
          dispatch_id,
          PendingDispatch {
            context: Persistent::save(&ctx, context_object),
            after,
          },
        );
      }
      Ok(proceed_with(wants_after, &call_args))
    });

    pump_jobs(rt, context, state.log.as_ref());
    answer.unwrap_or_else(|_| proceed_with(false, args))
  }

  pub fn dispatch_after(self: &Rc<Self>, rt: &Runtime, context: &Context, dispatch_id: i64, result: &str) -> String {
    let state = self;
    let answer = context.with(|ctx| -> JsResult<String> {
      let Some(pending) = state.pending.borrow_mut().remove(&dispatch_id) else {
        return Ok(result.to_string());
      };
      let context_object = pending.context.restore(&ctx)?;
      let afters: Vec<Function> = pending.after.into_iter().filter_map(|f| f.restore(&ctx).ok()).collect();
      run_after(&ctx, state, &afters.iter().collect::<Vec<_>>(), &context_object, result)
    });
    pump_jobs(rt, context, state.log.as_ref());
    answer.unwrap_or_else(|_| result.to_string())
  }

  pub fn release_dispatch(self: &Rc<Self>, context: &Context, dispatch_id: i64) {
    let state = self;
    let Some(pending) = state.pending.borrow_mut().remove(&dispatch_id) else {
      return;
    };
    context.with(|ctx| {
      let _ = pending.context.restore(&ctx);
      for f in pending.after {
        let _ = f.restore(&ctx);
      }
    });
  }

  pub fn dispose(self: &Rc<Self>, context: &Context) {
    let state = self;
    context.with(|ctx| {
      for hook in state.hooks.take_values() {
        state.release(&ctx, hook);
      }
      for (_, pending) in state.pending.borrow_mut().drain() {
        let _ = pending.context.restore(&ctx);
        for f in pending.after {
          let _ = f.restore(&ctx);
        }
      }
    });
  }
}

#[cfg(test)]
#[path = "tests.rs"]
mod tests;

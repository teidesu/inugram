pub(crate) mod elf;
pub(crate) mod lsplant;

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use rquickjs::function::Opt;
use rquickjs::{Array, Context, Ctx, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::api::error::PluginErrorCode;
use crate::api::platform::jvm::JvmState;
use crate::sandbox::grants::{GrantHost, MATCH_NAMESPACE};
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
const OP_NATIVE_ADD: i32 = 6;
const OP_NATIVE_REMOVE: i32 = 7;

pub const GRANT: &str = "unsafe.xposed";

pub const HOOK_LIMIT: usize = 512;

pub const HOOK_BUDGET_MS: i64 = 250;

#[derive(Clone)]
struct Hook {
  site: i64,
  native_token: Option<u32>,
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
    if let Some(token) = hook.native_token {
      self.host.xposed(OP_NATIVE_REMOVE, hook.site, &token.to_string(), &[]);
    }
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

impl XposedState {
  fn ask<'js>(&self, ctx: &Ctx<'js>, op: i32, target: i64, name: &str, args: &[String]) -> JsResult<Value<'js>> {
    let wire = self.host.xposed(op, target, name, args);
    self.jvm.wire_to_value(ctx, &wire)
  }

  fn require_handle<'js>(&self, ctx: &Ctx<'js>, value: &Value<'js>, what: &str) -> JsResult<i64> {
    let id = self.jvm.handle_id(ctx, value)?;
    if id < 0 {
      return PluginErrorCode::InvalidArgument.throw(ctx, &format!("xposed: {what} expected a java class or method"));
    }
    Ok(id)
  }
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
  native_phases: Option<[String; 2]>,
  before: Option<Function<'js>>,
  after: Option<Function<'js>>,
}

fn callbacks_of<'js>(ctx: &Ctx<'js>, jvm: &JvmState, hook: &Object<'js>, what: &str) -> JsResult<Callbacks<'js>> {
  let mut callbacks = Callbacks {
    before: None,
    after: None,
    native_phases: None,
  };
  let mut wires = ["N".to_string(), "N".to_string()];
  let mut has_native = false;
  for (index, phase) in ["before", "after"].iter().enumerate() {
    let value: Value = hook.get(*phase)?;
    if value.is_undefined() {
      continue;
    }
    let id = jvm.handle_id(ctx, &value)?;
    if id >= 0 {
      wires[index] = format!("G{id}");
      has_native = true;
    } else if let Some(callback) = value.as_function() {
      if index == 0 {
        callbacks.before = Some(callback.clone());
      } else {
        callbacks.after = Some(callback.clone());
      }
    } else {
      return PluginErrorCode::InvalidArgument
        .throw(ctx, &format!("{what}: {phase} must be a function, Java Runnable or Consumer"));
    }
  }
  if has_native {
    if callbacks.before.is_some() || callbacks.after.is_some() {
      return PluginErrorCode::InvalidArgument
        .throw(ctx, "xposed: cannot mix native phases and JS callbacks in one hook");
    }
    callbacks.native_phases = Some(wires);
  } else if callbacks.before.is_none() && callbacks.after.is_none() {
    return PluginErrorCode::InvalidArgument.throw(ctx, "xposed: a hook needs a before or an after callback");
  }
  Ok(callbacks)
}

impl XposedState {
  fn install_hooks<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    sites: Vec<i64>,
    callbacks: Callbacks<'js>,
  ) -> JsResult<Function<'js>> {
    let mut tokens = Vec::with_capacity(sites.len());
    for &site in &sites {
      let token = self.hooks.alloc();
      *self.sites.borrow_mut().entry(site).or_insert(0) += 1;
      self.hooks.insert(
        token,
        None,
        Hook {
          site,
          native_token: callbacks.native_phases.as_ref().map(|_| token),
          before: callbacks.before.clone().map(|f| Persistent::save(ctx, f)),
          after: callbacks.after.clone().map(|f| Persistent::save(ctx, f)),
        },
      );
      tokens.push(token);
      if let Some(native_phases) = &callbacks.native_phases {
        if let Err(error) = self.ask(ctx, OP_NATIVE_ADD, site, &token.to_string(), native_phases) {
          for token in &tokens {
            if let Some(hook) = self.hooks.remove(*token) {
              self.release(ctx, hook);
            }
          }
          for orphan in sites.iter().skip(tokens.len()).filter(|site| !self.sites.borrow().contains_key(site)) {
            self.host.xposed(OP_UNHOOK, *orphan, "", &[]);
          }
          return Err(error);
        }
      }
    }

    let held = self.hooks.len();
    if held > HOOK_LIMIT {
      for token in &tokens {
        if let Some(hook) = self.hooks.remove(*token) {
          self.release(ctx, hook);
        }
      }
      return PluginErrorCode::QuotaExceeded(held as i64, HOOK_LIMIT as i64)
        .throw(ctx, &format!("xposed: this plugin may hold at most {HOOK_LIMIT} hooks"));
    }

    let state = self.clone();
    make_disposer(ctx, move |ctx| {
      for token in &tokens {
        if let Some(hook) = state.hooks.remove(*token) {
          state.release(ctx, hook);
        }
      }
    })
  }

  fn js_hook<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    op: i32,
    target: Value<'js>,
    name: &str,
    hook: Value<'js>,
    what: &str,
  ) -> JsResult<Function<'js>> {
    self.grants.check_grant(ctx, GRANT, None, MATCH_NAMESPACE)?;
    let Some(hook) = hook.as_object() else {
      return PluginErrorCode::InvalidArgument.throw(ctx, &format!("{what}: expected a hook object"));
    };
    let callbacks = callbacks_of(ctx, &self.jvm, hook, what)?;
    if self.lifecycle.is_unloading() {
      return noop_disposer(ctx);
    }
    let target = self.require_handle(ctx, &target, "hook")?;
    let answered = self.ask(ctx, op, target, name, callbacks.native_phases.as_ref().map(|v| &v[..]).unwrap_or(&[]))?;
    let sites = sites_from(ctx, answered)?;
    self.install_hooks(ctx, sites, callbacks)
  }

  fn js_call_original<'js>(
    &self,
    ctx: &Ctx<'js>,
    method: Value<'js>,
    this: Opt<Value<'js>>,
    args: Opt<Value<'js>>,
  ) -> JsResult<Value<'js>> {
    self.grants.check_grant(ctx, GRANT, None, MATCH_NAMESPACE)?;
    let method = self.require_handle(ctx, &method, "callOriginalMethod")?;
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
    let mut wires = vec![self.jvm.arg_to_wire(ctx, &this)?];
    for arg in args {
      wires.push(self.jvm.arg_to_wire(ctx, &arg)?);
    }
    self.ask(ctx, OP_CALL_ORIGINAL, method, "", &wires)
  }

  fn js_allocate<'js>(&self, ctx: &Ctx<'js>, class: Value<'js>) -> JsResult<Value<'js>> {
    self.grants.check_grant(ctx, GRANT, None, MATCH_NAMESPACE)?;
    let class = self.require_handle(ctx, &class, "allocateInstance")?;
    self.ask(ctx, OP_ALLOCATE, class, "", &[])
  }

  fn js_disable_profile_saver<'js>(&self, ctx: &Ctx<'js>) -> JsResult<Value<'js>> {
    self.grants.check_grant(ctx, GRANT, None, MATCH_NAMESPACE)?;
    self.ask(ctx, OP_DISABLE_PROFILE_SAVER, 0, "", &[])
  }
}

pub fn install_xposed<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn XposedHost>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  jvm: Rc<JvmState>,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
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
    xposed.set(
      "hookMethod",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, method: Opt<Value<'js>>, hook: Opt<Value<'js>>| {
        state.js_hook(
          &ctx,
          OP_HOOK,
          method.0.unwrap_or_else(|| Value::new_undefined(ctx.clone())),
          "",
          hook.0.unwrap_or_else(|| Value::new_undefined(ctx.clone())),
          "hookMethod",
        )
      })?,
    )?;
  }
  {
    let state = state.clone();
    xposed.set(
      "hookAllOverloads",
      Function::new(
        ctx.clone(),
        move |ctx: Ctx<'js>, class: Opt<Value<'js>>, name: Opt<Value<'js>>, hook: Opt<Value<'js>>| {
          let Some(name) = name
            .0
            .and_then(|name| name.as_string().and_then(|name| name.to_string().ok()))
            .filter(|name| !name.is_empty())
          else {
            return PluginErrorCode::InvalidArgument.throw(&ctx, "hookAllOverloads: expected a method name");
          };
          state.js_hook(
            &ctx,
            OP_HOOK_ALL,
            class.0.unwrap_or_else(|| Value::new_undefined(ctx.clone())),
            &name,
            hook.0.unwrap_or_else(|| Value::new_undefined(ctx.clone())),
            "hookAllOverloads",
          )
        },
      )?,
    )?;
  }
  {
    let state = state.clone();
    xposed.set(
      "hookAllConstructors",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, class: Opt<Value<'js>>, hook: Opt<Value<'js>>| {
        state.js_hook(
          &ctx,
          OP_HOOK_ALL,
          class.0.unwrap_or_else(|| Value::new_undefined(ctx.clone())),
          "",
          hook.0.unwrap_or_else(|| Value::new_undefined(ctx.clone())),
          "hookAllConstructors",
        )
      })?,
    )?;
  }
  {
    let state = state.clone();
    xposed.set(
      "callOriginalMethod",
      Function::new(
        ctx.clone(),
        move |ctx: Ctx<'js>, method: Opt<Value<'js>>, this: Opt<Value<'js>>, args: Opt<Value<'js>>| {
          state.js_call_original(&ctx, method.0.unwrap_or_else(|| Value::new_undefined(ctx.clone())), this, args)
        },
      )?,
    )?;
  }
  {
    let state = state.clone();
    xposed.set(
      "allocateInstance",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, class: Opt<Value<'js>>| {
        state.js_allocate(&ctx, class.0.unwrap_or_else(|| Value::new_undefined(ctx.clone())))
      })?,
    )?;
  }
  {
    let state = state.clone();
    xposed.set(
      "disableProfileSaver",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>| state.js_disable_profile_saver(&ctx))?,
    )?;
  }
  {
    let state = state.clone();
    xposed.set(
      "routine",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, builder: Value<'js>| {
        state.grants.check_grant(&ctx, GRANT, None, MATCH_NAMESPACE)?;
        state.jvm.build_xposed_routine(&ctx, builder)
      })?,
    )?;
  }
  globals.inu.set("xposed", xposed)?;

  Ok(state)
}

pub(crate) const KEEP_ORIGINAL: &str = "U";

/// the after phase never ran, so nothing here took the result wire: the host still owns whatever
/// it minted for it. Distinct from [`KEEP_ORIGINAL`], which is a hook's own answer *after* the
/// wire was read into a handle the engine owns.
pub(crate) const NOT_DISPATCHED: &str = "X";

enum Verdict {
  Proceed,
  Answered(String),
}

impl XposedState {
  fn read_context<'js>(&self, ctx: &Ctx<'js>, context: &Object<'js>) -> JsResult<Verdict> {
    let answered: bool = context.get("__answered").unwrap_or(false);
    if !answered {
      return Ok(Verdict::Proceed);
    }
    let thrown: Option<Value> = context.get("__throwable").ok().flatten();
    if let Some(thrown) = thrown {
      if !thrown.is_null() && !thrown.is_undefined() {
        return Ok(Verdict::Answered(format!("T{}", self.jvm.arg_to_wire(ctx, &thrown)?)));
      }
    }
    let value: Value = context.get("returnValue")?;
    Ok(Verdict::Answered(self.jvm.arg_to_wire(ctx, &value)?))
  }

  fn run_callback<'js>(&self, ctx: &Ctx<'js>, callback: &Function<'js>, context: &Object<'js>, phase: &str) {
    match callback.call::<_, Value>((context.clone(),)) {
      Ok(_) => {}
      Err(rquickjs::Error::Exception) => {
        (self.log)(&crate::fault(format_args!("xposed {phase} hook threw: {}", format_exception(ctx))));
      }
      Err(e) => (self.log)(&format!("xposed {phase} hook failed: {e:?}")),
    }
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

impl XposedState {
  fn run_after<'js>(
    &self,
    ctx: &Ctx<'js>,
    afters: &[&Function<'js>],
    context_object: &Object<'js>,
    result: &str,
  ) -> JsResult<String> {
    if afters.is_empty() {
      return Ok(KEEP_ORIGINAL.to_string());
    }
    self.publish_result(ctx, context_object, result)?;
    for after in afters {
      self.run_callback(ctx, after, context_object, "after");
    }
    Ok(match self.read_context(ctx, context_object)? {
      Verdict::Answered(wire) => wire,
      Verdict::Proceed => KEEP_ORIGINAL.to_string(),
    })
  }

  fn build_context<'js>(&self, ctx: &Ctx<'js>, method: &str, this: &str, args: &[String]) -> JsResult<Object<'js>> {
    let object = Object::new(ctx.clone())?;
    object.set("method", self.jvm.wire_to_value(ctx, method)?)?;
    object.set("thisObject", self.jvm.wire_to_value(ctx, this)?)?;
    let array = Array::new(ctx.clone())?;
    for (index, arg) in args.iter().enumerate() {
      array.set(index, self.jvm.wire_to_value(ctx, arg)?)?;
    }
    object.set("args", array)?;
    object.set("returnValue", Value::new_null(ctx.clone()))?;
    object.set("throwable", Value::new_null(ctx.clone()))?;
    object.set("__answered", false)?;
    object.set("__throwable", Value::new_null(ctx.clone()))?;

    {
      object.set(
        "setReturnValue",
        Function::new(ctx.clone(), |this: This<Object<'js>>, value: Value<'js>| -> JsResult<()> {
          let null = Value::new_null(this.0.ctx().clone());
          this.0.set("returnValue", value)?;
          this.0.set("__throwable", null)?;
          this.0.set("__answered", true)
        })?,
      )?;
    }
    {
      object.set(
        "setThrowable",
        Function::new(ctx.clone(), |this: This<Object<'js>>, value: Value<'js>| -> JsResult<()> {
          let null = Value::new_null(this.0.ctx().clone());
          this.0.set("returnValue", null)?;
          this.0.set("__throwable", value)?;
          this.0.set("__answered", true)
        })?,
      )?;
    }
    Ok(object)
  }

  fn read_args<'js>(&self, ctx: &Ctx<'js>, context: &Object<'js>) -> JsResult<Vec<String>> {
    let array: Array = context.get("args")?;
    let mut wires = Vec::new();
    for value in array_values(ctx, &array, "xposed: 'args'")? {
      wires.push(self.jvm.arg_to_wire(ctx, &value)?);
    }
    Ok(wires)
  }

  fn publish_result<'js>(&self, ctx: &Ctx<'js>, context: &Object<'js>, result: &str) -> JsResult<()> {
    context.set("__answered", false)?;
    context.set("__throwable", Value::new_null(ctx.clone()))?;
    match result.strip_prefix('T') {
      Some(thrown) => {
        context.set("returnValue", Value::new_null(ctx.clone()))?;
        context.set("throwable", self.jvm.wire_to_value(ctx, thrown)?)
      }
      None => {
        context.set("throwable", Value::new_null(ctx.clone()))?;
        context.set("returnValue", self.jvm.wire_to_value(ctx, result)?)
      }
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
    // set the moment the wires are about to be read into handles the engine owns: from there on a
    // failure may answer anything but "nothing was dispatched", or the host would release them
    let taken = Cell::new(false);
    let answer = context.with(|ctx| -> JsResult<Vec<String>> {
      let hooks = state.snapshot(&ctx, site);
      if hooks.is_empty() {
        // nothing here read the argument wires, so the host is told it still owns them
        return Ok(Vec::new());
      }

      taken.set(true);
      let context_object = state.build_context(&ctx, method, this, args)?;

      let mut verdict = Verdict::Proceed;
      for hook in &hooks {
        let Some(before) = &hook.before else { continue };
        state.run_callback(&ctx, before, &context_object, "before");
        if let Verdict::Answered(wire) = state.read_context(&ctx, &context_object)? {
          verdict = Verdict::Answered(wire);
          break;
        }
      }

      let wants_after = hooks.iter().any(|hook| hook.after.is_some());
      if let Verdict::Answered(wire) = verdict {
        let afters: Vec<&Function> = hooks.iter().filter_map(|hook| hook.after.as_ref()).collect();
        let after = state.run_after(&ctx, &afters, &context_object, &wire)?;
        return Ok(vec!["A".to_string(), if after == KEEP_ORIGINAL { wire } else { after }]);
      }

      let call_args = state.read_args(&ctx, &context_object)?;
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
    // a dispatch that never started leaves the wires the host's; one that failed after reading them
    // proceeds with the arguments it was given, the engine keeping what it took
    answer.unwrap_or_else(|_| if taken.get() { proceed_with(false, args) } else { Vec::new() })
  }

  pub fn dispatch_after(self: &Rc<Self>, rt: &Runtime, context: &Context, dispatch_id: i64, result: &str) -> String {
    let state = self;
    // as in `dispatch_before`: once `run_after` has published the result the engine owns whatever
    // the wire minted, and the host must not be told the phase never ran
    let taken = Cell::new(false);
    let answer = context.with(|ctx| -> JsResult<String> {
      let Some(pending) = state.pending.borrow_mut().remove(&dispatch_id) else {
        return Ok(NOT_DISPATCHED.to_string());
      };
      let context_object = pending.context.restore(&ctx)?;
      let afters: Vec<Function> = pending.after.into_iter().filter_map(|f| f.restore(&ctx).ok()).collect();
      // `run_after` answers an empty set without publishing, so nothing would have read the wire
      if afters.is_empty() {
        return Ok(NOT_DISPATCHED.to_string());
      }
      taken.set(true);
      state.run_after(&ctx, &afters.iter().collect::<Vec<_>>(), &context_object, result)
    });
    pump_jobs(rt, context, state.log.as_ref());
    answer.unwrap_or_else(|_| if taken.get() { KEEP_ORIGINAL.to_string() } else { NOT_DISPATCHED.to_string() })
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

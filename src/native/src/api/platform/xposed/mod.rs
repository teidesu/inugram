mod context;
pub(crate) mod elf;
pub(crate) mod lsplant;

use crate::runtime::enter_js;
use crate::runtime::Dispose;
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use jni::objects::{JObject, JObjectArray};
use jni::sys::jobjectArray;
use rquickjs::function::Opt;
use rquickjs::{Class, Context, Ctx, Function, Object, Persistent, Result as JsResult, Value};

use crate::api::error::PluginErrorCode;
use crate::api::platform::jvm::JvmState;
use crate::sandbox::grants::{GrantHost, MATCH_NAMESPACE};
use crate::sandbox::registry::{make_disposer, noop_disposer, Lifecycle};
use crate::utils::arguments::array_values;
use crate::utils::shape::get_class_prototype;

use crate::api::error::report_callback_error;
use crate::runtime::pump_jobs;
use context::{create_hook_context, HookContext};

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
const OP_JS_BEFORES: i32 = 8;
const OP_JS_FILTER: i32 = 9;

pub const GRANT: &str = "unsafe.xposed";

pub const HOOK_LIMIT: usize = 512;

pub const HOOK_BUDGET_MS: i64 = 250;

struct Hook {
  token: u32,
  native: bool,
  before: Option<Persistent<Function<'static>>>,
  after: Option<Persistent<Function<'static>>>,
}

pub struct XposedState {
  host: Rc<dyn XposedHost>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  jvm: Rc<JvmState>,
  log: crate::Log,
  context_proto: RefCell<Option<Persistent<Object<'static>>>>,
  sites: RefCell<HashMap<i64, SiteHooks>>,
  held: Cell<usize>,
  next_token: Cell<u32>,
  pending: RefCell<HashMap<i64, PendingDispatch>>,
}

#[derive(Default)]
struct SiteHooks {
  hooks: Vec<Hook>,
  befores: usize,
}

struct PendingDispatch {
  context: Persistent<Object<'static>>,
  after: Vec<Persistent<Function<'static>>>,
}

impl XposedState {
  fn take_hook(&self, site: i64, token: u32) -> Option<(Hook, usize, bool)> {
    let mut sites = self.sites.borrow_mut();
    let entry = sites.get_mut(&site)?;
    let index = entry.hooks.iter().position(|hook| hook.token == token)?;
    let hook = entry.hooks.remove(index);
    if hook.before.is_some() {
      entry.befores -= 1;
    }
    let befores = entry.befores;
    let emptied = entry.hooks.is_empty();
    if emptied {
      sites.remove(&site);
    }
    self.held.set(self.held.get() - 1);
    Some((hook, befores, emptied))
  }

  fn release(&self, _ctx: &Ctx<'_>, site: i64, token: u32) {
    let Some((hook, befores, emptied)) = self.take_hook(site, token) else {
      return;
    };
    if hook.native {
      self.host.xposed(OP_NATIVE_REMOVE, site, &token.to_string(), &[]);
    }
    let had_before = hook.before.is_some();
    drop(hook);
    if emptied {
      self.host.xposed(OP_UNHOOK, site, "", &[]);
    } else if had_before {
      self.host.xposed(OP_JS_BEFORES, site, &befores.to_string(), &[]);
    }
  }

  fn release_tokens(&self, ctx: &Ctx<'_>, tokens: &[(i64, u32)]) {
    for (site, token) in tokens {
      self.release(ctx, *site, *token);
    }
  }

  fn hook_proto<'js>(&self, ctx: &Ctx<'js>) -> JsResult<Object<'js>> {
    let held = self.context_proto.borrow().clone();
    match held {
      Some(proto) => proto.restore(ctx),
      None => PluginErrorCode::Internal.throw(ctx, "xposed: the hook context class is not installed"),
    }
  }

  fn snapshot<'js>(&self, ctx: &Ctx<'js>, site: i64) -> Vec<Phase<'js>> {
    let sites = self.sites.borrow();
    let Some(entry) = sites.get(&site) else {
      return Vec::new();
    };
    entry
      .hooks
      .iter()
      .map(|hook| Phase {
        before: hook.before.clone().and_then(|f| f.restore(ctx).ok()),
        after: hook.after.clone().and_then(|f| f.restore(ctx).ok()),
      })
      .collect()
  }

  fn snapshot_afters<'js>(&self, ctx: &Ctx<'js>, site: i64) -> Vec<Function<'js>> {
    let sites = self.sites.borrow();
    let Some(entry) = sites.get(&site) else {
      return Vec::new();
    };
    entry.hooks.iter().rev().filter_map(|hook| hook.after.clone()?.restore(ctx).ok()).collect()
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
    match self.jvm.handle_id(value) {
      Some(id) => Ok(id),
      None => PluginErrorCode::InvalidArgument.throw(ctx, &format!("xposed: {what} expected a java class or method")),
    }
  }
}

fn or_undefined<'js>(ctx: &Ctx<'js>, value: Opt<Value<'js>>) -> Value<'js> {
  value.0.unwrap_or_else(|| Value::new_undefined(ctx.clone()))
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
  filter: Option<String>,
}

fn read_hook_callbacks<'js>(
  ctx: &Ctx<'js>,
  jvm: &JvmState,
  hook: &Object<'js>,
  what: &str,
) -> JsResult<Callbacks<'js>> {
  let mut callbacks = Callbacks {
    before: None,
    after: None,
    native_phases: None,
    filter: None,
  };
  let mut wires = ["N".to_string(), "N".to_string()];
  let mut has_native = false;
  for (index, phase) in ["before", "after"].iter().enumerate() {
    let value: Value = hook.get(*phase)?;
    if value.is_undefined() {
      continue;
    }
    if let Some(id) = jvm.handle_id(&value) {
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
  let filter: Value = hook.get("filter")?;
  if !filter.is_undefined() && !filter.is_null() {
    if has_native {
      return PluginErrorCode::InvalidArgument
        .throw(ctx, "xposed: a native hook already runs on the hooked thread, so a filter would only cost it");
    }
    let Some(id) = jvm.handle_id(&filter) else {
      return PluginErrorCode::InvalidArgument.throw(ctx, &format!("{what}: filter must be an inu.jvm.routine"));
    };
    callbacks.filter = Some(format!("G{id}"));
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
      let token = self.next_token.get();
      self.next_token.set(token.wrapping_add(1));
      let befores = {
        let mut held = self.sites.borrow_mut();
        let entry = held.entry(site).or_default();
        if callbacks.before.is_some() {
          entry.befores += 1;
        }
        entry.hooks.push(Hook {
          token,
          native: callbacks.native_phases.is_some(),
          before: callbacks.before.clone().map(|f| Persistent::save(ctx, f)),
          after: callbacks.after.clone().map(|f| Persistent::save(ctx, f)),
        });
        entry.befores
      };
      self.held.set(self.held.get() + 1);
      tokens.push((site, token));
      if callbacks.native_phases.is_none() {
        self.host.xposed(OP_JS_BEFORES, site, &befores.to_string(), &[]);
      }
      if let Some(filter) = &callbacks.filter {
        if let Err(error) = self.ask(ctx, OP_JS_FILTER, site, filter, &[]) {
          self.release_tokens(ctx, &tokens);
          return Err(error);
        }
      }
      if let Some(native_phases) = &callbacks.native_phases {
        if let Err(error) = self.ask(ctx, OP_NATIVE_ADD, site, &token.to_string(), native_phases) {
          self.release_tokens(ctx, &tokens);
          for orphan in sites.iter().skip(tokens.len()).filter(|site| !self.sites.borrow().contains_key(site)) {
            self.host.xposed(OP_UNHOOK, *orphan, "", &[]);
          }
          return Err(error);
        }
      }
    }

    let held = self.held.get();
    if held > HOOK_LIMIT {
      self.release_tokens(ctx, &tokens);
      return PluginErrorCode::QuotaExceeded(held as i64, HOOK_LIMIT as i64)
        .throw(ctx, &format!("xposed: this plugin may hold at most {HOOK_LIMIT} hooks"));
    }

    let state = self.clone();
    make_disposer(ctx, move |ctx| state.release_tokens(ctx, &tokens))
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
    let callbacks = read_hook_callbacks(ctx, &self.jvm, hook, what)?;
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
    let wire = crate::jni::lend_engine(|| self.host.xposed(OP_CALL_ORIGINAL, method, "", &wires));
    self.jvm.wire_to_value(ctx, &wire)
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
  // cached so a dispatch skips the class registry's type-id lookup
  let context_proto = Persistent::save(ctx, get_class_prototype::<HookContext>(ctx)?);
  let state = Rc::new(XposedState {
    host,
    grants,
    lifecycle,
    jvm,
    log,
    context_proto: RefCell::new(Some(context_proto)),
    sites: RefCell::new(HashMap::new()),
    held: Cell::new(0),
    next_token: Cell::new(1),
    pending: RefCell::new(HashMap::new()),
  });

  let xposed = Object::new(ctx.clone())?;
  set_fn!(
    xposed,
    "hookMethod",
    ctx,
    state,
    move |ctx: Ctx<'js>, method: Opt<Value<'js>>, hook: Opt<Value<'js>>| {
      state.js_hook(&ctx, OP_HOOK, or_undefined(&ctx, method), "", or_undefined(&ctx, hook), "hookMethod")
    }
  );
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
            or_undefined(&ctx, class),
            &name,
            or_undefined(&ctx, hook),
            "hookAllOverloads",
          )
        },
      )?,
    )?;
  }
  set_fn!(
    xposed,
    "hookAllConstructors",
    ctx,
    state,
    move |ctx: Ctx<'js>, class: Opt<Value<'js>>, hook: Opt<Value<'js>>| {
      state.js_hook(&ctx, OP_HOOK_ALL, or_undefined(&ctx, class), "", or_undefined(&ctx, hook), "hookAllConstructors")
    }
  );
  {
    let state = state.clone();
    xposed.set(
      "callOriginalMethod",
      Function::new(
        ctx.clone(),
        move |ctx: Ctx<'js>, method: Opt<Value<'js>>, this: Opt<Value<'js>>, args: Opt<Value<'js>>| {
          state.js_call_original(&ctx, or_undefined(&ctx, method), this, args)
        },
      )?,
    )?;
  }
  set_fn!(xposed, "allocateInstance", ctx, state, move |ctx: Ctx<'js>, class: Opt<Value<'js>>| {
    state.js_allocate(&ctx, or_undefined(&ctx, class))
  });
  set_fn!(xposed, "disableProfileSaver", ctx, state, move |ctx: Ctx<'js>| state.js_disable_profile_saver(&ctx));
  set_fn!(
    xposed,
    "routine",
    ctx,
    state,
    move |ctx: Ctx<'js>, program: Value<'js>, captures: Opt<Value<'js>>| {
      state.grants.check_grant(&ctx, GRANT, None, MATCH_NAMESPACE)?;
      let captures = captures.0.unwrap_or_else(|| Value::new_undefined(ctx.clone()));
      state.jvm.build_xposed_routine(&ctx, program, captures)
    }
  );
  globals.inu.set("xposed", xposed)?;

  Ok(state)
}

pub(crate) const KEEP_ORIGINAL: &str = "U";

/// the after phase never ran, so no pending dispatch was consumed. Distinct from
/// [`KEEP_ORIGINAL`], which is a hook's own answer.
pub(crate) const NOT_DISPATCHED: &str = "X";

pub(crate) const KEEP_ARGUMENT: &str = "=";

/// an invocation is `[method, receiver, args..]`
pub(crate) const FIRST_ARG_INDEX: usize = 2;

/// what a hooked call is answered with. [`Answer::Keep`] crosses as a null string, so the common
/// case allocates nothing on either side.
pub enum Answer {
  Keep,
  NotDispatched,
  Wire(String),
}

impl Answer {
  fn of(wire: String) -> Answer {
    if wire == KEEP_ORIGINAL {
      Answer::Keep
    } else {
      Answer::Wire(wire)
    }
  }
}

pub trait JavaValues {
  fn read<'js>(&self, ctx: &Ctx<'js>, index: usize) -> JsResult<Value<'js>>;
}

/// Borrows the caller's invocation array for this JNI call without creating global references.
/// Reads go through a hook context that expires when the phase returns.
pub struct HookedValues {
  jvm: Rc<JvmState>,
  array: jobjectArray,
  pub count: usize,
}

impl JavaValues for HookedValues {
  fn read<'js>(&self, ctx: &Ctx<'js>, index: usize) -> JsResult<Value<'js>> {
    self.jvm.element_to_value(ctx, self.array, index)
  }
}

pub struct Invocation {
  pub values: Rc<dyn JavaValues>,
  pub args: usize,
}

#[derive(Clone)]
pub struct Returned {
  pub values: Rc<dyn JavaValues>,
  pub index: usize,
  pub threw: bool,
}

enum Published<'a> {
  Answer(&'a str),
  Returned(&'a Returned),
}

fn proceed_with(wants_after: bool, args: Vec<String>) -> Vec<String> {
  let mut out = Vec::with_capacity(args.len() + 1);
  out.push(if wants_after { "P1" } else { "P0" }.to_string());
  out.extend(args);
  out
}

impl XposedState {
  /// `count` is the host's own `invocation.length`: asking the vm for it would be one jni call per
  /// hooked call for a number the caller already has
  pub fn read_hooked_values(&self, array: &JObjectArray<JObject>, count: usize) -> Rc<HookedValues> {
    Rc::new(HookedValues {
      jvm: self.jvm.clone(),
      array: array.as_raw(),
      count,
    })
  }

  fn run_callback<'js>(
    &self,
    ctx: &Ctx<'js>,
    callback: &Function<'js>,
    context: &Class<'js, HookContext<'js>>,
    phase: &str,
  ) {
    if let Err(error) = callback.call::<_, Value>((context.clone(),)) {
      report_callback_error(&self.log, ctx, &format!("xposed {phase} hook"), error);
    }
  }

  fn run_after<'js>(
    &self,
    ctx: &Ctx<'js>,
    afters: &[Function<'js>],
    context: &Class<'js, HookContext<'js>>,
    published: Published<'_>,
  ) -> JsResult<Answer> {
    if afters.is_empty() {
      return Ok(Answer::Keep);
    }
    match published {
      Published::Answer(wire) => context.borrow().publish_answer(ctx, wire)?,
      Published::Returned(returned) => context.borrow().publish_returned(returned),
    }
    for after in afters {
      self.run_callback(ctx, after, context, "after");
    }
    Ok(context.borrow().get_answer(ctx)?.map_or(Answer::Keep, Answer::of))
  }
}

impl XposedState {
  pub fn dispatch_before(
    self: &Rc<Self>,
    context: &Context,
    dispatch_id: i64,
    site: i64,
    call: &Invocation,
  ) -> Vec<String> {
    let answer = enter_js(context, |ctx| -> JsResult<Vec<String>> {
      let hooks = self.snapshot(&ctx, site);
      if hooks.is_empty() {
        return Ok(Vec::new());
      }

      let hook_context = create_hook_context(&self.jvm, call, self.hook_proto(&ctx)?)?;
      let answer = self.run_before(&ctx, &hooks, &hook_context, dispatch_id);
      hook_context.borrow().expire();
      answer
    });

    pump_jobs(context, self.log.as_ref());
    answer.unwrap_or_default()
  }

  fn run_before<'js>(
    &self,
    ctx: &Ctx<'js>,
    hooks: &[Phase<'js>],
    hook_context: &Class<'js, HookContext<'js>>,
    dispatch_id: i64,
  ) -> JsResult<Vec<String>> {
    let mut answer = None;
    for hook in hooks {
      let Some(before) = &hook.before else { continue };
      self.run_callback(ctx, before, hook_context, "before");
      answer = hook_context.borrow().get_answer(ctx)?;
      if answer.is_some() {
        break;
      }
    }

    let afters: Vec<Function> = hooks.iter().rev().filter_map(|hook| hook.after.clone()).collect();
    let wants_after = !afters.is_empty();
    if let Some(wire) = answer {
      let after = self.run_after(ctx, &afters, hook_context, Published::Answer(&wire))?;
      return Ok(vec!["A".to_string(), if let Answer::Wire(after) = after { after } else { wire }]);
    }

    let call_args = hook_context.borrow().get_call_args(ctx)?;
    if wants_after {
      let after = afters.into_iter().map(|f| Persistent::save(ctx, f)).collect();
      self.pending.borrow_mut().insert(
        dispatch_id,
        PendingDispatch {
          context: Persistent::save(ctx, hook_context.clone().into_inner()),
          after,
        },
      );
    }
    Ok(proceed_with(wants_after, call_args))
  }

  pub fn dispatch_after(
    self: &Rc<Self>,
    context: &Context,
    dispatch_id: i64,
    call: &Invocation,
    returned: &Returned,
  ) -> Answer {
    let answer = enter_js(context, |ctx| -> JsResult<Answer> {
      let Some(pending) = self.pending.borrow_mut().remove(&dispatch_id) else {
        return Ok(Answer::NotDispatched);
      };
      let object = pending.context.restore(&ctx)?;
      let Some(hook_context) = Class::<HookContext>::from_object(&object) else {
        return PluginErrorCode::Internal.throw(&ctx, "xposed: a pending dispatch lost its context");
      };
      let afters: Vec<Function> = pending.after.into_iter().filter_map(|f| f.restore(&ctx).ok()).collect();
      hook_context.borrow().revive(call);
      let answer = self.run_after(&ctx, &afters, &hook_context, Published::Returned(returned));
      hook_context.borrow().expire();
      answer
    });
    pump_jobs(context, self.log.as_ref());
    answer.unwrap_or(Answer::Keep)
  }

  pub fn dispatch_after_only(
    self: &Rc<Self>,
    context: &Context,
    site: i64,
    call: &Invocation,
    returned: &Returned,
  ) -> Answer {
    let answer = enter_js(context, |ctx| -> JsResult<Answer> {
      let afters = self.snapshot_afters(&ctx, site);
      if afters.is_empty() {
        return Ok(Answer::NotDispatched);
      }
      let hook_context = create_hook_context(&self.jvm, call, self.hook_proto(&ctx)?)?;
      let answer = self.run_after(&ctx, &afters, &hook_context, Published::Returned(returned));
      hook_context.borrow().expire();
      answer
    });
    pump_jobs(context, self.log.as_ref());
    answer.unwrap_or(Answer::Keep)
  }

  pub fn release_dispatch(self: &Rc<Self>, context: &Context, dispatch_id: i64) {
    let Some(pending) = self.pending.borrow_mut().remove(&dispatch_id) else {
      return;
    };
    enter_js(context, |_| drop(pending));
  }
}

impl Dispose for XposedState {
  fn dispose(&self, context: &rquickjs::Context) {
    enter_js(context, |ctx| {
      let installed: Vec<(i64, u32)> = self
        .sites
        .borrow()
        .iter()
        .flat_map(|(site, entry)| entry.hooks.iter().map(|hook| (*site, hook.token)))
        .collect();
      self.release_tokens(&ctx, &installed);
      let pending = std::mem::take(&mut *self.pending.borrow_mut());
      let proto = self.context_proto.take();
      drop((pending, proto));
    });
  }
}

#[cfg(test)]
#[path = "tests.rs"]
mod tests;

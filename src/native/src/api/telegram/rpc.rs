use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use rquickjs::function::{Opt, This};
use rquickjs::{Ctx, Exception, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::api::error::{self, error_value_to_string, format_thrown, PluginErrorCode};
use crate::api::telegram::account::{dispatch_account, AccountState};
use crate::api::tl::proxy::{self, TlViews, ViewLife};
use crate::sandbox::grants::{GrantHost, MATCH_EXACT};
use crate::sandbox::registry::{make_disposer, noop_disposer, CallbackRegistry, Lifecycle, Registry};
use crate::utils::prelude;
use crate::Log;

pub(crate) use crate::api::error::format_exception;

pub trait RpcHost {
  fn on_register(
    &self,
    methods: &[String],
    callback_id: u32,
    scope: &str,
    strict: bool,
    filter_json: &str,
  ) -> Option<String>;
  fn on_unregister(&self, callback_id: u32);
  fn on_invoke(&self, invoke_id: i64, slot: i32, request_wire: &str) -> Option<String>;
  fn on_next(&self, dispatch_id: i64, request_wire: &str) -> Option<String>;
  fn on_complete(&self, dispatch_id: i64, result_wire: &str);
  fn on_update_register(&self, callback_id: u32, types: &[String], scope: &str) -> Option<String>;
  fn on_update_unregister(&self, callback_id: u32);
  fn on_intercept_update_register(&self, callback_id: u32, types: &[String]) -> Option<String>;
  fn on_intercept_update_unregister(&self, callback_id: u32);
  fn on_update_verdict(&self, dispatch_id: i64, deliver: bool);
}

pub(crate) struct PendingSettle {
  pub(crate) resolve: Persistent<Function<'static>>,
  pub(crate) reject: Persistent<Function<'static>>,
}

impl PendingSettle {
  pub(crate) fn new<'js>(ctx: &Ctx<'js>) -> JsResult<(rquickjs::Promise<'js>, Self)> {
    let (promise, resolve, reject) = rquickjs::Promise::new(ctx)?;
    Ok((
      promise,
      PendingSettle {
        resolve: Persistent::save(ctx, resolve),
        reject: Persistent::save(ctx, reject),
      },
    ))
  }

  pub(crate) fn reject_with(self, ctx: &Ctx<'_>, msg: &str) -> JsResult<()> {
    let error_val = match error::host_error_to_js(ctx, msg) {
      Ok(v) => v,
      Err(e) => {
        self.release(ctx);
        return Err(e);
      }
    };
    self.reject_with_value(ctx, error_val)
  }

  pub(crate) fn reject_with_value<'js>(self, ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<()> {
    let reject = self.reject.restore(ctx)?;
    let _ = self.resolve.restore(ctx);
    reject.call::<_, Value>((value,))?;
    Ok(())
  }

  pub(crate) fn resolve_with<'js>(self, ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<()> {
    let resolve = self.resolve.restore(ctx)?;
    let _ = self.reject.restore(ctx);
    resolve.call::<_, Value>((value,))?;
    Ok(())
  }

  pub(crate) fn release(self, ctx: &Ctx<'_>) {
    let _ = self.resolve.restore(ctx);
    let _ = self.reject.restore(ctx);
  }
}

const CHAIN_TIMEOUT_TEXT: &str = "INTERCEPTOR_TIMEOUT";

#[derive(Default)]
struct DispatchState {
  called: Cell<bool>,
  settled: Cell<bool>,
  abandoned: Cell<bool>,
  timed_out: Cell<bool>,
  want_passthrough: Cell<bool>,
  next_response: RefCell<Option<String>>,
  next_resolvers: RefCell<Option<PendingSettle>>,
}

#[derive(Clone)]
struct UpdateReg {
  callback: Persistent<Function<'static>>,
  types: Rc<[String]>,
}

const DEMUX_EVENTS: [(&str, &str, &[&str]); 3] = [
  ("onNewMessage", "new_message", &["updateNewMessage", "updateNewChannelMessage"]),
  ("onMessageEdited", "edit_message", &["updateEditMessage", "updateEditChannelMessage"]),
  ("onMessageDeleted", "delete_message", &["updateDeleteMessages", "updateDeleteChannelMessages"]),
];

pub const ANY_ACCOUNT: i32 = -1;

const EVENTS_PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/events.qbc"));
const SEND_PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/send_message.qbc"));

const SEND_METHODS: [&str; 4] =
  ["messages.sendMessage", "messages.sendMedia", "messages.sendMultiMedia", "messages.editMessage"];

const SEND_SCOPE: &str = "interceptSendMessage";

#[derive(Default)]
struct UpdateDispatchState {
  settled: Cell<bool>,
}

pub struct RpcState {
  host: Rc<dyn RpcHost>,
  tl: Rc<TlViews>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  accounts: Option<Rc<AccountState>>,
  pub(crate) log: Log,
  next_invoke_id: Cell<i64>,
  intercept_fns: CallbackRegistry,
  update_fns: Registry<UpdateReg>,
  intercept_update_fns: Registry<UpdateReg>,
  demux: RefCell<Option<Persistent<Function<'static>>>>,
  send_wrap: RefCell<Option<Persistent<Function<'static>>>>,
  regexp_ctor: RefCell<Option<Persistent<Object<'static>>>>,
  promise: RefCell<Option<PromiseTools>>,
  dispatches: RefCell<HashMap<i64, Rc<DispatchState>>>,
  update_dispatches: RefCell<HashMap<i64, Rc<UpdateDispatchState>>>,
  pending_invoke: RefCell<HashMap<i64, PendingSettle>>,
}

impl RpcState {
  fn alloc_invoke_id(&self) -> i64 {
    let id = self.next_invoke_id.get();
    self.next_invoke_id.set(id + 1);
    id
  }
}

impl RpcState {
  fn insert_dispatch(&self, dispatch_id: i64, dstate: Rc<DispatchState>) {
    self.dispatches.borrow_mut().insert(dispatch_id, dstate);
    self.sync_blocking();
  }

  fn remove_dispatch(&self, dispatch_id: i64) -> Option<Rc<DispatchState>> {
    let removed = self.dispatches.borrow_mut().remove(&dispatch_id);
    self.sync_blocking();
    removed
  }

  fn drain_dispatches(&self) -> Vec<(i64, Rc<DispatchState>)> {
    let drained: Vec<_> = self.dispatches.borrow_mut().drain().collect();
    self.sync_blocking();
    drained
  }

  fn insert_update_dispatch(&self, dispatch_id: i64, ustate: Rc<UpdateDispatchState>) {
    self.update_dispatches.borrow_mut().insert(dispatch_id, ustate);
    self.sync_blocking();
  }

  fn remove_update_dispatch(&self, dispatch_id: i64) -> Option<Rc<UpdateDispatchState>> {
    let removed = self.update_dispatches.borrow_mut().remove(&dispatch_id);
    self.sync_blocking();
    removed
  }

  fn drain_update_dispatches(&self) {
    self.update_dispatches.borrow_mut().clear();
    self.sync_blocking();
  }

  fn sync_blocking(&self) {
    let count = self.dispatches.borrow().len() + self.update_dispatches.borrow().len();
    self.lifecycle.set_blocking_dispatches(count);
  }
}

pub(crate) fn make_rpc_error<'js>(ctx: &Ctx<'js>, code: i32, text: &str) -> JsResult<Value<'js>> {
  crate::api::Globals::get(ctx)?.get_rpc_error(ctx)?.construct((code, text))
}

fn rpc_error_to_wire<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> Option<String> {
  let obj = value.as_object()?;
  let ctor = crate::api::Globals::get(ctx).ok()?.get_rpc_error(ctx).ok()?;
  if !obj.is_instance_of(ctor.into_value()) {
    return None;
  }
  let code = obj.get::<_, i32>("code").ok()?;
  let text = obj.get::<_, String>("text").ok()?;
  Some(proxy::encode_rpc_error(code, &text))
}

fn thrown_to_result_wire<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> String {
  rpc_error_to_wire(ctx, value).unwrap_or_else(|| proxy::encode_error(&error_value_to_string(ctx, value)))
}

fn describe_stage_failure<'js>(ctx: &Ctx<'js>, what: std::fmt::Arguments, value: &Value<'js>) -> String {
  let detail = format_thrown(ctx, value);
  if rpc_error_to_wire(ctx, value).is_some() {
    format!("{what}: {detail}")
  } else {
    crate::fault(format_args!("{what}: {detail}"))
  }
}

impl PendingSettle {
  fn settle_from_wire<'js>(self, ctx: &Ctx<'js>, tl: &Rc<TlViews>, wire: &str, life: ViewLife) -> JsResult<()> {
    let built = match error::wire_error_to_js(ctx, wire) {
      Some(value) => value.map(|v| (v, true)),
      None => tl.wire_to_js_value(ctx, wire, life).map(|v| (v, false)),
    };
    match built {
      Ok((value, is_error)) => {
        if is_error {
          self.reject_with_value(ctx, value)
        } else {
          self.resolve_with(ctx, value)
        }
      }
      Err(e) => {
        self.release(ctx);
        Err(e)
      }
    }
  }
}

impl RpcState {
  fn resolve_and_then<'js>(
    &self,
    ctx: &Ctx<'js>,
    result: Value<'js>,
    ok: Function<'js>,
    err: Function<'js>,
  ) -> JsResult<()> {
    use rquickjs::function::This;
    let Some(tools) = self.promise.borrow().clone() else {
      return Err(Exception::throw_message(ctx, "the promise machinery was not installed"));
    };
    let ctor = tools.ctor.restore(ctx)?;
    let resolved: Value = tools.resolve.restore(ctx)?.call((This(ctor), result))?;
    tools.then.restore(ctx)?.call::<_, Value>((This(resolved), ok, err))?;
    Ok(())
  }
}

#[derive(Clone)]
struct PromiseTools {
  ctor: Persistent<Object<'static>>,
  resolve: Persistent<Function<'static>>,
  then: Persistent<Function<'static>>,
}

fn capture_promise_tools(ctx: &Ctx<'_>) -> JsResult<PromiseTools> {
  let ctor: Object = ctx.globals().get("Promise")?;
  let resolve: Function = ctor.get("resolve")?;
  let then: Function = ctor.get::<_, Object>("prototype")?.get("then")?;
  Ok(PromiseTools {
    ctor: Persistent::save(ctx, ctor),
    resolve: Persistent::save(ctx, resolve),
    then: Persistent::save(ctx, then),
  })
}

pub fn pump_jobs(rt: &Runtime, context: &rquickjs::Context, log: &dyn Fn(&str)) {
  loop {
    match rt.execute_pending_job() {
      Ok(true) => continue,
      Ok(false) => break,
      Err(e) => {
        log(&format!("unhandled error running microtask: {e:?}"));
        break;
      }
    }
  }
  context.with(|ctx| error::report_rejections(&ctx));
}

#[allow(clippy::too_many_arguments)]
pub fn install_rpc<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn RpcHost>,
  tl: Rc<TlViews>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  accounts: Option<Rc<AccountState>>,
  shared: Object<'js>,
  log: Log,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<RpcState>> {
  let state = Rc::new(RpcState {
    host,
    tl,
    grants,
    lifecycle,
    accounts,
    log,
    next_invoke_id: Cell::new(1),
    intercept_fns: CallbackRegistry::default(),
    update_fns: Registry::default(),
    intercept_update_fns: Registry::default(),
    demux: RefCell::new(None),
    send_wrap: RefCell::new(None),
    regexp_ctor: RefCell::new(Some(Persistent::save(ctx, ctx.globals().get::<_, Object>("RegExp")?))),
    promise: RefCell::new(Some(capture_promise_tools(ctx)?)),
    dispatches: RefCell::new(HashMap::new()),
    update_dispatches: RefCell::new(HashMap::new()),
    pending_invoke: RefCell::new(HashMap::new()),
  });

  globals.set_rpc_error(ctx.eval(
    r"(class RpcError extends Error {
        constructor(code, text) {
          super(code + ': ' + text);
          this.name = 'RpcError';
          this.code = code | 0;
          this.text = String(text ?? '');
        }
    })",
  )?)?;

  let state2 = state.clone();
  globals.inu.set(
    "interceptRpc",
    Function::new(
      ctx.clone(),
      move |ctx: Ctx<'js>, methods: Value<'js>, cb: Function<'js>, options: Opt<Value<'js>>| {
        let ctx: &Ctx<'js> = &ctx;
        if state2.lifecycle.is_unloading() {
          return noop_disposer(ctx);
        }
        let list = read_name_list(ctx, "interceptRpc", methods, "method")?;
        for method in &list {
          state2.grants.check_grant(ctx, "interceptRpc", Some(method), MATCH_EXACT)?;
        }
        let strict = match options.0 {
          None => false,
          Some(options) => {
            let Some(options) = options.as_object() else {
              return Err(Exception::throw_type(ctx, "interceptRpc: options must be an object"));
            };
            let strict: Value = options.get("strict")?;
            if strict.is_undefined() {
              false
            } else {
              strict
                .as_bool()
                .ok_or_else(|| Exception::throw_type(ctx, "interceptRpc: options.strict must be a boolean"))?
            }
          }
        };
        state2.register_intercept(ctx, list, "", strict, "", cb)
      },
    )?,
  )?;

  let state2 = state.clone();
  globals.inu.set(
    "invokeRpc",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, obj: Value<'js>| state2.js_invoke_rpc(&ctx, ANY_ACCOUNT, obj))?,
  )?;

  let state2 = state.clone();
  globals.inu.set(
    "onUpdate",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, types: Value<'js>, cb: Function<'js>| {
      state2.js_on_update(&ctx, types, cb)
    })?,
  )?;

  let state2 = state.clone();
  globals.inu.set(
    "interceptUpdate",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, types: Value<'js>, cb: Function<'js>| {
      state2.js_intercept_update(&ctx, types, cb)
    })?,
  )?;

  state.install_demuxed_events(ctx, globals)?;
  state.install_send_message(ctx, globals, shared)?;
  state.install_account_invoke(ctx)?;
  Ok(state)
}

impl RpcState {
  fn install_account_invoke<'js>(self: &Rc<Self>, ctx: &Ctx<'js>) -> JsResult<()> {
    let state = self;
    let Some(accounts) = state.accounts.clone() else {
      return Ok(());
    };
    let prototype = Object::new(ctx.clone())?;
    let state2 = state.clone();
    prototype.set(
      "invokeRpc",
      Function::new(
        ctx.clone(),
        move |ctx: Ctx<'js>, this: This<Value<'js>>, obj: Value<'js>| -> JsResult<Value<'js>> {
          let slot = this
            .0
            .as_object()
            .and_then(|handle| handle.get::<_, Value>("id").ok())
            .and_then(|id| id.as_number())
            .filter(|id| id.fract() == 0.0 && *id >= 0.0)
            .map(|id| id as i32);
          let Some(slot) = slot else {
            return PluginErrorCode::InvalidArgument
              .throw(&ctx, "invokeRpc: not called on an account handle; use inu.account().invokeRpc(...)");
          };
          state2.js_invoke_rpc(&ctx, slot, obj)
        },
      )?,
    )?;
    if let Some(inner) = accounts.take_prototype(ctx) {
      prototype.set_prototype(Some(&inner))?;
    }
    let object_ctor: Object = ctx.globals().get("Object")?;
    let freeze: Function = object_ctor.get("freeze")?;
    freeze.call::<_, Value>((prototype.clone(),))?;
    accounts.set_prototype(ctx, &prototype);
    Ok(())
  }

  fn install_send_message<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    globals: &crate::api::Globals<'js>,
    shared: Object<'js>,
  ) -> JsResult<()> {
    let factory = prelude::load(ctx, SEND_PRELUDE)?;
    let plugin_error = globals.plugin_error.clone();
    let rpc_error = globals.get_rpc_error(ctx)?;
    let accounts = self.accounts.clone();
    let self_user_id = Function::new(ctx.clone(), move |account_id: i32| {
      accounts.as_ref().and_then(|accounts| accounts.self_user_id(account_id)).map(|id| id as f64)
    })?;
    let build: Function = factory.call((shared, plugin_error, rpc_error, self_user_id))?;
    *self.send_wrap.borrow_mut() = Some(Persistent::save(ctx, build));

    let state = self.clone();
    globals.inu.set(
      "interceptSendMessage",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, first: Value<'js>, second: Opt<Value<'js>>| {
        let ctx: &Ctx<'js> = &ctx;
        if state.lifecycle.is_unloading() {
          return noop_disposer(ctx);
        }
        state.grants.check_grant(ctx, SEND_SCOPE, None, MATCH_EXACT)?;
        let (filter_json, cb) = match second.0 {
          Some(callback) => {
            let Some(callback) = callback.into_function() else {
              return Err(Exception::throw_type(ctx, "interceptSendMessage: middleware must be a function"));
            };
            let Some(filter) = first.as_object() else {
              return Err(Exception::throw_type(ctx, "interceptSendMessage: filter must be an object"));
            };
            let encoded = Object::new(ctx.clone())?;
            let is_edit: Value = filter.get("isEdit")?;
            if !is_edit.is_undefined() {
              let Some(is_edit) = is_edit.as_bool() else {
                return Err(Exception::throw_type(ctx, "interceptSendMessage: filter.isEdit must be a boolean"));
              };
              encoded.set("isEdit", is_edit)?;
            }
            let text: Value = filter.get("text")?;
            if !text.is_undefined() {
              let Some(text) = text.as_object() else {
                return Err(Exception::throw_type(ctx, "interceptSendMessage: filter.text must be a RegExp"));
              };
              let regexp = state
                .regexp_ctor
                .borrow()
                .as_ref()
                .ok_or_else(|| Exception::throw_type(ctx, "interceptSendMessage is not installed"))?
                .clone()
                .restore(ctx)?;
              if !text.is_instance_of(regexp.into_value()) {
                return Err(Exception::throw_type(ctx, "interceptSendMessage: filter.text must be a RegExp"));
              }
              let source: String = text
                .get("source")
                .map_err(|_| Exception::throw_type(ctx, "interceptSendMessage: filter.text must be a RegExp"))?;
              let flags: String = text
                .get("flags")
                .map_err(|_| Exception::throw_type(ctx, "interceptSendMessage: filter.text must be a RegExp"))?;
              let regex = Object::new(ctx.clone())?;
              regex.set("source", source)?;
              regex.set("flags", flags)?;
              encoded.set("text", regex)?;
            }
            let json = ctx
              .json_stringify(encoded)?
              .map(|value| value.to_string())
              .transpose()?
              .unwrap_or_else(|| "{}".to_string());
            (json, callback)
          }
          None => {
            let Some(callback) = first.into_function() else {
              return Err(Exception::throw_type(ctx, "interceptSendMessage: middleware must be a function"));
            };
            (String::new(), callback)
          }
        };
        let build = match state.send_wrap.borrow().as_ref() {
          Some(build) => build.clone().restore(ctx)?,
          None => return Err(Exception::throw_type(ctx, "interceptSendMessage is not installed")),
        };
        let middleware: Function = build.call((cb,))?;
        let list = SEND_METHODS.iter().map(ToString::to_string).collect();
        state.register_intercept(ctx, list, SEND_SCOPE, true, &filter_json, middleware)
      })?,
    )?;
    Ok(())
  }

  fn install_demuxed_events<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, globals: &crate::api::Globals<'js>) -> JsResult<()> {
    let factory = prelude::load(ctx, EVENTS_PRELUDE)?;
    let message = globals.get_message(ctx)?;
    if !message.is_function() {
      return Err(Exception::throw_type(ctx, "the demuxed events need the inu.Message installApi installs"));
    }
    let build: Function = factory.call((message,))?;
    *self.demux.borrow_mut() = Some(Persistent::save(ctx, build));

    for (name, kind, types) in DEMUX_EVENTS {
      let state = self.clone();
      globals.inu.set(
        name,
        Function::new(ctx.clone(), move |ctx: Ctx<'js>, cb: Function<'js>| state.js_on_demuxed(&ctx, kind, types, cb))?,
      )?;
    }
    Ok(())
  }
}

fn read_name_list<'js>(ctx: &Ctx<'js>, what: &str, names: Value<'js>, noun: &str) -> JsResult<Vec<String>> {
  let list: Vec<String> = if let Some(s) = names.as_string() {
    vec![s.to_string()?]
  } else if let Some(arr) = names.as_array() {
    let mut out = Vec::new();
    for item in crate::utils::arguments::array_values(ctx, arr, what)? {
      let s = item
        .as_string()
        .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: {noun} list must contain only strings")))?
        .to_string()?;
      out.push(s);
    }
    out
  } else {
    return Err(Exception::throw_type(ctx, &format!("{what}: {noun} must be a string or an array of strings")));
  };
  if list.is_empty() {
    return Err(Exception::throw_type(ctx, &format!("{what}: {noun} list must not be empty")));
  }
  Ok(list)
}

impl RpcState {
  fn register_intercept<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    methods: Vec<String>,
    scope: &str,
    strict: bool,
    filter_json: &str,
    middleware: Function<'js>,
  ) -> JsResult<Function<'js>> {
    let callback_id = self.intercept_fns.alloc();
    if let Some(err) = self.host.on_register(&methods, callback_id, scope, strict, filter_json) {
      return Err(ctx.throw(error::host_error_to_js(ctx, &err)?));
    }
    self.intercept_fns.register(ctx, callback_id, None, middleware);

    let state = self.clone();
    make_disposer(ctx, move |ctx| {
      if state.intercept_fns.dispose(ctx, callback_id) {
        state.host.on_unregister(callback_id);
      }
    })
  }
}

fn read_method_name<'js>(ctx: &Ctx<'js>, obj: &Value<'js>) -> JsResult<String> {
  let name = match obj.as_object() {
    Some(obj) => obj.get::<_, Value>("_")?,
    None => Value::new_undefined(ctx.clone()),
  };
  match name.as_string().and_then(|s| s.to_string().ok()) {
    Some(name) => Ok(name),
    None => PluginErrorCode::InvalidArgument.throw(ctx, "invokeRpc: the request must carry its method name in '_'"),
  }
}

impl RpcState {
  fn js_invoke_rpc<'js>(&self, ctx: &Ctx<'js>, slot: i32, obj: Value<'js>) -> JsResult<Value<'js>> {
    let method = read_method_name(ctx, &obj)?;
    self.grants.check_grant(ctx, "invokeRpc", Some(&method), MATCH_EXACT)?;

    let wire = proxy::js_value_to_wire(ctx, obj)?;
    let invoke_id = self.alloc_invoke_id();
    let (promise, pending) = PendingSettle::new(ctx)?;
    self.pending_invoke.borrow_mut().insert(invoke_id, pending);

    if let Some(err) = self.host.on_invoke(invoke_id, slot, &wire) {
      if let Some(pending) = self.pending_invoke.borrow_mut().remove(&invoke_id) {
        pending.reject_with(ctx, &err)?;
      }
    }
    Ok(promise.into_value())
  }

  fn js_on_update<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    types: Value<'js>,
    cb: Function<'js>,
  ) -> JsResult<Function<'js>> {
    if self.lifecycle.is_unloading() {
      return noop_disposer(ctx);
    }
    let list = read_name_list(ctx, "onUpdate", types, "type")?;
    for name in &list {
      self.grants.check_grant(ctx, "onUpdate", Some(name), MATCH_EXACT)?;
    }
    self.register_update_listener(ctx, list, "", cb)
  }

  fn js_intercept_update<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    types: Value<'js>,
    cb: Function<'js>,
  ) -> JsResult<Function<'js>> {
    if self.lifecycle.is_unloading() {
      return noop_disposer(ctx);
    }
    let list = read_name_list(ctx, "interceptUpdate", types, "type")?;
    for name in &list {
      self.grants.check_grant(ctx, "interceptUpdate", Some(name), MATCH_EXACT)?;
    }
    let callback_id = self.intercept_update_fns.alloc();
    if let Some(err) = self.host.on_intercept_update_register(callback_id, &list) {
      return Err(ctx.throw(error::host_error_to_js(ctx, &err)?));
    }
    self.intercept_update_fns.insert(
      callback_id,
      None,
      UpdateReg {
        callback: Persistent::save(ctx, cb),
        types: list.into(),
      },
    );

    let state = self.clone();
    make_disposer(ctx, move |ctx| {
      if let Some(reg) = state.intercept_update_fns.remove(callback_id) {
        let _ = reg.callback.restore(ctx);
        state.host.on_intercept_update_unregister(callback_id);
      }
    })
  }

  fn js_on_demuxed<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    kind: &str,
    types: &[&str],
    cb: Function<'js>,
  ) -> JsResult<Function<'js>> {
    if self.lifecycle.is_unloading() {
      return noop_disposer(ctx);
    }
    self.grants.check_grant(ctx, "onUpdate", Some(kind), MATCH_EXACT)?;
    let build = match self.demux.borrow().as_ref() {
      Some(build) => build.clone().restore(ctx)?,
      None => return Err(Exception::throw_type(ctx, "the demuxed events are not installed")),
    };
    let listener: Function = build.call((kind, cb))?;
    self.register_update_listener(ctx, types.iter().map(ToString::to_string).collect(), kind, listener)
  }

  fn register_update_listener<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    types: Vec<String>,
    scope: &str,
    listener: Function<'js>,
  ) -> JsResult<Function<'js>> {
    let callback_id = self.update_fns.alloc();
    if let Some(err) = self.host.on_update_register(callback_id, &types, scope) {
      return Err(ctx.throw(error::host_error_to_js(ctx, &err)?));
    }
    self.update_fns.insert(
      callback_id,
      None,
      UpdateReg {
        callback: Persistent::save(ctx, listener),
        types: types.into(),
      },
    );

    let state = self.clone();
    make_disposer(ctx, move |ctx| {
      if let Some(reg) = state.update_fns.remove(callback_id) {
        let _ = reg.callback.restore(ctx);
        state.host.on_update_unregister(callback_id);
      }
    })
  }
}

impl RpcState {
  fn settle_update_verdict(&self, ustate: &Rc<UpdateDispatchState>, dispatch_id: i64, deliver: bool) {
    if ustate.settled.replace(true) {
      return;
    }
    self.remove_update_dispatch(dispatch_id);
    self.host.on_update_verdict(dispatch_id, deliver);
  }

  fn try_dispatch_update_intercept<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    callback_id: u32,
    dispatch_id: i64,
    type_name: &str,
    account_id: i32,
    update_wire: &str,
  ) -> JsResult<()> {
    let state = self;
    let ustate = Rc::new(UpdateDispatchState::default());
    let Some(reg) = state.intercept_update_fns.get(callback_id) else {
      (state.log)(&format!(
        "interceptUpdate({type_name}): dispatch {dispatch_id} names disposed interceptor {callback_id}, delivering"
      ));
      state.settle_update_verdict(&ustate, dispatch_id, true);
      return Ok(());
    };
    let middleware = reg.callback.restore(ctx)?;
    let update = state.tl.wire_to_js_value(ctx, update_wire, ViewLife::Dispatch)?;
    let account = dispatch_account(ctx, &state.accounts, account_id)?;

    state.insert_update_dispatch(dispatch_id, ustate.clone());
    let call_result = middleware.call::<_, Value>((update, account));
    let result_value = match call_result {
      Ok(v) => v,
      Err(rquickjs::Error::Exception) => {
        let caught = ctx.catch();
        (state.log)(&crate::fault(format_args!(
          "interceptUpdate({type_name}) middleware threw, delivering: {}",
          format_thrown(ctx, &caught)
        )));
        state.settle_update_verdict(&ustate, dispatch_id, true);
        return Ok(());
      }
      Err(e) => return Err(e),
    };

    let ok_fn = {
      let state = state.clone();
      let ustate = ustate.clone();
      let type_name = type_name.to_string();
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| {
        let verdict = value.as_string().and_then(|s| s.to_string().ok());
        let deliver = match verdict.as_deref() {
          Some("deliver") => true,
          Some("drop") => false,
          _ => {
            (state.log)(&crate::fault(format_args!(
              "interceptUpdate({type_name}) middleware returned {}, delivering: expected 'deliver' or 'drop'",
              format_thrown(&ctx, &value)
            )));
            true
          }
        };
        state.settle_update_verdict(&ustate, dispatch_id, deliver);
      })?
    };
    let err_fn = {
      let state = state.clone();
      let ustate = ustate.clone();
      let type_name = type_name.to_string();
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| {
        (state.log)(&crate::fault(format_args!(
          "interceptUpdate({type_name}) middleware rejected, delivering: {}",
          format_thrown(&ctx, &value)
        )));
        state.settle_update_verdict(&ustate, dispatch_id, true);
      })?
    };
    state.resolve_and_then(ctx, result_value, ok_fn, err_fn)
  }

  fn settle_update_verdict_after_removal(&self, ustate: &Rc<UpdateDispatchState>, dispatch_id: i64) {
    if ustate.settled.replace(true) {
      return;
    }
    self.host.on_update_verdict(dispatch_id, true);
  }

  fn complete_dispatch(&self, ctx: &Ctx<'_>, dstate: &Rc<DispatchState>, dispatch_id: i64, result_wire: &str) {
    if dstate.settled.replace(true) {
      if dstate.abandoned.get() {
        (self.log)(&format!("interceptRpc: dispatch {dispatch_id} settled after being abandoned, result dropped"));
      }
      return;
    }
    self.remove_dispatch(dispatch_id);
    if let Some(pending) = dstate.next_resolvers.borrow_mut().take() {
      pending.release(ctx);
    }
    self.host.on_complete(dispatch_id, result_wire);
  }

  fn passthrough_dispatch(&self, ctx: &Ctx<'_>, dispatch_id: i64, request_wire: &str) {
    let dstate = Rc::new(DispatchState::default());
    dstate.called.set(true);
    dstate.want_passthrough.set(true);
    self.insert_dispatch(dispatch_id, dstate.clone());
    if let Some(err) = self.host.on_next(dispatch_id, request_wire) {
      self.complete_dispatch(ctx, &dstate, dispatch_id, &error::host_error_to_wire(&err));
    }
  }

  fn try_dispatch_rpc<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    callback_id: u32,
    dispatch_id: i64,
    method: &str,
    account_id: i32,
    request_wire: &str,
  ) -> JsResult<()> {
    let state = self;
    let Some(middleware) = state.intercept_fns.restore(ctx, callback_id) else {
      (state.log)(&format!(
        "interceptRpc({method}): dispatch {dispatch_id} names disposed interceptor {callback_id}, passing through"
      ));
      state.passthrough_dispatch(ctx, dispatch_id, request_wire);
      return Ok(());
    };
    let request_value = state.tl.wire_to_js_value(ctx, request_wire, ViewLife::Dispatch)?;

    let dstate = Rc::new(DispatchState::default());
    state.insert_dispatch(dispatch_id, dstate.clone());

    let next_fn = {
      let state = state.clone();
      let dstate = dstate.clone();
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, req: Value<'js>| -> JsResult<Value<'js>> {
        if dstate.abandoned.get() {
          let (code, message) = if dstate.timed_out.get() {
            (
              error::PluginErrorCode::TimedOut,
              "next(): the interceptor chain's budget expired and this stage was abandoned",
            )
          } else {
            (
              error::PluginErrorCode::Aborted,
              "next(): the interceptor chain was torn down and this stage was abandoned",
            )
          };
          return code.throw(&ctx, message);
        }
        if dstate.settled.get() {
          return PluginErrorCode::InvalidArgument.throw(&ctx, "next(): this dispatch already settled");
        }
        if dstate.called.replace(true) {
          return Err(Exception::throw_type(&ctx, "next() may only be called once"));
        }
        let wire = proxy::js_value_to_wire(&ctx, req)?;
        let (promise, pending) = PendingSettle::new(&ctx)?;
        *dstate.next_resolvers.borrow_mut() = Some(pending);
        if let Some(err) = state.host.on_next(dispatch_id, &wire) {
          if let Some(pending) = dstate.next_resolvers.borrow_mut().take() {
            pending.reject_with(&ctx, &err)?;
          }
        }
        Ok(promise.into_value())
      })?
    };

    let account = dispatch_account(ctx, &state.accounts, account_id)?;
    let call_result = middleware.call::<_, Value>((request_value, next_fn, account));
    let result_value = match call_result {
      Ok(v) => v,
      Err(rquickjs::Error::Exception) => {
        let caught = ctx.catch();
        (state.log)(&describe_stage_failure(ctx, format_args!("interceptRpc({method}) callback threw"), &caught));
        let wire = thrown_to_result_wire(ctx, &caught);
        state.complete_dispatch(ctx, &dstate, dispatch_id, &wire);
        return Ok(());
      }
      Err(e) => return Err(e),
    };

    let ok_fn = {
      let state = state.clone();
      let dstate = dstate.clone();
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| -> JsResult<()> {
        if value.is_undefined() {
          if dstate.called.get() {
            let stored = dstate.next_response.borrow().clone();
            match stored {
              Some(wire) => state.complete_dispatch(&ctx, &dstate, dispatch_id, &wire),
              None => dstate.want_passthrough.set(true),
            }
          } else {
            state.complete_dispatch(&ctx, &dstate, dispatch_id, &proxy::encode_error("middleware returned undefined"));
          }
        } else {
          let wire = match rpc_error_to_wire(&ctx, &value) {
            Some(wire) => wire,
            None => proxy::js_value_to_wire(&ctx, value)?,
          };
          state.complete_dispatch(&ctx, &dstate, dispatch_id, &wire);
        }
        Ok(())
      })?
    };
    let err_fn = {
      let state = state.clone();
      let dstate = dstate.clone();
      let method = method.to_string();
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| -> JsResult<()> {
        (state.log)(&describe_stage_failure(&ctx, format_args!("interceptRpc({method}) callback rejected"), &value));
        let wire = thrown_to_result_wire(&ctx, &value);
        state.complete_dispatch(&ctx, &dstate, dispatch_id, &wire);
        Ok(())
      })?
    };

    state.resolve_and_then(ctx, result_value, ok_fn, err_fn)
  }
}

impl RpcState {
  pub fn resolve_invoke(self: &Rc<Self>, rt: &Runtime, context: &rquickjs::Context, invoke_id: i64, result_wire: &str) {
    let state = self;
    context.with(|ctx| {
      if let Some(pending) = state.pending_invoke.borrow_mut().remove(&invoke_id) {
        if let Err(e) = pending.settle_from_wire(&ctx, &state.tl, result_wire, ViewLife::Plugin) {
          (state.log)(&format!("resolveInvoke({invoke_id}) failed: {e:?}"));
        }
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  pub fn dispatch_update(
    self: &Rc<Self>,
    rt: &Runtime,
    context: &rquickjs::Context,
    type_name: &str,
    account_id: i32,
    update_wire: &str,
  ) {
    let state = self;
    context.with(|ctx| {
      let value = match state.tl.wire_to_js_value(&ctx, update_wire, ViewLife::Plugin) {
        Ok(v) => v,
        Err(e) => {
          let msg = match e {
            rquickjs::Error::Exception => format_exception(&ctx),
            other => other.to_string(),
          };
          (state.log)(&format!("dispatchUpdate: bad update wire: {msg}"));
          return;
        }
      };
      let listening: Vec<UpdateReg> = state
        .update_fns
        .values()
        .into_iter()
        .filter(|reg| reg.types.iter().any(|t| t == type_name))
        .collect();
      if listening.is_empty() {
        return;
      }
      let account = match dispatch_account(&ctx, &state.accounts, account_id) {
        Ok(v) => v,
        Err(e) => {
          (state.log)(&format!("dispatchUpdate: cannot build the account handle: {e:?}"));
          return;
        }
      };
      for reg in listening {
        let Ok(f) = reg.callback.restore(&ctx) else {
          continue;
        };
        match f.call::<_, Value>((value.clone(), account.clone())) {
          Ok(_) => {}
          Err(rquickjs::Error::Exception) => {
            (state.log)(&crate::fault(format_args!("onUpdate callback threw: {}", format_exception(&ctx))));
          }
          Err(e) => {
            (state.log)(&format!("onUpdate callback failed: {e:?}"));
          }
        }
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  #[allow(clippy::too_many_arguments)]
  pub fn dispatch_update_intercept(
    self: &Rc<Self>,
    rt: &Runtime,
    context: &rquickjs::Context,
    callback_id: u32,
    dispatch_id: i64,
    type_name: &str,
    account_id: i32,
    update_wire: &str,
  ) {
    let state = self;
    context.with(|ctx| {
      if let Err(e) =
        state.try_dispatch_update_intercept(&ctx, callback_id, dispatch_id, type_name, account_id, update_wire)
      {
        let msg = match e {
          rquickjs::Error::Exception => format_exception(&ctx),
          other => other.to_string(),
        };
        (state.log)(&format!("interceptUpdate({type_name}) dispatch failed, delivering: {msg}"));
        if let Some(ustate) = state.remove_update_dispatch(dispatch_id) {
          state.settle_update_verdict_after_removal(&ustate, dispatch_id);
        } else {
          state.host.on_update_verdict(dispatch_id, true);
        }
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  pub fn abandon_update_dispatch(self: &Rc<Self>, rt: &Runtime, context: &rquickjs::Context, dispatch_id: i64) {
    let state = self;
    context.with(|_ctx| {
      if let Some(ustate) = state.remove_update_dispatch(dispatch_id) {
        ustate.settled.set(true);
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  #[allow(clippy::too_many_arguments)]
  pub fn dispatch(
    self: &Rc<Self>,
    rt: &Runtime,
    context: &rquickjs::Context,
    callback_id: u32,
    dispatch_id: i64,
    method: &str,
    account_id: i32,
    request_wire: &str,
  ) {
    let state = self;
    context.with(|ctx| {
      if let Err(e) = state.try_dispatch_rpc(&ctx, callback_id, dispatch_id, method, account_id, request_wire) {
        let msg = match e {
          rquickjs::Error::Exception => format_exception(&ctx),
          other => other.to_string(),
        };
        (state.log)(&format!("interceptRpc({method}) dispatch failed: {msg}"));
        let wire = proxy::encode_error(&msg);
        let dstate = state.dispatches.borrow().get(&dispatch_id).cloned();
        match dstate {
          Some(dstate) => state.complete_dispatch(&ctx, &dstate, dispatch_id, &wire),
          None => state.host.on_complete(dispatch_id, &wire),
        }
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  pub fn complete_next(
    self: &Rc<Self>,
    rt: &Runtime,
    context: &rquickjs::Context,
    dispatch_id: i64,
    result_wire: &str,
  ) {
    let state = self;
    context.with(|ctx| {
      let dstate = match state.dispatches.borrow().get(&dispatch_id).cloned() {
        Some(d) => d,
        None => return,
      };
      *dstate.next_response.borrow_mut() = Some(result_wire.to_string());

      if let Some(pending) = dstate.next_resolvers.borrow_mut().take() {
        if let Err(e) = pending.settle_from_wire(&ctx, &state.tl, result_wire, ViewLife::Dispatch) {
          (state.log)(&format!("completeNext({dispatch_id}) failed to settle next(): {e:?}"));
        }
      }

      if dstate.want_passthrough.get() {
        state.complete_dispatch(&ctx, &dstate, dispatch_id, result_wire);
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  pub fn abandon_dispatch(
    self: &Rc<Self>,
    rt: &Runtime,
    context: &rquickjs::Context,
    dispatch_id: i64,
    reason_wire: &str,
  ) {
    let state = self;
    context.with(|ctx| {
      let removed = state.remove_dispatch(dispatch_id);
      let Some(dstate) = removed else { return };
      dstate.abandoned.set(true);
      dstate
        .timed_out
        .set(proxy::wire_rpc_error(reason_wire).is_some_and(|(_, text)| text == CHAIN_TIMEOUT_TEXT));
      dstate.settled.set(true);

      let pending = dstate.next_resolvers.borrow_mut().take();
      if let Some(pending) = pending {
        if let Err(e) = pending.reject_with(&ctx, reason_wire) {
          (state.log)(&format!("abandonDispatch({dispatch_id}) failed to reject next(): {e:?}"));
        }
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  pub fn dispose(self: &Rc<Self>, context: &rquickjs::Context) {
    let state = self;
    context.with(|ctx| {
      state.intercept_fns.release_all(&ctx);
      for reg in state.update_fns.remove_matching(|_| true) {
        let _ = reg.callback.restore(&ctx);
      }
      for reg in state.intercept_update_fns.remove_matching(|_| true) {
        let _ = reg.callback.restore(&ctx);
      }
      if let Some(build) = state.demux.borrow_mut().take() {
        let _ = build.restore(&ctx);
      }
      if let Some(build) = state.send_wrap.borrow_mut().take() {
        let _ = build.restore(&ctx);
      }
      if let Some(regexp) = state.regexp_ctor.borrow_mut().take() {
        let _ = regexp.restore(&ctx);
      }
      if let Some(tools) = state.promise.borrow_mut().take() {
        let _ = tools.ctor.restore(&ctx);
        let _ = tools.resolve.restore(&ctx);
        let _ = tools.then.restore(&ctx);
      }
      if let Some(accounts) = state.accounts.as_ref() {
        let _ = accounts.take_prototype(&ctx);
      }
      state.drain_update_dispatches();
      for (_, pending) in state.pending_invoke.borrow_mut().drain() {
        pending.release(&ctx);
      }
      for (_, dstate) in state.drain_dispatches() {
        if let Some(pending) = dstate.next_resolvers.borrow_mut().take() {
          pending.release(&ctx);
        }
      }
    });
  }
}

#[cfg(test)]
#[path = "rpc_tests.rs"]
mod tests;

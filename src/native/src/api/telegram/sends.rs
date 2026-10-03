use std::rc::Rc;

use rquickjs::function::Opt;
use rquickjs::{Ctx, Exception, Function, Object, Persistent, Result as JsResult, Value};

use super::{Abandon, RpcState, UpdateDispatchState};
use crate::api::error::{self, describe_js_error, error_value_to_string, format_thrown};
use crate::api::telegram::account::dispatch_account;
use crate::api::tl::proxy;
use crate::runtime::{enter_js, pump_jobs};
use crate::sandbox::grants::MATCH_EXACT;
use crate::sandbox::registry::{make_disposer, noop_disposer};
use crate::utils::arguments::stringify_json;
use crate::utils::qjs::{qjs_is_regexp, qjs_load_prelude, resolve_and_then};

const SEND_PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/send_message.qbc"));
const SEND_GRANT: &str = "interceptSendMessage";
const MAX_SAFE_INTEGER: f64 = 9_007_199_254_740_991.0;
const PEER_TYPES: &[&str] = &["user", "group", "broadcast"];
const MEDIA_KINDS: &[&str] = &[
  "photo",
  "video",
  "roundVideo",
  "voice",
  "music",
  "sticker",
  "gif",
  "document",
  "poll",
  "contact",
  "location",
  "venue",
  "story",
  "giveaway",
  "invoice",
  "other",
];

impl RpcState {
  pub(super) fn install_send_message<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    globals: &crate::api::Globals<'js>,
    shared: Object<'js>,
  ) -> JsResult<()> {
    let factory = qjs_load_prelude(ctx, SEND_PRELUDE)?;
    let wrap: Function = factory.call((shared,))?;
    *self.send_wrap.borrow_mut() = Some(Persistent::save(ctx, wrap));

    let state = self.clone();
    globals.inu.set(
      "interceptSendMessage",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, first: Value<'js>, second: Opt<Value<'js>>| {
        let ctx: &Ctx<'js> = &ctx;
        if state.lifecycle.is_unloading() {
          return noop_disposer(ctx);
        }
        state.grants.check_grant(ctx, SEND_GRANT, None, MATCH_EXACT)?;
        let (filter_json, callback) = match second.0 {
          Some(callback) => (read_filter(ctx, &first)?, callback),
          None => (String::new(), first),
        };
        let Some(callback) = callback.into_function() else {
          return Err(Exception::throw_type(ctx, "interceptSendMessage: middleware must be a function"));
        };
        let wrap = match state.send_wrap.borrow().as_ref() {
          Some(wrap) => wrap.clone().restore(ctx)?,
          None => return Err(Exception::throw_type(ctx, "interceptSendMessage is not installed")),
        };
        let middleware: Function = wrap.call((callback,))?;
        let callback_id = state.send_fns.alloc();
        if let Some(err) = state.host.on_send_register(callback_id, &filter_json) {
          return Err(ctx.throw(error::host_error_to_js(ctx, &err)?));
        }
        state.send_fns.register(ctx, callback_id, middleware);
        let state = state.clone();
        make_disposer(ctx, move |ctx| {
          if state.send_fns.dispose(ctx, callback_id) {
            state.host.on_send_unregister(callback_id);
          }
        })
      })?,
    )?;
    Ok(())
  }

  fn settle_send_verdict(&self, ctx: &Ctx<'_>, sstate: &Rc<UpdateDispatchState>, dispatch_id: i64, verdict: &str) {
    if sstate.settled.replace(true) {
      return;
    }
    self.send_dispatches.borrow_mut().remove(&dispatch_id);
    self.sync_blocking();
    sstate.signal.finish(ctx);
    self.host.on_send_verdict(dispatch_id, verdict);
  }

  fn try_dispatch_send<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    callback_id: u32,
    dispatch_id: i64,
    account_id: i32,
    message_json: &str,
  ) -> JsResult<()> {
    let state = self;
    let sstate = Rc::new(UpdateDispatchState::default());
    let Some(middleware) = state.send_fns.restore(ctx, callback_id) else {
      state.settle_send_verdict(ctx, &sstate, dispatch_id, "Ethe interceptor was disposed");
      return Ok(());
    };
    let context = Object::new(ctx.clone())?;
    context.set("account", dispatch_account(ctx, &state.accounts, account_id)?)?;
    state.define_signal(&context, &sstate.signal)?;
    state.send_dispatches.borrow_mut().insert(dispatch_id, sstate.clone());
    state.sync_blocking();
    let message = proxy::json_parse_tl(ctx, message_json)?;
    let result = middleware.call::<_, Value>((context, message))?;

    let ok_fn = {
      let state = state.clone();
      let sstate = sstate.clone();
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| {
        let verdict = match value.is_null() {
          true => "D".to_string(),
          false => match proxy::json_stringify_tl(&ctx, value) {
            Ok(json) => format!("S{json}"),
            Err(e) => format!("Ethe message did not serialize: {}", describe_js_error(&ctx, e)),
          },
        };
        state.settle_send_verdict(&ctx, &sstate, dispatch_id, &verdict);
      })?
    };
    let err_fn = {
      let state = state.clone();
      let sstate = sstate.clone();
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| {
        if sstate.signal.abandoned.get().is_some() {
          return;
        }
        (state.log)(&crate::fault(format_args!(
          "interceptSendMessage callback rejected: {}",
          format_thrown(&ctx, &value)
        )));
        state.settle_send_verdict(&ctx, &sstate, dispatch_id, &format!("E{}", error_value_to_string(&ctx, &value)));
      })?
    };
    resolve_and_then(ctx, result, ok_fn, err_fn)
  }

  pub fn dispatch_send(
    self: &Rc<Self>,
    context: &rquickjs::Context,
    callback_id: u32,
    dispatch_id: i64,
    account_id: i32,
    message_json: &str,
  ) {
    enter_js(context, |ctx| {
      if let Err(e) = self.try_dispatch_send(&ctx, callback_id, dispatch_id, account_id, message_json) {
        let msg = describe_js_error(&ctx, e);
        (self.log)(&format!("interceptSendMessage dispatch failed: {msg}"));
        let sstate = self.send_dispatches.borrow().get(&dispatch_id).cloned().unwrap_or_default();
        self.settle_send_verdict(&ctx, &sstate, dispatch_id, &format!("E{msg}"));
      }
    });
    pump_jobs(context, self.log.as_ref());
  }

  pub fn abandon_send_dispatch(self: &Rc<Self>, context: &rquickjs::Context, dispatch_id: i64, reason_wire: &str) {
    enter_js(context, |ctx| {
      let removed = self.send_dispatches.borrow_mut().remove(&dispatch_id);
      self.sync_blocking();
      if let Some(sstate) = removed {
        sstate.settled.set(true);
        if let Err(e) = sstate.signal.abandon(&ctx, Abandon::from_wire(reason_wire)) {
          (self.log)(&format!("abandonSendDispatch({dispatch_id}) failed to abort the signal: {e:?}"));
        }
      }
    });
    pump_jobs(context, self.log.as_ref());
  }

  pub(super) fn drain_send_dispatches(&self, ctx: &Ctx<'_>) {
    let drained: Vec<_> = self.send_dispatches.borrow_mut().drain().collect();
    self.sync_blocking();
    for (_, sstate) in drained {
      sstate.signal.finish(ctx);
    }
  }
}

fn read_filter<'js>(ctx: &Ctx<'js>, filter: &Value<'js>) -> JsResult<String> {
  let Some(filter) = filter.as_object() else {
    return Err(Exception::throw_type(ctx, "interceptSendMessage: filter must be an object"));
  };
  let encoded = Object::new(ctx.clone())?;
  let stage: Value = filter.get("stage")?;
  if !stage.is_undefined() {
    let stage = stage.as_string().and_then(|stage| stage.to_string().ok());
    match stage.as_deref() {
      Some(stage @ ("compose" | "uploaded")) => encoded.set("stage", stage)?,
      _ => {
        return Err(Exception::throw_type(ctx, "interceptSendMessage: filter.stage must be 'compose' or 'uploaded'"))
      }
    }
  }
  let text: Value = filter.get("text")?;
  if let Some(text) = text.as_bool() {
    encoded.set("text", text)?;
  } else if !text.is_undefined() {
    let not_regexp = || Exception::throw_type(ctx, "interceptSendMessage: filter.text must be a RegExp or a boolean");
    let Some(text) = text.as_object().filter(|text| qjs_is_regexp(text)) else {
      return Err(not_regexp());
    };
    let source: String = text.get("source").map_err(|_| not_regexp())?;
    let flags: String = text.get("flags").map_err(|_| not_regexp())?;
    let regex = Object::new(ctx.clone())?;
    regex.set("source", source)?;
    regex.set("flags", flags)?;
    encoded.set("text", regex)?;
  }
  let peers = read_one_or_many(ctx, filter, "peer")?;
  if !peers.is_empty() {
    let (mut ids, mut types) = (Vec::new(), Vec::new());
    for peer in peers {
      match peer.as_number() {
        Some(id) if id.fract() == 0.0 && id.abs() <= MAX_SAFE_INTEGER => ids.push(id),
        _ => types.push(read_name(&peer, PEER_TYPES).ok_or_else(|| {
          Exception::throw_type(
            ctx,
            "interceptSendMessage: filter.peer must hold peer ids, 'user', 'group' or 'broadcast'",
          )
        })?),
      }
    }
    if !ids.is_empty() {
      encoded.set("peer", ids)?;
    }
    if !types.is_empty() {
      encoded.set("peerType", types)?;
    }
  }
  let media: Value = filter.get("media")?;
  if let Some(media) = media.as_bool() {
    encoded.set("media", media)?;
  } else {
    let kinds = read_one_or_many(ctx, filter, "media")?
      .iter()
      .map(|kind| read_name(kind, MEDIA_KINDS))
      .collect::<Option<Vec<_>>>()
      .ok_or_else(|| {
        Exception::throw_type(ctx, "interceptSendMessage: filter.media must be a boolean, or one or more media kinds")
      })?;
    if !kinds.is_empty() {
      encoded.set("media", kinds)?;
    }
  }
  let forward: Value = filter.get("forward")?;
  if !forward.is_undefined() {
    let Some(forward) = forward.as_bool() else {
      return Err(Exception::throw_type(ctx, "interceptSendMessage: filter.forward must be a boolean"));
    };
    encoded.set("forward", forward)?;
  }
  stringify_json(ctx, encoded.into_value(), "interceptSendMessage: the filter did not serialize")
}

/// an empty array is refused, so an empty result means the field is absent
fn read_one_or_many<'js>(ctx: &Ctx<'js>, filter: &Object<'js>, key: &str) -> JsResult<Vec<Value<'js>>> {
  let value: Value = filter.get(key)?;
  if value.is_undefined() {
    return Ok(Vec::new());
  }
  let Some(values) = value.as_array() else {
    return Ok(vec![value]);
  };
  let values = values.iter::<Value>().collect::<JsResult<Vec<_>>>()?;
  if values.is_empty() {
    return Err(Exception::throw_type(ctx, &format!("interceptSendMessage: filter.{key} must not be empty")));
  }
  Ok(values)
}

fn read_name(value: &Value<'_>, names: &[&str]) -> Option<String> {
  value.as_string()?.to_string().ok().filter(|name| names.contains(&name.as_str()))
}

use std::cell::{Cell, RefCell};
use std::collections::{HashMap, VecDeque};
use std::rc::Rc;

use rquickjs::{Array, Ctx, Function, IntoJs, Object, Result as JsResult, Runtime, Value};

use crate::api::error::wire_error_to_js;
use crate::api::telegram::account::AccountState;
use crate::api::telegram::rpc::{format_exception, pump_jobs, PendingSettle};
use crate::api::tl::proxy::{wire_to_js_value, TlViews, ViewLife};
use crate::sandbox::grants::{check_grant, GrantHost, MATCH_EXACT};
use crate::sandbox::registry::RequestIds;
use crate::utils::prelude;

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/reads.qbc"));

pub trait ReadsHost {
  fn account_read(&self, account_id: i32, op: i32, arg: &str) -> String;

  fn resolve_peer(&self, account_id: i32, request_id: i64, spec: &str, kind: i32) -> Option<String>;

  fn account_fetch(&self, account_id: i32, request_id: i64, op: i32, arg: &str) -> Option<String>;
}

const OP_ME: i32 = 0;
const OP_USER: i32 = 1;
const OP_CHAT: i32 = 2;
const OP_PEER: i32 = 3;
const OP_DIALOG: i32 = 4;
const OP_MESSAGE: i32 = 5;
const OP_USERS: i32 = 6;
const OP_CHATS: i32 = 7;
const OP_MESSAGES: i32 = 8;
pub(crate) const OP_INPUT_PEER: i32 = 9;
const OP_DRAFT: i32 = 10;
const OP_USER_FULL: i32 = 11;
const OP_CHAT_FULL: i32 = 12;
const OP_HISTORY: i32 = 13;
const OP_DIALOGS: i32 = 14;
const OP_TOPICS: i32 = 15;

const SEPARATOR: char = '\n';

const SPEC_SELF: &str = "S";

fn scope_of(op: i32) -> Option<&'static str> {
  Some(match op {
    OP_ME => "self",
    OP_DIALOG | OP_DIALOGS | OP_TOPICS => "dialogs",
    OP_MESSAGE | OP_MESSAGES => "messages",
    OP_HISTORY => "history",
    OP_DRAFT => "draft",
    OP_USER | OP_CHAT | OP_PEER | OP_USERS | OP_CHATS | OP_INPUT_PEER | OP_USER_FULL | OP_CHAT_FULL => "peers",
    _ => return None,
  })
}

#[derive(Clone, Copy)]
enum Shape {
  Value,
  List,
  Page(&'static str),
}

fn shape_of(op: i32) -> Shape {
  match op {
    OP_HISTORY => Shape::List,
    OP_DIALOGS => Shape::Page(LIST_DIALOGS),
    OP_TOPICS => Shape::Page(LIST_TOPICS),
    _ => Shape::Value,
  }
}

const LIST_DIALOGS: &str = "dialogs";
const LIST_TOPICS: &str = "topics";

const CURSOR_LIMIT: usize = 32;

struct Cursor {
  token: String,
  list: &'static str,
  payload: String,
}

#[derive(Default)]
struct Cursors {
  next_id: Cell<u64>,
  entries: RefCell<VecDeque<Cursor>>,
}

impl Cursors {
  fn mint(&self, list: &'static str, payload: &str) -> String {
    let id = self.next_id.get() + 1;
    self.next_id.set(id);
    let token = format!("c{id}");
    let mut entries = self.entries.borrow_mut();
    entries.push_back(Cursor {
      token: token.clone(),
      list,
      payload: payload.to_string(),
    });
    while entries.len() > CURSOR_LIMIT {
      entries.pop_front();
    }
    token
  }

  fn payload_of(&self, list: &str, token: &str) -> Option<String> {
    self.entries.borrow().iter().find(|c| c.token == token && c.list == list).map(|c| c.payload.clone())
  }
}

pub struct ReadsState {
  host: Rc<dyn ReadsHost>,
  grants: Rc<dyn GrantHost>,
  views: Rc<TlViews>,
  log: crate::Log,
  next_request_id: RequestIds,
  pending: RefCell<HashMap<i64, PendingRead>>,
  cursors: Cursors,
}

struct PendingRead {
  settle: PendingSettle,
  shape: Shape,
}

fn check_read_grant(ctx: &Ctx<'_>, state: &Rc<ReadsState>, op: i32, arg: &str) -> JsResult<()> {
  if op == OP_USER_FULL && arg == SPEC_SELF && state.grants.is_granted("account.read", Some("self"), MATCH_EXACT) {
    return Ok(());
  }
  let Some(scope) = scope_of(op) else {
    return crate::api::error::PluginErrorCode::InvalidArgument.throw(ctx, "unknown account read");
  };
  check_grant(ctx, &state.grants, "account.read", Some(scope), MATCH_EXACT)?;
  check_self_grant(ctx, state, arg)
}

fn check_self_grant(ctx: &Ctx<'_>, state: &Rc<ReadsState>, arg: &str) -> JsResult<()> {
  if !names_self(arg) {
    return Ok(());
  }
  check_grant(ctx, &state.grants, "account.read", Some("self"), MATCH_EXACT)
}

fn names_self(arg: &str) -> bool {
  arg.split(SEPARATOR).any(|part| part == SPEC_SELF)
}

fn read_wire(ctx: &Ctx<'_>, state: &Rc<ReadsState>, op: i32, slot: i32, arg: &str) -> JsResult<String> {
  check_read_grant(ctx, state, op, arg)?;
  Ok(state.host.account_read(slot, op, arg))
}

fn read_one<'js>(ctx: &Ctx<'js>, state: &Rc<ReadsState>, op: i32, slot: i32, arg: &str) -> JsResult<Value<'js>> {
  let wire = read_wire(ctx, state, op, slot, arg)?;
  wire_to_js_value(ctx, &state.views, &wire, ViewLife::Plugin)
}

fn read_many<'js>(ctx: &Ctx<'js>, state: &Rc<ReadsState>, op: i32, slot: i32, arg: &str) -> JsResult<Value<'js>> {
  let wire = read_wire(ctx, state, op, slot, arg)?;
  if let Some(built) = wire_error_to_js(ctx, &wire) {
    return Err(ctx.throw(built?));
  }
  Ok(decode_list(ctx, state, &wire)?.into_value())
}

#[allow(clippy::too_many_arguments)]
pub fn install_reads<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn ReadsHost>,
  grants: Rc<dyn GrantHost>,
  views: Rc<TlViews>,
  shared: &Object<'js>,
  accounts: &Rc<AccountState>,
  log: crate::Log,
  inu: &Object<'js>,
) -> JsResult<Rc<ReadsState>> {
  let state = Rc::new(ReadsState {
    host,
    grants,
    views,
    log,
    next_request_id: RequestIds::default(),
    pending: RefCell::new(HashMap::new()),
    cursors: Cursors::default(),
  });

  let natives = Object::new(ctx.clone())?;
  {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, slot: i32| read_one(&ctx, &state, OP_ME, slot, ""))?;
    natives.set("getMe", f)?;
  }
  for (name, op) in [("getUser", OP_USER), ("getChat", OP_CHAT), ("getPeer", OP_PEER), ("getDialog", OP_DIALOG)] {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, slot: i32, spec: String| {
      read_one(&ctx, &state, op, slot, &spec)
    })?;
    natives.set(name, f)?;
  }
  {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, slot: i32, spec: String, id: String| {
      read_one(&ctx, &state, OP_MESSAGE, slot, &{
        let parts: &[&str] = &[&spec, &id];
        parts.join("\n")
      })
    })?;
    natives.set("getMessage", f)?;
  }
  for (name, op) in [("getUsers", OP_USERS), ("getChats", OP_CHATS)] {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, slot: i32, specs: String| {
      read_many(&ctx, &state, op, slot, &specs)
    })?;
    natives.set(name, f)?;
  }
  {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, slot: i32, spec: String, ids: String| {
      read_many(&ctx, &state, OP_MESSAGES, slot, &{
        let parts: &[&str] = &[&spec, &ids];
        parts.join("\n")
      })
    })?;
    natives.set("getMessages", f)?;
  }
  {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, slot: i32, spec: String, kind: i32| {
      read_one(&ctx, &state, OP_INPUT_PEER, slot, &{
        let parts: &[&str] = &[&spec, &kind.to_string()];
        parts.join("\n")
      })
    })?;
    natives.set("inputPeer", f)?;
  }
  {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, slot: i32, spec: String, kind: i32| {
      js_resolve_peer(&ctx, &state, slot, &spec, kind)
    })?;
    natives.set("resolve", f)?;
  }
  {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, _slot: i32| -> JsResult<()> {
      check_grant(&ctx, &state.grants, "account.read", Some("peers"), MATCH_EXACT)
    })?;
    natives.set("checkPeers", f)?;
  }
  {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, slot: i32, spec: String, topic: String| {
      read_one(&ctx, &state, OP_DRAFT, slot, &{
        let parts: &[&str] = &[&spec, &topic];
        parts.join("\n")
      })
    })?;
    natives.set("getDraft", f)?;
  }
  {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, slot: i32, op: i32, arg: String, cursor: String| {
      js_fetch(&ctx, &state, slot, op, &arg, &cursor)
    })?;
    natives.set("fetch", f)?;
  }

  let message: Value = inu.get("Message")?;
  let plugin_error: Value = inu.get("PluginError")?;
  let ops = Object::new(ctx.clone())?;
  for (name, op) in [
    ("userFull", OP_USER_FULL),
    ("chatFull", OP_CHAT_FULL),
    ("history", OP_HISTORY),
    ("dialogs", OP_DIALOGS),
    ("topics", OP_TOPICS),
  ] {
    ops.set(name, op)?;
  }

  let factory = prelude::load(ctx, PRELUDE)?;
  let prototype: Object = factory.call((natives, shared.clone(), message, plugin_error, ops))?;
  accounts.set_prototype(ctx, &prototype);

  Ok(state)
}

fn js_resolve_peer<'js>(
  ctx: &Ctx<'js>,
  state: &Rc<ReadsState>,
  slot: i32,
  spec: &str,
  kind: i32,
) -> JsResult<Value<'js>> {
  check_grant(ctx, &state.grants, "account.read", Some("peers"), MATCH_EXACT)?;
  check_self_grant(ctx, state, spec)?;
  park(ctx, state, Shape::Value, |request_id| state.host.resolve_peer(slot, request_id, spec, kind))
}

fn park<'js>(
  ctx: &Ctx<'js>,
  state: &Rc<ReadsState>,
  shape: Shape,
  ask: impl FnOnce(i64) -> Option<String>,
) -> JsResult<Value<'js>> {
  let request_id = state.next_request_id.alloc();
  let (promise, settle) = PendingSettle::new(ctx)?;
  state.pending.borrow_mut().insert(request_id, PendingRead { settle, shape });

  if let Some(err) = ask(request_id) {
    if let Some(pending) = state.pending.borrow_mut().remove(&request_id) {
      let value = crate::api::error::host_error_to_js(ctx, &err)?;
      pending.settle.reject_with_value(ctx, value)?;
    }
  }
  Ok(promise.into_value())
}

fn js_fetch<'js>(
  ctx: &Ctx<'js>,
  state: &Rc<ReadsState>,
  slot: i32,
  op: i32,
  arg: &str,
  cursor: &str,
) -> JsResult<Value<'js>> {
  check_read_grant(ctx, state, op, arg)?;
  let shape = shape_of(op);
  let host_arg = match shape {
    Shape::Page(list) => {
      let payload = if cursor.is_empty() {
        String::new()
      } else {
        match state.cursors.payload_of(list, cursor) {
          Some(payload) => payload,
          None => {
            return crate::api::error::PluginErrorCode::InvalidArgument
              .throw(ctx, "this cursor did not come from this list, or is too old to page from")
          }
        }
      };
      {
        let parts: &[&str] = &[arg, &payload];
        parts.join("\n")
      }
    }
    _ => arg.to_string(),
  };
  park(ctx, state, shape, |request_id| state.host.account_fetch(slot, request_id, op, &host_arg))
}

fn decode_list<'js>(ctx: &Ctx<'js>, state: &Rc<ReadsState>, wire: &str) -> JsResult<Array<'js>> {
  let array = Array::new(ctx.clone())?;
  if wire.is_empty() {
    return Ok(array);
  }
  for (index, element) in wire.split(SEPARATOR).enumerate() {
    array.set(index, wire_to_js_value(ctx, &state.views, element, ViewLife::Plugin)?)?;
  }
  Ok(array)
}

fn decode_result<'js>(ctx: &Ctx<'js>, state: &Rc<ReadsState>, shape: Shape, wire: &str) -> JsResult<Value<'js>> {
  match shape {
    Shape::Value => wire_to_js_value(ctx, &state.views, wire, ViewLife::Plugin),
    Shape::List => Ok(decode_list(ctx, state, wire)?.into_value()),
    Shape::Page(list) => {
      let (payload, elements) = wire.split_once(SEPARATOR).unwrap_or((wire, ""));
      let array = decode_list(ctx, state, elements)?;
      let next = if payload.is_empty() {
        Value::new_null(ctx.clone())
      } else {
        state.cursors.mint(list, payload).into_js(ctx)?
      };
      array.as_object().set("next", next)?;
      Ok(array.into_value())
    }
  }
}

fn settle(
  rt: &Runtime,
  context: &rquickjs::Context,
  state: &Rc<ReadsState>,
  what: &str,
  request_id: i64,
  result_wire: &str,
) {
  context.with(|ctx| {
    let Some(pending) = state.pending.borrow_mut().remove(&request_id) else {
      return;
    };
    if let Some(built) = wire_error_to_js(&ctx, result_wire) {
      match built {
        Ok(value) => {
          if pending.settle.reject_with_value(&ctx, value).is_err() {
            (state.log)(&format!("{what}({request_id}) reject failed: {}", format_exception(&ctx)));
          }
        }
        Err(e) => {
          pending.settle.release(&ctx);
          (state.log)(&format!("{what}({request_id}) error decode failed: {e:?}"));
        }
      }
      return;
    }
    match decode_result(&ctx, state, pending.shape, result_wire) {
      Ok(value) => {
        if pending.settle.resolve_with(&ctx, value).is_err() {
          (state.log)(&format!("{what}({request_id}) resolve failed: {}", format_exception(&ctx)));
        }
      }
      Err(_) => {
        pending.settle.release(&ctx);
        (state.log)(&format!("{what}({request_id}) bad result wire: {}", format_exception(&ctx)));
      }
    }
  });
  pump_jobs(rt, context, state.log.as_ref());
}

impl ReadsState {
  pub fn resolve_peer(self: &Rc<Self>, rt: &Runtime, context: &rquickjs::Context, request_id: i64, result_wire: &str) {
    let state = self;
    settle(rt, context, state, "resolvePeer", request_id, result_wire);
  }

  pub fn resolve_account_fetch(
    self: &Rc<Self>,
    rt: &Runtime,
    context: &rquickjs::Context,
    request_id: i64,
    result_wire: &str,
  ) {
    let state = self;
    settle(rt, context, state, "accountFetch", request_id, result_wire);
  }

  pub fn dispose(self: &Rc<Self>, context: &rquickjs::Context) {
    let state = self;
    context.with(|ctx| {
      for (_, pending) in state.pending.borrow_mut().drain() {
        pending.settle.release(&ctx);
      }
    });
  }
}

#[cfg(test)]
#[path = "reads_tests.rs"]
mod tests;

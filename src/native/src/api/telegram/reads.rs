use crate::runtime::Dispose;
use std::cell::{Cell, RefCell};
use std::collections::VecDeque;
use std::rc::Rc;

use rquickjs::{Ctx, IntoJs, Object, Result as JsResult, Value};

use crate::api::error::{throw_wire_error, PluginErrorCode};
use crate::api::telegram::account::AccountState;
use crate::api::tl::proxy::{TlViews, ViewLife};
use crate::runtime::{enter_js, Parked, PendingTable};
use crate::sandbox::grants::{GrantHost, MATCH_EXACT};
use crate::utils::qjs::qjs_load_prelude;

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
const OP_DIALOGS_CACHED: i32 = 16;
const OP_CHAT_FOLDERS: i32 = 17;
const OP_FETCH_MESSAGES: i32 = 18;
const OP_DIALOG_MUTED: i32 = 19;
const OP_TOPIC: i32 = 20;
const OP_MESSAGE_PREVIEW: i32 = 21;

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/reads.qbc"));

pub trait ReadsHost {
  fn account_read(&self, account_id: i32, op: i32, arg: &str) -> String;

  fn resolve_peer(&self, account_id: i32, request_id: i64, spec: &str, kind: i32) -> Option<String>;

  fn account_fetch(
    &self,
    account_id: i32,
    request_id: i64,
    op: i32,
    peer: &str,
    args: &str,
    cursor: &str,
  ) -> Option<String>;
}

const SEPARATOR: char = '\n';

const SPEC_SELF: &str = "S";

fn get_op_scope(op: i32) -> Option<&'static str> {
  Some(match op {
    OP_ME => "self",
    OP_DIALOG | OP_DIALOGS | OP_TOPICS | OP_DIALOGS_CACHED | OP_CHAT_FOLDERS | OP_DIALOG_MUTED | OP_TOPIC => "dialogs",
    OP_MESSAGE | OP_MESSAGES | OP_FETCH_MESSAGES | OP_MESSAGE_PREVIEW => "messages",
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

fn get_op_shape(op: i32) -> Shape {
  match op {
    OP_HISTORY | OP_DIALOGS_CACHED | OP_FETCH_MESSAGES => Shape::List,
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
}

pub struct ReadsState {
  host: Rc<dyn ReadsHost>,
  grants: Rc<dyn GrantHost>,
  views: Rc<TlViews>,
  log: crate::Log,
  pending: PendingTable<Shape>,
  cursors: Cursors,
}

impl Parked for Shape {}

impl ReadsState {
  fn check_read_grant(&self, ctx: &Ctx<'_>, op: i32, arg: &str) -> JsResult<()> {
    if op == OP_USER_FULL && arg == SPEC_SELF && self.grants.is_granted("account.read", Some("self"), MATCH_EXACT) {
      return Ok(());
    }
    let Some(scope) = get_op_scope(op) else {
      return PluginErrorCode::InvalidArgument.throw(ctx, "unknown account read");
    };
    self.grants.check_grant(ctx, "account.read", Some(scope), MATCH_EXACT)?;
    self.check_self_grant(ctx, arg)
  }

  fn check_self_grant(&self, ctx: &Ctx<'_>, arg: &str) -> JsResult<()> {
    if !arg.split(SEPARATOR).any(|part| part == SPEC_SELF) {
      return Ok(());
    }
    self.grants.check_grant(ctx, "account.read", Some("self"), MATCH_EXACT)
  }

  fn read_wire(&self, ctx: &Ctx<'_>, op: i32, slot: i32, arg: &str) -> JsResult<String> {
    self.check_read_grant(ctx, op, arg)?;
    Ok(self.host.account_read(slot, op, arg))
  }

  fn read_one<'js>(&self, ctx: &Ctx<'js>, op: i32, slot: i32, arg: &str) -> JsResult<Value<'js>> {
    let wire = self.read_wire(ctx, op, slot, arg)?;
    self.views.wire_to_js_value(ctx, &wire, ViewLife::Plugin)
  }

  fn read_many<'js>(&self, ctx: &Ctx<'js>, op: i32, slot: i32, arg: &str) -> JsResult<Value<'js>> {
    let wire = self.read_wire(ctx, op, slot, arg)?;
    throw_wire_error(ctx, &wire)?;
    Ok(self.views.wire_to_js_list(ctx, &wire, ViewLife::Plugin)?.into_value())
  }
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
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<ReadsState>> {
  let state = Rc::new(ReadsState {
    host,
    grants,
    views,
    log,
    pending: PendingTable::default(),
    cursors: Cursors::default(),
  });

  let natives = Object::new(ctx.clone())?;
  set_fn!(natives, "read", ctx, state, move |ctx: Ctx<'js>, slot: i32, op: i32, arg: String| {
    state.read_one(&ctx, op, slot, &arg)
  });
  set_fn!(natives, "readMany", ctx, state, move |ctx: Ctx<'js>, slot: i32, op: i32, arg: String| {
    state.read_many(&ctx, op, slot, &arg)
  });
  set_fn!(
    natives,
    "previewMessage",
    ctx,
    state,
    move |ctx: Ctx<'js>, slot: i32, message: Value<'js>, hide_spoilers: bool| {
      // a message a plugin was handed is already the app's own object, so it goes back as the
      // handle it is; one the plugin built itself crosses as json and is rebuilt host-side
      let wire = crate::api::tl::proxy::js_value_to_wire(&ctx, message)?;
      let flag = i32::from(hide_spoilers);
      state.read_one(&ctx, OP_MESSAGE_PREVIEW, slot, &format!("{flag}{SEPARATOR}{wire}"))
    }
  );
  set_fn!(natives, "resolve", ctx, state, move |ctx: Ctx<'js>, slot: i32, spec: String, kind: i32| {
    state.grants.check_grant(&ctx, "account.read", Some("peers"), MATCH_EXACT)?;
    state.check_self_grant(&ctx, &spec)?;
    state.park(&ctx, Shape::Value, |request_id| state.host.resolve_peer(slot, request_id, &spec, kind))
  });
  set_fn!(natives, "checkPeers", ctx, state, move |ctx: Ctx<'js>, _slot: i32| -> JsResult<()> {
    state.grants.check_grant(&ctx, "account.read", Some("peers"), MATCH_EXACT)
  });
  set_fn!(natives, "fetch", ctx, state, move |ctx: Ctx<'js>,
                                              slot: i32,
                                              op: i32,
                                              peer: String,
                                              args: String,
                                              cursor: String| {
    state.check_read_grant(&ctx, op, &peer)?;
    let shape = get_op_shape(op);
    let payload = match shape {
      Shape::Page(list) if !cursor.is_empty() => match state
        .cursors
        .entries
        .borrow()
        .iter()
        .find(|c| c.token == cursor && c.list == list)
        .map(|c| c.payload.clone())
      {
        Some(payload) => payload,
        None => {
          return PluginErrorCode::InvalidArgument
            .throw(&ctx, "this cursor did not come from this list, or is too old to page from")
        }
      },
      _ => String::new(),
    };
    state.park(&ctx, shape, |request_id| state.host.account_fetch(slot, request_id, op, &peer, &args, &payload))
  });

  let message = globals.get_message(ctx)?;
  let plugin_error = globals.plugin_error.clone();
  let ops = Object::new(ctx.clone())?;
  for (name, op) in [
    ("me", OP_ME),
    ("user", OP_USER),
    ("chat", OP_CHAT),
    ("peer", OP_PEER),
    ("dialog", OP_DIALOG),
    ("messageCached", OP_MESSAGE),
    ("users", OP_USERS),
    ("chats", OP_CHATS),
    ("messagesCached", OP_MESSAGES),
    ("inputPeer", OP_INPUT_PEER),
    ("draft", OP_DRAFT),
    ("dialogMuted", OP_DIALOG_MUTED),
    ("topic", OP_TOPIC),
    ("userFull", OP_USER_FULL),
    ("chatFull", OP_CHAT_FULL),
    ("history", OP_HISTORY),
    ("messages", OP_FETCH_MESSAGES),
    ("dialogs", OP_DIALOGS),
    ("topics", OP_TOPICS),
    ("dialogsCached", OP_DIALOGS_CACHED),
    ("chatFolders", OP_CHAT_FOLDERS),
  ] {
    ops.set(name, op)?;
  }

  let factory = qjs_load_prelude(ctx, PRELUDE)?;
  let prototype: Object = factory.call((natives, shared.clone(), message, plugin_error, ops))?;
  accounts.set_prototype(ctx, &prototype);

  Ok(state)
}

impl ReadsState {
  fn park<'js>(&self, ctx: &Ctx<'js>, shape: Shape, ask: impl FnOnce(i64) -> Option<String>) -> JsResult<Value<'js>> {
    Ok(self.pending.park(ctx, shape, ask)?.into_value())
  }

  fn decode_result<'js>(&self, ctx: &Ctx<'js>, shape: Shape, wire: &str) -> JsResult<Value<'js>> {
    match shape {
      Shape::Value => self.views.wire_to_js_value(ctx, wire, ViewLife::Plugin),
      Shape::List => Ok(self.views.wire_to_js_list(ctx, wire, ViewLife::Plugin)?.into_value()),
      Shape::Page(list) => {
        let (payload, elements) = wire.split_once(SEPARATOR).unwrap_or((wire, ""));
        let array = self.views.wire_to_js_list(ctx, elements, ViewLife::Plugin)?;
        let next = if payload.is_empty() {
          Value::new_null(ctx.clone())
        } else {
          self.cursors.mint(list, payload).into_js(ctx)?
        };
        array.as_object().set("next", next)?;
        Ok(array.into_value())
      }
    }
  }
}

impl ReadsState {
  pub fn settle(self: &Rc<Self>, context: &rquickjs::Context, request_id: i64, result_wire: &str) {
    self
      .pending
      .settle_and_pump(context, &self.log, "account read", request_id, result_wire, |ctx, shape, wire| {
        self.decode_result(ctx, *shape, wire)
      });
  }
}

impl Dispose for ReadsState {
  fn dispose(&self, context: &rquickjs::Context) {
    enter_js(context, |ctx| self.pending.dispose(&ctx));
  }
}

#[cfg(test)]
#[path = "reads_tests.rs"]
mod tests;

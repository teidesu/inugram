//! The `Account` read surface: the synchronous cache getters and peer resolution, per
//! `common.d.ts`. JNI-free behind [`ReadsHost`]; the normalization half is prelude js in
//! `reads.js`, over the peer helpers [`crate::api::tl::utils`] already owns.
//!
//! **One gate, one materialization point.** Every getter runs [`check_grant`] against the
//! `account.read` scope its op belongs to and then hands the host a spec - `S` (myself),
//! `D<dialog id>`, `U<username>` - never a peer, so the host parses no TL, a batch is one crossing,
//! and the takeover filter (Kotlin-side, at materialization) covers this surface by construction.
//! [`check_self_grant`] is the one rule on top: naming yourself is naming your identity.
//!
//! The getters live on one prototype per engine, because `dispatch_account` mints an `Account` for
//! every update and every intercepted request; the slot comes off `this.id`.
//!
//! A paging cursor is a token, not an encoding: `Cursors` maps it to the offset triple, keyed by
//! which list minted it, so nothing about a page's position is ever in JS and a token from
//! `getDialogs` handed to `getTopics` is refused rather than paging from a nonsense offset.

use std::cell::{Cell, RefCell};
use std::collections::{HashMap, VecDeque};
use std::rc::Rc;

use rquickjs::{Array, Ctx, Function, IntoJs, Object, Result as JsResult, Runtime, Value};

use crate::api::error::wire_error_to_js;
use crate::api::telegram::account::AccountState;
use crate::api::telegram::rpc::{format_exception, pump_jobs, PendingSettle};
use crate::api::tl::proxy::{wire_to_js_value, TlViews, ViewLife};
use crate::sandbox::grants::{check_grant, GrantHost, MATCH_EXACT};

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/reads.qbc"));

/// stand-in for the Kotlin `QuickJs.ReadsListener`
pub trait ReadsHost {
    /// one cache lookup; the answer is a single wire value, or one per element joined with
    /// [`SEPARATOR`] for a batch op
    fn account_read(&self, account_id: i32, op: i32, arg: &str) -> String;

    /// `None` == accepted, settled later through [`resolve_peer_result`]; `Some` == an error to
    /// reject with, as a bare message or a `P`/`R` wire
    fn resolve_peer(&self, account_id: i32, request_id: i64, spec: &str, kind: i32) -> Option<String>;

    /// one read that may go to the network. Same accept/reject contract as [`Self::resolve_peer`],
    /// settled later through [`account_fetch_result`]; `arg` is the op's operands joined with
    /// [`SEPARATOR`], with the cursor already turned back into the host's own offset triple.
    fn account_fetch(&self, account_id: i32, request_id: i64, op: i32, arg: &str) -> Option<String>;
}

// keep in sync with Kotlin `PluginReads.OP_*`
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

/// keep in sync with Kotlin `PluginReads.LIST_SEPARATOR`
const SEPARATOR: char = '\n';

/// the spec `reads.js` writes for "myself", the one shape whose grant can be decided before the
/// peer is resolved - see [`check_read_grant`]
const SPEC_SELF: &str = "S";

/// which `account.read` scope each op reads behind, per `common.d.ts`'s grant list. `None` is an op
/// only this crate's own prelude could have asked for, so it is refused before it can be gated on a
/// scope picked by a fallback.
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

/// what settling an op's request builds out of the host's wire
#[derive(Clone, Copy)]
enum Shape {
    /// one value, `N` for a miss
    Value,
    /// one wire per element, joined with [`SEPARATOR`]
    List,
    /// the cursor payload, then one wire per element - all joined with [`SEPARATOR`]
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

/// the `Cursor<List>` brands `common.d.ts` declares, as the runtime half of the same distinction
const LIST_DIALOGS: &str = "dialogs";
const LIST_TOPICS: &str = "topics";

/// how many cursors an engine keeps live at once. Paging uses the newest, so the oldest is what a
/// bound can drop; a plugin holding more than this many half-read lists gets `invalid-argument` on
/// the ones it abandoned rather than an unbounded table.
const CURSOR_LIMIT: usize = 32;

struct Cursor {
    token: String,
    list: &'static str,
    payload: String,
}

/// tokens are never reused and mean nothing outside this table, which is per engine - so a forged
/// one can only ever name a cursor the same plugin already holds
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
        entries.push_back(Cursor { token: token.clone(), list, payload: payload.to_string() });
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
    next_request_id: crate::sandbox::registry::RequestIds,
    pending: RefCell<HashMap<i64, PendingRead>>,
    cursors: Cursors,
}

struct PendingRead {
    settle: PendingSettle,
    shape: Shape,
}

/// the one gate. `getUserFull` is the only op with two ways through it: `common.d.ts` lets you ask
/// about *yourself* under `account.read(self)` alone, and "yourself" has to mean the spec that says
/// so - a dialog id that happens to be yours is not decidable here, and the host mirrors this exact
/// rule rather than resolving one first.
fn check_read_grant(ctx: &Ctx<'_>, state: &Rc<ReadsState>, op: i32, arg: &str) -> JsResult<()> {
    if op == OP_USER_FULL && arg == SPEC_SELF && state.grants.is_granted("account.read", Some("self"), MATCH_EXACT) {
        return Ok(());
    }
    let Some(scope) = scope_of(op) else {
        return crate::api::error::throw_plugin_error(
            ctx,
            "invalid-argument",
            "unknown account read",
            None,
            None,
            None,
        );
    };
    check_grant(ctx, &state.grants, "account.read", Some(scope), MATCH_EXACT)?;
    check_self_grant(ctx, state, arg)
}

/// Naming *yourself* tells a plugin which peer you are, which is the identity `account.read(self)`
/// gates on `Account.userId` and `inu.accounts()` - a plugin holding one `Account` per slot would
/// otherwise rebuild that list out of `getUser('me').id`. So it is required on top of the read's own
/// scope, which is checked first: a plugin missing both is told about the wider one. `PluginReads`
/// mirrors this, and this side is what keeps a refused call from crossing at all.
fn check_self_grant(ctx: &Ctx<'_>, state: &Rc<ReadsState>, arg: &str) -> JsResult<()> {
    if !names_self(arg) {
        return Ok(());
    }
    check_grant(ctx, &state.grants, "account.read", Some("self"), MATCH_EXACT)
}

/// the spec vocabulary makes this exact: nothing else an op sends (ids, counts, a cursor) is `S`
fn names_self(arg: &str) -> bool {
    arg.split(SEPARATOR).any(|part| part == SPEC_SELF)
}

/// runs the grant check for `op`, then asks the host
fn read_wire(ctx: &Ctx<'_>, state: &Rc<ReadsState>, op: i32, slot: i32, arg: &str) -> JsResult<String> {
    check_read_grant(ctx, state, op, arg)?;
    Ok(state.host.account_read(slot, op, arg))
}

fn read_one<'js>(ctx: &Ctx<'js>, state: &Rc<ReadsState>, op: i32, slot: i32, arg: &str) -> JsResult<Value<'js>> {
    let wire = read_wire(ctx, state, op, slot, arg)?;
    wire_to_js_value(ctx, &state.views, &wire, ViewLife::Plugin)
}

/// a batch answers with one wire per element, so misses stay `null` *in place* - which is what the
/// contract promises and what a TL vector handle could not express
fn read_many<'js>(ctx: &Ctx<'js>, state: &Rc<ReadsState>, op: i32, slot: i32, arg: &str) -> JsResult<Value<'js>> {
    let wire = read_wire(ctx, state, op, slot, arg)?;
    if let Some(built) = wire_error_to_js(ctx, &wire) {
        return Err(ctx.throw(built?));
    }
    Ok(decode_list(ctx, state, &wire)?.into_value())
}

fn join(parts: &[&str]) -> String {
    parts.join(&SEPARATOR.to_string())
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
        next_request_id: crate::sandbox::registry::RequestIds::default(),
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
            read_one(&ctx, &state, OP_MESSAGE, slot, &join(&[&spec, &id]))
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
            read_many(&ctx, &state, OP_MESSAGES, slot, &join(&[&spec, &ids]))
        })?;
        natives.set("getMessages", f)?;
    }
    {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, slot: i32, spec: String, kind: i32| {
            read_one(&ctx, &state, OP_INPUT_PEER, slot, &join(&[&spec, &kind.to_string()]))
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
        // `resolvePeerMany` is a scheduler over `resolve`, so its own gate has nothing to hang off:
        // without this an empty list, or one of nothing but input peers, would be answered without
        // the check every other read runs
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, _slot: i32| -> JsResult<()> {
            check_grant(&ctx, &state.grants, "account.read", Some("peers"), MATCH_EXACT)
        })?;
        natives.set("checkPeers", f)?;
    }
    {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, slot: i32, spec: String, topic: String| {
            read_one(&ctx, &state, OP_DRAFT, slot, &join(&[&spec, &topic]))
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

    // captured at install, like `utils.js`'s: what the prelude constructs and throws must not be
    // decidable by a plugin reassigning `inu.Message`
    let message: Value = inu.get("Message")?;
    let plugin_error: Value = inu.get("PluginError")?;

    let factory = crate::utils::prelude::load(ctx, PRELUDE)?;
    let prototype: Object = factory.call((natives, shared.clone(), message, plugin_error))?;
    crate::api::telegram::account::set_prototype(ctx, accounts, &prototype);

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

/// mints the promise, remembers how its answer will have to be built, and lets `ask` hand the
/// request to the host - which either takes it or refuses it outright
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
    // the cursor is resolved here rather than crossing: what the host is handed is the offset
    // triple it minted, and a token naming another list never reaches it at all
    let host_arg = match shape {
        Shape::Page(list) => {
            let payload = if cursor.is_empty() {
                String::new()
            } else {
                match state.cursors.payload_of(list, cursor) {
                    Some(payload) => payload,
                    None => {
                        return crate::api::error::throw_plugin_error(
                            ctx,
                            "invalid-argument",
                            "this cursor did not come from this list, or is too old to page from",
                            None,
                            None,
                            None,
                        )
                    }
                }
            };
            join(&[arg, &payload])
        }
        _ => arg.to_string(),
    };
    park(ctx, state, shape, |request_id| state.host.account_fetch(slot, request_id, op, &host_arg))
}

/// splits a `SEPARATOR`-joined answer into a JS array; an empty wire is an empty array rather than
/// one empty element
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

/// settles a pending `resolvePeer` or async read; the wire is the op's own shape, or an error
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

/// settles a pending `resolvePeer`/`resolveUser`/`resolveChannel`; the wire is a `J` `InputPeer` or
/// an error
pub fn resolve_peer_result(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<ReadsState>,
    request_id: i64,
    result_wire: &str,
) {
    settle(rt, context, state, "resolvePeer", request_id, result_wire);
}

/// settles a pending `getHistory`/`getDialogs`/`getTopics`/`getUserFull`/`getChatFull`
pub fn account_fetch_result(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<ReadsState>,
    request_id: i64,
    result_wire: &str,
) {
    settle(rt, context, state, "accountFetch", request_id, result_wire);
}

/// releases every `Persistent` GC root this state still owns - same contract as [`crate::api::telegram::rpc::dispose`]
pub fn dispose(context: &rquickjs::Context, state: &Rc<ReadsState>) {
    context.with(|ctx| {
        for (_, pending) in state.pending.borrow_mut().drain() {
            pending.settle.release(&ctx);
        }
    });
}

#[cfg(test)]
#[path = "reads_tests.rs"]
mod tests;

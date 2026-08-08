use super::*;
use crate::engine::error::{install_plugin_error, TestGrantHost};
use crate::tg::account::tests::TestAccountHost;
use crate::tl::proxy::TlHost;
use rquickjs::Context;
use std::collections::HashMap;

// the kinds `reads.js` sends and Kotlin `PluginReads.KIND_*` receives; only the fake host has a
// reason to name them on this side, since the engine passes the number straight through
const KIND_PEER: i32 = 0;
const KIND_USER: i32 = 1;
const KIND_CHANNEL: i32 = 2;

/// one entity of the fake's cache. `is_user` is the whole type system it needs: the surface
/// under test only ever asks "user or chat", and `access_hash` is what an `InputPeer` carries.
#[derive(Clone)]
struct Entity {
    id: i64,
    is_user: bool,
    username: Option<String>,
    access_hash: i64,
}

/// stands in for `PluginReads` + `TlHandles`: a tiny cache, a handle table over it, and a
/// record of what the prelude actually asked for - which is where the spec normalization is
/// observed rather than assumed.
#[derive(Default)]
struct TestReadsHost {
    entities: RefCell<Vec<Entity>>,
    /// `(dialog id, message id)` the app has in memory, mirroring stock keeping only the chat
    /// list's own messages there
    messages: RefCell<Vec<(i64, i32)>>,
    dialogs: RefCell<Vec<i64>>,
    self_id: Cell<i64>,
    handles: crate::testing::util::FakeHandles,
    reads: RefCell<Vec<(i32, i32, String)>>,
    resolves: RefCell<Vec<(i64, String, i32)>>,
    fetches: RefCell<Vec<(i64, i32, String)>>,
    /// every fetch that ever crossed, never drained - `fetches` is the queue, this is the record
    fetch_log: RefCell<Vec<(i32, String)>>,
}

impl TestReadsHost {
    fn new() -> Rc<Self> {
        let host = Rc::new(TestReadsHost::default());
        host.self_id.set(111);
        host.entities.borrow_mut().extend([
            Entity { id: 111, is_user: true, username: Some("selfuser".into()), access_hash: 11 },
            Entity { id: 222, is_user: true, username: Some("alice".into()), access_hash: 22 },
            Entity { id: -1001, is_user: false, username: Some("newschan".into()), access_hash: 33 },
        ]);
        host.dialogs.borrow_mut().push(111);
        host.messages.borrow_mut().push((111, 7));
        host
    }

    fn handle_wire(&self, object: FakeObject) -> String {
        self.handles.mint_wire(&object.name, object.fields)
    }

    fn entity(&self, id: i64) -> Option<Entity> {
        self.entities.borrow().iter().find(|e| e.id == id).cloned()
    }

    fn by_username(&self, name: &str) -> Option<Entity> {
        self.entities.borrow().iter().find(|e| e.username.as_deref() == Some(name)).cloned()
    }

    /// the spec vocabulary `reads.js` emits, resolved the way `PluginReads` resolves it
    fn dialog_id(&self, spec: &str) -> Option<i64> {
        match spec.chars().next()? {
            'S' => Some(self.self_id.get()),
            'D' => spec[1..].parse().ok(),
            'U' => self.by_username(&spec[1..]).map(|e| e.id),
            _ => None,
        }
    }

    fn entity_wire(&self, spec: &str, want_user: Option<bool>) -> String {
        let Some(id) = self.dialog_id(spec) else {
            return "N".to_string();
        };
        let Some(entity) = self.entity(id) else {
            return "N".to_string();
        };
        if want_user.is_some_and(|want| want != entity.is_user) {
            return "N".to_string();
        }
        let mut fields = vec![
            ("id".to_string(), format!("S{}", entity.id.abs())),
            ("access_hash".to_string(), format!("S{}", entity.access_hash)),
        ];
        if let Some(username) = entity.username.clone() {
            fields.push(("username".to_string(), format!("S{username}")));
        }
        let name = if entity.is_user { "user" } else { "channel" };
        self.handle_wire(FakeObject { name: name.to_string(), fields })
    }

    fn dialog_wire(&self, spec: &str) -> String {
        let Some(id) = self.dialog_id(spec) else {
            return "N".to_string();
        };
        if !self.dialogs.borrow().contains(&id) {
            return "N".to_string();
        }
        let peer = self.handle_wire(FakeObject {
            name: "peerUser".to_string(),
            fields: vec![("user_id".to_string(), format!("S{id}"))],
        });
        // a dialog row carries the chat's draft, which is what the oracle cross-checks against
        // `getDraft`: without one seeded here that block could not tell an agreement from an
        // absence
        let draft = self.handle_wire(FakeObject {
            name: "draftMessage".to_string(),
            fields: vec![("message".to_string(), "Sunsent".to_string())],
        });
        self.handle_wire(FakeObject {
            name: "dialog".to_string(),
            fields: vec![
                ("peer".to_string(), peer),
                ("top_message".to_string(), "I7".to_string()),
                ("draft".to_string(), draft),
            ],
        })
    }

    fn message_wire(&self, spec: &str, message_id: &str) -> String {
        let Some(id) = self.dialog_id(spec) else {
            return "N".to_string();
        };
        let Ok(message_id) = message_id.parse::<i32>() else {
            return "N".to_string();
        };
        if !self.messages.borrow().contains(&(id, message_id)) {
            return "N".to_string();
        }
        let peer = self.handle_wire(FakeObject {
            name: "peerUser".to_string(),
            fields: vec![("user_id".to_string(), format!("S{id}"))],
        });
        self.handle_wire(FakeObject {
            name: "message".to_string(),
            fields: vec![
                ("id".to_string(), format!("I{message_id}")),
                ("message".to_string(), "Shello".to_string()),
                ("peer_id".to_string(), peer),
                ("date".to_string(), "I1715540640".to_string()),
            ],
        })
    }

    fn input_peer_wire(&self, spec: &str, kind: i32) -> String {
        let Some(id) = self.dialog_id(spec) else {
            return "N".to_string();
        };
        if id == self.self_id.get() {
            return match kind {
                KIND_CHANNEL => wrong_kind(kind),
                KIND_USER => r#"J{"_":"inputUserSelf"}"#.to_string(),
                _ => r#"J{"_":"inputPeerSelf"}"#.to_string(),
            };
        }
        let Some(entity) = self.entity(id) else {
            return "N".to_string();
        };
        match kind {
            KIND_USER if !entity.is_user => wrong_kind(kind),
            KIND_CHANNEL if entity.is_user => wrong_kind(kind),
            KIND_USER => {
                format!(r#"J{{"_":"inputUser","user_id":"{}","access_hash":"{}"}}"#, entity.id, entity.access_hash)
            }
            KIND_CHANNEL => format!(
                r#"J{{"_":"inputChannel","channel_id":"{}","access_hash":"{}"}}"#,
                -entity.id, entity.access_hash
            ),
            _ if entity.is_user => {
                format!(r#"J{{"_":"inputPeerUser","user_id":"{}","access_hash":"{}"}}"#, entity.id, entity.access_hash)
            }
            _ => format!(
                r#"J{{"_":"inputPeerChannel","channel_id":"{}","access_hash":"{}"}}"#,
                -entity.id, entity.access_hash
            ),
        }
    }

    fn take_fetch(&self) -> Option<(i64, i32, String)> {
        let mut pending = self.fetches.borrow_mut();
        if pending.is_empty() {
            None
        } else {
            Some(pending.remove(0))
        }
    }

    fn history_wire(&self, spec: &str, limit: usize) -> String {
        let Some(id) = self.dialog_id(spec) else {
            return NOT_CACHED.to_string();
        };
        if self.entity(id).is_none() {
            return NOT_CACHED.to_string();
        }
        (0..limit.min(2))
            .map(|n| {
                self.handle_wire(FakeObject {
                    name: "message".to_string(),
                    fields: vec![
                        ("id".to_string(), format!("I{}", 100 - n)),
                        ("message".to_string(), format!("Sline {n}")),
                        ("date".to_string(), "I1715540640".to_string()),
                    ],
                })
            })
            .collect::<Vec<_>>()
            .join(&SEPARATOR.to_string())
    }

    fn dialog_page_wire(&self, payload: &str) -> String {
        // one full page, then one short one - which is what makes `next` go null exactly once
        if payload.is_empty() {
            let rows = [self.dialog_wire("S"), self.dialog_wire("S")].join(&SEPARATOR.to_string());
            format!("1715540640,7,111{SEPARATOR}{rows}")
        } else {
            format!("{SEPARATOR}{}", self.dialog_wire("S"))
        }
    }

    /// what the host answers a fetch with, once the test has decided to let it through
    fn answer_fetch(&self, op: i32, arg: &str) -> String {
        let parts: Vec<&str> = arg.split(SEPARATOR).collect();
        match op {
            OP_USER_FULL => match self.dialog_id(parts[0]).filter(|id| *id > 0).and_then(|id| self.entity(id)) {
                None => NOT_CACHED.to_string(),
                Some(entity) => self.handle_wire(FakeObject {
                    name: "userFull".to_string(),
                    fields: vec![
                        ("id".to_string(), format!("S{}", entity.id)),
                        ("about".to_string(), "Sbio".to_string()),
                    ],
                }),
            },
            OP_CHAT_FULL => match self.dialog_id(parts[0]).filter(|id| *id < 0).and_then(|id| self.entity(id)) {
                None => wrong_kind(KIND_CHANNEL),
                Some(entity) => self.handle_wire(FakeObject {
                    name: "channelFull".to_string(),
                    fields: vec![("id".to_string(), format!("S{}", -entity.id))],
                }),
            },
            OP_HISTORY => {
                let limit = parts.get(1).and_then(|l| l.parse().ok()).unwrap_or(0usize);
                self.history_wire(parts[0], limit)
            }
            OP_DIALOGS => self.dialog_page_wire(parts.get(2).copied().unwrap_or("")),
            // nothing in this fake is a forum, which is also the only answer a device gives for
            // every peer the oracle can name without a fixture
            OP_TOPICS => "Pinvalid-argument\n\n\n\nnot a forum".to_string(),
            _ => "Einternal: unknown fetch op".to_string(),
        }
    }

    fn take_resolve(&self) -> Option<(i64, String, i32)> {
        let mut pending = self.resolves.borrow_mut();
        if pending.is_empty() {
            None
        } else {
            Some(pending.remove(0))
        }
    }

    /// what the network would have answered: 'telegram' exists and nothing else does, and a
    /// successful resolve puts the peer in the cache the synchronous half reads
    fn answer_resolve(&self, spec: &str, kind: i32) -> String {
        // the one username that fails with something other than "there is no such peer", so a
        // batch can be asked what it does with an answer that is not an answer
        if spec == "Uboom" {
            return "Pforbidden\n\n\n\nnot for you".to_string();
        }
        if spec != "Utelegram" {
            return "Pnot-found\n\n\n\nno such username".to_string();
        }
        if self.by_username("telegram").is_none() {
            self.entities.borrow_mut().push(Entity {
                id: -1002,
                is_user: false,
                username: Some("telegram".into()),
                access_hash: 44,
            });
        }
        self.input_peer_wire(spec, kind)
    }
}

const NOT_CACHED: &str = "Pnot-found\n\n\n\nthis peer is not cached";

fn wrong_kind(kind: i32) -> String {
    let what = match kind {
        KIND_USER => "a user",
        KIND_CHANNEL => "a channel",
        _ => "a peer",
    };
    format!("Pinvalid-argument\n\n\n\nnot {what}")
}

impl ReadsHost for TestReadsHost {
    fn account_read(&self, account_id: i32, op: i32, arg: &str) -> String {
        self.reads.borrow_mut().push((account_id, op, arg.to_string()));
        let parts: Vec<&str> = arg.split(SEPARATOR).collect();
        match op {
            OP_ME => self.entity_wire("S", Some(true)),
            OP_USER => self.entity_wire(arg, Some(true)),
            OP_CHAT => self.entity_wire(arg, Some(false)),
            OP_PEER => self.entity_wire(arg, None),
            OP_DIALOG => self.dialog_wire(arg),
            OP_MESSAGE => self.message_wire(parts[0], parts.get(1).copied().unwrap_or("")),
            OP_USERS | OP_CHATS => {
                if arg.is_empty() {
                    return String::new();
                }
                let want_user = op == OP_USERS;
                parts
                    .iter()
                    .map(|spec| self.entity_wire(spec, Some(want_user)))
                    .collect::<Vec<_>>()
                    .join(&SEPARATOR.to_string())
            }
            OP_MESSAGES => {
                let ids = parts.get(1..).unwrap_or(&[]);
                if ids.is_empty() {
                    return String::new();
                }
                ids.iter().map(|id| self.message_wire(parts[0], id)).collect::<Vec<_>>().join(&SEPARATOR.to_string())
            }
            OP_INPUT_PEER => {
                let kind = parts.get(1).and_then(|k| k.parse().ok()).unwrap_or(KIND_PEER);
                self.input_peer_wire(parts[0], kind)
            }
            OP_DRAFT => match self.dialog_id(parts[0]) {
                Some(id) if id == self.self_id.get() => r#"J{"text":"unsent"}"#.to_string(),
                _ => "N".to_string(),
            },
            _ => "Einternal: unknown op".to_string(),
        }
    }

    fn resolve_peer(&self, _account_id: i32, request_id: i64, spec: &str, kind: i32) -> Option<String> {
        // only a username can be looked up, and the host says so without taking the request -
        // the same shape `PluginReads` refuses a bare id with
        if !spec.starts_with('U') {
            return Some("Pnot-found\n\n\n\nthis peer is not cached, and only a username can be looked up".to_string());
        }
        // otherwise parked rather than answered: settling here would re-enter the context this
        // call is already inside, which is what `PluginReads` posts to globalQueue to avoid
        self.resolves.borrow_mut().push((request_id, spec.to_string(), kind));
        None
    }

    fn account_fetch(&self, _account_id: i32, request_id: i64, op: i32, arg: &str) -> Option<String> {
        // parked for the same reason a resolve is: answering here would re-enter the context
        // this call is already inside
        self.fetch_log.borrow_mut().push((op, arg.to_string()));
        self.fetches.borrow_mut().push((request_id, op, arg.to_string()));
        None
    }
}

impl TlHost for TestReadsHost {
    fn tl_get(&self, handle: i64, key: &str) -> String {
        self.handles.get(handle, key)
    }

    // deliberately *not* the read-only message: a test asserting on that one must be reading
    // the engine's own refusal, which is the one a writable handle would skip
    fn tl_set(&self, _handle: i64, _key: &str, _value_wire: &str) -> Option<String> {
        Some("Pforbidden\n\n\n\nthe fake host takes no writes".to_string())
    }

    fn tl_has(&self, handle: i64, key: &str) -> i32 {
        self.handles.has(handle, key)
    }

    fn tl_own_keys(&self, handle: i64) -> Option<String> {
        self.handles.own_keys(handle)
    }

    fn tl_copy(&self, _handle: i64) -> Option<String> {
        None
    }

    fn tl_release(&self, handle: i64) {
        self.handles.release(handle)
    }
}

type Disposing = crate::testing::util::DisposeOnDrop<ReadsState>;
/// both states are disposed: `install_reads` parks the `Account` prototype on the account
/// state, and a `Persistent` still held when `JS_FreeRuntime` runs aborts the process
type Fixture = (
    Runtime,
    Context,
    Rc<TestReadsHost>,
    Disposing,
    crate::testing::util::DisposeOnDrop<crate::tg::account::AccountState>,
);

const ONE_ACCOUNT: &str = r#"[{"id":0,"userId":111,"isCurrent":true,"isPremium":false}]"#;

fn setup(grants: &[&str]) -> Fixture {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let host = TestReadsHost::new();
    let grants = TestGrantHost::new(grants).as_host();
    let log: crate::Log = std::sync::Arc::new(|_| {});
    let (state, accounts) = ctx.with(|ctx| {
        install_plugin_error(&ctx).unwrap();
        let accounts = crate::tg::account::install_account(
            &ctx,
            TestAccountHost::with(ONE_ACCOUNT),
            grants.clone(),
            crate::engine::registry::Lifecycle::new(),
            log.clone(),
        )
        .unwrap();
        let shared = crate::tl::utils::install_utils(&ctx).unwrap();
        crate::tl::message::install_message(&ctx, &shared).unwrap();
        let reads_host: Rc<dyn ReadsHost> = host.clone();
        let tl_host: Rc<dyn TlHost> = host.clone();
        let state =
            install_reads(&ctx, reads_host, grants, TlViews::new(tl_host), &shared, &accounts, log.clone()).unwrap();
        (state, accounts)
    });
    let state = Disposing::new(&ctx, state, dispose);
    let accounts = crate::testing::util::DisposeOnDrop::new(&ctx, accounts, crate::tg::account::dispose);
    (rt, ctx, host, state, accounts)
}

const ALL_GRANTS: &[&str] = &["account.read(self,peers,dialogs,messages)"];

use crate::testing::util::{eval_json, FakeObject};

/// for the reads whose *answer* does not matter: `JSON.stringify` on a view would ask the fake
/// host for a snapshot it deliberately cannot make
fn eval_void(ctx: &Context, code: &str) {
    ctx.with(|ctx| match ctx.eval::<(), _>(code) {
        Ok(()) => {}
        Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
        Err(e) => panic!("{e:?}"),
    })
}

/// evaluates `code`, returning the caught error as `[isPluginError, code, grant, message]` json
use crate::testing::util::catch_json;

/// drives the fake host the way `PluginReads` drives the real one: drain the microtask queue,
/// answer whatever it parked, repeat
fn settle(rt: &Runtime, ctx: &Context, state: &Rc<ReadsState>, host: &Rc<TestReadsHost>) {
    for _ in 0..64 {
        while rt.is_job_pending() {
            rt.execute_pending_job().ok();
        }
        if let Some((request_id, spec, kind)) = host.take_resolve() {
            let wire = host.answer_resolve(&spec, kind);
            resolve_peer_result(rt, ctx, state, request_id, &wire);
            continue;
        }
        let Some((request_id, op, arg)) = host.take_fetch() else {
            return;
        };
        let wire = host.answer_fetch(op, &arg);
        account_fetch_result(rt, ctx, state, request_id, &wire);
    }
    panic!("the host queue never drained");
}

#[test]
fn every_getter_gates_on_its_own_account_read_scope() {
    let (_rt, ctx, host, _state, _accounts) = setup(&["account.read(peers)"]);
    for (call, scope) in [
        ("inu.account().getMe()", "self"),
        ("inu.account().getDialog('me')", "dialogs"),
        ("inu.account().getMessage('me', 7)", "messages"),
        ("inu.account().getMessages('me', [7])", "messages"),
    ] {
        assert_eq!(
            catch_json(&ctx, call),
            format!(r#"[true,"not-granted","account.read({scope})","missing grant: account.read({scope})"]"#),
            "call: {call}",
        );
    }
    assert!(host.reads.borrow().is_empty(), "a refused call must not reach the host");

    // the same calls, granted, do reach it
    let (_rt, ctx, host, _state, _accounts) = setup(ALL_GRANTS);
    eval_void(&ctx, "inu.account().getMe(); inu.account().getDialog('me');");
    assert_eq!(host.reads.borrow().len(), 2);
}

#[test]
fn the_peers_scope_covers_the_entity_getters_and_nothing_else() {
    let (_rt, ctx, _host, _state, _accounts) = setup(&["account.read(self)"]);
    for call in ["getUser('me')", "getChat(-1001)", "getPeer('me')", "getUsers([])", "resolvePeerCached('me')"] {
        assert_eq!(
            catch_json(&ctx, &format!("inu.account().{call}")),
            r#"[true,"not-granted","account.read(peers)","missing grant: account.read(peers)"]"#,
            "call: {call}",
        );
    }
}

/// naming yourself is naming your identity, whatever read it is the peer of: a plugin holding
/// every other scope but `self` would otherwise rebuild `inu.accounts()` one slot at a time
#[test]
fn naming_yourself_takes_the_self_scope_on_top_of_the_reads_own() {
    let without_self = &["account.read(peers,dialogs,messages,history,draft)"];
    let (_rt, ctx, host, _state, _accounts) = setup(without_self);
    for call in [
        "getUser('me')",
        "getPeer('self')",
        "getUsers([222, 'me'])",
        "getDialog('me')",
        "getMessage('me', 7)",
        "getDraft('me')",
        "resolvePeerCached('me')",
        "resolvePeerCached({ _: 'user', id: '111', self: true })",
    ] {
        assert_eq!(
            catch_json(&ctx, &format!("inu.account().{call}")),
            r#"[true,"not-granted","account.read(self)","missing grant: account.read(self)"]"#,
            "call: {call}",
        );
    }
    assert!(host.reads.borrow().is_empty(), "a refused call must not reach the host");

    // an id is not an identity: what is gated is being told *which* peer you are
    assert_eq!(eval_json(&ctx, "inu.account().getUser(222)._"), r#""user""#);
    assert_eq!(eval_json(&ctx, "inu.account().getUser(111)._"), r#""user""#);

    // and the asynchronous half refuses the same specs, asynchronously
    let out = run_async(
        without_self,
        r#"const a = inu.account();
           const push = (label) => (e) => __out.push(`${label}:${e.code}:${e.grant}`);
           a.getHistory('me').catch(push('history'));
           a.getUserFull('me').catch(push('userFull'));
           a.getTopics('me').catch(push('topics'));
           a.resolvePeer('me').catch(push('resolvePeer'));"#,
    );
    assert_eq!(
        out,
        r#"["history:not-granted:account.read(self)","resolvePeer:not-granted:account.read(self)","topics:not-granted:account.read(self)","userFull:not-granted:account.read(self)"]"#,
    );
}

/// the read's own scope is reported first, so a plugin holding neither is told about the wider
/// mistake rather than being sent to fix the narrower one twice
#[test]
fn the_reads_own_scope_is_checked_before_the_self_rule() {
    let (_rt, ctx, _host, _state, _accounts) = setup(&["account.read(peers)"]);
    assert_eq!(
        catch_json(&ctx, "inu.account().getDialog('me')"),
        r#"[true,"not-granted","account.read(dialogs)","missing grant: account.read(dialogs)"]"#,
    );
}

/// the whole point of the spec: the host is never handed a peer, only one of three shapes
#[test]
fn an_input_peer_like_is_normalized_before_it_crosses() {
    let (_rt, ctx, host, _state, _accounts) = setup(ALL_GRANTS);
    let calls = [
        "getUser('me')",
        "getUser('self')",
        "getUser(222)",
        "getUser('222')",
        "getUser('@Alice')",
        "getUser('alice')",
        "getChat(-1001)",
        "getPeer({ _: 'peerChannel', channel_id: '1001' })",
        "getUser({ _: 'peerUser', user_id: '222' })",
        "getUser({ _: 'inputPeerSelf' })",
        "getUser({ _: 'user', id: '222', access_hash: '22' })",
        "getUser({ _: 'user', id: '999', self: true })",
    ];
    for call in calls {
        eval_void(&ctx, &format!("inu.account().{call};"));
    }
    let seen: Vec<String> = host.reads.borrow().iter().map(|(_, _, arg)| arg.clone()).collect();
    assert_eq!(seen, vec!["S", "S", "D222", "D222", "Ualice", "Ualice", "D-1001", "D-1001", "D222", "S", "D222", "S"],);
}

#[test]
fn a_peer_that_names_nothing_is_an_invalid_argument_and_never_crosses() {
    let (_rt, ctx, host, _state, _accounts) = setup(ALL_GRANTS);
    for peer in ["0", "'0'", "null", "undefined", "{}", "[]", "1.5", "'not a name!'", "'@'", "true", "NaN"] {
        let caught = catch_json(&ctx, &format!("inu.account().getUser({peer})"));
        assert!(caught.starts_with(r#"[true,"invalid-argument""#), "peer {peer}: {caught}");
    }
    assert!(host.reads.borrow().is_empty());
}

#[test]
fn a_miss_is_null_rather_than_an_error() {
    let (_rt, ctx, _host, _state, _accounts) = setup(ALL_GRANTS);
    assert_eq!(
        eval_json(
            &ctx,
            r#"(() => { const a = inu.account(); return [
                a.getUser(4242), a.getChat(-4242), a.getPeer(4242), a.getDialog(-4242),
                a.getMessage(-4242, 7), a.resolvePeerCached(4242),
            ] })()"#
        ),
        "[null,null,null,null,null,null]",
    );
}

/// a user id read as a chat (and the other way round) is a miss, not the entity anyway
#[test]
fn the_entity_getters_do_not_cross_kinds() {
    let (_rt, ctx, _host, _state, _accounts) = setup(ALL_GRANTS);
    assert_eq!(
        eval_json(
            &ctx,
            r#"(() => { const a = inu.account(); return [
                a.getUser(222)._, a.getChat(222), a.getChat(-1001)._, a.getUser(-1001),
                a.getPeer(222)._, a.getPeer(-1001)._,
            ] })()"#
        ),
        r#"["user",null,"channel",null,"user","channel"]"#,
    );
}

#[test]
fn a_batch_is_one_crossing_and_keeps_its_misses_in_place() {
    let (_rt, ctx, host, _state, _accounts) = setup(ALL_GRANTS);
    assert_eq!(
        eval_json(&ctx, "inu.account().getUsers([222, 4242, 'me']).map((u) => u && u._)"),
        r#"["user",null,"user"]"#,
    );
    assert_eq!(host.reads.borrow().len(), 1, "one crossing for the whole batch");
    assert_eq!(host.reads.borrow()[0].2, "D222\nD4242\nS");
}

/// an empty batch still crosses, so the grant is checked exactly where every other read checks it
#[test]
fn an_empty_batch_is_an_empty_array_and_still_asks() {
    let (_rt, ctx, host, _state, _accounts) = setup(ALL_GRANTS);
    assert_eq!(eval_json(&ctx, "inu.account().getUsers([])"), "[]");
    assert_eq!(host.reads.borrow().len(), 1);

    let (_rt, denied, host, _state, _accounts) = setup(&[]);
    assert_eq!(
        catch_json(&denied, "inu.account().getUsers([])"),
        r#"[true,"not-granted","account.read(peers)","missing grant: account.read(peers)"]"#,
    );
    assert!(host.reads.borrow().is_empty());
}

/// its own batch and its own kind: a user named here is a miss, not the user anyway
#[test]
fn a_chat_batch_is_one_crossing_and_answers_for_chats_only() {
    let (_rt, ctx, host, _state, _accounts) = setup(ALL_GRANTS);
    assert_eq!(
        eval_json(&ctx, "inu.account().getChats([-1001, 222, -4242]).map((c) => c && c._)"),
        r#"["channel",null,null]"#,
    );
    assert_eq!(host.reads.borrow().len(), 1, "one crossing for the whole batch");
    assert_eq!(host.reads.borrow()[0].2, "D-1001\nD222\nD-4242");
    assert_eq!(eval_json(&ctx, "inu.account().getChats([])"), "[]");
    assert_eq!(
        catch_json(&ctx, "inu.account().getChats('newschan')"),
        r#"[true,"invalid-argument",null,"getChats: expected an array of peers"]"#,
    );
}

#[test]
fn a_drafts_topic_id_reaches_the_host() {
    let (_rt, ctx, host, _state, _accounts) = setup(ASYNC_GRANTS);
    eval_void(&ctx, "inu.account().getDraft('me', { topicId: 7 });");
    assert_eq!(host.reads.borrow().last().unwrap().2, "S\n7");
    // omitted is the host's own default rather than a topic of its own
    eval_void(&ctx, "inu.account().getDraft('me');");
    assert_eq!(host.reads.borrow().last().unwrap().2, "S\n0");
    assert_eq!(
        catch_json(&ctx, "inu.account().getDraft('me', { topicId: -1 })"),
        r#"[true,"invalid-argument",null,"getDraft: topicId must be a non-negative integer"]"#,
    );
}

#[test]
fn a_message_comes_back_wrapped_and_a_miss_stays_null() {
    let (_rt, ctx, _host, _state, _accounts) = setup(ALL_GRANTS);
    assert_eq!(
        eval_json(
            &ctx,
            r#"(() => {
                const m = inu.account().getMessage('me', 7);
                return [m instanceof inu.Message, m.id, m.text, m.raw._, inu.account().getMessage('me', 8)];
            })()"#
        ),
        r#"[true,7,"hello","message",null]"#,
    );
    assert_eq!(eval_json(&ctx, "inu.account().getMessages('me', [7, 8]).map((m) => m && m.id)"), "[7,null]",);
}

#[test]
fn everything_read_off_an_account_is_read_only() {
    let (_rt, ctx, _host, _state, _accounts) = setup(ALL_GRANTS);
    assert_eq!(
        catch_json(&ctx, "inu.account().getUser(222).username = 'mallory'"),
        format!(r#"[true,"forbidden",null,{:?}]"#, "this TL view is read-only; take a copy with toJSON() to edit it"),
    );
}

#[test]
fn resolve_peer_cached_answers_with_the_input_peer_alone() {
    let (_rt, ctx, _host, _state, _accounts) = setup(ALL_GRANTS);
    assert_eq!(eval_json(&ctx, "inu.account().resolvePeerCached('me')"), r#"{"_":"inputPeerSelf"}"#,);
    assert_eq!(
        eval_json(&ctx, "inu.account().resolvePeerCached(222)"),
        r#"{"_":"inputPeerUser","user_id":"222","access_hash":"22"}"#,
    );
}

#[test]
fn a_cached_peer_resolves_without_asking_the_host_to_look_it_up() {
    let (rt, ctx, host, state, _accounts) = setup(ALL_GRANTS);
    ctx.with(|ctx| {
        ctx.eval::<(), _>("globalThis.__out = []; inu.account().resolvePeer(222).then((p) => __out.push(p._))").unwrap()
    });
    settle(&rt, &ctx, &state, &host);
    assert_eq!(eval_json(&ctx, "__out"), r#"["inputPeerUser"]"#);
    assert!(host.resolves.borrow().is_empty(), "the cache answered, so nothing was looked up");
}

#[test]
fn an_uncached_username_is_looked_up_once_and_then_answers_from_the_cache() {
    let (rt, ctx, host, state, _accounts) = setup(ALL_GRANTS);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"globalThis.__out = [];
               inu.account().resolvePeer('@Telegram')
                   .then((p) => { __out.push(p._); __out.push(inu.account().resolvePeerCached('telegram')._) })"#,
        )
        .unwrap()
    });
    settle(&rt, &ctx, &state, &host);
    assert_eq!(eval_json(&ctx, "__out"), r#"["inputPeerChannel","inputPeerChannel"]"#);
}

#[test]
fn an_uncached_id_rejects_rather_than_inventing_an_access_hash() {
    let (rt, ctx, host, state, _accounts) = setup(ALL_GRANTS);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"globalThis.__out = [];
               inu.account().resolvePeer(4242).catch((e) => __out.push([e.code, e instanceof inu.PluginError]))"#,
        )
        .unwrap()
    });
    settle(&rt, &ctx, &state, &host);
    assert_eq!(eval_json(&ctx, "__out"), r#"[["not-found",true]]"#);
}

#[test]
fn narrowing_to_the_wrong_kind_is_an_invalid_argument() {
    let (rt, ctx, host, state, _accounts) = setup(ALL_GRANTS);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"globalThis.__out = [];
               const a = inu.account();
               a.resolveUser(-1001).catch((e) => __out.push(`user:${e.code}`));
               a.resolveChannel(222).catch((e) => __out.push(`channel:${e.code}`));
               a.resolveChannel('me').catch((e) => __out.push(`self:${e.code}`));
               a.resolveUser('me').then((p) => __out.push(`self:${p._}`));
               a.resolveChannel(-1001).then((p) => __out.push(`channel:${p._}`));"#,
        )
        .unwrap()
    });
    settle(&rt, &ctx, &state, &host);
    assert_eq!(
        eval_json(&ctx, "__out.slice().sort()"),
        r#"["channel:inputChannel","channel:invalid-argument","self:inputUserSelf","self:invalid-argument","user:invalid-argument"]"#,
    );
}

/// an input peer the caller already holds is the answer, and asking for it reads nothing
#[test]
fn an_already_built_input_peer_passes_straight_through() {
    let (rt, ctx, host, state, _accounts) = setup(&[]);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"globalThis.__out = [];
               const peer = { _: 'inputPeerUser', user_id: '222', access_hash: '22' };
               const a = inu.account();
               __out.push(a.resolvePeerCached(peer) === peer);
               a.resolvePeer(peer).then((p) => __out.push(p === peer));
               a.resolveUser({ _: 'inputUserSelf' }).then((p) => __out.push(p._));"#,
        )
        .unwrap()
    });
    settle(&rt, &ctx, &state, &host);
    assert_eq!(eval_json(&ctx, "__out"), r#"[true,true,"inputUserSelf"]"#);
    assert!(host.reads.borrow().is_empty(), "nothing was read to answer with the argument");
}

/// an async member fails asynchronously, including when the failure is the grant gate
#[test]
fn a_missing_grant_rejects_rather_than_throws_on_the_promise_members() {
    let (rt, ctx, host, state, _accounts) = setup(&[]);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"globalThis.__out = [];
               inu.account().resolvePeer('telegram').catch((e) => __out.push([e.code, e.grant]))"#,
        )
        .unwrap()
    });
    settle(&rt, &ctx, &state, &host);
    assert_eq!(eval_json(&ctx, "__out"), r#"[["not-granted","account.read(peers)"]]"#);
}

/// one prototype per engine, not one closure set per handle: a dispatch mints an `Account` for
/// every update and every intercepted request
#[test]
fn the_getters_live_on_one_shared_prototype() {
    let (_rt, ctx, _host, _state, _accounts) = setup(ALL_GRANTS);
    assert_eq!(
        eval_json(
            &ctx,
            r#"[
                Object.getPrototypeOf(inu.account()) === Object.getPrototypeOf(inu.account(0)),
                Object.hasOwn(inu.account(), 'getUser'),
                typeof inu.account().getUser,
            ]"#
        ),
        r#"[true,false,"function"]"#,
    );
}

/// the slot is read off the handle, so a method torn off one is a mistake with a name rather
/// than a read against slot 0
#[test]
fn a_detached_getter_says_so_instead_of_guessing_a_slot() {
    let (_rt, ctx, _host, _state, _accounts) = setup(ALL_GRANTS);
    let caught = catch_json(&ctx, "(0, inu.account().getUser)(222)");
    assert!(caught.contains("not called on an account handle"), "{caught}");
}

const ASYNC_GRANTS: &[&str] = &["account.read(self,peers,dialogs,messages,history,draft)"];

/// runs `code`, which must leave its results in `__out`, and answers whatever the host parked
fn run_async(grants: &[&str], code: &str) -> String {
    let (rt, ctx, host, state, _accounts) = setup(grants);
    ctx.with(|ctx| match ctx.eval::<(), _>(format!("globalThis.__out = []; {code}")) {
        Ok(()) => {}
        Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
        Err(e) => panic!("{e:?}"),
    });
    settle(&rt, &ctx, &state, &host);
    // the order settlements land in is the order the host answered, which is not the order the
    // calls were made in - a sorted comparison is the only stable one
    eval_json(&ctx, "__out.slice().sort()")
}

#[test]
fn every_async_read_gates_on_its_own_account_read_scope() {
    let out = run_async(
        &["account.read(messages)"],
        r#"const a = inu.account();
           const push = (label) => (e) => __out.push(`${label}:${e.code}:${e.grant}`);
           a.getHistory('me').catch(push('history'));
           a.getDialogs().catch(push('dialogs'));
           a.getTopics('me').catch(push('topics'));
           a.getUserFull('me').catch(push('userFull'));
           a.getChatFull(-1001).catch(push('chatFull'));"#,
    );
    assert_eq!(
        out,
        r#"["chatFull:not-granted:account.read(peers)","dialogs:not-granted:account.read(dialogs)","history:not-granted:account.read(history)","topics:not-granted:account.read(dialogs)","userFull:not-granted:account.read(peers)"]"#,
    );
}

/// `getDraft` is synchronous, so its refusal is a throw rather than a rejection
#[test]
fn the_draft_scope_covers_get_draft_and_nothing_else() {
    let (_rt, ctx, _host, _state, _accounts) = setup(&["account.read(peers)"]);
    assert_eq!(
        catch_json(&ctx, "inu.account().getDraft('me')"),
        r#"[true,"not-granted","account.read(draft)","missing grant: account.read(draft)"]"#,
    );
    let (_rt, ctx, _host, _state, _accounts) = setup(ASYNC_GRANTS);
    assert_eq!(eval_json(&ctx, "inu.account().getDraft('me')"), r#"{"text":"unsent"}"#);
    assert_eq!(eval_json(&ctx, "inu.account().getDraft(222)"), "null");
}

/// `common.d.ts`: asking about yourself is allowed under `self` alone, and "yourself" is the
/// spec that says so - the gate runs before any peer is resolved
#[test]
fn get_user_full_on_yourself_needs_only_the_self_scope() {
    let out = run_async(
        &["account.read(self)"],
        r#"const a = inu.account();
           a.getUserFull('me').then((u) => __out.push(u.about), (e) => __out.push(`me:${e.code}`));
           a.getUserFull(222).catch((e) => __out.push(`other:${e.code}`));"#,
    );
    assert_eq!(out, r#"["bio","other:not-granted"]"#);
}

#[test]
fn a_bad_argument_rejects_rather_than_throws() {
    let out = run_async(
        ASYNC_GRANTS,
        r#"const a = inu.account();
           const push = (label) => (e) => __out.push(`${label}:${e.code}`);
           a.getHistory(0).catch(push('peer'));
           a.getHistory('me', { limit: -1 }).catch(push('limit'));
           a.getHistory('me', { offsetId: 1.5 }).catch(push('offsetId'));
           a.getDialogs('main').catch(push('options'));
           a.getDialogs({ cursor: 42 }).catch(push('cursor'));
           a.getUserFull(null).catch(push('userFull'));"#,
    );
    assert_eq!(
        out,
        r#"["cursor:invalid-argument","limit:invalid-argument","offsetId:invalid-argument","options:invalid-argument","peer:invalid-argument","userFull:invalid-argument"]"#,
    );
}

#[test]
fn history_comes_back_wrapped_and_an_empty_one_is_an_empty_array() {
    let out = run_async(
        ASYNC_GRANTS,
        r#"const a = inu.account();
           a.getHistory('me', { limit: 2 }).then((page) => {
             __out.push(Array.isArray(page), page.length, page[0] instanceof inu.Message, page[0].id)
           });
           a.getHistory('me', { limit: 0 }).then((page) => __out.push(page.length));
           a.getHistory(4242).catch((e) => __out.push(e.code));"#,
    );
    assert_eq!(out, r#"[0,100,2,"not-found",true,true]"#);
}

#[test]
fn a_page_is_an_array_carrying_its_own_next() {
    let out = run_async(
        ASYNC_GRANTS,
        r#"inu.account().getDialogs({ limit: 2 }).then((page) => {
             __out.push(Array.isArray(page), page.length, typeof page.next, page.map((d) => d._).join(','))
           })"#,
    );
    assert_eq!(out, r#"[2,"dialog,dialog","string",true]"#);
}

/// the offsets never reach JS: what the host is handed back is the payload it minted, and what
/// the plugin held was a token that means nothing outside this engine
#[test]
fn paging_hands_the_host_back_its_own_offsets_and_ends_at_a_short_page() {
    let (rt, ctx, host, state, _accounts) = setup(ASYNC_GRANTS);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"globalThis.__out = [];
               inu.account().getDialogs({ limit: 2 }).then((first) => {
                 __out.push(first.next.includes('111') || first.next.includes('1715540640'))
                 return inu.account().getDialogs({ limit: 2, cursor: first.next })
               }).then((second) => __out.push(second.length, second.next))"#,
        )
        .unwrap()
    });
    settle(&rt, &ctx, &state, &host);
    assert_eq!(eval_json(&ctx, "__out"), "[false,1,null]");
    let asked: Vec<String> = host.fetch_log.borrow().iter().map(|(_, arg)| arg.clone()).collect();
    assert_eq!(asked, vec!["0\n2\n", "0\n2\n1715540640,7,111"], "the second page carries the host's own offsets");
}

#[test]
fn a_cursor_that_did_not_come_from_this_list_never_crosses() {
    let (rt, ctx, host, state, _accounts) = setup(ASYNC_GRANTS);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"globalThis.__out = [];
               const a = inu.account();
               a.getDialogs({ limit: 2 }).then((page) => {
                 a.getTopics('me', { cursor: page.next }).catch((e) => __out.push(`brand:${e.code}`))
                 a.getDialogs({ cursor: page.next + 'x' }).catch((e) => __out.push(`forged:${e.code}`))
                 a.getDialogs({ cursor: 'not-a-cursor' }).catch((e) => __out.push(`invented:${e.code}`))
               })"#,
        )
        .unwrap()
    });
    settle(&rt, &ctx, &state, &host);
    assert_eq!(
        eval_json(&ctx, "__out.slice().sort()"),
        r#"["brand:invalid-argument","forged:invalid-argument","invented:invalid-argument"]"#,
    );
    // one crossing, the one that minted the cursor: the three refusals were decided in-engine
    assert_eq!(host.fetch_log.borrow().len(), 1);
}

#[test]
fn a_host_refusal_becomes_the_rejection() {
    let out = run_async(
        ASYNC_GRANTS,
        r#"const a = inu.account();
           a.getTopics(-1001).catch((e) => __out.push(`topics:${e.code}`));
           a.getChatFull(222).catch((e) => __out.push(`chatFull:${e.code}`));"#,
    );
    assert_eq!(out, r#"["chatFull:invalid-argument","topics:invalid-argument"]"#);
}

#[test]
fn everything_an_async_read_hands_over_is_read_only() {
    let out = run_async(
        ASYNC_GRANTS,
        r#"inu.account().getHistory('me', { limit: 1 }).then((page) => {
             try { page[0].raw.message = 'mallory'; __out.push('no-throw') }
             catch (e) { __out.push(`${e instanceof inu.PluginError}:${e.code}:${e.message}`) }
           })"#,
    );
    assert!(out.starts_with(r#"["true:forbidden:this TL view is read-only"#), "{out}");
}

/// runs `code` (which leaves its results in `__out`) and answers whatever the host parks,
/// keeping the order the results landed in - which is the whole subject of an iterator
fn run_ordered(grants: &[&str], code: &str) -> (String, Vec<(i32, String)>) {
    let (rt, ctx, host, state, _accounts) = setup(grants);
    ctx.with(|ctx| match ctx.eval::<(), _>(format!("globalThis.__out = []; {code}")) {
        Ok(()) => {}
        Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
        Err(e) => panic!("{e:?}"),
    });
    settle(&rt, &ctx, &state, &host);
    let asked = host.fetch_log.borrow().clone();
    (eval_json(&ctx, "__out"), asked)
}

#[test]
fn an_iterator_pages_until_the_list_runs_out() {
    let (out, asked) = run_ordered(
        ASYNC_GRANTS,
        r#"(async () => {
             for await (const d of inu.account().iterDialogs({ batchSize: 2 })) __out.push(d._)
             __out.push('end')
           })()"#,
    );
    // the fake answers one full page then a short one, so this is both pages and the stop
    assert_eq!(out, r#"["dialog","dialog","dialog","end"]"#);
    assert_eq!(asked.len(), 2, "the second page is the cursor's, and there is no third");
    assert_eq!(asked[1].1, "0\n2\n1715540640,7,111", "it pages with the host's own offsets");
}

/// `limit` is a total and cuts the last page short, which is the difference between it and
/// `batchSize`
#[test]
fn a_limit_stops_an_iterator_mid_page_and_asks_for_nothing_more() {
    let (out, asked) = run_ordered(
        ASYNC_GRANTS,
        r#"(async () => {
             for await (const d of inu.account().iterDialogs({ limit: 1, batchSize: 2 })) __out.push(d._)
             __out.push('end')
           })()"#,
    );
    assert_eq!(out, r#"["dialog","end"]"#);
    assert_eq!(asked.len(), 1);
}

/// the number is read back out of the contract, not out of a copy of itself: a test spelling
/// the constant stays green with it raised to anything
#[test]
fn an_iterator_asks_for_the_page_size_the_contract_states() {
    let (_out, asked) = run_ordered(
        ASYNC_GRANTS,
        "(async () => { for await (const d of inu.account().iterDialogs()) __out.push(d._) })()",
    );
    let stated = crate::testing::util::stated_number(
        crate::testing::util::CONTRACT,
        "(omitted, **{}**, which is telegram's own page)",
    );
    assert_eq!(asked[0].1, format!("0\n{stated}\n"), "the default batch is not the documented one");
}

/// history has no cursor to hold, so the offset is this side's to advance - and a fake that
/// ignores it is exactly the server that would otherwise page forever
#[test]
fn iter_history_advances_the_offset_and_stops_when_it_stops_moving() {
    let (out, asked) = run_ordered(
        ASYNC_GRANTS,
        r#"(async () => {
             for await (const m of inu.account().iterHistory('me', { batchSize: 2 })) __out.push(m.id)
             __out.push('end')
           })()"#,
    );
    assert_eq!(out, r#"[100,99,100,99,"end"]"#);
    let offsets: Vec<&str> = asked.iter().map(|(_, arg)| arg.split(SEPARATOR).nth(2).unwrap()).collect();
    assert_eq!(offsets, vec!["0", "99"], "the second page starts below the first page's oldest id");
    assert_eq!(asked.len(), 2, "a page that did not move the offset is the end of the history");
}

/// the default batch is larger than anything the fake has, so a short page ends it in one go
#[test]
fn a_page_shorter_than_the_batch_ends_the_history() {
    let (out, asked) = run_ordered(
        ASYNC_GRANTS,
        r#"(async () => {
             for await (const m of inu.account().iterHistory('me')) __out.push(m.id)
           })()"#,
    );
    assert_eq!(out, "[100,99]");
    assert_eq!(asked.len(), 1);
}

/// nothing an iterator was handed is looked at until the first `next()`, so every refusal is a
/// rejection there rather than a throw where it was made
#[test]
fn an_iterator_rejects_on_its_first_step_and_never_at_the_call() {
    let (out, asked) = run_ordered(
        &["account.read(peers,dialogs)"],
        r#"const a = inu.account();
           const push = (label) => (e) => __out.push(`${label}:${e.code}`);
           const bad = a.iterDialogs('main');
           const ungranted = a.iterHistory('me');
           const detached = (0, a.iterTopics)('me');
           __out.push(typeof bad.next, typeof ungranted.next, typeof detached.next);
           (async () => {
             await bad.next().catch(push('options'));
             await ungranted.next().catch(push('grant'));
             await detached.next().catch(push('detached'));
             await a.iterTopics(-1001).next().catch(push('topics'));
           })()"#,
    );
    assert_eq!(
        out,
        r#"["function","function","function","options:invalid-argument","grant:not-granted","detached:invalid-argument","topics:invalid-argument"]"#,
    );
    assert_eq!(asked.len(), 1, "only the forum check reached the host at all");
}

/// an iterator holds a cursor between steps, and the table it holds it in is bounded - so
/// abandoning it for that many other pages is a real case rather than a theoretical one
#[test]
fn an_iterator_whose_cursor_was_evicted_ends_with_invalid_argument() {
    let (out, _asked) = run_ordered(
        ASYNC_GRANTS,
        &format!(
            r#"(async () => {{
                 const a = inu.account();
                 const it = a.iterDialogs({{ batchSize: 2 }});
                 __out.push((await it.next()).value._, (await it.next()).value._);
                 for (let i = 0; i < {CURSOR_LIMIT}; i++) await a.getDialogs({{ limit: 2 }});
                 try {{ await it.next(); __out.push('no-throw') }} catch (e) {{ __out.push(e.code) }}
               }})()"#
        ),
    );
    assert_eq!(out, r#"["dialog","dialog","invalid-argument"]"#);
}

/// same bound, stated: raising `CURSOR_LIMIT` with the contract left alone is the drift this
/// catches, and the test above would happily follow it
#[test]
fn the_cursor_table_holds_what_the_contract_says_it_holds() {
    assert_eq!(
        CURSOR_LIMIT as u64,
        crate::testing::util::stated_number(crate::testing::util::CONTRACT, "table holds **{} cursors at once**"),
    );
}

#[test]
fn resolve_peer_many_answers_in_place_and_only_the_misses_cost_a_request() {
    let (out, _asked) = run_ordered(
        ASYNC_GRANTS,
        r#"inu.account()
             .resolvePeerMany([222, { _: 'inputPeerSelf' }, 4242, 'telegram', 'nosuch'])
             .then((peers) => __out.push(peers.map((p) => (p === null ? null : p._))))"#,
    );
    assert_eq!(
        out, r#"[["inputPeerUser","inputPeerSelf",null,"inputPeerChannel",null]]"#,
        "a peer that does not resolve is null where it was asked about",
    );
}

/// the one refusal every element would have shared, so it fails the call instead of answering
/// with a list of nulls a plugin cannot tell from a list of unknown peers
#[test]
fn resolve_peer_many_fails_the_batch_for_a_missing_grant_or_a_non_peer() {
    let (out, asked) = run_ordered(
        &[],
        r#"const a = inu.account();
           const push = (label) => (e) => __out.push(`${label}:${e.code}:${e.grant ?? ''}`);
           a.resolvePeerMany([222]).catch(push('list'));
           a.resolvePeerMany([]).catch(push('empty'));"#,
    );
    assert_eq!(out, r#"["list:not-granted:account.read(peers)","empty:not-granted:account.read(peers)"]"#,);
    assert!(asked.is_empty());

    let (out, _asked) = run_ordered(
        ASYNC_GRANTS,
        r#"const a = inu.account();
           // @ts-nocheck
           a.resolvePeerMany('me').catch((e) => __out.push(`notlist:${e.code}`));
           a.resolvePeerMany([222, null]).catch((e) => __out.push(`nonpeer:${e.code}`));
           a.resolvePeerMany([]).then((peers) => __out.push(peers.length));"#,
    );
    assert_eq!(out, r#"["notlist:invalid-argument","nonpeer:invalid-argument",0]"#);
}

/// `null` in place means "there is no such peer" and nothing else, so an element that failed
/// for any other reason has to reach the caller as a failure
#[test]
fn a_resolve_that_fails_for_anything_but_not_found_fails_the_batch() {
    let (out, _asked) = run_ordered(
        ASYNC_GRANTS,
        r#"inu.account()
             .resolvePeerMany(['telegram', 'boom', 'nosuch'])
             .then((peers) => __out.push(peers.map((p) => (p === null ? null : p._))))
             .catch((e) => __out.push(`batch:${e.code}`))"#,
    );
    assert_eq!(out, r#"["batch:forbidden"]"#);
}

/// the bound is what the contract states, and it is observed as what the host is holding at
/// once rather than as the constant that produced it
#[test]
fn resolve_peer_many_keeps_the_stated_number_in_flight() {
    let (_rt, ctx, host, _state, _accounts) = setup(ASYNC_GRANTS);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"const many = [];
               for (let i = 0; i < 40; i++) many.push(`user${i}`);
               inu.account().resolvePeerMany(many)"#,
        )
        .unwrap()
    });
    let stated = crate::testing::util::stated_number(crate::testing::util::CONTRACT, "at most **{} in flight**");
    assert_eq!(
        host.resolves.borrow().len() as u64,
        stated,
        "more peers are being resolved at once than the contract allows"
    );
}

/// a pending read whose engine is torn down must release its resolvers, or `JS_FreeRuntime`
/// aborts the process on a `Persistent` that outlived it
#[test]
fn an_unanswered_read_is_released_at_dispose() {
    let (_rt, ctx, host, _state, _accounts) = setup(ASYNC_GRANTS);
    ctx.with(|ctx| ctx.eval::<(), _>("inu.account().getDialogs(); inu.account().getHistory('me')").unwrap());
    assert_eq!(host.fetch_log.borrow().len(), 2);
}

#[test]
fn the_bundled_reads_test_plugin_passes() {
    const ORACLE: &str = include_str!("../../../res/assets-debug/inu_plugins/reads-test.js");
    let (rt, ctx, host, state, _accounts) = setup(&crate::testing::util::manifest_grants(ORACLE));
    let lines = crate::testing::util::install_capturing_console(&ctx);
    ctx.with(|ctx| match ctx.eval::<(), _>(ORACLE) {
        Ok(()) => {}
        Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
        Err(e) => panic!("{e:?}"),
    });
    settle(&rt, &ctx, &state, &host);
    let lines = lines.borrow().clone();
    // exact rather than a floor: nothing here may SKIP against this fake, so a block that
    // stopped running - or a fixture that stopped existing - would otherwise take its
    // assertions with it and still pass
    crate::testing::util::assert_oracle_exact(&lines, "reads test done", 52);
}

#[test]
fn the_bundled_async_reads_test_plugin_passes() {
    const ORACLE: &str = include_str!("../../../res/assets-debug/inu_plugins/async-reads-test.js");
    let (rt, ctx, host, state, _accounts) = setup(&crate::testing::util::manifest_grants(ORACLE));
    let lines = crate::testing::util::install_capturing_console(&ctx);
    ctx.with(|ctx| match ctx.eval::<(), _>(ORACLE) {
        Ok(()) => {}
        Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
        Err(e) => panic!("{e:?}"),
    });
    settle(&rt, &ctx, &state, &host);
    let lines = lines.borrow().clone();
    // exact rather than a floor: a cursor that stopped being minted would otherwise take the
    // paging assertions with it and still pass. The two skips are what the fake's dialog list
    // has no shape for, and are named so a third one is a failure
    crate::testing::util::assert_oracle_exact_skipping(
        &lines,
        "async reads test done",
        35,
        &[
            "SKIP the service chat: no telegram chat here",
            "SKIP getTopics answers with a page: no forum among the first 100 dialogs",
        ],
    );
}

/// The one oracle whose subject is every api at once: what an *ungranted* call answers. It needs
/// the whole `inu` surface rather than this module's, so the fixture is here instead of a fifth
/// module growing a copy of the other four.
mod grant_boundary {
    use super::*;
    use crate::api::{ApiHost, KV_GET, KV_SET};
    use crate::engine::registry::Lifecycle;
    use crate::tg::rpc::RpcHost;

    /// answers nothing and records nothing: the plugin under test holds `kv` alone, so every
    /// other member has to be refused before it could reach any of this
    #[derive(Default)]
    struct TestBoundaryHost {
        kv: RefCell<HashMap<String, String>>,
        crossings: Cell<usize>,
    }

    impl ApiHost for TestBoundaryHost {
        fn kv(&self, op: i32, key: &str, value: &str) -> String {
            match op {
                KV_GET => match self.kv.borrow().get(key) {
                    Some(found) => format!("S{found}"),
                    None => "N".to_string(),
                },
                KV_SET => {
                    self.kv.borrow_mut().insert(key.to_string(), value.to_string());
                    "N".to_string()
                }
                _ => "Einternal: unexpected kv op".to_string(),
            }
        }

        fn ui_toast(&self, _text: &str) {
            self.crossings.set(self.crossings.get() + 1);
        }

        fn ui_dialog(&self, _request_id: i64, _options_json: &str) -> Option<String> {
            self.crossings.set(self.crossings.get() + 1);
            Some("no ui here".to_string())
        }

        fn ui_chooser(&self, _request_id: i64, _options_json: &str) -> Option<String> {
            self.crossings.set(self.crossings.get() + 1);
            Some("no ui here".to_string())
        }

        fn open_url(&self, _url: &str) {
            self.crossings.set(self.crossings.get() + 1);
        }

        fn clipboard_read(&self) -> String {
            self.crossings.set(self.crossings.get() + 1);
            String::new()
        }

        fn clipboard_write(&self, _text: &str) {
            self.crossings.set(self.crossings.get() + 1);
        }
    }

    impl RpcHost for TestBoundaryHost {
        fn on_register(&self, _methods: &[String], _callback_id: u32, _scope: &str) -> Option<String> {
            self.crossings.set(self.crossings.get() + 1);
            None
        }

        fn on_unregister(&self, _callback_id: u32) {}

        fn on_invoke(&self, _invoke_id: i64, _slot: i32, _request_wire: &str) -> Option<String> {
            self.crossings.set(self.crossings.get() + 1);
            None
        }

        fn on_next(&self, _dispatch_id: i64, _request_wire: &str) -> Option<String> {
            None
        }

        fn on_complete(&self, _dispatch_id: i64, _result_wire: &str) {}

        fn on_update_register(&self, _callback_id: u32, _types: &[String], _scope: &str) -> Option<String> {
            self.crossings.set(self.crossings.get() + 1);
            None
        }

        fn on_update_unregister(&self, _callback_id: u32) {}

        fn on_intercept_update_register(&self, _callback_id: u32, _types: &[String]) -> Option<String> {
            self.crossings.set(self.crossings.get() + 1);
            None
        }

        fn on_intercept_update_unregister(&self, _callback_id: u32) {}

        fn on_update_verdict(&self, _dispatch_id: i64, _deliver: bool) {}
    }

    #[test]
    fn the_bundled_grant_boundary_test_plugin_passes() {
        const ORACLE: &str = include_str!("../../../res/assets-debug/inu_plugins/grant-boundary-test.js");
        let rt = Runtime::new().unwrap();
        let ctx = Context::full(&rt).unwrap();
        let reads_host = TestReadsHost::new();
        let boundary = Rc::new(TestBoundaryHost::default());
        let grants = TestGrantHost::new(&crate::testing::util::manifest_grants(ORACLE)).as_host();
        let log: crate::Log = std::sync::Arc::new(|_| {});
        let lifecycle = Lifecycle::new();

        // the order `nativeInstallApi`/`nativeInstallRpc` install in, which is what makes the
        // `Account` prototype and the demuxed events exist
        let (reads_state, accounts, rpc_state) = ctx.with(|ctx| {
            install_plugin_error(&ctx).unwrap();
            let api_host: Rc<dyn ApiHost> = boundary.clone();
            crate::api::install_api(&ctx, api_host, grants.clone(), lifecycle.clone(), log.clone()).unwrap();
            let accounts = crate::tg::account::install_account(
                &ctx,
                TestAccountHost::with(ONE_ACCOUNT),
                grants.clone(),
                lifecycle.clone(),
                log.clone(),
            )
            .unwrap();
            let shared = crate::tl::utils::install_utils(&ctx).unwrap();
            crate::tl::message::install_message(&ctx, &shared).unwrap();
            let tl_host: Rc<dyn TlHost> = reads_host.clone();
            let views = TlViews::new(tl_host);
            let reads: Rc<dyn ReadsHost> = reads_host.clone();
            let reads_state =
                install_reads(&ctx, reads, grants.clone(), views.clone(), &shared, &accounts, log.clone()).unwrap();
            let rpc_host: Rc<dyn RpcHost> = boundary.clone();
            let rpc_state = crate::tg::rpc::install_rpc(
                &ctx,
                rpc_host,
                views,
                grants,
                lifecycle.clone(),
                Some(accounts.clone()),
                shared,
                log.clone(),
            )
            .unwrap();
            (reads_state, accounts, rpc_state)
        });
        let reads_state = Disposing::new(&ctx, reads_state, dispose);
        let accounts = crate::testing::util::DisposeOnDrop::new(&ctx, accounts, crate::tg::account::dispose);
        let rpc_state = crate::testing::util::DisposeOnDrop::new(&ctx, rpc_state, crate::tg::rpc::dispose);

        let lines = crate::testing::util::install_capturing_console(&ctx);
        ctx.with(|ctx| match ctx.eval::<(), _>(ORACLE) {
            Ok(()) => {}
            Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
            Err(e) => panic!("{e:?}"),
        });
        while rt.is_job_pending() {
            rt.execute_pending_job().ok();
        }

        let lines = lines.borrow().clone();
        crate::testing::util::assert_oracle_exact(&lines, "grant boundary test done", 24);
        // the point of the oracle, restated where it can be checked: a refusal is decided in the
        // engine, so nothing it asserts on ever reached a host at all
        assert!(reads_host.reads.borrow().is_empty(), "an ungranted read crossed");
        assert!(reads_host.fetch_log.borrow().is_empty(), "an ungranted fetch crossed");
        assert_eq!(boundary.crossings.get(), 0, "an ungranted api crossed");

        drop((reads_state, accounts, rpc_state));
    }
}

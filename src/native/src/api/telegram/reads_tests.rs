use super::*;
use crate::api::telegram::account::tests::TestAccountHost;
use crate::sandbox::grants::CachedGrantHost;
use rquickjs::{Context, Runtime};
use std::cell::RefCell;

// the kinds `reads.js` sends and Kotlin `PluginReads.KIND_*` receives; only the fake host has a
// reason to name them on this side, since the engine passes the number straight through
const KIND_PEER: i32 = 0;
const KIND_USER: i32 = 1;
const KIND_CHANNEL: i32 = 2;

#[derive(Clone)]
struct Entity {
  id: i64,
  is_user: bool,
  username: Option<String>,
  access_hash: i64,
}

/// stands in for `PluginReads` + `TlHandles`
#[derive(Default)]
struct TestReadsHost {
  entities: RefCell<Vec<Entity>>,
  /// `(dialog id, message id)` the app has in memory, mirroring stock keeping only the chat
  /// list's own messages there
  messages: RefCell<Vec<(i64, i32)>>,
  dialogs: RefCell<Vec<i64>>,
  self_id: Cell<i64>,
  handles: Rc<crate::testing::harness::FakeHandles>,
  reads: RefCell<Vec<(i32, i32, String)>>,
  resolves: RefCell<Vec<(i64, String, i32)>>,
  fetches: RefCell<Vec<(i64, i32, String)>>,
  /// every fetch that ever crossed as `peer|args|cursor`, never drained - `fetches` is the queue, this is the record
  fetch_log: RefCell<Vec<(i32, String)>>,
}

impl TestReadsHost {
  fn new() -> Rc<Self> {
    let host = Rc::new(TestReadsHost::default());
    host.self_id.set(111);
    host.entities.borrow_mut().extend([
      Entity {
        id: 111,
        is_user: true,
        username: Some("selfuser".into()),
        access_hash: 11,
      },
      Entity {
        id: 222,
        is_user: true,
        username: Some("alice".into()),
        access_hash: 22,
      },
      Entity {
        id: -1001,
        is_user: false,
        username: Some("newschan".into()),
        access_hash: 33,
      },
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
      KIND_CHANNEL => {
        format!(r#"J{{"_":"inputChannel","channel_id":"{}","access_hash":"{}"}}"#, -entity.id, entity.access_hash)
      }
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

  /// id 7 exists wherever it is asked for, including the common box; everything else is a `null`
  /// in the slot it was asked about, which is what the fetch has to keep lined up
  fn messages_wire(&self, spec: &str, ids: &[i64]) -> String {
    if spec != "D0" && self.dialog_id(spec).and_then(|id| self.entity(id)).is_none() {
      return NOT_CACHED.to_string();
    }
    ids
      .iter()
      .map(|id| {
        if *id != 7 {
          return "N".to_string();
        }
        self.handle_wire(FakeObject {
          name: "message".to_string(),
          fields: vec![("id".to_string(), "I7".to_string()), ("message".to_string(), "Shello".to_string())],
        })
      })
      .collect::<Vec<_>>()
      .join(&SEPARATOR.to_string())
  }

  fn dialog_page_wire(&self, payload: &str) -> String {
    if payload.is_empty() {
      let rows = [self.dialog_wire("S"), self.dialog_wire("S")].join(&SEPARATOR.to_string());
      format!("1715540640,7,111{SEPARATOR}{rows}")
    } else {
      format!("{SEPARATOR}{}", self.dialog_wire("S"))
    }
  }

  fn answer_fetch(&self, op: i32, crossed: &str) -> String {
    let mut parts = crossed.splitn(3, '|');
    let (peer, args, cursor) = (parts.next().unwrap(), parts.next().unwrap(), parts.next().unwrap());
    let args: serde_json::Value = serde_json::from_str(args).expect("fetch args are json");
    match op {
      OP_USER_FULL => match self.dialog_id(peer).filter(|id| *id > 0).and_then(|id| self.entity(id)) {
        None => NOT_CACHED.to_string(),
        Some(entity) => self.handle_wire(FakeObject {
          name: "userFull".to_string(),
          fields: vec![("id".to_string(), format!("S{}", entity.id)), ("about".to_string(), "Sbio".to_string())],
        }),
      },
      OP_CHAT_FULL => match self.dialog_id(peer).filter(|id| *id < 0).and_then(|id| self.entity(id)) {
        None => wrong_kind(KIND_CHANNEL),
        Some(entity) => self.handle_wire(FakeObject {
          name: "channelFull".to_string(),
          fields: vec![("id".to_string(), format!("S{}", -entity.id))],
        }),
      },
      OP_HISTORY => {
        let limit = args["limit"].as_u64().unwrap_or(0) as usize;
        self.history_wire(peer, limit)
      }
      OP_FETCH_MESSAGES => {
        let ids: Vec<i64> = args["ids"].as_array().unwrap().iter().map(|id| id.as_i64().unwrap()).collect();
        self.messages_wire(peer, &ids)
      }
      OP_DIALOGS => self.dialog_page_wire(cursor),
      OP_DIALOGS_CACHED => self.dialog_wire("S"),
      OP_CHAT_FOLDERS => {
        r#"J[{"id":0,"title":{"text":"All chats"},"emoticon":null,"colorIndex":null,"unreadCount":0,"dialogCount":1,"isDefault":true,"isChatlist":false,"pinned":[]}]"#
          .to_string()
      }
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
        ids
          .iter()
          .map(|id| self.message_wire(parts[0], id))
          .collect::<Vec<_>>()
          .join(&SEPARATOR.to_string())
      }
      OP_INPUT_PEER => {
        let kind = parts.get(1).and_then(|k| k.parse().ok()).unwrap_or(KIND_PEER);
        self.input_peer_wire(parts[0], kind)
      }
      OP_DRAFT => match self.dialog_id(parts[0]) {
        Some(id) if id == self.self_id.get() => r#"J{"text":"unsent"}"#.to_string(),
        _ => "N".to_string(),
      },
      OP_DIALOG_MUTED => {
        let muted = self.dialog_id(parts[0]) == Some(-1001) && parts.get(1) == Some(&"0");
        if muted { "B1" } else { "B0" }.to_string()
      }
      OP_MESSAGE_PREVIEW => {
        format!(r#"J{{"text":"{}|{}"}}"#, parts[0], parts.get(1).copied().unwrap_or("").replace('"', "'"))
      }
      OP_TOPIC => match (self.dialog_id(parts[0]), parts.get(1).copied()) {
        (Some(-1001), Some("7")) => self.handles.mint_wire("forumTopic", [("title".to_string(), "Stopic".to_string())]),
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

  fn account_fetch(
    &self,
    _account_id: i32,
    request_id: i64,
    op: i32,
    peer: &str,
    args: &str,
    cursor: &str,
  ) -> Option<String> {
    // parked for the same reason a resolve is: answering here would re-enter the context
    // this call is already inside
    let crossed = format!("{peer}|{args}|{cursor}");
    self.fetch_log.borrow_mut().push((op, crossed.clone()));
    self.fetches.borrow_mut().push((request_id, op, crossed));
    None
  }
}

type Disposing = crate::testing::harness::DisposeOnDrop<ReadsState>;
type Fixture = (
  Runtime,
  Context,
  Rc<TestReadsHost>,
  Disposing,
  crate::testing::harness::DisposeOnDrop<crate::api::telegram::account::AccountState>,
);

const ONE_ACCOUNT: &str = r#"[{"id":0,"userId":111,"isCurrent":true,"isPremium":false}]"#;

fn setup(grants: &[&str]) -> Fixture {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let host = TestReadsHost::new();
  let grants = CachedGrantHost::new(grants);
  let log: crate::Log = std::sync::Arc::new(|_| {});
  let (state, accounts) = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    let accounts = crate::api::telegram::account::install_account(
      &ctx,
      TestAccountHost::with(ONE_ACCOUNT),
      grants.clone(),
      crate::sandbox::registry::Lifecycle::new(),
      log.clone(),
      &inu,
    )
    .unwrap();
    let shared = crate::api::tl::utils::install_utils(&ctx, &inu).unwrap();
    crate::api::tl::message::install_message(&ctx, &shared, &inu).unwrap();
    let reads_host: Rc<dyn ReadsHost> = host.clone();
    let state = install_reads(
      &ctx,
      reads_host,
      grants,
      TlViews::new(host.handles.clone()),
      &shared,
      &accounts,
      log.clone(),
      &inu,
    )
    .unwrap();
    (state, accounts)
  });
  let state = Disposing::new(&ctx, state, |ctx, state| state.dispose(ctx));
  let accounts = crate::testing::harness::DisposeOnDrop::new(&ctx, accounts, |ctx, state| state.dispose(ctx));
  (rt, ctx, host, state, accounts)
}

const ALL_GRANTS: &[&str] = &["account.read(self,peers,dialogs,messages)"];

use crate::testing::harness::{eval_json, eval_unit, FakeObject};

use crate::testing::harness::catch_json;

/// drives the fake host the way `PluginReads` drives the real one: drain the microtask queue,
/// answer whatever it parked, repeat
fn settle(rt: &Runtime, ctx: &Context, state: &Rc<ReadsState>, host: &Rc<TestReadsHost>) {
  for _ in 0..64 {
    while rt.is_job_pending() {
      rt.execute_pending_job().ok();
    }
    if let Some((request_id, spec, kind)) = host.take_resolve() {
      let wire = host.answer_resolve(&spec, kind);
      state.settle(ctx, request_id, &wire);
      continue;
    }
    let Some((request_id, op, arg)) = host.take_fetch() else {
      return;
    };
    let wire = host.answer_fetch(op, &arg);
    state.settle(ctx, request_id, &wire);
  }
  panic!("the host queue never drained");
}

#[test]
fn every_getter_gates_on_its_own_account_read_scope() {
  let (_rt, ctx, host, _state, _accounts) = setup(&["account.read(peers)"]);
  for (call, scope) in [
    ("inu.account().getMe()", "self"),
    ("inu.account().getDialog('me')", "dialogs"),
    ("inu.account().getMessagesCached('me', 7)", "messages"),
    ("inu.account().getMessagesCached('me', [7])", "messages"),
    ("inu.account().isDialogMuted('me')", "dialogs"),
    ("inu.account().getTopicCached(-1001, 7)", "dialogs"),
    ("inu.account().previewMessage({ _: 'message' })", "messages"),
  ] {
    assert_eq!(
      catch_json(&ctx, call),
      format!(r#"[true,"not-granted","account.read({scope})","missing grant: account.read({scope})"]"#),
      "call: {call}",
    );
  }
  assert!(host.reads.borrow().is_empty(), "a refused call must not reach the host");

  let (_rt, ctx, host, _state, _accounts) = setup(ALL_GRANTS);
  eval_unit(&ctx, "inu.account().getMe(); inu.account().getDialog('me');");
  assert_eq!(host.reads.borrow().len(), 2);
}

/// stock owns what "muted" means, so this only pins that the topic reaches it and that the answer
/// crosses as a plain boolean rather than a handle
#[test]
fn is_dialog_muted_answers_a_boolean_and_carries_the_topic() {
  let (_rt, ctx, host, _state, _accounts) = setup(ALL_GRANTS);
  assert_eq!(eval_json(&ctx, "inu.account().isDialogMuted(-1001)"), "true");
  assert_eq!(eval_json(&ctx, "inu.account().isDialogMuted(-1001, { topicId: 7 })"), "false");
  assert_eq!(eval_json(&ctx, "inu.account().isDialogMuted('me')"), "false");
  let args: Vec<String> = host
    .reads
    .borrow()
    .iter()
    .filter(|(_, op, _)| *op == OP_DIALOG_MUTED)
    .map(|(_, _, arg)| arg.clone())
    .collect();
  assert_eq!(args, vec!["D-1001\n0", "D-1001\n7", "S\n0"], "a topic-less read still names one, as 0");
}

#[test]
fn get_topic_cached_answers_a_loaded_topic_and_null_otherwise() {
  let (_rt, ctx, _host, _state, _accounts) = setup(ALL_GRANTS);
  assert_eq!(eval_json(&ctx, "inu.account().getTopicCached(-1001, 7)._"), r#""forumTopic""#);
  assert_eq!(eval_json(&ctx, "inu.account().getTopicCached(-1001, 8)"), "null");
  assert_eq!(eval_json(&ctx, "inu.account().getTopicCached('me', 7)"), "null", "a user dialog has no topics");
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
    "getMessagesCached('me', 7)",
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

  let out = run_async(
    without_self,
    r#"
      const a = inu.account();
      const push = (label) => (e) => __out.push(`${label}:${e.code}:${e.grant}`);
      a.getHistory('me').catch(push('history'));
      a.getUserFull('me').catch(push('userFull'));
      a.getTopics('me').catch(push('topics'));
      a.resolvePeer('me').catch(push('resolvePeer'));
    "#,
  );
  assert_eq!(
    out,
    r#"["history:not-granted:account.read(self)","resolvePeer:not-granted:account.read(self)","topics:not-granted:account.read(self)","userFull:not-granted:account.read(self)"]"#,
  );
}

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
    eval_unit(&ctx, &format!("inu.account().{call};"));
  }
  let seen: Vec<String> = host.reads.borrow().iter().map(|(_, _, arg)| arg.clone()).collect();
  assert_eq!(
    seen,
    vec!["S", "S", "D222", "D222", "Ualice", "Ualice", "D-1001", "D-1000000001001", "D222", "S", "D222", "S"],
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

#[test]
fn a_drafts_topic_id_reaches_the_host() {
  let (_rt, ctx, host, _state, _accounts) = setup(ASYNC_GRANTS);
  eval_unit(&ctx, "inu.account().getDraft('me', { topicId: 7 });");
  assert_eq!(host.reads.borrow().last().unwrap().2, "S\n7");
  // omitted is the host's own default rather than a topic of its own
  eval_unit(&ctx, "inu.account().getDraft('me');");
  assert_eq!(host.reads.borrow().last().unwrap().2, "S\n0");
  assert_eq!(
    catch_json(&ctx, "inu.account().getDraft('me', { topicId: -1 })"),
    r#"[true,"invalid-argument",null,"getDraft: topicId must be a non-negative 32-bit integer"]"#,
  );
}

/// `0` crosses as the dialog id it is. The message reads give it a meaning - the common box - and
/// everywhere else it is a dialog nothing has, which is a miss rather than a refusal
#[test]
fn zero_is_a_dialog_id_that_only_the_message_reads_give_a_meaning() {
  let (_rt, ctx, host, _state, _accounts) = setup(ALL_GRANTS);
  eval_unit(&ctx, "inu.account().getMessagesCached(0, 7); inu.account().getMessagesCached(0, [7, 8]);");
  {
    let reads = host.reads.borrow();
    assert_eq!(reads[0].2, "D0\n7");
    assert_eq!(reads[1].2, "D0\n7\n8");
  }
  assert_eq!(eval_json(&ctx, "inu.account().getUser(0)"), "null");
  assert_eq!(eval_json(&ctx, "inu.account().getDialog('0')"), "null");
  assert_eq!(host.reads.borrow().last().unwrap().2, "D0");
}

#[test]
fn the_message_reads_take_one_id_or_a_list_of_them() {
  let (_rt, ctx, _host, _state, _accounts) = setup(ALL_GRANTS);
  assert_eq!(eval_json(&ctx, "inu.account().getMessagesCached('me', 7).id"), "7");
  assert_eq!(eval_json(&ctx, "inu.account().getMessagesCached('me', [7]).length"), "1");

  let out = run_async(
    ASYNC_GRANTS,
    r#"
      const a = inu.account();
      a.getMessages('me', 7).then((m) => __out.push(m === null ? 'null' : `one:${m.id}`));
      a.getMessages('me', [7, 8]).then((list) => __out.push(`many:${list.map((m) => m && m.id).join(',')}`));
      a.getMessages(0, 7).then((m) => __out.push(`box:${m && m.id}`));
      a.getMessages(4242, [7]).catch((e) => __out.push(e.code));
    "#,
  );
  assert_eq!(out, r#"["box:7","many:7,","not-found","one:7"]"#);
}

#[test]
fn a_cached_peer_resolves_without_asking_the_host_to_look_it_up() {
  let (rt, ctx, host, state, _accounts) = setup(ALL_GRANTS);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>("globalThis.__out = []; inu.account().resolvePeer(222).then((p) => __out.push(p._))")
      .unwrap()
  });
  settle(&rt, &ctx, &state, &host);
  assert_eq!(eval_json(&ctx, "__out"), r#"["inputPeerUser"]"#);
  assert!(host.resolves.borrow().is_empty(), "the cache answered, so nothing was looked up");
}

#[test]
fn an_already_built_input_peer_passes_straight_through() {
  let (rt, ctx, host, state, _accounts) = setup(&[]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
          globalThis.__out = [];
          const peer = { _: 'inputPeerUser', user_id: '222', access_hash: '22' };
          const a = inu.account();
          __out.push(a.resolvePeerCached(peer) === peer);
          a.resolvePeer(peer).then((p) => __out.push(p === peer));
          a.resolveUser({ _: 'inputUserSelf' }).then((p) => __out.push(p._));
        "#,
      )
      .unwrap()
  });
  settle(&rt, &ctx, &state, &host);
  assert_eq!(eval_json(&ctx, "__out"), r#"[true,true,"inputUserSelf"]"#);
  assert!(host.reads.borrow().is_empty(), "nothing was read to answer with the argument");
}

/// one prototype per engine, not one closure set per handle: a dispatch mints an `Account` for
/// every update and every intercepted request
#[test]
fn the_getters_live_on_one_shared_prototype() {
  let (_rt, ctx, _host, _state, _accounts) = setup(ALL_GRANTS);
  assert_eq!(
    eval_json(
      &ctx,
      r#"
        [
          Object.getPrototypeOf(inu.account()) === Object.getPrototypeOf(inu.account(0)),
          Object.hasOwn(inu.account(), 'getUser'),
          typeof inu.account().getUser,
        ]
      "#
    ),
    r#"[true,false,"function"]"#,
  );
}

const ASYNC_GRANTS: &[&str] = &["account.read(self,peers,dialogs,messages,history,draft)"];

fn run_async(grants: &[&str], code: &str) -> String {
  let (rt, ctx, host, state, _accounts) = setup(grants);
  eval_unit(&ctx, &format!("globalThis.__out = []; {code}"));
  settle(&rt, &ctx, &state, &host);
  // the order settlements land in is the order the host answered, which is not the order the
  // calls were made in - a sorted comparison is the only stable one
  eval_json(&ctx, "__out.slice().sort()")
}

#[test]
fn every_async_read_gates_on_its_own_account_read_scope() {
  let out = run_async(
    &["account.read(messages)"],
    r#"
      const a = inu.account();
      const push = (label) => (e) => __out.push(`${label}:${e.code}:${e.grant}`);
      a.getHistory('me').catch(push('history'));
      a.getDialogs().catch(push('dialogs'));
      a.getTopics('me').catch(push('topics'));
      a.getUserFull('me').catch(push('userFull'));
      a.getChatFull(-1001).catch(push('chatFull'));
      a.getDialogsCached().catch(push('dialogsCached'));
      a.getChatFoldersCached().catch(push('chatFolders'));
    "#,
  );
  assert_eq!(
    out,
    r#"["chatFolders:not-granted:account.read(dialogs)","chatFull:not-granted:account.read(peers)","dialogs:not-granted:account.read(dialogs)","dialogsCached:not-granted:account.read(dialogs)","history:not-granted:account.read(history)","topics:not-granted:account.read(dialogs)","userFull:not-granted:account.read(peers)"]"#,
  );
}

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
    r#"
      const a = inu.account();
      a.getUserFull('me').then((u) => __out.push(u.about), (e) => __out.push(`me:${e.code}`));
      a.getUserFull(222).catch((e) => __out.push(`other:${e.code}`));
    "#,
  );
  assert_eq!(out, r#"["bio","other:not-granted"]"#);
}

#[test]
fn history_comes_back_wrapped_and_an_empty_one_is_an_empty_array() {
  let out = run_async(
    ASYNC_GRANTS,
    r#"
      const a = inu.account();
      a.getHistory('me', { limit: 2 }).then((page) => {
        __out.push(Array.isArray(page), page.length, page[0] instanceof inu.Message, page[0].id)
      });
      a.getHistory('me', { limit: 0 }).then((page) => __out.push(page.length));
      a.getHistory(4242).catch((e) => __out.push(e.code));
    "#,
  );
  assert_eq!(out, r#"[0,100,2,"not-found",true,true]"#);
}

/// the offsets never reach JS: what the host is handed back is the payload it minted, and what
/// the plugin held was a token that means nothing outside this engine
#[test]
fn paging_hands_the_host_back_its_own_offsets_and_ends_at_a_short_page() {
  let (rt, ctx, host, state, _accounts) = setup(ASYNC_GRANTS);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
          globalThis.__out = [];
          inu.account().getDialogs({ limit: 2 }).then((first) => {
            __out.push(first.next.includes('111') || first.next.includes('1715540640'))
            return inu.account().getDialogs({ limit: 2, cursor: first.next })
          }).then((second) => __out.push(second.length, second.next))
        "#,
      )
      .unwrap()
  });
  settle(&rt, &ctx, &state, &host);
  assert_eq!(eval_json(&ctx, "__out"), "[false,1,null]");
  let asked: Vec<String> = host.fetch_log.borrow().iter().map(|(_, arg)| arg.clone()).collect();
  assert_eq!(
    asked,
    vec![r#"|{"folderId":0,"limit":2,"fields":null}|"#, r#"|{"folderId":0,"limit":2,"fields":null}|1715540640,7,111"#],
    "the second page carries the host's own offsets"
  );
}

/// an iterator names fields on every page it asks for, or only the first would carry anything
#[test]
fn an_iterator_pages_until_the_list_runs_out_with_its_fields_on_every_page() {
  let (out, asked) = run_ordered(
    ASYNC_GRANTS,
    r#"
      (async () => {
        for await (const d of inu.account().iterDialogs({ batchSize: 2, fields: ['top_message'] })) __out.push(d._)
        __out.push('end')
      })()
    "#,
  );
  assert_eq!(out, r#"["dialog","dialog","dialog","end"]"#);
  assert_eq!(asked.len(), 2, "the second page is the cursor's, and there is no third");
  assert_eq!(asked[0].1, r#"|{"folderId":0,"limit":2,"fields":["top_message"]}|"#);
  assert_eq!(asked[1].1, r#"|{"folderId":0,"limit":2,"fields":["top_message"]}|1715540640,7,111"#);
}

#[test]
fn a_cursor_that_did_not_come_from_this_list_never_crosses() {
  let (rt, ctx, host, state, _accounts) = setup(ASYNC_GRANTS);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
          globalThis.__out = [];
          const a = inu.account();
          a.getDialogs({ limit: 2 }).then((page) => {
            a.getTopics('me', { cursor: page.next }).catch((e) => __out.push(`brand:${e.code}`))
            a.getDialogs({ cursor: page.next + 'x' }).catch((e) => __out.push(`forged:${e.code}`))
            a.getDialogs({ cursor: 'not-a-cursor' }).catch((e) => __out.push(`invented:${e.code}`))
          })
        "#,
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

fn run_ordered(grants: &[&str], code: &str) -> (String, Vec<(i32, String)>) {
  let (rt, ctx, host, state, _accounts) = setup(grants);
  eval_unit(&ctx, &format!("globalThis.__out = []; {code}"));
  settle(&rt, &ctx, &state, &host);
  let asked = host.fetch_log.borrow().clone();
  (eval_json(&ctx, "__out"), asked)
}

/// `limit` is a total and cuts the last page short, which is the difference between it and
/// `batchSize`
#[test]
fn a_limit_stops_an_iterator_mid_page_and_asks_for_nothing_more() {
  let (out, asked) = run_ordered(
    ASYNC_GRANTS,
    r#"
      (async () => {
        for await (const d of inu.account().iterDialogs({ limit: 1, batchSize: 2 })) __out.push(d._)
        __out.push('end')
      })()
    "#,
  );
  assert_eq!(out, r#"["dialog","end"]"#);
  assert_eq!(asked.len(), 1);
}

#[test]
fn an_iterator_uses_the_default_page_size() {
  let (_out, asked) = run_ordered(
    ASYNC_GRANTS,
    "(async () => { for await (const d of inu.account().iterDialogs()) __out.push(d._) })()",
  );
  let stated = 100;
  assert_eq!(
    asked[0].1,
    format!(r#"|{{"folderId":0,"limit":{stated},"fields":null}}|"#),
    "the default batch is not the documented one"
  );
}

/// history has no cursor to hold, so the offset is this side's to advance - and a fake that
/// ignores it is exactly the server that would otherwise page forever
#[test]
fn iter_history_advances_the_offset_and_stops_when_it_stops_moving() {
  let (out, asked) = run_ordered(
    ASYNC_GRANTS,
    r#"
      (async () => {
        for await (const m of inu.account().iterHistory('me', { batchSize: 2 })) __out.push(m.id)
        __out.push('end')
      })()
    "#,
  );
  assert_eq!(out, r#"[100,99,100,99,"end"]"#);
  let offsets: Vec<i64> = asked
    .iter()
    .map(|(_, crossed)| {
      let args: serde_json::Value = serde_json::from_str(crossed.split('|').nth(1).unwrap()).unwrap();
      args["offsetId"].as_i64().unwrap()
    })
    .collect();
  assert_eq!(offsets, vec![0, 99], "the second page starts below the first page's oldest id");
  assert_eq!(asked.len(), 2, "a page that did not move the offset is the end of the history");
}

/// the default batch is larger than anything the fake has, so a short page ends it in one go
#[test]
fn a_page_shorter_than_the_batch_ends_the_history() {
  let (out, asked) = run_ordered(
    ASYNC_GRANTS,
    r#"
      (async () => {
        for await (const m of inu.account().iterHistory('me')) __out.push(m.id)
      })()
    "#,
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
    r#"
      const a = inu.account();
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
      })()
    "#,
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
      r#"
        (async () => {{
          const a = inu.account();
          const it = a.iterDialogs({{ batchSize: 2 }});
          __out.push((await it.next()).value._, (await it.next()).value._);
          for (let i = 0; i < {CURSOR_LIMIT}; i++) await a.getDialogs({{ limit: 2 }});
          try {{ await it.next(); __out.push('no-throw') }} catch (e) {{ __out.push(e.code) }}
        }})()
      "#
    ),
  );
  assert_eq!(out, r#"["dialog","dialog","invalid-argument"]"#);
}

#[test]
fn resolve_peer_many_answers_in_place_and_only_the_misses_cost_a_request() {
  let (out, _asked) = run_ordered(
    ASYNC_GRANTS,
    r#"
      inu.account()
        .resolvePeerMany([222, { _: 'inputPeerSelf' }, 4242, 'telegram', 'nosuch'])
        .then((peers) => __out.push(peers.map((p) => (p === null ? null : p._))))
    "#,
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
    r#"
      const a = inu.account();
      const push = (label) => (e) => __out.push(`${label}:${e.code}:${e.grant ?? ''}`);
      a.resolvePeerMany([222]).catch(push('list'));
      a.resolvePeerMany([]).catch(push('empty'));
    "#,
  );
  assert_eq!(out, r#"["list:not-granted:account.read(peers)","empty:not-granted:account.read(peers)"]"#,);
  assert!(asked.is_empty());

  let (out, _asked) = run_ordered(
    ASYNC_GRANTS,
    r#"
      const a = inu.account();
      a.resolvePeerMany('me').catch((e) => __out.push(`notlist:${e.code}`));
      a.resolvePeerMany([222, null]).catch((e) => __out.push(`nonpeer:${e.code}`));
      a.resolvePeerMany([]).then((peers) => __out.push(peers.length));
    "#,
  );
  assert_eq!(out, r#"["notlist:invalid-argument","nonpeer:invalid-argument",0]"#);
}

/// `null` in place means "there is no such peer" and nothing else, so an element that failed
/// for any other reason has to reach the caller as a failure
#[test]
fn a_resolve_that_fails_for_anything_but_not_found_fails_the_batch() {
  let (out, _asked) = run_ordered(
    ASYNC_GRANTS,
    r#"
      inu.account()
        .resolvePeerMany(['telegram', 'boom', 'nosuch'])
        .then((peers) => __out.push(peers.map((p) => (p === null ? null : p._))))
        .catch((e) => __out.push(`batch:${e.code}`))
    "#,
  );
  assert_eq!(out, r#"["batch:forbidden"]"#);
}

#[test]
fn resolve_peer_many_limits_in_flight_requests() {
  let (_rt, ctx, host, _state, _accounts) = setup(ASYNC_GRANTS);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
          const many = [];
          for (let i = 0; i < 40; i++) many.push(`user${i}`);
          inu.account().resolvePeerMany(many)
        "#,
      )
      .unwrap()
  });
  assert_eq!(
    host.resolves.borrow().len() as u64,
    8,
    "more peers are being resolved at once than the engine allows"
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
  const ORACLE: &str = crate::testing::test_plugin!("reads-test.js");
  let (rt, ctx, host, state, _accounts) = setup(&crate::testing::harness::manifest_grants(ORACLE));
  let lines = crate::testing::harness::install_capturing_console(&ctx);
  eval_unit(&ctx, ORACLE);
  settle(&rt, &ctx, &state, &host);
  let lines = lines.borrow().clone();
  crate::testing::harness::assert_oracle_exact(&lines, "reads test done", 75);
}

#[test]
fn the_bundled_async_reads_test_plugin_passes() {
  const ORACLE: &str = crate::testing::test_plugin!("async-reads-test.js");
  let (rt, ctx, host, state, _accounts) = setup(&crate::testing::harness::manifest_grants(ORACLE));
  let lines = crate::testing::harness::install_capturing_console(&ctx);
  eval_unit(&ctx, ORACLE);
  settle(&rt, &ctx, &state, &host);
  let lines = lines.borrow().clone();
  crate::testing::harness::assert_oracle_exact_skipping(
    &lines,
    "async reads test done",
    37,
    &[
      "SKIP the service chat: no telegram chat here",
      "SKIP getTopics answers with a page: no forum among the first 100 dialogs",
    ],
  );
}

/// The one oracle whose subject is every api at once: what an *ungranted* call answers. It needs
/// the whole `inu` surface rather than this module's, so the fixture is here instead of a fifth
/// module growing a copy of the other four.
#[test]
fn the_bundled_grant_boundary_test_plugin_passes() {
  const ORACLE: &str = crate::testing::test_plugin!("grant-boundary-test.js");
  let manifest = crate::testing::harness::manifest_grants(ORACLE);
  let (rt, ctx, boundary, _lifecycle, _dialogs, _logs) = crate::testing::harness::setup_apis(&manifest);
  let reads_host = TestReadsHost::new();
  let rpc_host = Rc::new(crate::api::telegram::rpc::tests::TestHost::default());
  let grants = CachedGrantHost::new(&manifest);
  let log: crate::Log = std::sync::Arc::new(|_| {});
  let (reads_state, accounts, rpc_state) = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    let accounts = crate::api::telegram::account::install_account(
      &ctx,
      TestAccountHost::with(ONE_ACCOUNT),
      grants.clone(),
      crate::sandbox::registry::Lifecycle::new(),
      log.clone(),
      &inu,
    )
    .unwrap();
    let shared = crate::api::tl::utils::install_utils(&ctx, &inu).unwrap();
    crate::api::tl::message::install_message(&ctx, &shared, &inu).unwrap();
    let views = TlViews::new(reads_host.handles.clone());
    let reads: Rc<dyn ReadsHost> = reads_host.clone();
    let reads_state =
      install_reads(&ctx, reads, grants.clone(), views.clone(), &shared, &accounts, log.clone(), &inu).unwrap();
    let rpc_state = crate::api::telegram::rpc::install_rpc(
      &ctx,
      rpc_host.clone(),
      views,
      grants,
      crate::sandbox::registry::Lifecycle::new(),
      Some(accounts.clone()),
      shared,
      log.clone(),
      &inu,
    )
    .unwrap();
    (reads_state, accounts, rpc_state)
  });
  let reads_state = Disposing::new(&ctx, reads_state, |ctx, state| state.dispose(ctx));
  let accounts = crate::testing::harness::DisposeOnDrop::new(&ctx, accounts, |ctx, state| state.dispose(ctx));
  let rpc_state = crate::testing::harness::DisposeOnDrop::new(&ctx, rpc_state, |ctx, state| state.dispose(ctx));

  let lines = crate::testing::harness::run_capturing_console(&rt, &ctx, ORACLE);
  crate::testing::harness::assert_oracle_exact(&lines, "grant boundary test done", 23);
  assert!(reads_host.reads.borrow().is_empty(), "an ungranted read crossed");
  assert!(reads_host.fetch_log.borrow().is_empty(), "an ungranted fetch crossed");
  assert!(boundary.toasts.borrow().is_empty() && boundary.bulletins.borrow().is_empty());
  assert!(boundary.dialogs.borrow().is_empty() && boundary.choosers.borrow().is_empty());
  assert!(boundary.prompts.borrow().is_empty() && boundary.opened.borrow().is_empty());
  assert!(boundary.writes.borrow().is_empty() && boundary.clipboard_reads.get() == 0);
  assert!(rpc_host.registered.borrow().is_empty() && rpc_host.update_registered.borrow().is_empty());
  assert!(rpc_host.intercept_update_registered.borrow().is_empty() && rpc_host.invoke_calls.borrow().is_empty());
  assert!(rpc_host.raw_calls.borrow().is_empty() && rpc_host.takeout_calls.borrow().is_empty());

  drop((reads_state, accounts, rpc_state));
}

#[test]
fn dialogs_cached_options_cross_as_one_wire() {
  for (options, wire) in [
    ("{ archive: 'exclude' }", r#"{"archive":0,"chatFolderId":null,"limit":0,"fields":null}"#),
    ("{ archive: 'only' }", r#"{"archive":1,"chatFolderId":null,"limit":0,"fields":null}"#),
    ("{ archive: 'keep' }", r#"{"archive":2,"chatFolderId":null,"limit":0,"fields":null}"#),
    ("{ chatFolderId: 0 }", r#"{"archive":0,"chatFolderId":0,"limit":0,"fields":null}"#),
    ("{ chatFolderId: 3, limit: 20 }", r#"{"archive":0,"chatFolderId":3,"limit":20,"fields":null}"#),
    (
      "{ fields: ['top_message', 'peer'] }",
      r#"{"archive":0,"chatFolderId":null,"limit":0,"fields":["top_message","peer"]}"#,
    ),
  ] {
    let (rt, ctx, host, state, _accounts) = setup(ALL_GRANTS);
    eval_unit(&ctx, &format!("inu.account().getDialogsCached({options})"));
    settle(&rt, &ctx, &state, &host);
    assert_eq!(host.fetch_log.borrow().last().unwrap().1, format!("|{wire}|"), "options: {options}");
  }
}

#[test]
fn refused_dialogs_cached_options_never_reach_the_host() {
  let (rt, ctx, host, state, _accounts) = setup(ALL_GRANTS);
  eval_unit(
    &ctx,
    r#"
      globalThis.__out = [];
      for (const options of [
        { archive: 'both' }, { archive: 'Exclude' }, { archive: 0 }, { archive: 'constructor' },
        { archive: 'keep', chatFolderId: 2 }, { chatFolderId: -1 },
        { fields: ['a,b'] }, { fields: ['a\nb'] }, { fields: [''] }, { fields: [7] }, { fields: 'top_message' },
        { fields: [{}] },
      ]) {
        inu.account().getDialogsCached(options).catch(e => __out.push(e.code))
      }
    "#,
  );
  settle(&rt, &ctx, &state, &host);
  assert_eq!(eval_json(&ctx, "new Set(__out).size === 1 && __out.length"), "12");
  assert_eq!(eval_json(&ctx, "__out[0]"), r#""invalid-argument""#);
  assert!(host.fetch_log.borrow().is_empty());
}

#[test]
fn an_integer_past_int32_never_reaches_the_host() {
  let (rt, ctx, host, state, _accounts) = setup(ALL_GRANTS);
  eval_unit(
    &ctx,
    r#"
      globalThis.__out = [];
      const push = e => __out.push(e.code);
      const a = inu.account();
      a.getHistory('me', { limit: 2 ** 31 }).catch(push);
      a.getHistory('me', { offsetId: 1e21 }).catch(push);
      a.getMessages('me', [2 ** 31]).catch(push);
      a.getDialogsCached({ chatFolderId: '99999999999' }).catch(push);
      try { a.getMessagesCached('me', -(2 ** 31) - 1) } catch (e) { push(e) }
    "#,
  );
  settle(&rt, &ctx, &state, &host);
  assert_eq!(
    eval_json(&ctx, "__out"),
    r#"["invalid-argument","invalid-argument","invalid-argument","invalid-argument","invalid-argument"]"#
  );
  assert!(host.fetch_log.borrow().is_empty());
  assert!(host.reads.borrow().is_empty());
}

/// a chat folder is app state rather than a TL object, so it crosses as plain json and arrives as
/// an ordinary array - no handle, nothing to release
#[test]
fn chat_folders_arrive_as_plain_objects() {
  let (rt, ctx, host, state, _accounts) = setup(ALL_GRANTS);
  eval_unit(
    &ctx,
    r#"
      globalThis.__out = [];
      inu.account().getChatFoldersCached()
        .then(list => __out.push(...list.map(f => [f.id, f.title.text, f.isDefault, f.pinned])))
    "#,
  );
  settle(&rt, &ctx, &state, &host);
  assert_eq!(eval_json(&ctx, "__out"), r#"[[0,"All chats",true,[]]]"#);
  assert_eq!(host.fetch_log.borrow().last().unwrap().0, OP_CHAT_FOLDERS);
}

/// only the app knows its own preview line, so this pins what reaches it: the message as a wire,
/// and whether spoilers are to be masked
#[test]
fn preview_message_sends_the_message_and_masks_spoilers_only_when_asked() {
  let (_rt, ctx, _host, _state, _accounts) = setup(ALL_GRANTS);
  assert_eq!(
    eval_json(&ctx, r#"inu.account().previewMessage({ _: 'message', id: 7 }).text"#),
    r#""0|J{'_':'message','id':7}""#,
  );
  assert_eq!(
    eval_json(&ctx, r#"inu.account().previewMessage({ _: 'message' }, { hideSpoilers: true }).text"#),
    r#""1|J{'_':'message'}""#,
  );
  // a `Message` is unwrapped to the TL value it holds, so either may be passed
  assert_eq!(
    eval_json(&ctx, r#"inu.account().previewMessage(new inu.Message({ _: 'message', id: 9 })).text"#),
    r#""0|J{'_':'message','id':9}""#,
  );
}

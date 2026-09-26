use super::*;
use crate::api::telegram::account::tests::TestAccountHost;
use crate::api::telegram::reads::ReadsHost;
use crate::sandbox::grants::CachedGrantHost;
use rquickjs::{Context, Runtime};
use std::cell::RefCell;
use std::fs;

/// one call the fake took but has not answered: `(request id, op, arg, values)`
type Parked = (i64, i32, String, Vec<String>);

/// stands in for `PluginWrites` + `PluginMedia`: it records every crossing, mirrors the two
/// rules the real host owns (a secret chat is `forbidden`, an uncached peer is `not-found`) and
/// answers each op with the wire shape that op's [`Shape`] expects.
struct TestWritesHost {
  calls: RefCell<Vec<(i32, String, Vec<String>)>>,
  /// parked rather than answered: settling inside this call would re-enter the context it is
  /// already inside, which is what `PluginWrites.answer` posts to globalQueue to avoid
  pending: RefCell<Vec<Parked>>,
  media_dir: crate::testing::harness::TestDir,
  downloaded: RefCell<Option<PathBuf>>,
  message_files: Cell<u32>,
  /// answers every transfer with an error wire instead of a result
  transfers_fail: Cell<bool>,
  /// the handle table `PluginReads.mint` stands for, so a send can answer with the message
  /// the server made rather than with nothing
  handles: Rc<crate::testing::harness::FakeHandles>,
}

/// what the download hands back, and what `getMessageFile` says is on disk
const CONTENT: &[u8] = b"hello world";

/// the dialog ids this fake pretends the app has an entity for; anything else is `not-found`
const CACHED: [i64; 3] = [111, -100, -1001];

impl TestWritesHost {
  fn new() -> Rc<Self> {
    Rc::new(TestWritesHost {
      calls: RefCell::new(Vec::new()),
      pending: RefCell::new(Vec::new()),
      media_dir: crate::testing::harness::TestDir::new("media"),
      downloaded: RefCell::new(None),
      message_files: Cell::new(0),
      transfers_fail: Cell::new(false),
      handles: Rc::default(),
    })
  }

  /// a *read-only* object handle, which is what `PluginReads.mint(readOnly = true)` answers
  /// with - everything an `Account` hands over is read-only
  fn handle_wire(&self, name: &str, fields: Vec<(&str, String)>) -> String {
    self.handles.mint_wire(name, fields)
  }

  /// the message a send or an edit resolves with. Its text is read back out of the request,
  /// so a call whose text never crossed cannot answer with one that matches.
  fn sent_message_wire(&self, arg: &str, id: i32) -> String {
    let text = read_json_field(arg, "text");
    self.handle_wire(
      "message",
      vec![("id", format!("I{id}")), ("message", format!("S{text}")), ("out", "B1".to_string())],
    )
  }

  fn take(&self) -> Option<Parked> {
    let mut pending = self.pending.borrow_mut();
    if pending.is_empty() {
      None
    } else {
      Some(pending.remove(0))
    }
  }

  /// the app's own copy of the media, written once and answered for every download. its
  /// directory is this host's, so it is unique per test (the suite runs in parallel and a
  /// sealed mtime is re-checked on every read, so a file two tests share reads back as
  /// `handle-expired`) and it goes away with the host rather than staying in the temp dir
  fn media_path(&self) -> PathBuf {
    let mut held = self.downloaded.borrow_mut();
    if held.is_none() {
      let path = self.media_dir.path().join("note.txt");
      fs::write(&path, CONTENT).unwrap();
      *held = Some(path);
    }
    held.clone().unwrap()
  }

  /// a `File` over an app-owned path seals the mtime and re-checks it on every read, so a
  /// download that reported a made-up one would read back as `handle-expired`
  fn media_mtime(&self) -> i64 {
    fs::metadata(self.media_path())
      .and_then(|m| m.modified())
      .ok()
      .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
      .map(|d| d.as_millis() as i64)
      .unwrap_or(0)
  }

  /// mirrors `PluginWrites.writePeer`: a `D<id>` carrying `DialogObject`'s encrypted bit is
  /// refused outright, an id nothing is cached for is `not-found`, and 'me' resolves
  fn peer_refusal(&self, arg: &str) -> Option<String> {
    let json = arg;
    for key in ["\"peer\":\"", "\"toPeer\":\"", "\"sendAs\":\""] {
      let Some(at) = json.find(key) else { continue };
      let rest = &json[at + key.len()..];
      let spec = &rest[..rest.find('"').unwrap_or(0)];
      if let Some(id) = spec.strip_prefix('D').and_then(|d| d.parse::<i64>().ok()) {
        if id > 0 && (id & 0x4000_0000_0000_0000) != 0 {
          return Some("Pforbidden\n\n\n\nsecret chats are never reachable".to_string());
        }
        if !CACHED.contains(&id) {
          return Some("Pnot-found\n\n\n\nthis peer is not cached".to_string());
        }
      }
    }
    None
  }

  fn answer(&self, op: i32, arg: &str, values: &[String]) -> String {
    if self.transfers_fail.get() && matches!(op, OP_DOWNLOAD_MEDIA | OP_DOWNLOAD_MEDIA_TO_FILE | OP_UPLOAD_FILE) {
      return "Pnetwork\n\n\n\nthe transfer died mid-chunk".to_string();
    }
    match op {
      OP_DOWNLOAD_MEDIA => {
        let path = self.media_path();
        format!(
          "J{{\"path\":{},\"size\":{},\"mime\":\"text/plain\",\"name\":\"note.txt\",\"mtime\":{}}}",
          serde_json::to_string(&path.to_string_lossy()).unwrap(),
          CONTENT.len(),
          self.media_mtime(),
        )
      }
      OP_DOWNLOAD_MEDIA_TO_FILE => {
        format!("J{{\"path\":{}}}", serde_json::to_string(&self.media_path().to_string_lossy()).unwrap())
      }
      OP_UPLOAD_FILE => {
        // the staged file is read *here*, which is the assertion this fake exists for:
        // a `Blob` reached the host as content on disk rather than as bytes in js
        let Some(staged) = values.first().and_then(|w| w.strip_prefix('F')) else {
          return "Pinvalid-argument\n\n\n\nnot a staged file".to_string();
        };
        let path = read_json_field(staged, "path");
        let Ok(bytes) = fs::read(&path) else {
          return "Pnot-found\n\n\n\nthe staged file is not there".to_string();
        };
        let requested = read_json_field(arg, "fileName");
        let name = if requested.is_empty() { read_json_field(staged, "name") } else { requested };
        format!(
          "J{{\"_\":\"inputFile\",\"id\":\"1\",\"parts\":{},\"name\":{},\"md5_checksum\":\"\"}}",
          bytes.len(),
          serde_json::to_string(&name).unwrap(),
        )
      }
      OP_SEND_MESSAGE | OP_SEND_MEDIA | OP_EDIT_MESSAGE => self.sent_message_wire(arg, 7),
      _ => "N".to_string(),
    }
  }
}

fn read_json_field(text: &str, key: &str) -> String {
  let value: serde_json::Value = serde_json::from_str(text).unwrap_or_default();
  value[key].as_str().unwrap_or_default().to_string()
}

impl WritesHost for TestWritesHost {
  fn account_write(&self, _account_id: i32, request_id: i64, op: i32, arg: &str, values: &[String]) -> Option<String> {
    self.calls.borrow_mut().push((op, arg.to_string(), values.to_vec()));
    if let Some(refusal) = self.peer_refusal(arg) {
      return Some(refusal);
    }
    if matches!(op, OP_DOWNLOAD_MEDIA | OP_DOWNLOAD_MEDIA_TO_FILE)
      && !values.first().is_some_and(|v| v.contains("messageMediaDocument"))
    {
      return Some("Pinvalid-argument\n\n\n\nthis message has no media".to_string());
    }
    self.pending.borrow_mut().push((request_id, op, arg.to_string(), values.to_vec()));
    None
  }

  fn message_file(&self, _account_id: i32, value: &str) -> String {
    self.message_files.set(self.message_files.get() + 1);
    if !value.contains("messageMediaDocument") {
      return "N".to_string();
    }
    format!(
      "J{{\"path\":{},\"exists\":true}}",
      serde_json::to_string(&self.media_path().to_string_lossy()).unwrap(),
    )
  }
}

/// the one read this surface asks for: `resolvePeerCached`, which is what gates the oracle's
/// round trip and what an `OutgoingMessage.peer` retarget resolves through. Only [`CACHED`]
/// resolves; everything else misses, exactly as an uncached id does on a device.
struct SelfOnlyReadsHost;

impl ReadsHost for SelfOnlyReadsHost {
  fn account_read(&self, _account_id: i32, op: i32, arg: &str) -> String {
    let spec = arg.split("\n").next().unwrap_or("");
    if op != crate::api::telegram::reads::OP_INPUT_PEER {
      return "N".to_string();
    }
    if spec == "S" {
      return r#"J{"_":"inputPeerSelf"}"#.to_string();
    }
    match spec.strip_prefix('D').and_then(|d| d.parse::<i64>().ok()) {
      Some(id) if CACHED.contains(&id) && id > 0 => {
        format!(r#"J{{"_":"inputPeerUser","user_id":"{id}","access_hash":"{}"}}"#, id * 10)
      }
      Some(id) if CACHED.contains(&id) => {
        format!(r#"J{{"_":"inputPeerChannel","channel_id":"{}","access_hash":"{}"}}"#, -id, -id * 10)
      }
      _ => "N".to_string(),
    }
  }

  fn resolve_peer(&self, _a: i32, _r: i64, _s: &str, _k: i32) -> Option<String> {
    Some("Pnot-found\n\n\n\nnot cached".to_string())
  }

  fn account_fetch(&self, _a: i32, _r: i64, _o: i32, _p: &str, _g: &str, _c: &str) -> Option<String> {
    Some("Pnot-found\n\n\n\nnot cached".to_string())
  }
}

const ONE_ACCOUNT: &str = r#"[{"id":0,"userId":111,"isCurrent":true,"isPremium":false}]"#;

type Fixture = (
  Runtime,
  Context,
  Rc<TestWritesHost>,
  crate::testing::harness::DisposeOnDrop<WritesState>,
  crate::testing::harness::DisposeOnDrop<crate::api::telegram::reads::ReadsState>,
  crate::testing::harness::DisposeOnDrop<crate::api::telegram::account::AccountState>,
  crate::testing::harness::TestDir,
);

fn install_test_accounts<'js>(
  ctx: &rquickjs::Ctx<'js>,
  grants: &Rc<dyn crate::sandbox::grants::GrantHost>,
  log: &crate::Log,
  inu: &crate::api::Globals<'js>,
) -> Rc<crate::api::telegram::account::AccountState> {
  crate::api::telegram::account::install_account(
    ctx,
    TestAccountHost::with(ONE_ACCOUNT),
    grants.clone(),
    crate::sandbox::registry::Lifecycle::new(),
    log.clone(),
    inu,
  )
  .unwrap()
}

fn install_test_reads<'js>(
  ctx: &rquickjs::Ctx<'js>,
  grants: &Rc<dyn crate::sandbox::grants::GrantHost>,
  views: &Rc<TlViews>,
  reads_host: Rc<dyn ReadsHost>,
  accounts: &Rc<crate::api::telegram::account::AccountState>,
  log: &crate::Log,
  inu: &crate::api::Globals<'js>,
) -> (rquickjs::Object<'js>, Rc<crate::api::telegram::reads::ReadsState>) {
  let shared = crate::api::tl::utils::install_utils(ctx, inu).unwrap();
  crate::api::tl::message::install_message(ctx, &shared, inu).unwrap();
  let reads = crate::api::telegram::reads::install_reads(
    ctx,
    reads_host,
    grants.clone(),
    views.clone(),
    &shared,
    accounts,
    log.clone(),
    inu,
  )
  .unwrap();
  (shared, reads)
}

fn setup(grants: &[&str]) -> Fixture {
  setup_with_limit(grants, TRANSFER_LIMIT_BYTES)
}

fn setup_with_limit(grants: &[&str], transfer_limit: u64) -> Fixture {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let host = TestWritesHost::new();
  let grants: Rc<dyn GrantHost> = CachedGrantHost::new(grants);
  let log: crate::Log = std::sync::Arc::new(|_| {});
  let dir = crate::testing::harness::TestDir::new("stage");
  let empty = Rc::new(SelfOnlyReadsHost);
  let (writes, reads, accounts) = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    let accounts = install_test_accounts(&ctx, &grants, &log, &inu);
    crate::testing::harness::install_sandbox_globals(&ctx, dir.path()).unwrap();
    let views = TlViews::new(host.handles.clone());
    let (shared, reads) = install_test_reads(&ctx, &grants, &views, empty.clone(), &accounts, &log, &inu);
    let deps = WritesDeps {
      host: host.clone(),
      grants,
      views,
      stage_dir: dir.path().to_path_buf(),
      log: log.clone(),
    };
    let writes = install_writes_with_limit(&ctx, deps, &shared, &accounts, &inu, transfer_limit).unwrap();
    (writes, reads, accounts)
  });
  let writes = crate::testing::harness::DisposeOnDrop::new(&ctx, writes, |ctx, state| state.dispose(ctx));
  let reads = crate::testing::harness::DisposeOnDrop::new(&ctx, reads, |ctx, state| state.dispose(ctx));
  let accounts = crate::testing::harness::DisposeOnDrop::new(&ctx, accounts, |ctx, state| state.dispose(ctx));
  (rt, ctx, host, writes, reads, accounts, dir)
}

const ALL_WRITES: &[&str] =
  &["account.read(peers,messages,history)", "account.write(send,edit,delete,forward,react,read,typing,draft)"];

/// drives the fake the way `PluginWrites` drives the real one: drain the microtask queue, answer
/// whatever it parked (reporting progress for the transfers first), repeat
fn settle(rt: &Runtime, ctx: &Context, state: &Rc<WritesState>, host: &Rc<TestWritesHost>) {
  for _ in 0..64 {
    while rt.is_job_pending() {
      rt.execute_pending_job().ok();
    }
    let Some((request_id, op, arg, values)) = host.take() else {
      return;
    };
    if matches!(op, OP_DOWNLOAD_MEDIA | OP_DOWNLOAD_MEDIA_TO_FILE | OP_UPLOAD_FILE) {
      let total = CONTENT.len() as i64;
      // deliberately short of `total`: the last thing the host reports is 8/11, so a
      // terminal [11,11] can only have come from `finish` and an `abandon` in its place
      // leaves the plugin on the 8/11 the window was withholding
      for chunk in 1..=4 {
        state.report_progress(ctx, request_id, chunk * total / 5, total);
      }
    }
    let wire = host.answer(op, &arg, &values);
    state.settle(ctx, request_id, &wire);
  }
  panic!("the host queue never drained");
}

use crate::testing::harness::catch_json;

fn run_async(grants: &[&str], code: &str) -> (String, Rc<TestWritesHost>) {
  let (rt, ctx, host, state, _reads, _accounts, _dir) = setup(grants);
  crate::testing::harness::eval_unit(&ctx, &format!("globalThis.__out = []; {code}"));
  settle(&rt, &ctx, &state, &host);
  let out = crate::testing::harness::eval_json(&ctx, "__out");
  (out, host)
}

#[test]
fn a_missing_scope_rejects_with_the_grant_it_needs_and_never_crosses() {
  let calls = [
    ("edit", "editMessage(1, 1, 'x')"),
    ("delete", "deleteMessages(1, [1])"),
    ("react", "setReaction(1, 1, [])"),
    ("read", "readHistory(1)"),
    ("typing", "sendTyping(1)"),
    ("draft", "setDraft(1, 'x')"),
    ("forward", "forwardMessages(1, [1], 2)"),
  ];
  let code: String = calls
    .iter()
    .map(|(_, call)| {
      format!("inu.account().{call}.then(() => __out.push('ok'), (e) => __out.push(`${{e.code}}:${{e.grant}}`));")
    })
    .collect();
  let (out, host) = run_async(&["account.write(send)"], &code);
  let expected: Vec<String> = calls.iter().map(|(scope, _)| format!("not-granted:account.write({scope})")).collect();
  assert_eq!(out, serde_json::to_string(&expected).unwrap());
  assert!(host.calls.borrow().is_empty(), "a refused write must not reach the host");
}

#[test]
fn a_peer_crosses_as_a_spec_and_the_options_as_scalars() {
  let (out, host) = run_async(
    ALL_WRITES,
    r#"
      inu.account()
        .sendMessage('@Durov', { text: 'hi', entities: [] }, {
          replyToMessageId: 5, topicId: 9, silent: true, scheduleDate: 100, sendAs: -1001,
        })
        .then(() => __out.push('sent'), (e) => __out.push(e.code))
    "#,
  );
  let calls = host.calls.borrow();
  let (op, arg, values) = calls.first().expect("the send must cross");
  assert_eq!(*op, OP_SEND_MESSAGE);
  assert!(arg.contains(r#""peer":"Udurov""#), "got: {arg}");
  assert!(arg.contains(r#""sendAs":"D-1001""#), "got: {arg}");
  assert!(arg.contains(r#""replyTo":5"#) && arg.contains(r#""topicId":9"#), "got: {arg}");
  assert!(arg.contains(r#""silent":true"#) && arg.contains(r#""scheduleDate":100"#), "got: {arg}");
  assert!(values.is_empty(), "a text send carries no values");
  assert_eq!(out, r#"["sent"]"#);
}

/// `common.d.ts` declares these `Promise<void>`, and the host answers a null wire: a plugin
/// writing `await acc.readHistory(...) === undefined` is entitled to be right
#[test]
fn the_void_members_resolve_with_undefined_rather_than_the_hosts_null() {
  let (out, _) = run_async(
    ALL_WRITES,
    r#"
      const a = inu.account()
      const push = (label) => (v) => __out.push(`${label}:${v === undefined}`)
      a.setReaction(111, 1, ['x']).then(push('react'), (e) => __out.push(`react:${e.code}`))
      a.readHistory(111).then(push('read'), (e) => __out.push(`read:${e.code}`))
    "#,
  );
  assert_eq!(out, r#"["react:true","read:true"]"#);
}

#[test]
fn a_blob_reaches_the_host_as_a_file_and_the_staged_copy_does_not_outlive_the_call() {
  let (rt, ctx, host, state, _r, _a, dir) = setup(ALL_WRITES);
  ctx.with(|ctx| {
    ctx.globals().set("__out", rquickjs::Array::new(ctx.clone()).unwrap()).unwrap();
    ctx
      .eval::<(), _>(
        r#"
          const f = new File([new Uint8Array([1,2,3,4])], 'payload.bin', { type: 'application/octet-stream' })
          inu.account().uploadFile(f).then(
            (input) => __out.push(`${input._}:${input.name}:${input.parts}`),
            (e) => __out.push(e.code),
          )
        "#,
      )
      .unwrap();
  });
  settle(&rt, &ctx, &state, &host);
  let out = crate::testing::harness::eval_json(&ctx, "__out");
  assert_eq!(out, r#"["inputFile:payload.bin:4"]"#, "the host read the staged bytes back");

  let calls = host.calls.borrow();
  let (op, _, values) = calls.first().expect("the upload must cross");
  assert_eq!(*op, OP_UPLOAD_FILE);
  assert!(values[0].starts_with("F{"), "a blob crosses as a file: {}", values[0]);
  assert!(values[0].contains("\"name\":\"payload.bin\""), "got: {}", values[0]);

  let left: Vec<_> = fs::read_dir(dir.path()).unwrap().filter_map(|e| e.ok()).collect();
  assert!(
    left.iter().all(|e| !e.file_name().to_string_lossy().starts_with("transfer-")),
    "a staged copy outlived its transfer: {:?}",
    left.iter().map(|e| e.file_name()).collect::<Vec<_>>(),
  );
}

#[test]
fn the_plugins_prototype_cannot_rewrite_the_file_the_host_is_handed() {
  let (out, host) = run_async(
    ALL_WRITES,
    r#"
      Object.prototype.toJSON = function () { return { path: '/data/secret', name: 'forged', mime: '' } }
      Object.defineProperty(Object.prototype, 'mime', { set() {}, configurable: true })
      const sent = inu.account().uploadFile(new File([new Uint8Array([1])], 'payload.bin'))
      delete Object.prototype.toJSON
      delete Object.prototype.mime
      sent.then(() => __out.push('ok'), (e) => __out.push(e.code))
    "#,
  );
  assert_eq!(out, r#"["ok"]"#);
  let calls = host.calls.borrow();
  let (_, _, values) = calls.first().expect("the upload must cross");
  assert!(!values[0].contains("/data/secret"), "the plugin chose the path: {}", values[0]);
  assert!(values[0].contains("\"name\":\"payload.bin\""), "got: {}", values[0]);
  assert!(values[0].contains("\"mime\":"), "a prototype setter swallowed a field: {}", values[0]);
}

/// what a test reads back out of a rejected transfer: the code, and the two numbers
/// `common.d.ts` promises a `quota-exceeded` carries
const REPORT_QUOTA: &str = "(e) => __out.push(`${e.code}:${e.usage}:${e.quota}`)";

#[test]
fn a_transfer_past_the_staging_cap_is_refused_before_a_byte_is_written() {
  let (rt, ctx, host, state, _r, _a, dir) = setup_with_limit(ALL_WRITES, 8);
  ctx.with(|ctx| {
    ctx.globals().set("__out", rquickjs::Array::new(ctx.clone()).unwrap()).unwrap();
    ctx
      .eval::<(), _>(format!(
        r#"
          const a = inu.account()
          const ok = () => __out.push('staged')
          a.uploadFile(new Blob([new Uint8Array(9)])).then(ok, {REPORT_QUOTA})
          a.uploadFile(new Uint8Array(9)).then(ok, {REPORT_QUOTA})
          a.sendMedia(111, new Blob([new Uint8Array(9)])).then(ok, {REPORT_QUOTA})
          // one byte under, so the refusal is the cap and not the shape of the call
          a.uploadFile(new Blob([new Uint8Array(8)])).then(ok, {REPORT_QUOTA})
        "#
      ))
      .unwrap();
  });
  settle(&rt, &ctx, &state, &host);
  let out = crate::testing::harness::eval_json(&ctx, "__out");
  assert_eq!(
    out, r#"["quota-exceeded:9:8","quota-exceeded:9:8","quota-exceeded:9:8","staged"]"#,
    "usage is what the transfer would have been and quota is the cap, both in bytes",
  );

  let staged: Vec<String> = host
    .calls
    .borrow()
    .iter()
    .flat_map(|(_, _, values)| values.clone())
    .filter(|wire| wire.starts_with('F'))
    .collect();
  assert_eq!(staged.len(), 1, "a refused transfer reached the host: {staged:?}");
  let written: Vec<_> = fs::read_dir(dir.path())
    .unwrap()
    .filter_map(|e| e.ok())
    .filter(|e| e.file_name().to_string_lossy().starts_with("transfer-"))
    .collect();
  assert!(written.is_empty(), "a refused transfer left a staged copy behind: {written:?}");
}

#[test]
fn a_path_is_gated_on_fs_and_the_relative_form_says_it_is_not_here_yet() {
  let (out, host) = run_async(
    ALL_WRITES,
    r#"
      const a = inu.account()
      a.uploadFile({ path: '/etc/hosts' }).then(() => __out.push('ok'), (e) => __out.push(`${e.code}:${e.grant}`))
      a.uploadFile({ path: 'own.bin' }).then(() => __out.push('ok'), (e) => __out.push(`${e.code}:${e.grant}`))
    "#,
  );
  assert_eq!(out, r#"["not-granted:unsafe.fs","not-granted:fs"]"#);
  assert!(host.calls.borrow().is_empty());

  let (out, _) = run_async(
    &["account.write(send)", "fs", "unsafe.fs"],
    r#"
      const a = inu.account()
      a.uploadFile({ path: 'own.bin' }).then(() => __out.push('ok'), (e) => __out.push(e.code))
    "#,
  );
  assert_eq!(out, r#"["unsupported"]"#, "the scoped directory arrives with inu.fs");
}

/// the other terminal call `write_result` owes the throttle. `common.d.ts` promises a plugin is
/// left on the last numbers the transfer really made, and a transfer that died has none of its
/// own - so the window's withheld report is flushed. `release` in `abandon`'s place would leave
/// the plugin on whichever report happened to land on a window boundary, forever.
#[test]
fn a_failed_transfer_ends_on_the_last_numbers_it_managed_to_report() {
  let (rt, ctx, host, state, _r, _a, _d) = setup(ALL_WRITES);
  host.transfers_fail.set(true);
  ctx.with(|ctx| {
    ctx.globals().set("__out", rquickjs::Array::new(ctx.clone()).unwrap()).unwrap();
    ctx
      .eval::<(), _>(
        r#"
          globalThis.__seen = []
          inu.account()
            .downloadMedia({ _: 'message', id: 1, media: { _: 'messageMediaDocument' } }, {
              onProgress: (loaded, total) => __seen.push([loaded, total]),
            })
            .then(() => __out.push('ok'), (e) => __out.push(e.code))
        "#,
      )
      .unwrap();
  });
  settle(&rt, &ctx, &state, &host);

  let out = crate::testing::harness::eval_json(&ctx, "[__out, __seen]");
  assert_eq!(
    out, r#"[["network"],[[2,11],[8,11]]]"#,
    "the leading edge, then the 8/11 the window was withholding when the transfer died",
  );
}

#[test]
fn get_message_file_is_synchronous_and_gated_on_the_messages_scope() {
  let (_rt, ctx, host, _w, _r, _a, _d) = setup(&["account.read(peers)"]);
  assert_eq!(
    catch_json(&ctx, "inu.account().getMessageFile({ _: 'message', id: 1 })"),
    r#"[true,"not-granted","account.read(messages)","missing grant: account.read(messages)"]"#,
  );
  assert_eq!(host.message_files.get(), 0, "a refused read must not cross");

  let (_rt, ctx, host, _w, _r, _a, _d) = setup(ALL_WRITES);
  let out = ctx.with(|ctx| {
    ctx
      .eval::<String, _>(
        r#"
          JSON.stringify([
            inu.account().getMessageFile({ _: 'message', id: 1 }),
            inu.account().getMessageFile({ _: 'message', id: 1, media: { _: 'messageMediaDocument' } }).exists,
          ])
        "#,
      )
      .unwrap()
  });
  assert_eq!(out, "[null,true]");
  assert_eq!(host.message_files.get(), 2);
}

type SendFixture = (
  Runtime,
  Context,
  Rc<crate::api::telegram::rpc::tests::TestHost>,
  crate::testing::harness::DisposeOnDrop<crate::api::telegram::rpc::RpcState>,
  crate::testing::harness::DisposeOnDrop<crate::api::telegram::reads::ReadsState>,
  crate::testing::harness::DisposeOnDrop<crate::api::telegram::account::AccountState>,
);

/// the install order a device uses: the account api, then the read surface whose prototype an
/// `Account` answers `resolvePeerCached` from, then the rpc chain `interceptSendMessage` is a
/// narrowing of
fn setup_send(grants: &[&str]) -> SendFixture {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let rpc_host = Rc::new(crate::api::telegram::rpc::tests::TestHost::default());
  let grants: Rc<dyn GrantHost> = CachedGrantHost::new(grants);
  let log: crate::Log = std::sync::Arc::new(|_| {});
  let peers = Rc::new(SelfOnlyReadsHost);
  let views = TlViews::new(TestWritesHost::new().handles.clone());
  let (rpc, reads, accounts) = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    let accounts = install_test_accounts(&ctx, &grants, &log, &inu);
    let (shared, reads) = install_test_reads(&ctx, &grants, &views, peers.clone(), &accounts, &log, &inu);
    let rpc_host_dyn: Rc<dyn crate::api::telegram::rpc::RpcHost> = rpc_host.clone();
    let rpc = crate::api::telegram::rpc::install_rpc(
      &ctx,
      rpc_host_dyn,
      views.clone(),
      grants,
      crate::sandbox::registry::Lifecycle::new(),
      Some(accounts.clone()),
      shared,
      log.clone(),
      &inu,
    )
    .unwrap();
    (rpc, reads, accounts)
  });
  let rpc = crate::testing::harness::DisposeOnDrop::new(&ctx, rpc, |ctx, state| state.dispose(ctx));
  let reads = crate::testing::harness::DisposeOnDrop::new(&ctx, reads, |ctx, state| state.dispose(ctx));
  let accounts = crate::testing::harness::DisposeOnDrop::new(&ctx, accounts, |ctx, state| state.dispose(ctx));
  (rt, ctx, rpc_host, rpc, reads, accounts)
}

const A_SEND: &str = r#"{"_":"messages.sendMessage","peer":{"_":"inputPeerUser","user_id":"7","access_hash":"3"},"message":"hi","random_id":"1"}"#;

fn run_one_send(fixture: &SendFixture, middleware: &str) -> Option<String> {
  let (_rt, ctx, host, rpc, _reads, _accounts) = fixture;
  ctx.with(|ctx| {
    ctx.globals().set("__out", rquickjs::Array::new(ctx.clone()).unwrap()).unwrap();
    ctx.eval::<(), _>(format!("inu.interceptSendMessage({middleware})")).unwrap();
  });
  let callback_id = host.registered.borrow().last().expect("the middleware never registered").1;
  rpc.dispatch(ctx, callback_id, 1, "messages.sendMessage", 0, &format!("J{A_SEND}"));
  host.next_calls.borrow().first().map(|(_, wire)| wire.clone())
}

fn read_out_json(ctx: &Context) -> String {
  crate::testing::harness::eval_json(&ctx, "__out")
}

#[test]
fn retargeting_a_send_writes_the_peer_the_account_resolved_and_never_the_bare_id() {
  let fixture = setup_send(&["interceptSendMessage", "account.read(peers)"]);
  let next =
    run_one_send(&fixture, "({ message: m }) => { m.peer = 111; return 'send' }").expect("the send never went out");
  assert!(
    next.contains(r#""peer":{"_":"inputPeerUser","user_id":"111","access_hash":"1110"}"#),
    "the retarget must write the resolved InputPeer, not the dialog id: {next}",
  );
  assert!(!next.contains(r#""peer":111"#), "a bare dialog id reached the request: {next}");
}

#[test]
fn a_topic_retarget_writes_the_reply_that_lands_the_message_in_it() {
  let fixture = setup_send(&["interceptSendMessage", "account.read(peers)"]);
  let next =
    run_one_send(&fixture, "({ message: m }) => { m.topicId = 12; return 'send' }").expect("the send never went out");
  // a post into a topic with no reply of its own addresses the topic's own root message,
  // which is what makes it land in the topic at all
  assert!(
    next.contains(r#""reply_to":{"_":"inputReplyToMessage","reply_to_msg_id":12,"top_msg_id":12}"#),
    "{next}",
  );

  let fixture = setup_send(&["interceptSendMessage", "account.read(peers)"]);
  let next = run_one_send(&fixture, "({ message: m }) => { m.replyToMessageId = 33; m.topicId = 12; return 'send' }")
    .expect("the send never went out");
  assert!(
    next.contains(r#""reply_to":{"_":"inputReplyToMessage","reply_to_msg_id":33,"top_msg_id":12}"#),
    "a reply of its own is kept as the reply, with the topic beside it: {next}",
  );
}

#[test]
fn retargeting_at_a_peer_the_app_has_never_seen_is_not_found_and_leaves_the_send_alone() {
  let fixture = setup_send(&["interceptSendMessage", "account.read(peers)"]);
  let next = run_one_send(
    &fixture,
    "({ message: m }) => { try { m.peer = 4242424242 } catch (e) { __out.push([e.code, m.peer]) } return 'send' }",
  )
  .expect("the send never went out");
  assert_eq!(
    read_out_json(&fixture.1),
    r#"[["not-found",7]]"#,
    "the original peer must survive a failed retarget"
  );
  assert!(next.contains(r#""user_id":"7""#), "{next}");
}

#[test]
fn retargeting_needs_the_read_grant_on_top_of_the_apis_own() {
  let fixture = setup_send(&["interceptSendMessage"]);
  let next = run_one_send(
    &fixture,
    "({ message: m }) => { try { m.peer = 111 } catch (e) { __out.push([e.code, e.grant]) } return 'send' }",
  )
  .expect("the send never went out");
  assert_eq!(read_out_json(&fixture.1), r#"[["not-granted","account.read(peers)"]]"#);
  assert!(next.contains(r#""user_id":"7""#), "a refused retarget must not touch the request: {next}");
}

#[test]
fn what_a_retarget_refuses_outright() {
  let fixture = setup_send(&["interceptSendMessage", "account.read(peers)"]);
  run_one_send(
    &fixture,
    r#"
      ({ message: m }) => {
        for (const bad of [0, null, undefined, 'me', {}]) {
          try { m.peer = bad; __out.push('accepted') } catch (e) { __out.push(e.code) }
        }
        return 'send'
      }
    "#,
  );
  // a dialog id, never an `InputPeerLike`: the getter answers one, so the setter takes one
  assert_eq!(
    read_out_json(&fixture.1),
    r#"["invalid-argument","invalid-argument","invalid-argument","invalid-argument","invalid-argument"]"#,
  );
}

fn run_bundled_oracle(source: &str, done: &str, count: usize) {
  let (rt, ctx, host, state, _r, _a, _d) = setup(&crate::testing::harness::manifest_grants(source));
  let lines = crate::testing::harness::install_capturing_console(&ctx);
  crate::testing::harness::eval_unit(&ctx, source);
  settle(&rt, &ctx, &state, &host);
  let lines = lines.borrow().clone();
  crate::testing::harness::assert_oracle_exact(&lines, done, count);
}

#[test]
fn the_bundled_writes_test_plugin_passes() {
  run_bundled_oracle(crate::testing::test_plugin!("writes-test.js"), "writes test done", 42);
}

#[test]
fn the_bundled_media_test_plugin_passes() {
  run_bundled_oracle(crate::testing::test_plugin!("media-test.js"), "media test done", 30);
}

use super::*;
use crate::engine::error::{install_plugin_error, TestGrantHost};
use crate::engine::globals::RandomHost;
use crate::tg::account::tests::TestAccountHost;
use crate::tg::reads::ReadsHost;
use crate::tl::proxy::TlHost;
use rquickjs::{Context, Runtime};

/// one call the fake took but has not answered: `(request id, op, arg, values)`
type Parked = (i64, i32, String, Vec<String>);

/// a filesystem name nothing else in this process can pick. A counter rather than the clock:
/// the suite runs its tests in parallel and macOS hands two of them the same nanosecond often
/// enough that a shared staging directory was a one-in-six failure.
fn unique_name(tag: &str) -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);
    format!("inu-writes-{tag}-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed))
}

/// stands in for `PluginWrites` + `PluginMedia`: it records every crossing, mirrors the two
/// rules the real host owns (a secret chat is `forbidden`, an uncached peer is `not-found`) and
/// answers each op with the wire shape that op's [`Shape`] expects.
struct TestWritesHost {
    calls: RefCell<Vec<(i32, String, Vec<String>)>>,
    /// parked rather than answered: settling inside this call would re-enter the context it is
    /// already inside, which is what `PluginWrites.answer` posts to globalQueue to avoid
    pending: RefCell<Vec<Parked>>,
    /// the app's own media directory, removed with the host
    media_dir: tempdir::TempDir,
    downloaded: RefCell<Option<PathBuf>>,
    message_files: Cell<u32>,
    /// answers every transfer with an error wire instead of a result
    transfers_fail: Cell<bool>,
    /// the handle table `PluginReads.mint` stands for, so a send can answer with the message
    /// the server made rather than with nothing
    handles: crate::testing::util::FakeHandles,
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
            media_dir: tempdir::TempDir::new("media"),
            downloaded: RefCell::new(None),
            message_files: Cell::new(0),
            transfers_fail: Cell::new(false),
            handles: crate::testing::util::FakeHandles::default(),
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
        let text = serde_lite::parse(arg).get("text");
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
                    json_string(&path.to_string_lossy()),
                    CONTENT.len(),
                    self.media_mtime(),
                )
            }
            OP_DOWNLOAD_MEDIA_TO_FILE => {
                format!("J{{\"path\":{}}}", json_string(&self.media_path().to_string_lossy()))
            }
            OP_UPLOAD_FILE => {
                // the staged file is read *here*, which is the assertion this fake exists for:
                // a `Blob` reached the host as content on disk rather than as bytes in js
                let Some(staged) = values.first().and_then(|w| w.strip_prefix('F')) else {
                    return "Pinvalid-argument\n\n\n\nnot a staged file".to_string();
                };
                let described: serde_lite::Json = serde_lite::parse(staged);
                let path = described.get("path");
                let Ok(bytes) = fs::read(&path) else {
                    return "Pnot-found\n\n\n\nthe staged file is not there".to_string();
                };
                let requested = serde_lite::parse(arg).get("fileName");
                let name = if requested.is_empty() { described.get("name") } else { requested };
                format!(
                    "J{{\"_\":\"inputFile\",\"id\":\"1\",\"parts\":{},\"name\":{},\"md5_checksum\":\"\"}}",
                    bytes.len(),
                    json_string(&name),
                )
            }
            OP_SEND_MESSAGE | OP_SEND_MEDIA | OP_EDIT_MESSAGE => self.sent_message_wire(arg, 7),
            _ => "N".to_string(),
        }
    }
}

/// the two fields these tests read out of a flat json object, without a json crate
mod serde_lite {
    pub struct Json(pub String);

    pub fn parse(text: &str) -> Json {
        Json(text.to_string())
    }

    impl Json {
        pub fn get(&self, key: &str) -> String {
            let needle = format!("\"{key}\":\"");
            let Some(at) = self.0.find(&needle) else {
                return String::new();
            };
            let rest = &self.0[at + needle.len()..];
            let end = rest.find('"').unwrap_or(0);
            rest[..end].replace("\\\\", "\\").replace("\\\"", "\"")
        }
    }
}

impl WritesHost for TestWritesHost {
    fn account_write(
        &self,
        _account_id: i32,
        request_id: i64,
        op: i32,
        arg: &str,
        values: &[String],
    ) -> Option<String> {
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
        format!("J{{\"path\":{},\"exists\":true}}", json_string(&self.media_path().to_string_lossy()),)
    }
}

/// the one read this surface asks for: `resolvePeerCached`, which is what gates the oracle's
/// round trip and what an `OutgoingMessage.peer` retarget resolves through. Only [`CACHED`]
/// resolves; everything else misses, exactly as an uncached id does on a device.
struct SelfOnlyReadsHost;

impl ReadsHost for SelfOnlyReadsHost {
    fn account_read(&self, _account_id: i32, op: i32, arg: &str) -> String {
        let spec = arg.split(SEPARATOR).next().unwrap_or("");
        if op != crate::tg::reads::OP_INPUT_PEER {
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

    fn account_fetch(&self, _a: i32, _r: i64, _o: i32, _g: &str) -> Option<String> {
        Some("Pnot-found\n\n\n\nnot cached".to_string())
    }
}

/// the table behind the handles [`TestWritesHost::handle_wire`] mints, standing in for
/// `TlHandles`. Every one of them is read-only, so `tl_set` is unreachable through a view - the
/// proxy traps first, which is the half of that rule this file's oracle asserts.
impl TlHost for TestWritesHost {
    fn tl_get(&self, handle: i64, key: &str) -> String {
        self.handles.get(handle, key)
    }
    fn tl_set(&self, _handle: i64, _key: &str, _value: &str) -> Option<String> {
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
    fn tl_release(&self, _handle: i64) {}
}

struct NoRandom;

impl RandomHost for NoRandom {
    fn random_bytes(&self, out: &mut [u8]) -> bool {
        out.fill(7);
        true
    }
}

const ONE_ACCOUNT: &str = r#"[{"id":0,"userId":111,"isCurrent":true,"isPremium":false}]"#;

type Fixture = (
    Runtime,
    Context,
    Rc<TestWritesHost>,
    crate::testing::util::DisposeOnDrop<WritesState>,
    crate::testing::util::DisposeOnDrop<crate::tg::reads::ReadsState>,
    crate::testing::util::DisposeOnDrop<crate::tg::account::AccountState>,
    tempdir::TempDir,
);

mod tempdir {
    use std::path::{Path, PathBuf};

    /// a staging directory per test, removed with it - the engine's own is the host's job
    pub struct TempDir(PathBuf);

    impl TempDir {
        pub fn new(tag: &str) -> TempDir {
            let path = std::env::temp_dir().join(super::unique_name(tag));
            std::fs::create_dir_all(&path).unwrap();
            TempDir(path)
        }

        pub fn path(&self) -> &Path {
            &self.0
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
}

fn setup(grants: &[&str]) -> Fixture {
    setup_with_limit(grants, TRANSFER_LIMIT_BYTES)
}

fn setup_with_limit(grants: &[&str], transfer_limit: u64) -> Fixture {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let host = TestWritesHost::new();
    let grants = TestGrantHost::new(grants).as_host();
    let log: crate::Log = std::sync::Arc::new(|_| {});
    let dir = tempdir::TempDir::new("stage");
    let empty = Rc::new(SelfOnlyReadsHost);
    let (writes, reads, accounts) = ctx.with(|ctx| {
        install_plugin_error(&ctx).unwrap();
        let accounts = crate::tg::account::install_account(
            &ctx,
            TestAccountHost::with(ONE_ACCOUNT),
            grants.clone(),
            crate::engine::registry::Lifecycle::new(),
            log.clone(),
        )
        .unwrap();
        let random: Rc<dyn RandomHost> = Rc::new(NoRandom);
        let blobs = crate::engine::globals::install_globals(
            &ctx,
            random,
            dir.path(),
            crate::engine::deadline::ExternalMemory::new(),
        )
        .unwrap();
        let shared = crate::tl::utils::install_utils(&ctx).unwrap();
        crate::tl::message::install_message(&ctx, &shared).unwrap();
        let tl_host: Rc<dyn TlHost> = host.clone();
        let views = TlViews::new(tl_host);
        let reads_host: Rc<dyn ReadsHost> = empty.clone();
        let reads = crate::tg::reads::install_reads(
            &ctx,
            reads_host,
            grants.clone(),
            views.clone(),
            &shared,
            &accounts,
            log.clone(),
        )
        .unwrap();
        let deps = WritesDeps {
            host: host.clone(),
            grants,
            views,
            blobs,
            stage_dir: dir.path().to_path_buf(),
            log: log.clone(),
        };
        let writes = install_writes_with_limit(&ctx, deps, &shared, &accounts, transfer_limit).unwrap();
        (writes, reads, accounts)
    });
    let writes = crate::testing::util::DisposeOnDrop::new(&ctx, writes, dispose);
    let reads = crate::testing::util::DisposeOnDrop::new(&ctx, reads, crate::tg::reads::dispose);
    let accounts = crate::testing::util::DisposeOnDrop::new(&ctx, accounts, crate::tg::account::dispose);
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
                write_progress(rt, ctx, state, request_id, chunk * total / 5, total);
            }
        }
        let wire = host.answer(op, &arg, &values);
        write_result(rt, ctx, state, request_id, &wire);
    }
    panic!("the host queue never drained");
}

use crate::testing::util::catch_json;

/// runs `code` with `__out` collecting whatever it pushes, settling the host until it is done
fn run_async(grants: &[&str], code: &str) -> (String, Rc<TestWritesHost>) {
    let (rt, ctx, host, state, _reads, _accounts, _dir) = setup(grants);
    ctx.with(|ctx| {
        ctx.globals().set("__out", rquickjs::Array::new(ctx.clone()).unwrap()).unwrap();
        match ctx.eval::<(), _>(code) {
            Ok(()) => {}
            Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
            Err(e) => panic!("{e:?}"),
        }
    });
    settle(&rt, &ctx, &state, &host);
    let out = ctx.with(|ctx| ctx.eval::<String, _>("JSON.stringify(__out)").unwrap());
    (out, host)
}

#[test]
fn a_missing_scope_rejects_with_the_grant_it_needs_and_never_crosses() {
    let (out, host) = run_async(
        &["account.write(send)"],
        r#"const a = inu.account()
           const push = (label) => (e) => __out.push(`${label}:${e.code}:${e.grant}`)
           a.editMessage(1, 1, 'x').then(() => __out.push('edit:ok'), push('edit'))
           a.deleteMessages(1, [1]).then(() => __out.push('delete:ok'), push('delete'))
           a.setReaction(1, 1, []).then(() => __out.push('react:ok'), push('react'))
           a.readHistory(1).then(() => __out.push('read:ok'), push('read'))
           a.sendTyping(1).then(() => __out.push('typing:ok'), push('typing'))
           a.setDraft(1, 'x').then(() => __out.push('draft:ok'), push('draft'))
           a.forwardMessages(1, [1], 2).then(() => __out.push('forward:ok'), push('forward'))"#,
    );
    assert_eq!(
        out,
        r#"["edit:not-granted:account.write(edit)","delete:not-granted:account.write(delete)",
"react:not-granted:account.write(react)","read:not-granted:account.write(read)",
"typing:not-granted:account.write(typing)","draft:not-granted:account.write(draft)",
"forward:not-granted:account.write(forward)"]"#
            .replace('\n', "")
    );
    assert!(host.calls.borrow().is_empty(), "a refused write must not reach the host");
}

#[test]
fn a_peer_crosses_as_a_spec_and_the_options_as_scalars() {
    let (out, host) = run_async(
        ALL_WRITES,
        r#"inu.account()
             .sendMessage('@Durov', { text: 'hi', entities: [] }, {
               replyToMessageId: 5, topicId: 9, silent: true, scheduleDate: 100, sendAs: -1001,
             })
             .then(() => __out.push('sent'), (e) => __out.push(e.code))"#,
    );
    let calls = host.calls.borrow();
    let (op, arg, values) = calls.first().expect("the send must cross");
    assert_eq!(*op, OP_SEND_MESSAGE);
    assert!(arg.contains(r#""peer":"Udurov""#), "got: {arg}");
    assert!(arg.contains(r#""sendAs":"D-1001""#), "got: {arg}");
    assert!(arg.contains(r#""replyTo":"5""#) && arg.contains(r#""topicId":"9""#), "got: {arg}");
    assert!(arg.contains(r#""silent":true"#) && arg.contains(r#""scheduleDate":"100""#), "got: {arg}");
    assert!(values.is_empty(), "a text send carries no values");
    assert_eq!(out, r#"["sent"]"#);
}

#[test]
fn a_bad_argument_rejects_before_anything_crosses() {
    let (out, host) = run_async(
        ALL_WRITES,
        r#"const a = inu.account()
           const push = (e) => __out.push(e.code)
           a.sendMessage(0, 'hi').then(() => __out.push('ok'), push)
           a.sendMessage(1, 7).then(() => __out.push('ok'), push)
           a.sendMessage(1, { text: 'x', entities: 'bold' }).then(() => __out.push('ok'), push)
           a.sendMessage(1, 'hi', { silent: 'yes' }).then(() => __out.push('ok'), push)
           a.sendTyping(1, 'dancing').then(() => __out.push('ok'), push)
           a.setReaction(1, 1, ['']).then(() => __out.push('ok'), push)
           a.sendMultiMedia(1, []).then(() => __out.push('ok'), push)"#,
    );
    assert_eq!(
        out,
        r#"["invalid-argument","invalid-argument","invalid-argument","invalid-argument","invalid-argument","invalid-argument","invalid-argument"]"#
    );
    assert!(host.calls.borrow().is_empty(), "a malformed call must not reach the host");
}

/// `common.d.ts` declares these `Promise<void>`, and the host answers a null wire: a plugin
/// writing `await acc.readHistory(...) === undefined` is entitled to be right
#[test]
fn the_void_members_resolve_with_undefined_rather_than_the_hosts_null() {
    let (out, _) = run_async(
        ALL_WRITES,
        r#"const a = inu.account()
           const push = (label) => (v) => __out.push(`${label}:${v === undefined}`)
           a.deleteMessages(111, [1]).then(push('delete'), (e) => __out.push(`delete:${e.code}`))
           a.setReaction(111, 1, ['x']).then(push('react'), (e) => __out.push(`react:${e.code}`))
           a.readHistory(111).then(push('read'), (e) => __out.push(`read:${e.code}`))
           a.sendTyping(111).then(push('typing'), (e) => __out.push(`typing:${e.code}`))
           a.setDraft(111, 'x').then(push('draft'), (e) => __out.push(`draft:${e.code}`))"#,
    );
    assert_eq!(out, r#"["delete:true","react:true","read:true","typing:true","draft:true"]"#);
}

#[test]
fn a_blob_reaches_the_host_as_a_file_and_the_staged_copy_does_not_outlive_the_call() {
    let (rt, ctx, host, state, _r, _a, dir) = setup(ALL_WRITES);
    ctx.with(|ctx| {
        ctx.globals().set("__out", rquickjs::Array::new(ctx.clone()).unwrap()).unwrap();
        ctx.eval::<(), _>(
            r#"const f = new File([new Uint8Array([1,2,3,4])], 'payload.bin', { type: 'application/octet-stream' })
               inu.account().uploadFile(f).then(
                 (input) => __out.push(`${input._}:${input.name}:${input.parts}`),
                 (e) => __out.push(e.code),
               )"#,
        )
        .unwrap();
    });
    settle(&rt, &ctx, &state, &host);
    let out = ctx.with(|ctx| ctx.eval::<String, _>("JSON.stringify(__out)").unwrap());
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

/// what a test reads back out of a rejected transfer: the code, and the two numbers
/// `common.d.ts` promises a `quota-exceeded` carries
const REPORT_QUOTA: &str = "(e) => __out.push(`${e.code}:${e.usage}:${e.quota}`)";

/// the refusal below injects its own cap so a test does not have to move a quarter of a
/// gigabyte, which leaves the number the app ships pinned by nothing else
#[test]
fn the_staging_cap_is_the_size_the_contract_states() {
    use crate::testing::util::{stated_number, CONTRACT};
    let mb = stated_number(CONTRACT, "**one such copy is capped at {} MB**");
    assert_eq!(TRANSFER_LIMIT_BYTES, mb * 1024 * 1024);
    // `uploadFile` states it by pointing at `sendMedia`'s, so the two have to say one number
    assert_eq!(mb, stated_number(CONTRACT, "the same {} MB staging cap"));
}

#[test]
fn a_transfer_past_the_staging_cap_is_refused_before_a_byte_is_written() {
    let (rt, ctx, host, state, _r, _a, dir) = setup_with_limit(ALL_WRITES, 8);
    ctx.with(|ctx| {
        ctx.globals().set("__out", rquickjs::Array::new(ctx.clone()).unwrap()).unwrap();
        ctx.eval::<(), _>(format!(
            r#"const a = inu.account()
               const ok = () => __out.push('staged')
               a.uploadFile(new Blob([new Uint8Array(9)])).then(ok, {REPORT_QUOTA})
               a.uploadFile(new Uint8Array(9)).then(ok, {REPORT_QUOTA})
               a.sendMedia(111, new Blob([new Uint8Array(9)])).then(ok, {REPORT_QUOTA})
               // one byte under, so the refusal is the cap and not the shape of the call
               a.uploadFile(new Blob([new Uint8Array(8)])).then(ok, {REPORT_QUOTA})"#
        ))
        .unwrap();
    });
    settle(&rt, &ctx, &state, &host);
    let out = ctx.with(|ctx| ctx.eval::<String, _>("JSON.stringify(__out)").unwrap());
    assert_eq!(
        out, r#"["quota-exceeded:9:8","quota-exceeded:9:8","quota-exceeded:9:8","staged"]"#,
        "usage is what the transfer would have been and quota is the cap, both in bytes",
    );

    // the three refusals crossed nothing and wrote nothing; the one that fit did both
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
fn a_disposed_blob_is_handle_expired_rather_than_an_upload_of_nothing() {
    let (out, host) = run_async(
        ALL_WRITES,
        r#"const b = new Blob([new Uint8Array([1,2,3])])
           b.dispose()
           inu.account().uploadFile(b).then(() => __out.push('ok'), (e) => __out.push(e.code))"#,
    );
    assert_eq!(out, r#"["handle-expired"]"#);
    assert!(host.calls.borrow().is_empty(), "a dead blob must not cross");
}

#[test]
fn a_path_is_gated_on_fs_and_the_relative_form_says_it_is_not_here_yet() {
    let (out, host) = run_async(
        ALL_WRITES,
        r#"const a = inu.account()
           a.uploadFile({ path: '/etc/hosts' }).then(() => __out.push('ok'), (e) => __out.push(`${e.code}:${e.grant}`))
           a.uploadFile({ path: 'own.bin' }).then(() => __out.push('ok'), (e) => __out.push(`${e.code}:${e.grant}`))"#,
    );
    assert_eq!(out, r#"["not-granted:unsafe.fs","not-granted:fs"]"#);
    assert!(host.calls.borrow().is_empty());

    let (out, _) = run_async(
        &["account.write(send)", "fs", "unsafe.fs"],
        r#"const a = inu.account()
           a.uploadFile({ path: 'own.bin' }).then(() => __out.push('ok'), (e) => __out.push(e.code))"#,
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
        ctx.eval::<(), _>(
            r#"globalThis.__seen = []
               inu.account()
                 .downloadMedia({ _: 'message', id: 1, media: { _: 'messageMediaDocument' } }, {
                   onProgress: (loaded, total) => __seen.push([loaded, total]),
                 })
                 .then(() => __out.push('ok'), (e) => __out.push(e.code))"#,
        )
        .unwrap();
    });
    settle(&rt, &ctx, &state, &host);

    let out = ctx.with(|ctx| ctx.eval::<String, _>("JSON.stringify([__out, __seen])").unwrap());
    assert_eq!(
        out, r#"[["network"],[[2,11],[8,11]]]"#,
        "the leading edge, then the 8/11 the window was withholding when the transfer died",
    );
}

#[test]
fn a_download_answers_a_file_over_the_apps_own_copy_and_coalesces_its_progress() {
    let (out, host) = run_async(
        ALL_WRITES,
        r#"const seen = []
           inu.account()
             .downloadMedia({ _: 'message', id: 1, media: { _: 'messageMediaDocument' } }, {
               onProgress: (loaded, total) => seen.push([loaded, total]),
             })
             .then(
               async (file) => __out.push([file instanceof File, file.name, file.type, file.size, await file.text(), seen.length, seen[seen.length - 1]]),
               (e) => __out.push(e.code),
             )"#,
    );
    assert_eq!(
        out, r#"[[true,"note.txt","text/plain",11,"hello world",2,[11,11]]]"#,
        "four reports coalesce to a leading edge, and the transfer ends on its own total",
    );
    assert_eq!(host.calls.borrow().len(), 1);
}

#[test]
fn the_to_file_form_answers_a_path_and_nothing_else() {
    let (out, _) = run_async(
        ALL_WRITES,
        r#"inu.account()
             .downloadMediaToFile({ _: 'message', id: 1, media: { _: 'messageMediaDocument' } })
             .then((where) => __out.push([typeof where.path === 'string', where instanceof Blob]), (e) => __out.push(e.code))"#,
    );
    assert_eq!(out, r#"[[true,false]]"#);
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
        ctx.eval::<String, _>(
            r#"JSON.stringify([
                 inu.account().getMessageFile({ _: 'message', id: 1 }),
                 inu.account().getMessageFile({ _: 'message', id: 1, media: { _: 'messageMediaDocument' } }).exists,
               ])"#,
        )
        .unwrap()
    });
    assert_eq!(out, "[null,true]");
    assert_eq!(host.message_files.get(), 2);
}

#[test]
fn the_peer_refusals_are_the_hosts_and_reach_the_plugin_as_they_are() {
    let (out, _) = run_async(
        ALL_WRITES,
        r#"const a = inu.account()
           const push = (e) => __out.push(e.code)
           a.sendMessage('4611686018427387911', 'hi').then(() => __out.push('ok'), push)
           a.sendMessage(4242424242, 'hi').then(() => __out.push('ok'), push)"#,
    );
    assert_eq!(out, r#"["forbidden","not-found"]"#);
}

//
// It lives here rather than beside the other `interceptSendMessage` tests because retargeting a
// send is the one member of `OutgoingMessage` that reads through the `Account` handle, so the
// fixture needs the read surface installed as well as the chain - and that read is the whole
// point: `common.d.ts` says a retarget resolves through the account's own cache and therefore
// needs `account.read(peers)` on top of the api's own grant.

/// records what the chain did with a dispatch; nothing here decides anything
#[derive(Default)]
struct TestRpcHost {
    registered: RefCell<Vec<u32>>,
    /// what `next()` was handed, i.e. the request that would actually go out
    next_calls: RefCell<Vec<String>>,
    completes: RefCell<Vec<String>>,
}

impl crate::tg::rpc::RpcHost for TestRpcHost {
    fn on_register(&self, _methods: &[String], callback_id: u32, _scope: &str) -> Option<String> {
        self.registered.borrow_mut().push(callback_id);
        None
    }
    fn on_unregister(&self, _callback_id: u32) {}
    fn on_invoke(&self, _invoke_id: i64, _slot: i32, _request_wire: &str) -> Option<String> {
        Some("Pforbidden\n\n\n\nthis fake sends nothing".to_string())
    }
    fn on_next(&self, _dispatch_id: i64, request_wire: &str) -> Option<String> {
        self.next_calls.borrow_mut().push(request_wire.to_string());
        None
    }
    fn on_complete(&self, _dispatch_id: i64, result_wire: &str) {
        self.completes.borrow_mut().push(result_wire.to_string());
    }
    fn on_update_register(&self, _callback_id: u32, _types: &[String], _scope: &str) -> Option<String> {
        None
    }
    fn on_update_unregister(&self, _callback_id: u32) {}
    fn on_intercept_update_register(&self, _callback_id: u32, _types: &[String]) -> Option<String> {
        None
    }
    fn on_intercept_update_unregister(&self, _callback_id: u32) {}
    fn on_update_verdict(&self, _dispatch_id: i64, _deliver: bool) {}
}

type SendFixture = (
    Runtime,
    Context,
    Rc<TestRpcHost>,
    crate::testing::util::DisposeOnDrop<crate::tg::rpc::RpcState>,
    crate::testing::util::DisposeOnDrop<crate::tg::reads::ReadsState>,
    crate::testing::util::DisposeOnDrop<crate::tg::account::AccountState>,
);

/// the install order a device uses: the account api, then the read surface whose prototype an
/// `Account` answers `resolvePeerCached` from, then the rpc chain `interceptSendMessage` is a
/// narrowing of
fn setup_send(grants: &[&str]) -> SendFixture {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let rpc_host = Rc::new(TestRpcHost::default());
    let grants = TestGrantHost::new(grants).as_host();
    let log: crate::Log = std::sync::Arc::new(|_| {});
    let peers = Rc::new(SelfOnlyReadsHost);
    let views = TlViews::new(TestWritesHost::new() as Rc<dyn TlHost>);
    let (rpc, reads, accounts) = ctx.with(|ctx| {
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
        let reads_host: Rc<dyn ReadsHost> = peers.clone();
        let reads = crate::tg::reads::install_reads(
            &ctx,
            reads_host,
            grants.clone(),
            views.clone(),
            &shared,
            &accounts,
            log.clone(),
        )
        .unwrap();
        let rpc_host_dyn: Rc<dyn crate::tg::rpc::RpcHost> = rpc_host.clone();
        let rpc = crate::tg::rpc::install_rpc(
            &ctx,
            rpc_host_dyn,
            views.clone(),
            grants,
            crate::engine::registry::Lifecycle::new(),
            Some(accounts.clone()),
            shared,
            log.clone(),
        )
        .unwrap();
        (rpc, reads, accounts)
    });
    let rpc = crate::testing::util::DisposeOnDrop::new(&ctx, rpc, crate::tg::rpc::dispose);
    let reads = crate::testing::util::DisposeOnDrop::new(&ctx, reads, crate::tg::reads::dispose);
    let accounts = crate::testing::util::DisposeOnDrop::new(&ctx, accounts, crate::tg::account::dispose);
    (rt, ctx, rpc_host, rpc, reads, accounts)
}

const A_SEND: &str = r#"{"_":"messages.sendMessage","peer":{"_":"inputPeerUser","user_id":"7","access_hash":"3"},"message":"hi","random_id":"1"}"#;

/// registers `middleware` through `inu.interceptSendMessage`, runs one `messages.sendMessage`
/// through the chain, and hands back what reached `next()` (or `None` when the send was dropped)
fn run_one_send(fixture: &SendFixture, middleware: &str) -> Option<String> {
    let (rt, ctx, host, rpc, _reads, _accounts) = fixture;
    ctx.with(|ctx| {
        ctx.globals().set("__out", rquickjs::Array::new(ctx.clone()).unwrap()).unwrap();
        ctx.eval::<(), _>(format!("inu.interceptSendMessage({middleware})")).unwrap();
    });
    let callback_id = *host.registered.borrow().last().expect("the middleware never registered");
    crate::tg::rpc::dispatch_rpc(rt, ctx, rpc, callback_id, 1, "messages.sendMessage", 0, &format!("J{A_SEND}"));
    host.next_calls.borrow().first().cloned()
}

fn out_of(ctx: &Context) -> String {
    ctx.with(|ctx| ctx.eval::<String, _>("JSON.stringify(__out)").unwrap())
}

#[test]
fn retargeting_a_send_writes_the_peer_the_account_resolved_and_never_the_bare_id() {
    let fixture = setup_send(&["interceptSendMessage", "account.read(peers)"]);
    let next = run_one_send(&fixture, "(m) => { m.peer = 111; return 'send' }").expect("the send never went out");
    assert!(
        next.contains(r#""peer":{"_":"inputPeerUser","user_id":"111","access_hash":"1110"}"#),
        "the retarget must write the resolved InputPeer, not the dialog id: {next}",
    );
    assert!(!next.contains(r#""peer":111"#), "a bare dialog id reached the request: {next}");
}

#[test]
fn a_topic_retarget_writes_the_reply_that_lands_the_message_in_it() {
    let fixture = setup_send(&["interceptSendMessage", "account.read(peers)"]);
    let next = run_one_send(&fixture, "(m) => { m.topicId = 12; return 'send' }").expect("the send never went out");
    // a post into a topic with no reply of its own addresses the topic's own root message,
    // which is what makes it land in the topic at all
    assert!(next.contains(r#""reply_to":{"_":"inputReplyToMessage","reply_to_msg_id":12,"top_msg_id":12}"#), "{next}",);

    let fixture = setup_send(&["interceptSendMessage", "account.read(peers)"]);
    let next = run_one_send(&fixture, "(m) => { m.replyToMessageId = 33; m.topicId = 12; return 'send' }")
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
        "(m) => { try { m.peer = 4242424242 } catch (e) { __out.push([e.code, m.peer]) } return 'send' }",
    )
    .expect("the send never went out");
    assert_eq!(out_of(&fixture.1), r#"[["not-found",7]]"#, "the original peer must survive a failed retarget");
    assert!(next.contains(r#""user_id":"7""#), "{next}");
}

#[test]
fn retargeting_needs_the_read_grant_on_top_of_the_apis_own() {
    let fixture = setup_send(&["interceptSendMessage"]);
    let next = run_one_send(
        &fixture,
        "(m) => { try { m.peer = 111 } catch (e) { __out.push([e.code, e.grant]) } return 'send' }",
    )
    .expect("the send never went out");
    assert_eq!(out_of(&fixture.1), r#"[["not-granted","account.read(peers)"]]"#);
    assert!(next.contains(r#""user_id":"7""#), "a refused retarget must not touch the request: {next}");
}

#[test]
fn what_a_retarget_refuses_outright() {
    let fixture = setup_send(&["interceptSendMessage", "account.read(peers)"]);
    run_one_send(
        &fixture,
        r#"(m) => {
             for (const bad of [0, null, undefined, 'me', {}]) {
               try { m.peer = bad; __out.push('accepted') } catch (e) { __out.push(e.code) }
             }
             return 'send'
           }"#,
    );
    // a dialog id, never an `InputPeerLike`: the getter answers one, so the setter takes one
    assert_eq!(
        out_of(&fixture.1),
        r#"["invalid-argument","invalid-argument","invalid-argument","invalid-argument","invalid-argument"]"#,
    );
}

/// evaluates a bundled oracle under exactly the grants its header asks for, and holds it to
/// running every assertion it contains
fn run_bundled_oracle(source: &str, done: &str, count: usize) {
    let (rt, ctx, host, state, _r, _a, _d) = setup(&crate::testing::util::manifest_grants(source));
    let lines = crate::testing::util::install_capturing_console(&ctx);
    ctx.with(|ctx| match ctx.eval::<(), _>(source) {
        Ok(()) => {}
        Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
        Err(e) => panic!("{e:?}"),
    });
    settle(&rt, &ctx, &state, &host);
    let lines = lines.borrow().clone();
    crate::testing::util::assert_oracle_exact(&lines, done, count);
}

#[test]
fn the_bundled_writes_test_plugin_passes() {
    run_bundled_oracle(include_str!("../../../res/assets-debug/inu_plugins/writes-test.js"), "writes test done", 43);
}

#[test]
fn the_bundled_media_test_plugin_passes() {
    run_bundled_oracle(include_str!("../../../res/assets-debug/inu_plugins/media-test.js"), "media test done", 30);
}

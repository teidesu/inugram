//! The `Account` write surface and the media transfers, per `common.d.ts`. JNI-free behind
//! [`WritesHost`]; the normalization half is prelude js in `writes.js`, over the *same*
//! `InputPeerLike` resolver [`crate::tl::utils`] hands [`crate::telegram::reads`].
//!
//! **Neither of this block's two rules is decided here.** "never re-enter the interceptors" and
//! "never reach a secret chat" both need the request and the peer, which exist only host-side;
//! `PluginWrites` owns both at the single point every op's request goes through.
//!
//! **A `Blob` becomes a file before it crosses.** Stock's uploader takes a path and the host cannot
//! read a blob's backing (that is rust's, deliberately), so content in a file position is staged
//! into this engine's spill directory and the path is what crosses. The staged copy is deleted
//! whichever way the request settles. Staging is native work no interpreter deadline can interrupt,
//! so it carries its own bound: [`TRANSFER_LIMIT_BYTES`], refused before the copy starts.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::rc::Rc;

use rquickjs::{Array, Ctx, Function, Object, Result as JsResult, Runtime, TypedArray, Value};

use crate::grants::{check_grant, GrantHost, MATCH_EXACT};
use crate::io::blob::BlobState;
use crate::sandbox::error::{get_or_create_inu, wire_error_to_js};
use crate::telegram::account::AccountState;
use crate::telegram::progress::ProgressReporter;
use crate::telegram::rpc::{format_exception, pump_jobs, PendingSettle};
use crate::tl::proxy::{js_value_to_wire, wire_to_js_value, TlViews, ViewLife};

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/writes.qbc"));

/// stand-in for the Kotlin `QuickJs.WritesListener`
pub trait WritesHost {
    /// one write or transfer. `arg` is the op's operands as json, `values` the file/message
    /// positions it could not carry (a staged file, a live TL handle, a plain TL object).
    ///
    /// `None` == accepted, settled later through [`write_result`]; `Some` == an error to reject
    /// with, as a bare message or a `P`/`R` wire.
    fn account_write(&self, account_id: i32, request_id: i64, op: i32, arg: &str, values: &[String]) -> Option<String>;

    /// `getMessageFile`, the one synchronous member here: it reads the app's file-path database and
    /// never transfers anything. Answers a `J`/`N`/`P` wire.
    fn message_file(&self, account_id: i32, value: &str) -> String;
}

// keep in sync with `writes.js` and Kotlin `PluginWrites.OP_*`
const OP_SEND_MESSAGE: i32 = 0;
const OP_SEND_MEDIA: i32 = 1;
const OP_SEND_MULTI_MEDIA: i32 = 2;
const OP_EDIT_MESSAGE: i32 = 3;
const OP_DELETE_MESSAGES: i32 = 4;
const OP_FORWARD_MESSAGES: i32 = 5;
const OP_SET_REACTION: i32 = 6;
const OP_READ_HISTORY: i32 = 7;
const OP_SEND_TYPING: i32 = 8;
const OP_SET_DRAFT: i32 = 9;
const OP_DOWNLOAD_MEDIA: i32 = 10;
const OP_DOWNLOAD_MEDIA_TO_FILE: i32 = 11;
const OP_UPLOAD_FILE: i32 = 12;

/// what one construction may stage into a file before the host is handed the path. A copy is native
/// work and the entry deadline is polled on js back-edges, so it cannot see a single host call at
/// all - the same reason `blob.rs` bounds a construction in bytes rather than in time.
pub const TRANSFER_LIMIT_BYTES: u64 = 256 * 1024 * 1024;

/// how much of a staged copy is held in memory at once
const STAGE_CHUNK_BYTES: u64 = 1024 * 1024;

/// which grant each op is gated on, per `common.d.ts`'s grant list. `None` is an op only this
/// crate's own prelude could have asked for, so it is refused rather than gated on a fallback.
fn grant_of(op: i32) -> Option<(&'static str, &'static str)> {
    Some(match op {
        OP_SEND_MESSAGE | OP_SEND_MEDIA | OP_SEND_MULTI_MEDIA | OP_UPLOAD_FILE => ("account.write", "send"),
        OP_EDIT_MESSAGE => ("account.write", "edit"),
        OP_DELETE_MESSAGES => ("account.write", "delete"),
        OP_FORWARD_MESSAGES => ("account.write", "forward"),
        OP_SET_REACTION => ("account.write", "react"),
        OP_READ_HISTORY => ("account.write", "read"),
        OP_SEND_TYPING => ("account.write", "typing"),
        OP_SET_DRAFT => ("account.write", "draft"),
        OP_DOWNLOAD_MEDIA | OP_DOWNLOAD_MEDIA_TO_FILE => ("account.read", "messages"),
        _ => return None,
    })
}

/// what settling an op's request builds out of the host's wire
#[derive(Clone, Copy, PartialEq, Eq)]
enum Shape {
    /// one value: a message handle, an `InputFile`, or `N` for the `Promise<void>` members
    Value,
    /// one handle per element, joined with [`SEPARATOR`]
    List,
    /// `J{path,size,mime,name,mtime}` over a file the *app* owns, minted as a `File`
    File,
}

/// keep in sync with Kotlin `PluginWrites.LIST_SEPARATOR`
const SEPARATOR: char = '\n';

fn shape_of(op: i32) -> Shape {
    match op {
        OP_SEND_MULTI_MEDIA | OP_FORWARD_MESSAGES => Shape::List,
        OP_DOWNLOAD_MEDIA => Shape::File,
        _ => Shape::Value,
    }
}

/// whether an op's `values` are files to stage or TL values to pass through. The two cannot be told
/// apart by looking: a `Blob` in a file position and a message in a download's are both objects.
fn takes_files(op: i32) -> bool {
    matches!(op, OP_SEND_MEDIA | OP_SEND_MULTI_MEDIA | OP_UPLOAD_FILE)
}

pub struct WritesState {
    host: Rc<dyn WritesHost>,
    grants: Rc<dyn GrantHost>,
    views: Rc<TlViews>,
    blobs: Rc<BlobState>,
    stage_dir: PathBuf,
    log: crate::Log,
    /// [`TRANSFER_LIMIT_BYTES`], injectable so a test does not have to move a quarter of a gigabyte
    /// to reach it - same reason `blob.rs` takes its ceilings as a struct
    transfer_limit: u64,
    next_request_id: crate::sandbox::registry::RequestIds,
    next_staged: Cell<u64>,
    pending: RefCell<HashMap<i64, PendingWrite>>,
}

struct PendingWrite {
    settle: PendingSettle,
    shape: Shape,
    progress: Option<Rc<ProgressReporter>>,
    /// the last `total` the transfer reported, so a completed one ends on `total`/`total` even
    /// though only the host ever knew the size
    last_total: Cell<i64>,
    /// what [`stage_value`] wrote for this request, deleted however it settles
    staged: Vec<PathBuf>,
}

fn throw_write<'js, T>(ctx: &Ctx<'js>, code: &str, message: &str) -> JsResult<T> {
    crate::sandbox::error::throw_plugin_error(ctx, code, message, None, None, None)
}

/// the one gate. Nothing else about a write is decidable here: which peer it names is a spec, and
/// whether that peer is one plugins may write to is the host's answer.
fn check_write_grant(ctx: &Ctx<'_>, state: &Rc<WritesState>, op: i32) -> JsResult<()> {
    let Some((name, scope)) = grant_of(op) else {
        return throw_write(ctx, "invalid-argument", "unknown account write");
    };
    check_grant(ctx, &state.grants, name, Some(scope), MATCH_EXACT)
}

/// A `{ path }` is the one member of the `file` union that can name content the plugin was never
/// handed, which is why `common.d.ts` puts it behind `fs`: without that rule `account.write(send)`
/// alone would read any file the app can reach and post it to a chat. A relative path resolves
/// inside the plugin's own directory and an absolute one needs `unsafe.fs`.
fn check_path_grant(ctx: &Ctx<'_>, state: &Rc<WritesState>, path: &str) -> JsResult<()> {
    if Path::new(path).is_absolute() {
        return check_grant(ctx, &state.grants, "unsafe.fs", None, MATCH_EXACT);
    }
    check_grant(ctx, &state.grants, "fs", None, MATCH_EXACT)?;
    // `inu.fs` owns the scoped directory and nothing hands it to `WritesState`, so this is the one
    // shape of the union with nowhere here to resolve to. Refusing beats picking a directory of our
    // own, which would be a second scoped root that `fs.write` never writes into
    throw_write(
        ctx,
        "unsupported",
        "a relative path needs the plugin's scoped directory, which arrives with inu.fs; \
         pass a Blob, bytes, or an absolute path under unsafe.fs",
    )
}

struct Staged {
    wire: String,
    path: Option<PathBuf>,
}

/// the wire for one value in a file position: a path the plugin named, or content staged into one
fn stage_value<'js>(ctx: &Ctx<'js>, state: &Rc<WritesState>, value: &Value<'js>) -> JsResult<Staged> {
    if let Some(object) = value.as_object() {
        if let Some(path) = object.get::<_, Option<String>>("path")? {
            if object.get::<_, Value>("_")?.is_undefined() {
                check_path_grant(ctx, state, &path)?;
                return Ok(Staged { wire: file_wire(&path, "", ""), path: None });
            }
        }
    }
    // the class rather than a duck type: a plugin can reassign `globalThis.Blob`, and a blob whose
    // content is gone has to read as `handle-expired` rather than fall through to "not a file"
    if rquickjs::Class::<crate::io::blob::BlobHandle>::from_value(value).is_ok() {
        let Some(exported) = crate::io::blob::export_for_host(&state.blobs, value) else {
            return throw_write(ctx, "handle-expired", "this blob has been disposed");
        };
        return stage_blob(ctx, state, value, &exported);
    }
    if let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) {
        let Some(bytes) = typed.as_bytes() else {
            return throw_write(ctx, "invalid-argument", "this Uint8Array is detached");
        };
        check_transfer_limit(ctx, state, bytes.len() as u64)?;
        let path = write_staged(ctx, state, |file| file.write_all(bytes))?;
        return Ok(Staged { wire: file_wire(&path.to_string_lossy(), "", ""), path: Some(path) });
    }
    // an already-built `InputFile`/`InputMedia`, whichever form it arrived in
    Ok(Staged { wire: js_value_to_wire(ctx, value.clone())?, path: None })
}

/// the staging cap, refused before a byte of the content is read. `usage`/`quota` are the two
/// numbers `common.d.ts` promises a `quota-exceeded` carries, in bytes.
fn check_transfer_limit(ctx: &Ctx<'_>, state: &Rc<WritesState>, len: u64) -> JsResult<()> {
    if len <= state.transfer_limit {
        return Ok(());
    }
    crate::sandbox::error::throw_plugin_error(
        ctx,
        "quota-exceeded",
        &format!("this transfer is {len} bytes; at most {} may be staged at once", state.transfer_limit,),
        None,
        Some(len as i64),
        Some(state.transfer_limit as i64),
    )
}

/// copies a blob's own range into this engine's staging directory. The range is the *handle's*, so
/// a four-byte header sliced off a download stages four bytes.
fn stage_blob<'js>(ctx: &Ctx<'js>, state: &Rc<WritesState>, value: &Value<'js>, exported: &str) -> JsResult<Staged> {
    let Some(id) = parse_export_id(exported) else {
        return throw_write(ctx, "internal", "this blob could not be handed over");
    };
    let Some(export) = crate::io::blob::resolve_export(&state.blobs, id) else {
        return throw_write(ctx, "handle-expired", "this blob has been disposed");
    };
    let len = export.len();
    check_transfer_limit(ctx, state, len)?;
    let object = value.as_object().cloned();
    let name = object.as_ref().and_then(|o| o.get::<_, Option<String>>("name").ok().flatten()).unwrap_or_default();
    let mime = object.as_ref().and_then(|o| o.get::<_, Option<String>>("type").ok().flatten()).unwrap_or_default();

    let path = write_staged(ctx, state, |file| {
        let mut at = 0u64;
        while at < len {
            let take = STAGE_CHUNK_BYTES.min(len - at);
            let chunk = export
                .read(at, take)
                .map_err(|_| std::io::Error::other("this blob's content is no longer readable"))?;
            file.write_all(&chunk)?;
            at += take;
        }
        Ok(())
    })?;
    Ok(Staged { wire: file_wire(&path.to_string_lossy(), &name, &mime), path: Some(path) })
}

fn parse_export_id(exported: &str) -> Option<i64> {
    exported.strip_prefix('B')?.split(':').next()?.parse().ok()
}

fn write_staged<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<WritesState>,
    fill: impl FnOnce(&mut fs::File) -> std::io::Result<()>,
) -> JsResult<PathBuf> {
    if state.stage_dir.as_os_str().is_empty() {
        return throw_write(ctx, "internal", "this engine has no directory to stage a transfer in");
    }
    let n = state.next_staged.get() + 1;
    state.next_staged.set(n);
    let path = state.stage_dir.join(format!("transfer-{n}.bin"));
    let written = fs::create_dir_all(&state.stage_dir)
        .and_then(|_| fs::File::create(&path))
        .and_then(|mut file| fill(&mut file).and_then(|_| file.sync_all()));
    if let Err(e) = written {
        let _ = fs::remove_file(&path);
        return throw_write(ctx, "internal", &format!("staging this transfer failed: {e}"));
    }
    Ok(path)
}

fn file_wire(path: &str, name: &str, mime: &str) -> String {
    format!("F{{\"path\":{},\"name\":{},\"mime\":{}}}", json_string(path), json_string(name), json_string(mime),)
}

/// a json string literal. A path is whatever the filesystem allows, so it is escaped rather than
/// assumed printable - which is also what keeps a path carrying a quote from forging a second field.
pub(crate) fn json_string(value: &str) -> String {
    let mut out = String::with_capacity(value.len() + 2);
    out.push('"');
    for c in value.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

fn js_write<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<WritesState>,
    slot: i32,
    op: i32,
    arg: &str,
    values: Array<'js>,
    on_progress: Option<Function<'js>>,
) -> JsResult<Value<'js>> {
    check_write_grant(ctx, state, op)?;

    let mut wires = Vec::new();
    let mut staged = Vec::new();
    // an item that fails to stage leaves the copies its predecessors made, and nothing downstream
    // ever learns those request ids existed, so the cleanup is here rather than on `take_pending`
    let outcome = (|| -> JsResult<()> {
        for value in crate::sandbox::argv::array_values(ctx, &values, "account write")? {
            let one = if takes_files(op) {
                stage_value(ctx, state, &value)?
            } else {
                Staged { wire: js_value_to_wire(ctx, value)?, path: None }
            };
            if let Some(path) = one.path {
                staged.push(path);
            }
            wires.push(one.wire);
        }
        Ok(())
    })();
    if let Err(e) = outcome {
        for path in &staged {
            let _ = fs::remove_file(path);
        }
        return Err(e);
    }

    let request_id = state.next_request_id.alloc();
    let (promise, settle) = PendingSettle::new(ctx)?;
    let progress = on_progress.map(|callback| ProgressReporter::new(ctx, callback, state.log.clone()));
    state
        .pending
        .borrow_mut()
        .insert(request_id, PendingWrite { settle, shape: shape_of(op), progress, last_total: Cell::new(0), staged });

    if let Some(err) = state.host.account_write(slot, request_id, op, arg, &wires) {
        if let Some(pending) = take_pending(state, request_id) {
            let value = crate::sandbox::error::host_error_to_js(ctx, &err)?;
            if let Some(progress) = pending.progress.as_ref() {
                progress.release(ctx);
            }
            pending.settle.reject_with_value(ctx, value)?;
        }
    }
    Ok(promise.into_value())
}

/// removes a request and deletes whatever it staged; every exit goes through here, so a staged copy
/// outlives its transfer by nothing at all
fn take_pending(state: &Rc<WritesState>, request_id: i64) -> Option<PendingWrite> {
    let pending = state.pending.borrow_mut().remove(&request_id)?;
    for path in pending.staged.iter() {
        let _ = fs::remove_file(path);
    }
    Some(pending)
}

fn decode_list<'js>(ctx: &Ctx<'js>, state: &Rc<WritesState>, wire: &str) -> JsResult<Array<'js>> {
    let array = Array::new(ctx.clone())?;
    if wire.is_empty() {
        return Ok(array);
    }
    for (index, element) in wire.split(SEPARATOR).enumerate() {
        array.set(index, wire_to_js_value(ctx, &state.views, element, ViewLife::Plugin)?)?;
    }
    Ok(array)
}

fn decode_result<'js>(ctx: &Ctx<'js>, state: &Rc<WritesState>, shape: Shape, wire: &str) -> JsResult<Value<'js>> {
    match shape {
        Shape::Value => wire_to_js_value(ctx, &state.views, wire, ViewLife::Plugin),
        Shape::List => Ok(decode_list(ctx, state, wire)?.into_value()),
        Shape::File => {
            let described = wire_to_js_value(ctx, &state.views, wire, ViewLife::Plugin)?;
            let Some(object) = described.as_object() else {
                return throw_write(ctx, "internal", "the host described a download it did not make");
            };
            let path: String = object.get("path")?;
            let size: f64 = object.get::<_, Option<f64>>("size")?.unwrap_or(0.0);
            let mime: String = object.get::<_, Option<String>>("mime")?.unwrap_or_default();
            let name: Option<String> = object.get::<_, Option<String>>("name")?.filter(|n| !n.is_empty());
            let mtime: f64 = object.get::<_, Option<f64>>("mtime")?.unwrap_or(0.0);
            crate::io::blob::mint_app_file(
                ctx,
                Path::new(&path),
                size.max(0.0) as u64,
                &mime,
                name.as_deref(),
                mtime as i64,
            )
        }
    }
}

/// settles a pending write or transfer; the wire is the op's own shape, or an error
pub fn write_result(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<WritesState>,
    request_id: i64,
    result_wire: &str,
) {
    context.with(|ctx| {
        let Some(pending) = take_pending(state, request_id) else {
            return;
        };
        if let Some(built) = wire_error_to_js(&ctx, result_wire) {
            // a failed transfer ends on the last numbers it managed to report, per `common.d.ts`
            if let Some(progress) = pending.progress.as_ref() {
                progress.abandon(&ctx);
            }
            match built {
                Ok(value) => {
                    if pending.settle.reject_with_value(&ctx, value).is_err() {
                        (state.log)(&format!("write({request_id}) reject failed: {}", format_exception(&ctx)));
                    }
                }
                Err(e) => {
                    pending.settle.release(&ctx);
                    (state.log)(&format!("write({request_id}) error decode failed: {e:?}"));
                }
            }
            return;
        }
        if let Some(progress) = pending.progress.as_ref() {
            let total = pending.last_total.get();
            // the terminal report a completed transfer owes the throttle: it arrived, so it is at
            // its own total, whatever the window was withholding when the last chunk landed
            if total > 0 {
                progress.finish(&ctx, total, total);
            } else {
                progress.abandon(&ctx);
            }
        }
        match decode_result(&ctx, state, pending.shape, result_wire) {
            Ok(value) => {
                if pending.settle.resolve_with(&ctx, value).is_err() {
                    (state.log)(&format!("write({request_id}) resolve failed: {}", format_exception(&ctx)));
                }
            }
            Err(_) => {
                pending.settle.release(&ctx);
                (state.log)(&format!("write({request_id}) bad result wire: {}", format_exception(&ctx)));
            }
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

/// one `(loaded, total)` from a transfer in flight; the throttle decides whether it is delivered
pub fn write_progress(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<WritesState>,
    request_id: i64,
    loaded: i64,
    total: i64,
) {
    context.with(|ctx| {
        // the table's borrow ends before the callback runs: that is plugin js, and it may start
        // another write from inside its own progress handler
        let progress = {
            let table = state.pending.borrow();
            let Some(pending) = table.get(&request_id) else {
                return;
            };
            pending.last_total.set(total);
            pending.progress.clone()
        };
        let Some(progress) = progress else { return };
        progress.report(&ctx, loaded, total);
    });
    pump_jobs(rt, context, state.log.as_ref());
}

/// releases every `Persistent` GC root this state still owns - same contract as [`crate::telegram::rpc::dispose`]
pub fn dispose(context: &rquickjs::Context, state: &Rc<WritesState>) {
    context.with(|ctx| {
        for (_, pending) in state.pending.borrow_mut().drain() {
            if let Some(progress) = pending.progress.as_ref() {
                progress.release(&ctx);
            }
            pending.settle.release(&ctx);
            for path in pending.staged.iter() {
                let _ = fs::remove_file(path);
            }
        }
    });
}

/// everything behind this surface that is not the realm it installs into
pub struct WritesDeps {
    pub host: Rc<dyn WritesHost>,
    pub grants: Rc<dyn GrantHost>,
    pub views: Rc<TlViews>,
    pub blobs: Rc<BlobState>,
    pub stage_dir: PathBuf,
    pub log: crate::Log,
}

pub fn install_writes<'js>(
    ctx: &Ctx<'js>,
    deps: WritesDeps,
    shared: &Object<'js>,
    accounts: &Rc<AccountState>,
) -> JsResult<Rc<WritesState>> {
    install_writes_with_limit(ctx, deps, shared, accounts, TRANSFER_LIMIT_BYTES)
}

pub(crate) fn install_writes_with_limit<'js>(
    ctx: &Ctx<'js>,
    deps: WritesDeps,
    shared: &Object<'js>,
    accounts: &Rc<AccountState>,
    transfer_limit: u64,
) -> JsResult<Rc<WritesState>> {
    let state = Rc::new(WritesState {
        host: deps.host,
        grants: deps.grants,
        views: deps.views,
        blobs: deps.blobs,
        stage_dir: deps.stage_dir,
        log: deps.log,
        transfer_limit,
        next_request_id: crate::sandbox::registry::RequestIds::default(),
        next_staged: Cell::new(0),
        pending: RefCell::new(HashMap::new()),
    });

    let natives = Object::new(ctx.clone())?;
    {
        let state = state.clone();
        let f = Function::new(
            ctx.clone(),
            move |ctx: Ctx<'js>,
                  slot: i32,
                  op: i32,
                  arg: String,
                  values: Array<'js>,
                  on_progress: Option<Function<'js>>| {
                js_write(&ctx, &state, slot, op, &arg, values, on_progress)
            },
        )?;
        natives.set("write", f)?;
    }
    {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, slot: i32, message: Value<'js>| {
            check_grant(&ctx, &state.grants, "account.read", Some("messages"), MATCH_EXACT)?;
            let wire = js_value_to_wire(&ctx, message)?;
            let answer = state.host.message_file(slot, &wire);
            wire_to_js_value(&ctx, &state.views, &answer, ViewLife::Plugin)
        })?;
        natives.set("messageFile", f)?;
    }

    let inu = get_or_create_inu(ctx)?;
    // captured at install, like `reads.js`'s: what the prelude constructs must not be decidable by
    // a plugin reassigning `inu.Message`
    let message: Value = inu.get("Message")?;
    let plugin_error: Value = inu.get("PluginError")?;

    // taken rather than read: a `Persistent` has no `Drop`, so overwriting the account's without
    // releasing it first leaks a GC root and aborts `JS_FreeRuntime`
    let reads = crate::telegram::account::take_prototype(ctx, accounts);

    let factory = crate::sandbox::prelude::load(ctx, PRELUDE)?;
    let prototype: Object = factory.call((natives, shared.clone(), message, plugin_error, reads))?;
    crate::telegram::account::set_prototype(ctx, accounts, &prototype);

    Ok(state)
}

#[cfg(test)]
#[path = "writes_tests.rs"]
mod tests;

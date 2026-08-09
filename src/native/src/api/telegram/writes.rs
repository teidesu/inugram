use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::rc::Rc;

use rquickjs::{Array, Ctx, Function, Object, Result as JsResult, Runtime, TypedArray, Value};

use crate::api::error::wire_error_to_js;
use crate::api::io::blob::BlobState;
use crate::api::telegram::account::AccountState;
use crate::api::telegram::progress::ProgressReporter;
use crate::api::telegram::rpc::{format_exception, pump_jobs, PendingSettle};
use crate::api::tl::proxy::{js_value_to_wire, wire_to_js_value, TlViews, ViewLife};
use crate::sandbox::grants::{check_grant, GrantHost, MATCH_EXACT};

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/writes.qbc"));

pub trait WritesHost {
    fn account_write(&self, account_id: i32, request_id: i64, op: i32, arg: &str, values: &[String]) -> Option<String>;

    fn message_file(&self, account_id: i32, value: &str) -> String;
}

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

pub const TRANSFER_LIMIT_BYTES: u64 = 256 * 1024 * 1024;

const STAGE_CHUNK_BYTES: u64 = 1024 * 1024;

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

#[derive(Clone, Copy, PartialEq, Eq)]
enum Shape {
    Value,
    List,
    File,
}

const SEPARATOR: char = '\n';

fn shape_of(op: i32) -> Shape {
    match op {
        OP_SEND_MULTI_MEDIA | OP_FORWARD_MESSAGES => Shape::List,
        OP_DOWNLOAD_MEDIA => Shape::File,
        _ => Shape::Value,
    }
}

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
    transfer_limit: u64,
    next_request_id: crate::sandbox::registry::RequestIds,
    next_staged: Cell<u64>,
    pending: RefCell<HashMap<i64, PendingWrite>>,
}

struct PendingWrite {
    settle: PendingSettle,
    shape: Shape,
    progress: Option<Rc<ProgressReporter>>,
    last_total: Cell<i64>,
    staged: Vec<PathBuf>,
}

fn throw_write<'js, T>(ctx: &Ctx<'js>, code: &str, message: &str) -> JsResult<T> {
    crate::api::error::throw_plugin_error(ctx, code, message, None, None, None)
}

fn check_write_grant(ctx: &Ctx<'_>, state: &Rc<WritesState>, op: i32) -> JsResult<()> {
    let Some((name, scope)) = grant_of(op) else {
        return throw_write(ctx, "invalid-argument", "unknown account write");
    };
    check_grant(ctx, &state.grants, name, Some(scope), MATCH_EXACT)
}

fn check_path_grant(ctx: &Ctx<'_>, state: &Rc<WritesState>, path: &str) -> JsResult<()> {
    if Path::new(path).is_absolute() {
        return check_grant(ctx, &state.grants, "unsafe.fs", None, MATCH_EXACT);
    }
    check_grant(ctx, &state.grants, "fs", None, MATCH_EXACT)?;
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

fn stage_value<'js>(ctx: &Ctx<'js>, state: &Rc<WritesState>, value: &Value<'js>) -> JsResult<Staged> {
    if let Some(object) = value.as_object() {
        if let Some(path) = object.get::<_, Option<String>>("path")? {
            if object.get::<_, Value>("_")?.is_undefined() {
                check_path_grant(ctx, state, &path)?;
                return Ok(Staged { wire: file_wire(&path, "", ""), path: None });
            }
        }
    }
    if rquickjs::Class::<crate::api::io::blob::BlobHandle>::from_value(value).is_ok() {
        let Some(exported) = crate::api::io::blob::export_for_host(&state.blobs, value) else {
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
    Ok(Staged { wire: js_value_to_wire(ctx, value.clone())?, path: None })
}

fn check_transfer_limit(ctx: &Ctx<'_>, state: &Rc<WritesState>, len: u64) -> JsResult<()> {
    if len <= state.transfer_limit {
        return Ok(());
    }
    crate::api::error::throw_plugin_error(
        ctx,
        "quota-exceeded",
        &format!("this transfer is {len} bytes; at most {} may be staged at once", state.transfer_limit,),
        None,
        Some(len as i64),
        Some(state.transfer_limit as i64),
    )
}

fn stage_blob<'js>(ctx: &Ctx<'js>, state: &Rc<WritesState>, value: &Value<'js>, exported: &str) -> JsResult<Staged> {
    let Some(id) = parse_export_id(exported) else {
        return throw_write(ctx, "internal", "this blob could not be handed over");
    };
    let Some(export) = crate::api::io::blob::resolve_export(&state.blobs, id) else {
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
    let outcome = (|| -> JsResult<()> {
        for value in crate::utils::arguments::array_values(ctx, &values, "account write")? {
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
            let value = crate::api::error::host_error_to_js(ctx, &err)?;
            if let Some(progress) = pending.progress.as_ref() {
                progress.release(ctx);
            }
            pending.settle.reject_with_value(ctx, value)?;
        }
    }
    Ok(promise.into_value())
}

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
            crate::api::io::blob::mint_app_file(
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

pub fn write_progress(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<WritesState>,
    request_id: i64,
    loaded: i64,
    total: i64,
) {
    context.with(|ctx| {
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
    inu: &Object<'js>,
) -> JsResult<Rc<WritesState>> {
    install_writes_with_limit(ctx, deps, shared, accounts, inu, TRANSFER_LIMIT_BYTES)
}

pub(crate) fn install_writes_with_limit<'js>(
    ctx: &Ctx<'js>,
    deps: WritesDeps,
    shared: &Object<'js>,
    accounts: &Rc<AccountState>,
    inu: &Object<'js>,
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

    let message: Value = inu.get("Message")?;
    let plugin_error: Value = inu.get("PluginError")?;

    let reads = accounts.take_prototype(ctx);
    let ops = Object::new(ctx.clone())?;
    ops.set("sendMessage", OP_SEND_MESSAGE)?;
    ops.set("sendMedia", OP_SEND_MEDIA)?;
    ops.set("sendMultiMedia", OP_SEND_MULTI_MEDIA)?;
    ops.set("editMessage", OP_EDIT_MESSAGE)?;
    ops.set("deleteMessages", OP_DELETE_MESSAGES)?;
    ops.set("forwardMessages", OP_FORWARD_MESSAGES)?;
    ops.set("setReaction", OP_SET_REACTION)?;
    ops.set("readHistory", OP_READ_HISTORY)?;
    ops.set("sendTyping", OP_SEND_TYPING)?;
    ops.set("setDraft", OP_SET_DRAFT)?;
    ops.set("downloadMedia", OP_DOWNLOAD_MEDIA)?;
    ops.set("downloadMediaToFile", OP_DOWNLOAD_MEDIA_TO_FILE)?;
    ops.set("uploadFile", OP_UPLOAD_FILE)?;

    let factory = crate::utils::prelude::load(ctx, PRELUDE)?;
    let prototype: Object = factory.call((natives, shared.clone(), message, plugin_error, reads, ops))?;
    accounts.set_prototype(ctx, &prototype);

    Ok(state)
}

#[cfg(test)]
#[path = "writes_tests.rs"]
mod tests;

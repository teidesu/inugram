//! `fetch`: the one api here that talks to something other than telegram, per `common.d.ts`. The
//! `Response` shape, the `AbortSignal` wiring and the `timeout` are prelude js in `fetch.js`.
//!
//! **There is no http client in this crate and there will not be one**, for three reasons, none of
//! which is the egress screening - that part rust could do, and arguably better, since a socket
//! pinned to an address it resolved itself would close the rebinding window `PluginFetch` documents
//! as residual.
//!
//! *Trust.* `HttpURLConnection` verifies against the **platform** store, so user-installed CAs, a
//! system distrust update and the app's Network Security Config all apply. A rust client means
//! bundling a root list into the apk: no user CAs (which breaks both a corporate proxy and anyone
//! debugging this with mitmproxy), no distrust updates, no Network Security Config. The usual fix
//! for that, `rustls-platform-verifier`, does its verification *by calling back into java* - so the
//! boundary comes back at the least testable point of the stack rather than going away.
//!
//! *Threading.* An engine may only be entered from `globalQueue`. A client here would need its own
//! thread and would still have to marshal the answer onto that queue to settle the promise, which is
//! the hop `PluginFetch` already does - no structural gain, and one more foreign-thread path into a
//! crate whose worst two bugs were exactly that shape.
//!
//! *Size.* This is a cdylib shipped per abi. `url` alone costs ~194 KB stripped; a tls stack and an
//! async runtime are megabytes.
//!
//! What crosses is small and does not scale with the payload: **two JNI crossings per request**
//! (`send`, then `fetch_result`), against a network round trip that is three to four orders of
//! magnitude longer. The response body makes none at all - the host writes it to a file and it
//! arrives here as an app-file blob.
//!
//! So `PluginFetch` runs the exchange, screening every redirect hop against the grant's domain list
//! and every *resolved* address against the loopback/link-local/private ranges; this module does the
//! pre-flight (scheme, shape, the first url's grant) so a refused call never crosses. The host's
//! check is the authoritative one, being the only side that knows what the name resolved to and
//! where the redirects went.
//!
//! A response body is a file the host wrote, reaching js as a [`crate::io::blob`] app-file backing, so
//! it never touches either heap. A *request* body does cross, bounded by
//! [`crate::io::blob::BUILD_LIMIT_BYTES`]; anything bigger is `uploadFile`'s job.

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use rquickjs::{Ctx, Function, Object, Result as JsResult, Runtime, TypedArray, Value};

use crate::api::{json_parse, json_stringify};
use crate::engine::error::{
    check_grant, get_or_create_inu, throw_plugin_error, wire_error_to_js, GrantHost, MATCH_DOMAIN,
};
use crate::io::blob::{export_for_host, mint_app_file, resolve_export, BlobState, BUILD_LIMIT_BYTES};
use crate::tg::rpc::{format_exception, pump_jobs, PendingSettle};

const PRELUDE: &str = include_str!("fetch.js");

/// stand-in for the Kotlin `QuickJs.FetchListener`
pub trait FetchHost {
    /// `None` == accepted, settled later through [`fetch_result`]; `Some` == an error to reject
    /// with, as a bare message or a `P`/`R` wire (never an `E` wire - see `QuickJs.RpcListener`)
    fn send(&self, request_id: i64, url: &str, spec_json: &str, body: Option<&[u8]>) -> Option<String>;

    /// the plugin's `AbortSignal` fired or its `timeout` elapsed: stop, and answer nothing. The
    /// engine has already settled the promise, so a late answer is dropped either way.
    fn abort(&self, request_id: i64);
}

pub struct FetchState {
    host: Rc<dyn FetchHost>,
    grants: Rc<dyn GrantHost>,
    blobs: Rc<BlobState>,
    log: crate::Log,
    next_request_id: crate::engine::registry::RequestIds,
    pending: RefCell<HashMap<i64, PendingSettle>>,
}

/// The host the grant is checked against, from [`crate::engine::url`]'s screen - which `openUrl`
/// runs too, both apis handing the string on to something that re-parses it. `EgressPolicy.hostOf`
/// does it a third time with a real url parser and is the authority; this is the pre-flight that
/// decides which grant to ask for.
fn parse_target(url: &str) -> Result<String, String> {
    crate::engine::url::parse_http_url("fetch", url)
}

/// `string | Uint8Array | Blob` for a request body, read here because only this side can read a
/// blob at all
fn read_body(state: &FetchState, value: &Value<'_>) -> Result<Option<Vec<u8>>, (String, String)> {
    if value.is_undefined() || value.is_null() {
        return Ok(None);
    }
    if let Some(text) = value.as_string() {
        let text = text.to_string().map_err(|e| ("internal".to_string(), format!("fetch: {e:?}")))?;
        return Ok(Some(text.into_bytes()));
    }
    if let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) {
        let Some(bytes) = typed.as_bytes() else {
            return Err(("invalid-argument".to_string(), "fetch: the body array is detached".to_string()));
        };
        return Ok(Some(bytes.to_vec()));
    }
    if let Some(wire) = export_for_host(&state.blobs, value) {
        let id =
            wire.strip_prefix('B').and_then(|rest| rest.split(':').next()).and_then(|id| id.parse().ok()).unwrap_or(0);
        let Some(export) = resolve_export(&state.blobs, id) else {
            return Err(("handle-expired".to_string(), "fetch: the body blob is gone".to_string()));
        };
        if export.len() > BUILD_LIMIT_BYTES {
            return Err((
                "quota-exceeded".to_string(),
                format!(
                    "fetch: a request body is capped at {BUILD_LIMIT_BYTES} bytes; use uploadFile for anything bigger",
                ),
            ));
        }
        let bytes = export
            .read(0, export.len())
            .map_err(|_| ("handle-expired".to_string(), "fetch: the body blob is gone".to_string()))?;
        return Ok(Some(bytes));
    }
    if rquickjs::Class::<crate::io::blob::BlobHandle>::from_value(value).is_ok() {
        return Err(("handle-expired".to_string(), "fetch: the body blob was disposed".to_string()));
    }
    Err(("invalid-argument".to_string(), "fetch: the body must be a string, a Uint8Array or a Blob".to_string()))
}

/// The pre-flight, and the whole of this module's own policy: a scheme it speaks, a host the grant
/// covers, a body it can read. Everything past this is the host's.
fn js_send<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<FetchState>,
    url: String,
    spec: Value<'js>,
    body: Value<'js>,
) -> JsResult<Object<'js>> {
    // through `Ctx`, never `globalThis.JSON`, for [`crate::api::json_stringify`]'s reason: the
    // prelude runs in the plugin's realm, so a spec it serialized itself would be whatever the
    // plugin's `JSON.stringify` felt like returning. The host validates it again either way.
    let spec_json = json_stringify(ctx, spec)?.unwrap_or_else(|| "{}".to_string());
    let host = match parse_target(&url) {
        Ok(host) => host,
        Err(message) => return throw_plugin_error(ctx, "invalid-argument", &message, None, None, None),
    };
    // domain-matched, so `@grant fetch(google.com)` covers `api.google.com` and nothing else. The
    // host re-checks this for every redirect hop, which is the half this side cannot see.
    check_grant(ctx, &state.grants, "fetch", Some(&host), MATCH_DOMAIN)?;

    let body = match read_body(state, &body) {
        Ok(body) => body,
        Err((code, message)) => return throw_plugin_error(ctx, &code, &message, None, None, None),
    };

    let request_id = state.next_request_id.alloc();
    let (promise, settle) = PendingSettle::new(ctx)?;
    state.pending.borrow_mut().insert(request_id, settle);

    if let Some(err) = state.host.send(request_id, &url, &spec_json, body.as_deref()) {
        if let Some(settle) = state.pending.borrow_mut().remove(&request_id) {
            let value = crate::engine::error::host_error_to_js(ctx, &err)?;
            settle.reject_with_value(ctx, value)?;
        }
    }

    let handle = Object::new(ctx.clone())?;
    handle.set("id", request_id)?;
    handle.set("promise", promise)?;
    Ok(handle)
}

pub fn install_fetch<'js>(
    ctx: &Ctx<'js>,
    host: Rc<dyn FetchHost>,
    grants: Rc<dyn GrantHost>,
    blobs: Rc<BlobState>,
    log: crate::Log,
) -> JsResult<Rc<FetchState>> {
    let state = Rc::new(FetchState {
        host,
        grants,
        blobs,
        log,
        next_request_id: crate::engine::registry::RequestIds::default(),
        pending: RefCell::new(HashMap::new()),
    });

    let natives = Object::new(ctx.clone())?;
    {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, url: String, spec: Value<'js>, body: Value<'js>| {
            js_send(&ctx, &state, url, spec, body)
        })?;
        natives.set("send", f)?;
    }
    {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |request_id: i64| {
            // the promise is settled by whoever called this; the host is only being told to stop
            state.pending.borrow_mut().remove(&request_id);
            state.host.abort(request_id);
        })?;
        natives.set("abort", f)?;
    }

    // captured at install like `reads.js`'s constructors: what the prelude throws, and the clock it
    // measures `timeout` on, must not be decidable by a plugin reassigning a global
    let plugin_error: Value = get_or_create_inu(ctx)?.get("PluginError")?;
    let timers = Object::new(ctx.clone())?;
    for name in ["setTimeout", "clearTimeout"] {
        let f: Value = ctx.globals().get(name)?;
        timers.set(name, f)?;
    }

    let mut options = rquickjs::context::EvalOptions::default();
    options.filename = Some("<inu:fetch>".to_string());
    let factory: Function = ctx.eval_with_options(PRELUDE, options)?;
    factory.call::<_, ()>((natives, plugin_error, timers))?;
    Ok(state)
}

/// Turns the host's `{path, type}` into the `Blob` the `Response` hands over. The size and mtime
/// are read *here* rather than sent, so the seal [`mint_app_file`] checks every read against is one
/// this side wrote - a number crossing from java and a number `std::fs` reports are two ways of
/// asking the same filesystem and only have to disagree once for every read to say the file is gone.
fn mint_body<'js>(ctx: &Ctx<'js>, body: &Object<'js>) -> JsResult<Value<'js>> {
    let path: String = body.get("path")?;
    let mime: String = body.get("type").unwrap_or_default();
    let path = std::path::PathBuf::from(path);
    let (size, mtime) = match std::fs::metadata(&path) {
        Ok(meta) => (
            meta.len(),
            meta.modified()
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as i64)
                .unwrap_or(0),
        ),
        // an empty body the host never had to write; a blob over nothing still answers `size` 0 and
        // reads as the empty string, which is what a 204 should look like
        Err(_) => (0, 0),
    };
    mint_app_file(ctx, &path, size, &mime, None, mtime)
}

/// settles one `fetch`: `J<json>` carrying `{status, statusText, url, headers, body}`, or an error
pub fn fetch_result(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<FetchState>,
    request_id: i64,
    result_wire: &str,
) {
    context.with(|ctx| {
        let Some(settle) = state.pending.borrow_mut().remove(&request_id) else {
            return;
        };
        if let Some(built) = wire_error_to_js(&ctx, result_wire) {
            match built {
                Ok(value) => {
                    if settle.reject_with_value(&ctx, value).is_err() {
                        (state.log)(&format!("fetch({request_id}) reject failed: {}", format_exception(&ctx)));
                    }
                }
                Err(e) => {
                    settle.release(&ctx);
                    (state.log)(&format!("fetch({request_id}) error decode failed: {e:?}"));
                }
            }
            return;
        }
        let built = (|| -> JsResult<Value> {
            let json = result_wire
                .strip_prefix('J')
                .ok_or_else(|| rquickjs::Exception::throw_message(&ctx, "fetch: malformed host response"))?;
            let value = json_parse(&ctx, json)?;
            let object = value
                .as_object()
                .cloned()
                .ok_or_else(|| rquickjs::Exception::throw_message(&ctx, "fetch: malformed host response"))?;
            let body: Option<Object> = object.get("body")?;
            let blob = match body {
                Some(body) => mint_body(&ctx, &body)?,
                None => Value::new_null(ctx.clone()),
            };
            object.set("body", blob)?;
            Ok(object.into_value())
        })();
        match built {
            Ok(value) => {
                if settle.resolve_with(&ctx, value).is_err() {
                    (state.log)(&format!("fetch({request_id}) resolve failed: {}", format_exception(&ctx)));
                }
            }
            Err(_) => {
                settle.release(&ctx);
                (state.log)(&format!("fetch({request_id}) bad result wire: {}", format_exception(&ctx)));
            }
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

/// releases every `Persistent` GC root this state still owns - same contract as [`crate::tg::rpc::dispose`]
pub fn dispose(context: &rquickjs::Context, state: &Rc<FetchState>) {
    context.with(|ctx| {
        for (_, settle) in state.pending.borrow_mut().drain() {
            settle.release(&ctx);
        }
    });
}

#[cfg(test)]
#[path = "fetch_tests.rs"]
mod tests;

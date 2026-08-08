//! The web globals the sandbox promises that quickjs-ng does not already ship, minus the timers
//! (those are [`crate::api::timers`]).
//!
//! `Context::full` goes through quickjs-ng's own `JS_NewContext`, which already installs
//! `atob`/`btoa`, `DOMException`, `performance`, `queueMicrotask`, `BigInt`, `Proxy`/`Reflect`,
//! `WeakRef`/`FinalizationRegistry` and the rest of ES2022. rquickjs's own `intrinsic::All` is not
//! the same list and omits `JS_AddIntrinsicAToB`/`JS_AddIntrinsicDOMException`, so moving off
//! `Context::full` would silently drop documented surface. A test can only pin a context it built
//! itself, so [`install_globals`] refuses a context missing any of [`REQUIRED_INTRINSICS`] instead.
//!
//! What is left is `TextEncoder`/`TextDecoder`, `crypto`, `AbortController`/`AbortSignal` and
//! `structuredClone`, whose shapes live in `globals.js`. `Blob`/`File` install from here too, before
//! the prelude, so `structuredClone` can be taught about them without a JNI export, and so does
//! `URL`/`URLSearchParams` ([`crate::api::url`]), which needs a real parser rather than a shape.

use std::path::Path;
use std::rc::Rc;

use rquickjs::{ArrayBuffer, Ctx, Exception, Function, Object, Result as JsResult, TypedArray, Value};

use crate::{
    api::io::blob::{self, BlobState},
    sandbox::limits::ExternalMemory,
};

/// stand-in for the Kotlin `QuickJs.onRandomBytes` upcall
pub trait RandomHost {
    /// fills `out` with cryptographically strong bytes (`SecureRandom` on android). `false` == the
    /// host could not answer, and `crypto.getRandomValues` throws rather than handing back
    /// something weaker than it promised.
    fn random_bytes(&self, out: &mut [u8]) -> bool;
}

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/globals.qbc"));

/// what the context is expected to bring with it. The first three are the ones an `intrinsic::All`
/// context would be missing; the rest are cheap to name and catch a context slimmed down any other
/// way. `structuredClone`'s `WeakRef` check and `AbortController`'s `DOMException` are in
/// `globals.js`, so this is a precondition of the prelude and not only of the doc.
const REQUIRED_INTRINSICS: [&str; 8] =
    ["atob", "btoa", "DOMException", "performance", "queueMicrotask", "Proxy", "Reflect", "WeakRef"];

/// `spill_dir` is this plugin's own directory under the app's cache area, which the host creates,
/// sweeps and wipes. An empty path means this engine cannot spill and oversized blobs stay in
/// memory against the native budget.
///
/// The blob state is returned rather than dropped because [`crate::api::telegram::writes`] needs it: a `Blob` in a
/// `sendMedia`/`uploadFile` position is content only this side can read, and staging it into a file
/// is what lets the host upload it.
pub fn install_globals<'js>(
    ctx: &Ctx<'js>,
    host: Rc<dyn RandomHost>,
    spill_dir: &Path,
    external: Rc<ExternalMemory>,
) -> JsResult<Rc<BlobState>> {
    let globals = ctx.globals();
    for name in REQUIRED_INTRINSICS {
        let present = globals.get::<_, Value>(name).is_ok_and(|value| !value.is_undefined());
        if !present {
            return Err(Exception::throw_message(
                ctx,
                &format!("sandbox globals: this context has no '{name}'; the engine needs Context::full"),
            ));
        }
    }

    // before the prelude, which captures `globalThis.Blob` to teach `structuredClone` about it
    let blobs = blob::install(ctx, spill_dir, external.clone())?;

    crate::api::url::install_url(ctx)?;

    let natives = Object::new(ctx.clone())?;
    natives.set("cloneBlob", blob::make_clone_fn(ctx)?)?;

    let f = Function::new(ctx.clone(), |ctx: Ctx<'js>, input: String| TypedArray::<u8>::new(ctx, input.into_bytes()))?;
    natives.set("encodeUtf8", f)?;

    let f = Function::new(ctx.clone(), |ctx: Ctx<'js>, input: Value<'js>| decode_utf8(&ctx, input))?;
    natives.set("decodeUtf8", f)?;

    let f =
        Function::new(ctx.clone(), move |ctx: Ctx<'js>, array: Value<'js>| random_fill(&ctx, host.as_ref(), array))?;
    natives.set("randomFill", f)?;

    let factory = crate::utils::prelude::load(ctx, PRELUDE)?;
    factory.call::<_, ()>((natives,))?;
    Ok(blobs)
}

fn decode_utf8(ctx: &Ctx<'_>, input: Value<'_>) -> JsResult<String> {
    if let Ok(bytes) = TypedArray::<u8>::from_value(input.clone()) {
        let Some(bytes) = bytes.as_bytes() else {
            return Err(Exception::throw_type(ctx, "TextDecoder: the array is detached"));
        };
        return Ok(String::from_utf8_lossy(bytes).into_owned());
    }
    if let Some(buffer) = ArrayBuffer::from_value(input) {
        let Some(bytes) = buffer.as_bytes() else {
            return Err(Exception::throw_type(ctx, "TextDecoder: the buffer is detached"));
        };
        return Ok(String::from_utf8_lossy(bytes).into_owned());
    }
    Err(Exception::throw_type(ctx, "TextDecoder: expected a Uint8Array or an ArrayBuffer"))
}

fn random_fill<'js>(ctx: &Ctx<'js>, host: &dyn RandomHost, array: Value<'js>) -> JsResult<Value<'js>> {
    let Ok(typed) = TypedArray::<u8>::from_value(array.clone()) else {
        return Err(Exception::throw_type(ctx, "getRandomValues: expected a Uint8Array"));
    };

    // filled before the pointer is taken, because `random_bytes` is a JNI upcall: whatever the host
    // does there, it must not be able to happen between taking the pointer and writing through it
    let mut bytes = vec![0u8; typed.len()];
    if !host.random_bytes(&mut bytes) {
        return Err(Exception::throw_message(ctx, "getRandomValues: the host has no randomness to give"));
    }

    let Some(raw) = typed.as_raw() else {
        return Err(Exception::throw_type(ctx, "getRandomValues: the array is detached"));
    };
    if raw.len != bytes.len() {
        return Err(Exception::throw_type(ctx, "getRandomValues: the array was resized"));
    }
    // SAFETY: `raw` describes the live backing store of this Uint8Array's own window (`as_raw`
    // resolves byteOffset), taken on the thread that owns the runtime, and the copy is the very
    // next thing that happens - nothing in between runs JS, upcalls, or allocates.
    unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), raw.ptr.as_ptr(), raw.len) };
    Ok(array)
}

#[cfg(test)]
#[path = "globals_tests.rs"]
mod tests;

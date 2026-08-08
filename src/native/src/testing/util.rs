//! Test-only plumbing shared by the module test suites.

use std::ops::Deref;
use std::rc::Rc;
use std::sync::{Arc, Mutex, MutexGuard};

use rquickjs::function::Rest;
use rquickjs::{Coerced, Context, Function, Object, Runtime};

/// One TL object behind a fake handle: its constructor name, and each field already as a wire.
pub(crate) struct FakeObject {
    pub(crate) name: String,
    pub(crate) fields: Vec<(String, String)>,
}

/// What `TlHandles` is on the host side of the bridge, for a suite whose subject is some *other*
/// module's use of it: a table of minted objects, answering the `TlHost` upcalls the proxy makes.
///
/// `tl_own_keys` answers a comma-joined list because that is what `proxy::keys_to_array` splits on;
/// a fake that joined on anything else would hand `Object.keys` one key holding the whole list.
#[derive(Default)]
pub(crate) struct FakeHandles {
    objects: std::cell::RefCell<std::collections::HashMap<i64, FakeObject>>,
    next: std::cell::Cell<i64>,
}

impl FakeHandles {
    pub(crate) fn mint<K: Into<String>>(&self, name: &str, fields: impl IntoIterator<Item = (K, String)>) -> i64 {
        let id = self.next.get() + 1;
        self.next.set(id);
        let fields = fields.into_iter().map(|(k, v)| (k.into(), v)).collect();
        self.objects.borrow_mut().insert(id, FakeObject { name: name.to_string(), fields });
        id
    }

    /// the read-only object wire, which is what `PluginReads.mint(readOnly = true)` answers with
    pub(crate) fn mint_wire<K: Into<String>>(
        &self,
        name: &str,
        fields: impl IntoIterator<Item = (K, String)>,
    ) -> String {
        format!("HOR{}", self.mint(name, fields))
    }

    pub(crate) fn get(&self, handle: i64, key: &str) -> String {
        let objects = self.objects.borrow();
        let Some(object) = objects.get(&handle) else {
            return "Phandle-expired\n\n\n\nexpired".to_string();
        };
        if key == "_" {
            return format!("S{}", object.name);
        }
        match object.fields.iter().find(|(name, _)| name == key) {
            Some((_, wire)) => wire.clone(),
            None => "N".to_string(),
        }
    }

    pub(crate) fn has(&self, handle: i64, key: &str) -> i32 {
        let objects = self.objects.borrow();
        match objects.get(&handle) {
            None => -1,
            Some(object) => i32::from(key == "_" || object.fields.iter().any(|(name, _)| name == key)),
        }
    }

    pub(crate) fn own_keys(&self, handle: i64) -> Option<String> {
        let objects = self.objects.borrow();
        let object = objects.get(&handle)?;
        let mut keys = vec!["_".to_string()];
        keys.extend(object.fields.iter().map(|(name, _)| name.clone()));
        Some(keys.join(","))
    }

    pub(crate) fn release(&self, handle: i64) {
        self.objects.borrow_mut().remove(&handle);
    }
}

/// Evaluates for a string, reporting a thrown exception the way the engine formats one for the
/// host rather than as rquickjs's opaque `Error::Exception`.
pub(crate) fn eval_string(ctx: &Context, code: &str) -> String {
    ctx.with(|ctx| match ctx.eval::<String, _>(code) {
        Ok(value) => value,
        Err(rquickjs::Error::Exception) => panic!("{}", crate::tg::rpc::format_exception(&ctx)),
        Err(e) => panic!("{e:?}"),
    })
}

/// [`eval_string`] for code evaluated for its effect.
pub(crate) fn eval_unit(ctx: &Context, code: &str) {
    ctx.with(|ctx| match ctx.eval::<(), _>(code) {
        Ok(()) => {}
        Err(rquickjs::Error::Exception) => panic!("{}", crate::tg::rpc::format_exception(&ctx)),
        Err(e) => panic!("{e:?}"),
    });
}

/// [`eval_string`] over `JSON.stringify`, for asserting on a shape rather than on a scalar.
pub(crate) fn eval_json(ctx: &Context, code: &str) -> String {
    eval_string(ctx, &format!("JSON.stringify({code})"))
}

/// What a refusal looks like from JS: `[is a PluginError, code, grant, message]`, or `'no-throw'`.
/// The grant is part of it because `not-granted` naming the wrong scope is the failure a test of a
/// gate is written to catch.
pub(crate) fn catch_json(ctx: &Context, code: &str) -> String {
    eval_string(
        ctx,
        &format!(
            r#"(() => {{
                try {{ {code}; return 'no-throw'; }}
                catch (e) {{
                    return JSON.stringify([e instanceof inu.PluginError, e.code, e.grant ?? null, e.message]);
                }}
            }})()"#
        ),
    )
}

/// What a test reads a module's diagnostics out of.
///
/// [`crate::Log`] is `Send + Sync`, so the `Rc<RefCell<Vec<String>>>` the suite used to build one
/// out of no longer fits. Keeps `borrow`/`borrow_mut` rather than exposing the lock, so an
/// assertion reads exactly as it did.
pub(crate) struct Logs(Mutex<Vec<String>>);

impl Logs {
    pub(crate) fn new() -> Arc<Logs> {
        Arc::new(Logs(Mutex::new(Vec::new())))
    }

    pub(crate) fn borrow(&self) -> MutexGuard<'_, Vec<String>> {
        self.0.lock().expect("a test thread panicked holding the log")
    }

    pub(crate) fn borrow_mut(&self) -> MutexGuard<'_, Vec<String>> {
        self.borrow()
    }
}

/// the [`crate::Log`] a module writes into this sink through
pub(crate) fn log_sink(logs: &Arc<Logs>) -> crate::Log {
    let logs = logs.clone();
    Arc::new(move |msg: &str| logs.borrow_mut().push(msg.to_string()))
}

/// Runs a module's `dispose` when the test's state binding goes out of scope, on the failing path
/// too. Skipping disposal leaves quickjs GC roots (`Persistent` has no `Drop`) and `JS_FreeRuntime`
/// aborts the process over them, which under `cargo test`'s shared process turns one failed
/// assertion into a suite with no results at all.
///
/// Holds its own `Context` clone, which keeps the `Runtime` alive regardless of the order the
/// test's own bindings drop in.
pub(crate) struct DisposeOnDrop<S> {
    ctx: Context,
    state: Rc<S>,
    dispose: fn(&Context, &Rc<S>),
}

impl<S> DisposeOnDrop<S> {
    pub(crate) fn new(ctx: &Context, state: Rc<S>, dispose: fn(&Context, &Rc<S>)) -> Self {
        DisposeOnDrop { ctx: ctx.clone(), state, dispose }
    }
}

impl<S> Deref for DisposeOnDrop<S> {
    type Target = Rc<S>;

    fn deref(&self) -> &Rc<S> {
        &self.state
    }
}

impl<S> Drop for DisposeOnDrop<S> {
    fn drop(&mut self) {
        (self.dispose)(&self.ctx, &self.state);
    }
}

/// Runs a bundled oracle plugin the way the host would - a console that captures instead of
/// reaching logcat, then a drain of whatever it left pending - and hands back what it printed.
/// A plugin whose surface is pure needs nothing else, which is what makes the oracle a real test
/// here rather than only on a device.
pub(crate) fn run_capturing_console(rt: &Runtime, ctx: &Context, source: &str) -> Vec<String> {
    let lines = install_capturing_console(ctx);
    ctx.with(|ctx| match ctx.eval::<(), _>(source) {
        Ok(()) => {}
        Err(rquickjs::Error::Exception) => panic!("{}", crate::tg::rpc::format_exception(&ctx)),
        Err(e) => panic!("{e:?}"),
    });
    while rt.is_job_pending() {
        rt.execute_pending_job().ok();
    }
    let lines = lines.borrow();
    lines.clone()
}

/// The console half of [`run_capturing_console`], for an oracle whose assertions only run once the
/// host has fed it something: install this, evaluate the source, drive the host, then read the lines.
pub(crate) fn install_capturing_console(ctx: &Context) -> Arc<Logs> {
    let lines = Logs::new();
    ctx.with(|ctx| {
        let console = Object::new(ctx.clone()).unwrap();
        for name in ["log", "error", "warn", "info", "debug"] {
            let lines = lines.clone();
            let f = Function::new(ctx.clone(), move |args: Rest<Coerced<String>>| {
                lines.borrow_mut().push(args.0.iter().map(|a| a.0.as_str()).collect::<Vec<_>>().join(" "));
            })
            .unwrap();
            console.set(name, f).unwrap();
        }
        ctx.globals().set("console", console).unwrap();
    });
    lines
}

/// The one thing a bundled oracle is held to, for every oracle in the crate.
///
/// An oracle that stopped halfway prints no `FAIL` either, so reaching its own last line is part of
/// passing. The count is **exact** and never a floor: in a suite written out of `expectThrow` a
/// member that vanished reads as a refusal, and only the count tells those apart - so a floor is
/// cleared by every number it is not equal to, which is the failure the oracle exists to catch.
/// Nothing may `SKIP` either: every escape hatch in an oracle exists for a device with no peer, no
/// network or no login, and a harness has all three, so a block that starts skipping here is a
/// surface that stopped answering.
pub(crate) fn assert_oracle_exact(lines: &[String], done: &str, count: usize) {
    assert_oracle_exact_skipping(lines, done, count, &[]);
}

/// [`assert_oracle_exact`] for a harness that genuinely cannot answer part of an oracle - no
/// network, no telegram chat, no forum. The skips are listed rather than tolerated, so one that
/// appears is still a failure and the ones named here have to keep being the only ones.
pub(crate) fn assert_oracle_exact_skipping(lines: &[String], done: &str, count: usize, skips: &[&str]) {
    let failures: Vec<&String> = lines.iter().filter(|l| l.starts_with("FAIL")).collect();
    assert!(failures.is_empty(), "{failures:#?}");
    assert!(lines.iter().any(|l| l == done), "the oracle did not finish: {lines:#?}");
    let skipped: Vec<&str> = lines.iter().filter(|l| l.starts_with("SKIP")).map(String::as_str).collect();
    assert_eq!(skipped, skips, "the harness answers everything else, so nothing else may skip");
    assert_eq!(
        lines.iter().filter(|l| l.starts_with("PASS")).count(),
        count,
        "the oracle ran a different number of assertions than this floor pins: {lines:#?}",
    );
}

/// `common.d.ts`, the normative contract, as the file a plugin author reads
pub(crate) const CONTRACT: &str = include_str!("../../../plugins/common.d.ts");

/// `fs.d.ts`, which states `inu.fs`'s own numbers
pub(crate) const FS_CONTRACT: &str = include_str!("../../../plugins/fs.d.ts");

/// `android.xposed.d.ts`, which states `inu.xposed`'s own numbers
pub(crate) const XPOSED_CONTRACT: &str = include_str!("../../../plugins/android.xposed.d.ts");

/// `android.d.ts`, which states what the platform-specific half promises - `inu.android.resourceIcon`
/// among them, whose rules `icons.rs` enforces
pub(crate) const ANDROID_CONTRACT: &str = include_str!("../../../plugins/android.d.ts");

/// Reads the number the contract states, given the sentence it appears in with `{}` standing in for
/// the number. A ceiling pinned to a constant the app also ships is pinned to a copy of itself and
/// can be raised to its maximum with the suite green; this is what makes the promise the number.
///
/// Exactly one place in the document may match, so a ceiling the contract states twice cannot be
/// pinned to whichever of the two happened to be updated.
pub(crate) fn stated_number(doc: &str, phrase: &str) -> u64 {
    let (head, tail) = phrase.split_once("{}").expect("mark the number with {}");
    assert!(!tail.is_empty(), "the phrase must carry text after the number to anchor on");
    let bytes = doc.as_bytes();
    let mut found: Vec<u64> = Vec::new();
    let mut from = 0;
    while let Some(offset) = doc[from..].find(tail) {
        let end = from + offset;
        from = end + tail.len();
        let mut start = end;
        while start > 0 && bytes[start - 1].is_ascii_digit() {
            start -= 1;
        }
        if start < end && doc[..start].ends_with(head) {
            found.push(doc[start..end].parse().expect("digits"));
        }
    }
    assert_eq!(found.len(), 1, "the contract states '{phrase}' {} time(s)", found.len());
    found[0]
}

fn header_lines(source: &str) -> impl Iterator<Item = &str> {
    source.lines().take_while(|line| !line.contains("==/UserScript=="))
}

/// The grants a bundled oracle's *own manifest* asks for. Running it under these rather than a list
/// written in the test is what makes the `@grant` header load-bearing: the device reads that header
/// and nothing else, so a suite granting a scope the header forgot would pass on a plugin the app
/// then refuses.
pub(crate) fn manifest_grants(source: &str) -> Vec<&str> {
    header_lines(source).filter_map(|line| line.trim().strip_prefix("// @grant")).map(str::trim).collect()
}

/// Every directive of a bundled oracle's own manifest, base key lowercased and repeated once per
/// value. That is the shape `QuickJs.installInfo` flattens `PluginManifest.raw` into, so a run site
/// builds `inu.info().header` out of what the device builds it out of rather than out of a literal
/// written next to the assertion it is checked by.
pub(crate) fn manifest_header(source: &str) -> Vec<(String, String)> {
    header_lines(source)
        .filter_map(|line| {
            let directive = line.trim().strip_prefix("//")?.trim().strip_prefix('@')?;
            let (key, value) = match directive.split_once(char::is_whitespace) {
                Some((key, value)) => (key, value.trim()),
                None => (directive, ""),
            };
            Some((key.to_ascii_lowercase(), value.to_string()))
        })
        .collect()
}

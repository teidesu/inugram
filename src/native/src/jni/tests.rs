/// Wiring that exists only at a JNI entry point, where no test can reach it: an export is an
/// `extern "system"` fn taking a live `EnvUnowned`. Deleting one of these lines leaves an engine
/// unbounded, a subsystem inert or a plugin's own failures indistinguishable from the host's, with
/// every behavioural test still green - so the source is the assertion.
#[cfg(test)]
mod wiring {
    const SOURCE: &str = concat!(include_str!("exports.rs"), include_str!("bridge.rs"), include_str!("hosts.rs"),);

    /// An export is either a hand-written `extern "system" fn` or an `engine_export!` invocation,
    /// and a test asking for one by name has no business knowing which.
    fn body_of(name: &str) -> &'static str {
        let (at, end) = match SOURCE.find(&format!("fn Java_desu_inugram_helpers_plugins_QuickJs_{name}(")) {
            Some(at) => (at, "\n}\n"),
            None => {
                let at = SOURCE
                    .find(&format!("Java_desu_inugram_helpers_plugins_QuickJs_{name},"))
                    .unwrap_or_else(|| panic!("no JNI export named '{name}'"));
                // rustfmt joins an invocation it can fit, so the close is `);` or `});`
                (at, "")
            }
        };
        let body = &SOURCE[at..];
        let to = match end {
            "" => ["\n);\n", "\n});\n"].iter().filter_map(|m| body.find(m).map(|at| at + m.len())).min(),
            _ => body.find(end).map(|to| to + end.len()),
        };
        &body[..to.unwrap_or(body.len())]
    }

    /// rustfmt owns the layout inside an export, so a needle spanning an argument list has to be
    /// matched against the body with its whitespace taken out
    fn without_space(body: &str) -> String {
        body.chars().filter(|c| !c.is_whitespace()).collect()
    }

    fn position_of(body: &str, needle: &str, what: &str) -> usize {
        body.find(needle).unwrap_or_else(|| panic!("{what}"))
    }

    #[test]
    fn a_descriptor_that_does_not_resolve_names_itself() {
        let at = SOURCE.find("    pub(crate) fn new(env: &mut Env").expect("JniBridge::new moved");
        let body = &SOURCE[at..];
        let body = &body[..body.find("\n    }\n").map(|end| end + 6).unwrap_or(body.len())];
        assert!(
            body.contains("NoSuchMethodError"),
            "a typo in one descriptor breaks every plugin; without this it names nothing",
        );
    }

    #[test]
    fn a_new_runtime_gets_its_heap_ceiling() {
        assert!(
            body_of("nativeCreate").contains("crate::engine::deadline::apply_heap_limit(&rt)"),
            "an engine created without its heap ceiling can take the app down with it",
        );
    }

    #[test]
    fn an_interrupted_entry_is_stopped_but_the_plugin_is_not_disabled() {
        let body = body_of("nativeCreate");
        assert!(
            without_space(body).contains("crate::engine::deadline::install_interrupt_handler(&rt,"),
            "without the interrupt handler a spinning plugin wedges the queue for good",
        );
        assert!(
            !body.contains("interrupt_log(&fault("),
            "the doc promises only the turn dies, and a turn charged for a slow host call did nothing wrong",
        );
    }

    #[test]
    fn unhandled_rejections_are_tracked_from_engine_creation() {
        assert!(
            body_of("nativeCreate").contains("crate::tg::rpc::install_rejection_tracker(&rt"),
            "an async handler that throws would fail silently",
        );
    }

    #[test]
    fn no_entry_point_builds_its_own_diagnostic_sink() {
        // both shapes an export comes in, counted exactly: scanning for only the hand-written one
        // would walk past every generated export while still passing
        const WRITTEN: &str = "pub extern \"system\" fn Java_desu_inugram_helpers_plugins_QuickJs_";
        const PREFIX: &str = "Java_desu_inugram_helpers_plugins_QuickJs_";
        let mut names = Vec::new();
        for (index, _) in SOURCE.match_indices(WRITTEN) {
            names.push(SOURCE[index + WRITTEN.len()..].chars().take_while(|c| *c != '(').collect::<String>());
        }
        // the generated ones, found through the invocation rather than through a layout rustfmt owns
        for (index, _) in SOURCE.match_indices("engine_export!(") {
            let rest = &SOURCE[index..];
            let at = rest.find(PREFIX).expect("an engine_export! that names no export");
            names.push(rest[at + PREFIX.len()..].chars().take_while(|c| *c != ',').collect::<String>());
        }
        assert_eq!(names.len(), 43, "the set of QuickJs entry points moved");
        for name in names {
            assert!(
                !body_of(&name).contains("emit_console("),
                "JNI export '{name}' logs past make_log, so its faults reach the host as ordinary errors",
            );
        }
    }

    #[test]
    fn installing_the_api_installs_the_sandbox_globals_and_the_timer_wheel() {
        let body = body_of("nativeInstallApi");
        assert!(
            body.contains("crate::engine::globals::install_globals("),
            "the documented sandbox globals would be missing"
        );
        assert!(body.contains("crate::engine::timers::install_timers("), "setTimeout would be missing");
        assert!(
            body.contains("crate::tl::utils::install_utils(&ctx)") && body.contains("crate::tl::message::install_message(&ctx, &shared)"),
            "inu.utils/inu.Message would be missing, and `inu.Message` cannot install without the helpers utils returns",
        );
        assert!(
            body.contains("std::path::PathBuf::from(spill_dir)")
                && body.contains(
                    "crate::engine::globals::install_globals(&ctx, random_host, &spill_dir, external.clone())"
                ),
            "an engine installed with anything but the host's own spill directory keeps every blob in memory",
        );
    }

    /// the read surface takes the peer helpers `utils` returns and the `Message` class, so it can
    /// only install behind both - and it hands `account.rs` the prototype every handle is minted
    /// with, so installing it after the account api is what makes the getters exist at all
    #[test]
    fn installing_the_api_installs_the_account_read_surface() {
        let body = body_of("nativeInstallApi");
        let accounts = position_of(body, "crate::tg::account::install_account(", "inu.account would be missing");
        let reads = position_of(body, "crate::tg::reads::install_reads(", "the Account getters would be missing");
        let message = position_of(body, "crate::tl::message::install_message(", "inu.Message would be missing");
        assert!(accounts < reads && message < reads);
    }

    /// `deserialize.rs`'s own tests install it directly, so a deleted line here leaves every plugin
    /// without `inu.interceptDeserialize` and every rule silently doing nothing - which is exactly
    /// what a plugin holding one cannot tell from a rule that matched no object
    #[test]
    fn installing_the_rpc_family_installs_the_deserialize_rules() {
        assert!(
            body_of("nativeInstallRpc").contains("crate::tg::deserialize::install_deserialize("),
            "inu.interceptDeserialize would be missing",
        );
    }

    /// one table for both, or a write through an `invokeRpc` result would leave a cached field on an
    /// `Account` read stale, which `common.d.ts` promises it cannot
    #[test]
    fn the_rpc_and_account_views_share_one_table() {
        assert!(
            body_of("nativeInstallRpc").contains("let tl = engine.views.clone()"),
            "a second TlViews would give each family its own field-cache epoch",
        );
    }

    /// `fetch.rs`'s own tests install it directly, so a deleted line here would leave every plugin
    /// without a `fetch` and every behavioural test green
    #[test]
    fn installing_the_api_installs_fetch_behind_the_timer_wheel() {
        let body = body_of("nativeInstallApi");
        assert!(
            body.contains("engine.blobs = blobs.clone()"),
            "without the blob table kept, neither `fs.write` nor a fetched body can exist",
        );
        let timers = position_of(body, "crate::engine::timers::install_timers(", "setTimeout would be missing");
        let fetch = position_of(body, "crate::io::fetch::install_fetch(", "the global fetch would be missing");
        assert!(timers < fetch, "`fetch.js` captures setTimeout at install to measure `timeout` on",);
    }

    /// the directory and the cap are the host's to answer, and an engine handed anything else would
    /// write a plugin's durable storage somewhere the host neither sweeps nor wipes
    #[test]
    fn installing_fs_uses_the_hosts_own_directory_and_quota() {
        let body = body_of("nativeInstallFs");
        assert!(body.contains("std::path::Path::new(&dir)"), "the fs root must be the one the host named");
        assert!(body.contains("engine.blobs.clone()"), "fs.write reads a `Blob` through the blob table");
        assert!(body.contains("crate::io::fs::UNCAPPED"), "unsafe.fs would silently get the default cap");
    }

    /// the whole app is behind this one, so an engine that installed it with anything but the
    /// engine's own grant gate would answer every reflection call for a plugin nobody granted it to
    #[test]
    fn installing_jvm_gates_it_on_the_engines_own_grant_host() {
        let body = body_of("nativeInstallJvm");
        assert!(
            body.contains("let grants: Rc<dyn GrantHost> = engine.bridge.clone()")
                && body.contains("crate::platform::jvm::install_jvm(&ctx, host, grants"),
            "inu.jvm installed without the grant gate reflects for anyone",
        );
    }

    /// entering an engine from inside the reflected call that handed the object over is a
    /// same-engine re-entry, i.e. a process abort - so the callback has an entry point of its own
    #[test]
    fn a_jvm_runnable_comes_back_through_its_own_entry_point() {
        assert!(
            body_of("nativeJvmCallback").contains("crate::platform::jvm::dispatch_callback("),
            "a java Runnable would be run and the plugin's callback never fire",
        );
    }

    #[test]
    fn a_visibility_change_reaches_the_wheel_before_the_plugin() {
        let body = body_of("nativeAppVisibilityChanged");
        let wheel = position_of(
            body,
            "crate::engine::timers::set_visible(",
            "backgrounding no longer throttles the timer wheel",
        );
        let callbacks =
            position_of(body, "crate::api::app_visibility_changed(", "inu.onAppVisibilityChange never fires");
        assert!(wheel < callbacks, "a callback arming a timer must already arm it against the new floor",);
    }

    #[test]
    fn a_timer_wake_runs_the_wheel() {
        assert!(
            body_of("nativeRunTimers").contains("crate::engine::timers::run_due("),
            "the wake the engine asked for would arrive and do nothing",
        );
    }

    #[test]
    fn unload_drops_the_timers_after_the_plugins_own_callbacks() {
        let body = body_of("nativeNotifyUnload");
        let api = position_of(body, "crate::api::notify_unload(", "inu.onUnload never fires");
        let timers =
            position_of(body, "crate::engine::timers::notify_unload(", "a stray setInterval would outlive the plugin");
        assert!(api < timers, "an onUnload callback clearing its own timers must still find them");
    }

    /// Every upcall is a member of `PluginListener`, and nothing in any suite checks these
    /// descriptors against it: `get_method_id` resolves them off `PluginBridge` at runtime, so a
    /// mismatch is only found by the app build. It also fails whole rather than per method -
    /// `JniBridge::new` `?`-chains every lookup, so one wrong signature makes the bridge `None`,
    /// `nativeCreate` answers 0, and every plugin dies at `installInfo` with a message naming
    /// nothing.
    ///
    /// The file rather than `PluginBridge.kt` because `by` writes the forwarders: the declarations
    /// are the only place the shapes are spelled out.
    const KOTLIN_BRIDGE: &str = include_str!("../../../kotlin/helpers/plugins/PluginListener.kt");

    fn jni_type(kotlin: &str) -> String {
        let bare = kotlin.trim().trim_end_matches('?');
        match bare {
            "Int" => "I".into(),
            "Long" => "J".into(),
            "Boolean" => "Z".into(),
            "Float" => "F".into(),
            "Double" => "D".into(),
            "String" => "Ljava/lang/String;".into(),
            "ByteArray" => "[B".into(),
            "Array<String>" => "[Ljava/lang/String;".into(),
            other => panic!("no JNI mapping for kotlin type '{other}'; teach jni_type about it"),
        }
    }

    fn kotlin_upcalls() -> Vec<(String, String)> {
        let mut out = Vec::new();
        for line in KOTLIN_BRIDGE.lines() {
            let line = line.trim();
            let Some(rest) = line.strip_prefix("fun ") else {
                continue;
            };
            let Some(open) = rest.find('(') else { continue };
            let Some(close) = rest.find(')') else {
                continue;
            };
            let name = rest[..open].to_string();
            let params = &rest[open + 1..close];
            let mut sig = String::from("(");
            for param in params.split(',') {
                if param.trim().is_empty() {
                    continue;
                }
                let ty = param.split_once(':').expect("a parameter with no type").1;
                sig.push_str(&jni_type(ty));
            }
            sig.push(')');
            let tail = rest[close + 1..].trim();
            sig.push_str(&match tail.strip_prefix(':') {
                Some(ret) => jni_type(ret.split(['=', '{']).next().unwrap_or(ret).trim()),
                None => "V".into(),
            });
            out.push((name, sig));
        }
        out
    }

    fn rust_descriptors() -> Vec<(String, String)> {
        // starts at the `ConsoleSink`, not the `JniBridge`: `onConsole` was lifted out into a sink
        // of its own and a parser anchored on the bridge alone stopped seeing it
        let at = SOURCE.find("let console = Arc::new(ConsoleSink {").expect("no ConsoleSink literal");
        let body = &SOURCE[at..];
        let body = &body[..body.find("\n        };").expect("unterminated JniBridge literal")];
        // the literal is rustfmt'd, so a descriptor is one line or three; collapsing whitespace
        // makes both read as `method("name", "sig")`
        let flat: String = body.split_whitespace().collect::<Vec<_>>().join(" ");
        let mut out = Vec::new();
        let mut rest = flat.as_str();
        while let Some(at) = rest.find("method(") {
            rest = rest[at + "method(".len()..].trim_start();
            let Some(after) = rest.strip_prefix('"') else {
                continue;
            };
            rest = after;
            let (name, tail) = rest.split_once('"').expect("unterminated method name");
            let tail = tail.trim_start_matches([',', ' ']);
            let sig = tail.strip_prefix('"').and_then(|t| t.split_once('"')).expect("no signature").0;
            out.push((name.to_string(), sig.to_string()));
        }
        out
    }

    #[test]
    fn every_jni_upcall_descriptor_matches_the_kotlin_it_names() {
        let kotlin: std::collections::HashMap<_, _> = kotlin_upcalls().into_iter().collect();
        let rust = rust_descriptors();
        // exact rather than a floor: the cross-check below only speaks for the descriptors the
        // parser found, so a parse that quietly lost some passes while covering nothing of them
        assert_eq!(rust.len(), 56, "the set of upcalls rust looks up changed");

        let mut wrong = Vec::new();
        for (name, sig) in &rust {
            match kotlin.get(name) {
                None => wrong.push(format!("{name}: rust calls it, PluginListener has no such member")),
                Some(actual) if actual != sig => wrong.push(format!("{name}: rust says {sig}, PluginListener is {actual}")),
                Some(_) => {}
            }
        }
        assert!(wrong.is_empty(), "JNI descriptor mismatch:\n{}", wrong.join("\n"));
    }

    /// Every `external fun` in the bridge, paired with the package of the file declaring it.
    fn kotlin_natives() -> Vec<(String, String)> {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../kotlin/helpers/plugins");
        let mut files = vec![root];
        let mut out = Vec::new();
        while let Some(path) = files.pop() {
            if path.is_dir() {
                files.extend(std::fs::read_dir(&path).unwrap().map(|e| e.unwrap().path()));
                continue;
            }
            if path.extension().is_none_or(|e| e != "kt") {
                continue;
            }
            let source = std::fs::read_to_string(&path).unwrap();
            let package = source
                .lines()
                .find_map(|l| l.trim().strip_prefix("package "))
                .unwrap_or_else(|| panic!("{} declares no package", path.display()))
                .trim()
                .to_string();
            for line in source.lines() {
                let Some(rest) = line.trim().split("external fun ").nth(1) else { continue };
                let Some(open) = rest.find('(') else { continue };
                out.push((rest[..open].trim().to_string(), package.clone()));
            }
        }
        out
    }

    /// The package a JNI export encodes, e.g.
    /// `Java_desu_inugram_helpers_plugins_platform_PluginXposed_00024Native_nativeInit` ->
    /// (`desu.inugram.helpers.plugins.platform`, `nativeInit`). None of our names contain an
    /// underscore, which is what makes splitting on one sound (JNI escapes a real one as `_1`).
    fn export_package_and_method(symbol: &str) -> (String, String) {
        let rest = symbol.strip_prefix("Java_").expect("not a JNI export");
        // a nested class is `_00024Nested`, and the method is whatever follows it
        let (owner, method) = match rest.split_once("_00024") {
            Some((owner, nested)) => (owner, nested.split_once('_').expect("no method after the nested class").1),
            None => rest.rsplit_once('_').expect("no method"),
        };
        // the last segment of the owner is the class; everything before it is the package
        let package = owner.rsplit_once('_').expect("no package").0.replace('_', ".");
        (package, method.to_string())
    }

    fn rust_exports() -> Vec<String> {
        let mut out: Vec<String> = SOURCE
            .match_indices("Java_desu_inugram_helpers_plugins_")
            .map(|(at, _)| {
                let tail = &SOURCE[at..];
                let end = tail.find(|c: char| !c.is_ascii_alphanumeric() && c != '_').unwrap_or(tail.len());
                tail[..end].to_string()
            })
            .collect();
        out.sort();
        out.dedup();
        out
    }

    /// A JNI export's name carries the *package* of the class it implements, so moving that class
    /// into a subpackage silently unbinds every one of its natives - the app then throws
    /// `UnsatisfiedLinkError` at the first call and nothing before launch says a word.
    /// `PluginXposed` moved into `platform/` and its six exports kept the old name, which left
    /// `inu.xposed` dead on device. `QuickJs` survives only by never having moved.
    #[test]
    fn every_jni_export_names_the_package_its_kotlin_actually_lives_in() {
        let kotlin: std::collections::HashMap<_, _> = kotlin_natives().into_iter().collect();
        let exports = rust_exports();
        assert_eq!(exports.len(), 49, "the set of JNI exports changed");

        let mut wrong = Vec::new();
        for symbol in &exports {
            let (package, method) = export_package_and_method(symbol);
            match kotlin.get(&method) {
                None => wrong.push(format!("{symbol}: no `external fun {method}` anywhere in the bridge")),
                Some(actual) if *actual != package => {
                    wrong.push(format!("{symbol}: names package {package}, but {method} is declared in {actual}"))
                }
                Some(_) => {}
            }
        }
        assert!(wrong.is_empty(), "JNI export/package mismatch:\n{}", wrong.join("\n"));
    }

    #[test]
    fn every_kotlin_native_has_a_rust_export() {
        let exports: std::collections::HashSet<_> =
            rust_exports().iter().map(|s| export_package_and_method(s)).collect();
        let orphans: Vec<_> = kotlin_natives()
            .into_iter()
            .filter(|(method, package)| !exports.contains(&(package.clone(), method.clone())))
            .map(|(method, package)| format!("{package}.{method}"))
            .collect();
        assert!(orphans.is_empty(), "`external fun`s nothing in rust exports: {orphans:?}");
    }

    #[test]
    fn every_kotlin_upcall_is_one_rust_looks_up() {
        let rust: std::collections::HashSet<_> = rust_descriptors().into_iter().map(|(n, _)| n).collect();
        let orphans: Vec<_> = kotlin_upcalls().into_iter().map(|(n, _)| n).filter(|n| !rust.contains(n)).collect();
        assert!(orphans.is_empty(), "upcalls nothing in rust ever calls: {orphans:?}");
    }
}

#[cfg(test)]
mod info_tests {
    use super::super::info::{build_info_object, install_inu, InuInfo};
    use rquickjs::{Context, Runtime};
    use std::sync::Arc;

    #[test]
    fn a_repeated_header_directive_reaches_js_as_an_array() {
        let rt = Runtime::new().unwrap();
        let ctx = Context::full(&rt).unwrap();
        let info = InuInfo {
            app_version: "1.0".into(),
            app_build: "1".into(),
            api_version: 1,
            layer: 1,
            language: "en".into(),
            // the wire repeats the key once per value, exactly as QuickJs.installInfo flattens it
            header: vec![
                ("grant".into(), "kv".into()),
                ("name".into(), "demo".into()),
                ("grant".into(), "openUrl".into()),
            ],
        };
        ctx.with(|ctx| {
            let obj = build_info_object(ctx.clone(), &info).unwrap();
            ctx.globals().set("__info", obj).unwrap();
            let is_array: bool = ctx.eval("Array.isArray(__info.header.grant)").unwrap();
            assert!(is_array, "a repeated directive must not collapse to a string");
            let grants: Vec<String> = ctx.eval("__info.header.grant").unwrap();
            assert_eq!(grants, vec!["kv".to_string(), "openUrl".to_string()]);
            let names: Vec<String> = ctx.eval("__info.header.name").unwrap();
            assert_eq!(names, vec!["demo".to_string()], "a single-value directive is still an array");
        });
    }

    const ORACLE: &str = include_str!("../../../res/assets-debug/inu_plugins/info-test.js");

    /// The bundled oracle is the only test `inu.info()` gets on a device, and it needs no JNI to
    /// run here: [`install_inu`] is an ordinary crate function, and the header the app hands it is
    /// `PluginManifest.raw` flattened key-per-value, which the oracle's *own* manifest supplies.
    /// So what the app would have built the object out of is what builds it here.
    #[test]
    fn the_bundled_info_test_plugin_passes() {
        let rt = Runtime::new().unwrap();
        let ctx = Context::full(&rt).unwrap();
        let info = Arc::new(InuInfo {
            app_version: "6.81".into(),
            app_build: "6822".into(),
            api_version: 1,
            layer: 214,
            language: "en".into(),
            header: crate::testing::util::manifest_header(ORACLE),
        });
        ctx.with(|ctx| install_inu(&ctx, info).unwrap());

        let lines = crate::testing::util::run_capturing_console(&rt, &ctx, ORACLE);
        crate::testing::util::assert_oracle_exact(&lines, "info test done", 11);
    }
}

/// The one thing about the JNI bridge that neither the compiler nor a device-free test can
/// otherwise see: whether the argument list an upcall passes agrees with the java descriptor it
/// was looked up under.
///
/// A mismatch is not a compile error - `Arg` is the same type either way - and it does not surface
/// as a wrong value either: `call_method_unchecked` reads the `jvalue` array according to the
/// *descriptor*, so a swapped `Int`/`Long` reads eight bytes where four were written and the host
/// is handed garbage. So both halves are re-derived from this file's own source and compared.
#[cfg(test)]
mod bridge_signature_tests {
    /// `on_console` is called through [`ConsoleSink`] rather than a `call_*` helper
    const NOT_CALLED_HERE: &[&str] = &["on_console"];

    fn squeeze(source: &str) -> String {
        source.chars().filter(|c| !c.is_whitespace()).collect()
    }

    /// the parameter descriptors of a java signature, e.g. `(I[Ljava/lang/String;)V` -> ["I", "[Ljava/lang/String;"]
    fn params(sig: &str) -> Vec<String> {
        let inner = sig
            .split_once('(')
            .and_then(|(_, rest)| rest.split_once(')'))
            .expect("a method descriptor has parentheses")
            .0;
        let mut out = Vec::new();
        let mut chars = inner.chars().peekable();
        while let Some(c) = chars.next() {
            let mut d = String::from(c);
            while d.ends_with('[') {
                d.push(chars.next().expect("an array descriptor names an element type"));
            }
            if d.ends_with('L') {
                for c in chars.by_ref() {
                    d.push(c);
                    if c == ';' {
                        break;
                    }
                }
            }
            out.push(d);
        }
        out
    }

    fn returns(sig: &str) -> String {
        sig.split_once(')').expect("a method descriptor has parentheses").1.to_string()
    }

    /// (field name, java method name, java descriptor) for every entry in `JniBridge::new`'s
    /// lookup table, read back out of this file's own source
    fn lookup_table(source: &str) -> Vec<(String, String, String)> {
        let mut out = Vec::new();
        let mut rest = source;
        while let Some(at) = rest.find(":method(") {
            let field = rest[..at].rsplit(|c: char| !(c.is_alphanumeric() || c == '_')).next().unwrap().to_string();
            let after = &rest[at + ":method(".len()..];
            let mut quoted = after.split('"').skip(1).step_by(2);
            let name = quoted.next().expect("a lookup names a java method").to_string();
            let sig = quoted.next().expect("a lookup names a descriptor").to_string();
            // the string literals in this test's own source name no field
            if !field.is_empty() {
                out.push((field, name, sig));
            }
            rest = after;
        }
        out
    }

    fn arg_descriptor(arg: &str) -> &'static str {
        match arg {
            a if a.starts_with("Arg::Int(") => "I",
            a if a.starts_with("Arg::Long(") => "J",
            a if a.starts_with("Arg::Bool(") => "Z",
            a if a.starts_with("Arg::Str(") || a.starts_with("Arg::OptStr(") => "Ljava/lang/String;",
            a if a.starts_with("Arg::Strs(") => "[Ljava/lang/String;",
            a if a.starts_with("Arg::Bytes(") => "[B",
            other => panic!("unknown Arg variant in a call site: {other}"),
        }
    }

    fn return_descriptor(helper: &str) -> &'static str {
        match helper {
            "call_string" | "call_wire" | "call_refusal" => "Ljava/lang/String;",
            "call_void" => "V",
            "call_bool" => "Z",
            "call_int" => "I",
            "call_bytes" => "[B",
            other => panic!("unknown call helper: {other}"),
        }
    }

    /// splits an `Arg::A(x),Arg::B(y)` list at top level, since a variant's own payload may contain commas
    fn split_args(list: &str) -> Vec<String> {
        let mut out = Vec::new();
        let mut depth = 0usize;
        let mut current = String::new();
        for c in list.chars() {
            match c {
                '(' => {
                    depth += 1;
                    current.push(c)
                }
                ')' => {
                    depth -= 1;
                    current.push(c)
                }
                ',' if depth == 0 => {
                    out.push(std::mem::take(&mut current));
                }
                _ => current.push(c),
            }
        }
        if !current.is_empty() {
            out.push(current);
        }
        out
    }

    #[test]
    fn every_upcall_passes_what_its_java_descriptor_declares() {
        let source = squeeze(concat!(include_str!("exports.rs"), include_str!("bridge.rs"), include_str!("hosts.rs"),));
        let table = lookup_table(&source);
        assert_eq!(table.len(), 56, "the set of cached method ids changed");

        let mut called = std::collections::HashSet::new();
        let mut rest = source.as_str();
        while let Some(at) = rest.find("self.call_") {
            let after = &rest[at + "self.".len()..];
            rest = after;
            let helper: String = after.chars().take_while(|c| c.is_alphanumeric() || *c == '_').collect();
            let tail = &after[helper.len()..];
            // the `call_*("what", self.on_x, &[..])` shape only: a helper's own definition and the
            // `call_string` calls the other helpers make do not have a literal here
            let Some(tail) = tail.strip_prefix("(\"") else {
                continue;
            };
            let helper = helper.as_str();
            // only the `call_*(what, self.on_x, &[..])` shape; the helpers' own definitions and
            // `call_string` calls made from another helper do not match it
            let Some((_what, tail)) = tail.split_once("\",self.") else {
                continue;
            };
            let Some((field, tail)) = tail.split_once(",&[") else {
                continue;
            };
            let Some((list, _)) = tail.split_once(']') else {
                continue;
            };
            let Some((_, java_name, sig)) = table.iter().find(|(f, _, _)| f == field) else {
                continue;
            };

            let passed: Vec<&str> = split_args(list).iter().map(|a| arg_descriptor(a)).collect();
            assert_eq!(params(sig), passed, "{java_name}{sig} is not what {field}'s call site passes",);
            assert_eq!(returns(sig), return_descriptor(helper), "{java_name}{sig} does not return what {helper} reads",);
            called.insert(field.to_string());
        }

        let missing: Vec<_> = table
            .iter()
            .map(|(f, _, _)| f)
            .filter(|f| !called.contains(*f) && !NOT_CALLED_HERE.contains(&f.as_str()))
            .collect();
        assert!(missing.is_empty(), "declared but never called: {missing:?}");
    }
}

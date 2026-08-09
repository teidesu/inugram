use super::*;
use rquickjs::{Context, Runtime};

fn setup() -> (Runtime, Context, std::sync::Arc<crate::testing::harness::Logs>) {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let logs = crate::testing::harness::Logs::new();
    install_interrupt_handler(&rt, crate::testing::harness::log_sink(&logs));
    (rt, ctx, logs)
}

/// `Ok` == the script ran to completion, `Err` == it raised (interrupted scripts land here)
fn eval(ctx: &Context, code: &str) -> Result<(), String> {
    ctx.with(|ctx| {
        ctx.eval::<(), _>(code).map_err(|e| match e {
            rquickjs::Error::Exception => crate::api::telegram::rpc::format_exception(&ctx),
            other => other.to_string(),
        })
    })
}

#[test]
fn a_spinning_script_is_interrupted_and_logged_once() {
    let (_rt, ctx, logs) = setup();
    let armed = arm(50);

    let err = eval(&ctx, "while (true) {}").unwrap_err();

    assert!(err.contains("interrupted"), "unexpected error: {err}");
    assert!(armed.tripped());
    // `Logs` is a `Mutex`, and `assert_eq!` keeps both operands' temporaries alive across the arm
    // that formats the failure message: a second `borrow()` in there blocks the thread forever
    // instead of reporting, which is how a failing run gets left behind as a hung `cargo test`
    let logged = logs.borrow().clone();
    assert_eq!(logged.len(), 1, "got: {logged:?}");
    assert!(logged[0].contains("execution budget exceeded"), "got: {logged:?}");
}

#[test]
fn the_interrupt_survives_catch_and_finally() {
    let (_rt, ctx, _logs) = setup();
    let err = {
        let _armed = arm(50);
        eval(
            &ctx,
            r#"
            globalThis.__caught = false;
            try { while (true) {} }
            catch (e) { globalThis.__caught = true; }
            finally { while (true) {} }
            "#,
        )
        .unwrap_err()
    };

    assert!(err.contains("interrupted"), "unexpected error: {err}");
    let swallowed: bool = ctx.with(|ctx| ctx.eval("globalThis.__caught === true").unwrap());
    assert!(!swallowed, "an uncatchable interrupt must not reach a plugin's catch block");
}

#[test]
fn a_spinning_microtask_is_interrupted() {
    let (rt, ctx, logs) = setup();
    let armed = arm(50);
    let log = crate::testing::harness::log_sink(&logs);

    eval(&ctx, "Promise.resolve().then(() => { while (true) {} });").unwrap();
    crate::api::telegram::rpc::pump_jobs(&rt, &ctx, log.as_ref());

    assert!(armed.tripped());
}

#[test]
fn honest_work_within_the_real_entry_budget_is_never_interrupted() {
    let (rt, ctx, logs) = setup();
    let armed = arm_entry_deadline();
    let log: crate::Log = std::sync::Arc::new(|_| {});

    eval(
        &ctx,
        r#"
        let acc = 0;
        const parts = [];
        for (let i = 0; i < 300000; i++) {
            acc += i % 7;
            if (i % 1000 === 0) parts.push(JSON.stringify({ i, acc }));
        }
        globalThis.__out = JSON.parse(parts[parts.length - 1]).acc;
        Promise.resolve().then(() => { globalThis.__done = acc; });
        "#,
    )
    .unwrap();
    crate::api::telegram::rpc::pump_jobs(&rt, &ctx, log.as_ref());

    assert!(!armed.tripped());
    assert!(logs.borrow().is_empty(), "got: {:?}", logs.borrow());
    let done: bool = ctx.with(|ctx| ctx.eval("globalThis.__done > 0").unwrap());
    assert!(done);
}

/// The handler reads the deadline out of a thread-local, so whatever armed it and whatever polls it
/// have to be the same thread, and both directions of getting that wrong are silent. A slot read
/// off the *wrong* thread answers `None`, which the handler reports as "no ceiling" and a
/// `while (true) {}` then runs for as long as the process does; a slot *shared* between threads
/// cuts down a neighbour that never asked for a budget. Two engines side by side is what the suite
/// itself does under `--test-threads`, so the halves here run against each other rather than one
/// after the other: the neighbour's own work is timed to fall strictly inside the window where the
/// armed thread holds an already-expired deadline, which is the only window a shared slot is
/// visible in and is microseconds wide if it is left to chance. Results come back over a channel
/// with a timeout, so a regression fails this test rather than wedging the run that found it.
#[test]
fn one_thread_being_armed_neither_arms_nor_disarms_another() {
    use std::sync::mpsc;
    use std::sync::{Arc, Barrier};

    // 1: both engines up. 2: the armed thread has tripped and still holds its deadline. 3: the
    // neighbour is done with it
    let phase = Arc::new(Barrier::new(2));
    let (report, results) = mpsc::channel::<(&'static str, Result<(), String>)>();

    let armed_side = {
        let (phase, report) = (phase.clone(), report.clone());
        std::thread::spawn(move || {
            let (_rt, ctx, _logs) = setup();
            let armed = arm(50);
            phase.wait();
            let outcome = match eval(&ctx, "while (true) {}") {
                Ok(()) => Err("a spinning script on an armed thread ran to completion".to_string()),
                Err(e) if !e.contains("interrupted") => Err(format!("unexpected error: {e}")),
                Err(_) if !armed.tripped() => Err("the armed thread's own deadline never tripped".to_string()),
                Err(_) => Ok(()),
            };
            phase.wait();
            phase.wait();
            drop(armed);
            let _ = report.send(("armed", outcome));
        })
    };

    let unarmed_side = std::thread::spawn(move || {
        let (_rt, ctx, logs) = setup();
        phase.wait();
        // alongside the spin, then again while the expired deadline is still held
        let bounded = "(function () { let n = 0; for (let i = 0; i < 2000000; i++) n += i % 7; })();";
        let mut outcome = eval(&ctx, bounded);
        phase.wait();
        outcome = outcome.and_then(|()| eval(&ctx, bounded));
        let outcome = match outcome {
            Err(e) => Err(format!("an unarmed thread was interrupted: {e}")),
            Ok(()) => {
                let logged = logs.borrow().clone();
                if logged.is_empty() {
                    Ok(())
                } else {
                    Err(format!("an unarmed thread was reported against: {logged:?}"))
                }
            }
        };
        phase.wait();
        let _ = report.send(("unarmed", outcome));
    });

    for _ in 0..2 {
        let (side, outcome) = results
            .recv_timeout(Duration::from_secs(30))
            .expect("a deadline armed on one thread was invisible to it, or visible to the other");
        outcome.unwrap_or_else(|e| panic!("{side} side: {e}"));
    }
    armed_side.join().unwrap();
    unarmed_side.join().unwrap();
}

#[test]
fn an_unarmed_thread_is_never_interrupted() {
    let (_rt, ctx, logs) = setup();
    eval(&ctx, "let n = 0; for (let i = 0; i < 200000; i++) n += i;").unwrap();
    assert!(logs.borrow().is_empty(), "got: {:?}", logs.borrow());
}

#[test]
fn a_tripped_deadline_does_not_leak_into_the_next_entry() {
    let (_rt, ctx, _logs) = setup();
    {
        let _armed = arm(50);
        assert!(eval(&ctx, "while (true) {}").is_err());
    }
    let armed = arm_entry_deadline();
    let sum: i32 = ctx.with(|ctx| ctx.eval("let n = 0; for (let i = 0; i < 1000; i++) n += i; n").unwrap());
    assert_eq!(sum, 499500);
    assert!(!armed.tripped());
}

#[cfg(test)]
mod memory_tests {
    use super::*;
    use rquickjs::class::{JsClass, Readable, Trace, Tracer};
    use rquickjs::function::Constructor;
    use rquickjs::{Class, Context, FromJs, JsLifetime, Runtime};

    /// a native-backed js object exactly as 0.4's `Blob`/`OffscreenCanvas` will be one: a handle
    /// whose `Drop` (the class finalizer, or a collected cycle) hands the bytes back
    struct Surface {
        _charge: ExternalCharge,
    }

    impl<'js> Trace<'js> for Surface {
        fn trace<'a>(&self, _tracer: Tracer<'a, 'js>) {}
    }

    unsafe impl<'js> JsLifetime<'js> for Surface {
        type Changed<'to> = Surface;
    }

    impl<'js> JsClass<'js> for Surface {
        const NAME: &'static str = "Surface";
        type Mutable = Readable;

        fn constructor(_ctx: &Ctx<'js>) -> JsResult<Option<Constructor<'js>>> {
            Ok(None)
        }
    }

    fn setup() -> (Runtime, Context) {
        let rt = Runtime::new().unwrap();
        let ctx = Context::full(&rt).unwrap();
        ctx.with(|ctx| {
            crate::api::error::install_plugin_error(&ctx, &crate::testing::harness::inu_namespace(&ctx)).unwrap()
        });
        (rt, ctx)
    }

    /// `Err` carries what the host would report for the failure
    fn eval(ctx: &Context, code: &str) -> Result<String, String> {
        ctx.with(|ctx| match ctx.eval::<Value, _>(code) {
            Ok(value) => Ok(rquickjs::Coerced::<String>::from_js(&ctx, value).map(|c| c.0).unwrap_or_default()),
            Err(rquickjs::Error::Exception) => Err(crate::api::telegram::rpc::format_exception(&ctx)),
            Err(e) => Err(e.to_string()),
        })
    }

    const GROW_UNTIL_REFUSED: &str = "(function () {
        const held = [];
        for (let i = 0; i < 1e7; i++) held.push({ index: i, tag: 'x' + i });
        return 'never';
    })()";

    #[test]
    fn the_heap_ceiling_is_applied_to_the_runtime() {
        let (rt, _ctx) = setup();
        apply_heap_limit(&rt);
        assert_eq!(rt.memory_usage().malloc_limit, HEAP_LIMIT_BYTES as i64);
    }

    #[test]
    fn a_runaway_allocation_is_refused_and_the_engine_survives() {
        let (rt, ctx) = setup();
        let baseline = rt.memory_usage().malloc_size as usize;
        rt.set_memory_limit(baseline + 4 * 1024 * 1024);

        // a gradually grown heap is at its ceiling when it trips, so quickjs has no room to build
        // the error and throws bare `null`; the host still names the ceiling
        let err = eval(&ctx, GROW_UNTIL_REFUSED).unwrap_err();
        assert!(err.contains("heap ceiling of 32 MB"), "got: {err}");

        // the dead turn's objects are gone with it, so the engine is not left pinned at its ceiling
        assert!(
            rt.memory_usage().malloc_size as usize <= baseline + 512 * 1024,
            "the refused turn leaked: {} bytes live, baseline {baseline}",
            rt.memory_usage().malloc_size,
        );
        assert_eq!(eval(&ctx, "JSON.stringify({ ok: [1, 2, 3] })").unwrap(), r#"{"ok":[1,2,3]}"#,);
        // and a second runaway is refused the same way, not fatally
        assert!(eval(&ctx, GROW_UNTIL_REFUSED).is_err());
        assert_eq!(eval(&ctx, "String(6 * 7)").unwrap(), "42");
    }

    #[test]
    fn one_oversized_request_is_refused_by_name() {
        let (rt, ctx) = setup();
        rt.set_memory_limit(rt.memory_usage().malloc_size as usize + 4 * 1024 * 1024);
        // large enough that quickjs refuses it outright, so it still has room to build the error
        let err = eval(&ctx, "'x'.repeat(1e9)").unwrap_err();
        assert!(err.starts_with("quota-exceeded:"), "got: {err}");
    }

    #[test]
    fn an_ordinary_failure_is_not_reported_as_a_quota() {
        let (rt, ctx) = setup();
        apply_heap_limit(&rt);
        let err = eval(&ctx, "throw new Error('boom')").unwrap_err();
        assert!(err.starts_with("Error: boom"), "got: {err}");
        let err = eval(&ctx, "throw new RangeError('nope')").unwrap_err();
        assert!(err.starts_with("RangeError: nope"), "got: {err}");
        let err = eval(&ctx, "throw new inu.PluginError('forbidden', 'nope')").unwrap_err();
        assert!(err.starts_with("PluginError: nope"), "got: {err}");
    }

    #[test]
    fn an_ordinary_workload_never_reaches_either_ceiling() {
        let (rt, ctx) = setup();
        apply_heap_limit(&rt);
        let out = eval(
            &ctx,
            r#"(function () {
                const dialogs = [];
                for (let i = 0; i < 5000; i++) {
                    dialogs.push({ id: i, title: 'chat ' + i, unread: i % 7, draft: 'x'.repeat(64) });
                }
                const encoded = JSON.stringify(dialogs);
                return String(JSON.parse(encoded).length) + ':' + String(encoded.length > 0);
            })()"#,
        )
        .unwrap();
        assert_eq!(out, "5000:true");
        assert!(
            (rt.memory_usage().malloc_size as usize) < HEAP_LIMIT_BYTES / 4,
            "an ordinary workload should sit nowhere near the ceiling: {}",
            rt.memory_usage().malloc_size,
        );
    }

    #[test]
    fn a_charge_holds_its_bytes_until_it_is_dropped() {
        let (_rt, ctx) = setup();
        let external = ExternalMemory::new();
        ctx.with(|ctx| {
            let charge = external.charge(&ctx, 4 * 1024 * 1024).unwrap();
            assert_eq!(external.charged_bytes(), 4 * 1024 * 1024);
            assert_eq!(charge.bytes(), 4 * 1024 * 1024);
            drop(charge);
            assert_eq!(external.charged_bytes(), 0);
        });
    }

    #[test]
    fn charging_past_the_native_budget_throws_quota_exceeded_and_reserves_nothing() {
        let (_rt, ctx) = setup();
        let external = ExternalMemory::new();
        ctx.with(|ctx| {
            let held = external.charge(&ctx, EXTERNAL_LIMIT_BYTES - 1024).unwrap();
            let Err(err) = external.charge(&ctx, 4 * 1024 * 1024) else {
                panic!("a charge past the budget must be refused");
            };
            assert!(matches!(err, rquickjs::Error::Exception));
            ctx.globals().set("e", ctx.catch()).unwrap();
            let got: String = ctx.eval("[e instanceof inu.PluginError, e.code, e.usage, e.quota].join('|')").unwrap();
            assert_eq!(
                got,
                format!(
                    "true|quota-exceeded|{}|{}",
                    EXTERNAL_LIMIT_BYTES - 1024 + 4 * 1024 * 1024,
                    EXTERNAL_LIMIT_BYTES,
                ),
            );
            assert_eq!(external.charged_bytes(), EXTERNAL_LIMIT_BYTES - 1024);
            drop(held);
            assert!(external.charge(&ctx, 4 * 1024 * 1024).is_ok());
        });
    }

    /// a buffer charging for its capacity as it grows: the reservation has to stay one number, and
    /// a refused growth must leave it exactly where it was
    #[test]
    fn a_charge_grows_in_place_and_a_refused_growth_changes_nothing() {
        let (_rt, ctx) = setup();
        let external = ExternalMemory::new();
        ctx.with(|ctx| {
            let mut charge = external.charge(&ctx, 1024).unwrap();
            assert!(charge.try_grow(&ctx, 4 * 1024 * 1024));
            assert_eq!(charge.bytes(), 4 * 1024 * 1024 + 1024);
            assert_eq!(external.charged_bytes(), 4 * 1024 * 1024 + 1024);

            assert!(!charge.try_grow(&ctx, EXTERNAL_LIMIT_BYTES));
            assert_eq!(charge.bytes(), 4 * 1024 * 1024 + 1024);
            assert_eq!(external.charged_bytes(), 4 * 1024 * 1024 + 1024);

            charge.shrink_to(512);
            assert_eq!(external.charged_bytes(), 512);
            // shrinking to more than is held is not a way to charge without asking
            charge.shrink_to(EXTERNAL_LIMIT_BYTES);
            assert_eq!(external.charged_bytes(), 512);
            drop(charge);
            assert_eq!(external.charged_bytes(), 0);
        });
    }

    /// the routing form: `Blob` asks with somewhere else to put the bytes, so a refusal must be a
    /// plain `None` with nothing thrown and nothing reserved
    #[test]
    fn try_charge_refuses_without_throwing_and_reserves_nothing() {
        let (_rt, ctx) = setup();
        let external = ExternalMemory::new();
        ctx.with(|ctx| {
            let held = external.try_charge(&ctx, EXTERNAL_LIMIT_BYTES - 1024).unwrap();
            assert!(external.try_charge(&ctx, 4 * 1024 * 1024).is_none());
            assert!(!ctx.has_exception(), "a refusal must not leave an exception pending");
            assert_eq!(external.charged_bytes(), EXTERNAL_LIMIT_BYTES - 1024);
            drop(held);
            assert!(external.try_charge(&ctx, 4 * 1024 * 1024).is_some());
        });
    }

    #[test]
    fn a_cycle_holding_a_charge_is_collected_before_the_budget_refuses() {
        let (_rt, ctx) = setup();
        let external = ExternalMemory::new();
        ctx.with(|ctx| {
            let surface = Class::instance(
                ctx.clone(),
                Surface { _charge: external.charge(&ctx, EXTERNAL_LIMIT_BYTES / 2).unwrap() },
            )
            .unwrap();
            // the case refcounting alone never frees: a cycle whose js side is a few dozen bytes,
            // so nothing about the js heap gives quickjs a reason to sweep it
            ctx.globals().set("held", surface).unwrap();
            ctx.eval::<(), _>("globalThis.cycle = { surface: held }; cycle.self = cycle; held = null;").unwrap();
            assert_eq!(external.charged_bytes(), EXTERNAL_LIMIT_BYTES / 2);

            ctx.eval::<(), _>("globalThis.cycle = null;").unwrap();
            assert_eq!(
                external.charged_bytes(),
                EXTERNAL_LIMIT_BYTES / 2,
                "an unreachable cycle is not freed by dropping the last reference to it",
            );

            let next = external.charge(&ctx, EXTERNAL_LIMIT_BYTES / 2 + 1024).unwrap();
            assert_eq!(next.bytes(), EXTERNAL_LIMIT_BYTES / 2 + 1024);
            assert_eq!(external.charged_bytes(), EXTERNAL_LIMIT_BYTES / 2 + 1024);
        });
    }
}

/// Every JNI entry point has to arm a deadline before it can reach plugin code, and nothing else
/// can check that: the exports are `extern "system"` fns taking a live `JNIEnv`, so no test can
/// call one. Without this, deleting the arm from `nativeDispatchRpc` regresses the engine to an
/// uninterruptible wedge with the whole suite still green.
#[cfg(test)]
mod entry_points {
    /// exempt: `nativeCreate`/`nativeDestroy` run before the context exists or after it is gone,
    /// `nativeXposedBudgetMs` answers a constant, and the `PluginXposed$Native` block is lsplant's
    /// own interface - it rewrites an ART entry point and never enters an engine, the dispatch that
    /// does being `nativeXposedBefore`/`nativeXposedAfter`.
    const UNBOUNDED: [&str; 9] = [
        "QuickJs_nativeCreate",
        "QuickJs_nativeDestroy",
        "QuickJs_nativeXposedBudgetMs",
        "platform_PluginXposed_00024Native_nativeInit",
        "platform_PluginXposed_00024Native_nativeHook",
        "platform_PluginXposed_00024Native_nativeUnhook",
        "platform_PluginXposed_00024Native_nativeIsHooked",
        "platform_PluginXposed_00024Native_nativeDeoptimize",
        "platform_PluginXposed_00024Native_nativeMakeInheritable",
    ];

    #[test]
    fn every_jni_export_arms_a_deadline() {
        let source = include_str!("../jni/exports.rs");
        let mut checked = 0;
        // every export in the package, not only `QuickJs`'s: a new class of entry points is
        // exactly the case a prefix pinned to one class would walk straight past
        const PREFIX: &str = "pub extern \"system\" fn Java_desu_inugram_helpers_plugins_";
        for (index, _) in source.match_indices(PREFIX) {
            let name = source[index + PREFIX.len()..]
                .split('(')
                .next()
                .unwrap_or_default()
                // an export with a lifetime on it is `name<'local>`, and the exempt list names
                // functions rather than signatures
                .split('<')
                .next()
                .unwrap_or_default()
                .trim()
                .to_string();
            if UNBOUNDED.contains(&name.as_str()) {
                continue;
            }
            let body = &source[index..];
            let end = body.find("\n}\n").map(|at| at + 3).unwrap_or(body.len());
            assert!(
                body[..end].contains("arm_entry_deadline()") || body[..end].contains("arm_eval_deadline()"),
                "JNI export '{name}' reaches plugin code without arming a deadline",
            );
            checked += 1;
        }
        assert_eq!(checked, 13, "hand-written export count changed; confirm each new one arms a deadline");

        // the rest are generated, so the deadline belongs to `engine_export!` rather than to each
        // export. That is only true of the ones whose rule arms it, so every rule has to: a fifth
        // one added without the line would expand into exports this scan can no longer see.
        let at = source.find("macro_rules! engine_export {").expect("engine_export! moved");
        let body = &source[at..];
        let body = &body[..body.find("\n}\n").expect("engine_export! has no end")];
        let rules = body.matches(") => {").count();
        assert!(rules > 0, "engine_export! parsed as having no rules");
        assert_eq!(
            body.matches("arm_entry_deadline()").count(),
            rules,
            "an engine_export! rule expands without arming a deadline",
        );

        let generated = source.matches("\nengine_export!(").count();
        assert_eq!(checked + generated, 42, "export count changed; confirm each new one arms a deadline");
    }
}

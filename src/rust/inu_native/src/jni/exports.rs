//! The `extern "system"` entry points, one per `QuickJs` native method.

use jni::objects::{JObject, JObjectArray, JString};
use jni::strings::JNIString;
use jni::sys::{jboolean, jint, jlong, jobject, jstring};
use jni::EnvUnowned;
use rquickjs::{Coerced, Context, Persistent, Runtime, Value};
use std::rc::Rc;
use std::sync::Arc;

use super::bridge::JniBridge;
use super::env::{in_env, jstring_to_string, read_header, read_string_array};
use super::info::{install_inu, InuInfo};
use super::log::{install_console, make_log};
use super::{pump, Engine};
use crate::api::ApiHost;
use crate::draw::canvas::CanvasHost;
use crate::engine::error::GrantHost;
use crate::engine::globals::RandomHost;
use crate::engine::registry::Lifecycle;
use crate::engine::timers::TimerHost;
use crate::io::fetch::FetchHost;
use crate::platform::jvm::JvmHost;
use crate::platform::notifications::NotificationHost;
use crate::platform::xposed::XposedHost;
use crate::tg::account::AccountHost;
use crate::tg::deserialize::DeserializeHost;
use crate::tg::reads::ReadsHost;
use crate::tg::rpc::{format_exception, RpcHost};
use crate::tg::writes::WritesHost;
use crate::ui::actions::ActionHost;
use crate::ui::icons::IconHost;
use crate::ui::pages::UiHost;
use crate::ui::screens::ScreenHost;

/// One JNI export that reaches into an installed api.
///
/// Every one of these arms the entry deadline, turns `ptr` back into an [`Engine`] and takes the
/// sub-state, answering nothing when either is gone. Spelling that out per export is how one of them
/// comes to be missing the deadline, which is the only thing that can stop a runaway plugin - so the
/// prologue is generated rather than repeated, and `$call` is what an export actually says.
macro_rules! engine_export {
    // reads java strings, so it runs inside an `Env`
    (
        $(#[$meta:meta])*
        $name:ident, $field:ident,
        ($($param:ident: $ty:ty),* $(,)?),
        strings($($str:ident),* $(,)?),
        |$engine:ident, $state:ident| $call:expr $(,)?
    ) => {
        $(#[$meta])*
        #[no_mangle]
        pub extern "system" fn $name(mut env: EnvUnowned, _this: JObject, ptr: jlong, $($param: $ty),*) {
            in_env(&mut env, (), |env| {
                let _deadline = crate::engine::deadline::arm_entry_deadline();
                let Some($engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else {
                    return;
                };
                let Some($state) = $engine.$field.as_ref() else {
                    return;
                };
                $(let $str = jstring_to_string(env, &$str);)*
                $call;
            })
        }
    };
    // a null string is a value here rather than a failure, so it stays an `Option`
    (
        $(#[$meta:meta])*
        $name:ident, $field:ident,
        ($($param:ident: $ty:ty),* $(,)?),
        opt_strings($($str:ident),* $(,)?),
        |$engine:ident, $state:ident| $call:expr $(,)?
    ) => {
        $(#[$meta])*
        #[no_mangle]
        pub extern "system" fn $name(mut env: EnvUnowned, _this: JObject, ptr: jlong, $($param: $ty),*) {
            in_env(&mut env, (), |env| {
                let _deadline = crate::engine::deadline::arm_entry_deadline();
                let Some($engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else {
                    return;
                };
                let Some($state) = $engine.$field.as_ref() else {
                    return;
                };
                $(let $str = if $str.is_null() { None } else { Some(jstring_to_string(env, &$str)) };)*
                $call;
            })
        }
    };
    // nothing to read, so no `Env` is taken at all
    (
        $(#[$meta:meta])*
        $name:ident, $field:ident,
        ($($param:ident: $ty:ty),* $(,)?),
        |$engine:ident, $state:ident| $call:expr $(,)?
    ) => {
        $(#[$meta])*
        #[no_mangle]
        pub extern "system" fn $name(_env: EnvUnowned, _this: JObject, ptr: jlong, $($param: $ty),*) {
            let _deadline = crate::engine::deadline::arm_entry_deadline();
            let Some($engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else {
                return;
            };
            let Some($state) = $engine.$field.as_ref() else {
                return;
            };
            $call;
        }
    };
    // answers the host a string, or null for "the engine could not"
    (
        $(#[$meta:meta])*
        $name:ident, $field:ident,
        ($($param:ident: $ty:ty),* $(,)?),
        strings($($str:ident),* $(,)?),
        -> jstring |$engine:ident, $state:ident| $call:expr $(,)?
    ) => {
        $(#[$meta])*
        #[no_mangle]
        pub extern "system" fn $name(mut env: EnvUnowned, _this: JObject, ptr: jlong, $($param: $ty),*) -> jstring {
            in_env(&mut env, std::ptr::null_mut(), |env| {
                let _deadline = crate::engine::deadline::arm_entry_deadline();
                let Some($engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else {
                    return std::ptr::null_mut();
                };
                let Some($state) = $engine.$field.as_ref() else {
                    return std::ptr::null_mut();
                };
                $(let $str = jstring_to_string(env, &$str);)*
                match $call {
                    Some(json) => env.new_string(json).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut()),
                    None => std::ptr::null_mut(),
                }
            })
        }
    };
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeCreate(
    mut env: EnvUnowned,
    _this: JObject,
    listener: JObject,
) -> jlong {
    in_env(&mut env, 0, |env| {
        // every upcall goes to the listener, never to the `QuickJs` that owns this pointer: the
        // method ids are cached off one class here, and `PluginBridge` is the only stable one
        let Some(bridge) = JniBridge::new(env, &listener) else {
            return 0;
        };

        let Ok(rt) = Runtime::new() else { return 0 };
        let Ok(ctx) = Context::full(&rt) else {
            return 0;
        };

        let installed = ctx.with(|ctx| {
            install_console(&ctx, bridge.clone())?;
            crate::engine::error::install_plugin_error(&ctx)
        });
        if installed.is_err() {
            return 0;
        }

        // set outside ctx.with(): both setters lock the runtime, which ctx.with() also holds
        crate::tg::rpc::install_rejection_tracker(&rt, make_log(bridge.console.clone()));
        // not a fault: `common.d.ts` promises "only the turn dies: timers, registrations and everything
        // else the plugin set up are still there, and the next callback starts with a full budget", and
        // a turn parked in a slow host call is charged the budget without having misbehaved
        let interrupt_log = make_log(bridge.console.clone());
        crate::engine::deadline::install_interrupt_handler(&rt, Arc::new(move |msg: &str| interrupt_log(msg)));
        crate::engine::deadline::apply_heap_limit(&rt);

        let views = crate::tl::proxy::TlViews::new(bridge.clone());
        let engine = Box::new(Engine {
            ctx,
            _rt: rt,
            bridge,
            lifecycle: Lifecycle::new(),
            views,
            blobs: None,
            shared: None,
            rpc: None,
            deserialize: None,
            api: None,
            ui: None,
            screens: None,
            actions: None,
            account: None,
            reads: None,
            writes: None,
            fs: None,
            fetch: None,
            canvas: None,
            timers: None,
            notifications: None,
            jvm: None,
            xposed: None,
        });
        Box::into_raw(engine) as jlong
    })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeInstallApi(
    mut env: EnvUnowned,
    _this: JObject,
    ptr: jlong,
    spill_dir: JString,
) {
    in_env(&mut env, (), |env| {
        let _deadline = crate::engine::deadline::arm_entry_deadline();
        let spill_dir = jstring_to_string(env, &spill_dir);
        let Some(engine) = (unsafe { (ptr as *mut Engine).as_mut() }) else {
            return;
        };
        let host: Rc<dyn ApiHost> = engine.bridge.clone();
        let grants: Rc<dyn GrantHost> = engine.bridge.clone();
        let log = make_log(engine.bridge.console.clone());

        let lifecycle = engine.lifecycle.clone();

        let installed =
            engine.ctx.with(|ctx| crate::api::install_api(&ctx, host, grants.clone(), lifecycle.clone(), log.clone()));
        match installed {
            Ok(state) => engine.api = Some(state),
            Err(e) => log(&format!("inu.kv/inu.ui failed to install: {e:?}")),
        }
        // before the rpc install, which hands every dispatch the account it arrived on
        let account_host: Rc<dyn AccountHost> = engine.bridge.clone();
        let installed = engine.ctx.with(|ctx| {
            crate::tg::account::install_account(&ctx, account_host, grants, lifecycle.clone(), log.clone())
        });
        match installed {
            Ok(state) => engine.account = Some(state),
            Err(e) => log(&format!("inu.account failed to install: {e:?}")),
        }
        let ui_host: Rc<dyn UiHost> = engine.bridge.clone();
        let installed = engine.ctx.with(|ctx| {
            crate::ui::pages::install_ui(&ctx, ui_host, lifecycle.clone(), log.clone(), engine.jvm.clone())
        });
        match installed {
            Ok(state) => engine.ui = Some(state),
            Err(e) => log(&format!("inu.ui pages failed to install: {e:?}")),
        }
        // before anything that takes a `UIIcon` in its options: an element built by top-level plugin
        // code would otherwise be refused an icon that `common.d.ts` says it can have
        let icon_host: Rc<dyn IconHost> = engine.bridge.clone();
        if let Err(e) = engine.ctx.with(|ctx| crate::ui::icons::install_icons(&ctx, icon_host)) {
            log(&format!("inu.icons failed to install: {e:?}"));
        }
        // after the account install: every action context carries the account its surface belongs to
        let action_host: Rc<dyn ActionHost> = engine.bridge.clone();
        let accounts = engine.account.clone();
        let action_grants: Rc<dyn GrantHost> = engine.bridge.clone();
        let installed = engine.ctx.with(|ctx| {
            crate::ui::actions::install_actions(
                &ctx,
                action_host,
                lifecycle.clone(),
                accounts,
                action_grants,
                log.clone(),
            )
        });
        match installed {
            Ok(state) => engine.actions = Some(state),
            Err(e) => log(&format!("inu.register*Action failed to install: {e:?}")),
        }
        // after the accounts, whose handles every `CurrentScreen` carries one of
        let screen_host: Rc<dyn ScreenHost> = engine.bridge.clone();
        let screen_grants: Rc<dyn GrantHost> = engine.bridge.clone();
        let accounts = engine.account.clone();
        let installed = engine.ctx.with(|ctx| {
            crate::ui::screens::install_screens(
                &ctx,
                screen_host,
                screen_grants,
                accounts,
                lifecycle.clone(),
                log.clone(),
            )
        });
        match installed {
            Ok(state) => engine.screens = Some(state),
            Err(e) => log(&format!("inu.ui navigation failed to install: {e:?}")),
        }
        // the app's own event bus, and the last thing on `inu.android` - nothing else here depends on
        // it, and it depends on nothing but the grant gate
        let notification_host: Rc<dyn NotificationHost> = engine.bridge.clone();
        let notification_grants: Rc<dyn GrantHost> = engine.bridge.clone();
        let installed = engine.ctx.with(|ctx| {
            crate::platform::notifications::install_notifications(
                &ctx,
                notification_host,
                notification_grants,
                lifecycle.clone(),
                log.clone(),
            )
        });
        match installed {
            Ok(state) => engine.notifications = Some(state),
            Err(e) => log(&format!("inu.android.addNotificationCenterDelegate failed to install: {e:?}")),
        }
        let random_host: Rc<dyn RandomHost> = engine.bridge.clone();
        let spill_dir = std::path::PathBuf::from(spill_dir);
        // one counter for everything native-backed this engine holds, per `crate::engine::deadline::ExternalMemory`:
        // a blob's bytes and a canvas's bitmap come out of the same 64 MB
        let external = crate::engine::deadline::ExternalMemory::new();
        let blobs = match engine
            .ctx
            .with(|ctx| crate::engine::globals::install_globals(&ctx, random_host, &spill_dir, external.clone()))
        {
            Ok(blobs) => Some(blobs),
            Err(e) => {
                log(&format!("sandbox globals failed to install: {e:?}"));
                None
            }
        };
        engine.blobs = blobs.clone();
        // after the blob table, which is what `convertToBlob` answers with and what `decode` may be
        // handed, and before `inu.fs`, which hands it the scoped root `{ path }` resolves against
        if let Some(blobs) = blobs.clone() {
            let canvas_host: Rc<dyn CanvasHost> = engine.bridge.clone();
            let installed = engine.ctx.with(|ctx| {
                crate::draw::canvas::install_canvas(
                    &ctx,
                    canvas_host,
                    blobs,
                    external.clone(),
                    spill_dir.clone(),
                    log.clone(),
                )
            });
            match installed {
                Ok(state) => engine.canvas = Some(state),
                Err(e) => log(&format!("inu.canvas failed to install: {e:?}")),
            }
        }
        // pure surface, no host behind either of them; `inu.Message` takes the peer helpers `inu.utils`
        // installs, so the two go in together - and the `Account` read and write surfaces take both,
        // the helpers to normalize a peer with and `inu.Message` to wrap what they answer
        let reads_host: Rc<dyn ReadsHost> = engine.bridge.clone();
        let writes_host: Rc<dyn WritesHost> = engine.bridge.clone();
        let grants: Rc<dyn GrantHost> = engine.bridge.clone();
        let views = engine.views.clone();
        let accounts = engine.account.clone();
        type Surfaces = (Option<Rc<crate::tg::reads::ReadsState>>, Option<Rc<crate::tg::writes::WritesState>>);
        let mut shared_root = None;
        let installed = engine.ctx.with(|ctx| -> rquickjs::Result<Surfaces> {
            let shared = crate::tl::utils::install_utils(&ctx)?;
            crate::tl::message::install_message(&ctx, &shared)?;
            // `installRpc` is a later JNI call and `sendmsg.js` takes the same helpers
            shared_root = Some(Persistent::save(&ctx, shared.clone()));
            let Some(accounts) = accounts.as_ref() else {
                return Ok((None, None));
            };
            let reads = crate::tg::reads::install_reads(
                &ctx,
                reads_host,
                grants.clone(),
                views.clone(),
                &shared,
                accounts,
                log.clone(),
            )?;
            // after the reads, whose prototype it chains behind its own; and only with a blob table,
            // since a `sendMedia` that cannot read a `Blob` is not the api `common.d.ts` describes
            let Some(blobs) = blobs else {
                return Ok((Some(reads), None));
            };
            let deps = crate::tg::writes::WritesDeps {
                host: writes_host,
                grants,
                views,
                blobs,
                stage_dir: spill_dir.clone(),
                log: log.clone(),
            };
            let writes = crate::tg::writes::install_writes(&ctx, deps, &shared, accounts)?;
            Ok((Some(reads), Some(writes)))
        });
        engine.shared = shared_root;
        match installed {
            Ok((reads, writes)) => {
                engine.reads = reads;
                engine.writes = writes;
            }
            Err(e) => log(&format!("inu.utils/inu.Message/Account reads+writes failed to install: {e:?}")),
        }
        let timer_host: Rc<dyn TimerHost> = engine.bridge.clone();
        let installed =
            engine.ctx.with(|ctx| crate::engine::timers::install_timers(&ctx, timer_host, lifecycle, log.clone()));
        match installed {
            Ok(state) => engine.timers = Some(state),
            Err(e) => log(&format!("timers failed to install: {e:?}")),
        }
        // after the timers, whose `setTimeout` its prelude captures to measure `timeout` on, and only
        // with a blob table, since a response body reaches js as a `Blob` over the file the host wrote
        let fetch_host: Rc<dyn FetchHost> = engine.bridge.clone();
        let grants: Rc<dyn GrantHost> = engine.bridge.clone();
        if let Some(blobs) = engine.blobs.clone() {
            let installed =
                engine.ctx.with(|ctx| crate::io::fetch::install_fetch(&ctx, fetch_host, grants, blobs, log.clone()));
            match installed {
                Ok(state) => engine.fetch = Some(state),
                Err(e) => log(&format!("fetch failed to install: {e:?}")),
            }
        }
        pump(engine);
    })
}

/// installs `inu.jvm`. Its own JNI call because the host only makes it for a plugin that holds
/// `unsafe.jvm` at all: the surface is the whole app, so an engine nobody granted it does not carry
/// the bindings for it either.
#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeInstallJvm(
    _env: EnvUnowned,
    _this: JObject,
    ptr: jlong,
) {
    let _deadline = crate::engine::deadline::arm_entry_deadline();
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_mut() }) else {
        return;
    };
    let host: Rc<dyn JvmHost> = engine.bridge.clone();
    let grants: Rc<dyn GrantHost> = engine.bridge.clone();
    let log = make_log(engine.bridge.console.clone());
    let lifecycle = engine.lifecycle.clone();
    let installed =
        engine.ctx.with(|ctx| crate::platform::jvm::install_jvm(&ctx, host, grants, lifecycle, log.clone()));
    match installed {
        Ok(state) => engine.jvm = Some(state),
        Err(e) => log(&format!("inu.jvm failed to install: {e:?}")),
    }
}

/// installs `inu.xposed`. Its own JNI call for `inu.jvm`'s reason, and it must run after it: every
/// entry point takes a `JavaMethod`, which is a handle in that api's table, and the values a hook is
/// handed cross on that api's wire.
#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeInstallXposed(
    _env: EnvUnowned,
    _this: JObject,
    ptr: jlong,
) {
    let _deadline = crate::engine::deadline::arm_entry_deadline();
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_mut() }) else {
        return;
    };
    let log = make_log(engine.bridge.console.clone());
    let Some(jvm) = engine.jvm.clone() else {
        log("inu.xposed needs inu.jvm, which is not installed");
        return;
    };
    let host: Rc<dyn XposedHost> = engine.bridge.clone();
    let grants: Rc<dyn GrantHost> = engine.bridge.clone();
    let lifecycle = engine.lifecycle.clone();
    let installed =
        engine.ctx.with(|ctx| crate::platform::xposed::install_xposed(&ctx, host, grants, lifecycle, jvm, log.clone()));
    match installed {
        Ok(state) => engine.xposed = Some(state),
        Err(e) => log(&format!("inu.xposed failed to install: {e:?}")),
    }
}

/// The `before` half of a hooked method's dispatch, on `globalQueue` with the calling thread parked
/// on it - never on the calling thread itself, which would race every `globalQueue`-confined map in
/// the bridge and abort outright when the hooked method is one plugin code reached.
///
/// Answers the `String[]` `crate::platform::xposed::dispatch_before` describes: the host reads it positionally and
/// calls the original itself, so a method that may only run on the ui thread still does.
#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeXposedBefore<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    ptr: jlong,
    dispatch_id: jlong,
    site: jlong,
    method_wire: JString<'local>,
    this_wire: JString<'local>,
    args: JObjectArray<'local, JString<'local>>,
) -> jobject {
    in_env(&mut env, std::ptr::null_mut(), |env| {
        let _deadline = crate::engine::deadline::arm_entry_deadline();
        let method_wire = jstring_to_string(env, &method_wire);
        let this_wire = jstring_to_string(env, &this_wire);
        let args = read_string_array(env, &args);
        let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else {
            return std::ptr::null_mut();
        };
        let answer = match engine.xposed.as_ref() {
            Some(state) => crate::platform::xposed::dispatch_before(
                &engine._rt,
                &engine.ctx,
                state,
                dispatch_id,
                site,
                &crate::platform::xposed::Invocation { method: &method_wire, this: &this_wire, args: &args },
            ),
            // no api installed is the same answer as no hook left on the site: run what the app called
            None => std::iter::once("P0".to_string()).chain(args.iter().cloned()).collect(),
        };
        match engine.bridge.new_jstring_array(env, "xposedBefore", &answer) {
            Ok(array) => array.unwrap().into_raw(),
            Err(e) => {
                make_log(engine.bridge.console.clone())(&e);
                std::ptr::null_mut()
            }
        }
    })
}

/// The `after` half, owed to every dispatch the `before` phase answered `P1`. `result` is what the
/// original answered, `T`-prefixed when it threw.
#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeXposedAfter<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    ptr: jlong,
    dispatch_id: jlong,
    result: JString<'local>,
) -> jstring {
    in_env(&mut env, std::ptr::null_mut(), |env| {
        let _deadline = crate::engine::deadline::arm_entry_deadline();
        let result = jstring_to_string(env, &result);
        let answer = match unsafe { (ptr as *mut Engine).as_ref() } {
            Some(engine) => match engine.xposed.as_ref() {
                Some(state) => {
                    crate::platform::xposed::dispatch_after(&engine._rt, &engine.ctx, state, dispatch_id, &result)
                }
                None => result,
            },
            None => result,
        };
        env.new_string(answer).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
    })
}

/// how long the host parks a hooked method's thread on one dispatch phase.
///
/// The waiting is the host's, but the number is stated in `android.xposed.d.ts` and pinned against
/// it here, so there is one of it rather than one per side.
#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeXposedBudgetMs(
    _env: EnvUnowned,
    _this: JObject,
) -> jlong {
    crate::platform::xposed::HOOK_BUDGET_MS
}

engine_export!(
    /// drops a dispatch the host stopped waiting on, so its GC roots do not outlive the engine
    Java_desu_inugram_helpers_plugins_QuickJs_nativeXposedRelease, xposed,
    (dispatch_id: jlong),
    |engine, state| crate::platform::xposed::release_dispatch(&engine.ctx, state, dispatch_id)
);

/// Loads lsplant + shadowhook and runs `lsplant::Init`. Process-wide and idempotent; false means
/// hooking is unavailable on this device.
///
/// `env` must carry no hidden-api restrictions, which is why the host chooses when this runs rather
/// than it happening lazily behind the first hook.
#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_PluginXposed_00024Native_nativeInit(
    mut env: EnvUnowned,
    _this: JObject,
) -> jboolean {
    in_env(&mut env, false, |env| crate::platform::lsplant::init(env))
}

/// Rewrites `target`'s entry point to call `callback` on `hooker`, answering the backup method to
/// invoke the original through, or null.
///
/// Nothing about the hook registry is on this side: `hooker` carries a site id minted by
/// `xposed.rs`, and a forged one resolves to nothing.
#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_PluginXposed_00024Native_nativeHook<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    target: JObject<'local>,
    hooker: JObject<'local>,
    callback: JObject<'local>,
) -> jobject {
    in_env(&mut env, std::ptr::null_mut(), |env| unsafe {
        crate::platform::lsplant::hook(env, target.as_raw(), hooker.as_raw(), callback.as_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_PluginXposed_00024Native_nativeUnhook<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    target: JObject<'local>,
) -> jboolean {
    in_env(&mut env, false, |env| unsafe { crate::platform::lsplant::unhook(env, target.as_raw()) })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_PluginXposed_00024Native_nativeIsHooked<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    target: JObject<'local>,
) -> jboolean {
    in_env(&mut env, false, |env| unsafe { crate::platform::lsplant::is_hooked(env, target.as_raw()) })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_PluginXposed_00024Native_nativeDeoptimize<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    method: JObject<'local>,
) -> jboolean {
    in_env(&mut env, false, |env| unsafe { crate::platform::lsplant::deoptimize(env, method.as_raw()) })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_PluginXposed_00024Native_nativeMakeInheritable<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    target: JObject<'local>,
) -> jboolean {
    in_env(&mut env, false, |env| unsafe { crate::platform::lsplant::make_inheritable(env, target.as_raw()) })
}

engine_export!(
    /// a java `Runnable` this engine minted was run. Posted from `PluginJvm`, never called from inside
    /// the reflected call that handed the object over: that call is already inside this engine.
    Java_desu_inugram_helpers_plugins_QuickJs_nativeJvmCallback, jvm,
    (callback_id: jint),
    |engine, state| crate::platform::jvm::dispatch_callback(&engine._rt, &engine.ctx, state, callback_id as u32)
);

/// installs `inu.fs` over this plugin's own durable directory. Separate from
/// [`Java_desu_inugram_helpers_plugins_QuickJs_nativeInstallApi`] because only the host can answer
/// what that directory is, and after it because `fs.write` takes a `Blob`, whose table the api
/// install is what creates.
///
/// `dir` == "" leaves every `inu.fs` call failing rather than landing in a directory this plugin
/// does not own. `android_dirs` is newline separated in [`crate::io::fs::install_fs`]'s order, an empty entry
/// being one the host could not answer.
#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeInstallFs(
    mut env: EnvUnowned,
    _this: JObject,
    ptr: jlong,
    dir: JString,
    quota_bytes: jlong,
    unscoped: jboolean,
    android_dirs: JString,
) {
    in_env(&mut env, (), |env| {
        let _deadline = crate::engine::deadline::arm_entry_deadline();
        let dir = jstring_to_string(env, &dir);
        let android_dirs = jstring_to_string(env, &android_dirs);
        let Some(engine) = (unsafe { (ptr as *mut Engine).as_mut() }) else {
            return;
        };
        let Some(blobs) = engine.blobs.clone() else {
            return;
        };
        let grants: Rc<dyn GrantHost> = engine.bridge.clone();
        let log = make_log(engine.bridge.console.clone());
        let quota = if quota_bytes < 0 {
            // -1 is how the host asks for the uncapped mode; a 0 is a host that had no number to give
            crate::io::fs::UNCAPPED
        } else if quota_bytes == 0 {
            crate::io::fs::DEFAULT_QUOTA_BYTES
        } else {
            quota_bytes as u64
        };
        let installed = engine.ctx.with(|ctx| {
            crate::io::fs::install_fs(&ctx, grants, blobs, std::path::Path::new(&dir), quota, unscoped, &android_dirs)
        });
        match installed {
            Ok(state) => {
                // `inu.canvas.load({ path })` resolves through the same gate `inu.fs` owns rather than
                // a second copy of it, so it is wired up once this exists
                if let Some(canvas) = engine.canvas.as_ref() {
                    crate::draw::canvas::attach_fs(canvas, state.clone());
                }
                engine.fs = Some(state);
            }
            Err(e) => log(&format!("inu.fs failed to install: {e:?}")),
        }
    })
}

engine_export!(
    /// the host finished (or failed) a `convertToBlob`, a `decode`/`load` or a `loadFont`
    Java_desu_inugram_helpers_plugins_QuickJs_nativeCanvasResult, canvas,
    (request_id: jlong, result_wire: JString),
    strings(result_wire),
    |engine, state| crate::draw::canvas::canvas_result(&engine._rt, &engine.ctx, state, request_id, &result_wire)
);

engine_export!(
    /// the host finished (or failed) a `fetch`; `result_wire` is `J<json>` carrying
    /// `{status, statusText, url, headers, body: {path, type}}`, or an error wire
    Java_desu_inugram_helpers_plugins_QuickJs_nativeFetchResult, fetch,
    (request_id: jlong, result_wire: JString),
    strings(result_wire),
    |engine, state| crate::io::fetch::fetch_result(&engine._rt, &engine.ctx, state, request_id, &result_wire)
);

engine_export!(
    /// the wake requested through `QuickJs.onTimerSchedule` came due
    Java_desu_inugram_helpers_plugins_QuickJs_nativeRunTimers, timers,
    (),
    |engine, state| crate::engine::timers::run_due(&engine._rt, &engine.ctx, state)
);

/// the app moved to the foreground or the background: floors this engine's timer wheel and fires
/// its `inu.onAppVisibilityChange` callbacks. Nothing else throttles - updates, interceptors and
/// every host callback keep their timing, so a message arriving while hidden still reaches the
/// plugin that asked for it.
#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeAppVisibilityChanged(
    _env: EnvUnowned,
    _this: JObject,
    ptr: jlong,
    visible: jboolean,
) {
    let _deadline = crate::engine::deadline::arm_entry_deadline();
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else {
        return;
    };

    // the wheel first, so a callback arming a timer already arms it against the new floor
    if let Some(state) = engine.timers.as_ref() {
        crate::engine::timers::set_visible(state, visible);
    }
    if let Some(state) = engine.api.as_ref() {
        crate::api::app_visibility_changed(&engine._rt, &engine.ctx, state, visible);
    }
}

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeUiRender, ui,
    (page_id: jlong),
    strings(),
    -> jstring |engine, state| crate::ui::pages::render_page(&engine._rt, &engine.ctx, state, page_id)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeUiEvent, ui,
    (page_id: jlong, slot: jint, arg_json: JString),
    strings(arg_json),
    |engine, state| crate::ui::pages::dispatch_ui_event(&engine._rt, &engine.ctx, state, page_id, slot as u32, &arg_json)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeUiMenuClick, ui,
    (menu_id: jlong, slot: jint),
    |engine, state| crate::ui::pages::dispatch_menu_click(&engine._rt, &engine.ctx, state, menu_id, slot)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeUiPageClosed, ui,
    (page_id: jlong),
    |engine, state| crate::ui::pages::page_closed(&engine._rt, &engine.ctx, state, page_id)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeRenderActions, actions,
    (kind: jint, surface_json: JString),
    strings(surface_json),
    -> jstring |engine, state| crate::ui::actions::render_actions(&engine._rt, &engine.ctx, state, kind, &surface_json)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchAction, actions,
    (kind: jint, token: jint, surface_json: JString),
    strings(surface_json),
    |engine, state| crate::ui::actions::dispatch_action(&engine._rt, &engine.ctx, state, kind, token as u32, &surface_json)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeResolvePrompt, ui,
    (request_id: jlong, text: JString),
    opt_strings(text),
    |engine, state| crate::ui::pages::resolve_prompt(&engine._rt, &engine.ctx, state, request_id, text.as_deref())
);

engine_export!(
    /// settles a pending `inu.ui.chooser()`; `picked` is null for dismissed, else a comma-separated
    /// index list (empty == a multi-select the user confirmed with nothing ticked)
    Java_desu_inugram_helpers_plugins_QuickJs_nativeResolveChooser, api,
    (request_id: jlong, picked: JString),
    opt_strings(picked),
    |engine, state| crate::api::resolve_chooser(&engine._rt, &engine.ctx, state, request_id, picked.as_deref())
);

engine_export!(
    /// the user navigated: fires `inu.ui.onScreenChanged`. The host has already diffed its own fragment
    /// stack, so `change_json` names the action and every call here is one navigation.
    Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchScreenChange, screens,
    (change_json: JString, stack_json: JString),
    strings(change_json, stack_json),
    |engine, state| crate::ui::screens::dispatch_screen_change(&engine._rt, &engine.ctx, state, &change_json, &stack_json)
);

engine_export!(
    /// the app posted `name` on the `NotificationCenter` of slot `account` (`-1` is the app-wide one).
    /// `args_json` is the event's own arguments with everything that is not a scalar already `null`,
    /// which is the whole of what a plugin may see of them.
    Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchNotification, notifications,
    (callback_id: jint, name: JString, account: jint, args_json: JString),
    strings(name, args_json),
    |engine, state| crate::platform::notifications::dispatch_notification(&engine._rt, &engine.ctx, state, callback_id as u32, &name, account, &args_json)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeResolveDialog, api,
    (request_id: jlong, result: JString),
    strings(result),
    |engine, state| crate::api::resolve_dialog(&engine._rt, &engine.ctx, state, request_id, &result)
);

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeNotifyUnload(
    _env: EnvUnowned,
    _this: JObject,
    ptr: jlong,
) {
    let _deadline = crate::engine::deadline::arm_entry_deadline();
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else {
        return;
    };
    engine.lifecycle.begin_unload();
    if let Some(state) = engine.account.as_ref() {
        crate::tg::account::notify_unload(&engine._rt, &engine.ctx, state);
    }
    if let Some(state) = engine.api.as_ref() {
        crate::api::notify_unload(&engine._rt, &engine.ctx, state);
    }
    // last: an `inu.onUnload` callback clearing its own timers must still find them
    let Some(state) = engine.timers.as_ref() else {
        return;
    };
    crate::engine::timers::notify_unload(&engine.ctx, state);
}

engine_export!(Java_desu_inugram_helpers_plugins_QuickJs_nativeAccountsChanged, account, (), |engine, state| {
    crate::tg::account::accounts_changed(&engine._rt, &engine.ctx, state)
});

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeInstallRpc(
    _env: EnvUnowned,
    _this: JObject,
    ptr: jlong,
) {
    let _deadline = crate::engine::deadline::arm_entry_deadline();
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_mut() }) else {
        return;
    };
    let host: Rc<dyn RpcHost> = engine.bridge.clone();
    let tl = engine.views.clone();
    let grants: Rc<dyn GrantHost> = engine.bridge.clone();
    let log = make_log(engine.bridge.console.clone());

    let lifecycle = engine.lifecycle.clone();
    // every dispatch carries the account it arrived on, so the account api has to be installed first
    let accounts = engine.account.clone();
    // `installApi`'s, since `inu.interceptSendMessage` normalizes a peer through the one
    // implementation of it the read and write surfaces already share
    let Some(shared) = engine.shared.clone() else {
        log("inu.interceptRpc/onUpdate cannot install without inu.utils");
        return;
    };

    // `inu.interceptDeserialize` rides along here: it is a registration in the same family
    let rules_host: Rc<dyn DeserializeHost> = engine.bridge.clone();
    let rules_grants: Rc<dyn GrantHost> = engine.bridge.clone();
    let rules_lifecycle = engine.lifecycle.clone();
    let rules_tl = engine.views.clone();
    let rules_log = log.clone();

    let installed = engine.ctx.with(|ctx| {
        let rules = crate::tg::deserialize::install_deserialize(
            &ctx,
            rules_host,
            rules_grants,
            rules_lifecycle,
            rules_tl,
            rules_log,
        )?;
        let shared = shared.restore(&ctx)?;
        let rpc = crate::tg::rpc::install_rpc(&ctx, host, tl, grants, lifecycle, accounts, shared, log.clone())?;
        Ok::<_, rquickjs::Error>((rules, rpc))
    });
    match installed {
        Ok((rules, state)) => {
            engine.deserialize = Some(rules);
            engine.rpc = Some(state);
            pump(engine);
        }
        // the whole rpc/events surface is gone when this fails, which is worth saying out loud
        Err(e) => log(&format!("inu.interceptRpc/onUpdate failed to install: {e:?}")),
    }
}

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchRpc, rpc,
    (callback_id: jint, dispatch_id: jlong, method: JString, account_id: jint, request_wire: JString),
    strings(method, request_wire),
    |engine, state| crate::tg::rpc::dispatch_rpc(&engine._rt, &engine.ctx, state, callback_id as u32, dispatch_id, &method, account_id, &request_wire)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeCompleteNext, rpc,
    (dispatch_id: jlong, result_wire: JString),
    strings(result_wire),
    |engine, state| crate::tg::rpc::complete_next(&engine._rt, &engine.ctx, state, dispatch_id, &result_wire)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeAbandonDispatch, rpc,
    (dispatch_id: jlong, reason_wire: JString),
    strings(reason_wire),
    |engine, state| crate::tg::rpc::abandon_dispatch(&engine._rt, &engine.ctx, state, dispatch_id, &reason_wire)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeResolveInvoke, rpc,
    (invoke_id: jlong, result_wire: JString),
    strings(result_wire),
    |engine, state| crate::tg::rpc::resolve_invoke(&engine._rt, &engine.ctx, state, invoke_id, &result_wire)
);

engine_export!(
    /// the host answered a `resolvePeer`/`resolveUser`/`resolveChannel` the cache could not
    Java_desu_inugram_helpers_plugins_QuickJs_nativeResolvePeerResult, reads,
    (request_id: jlong, result_wire: JString),
    strings(result_wire),
    |engine, state| crate::tg::reads::resolve_peer_result(&engine._rt, &engine.ctx, state, request_id, &result_wire)
);

engine_export!(
    /// the host answered a `getHistory`/`getDialogs`/`getTopics`/`getUserFull`/`getChatFull`
    Java_desu_inugram_helpers_plugins_QuickJs_nativeAccountFetchResult, reads,
    (request_id: jlong, result_wire: JString),
    strings(result_wire),
    |engine, state| crate::tg::reads::account_fetch_result(&engine._rt, &engine.ctx, state, request_id, &result_wire)
);

engine_export!(
    /// the host settled a write or a media transfer
    Java_desu_inugram_helpers_plugins_QuickJs_nativeWriteResult, writes,
    (request_id: jlong, result_wire: JString),
    strings(result_wire),
    |engine, state| crate::tg::writes::write_result(&engine._rt, &engine.ctx, state, request_id, &result_wire)
);

engine_export!(
    /// one `(loaded, total)` from a transfer in flight; the coalescing decides whether it is delivered
    Java_desu_inugram_helpers_plugins_QuickJs_nativeWriteProgress, writes,
    (request_id: jlong, loaded: jlong, total: jlong),
    |engine, state| crate::tg::writes::write_progress(&engine._rt, &engine.ctx, state, request_id, loaded, total)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchUpdate, rpc,
    (type_name: JString, account_id: jint, update_wire: JString),
    strings(type_name, update_wire),
    |engine, state| crate::tg::rpc::dispatch_update(&engine._rt, &engine.ctx, state, &type_name, account_id, &update_wire)
);

engine_export!(
    /// one `interceptDeserialize` middleware, over one object the app has just parsed. The parsing
    /// thread is blocked on this returning, so it may not park: nothing here awaits, and the view dies
    /// with the host's scope the moment it answers.
    Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchDeserialize, deserialize,
    (callback_id: jint, object_wire: JString),
    strings(object_wire),
    |engine, state| crate::tg::deserialize::dispatch_middleware(&engine.ctx, state, callback_id as u32, &object_wire)
);

engine_export!(
    /// one `interceptUpdate` stage; the host is answered by `onUpdateVerdict`, always exactly once
    Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchUpdateIntercept, rpc,
    (callback_id: jint, dispatch_id: jlong, type_name: JString, account_id: jint, update_wire: JString),
    strings(type_name, update_wire),
    |engine, state| crate::tg::rpc::dispatch_update_intercept(&engine._rt, &engine.ctx, state, callback_id as u32, dispatch_id, &type_name, account_id, &update_wire)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeAbandonUpdateDispatch, rpc,
    (dispatch_id: jlong),
    |engine, state| crate::tg::rpc::abandon_update_dispatch(&engine._rt, &engine.ctx, state, dispatch_id)
);

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeInstallInfo<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    ptr: jlong,
    app_version: JString<'local>,
    app_build: JString<'local>,
    api_version: jint,
    layer: jint,
    language: JString<'local>,
    header_keys: JObjectArray<'local, JString<'local>>,
    header_values: JObjectArray<'local, JString<'local>>,
) {
    in_env(&mut env, (), |env| {
        let _deadline = crate::engine::deadline::arm_entry_deadline();
        let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else {
            return;
        };
        let info = Arc::new(InuInfo {
            app_version: jstring_to_string(env, &app_version),
            app_build: jstring_to_string(env, &app_build),
            api_version,
            layer,
            language: jstring_to_string(env, &language),
            header: read_header(env, &header_keys, &header_values),
        });
        let _ = engine.ctx.with(|ctx| install_inu(&ctx, info));
    })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeEvaluate(
    mut env: EnvUnowned,
    _this: JObject,
    ptr: jlong,
    code: JString,
    filename: JString,
) -> jstring {
    in_env(&mut env, std::ptr::null_mut(), |env| {
        let _deadline = crate::engine::deadline::arm_eval_deadline();
        let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else {
            return std::ptr::null_mut();
        };
        let code = jstring_to_string(env, &code).into_bytes();
        let filename = jstring_to_string(env, &filename);

        let result: Result<String, String> = engine.ctx.with(|ctx| {
            let mut options = rquickjs::context::EvalOptions::default();
            options.filename = Some(filename);
            match ctx.eval_with_options::<Value, _>(code, options) {
                Ok(v) => {
                    use rquickjs::FromJs;
                    Ok(Coerced::<String>::from_js(&ctx, v).map(|c| c.0).unwrap_or_default())
                }
                Err(rquickjs::Error::Exception) => Err(format_exception(&ctx)),
                Err(e) => Err(e.to_string()),
            }
        });

        // drain microtasks from the initial run so top-level async work settles and any unhandled
        // rejection is surfaced
        pump(engine);

        match result {
            Ok(s) => env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut()),
            Err(msg) => {
                let _ = env.throw_new(JNIString::from("java/lang/RuntimeException"), JNIString::from(msg));
                std::ptr::null_mut()
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeDestroy(
    _env: EnvUnowned,
    _this: JObject,
    ptr: jlong,
) {
    if ptr != 0 {
        let mut engine = unsafe { Box::from_raw(ptr as *mut Engine) };
        if let Some(state) = engine.rpc.take() {
            crate::tg::rpc::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.deserialize.take() {
            crate::tg::deserialize::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.api.take() {
            crate::api::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.ui.take() {
            crate::ui::pages::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.screens.take() {
            crate::ui::screens::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.actions.take() {
            crate::ui::actions::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.writes.take() {
            crate::tg::writes::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.reads.take() {
            crate::tg::reads::dispose(&engine.ctx, &state);
        }
        // after reads, whose prototype it holds the last reference to
        if let Some(state) = engine.account.take() {
            crate::tg::account::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.fetch.take() {
            crate::io::fetch::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.canvas.take() {
            crate::draw::canvas::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.timers.take() {
            crate::engine::timers::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.notifications.take() {
            crate::platform::notifications::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.xposed.take() {
            crate::platform::xposed::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.jvm.take() {
            crate::platform::jvm::dispose(&engine.ctx, &state);
        }
        if let Some(shared) = engine.shared.take() {
            engine.ctx.with(|ctx| drop(shared.restore(&ctx)));
        }
        engine.ctx.with(|ctx| crate::tg::rpc::dispose_rejection_tracker(&ctx));
        drop(engine);
    }
}

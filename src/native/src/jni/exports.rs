use jni::objects::{JClass, JObject, JObjectArray, JString};
use jni::strings::JNIString;
use jni::sys::{jboolean, jclass, jint, jlong, jobject, jstring};
use jni::EnvUnowned;
use rquickjs::{Coerced, Context, Object, Persistent, Result as JsResult, Runtime, Value};
use std::rc::Rc;
use std::sync::Arc;

use super::bridge::JniBridge;
use super::env::{in_env, jstring_to_string, read_header, read_string_array};
use super::log::{install_console, make_log};
use super::{pump, Engine};
use crate::api::canvas::CanvasHost;
use crate::api::globals::RandomHost;
use crate::api::info::{install_inu, InuInfo};
use crate::api::io::fetch::FetchHost;
use crate::api::io::kv::KvHost;
use crate::api::platform::clipboard::ClipboardHost;
use crate::api::platform::jvm::JvmHost;
use crate::api::platform::notifications::NotificationHost;
use crate::api::platform::open_url::OpenUrlHost;
use crate::api::platform::xposed::XposedHost;
use crate::api::telegram::account::AccountHost;
use crate::api::telegram::deserialize::DeserializeHost;
use crate::api::telegram::reads::ReadsHost;
use crate::api::telegram::rpc::{format_exception, RpcHost};
use crate::api::telegram::writes::WritesHost;
use crate::api::timers::TimerHost;
use crate::api::ui::actions::ActionHost;
use crate::api::ui::dialogs::DialogHost;
use crate::api::ui::icons::IconHost;
use crate::api::ui::pages::UiHost;
use crate::api::ui::screens::ScreenHost;
use crate::sandbox::grants::GrantHost;
use crate::sandbox::registry::Lifecycle;

macro_rules! engine_export {
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
                let _deadline = crate::sandbox::limits::arm_entry_deadline();
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
                let _deadline = crate::sandbox::limits::arm_entry_deadline();
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
    (
        $(#[$meta:meta])*
        $name:ident, $field:ident,
        ($($param:ident: $ty:ty),* $(,)?),
        |$engine:ident, $state:ident| $call:expr $(,)?
    ) => {
        $(#[$meta])*
        #[no_mangle]
        pub extern "system" fn $name(_env: EnvUnowned, _this: JObject, ptr: jlong, $($param: $ty),*) {
            let _deadline = crate::sandbox::limits::arm_entry_deadline();
            let Some($engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else {
                return;
            };
            let Some($state) = $engine.$field.as_ref() else {
                return;
            };
            $call;
        }
    };
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
                let _deadline = crate::sandbox::limits::arm_entry_deadline();
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
        let Some(bridge) = JniBridge::new(env, &listener) else {
            return 0;
        };

        let Ok(rt) = Runtime::new() else { return 0 };
        let Ok(ctx) = Context::full(&rt) else {
            return 0;
        };

        let installed = ctx.with(|ctx| -> JsResult<Persistent<Object<'static>>> {
            install_console(&ctx, bridge.clone())?;
            let inu = Object::new(ctx.clone())?;
            ctx.globals().set("inu", inu.clone())?;
            crate::api::error::install_plugin_error(&ctx, &inu)?;
            Ok(Persistent::save(&ctx, inu))
        });
        let Ok(inu) = installed else {
            return 0;
        };

        crate::api::telegram::rpc::install_rejection_tracker(&rt, make_log(bridge.console.clone()));
        let interrupt_log = make_log(bridge.console.clone());
        crate::sandbox::limits::install_interrupt_handler(&rt, Arc::new(move |msg: &str| interrupt_log(msg)));
        crate::sandbox::limits::apply_heap_limit(&rt);

        let views = crate::api::tl::proxy::TlViews::new(bridge.clone());
        let engine = Box::new(Engine {
            ctx,
            _rt: rt,
            bridge,
            lifecycle: Lifecycle::new(),
            inu,
            views,
            blobs: None,
            shared: None,
            rpc: None,
            deserialize: None,
            lifecycle_state: None,
            dialogs: None,
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
        let _deadline = crate::sandbox::limits::arm_entry_deadline();
        let spill_dir = jstring_to_string(env, &spill_dir);
        let Some(engine) = (unsafe { (ptr as *mut Engine).as_mut() }) else {
            return;
        };
        let grants: Rc<dyn GrantHost> = engine.bridge.clone();
        let log = make_log(engine.bridge.console.clone());

        let lifecycle = engine.lifecycle.clone();

        let installed = engine.ctx.with(|ctx| {
            let inu = engine.inu.clone().restore(&ctx)?;
            crate::api::lifecycle::install_lifecycle(&ctx, grants.clone(), lifecycle.clone(), log.clone(), &inu)
        });
        match installed {
            Ok(state) => engine.lifecycle_state = Some(state),
            Err(e) => log(&format!("inu.onUnload failed to install: {e:?}")),
        }
        let installed = engine.ctx.with(|ctx| {
            let inu = engine.inu.clone().restore(&ctx)?;
            let kv_host: Rc<dyn KvHost> = engine.bridge.clone();
            crate::api::io::kv::install_kv(&ctx, kv_host, grants.clone(), &inu)?;
            let clipboard_host: Rc<dyn ClipboardHost> = engine.bridge.clone();
            crate::api::platform::clipboard::install_clipboard(&ctx, clipboard_host, grants.clone(), &inu)?;
            let open_url_host: Rc<dyn OpenUrlHost> = engine.bridge.clone();
            crate::api::platform::open_url::install_open_url(&ctx, open_url_host, grants.clone(), &inu)?;
            let dialog_host: Rc<dyn DialogHost> = engine.bridge.clone();
            crate::api::ui::dialogs::install_dialogs(&ctx, dialog_host, log.clone(), &inu)
        });
        match installed {
            Ok(state) => engine.dialogs = Some(state),
            Err(e) => log(&format!("inu.kv/inu.ui failed to install: {e:?}")),
        }
        let account_host: Rc<dyn AccountHost> = engine.bridge.clone();
        let installed = engine.ctx.with(|ctx| {
            let inu = engine.inu.clone().restore(&ctx)?;
            crate::api::telegram::account::install_account(
                &ctx,
                account_host,
                grants,
                lifecycle.clone(),
                log.clone(),
                &inu,
            )
        });
        match installed {
            Ok(state) => engine.account = Some(state),
            Err(e) => log(&format!("inu.account failed to install: {e:?}")),
        }
        let ui_host: Rc<dyn UiHost> = engine.bridge.clone();
        let installed = engine.ctx.with(|ctx| {
            let inu = engine.inu.clone().restore(&ctx)?;
            crate::api::ui::pages::install_ui(&ctx, ui_host, lifecycle.clone(), log.clone(), engine.jvm.clone(), &inu)
        });
        match installed {
            Ok(state) => engine.ui = Some(state),
            Err(e) => log(&format!("inu.ui pages failed to install: {e:?}")),
        }
        let icon_host: Rc<dyn IconHost> = engine.bridge.clone();
        if let Err(e) = engine.ctx.with(|ctx| {
            let inu = engine.inu.clone().restore(&ctx)?;
            crate::api::ui::icons::install_icons(&ctx, icon_host, &inu)
        }) {
            log(&format!("inu.icons failed to install: {e:?}"));
        }
        let action_host: Rc<dyn ActionHost> = engine.bridge.clone();
        let accounts = engine.account.clone();
        let action_grants: Rc<dyn GrantHost> = engine.bridge.clone();
        let installed = engine.ctx.with(|ctx| {
            let inu = engine.inu.clone().restore(&ctx)?;
            crate::api::ui::actions::install_actions(
                &ctx,
                action_host,
                lifecycle.clone(),
                accounts,
                action_grants,
                log.clone(),
                &inu,
            )
        });
        match installed {
            Ok(state) => engine.actions = Some(state),
            Err(e) => log(&format!("inu.register*Action failed to install: {e:?}")),
        }
        let screen_host: Rc<dyn ScreenHost> = engine.bridge.clone();
        let screen_grants: Rc<dyn GrantHost> = engine.bridge.clone();
        let accounts = engine.account.clone();
        let installed = engine.ctx.with(|ctx| {
            let inu = engine.inu.clone().restore(&ctx)?;
            crate::api::ui::screens::install_screens(
                &ctx,
                screen_host,
                screen_grants,
                accounts,
                lifecycle.clone(),
                log.clone(),
                &inu,
            )
        });
        match installed {
            Ok(state) => engine.screens = Some(state),
            Err(e) => log(&format!("inu.ui navigation failed to install: {e:?}")),
        }
        let notification_host: Rc<dyn NotificationHost> = engine.bridge.clone();
        let notification_grants: Rc<dyn GrantHost> = engine.bridge.clone();
        let installed = engine.ctx.with(|ctx| {
            let inu = engine.inu.clone().restore(&ctx)?;
            crate::api::platform::notifications::install_notifications(
                &ctx,
                notification_host,
                notification_grants,
                lifecycle.clone(),
                log.clone(),
                &inu,
            )
        });
        match installed {
            Ok(state) => engine.notifications = Some(state),
            Err(e) => log(&format!("inu.android.addNotificationCenterDelegate failed to install: {e:?}")),
        }
        let random_host: Rc<dyn RandomHost> = engine.bridge.clone();
        let spill_dir = std::path::PathBuf::from(spill_dir);
        let external = crate::sandbox::limits::ExternalMemory::new();
        let blobs = match engine
            .ctx
            .with(|ctx| crate::api::globals::install_globals(&ctx, random_host, &spill_dir, external.clone()))
        {
            Ok(blobs) => Some(blobs),
            Err(e) => {
                log(&format!("sandbox globals failed to install: {e:?}"));
                None
            }
        };
        engine.blobs = blobs.clone();
        if let Some(blobs) = blobs.clone() {
            let canvas_host: Rc<dyn CanvasHost> = engine.bridge.clone();
            let installed = engine.ctx.with(|ctx| {
                let inu = engine.inu.clone().restore(&ctx)?;
                crate::api::canvas::install_canvas(
                    &ctx,
                    canvas_host,
                    blobs,
                    external.clone(),
                    spill_dir.clone(),
                    log.clone(),
                    &inu,
                )
            });
            match installed {
                Ok(state) => engine.canvas = Some(state),
                Err(e) => log(&format!("inu.canvas failed to install: {e:?}")),
            }
        }
        let reads_host: Rc<dyn ReadsHost> = engine.bridge.clone();
        let writes_host: Rc<dyn WritesHost> = engine.bridge.clone();
        let grants: Rc<dyn GrantHost> = engine.bridge.clone();
        let views = engine.views.clone();
        let accounts = engine.account.clone();
        type Surfaces = (
            Option<Rc<crate::api::telegram::reads::ReadsState>>,
            Option<Rc<crate::api::telegram::writes::WritesState>>,
        );
        let mut shared_root = None;
        let installed = engine.ctx.with(|ctx| -> rquickjs::Result<Surfaces> {
            let inu = engine.inu.clone().restore(&ctx)?;
            let utils_host: Rc<dyn crate::api::tl::utils::UtilsHost> = engine.bridge.clone();
            let shared = crate::api::tl::utils::install_utils_with_host(&ctx, utils_host, &inu)?;
            crate::api::tl::message::install_message(&ctx, &shared, &inu)?;
            shared_root = Some(Persistent::save(&ctx, shared.clone()));
            let Some(accounts) = accounts.as_ref() else {
                return Ok((None, None));
            };
            let reads = crate::api::telegram::reads::install_reads(
                &ctx,
                reads_host,
                grants.clone(),
                views.clone(),
                &shared,
                accounts,
                log.clone(),
                &inu,
            )?;
            let Some(blobs) = blobs else {
                return Ok((Some(reads), None));
            };
            let deps = crate::api::telegram::writes::WritesDeps {
                host: writes_host,
                grants,
                views,
                blobs,
                stage_dir: spill_dir.clone(),
                log: log.clone(),
            };
            let writes = crate::api::telegram::writes::install_writes(&ctx, deps, &shared, accounts, &inu)?;
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
            engine.ctx.with(|ctx| crate::api::timers::install_timers(&ctx, timer_host, lifecycle, log.clone()));
        match installed {
            Ok(state) => engine.timers = Some(state),
            Err(e) => log(&format!("timers failed to install: {e:?}")),
        }
        let fetch_host: Rc<dyn FetchHost> = engine.bridge.clone();
        let grants: Rc<dyn GrantHost> = engine.bridge.clone();
        if let Some(blobs) = engine.blobs.clone() {
            let installed = engine.ctx.with(|ctx| {
                let inu = engine.inu.clone().restore(&ctx)?;
                crate::api::io::fetch::install_fetch(&ctx, fetch_host, grants, blobs, log.clone(), &inu)
            });
            match installed {
                Ok(state) => engine.fetch = Some(state),
                Err(e) => log(&format!("fetch failed to install: {e:?}")),
            }
        }
        pump(engine);
    })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeInstallJvm(
    _env: EnvUnowned,
    _this: JObject,
    ptr: jlong,
) {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_mut() }) else {
        return;
    };
    let host: Rc<dyn JvmHost> = engine.bridge.clone();
    let grants: Rc<dyn GrantHost> = engine.bridge.clone();
    let log = make_log(engine.bridge.console.clone());
    let lifecycle = engine.lifecycle.clone();
    let installed = engine.ctx.with(|ctx| {
        let inu = engine.inu.clone().restore(&ctx)?;
        crate::api::platform::jvm::install_jvm(&ctx, host, grants, lifecycle, log.clone(), &inu)
    });
    match installed {
        Ok(state) => engine.jvm = Some(state),
        Err(e) => log(&format!("inu.jvm failed to install: {e:?}")),
    }
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeInstallXposed(
    _env: EnvUnowned,
    _this: JObject,
    ptr: jlong,
) {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
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
    let installed = engine.ctx.with(|ctx| {
        let inu = engine.inu.clone().restore(&ctx)?;
        crate::api::platform::xposed::install_xposed(&ctx, host, grants, lifecycle, jvm, log.clone(), &inu)
    });
    match installed {
        Ok(state) => engine.xposed = Some(state),
        Err(e) => log(&format!("inu.xposed failed to install: {e:?}")),
    }
}

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
        let _deadline = crate::sandbox::limits::arm_entry_deadline();
        let method_wire = jstring_to_string(env, &method_wire);
        let this_wire = jstring_to_string(env, &this_wire);
        let args = read_string_array(env, &args);
        let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else {
            return std::ptr::null_mut();
        };
        let answer = match engine.xposed.as_ref() {
            Some(state) => crate::api::platform::xposed::dispatch_before(
                &engine._rt,
                &engine.ctx,
                state,
                dispatch_id,
                site,
                &crate::api::platform::xposed::Invocation { method: &method_wire, this: &this_wire, args: &args },
            ),
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

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeXposedAfter<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    ptr: jlong,
    dispatch_id: jlong,
    result: JString<'local>,
) -> jstring {
    in_env(&mut env, std::ptr::null_mut(), |env| {
        let _deadline = crate::sandbox::limits::arm_entry_deadline();
        let result = jstring_to_string(env, &result);
        let answer = match unsafe { (ptr as *mut Engine).as_ref() } {
            Some(engine) => match engine.xposed.as_ref() {
                Some(state) => {
                    crate::api::platform::xposed::dispatch_after(&engine._rt, &engine.ctx, state, dispatch_id, &result)
                }
                None => result,
            },
            None => result,
        };
        env.new_string(answer).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
    })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeXposedBudgetMs(
    _env: EnvUnowned,
    _this: JObject,
) -> jlong {
    crate::api::platform::xposed::HOOK_BUDGET_MS
}

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeXposedRelease, xposed,
    (dispatch_id: jlong),
    |engine, state| crate::api::platform::xposed::release_dispatch(&engine.ctx, state, dispatch_id)
);

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_platform_PluginXposed_00024Native_nativeInit(
    mut env: EnvUnowned,
    _this: JObject,
) -> jboolean {
    in_env(&mut env, false, |env| crate::api::platform::xposed::lsplant::init(env))
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_platform_PluginXposed_00024Native_nativeHook<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    target: JObject<'local>,
    hooker: JObject<'local>,
    callback: JObject<'local>,
) -> jobject {
    in_env(&mut env, std::ptr::null_mut(), |env| unsafe {
        crate::api::platform::xposed::lsplant::hook(env, target.as_raw(), hooker.as_raw(), callback.as_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_platform_PluginXposed_00024Native_nativeUnhook<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    target: JObject<'local>,
) -> jboolean {
    in_env(&mut env, false, |env| unsafe { crate::api::platform::xposed::lsplant::unhook(env, target.as_raw()) })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_platform_PluginXposed_00024Native_nativeIsHooked<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    target: JObject<'local>,
) -> jboolean {
    in_env(&mut env, false, |env| unsafe { crate::api::platform::xposed::lsplant::is_hooked(env, target.as_raw()) })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_platform_PluginXposed_00024Native_nativeDeoptimize<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    method: JObject<'local>,
) -> jboolean {
    in_env(&mut env, false, |env| unsafe { crate::api::platform::xposed::lsplant::deoptimize(env, method.as_raw()) })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_platform_PluginXposed_00024Native_nativeMakeInheritable<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    target: JObject<'local>,
) -> jboolean {
    in_env(&mut env, false, |env| unsafe {
        crate::api::platform::xposed::lsplant::make_inheritable(env, target.as_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_platform_PluginXposed_00024Native_nativeAllocateInstance<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    target: JObject<'local>,
) -> jobject {
    in_env(&mut env, std::ptr::null_mut(), |env| {
        let _deadline = crate::sandbox::limits::arm_entry_deadline();
        let class = unsafe { JClass::from_raw(env, target.into_raw() as jclass) };
        env.alloc_object(class).map(|value| value.into_raw()).unwrap_or(std::ptr::null_mut())
    })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_platform_PluginXposed_00024Native_nativeDisableProfileSaver(
    mut env: EnvUnowned,
    _this: JObject,
) -> jboolean {
    in_env(&mut env, false, |_env| {
        let _deadline = crate::sandbox::limits::arm_entry_deadline();
        crate::api::platform::xposed::lsplant::disable_profile_saver()
    })
}

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeJvmCallback, jvm,
    (callback_id: jint),
    |engine, state| crate::api::platform::jvm::dispatch_callback(&engine._rt, &engine.ctx, state, callback_id as u32)
);

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
        let _deadline = crate::sandbox::limits::arm_entry_deadline();
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
            crate::api::io::fs::UNCAPPED
        } else if quota_bytes == 0 {
            crate::api::io::fs::DEFAULT_QUOTA_BYTES
        } else {
            quota_bytes as u64
        };
        let installed = engine.ctx.with(|ctx| {
            let inu = engine.inu.clone().restore(&ctx)?;
            crate::api::io::fs::install_fs(
                &ctx,
                grants,
                blobs,
                std::path::Path::new(&dir),
                quota,
                unscoped,
                &android_dirs,
                &inu,
            )
        });
        match installed {
            Ok(state) => {
                if let Some(canvas) = engine.canvas.as_ref() {
                    crate::api::canvas::attach_fs(canvas, state.clone());
                }
                engine.fs = Some(state);
            }
            Err(e) => log(&format!("inu.fs failed to install: {e:?}")),
        }
    })
}

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeCanvasResult, canvas,
    (request_id: jlong, result_wire: JString),
    strings(result_wire),
    |engine, state| crate::api::canvas::canvas_result(&engine._rt, &engine.ctx, state, request_id, &result_wire)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeFetchResult, fetch,
    (request_id: jlong, result_wire: JString),
    strings(result_wire),
    |engine, state| crate::api::io::fetch::fetch_result(&engine._rt, &engine.ctx, state, request_id, &result_wire)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeRunTimers, timers,
    (),
    |engine, state| crate::api::timers::run_due(&engine._rt, &engine.ctx, state)
);

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeAppVisibilityChanged(
    _env: EnvUnowned,
    _this: JObject,
    ptr: jlong,
    visible: jboolean,
) {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else {
        return;
    };

    if let Some(state) = engine.timers.as_ref() {
        crate::api::timers::set_visible(state, visible);
    }
    if let Some(state) = engine.lifecycle_state.as_ref() {
        crate::api::lifecycle::app_visibility_changed(&engine._rt, &engine.ctx, state, visible);
    }
}

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeUiRender, ui,
    (page_id: jlong),
    strings(),
    -> jstring |engine, state| crate::api::ui::pages::render_page(&engine._rt, &engine.ctx, state, page_id)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeUiEvent, ui,
    (page_id: jlong, slot: jint, arg_json: JString),
    strings(arg_json),
    |engine, state| crate::api::ui::pages::dispatch_ui_event(&engine._rt, &engine.ctx, state, page_id, slot as u32, &arg_json)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeUiMenuClick, ui,
    (menu_id: jlong, slot: jint),
    |engine, state| crate::api::ui::pages::dispatch_menu_click(&engine._rt, &engine.ctx, state, menu_id, slot)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeUiPageClosed, ui,
    (page_id: jlong),
    |engine, state| crate::api::ui::pages::page_closed(&engine._rt, &engine.ctx, state, page_id)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeRenderActions, actions,
    (kind: jint, surface_json: JString),
    strings(surface_json),
    -> jstring |engine, state| crate::api::ui::actions::render_actions(&engine._rt, &engine.ctx, state, kind, &surface_json)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchAction, actions,
    (kind: jint, token: jint, surface_json: JString),
    strings(surface_json),
    |engine, state| crate::api::ui::actions::dispatch_action(&engine._rt, &engine.ctx, state, kind, token as u32, &surface_json)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeResolvePrompt, ui,
    (request_id: jlong, text: JString),
    opt_strings(text),
    |engine, state| crate::api::ui::pages::resolve_prompt(&engine._rt, &engine.ctx, state, request_id, text.as_deref())
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeResolveChooser, dialogs,
    (request_id: jlong, picked: JString),
    opt_strings(picked),
    |engine, state| crate::api::ui::dialogs::resolve_chooser(&engine._rt, &engine.ctx, state, request_id, picked.as_deref())
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchScreenChange, screens,
    (change_json: JString, stack_json: JString),
    strings(change_json, stack_json),
    |engine, state| crate::api::ui::screens::dispatch_screen_change(&engine._rt, &engine.ctx, state, &change_json, &stack_json)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchNotification, notifications,
    (callback_id: jint, name: JString, account: jint, args_json: JString),
    strings(name, args_json),
    |engine, state| crate::api::platform::notifications::dispatch_notification(&engine._rt, &engine.ctx, state, callback_id as u32, &name, account, &args_json)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeResolveDialog, dialogs,
    (request_id: jlong, result: JString),
    strings(result),
    |engine, state| crate::api::ui::dialogs::resolve_dialog(&engine._rt, &engine.ctx, state, request_id, &result)
);

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeNotifyUnload(
    _env: EnvUnowned,
    _this: JObject,
    ptr: jlong,
) {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else {
        return;
    };
    engine.lifecycle.begin_unload();
    if let Some(state) = engine.account.as_ref() {
        crate::api::telegram::account::notify_unload(&engine._rt, &engine.ctx, state);
    }
    if let Some(state) = engine.lifecycle_state.as_ref() {
        crate::api::lifecycle::notify_unload(&engine._rt, &engine.ctx, state);
    }
    let Some(state) = engine.timers.as_ref() else {
        return;
    };
    crate::api::timers::notify_unload(&engine.ctx, state);
}

engine_export!(Java_desu_inugram_helpers_plugins_QuickJs_nativeAccountsChanged, account, (), |engine, state| {
    crate::api::telegram::account::accounts_changed(&engine._rt, &engine.ctx, state)
});

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeInstallRpc(
    _env: EnvUnowned,
    _this: JObject,
    ptr: jlong,
) {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_mut() }) else {
        return;
    };
    let host: Rc<dyn RpcHost> = engine.bridge.clone();
    let tl = engine.views.clone();
    let grants: Rc<dyn GrantHost> = engine.bridge.clone();
    let log = make_log(engine.bridge.console.clone());

    let lifecycle = engine.lifecycle.clone();
    let accounts = engine.account.clone();
    let Some(shared) = engine.shared.clone() else {
        log("inu.interceptRpc/onUpdate cannot install without inu.utils");
        return;
    };

    let rules_host: Rc<dyn DeserializeHost> = engine.bridge.clone();
    let rules_grants: Rc<dyn GrantHost> = engine.bridge.clone();
    let rules_lifecycle = engine.lifecycle.clone();
    let rules_tl = engine.views.clone();
    let rules_log = log.clone();

    let installed = engine.ctx.with(|ctx| {
        let inu = engine.inu.clone().restore(&ctx)?;
        let rules = crate::api::telegram::deserialize::install_deserialize(
            &ctx,
            rules_host,
            rules_grants,
            rules_lifecycle,
            rules_tl,
            rules_log,
            &inu,
        )?;
        let shared = shared.restore(&ctx)?;
        let rpc = crate::api::telegram::rpc::install_rpc(
            &ctx,
            host,
            tl,
            grants,
            lifecycle,
            accounts,
            shared,
            log.clone(),
            &inu,
        )?;
        Ok::<_, rquickjs::Error>((rules, rpc))
    });
    match installed {
        Ok((rules, state)) => {
            engine.deserialize = Some(rules);
            engine.rpc = Some(state);
            pump(engine);
        }
        Err(e) => log(&format!("inu.interceptRpc/onUpdate failed to install: {e:?}")),
    }
}

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchRpc, rpc,
    (callback_id: jint, dispatch_id: jlong, method: JString, account_id: jint, request_wire: JString),
    strings(method, request_wire),
    |engine, state| crate::api::telegram::rpc::dispatch_rpc(&engine._rt, &engine.ctx, state, callback_id as u32, dispatch_id, &method, account_id, &request_wire)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeCompleteNext, rpc,
    (dispatch_id: jlong, result_wire: JString),
    strings(result_wire),
    |engine, state| crate::api::telegram::rpc::complete_next(&engine._rt, &engine.ctx, state, dispatch_id, &result_wire)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeAbandonDispatch, rpc,
    (dispatch_id: jlong, reason_wire: JString),
    strings(reason_wire),
    |engine, state| crate::api::telegram::rpc::abandon_dispatch(&engine._rt, &engine.ctx, state, dispatch_id, &reason_wire)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeResolveInvoke, rpc,
    (invoke_id: jlong, result_wire: JString),
    strings(result_wire),
    |engine, state| crate::api::telegram::rpc::resolve_invoke(&engine._rt, &engine.ctx, state, invoke_id, &result_wire)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeResolvePeerResult, reads,
    (request_id: jlong, result_wire: JString),
    strings(result_wire),
    |engine, state| crate::api::telegram::reads::resolve_peer_result(&engine._rt, &engine.ctx, state, request_id, &result_wire)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeAccountFetchResult, reads,
    (request_id: jlong, result_wire: JString),
    strings(result_wire),
    |engine, state| crate::api::telegram::reads::account_fetch_result(&engine._rt, &engine.ctx, state, request_id, &result_wire)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeWriteResult, writes,
    (request_id: jlong, result_wire: JString),
    strings(result_wire),
    |engine, state| crate::api::telegram::writes::write_result(&engine._rt, &engine.ctx, state, request_id, &result_wire)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeWriteProgress, writes,
    (request_id: jlong, loaded: jlong, total: jlong),
    |engine, state| crate::api::telegram::writes::write_progress(&engine._rt, &engine.ctx, state, request_id, loaded, total)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchUpdate, rpc,
    (type_name: JString, account_id: jint, update_wire: JString),
    strings(type_name, update_wire),
    |engine, state| crate::api::telegram::rpc::dispatch_update(&engine._rt, &engine.ctx, state, &type_name, account_id, &update_wire)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchDeserialize, deserialize,
    (callback_id: jint, object_wire: JString),
    strings(object_wire),
    |engine, state| crate::api::telegram::deserialize::dispatch_middleware(&engine.ctx, state, callback_id as u32, &object_wire)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchUpdateIntercept, rpc,
    (callback_id: jint, dispatch_id: jlong, type_name: JString, account_id: jint, update_wire: JString),
    strings(type_name, update_wire),
    |engine, state| crate::api::telegram::rpc::dispatch_update_intercept(&engine._rt, &engine.ctx, state, callback_id as u32, dispatch_id, &type_name, account_id, &update_wire)
);

engine_export!(
    Java_desu_inugram_helpers_plugins_QuickJs_nativeAbandonUpdateDispatch, rpc,
    (dispatch_id: jlong),
    |engine, state| crate::api::telegram::rpc::abandon_update_dispatch(&engine._rt, &engine.ctx, state, dispatch_id)
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
        let _deadline = crate::sandbox::limits::arm_entry_deadline();
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
        let _ = engine.ctx.with(|ctx| {
            let inu = engine.inu.clone().restore(&ctx)?;
            install_inu(&ctx, info, &inu)
        });
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
        let _deadline = crate::sandbox::limits::arm_eval_deadline();
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
            crate::api::telegram::rpc::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.deserialize.take() {
            crate::api::telegram::deserialize::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.lifecycle_state.take() {
            crate::api::lifecycle::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.dialogs.take() {
            crate::api::ui::dialogs::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.ui.take() {
            crate::api::ui::pages::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.screens.take() {
            crate::api::ui::screens::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.actions.take() {
            crate::api::ui::actions::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.writes.take() {
            crate::api::telegram::writes::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.reads.take() {
            crate::api::telegram::reads::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.account.take() {
            crate::api::telegram::account::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.fetch.take() {
            crate::api::io::fetch::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.canvas.take() {
            crate::api::canvas::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.timers.take() {
            crate::api::timers::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.notifications.take() {
            crate::api::platform::notifications::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.xposed.take() {
            crate::api::platform::xposed::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.jvm.take() {
            crate::api::platform::jvm::dispose(&engine.ctx, &state);
        }
        if let Some(shared) = engine.shared.take() {
            engine.ctx.with(|ctx| drop(shared.restore(&ctx)));
        }
        let inu = engine.inu;
        engine.ctx.with(|ctx| {
            drop(inu.restore(&ctx));
            crate::api::telegram::rpc::dispose_rejection_tracker(&ctx);
        });
    }
}

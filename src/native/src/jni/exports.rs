use jni::objects::{JClass, JObject, JObjectArray, JString};
use jni::strings::JNIString;
use jni::sys::{jboolean, jclass, jint, jlong, jobject, jstring};
use jni::EnvUnowned;
use rquickjs::{Coerced, Context, Object, Persistent, Result as JsResult, Runtime, Value};
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::sync::Arc;

use super::bridge::JniBridge;
use super::env::{in_env, jstring_to_string, read_header, read_string_array};
use super::log::{install_console, make_log};
use super::{get_engine, insert_engine, pump, remove_engine, Engine};
use crate::api::canvas::{self, install_canvas, CanvasHost};
use crate::api::error::{dispose_rejection_tracker, format_exception, install_plugin_error, install_rejection_tracker};
use crate::api::globals::{install_globals, RandomHost};
use crate::api::info::{install_inu, InuInfo};
use crate::api::io::fetch::{install_fetch, FetchHost};
use crate::api::io::fs::install_fs;
use crate::api::io::kv::install_kv;
use crate::api::lifecycle::install_lifecycle;
use crate::api::platform::clipboard::install_clipboard;
use crate::api::platform::jvm::{self, install_jvm};
use crate::api::platform::notifications::{install_notifications, NotificationHost};
use crate::api::platform::open_url::install_open_url;
use crate::api::platform::xposed::{self, install_xposed};
use crate::api::telegram::account::{install_account, AccountHost};
use crate::api::telegram::deserialize::DeserializeHost;
use crate::api::telegram::reads::{install_reads, ReadsHost};
use crate::api::telegram::rpc::RpcHost;
use crate::api::telegram::writes::{install_writes, WritesDeps, WritesHost};
use crate::api::timers::{install_timers, TimerHost};
use crate::api::tl::message::install_message;
use crate::api::tl::proxy::TlViews;
use crate::api::tl::utils::{install_utils_with_host, UtilsHost};
use crate::api::ui::actions::{install_actions, ActionHost};
use crate::api::ui::dialogs::install_dialogs;
use crate::api::ui::icons::{install_icons, IconHost};
use crate::api::ui::pages::{install_ui, UiHost};
use crate::api::ui::screens::{install_screens, ScreenHost};
use crate::api::Globals;
use crate::sandbox::grants::GrantHost;
use crate::sandbox::limits::{apply_heap_limit, arm_entry_deadline, install_interrupt_handler, ExternalMemory};
use crate::sandbox::registry::Lifecycle;

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeCreate(
  mut env: EnvUnowned,
  _this: JObject,
  listener: JObject,
  spill_dir: JString,
  fs_dir: JString,
  fs_quota_bytes: jlong,
  fs_unscoped: jboolean,
  install_fs: jboolean,
  android_dirs: JString,
  install_jvm: jboolean,
  install_xposed: jboolean,
) -> jlong {
  in_env(&mut env, 0, |env| {
    let Some(bridge) = JniBridge::new(env, &listener) else {
      return 0;
    };

    let Ok(rt) = Runtime::new() else { return 0 };
    let Ok(ctx) = Context::full(&rt) else {
      return 0;
    };

    let installed = ctx.with(|ctx| -> JsResult<()> {
      install_console(&ctx, bridge.clone())?;
      install_plugin_error(&ctx)
    });
    if installed.is_err() {
      return 0;
    }

    install_rejection_tracker(&rt, make_log(bridge.console.clone()));
    let interrupt_log = make_log(bridge.console.clone());
    install_interrupt_handler(&rt, Arc::new(move |msg: &str| interrupt_log(msg)));
    apply_heap_limit(&rt);

    let spill_dir = PathBuf::from(jstring_to_string(env, &spill_dir));
    let fs_dir = PathBuf::from(jstring_to_string(env, &fs_dir));
    let android_dirs = jstring_to_string(env, &android_dirs);
    let install_fs_enabled = install_fs;
    let install_jvm_enabled = install_jvm;
    let install_xposed_enabled = install_xposed;
    let Some(engine) = (|| -> Option<Engine> {
      let _deadline = arm_entry_deadline();
      let lifecycle = Lifecycle::new();
      let log = make_log(bridge.console.clone());
      let views = TlViews::new(bridge.clone());

      let jvm = if install_jvm_enabled {
        Some(install_engine_jvm(&ctx, &bridge, &lifecycle, log.as_ref())?)
      } else {
        None
      };
      let xposed = if install_xposed_enabled {
        Some(install_engine_xposed(&ctx, &bridge, &lifecycle, log.as_ref(), jvm.clone())?)
      } else {
        None
      };

      let grants: Rc<dyn GrantHost> = bridge.clone();
      let lifecycle_state = ctx
        .with(|ctx| {
          let globals = Globals::get(&ctx)?;
          install_lifecycle(&ctx, grants.clone(), lifecycle.clone(), log.clone(), &globals)
        })
        .map_err(|e| log(&format!("inu.onUnload failed to install: {e:?}")))
        .ok()?;
      let dialogs = ctx
        .with(|ctx| {
          let globals = Globals::get(&ctx)?;
          install_kv(&ctx, bridge.clone(), grants.clone(), &globals)?;
          install_clipboard(&ctx, bridge.clone(), grants.clone(), &globals)?;
          install_open_url(&ctx, bridge.clone(), grants.clone(), &globals)?;
          install_dialogs(&ctx, bridge.clone(), log.clone(), &globals)
        })
        .map_err(|e| log(&format!("inu.kv/inu.ui failed to install: {e:?}")))
        .ok()?;
      let account_host: Rc<dyn AccountHost> = bridge.clone();
      let account = ctx
        .with(|ctx| {
          let globals = Globals::get(&ctx)?;
          install_account(&ctx, account_host, grants, lifecycle.clone(), log.clone(), &globals)
        })
        .map_err(|e| log(&format!("inu.account failed to install: {e:?}")))
        .ok()?;
      let ui_host: Rc<dyn UiHost> = bridge.clone();
      let ui = ctx
        .with(|ctx| {
          let globals = Globals::get(&ctx)?;
          install_ui(&ctx, ui_host, lifecycle.clone(), log.clone(), jvm.clone(), &globals)
        })
        .map_err(|e| log(&format!("inu.ui pages failed to install: {e:?}")))
        .ok()?;
      let icon_host: Rc<dyn IconHost> = bridge.clone();
      ctx
        .with(|ctx| {
          let globals = Globals::get(&ctx)?;
          install_icons(&ctx, icon_host, jvm.clone(), &globals)
        })
        .map_err(|e| log(&format!("inu.icons failed to install: {e:?}")))
        .ok()?;
      let action_host: Rc<dyn ActionHost> = bridge.clone();
      let action_grants: Rc<dyn GrantHost> = bridge.clone();
      let actions = ctx
        .with(|ctx| {
          let globals = Globals::get(&ctx)?;
          install_actions(
            &ctx,
            action_host,
            lifecycle.clone(),
            Some(account.clone()),
            action_grants,
            jvm.clone(),
            log.clone(),
            &globals,
          )
        })
        .map_err(|e| log(&format!("inu.register*Action failed to install: {e:?}")))
        .ok()?;
      let screen_host: Rc<dyn ScreenHost> = bridge.clone();
      let screen_grants: Rc<dyn GrantHost> = bridge.clone();
      let screens = ctx
        .with(|ctx| {
          let globals = Globals::get(&ctx)?;
          install_screens(
            &ctx,
            screen_host,
            screen_grants,
            Some(account.clone()),
            lifecycle.clone(),
            log.clone(),
            &globals,
          )
        })
        .map_err(|e| log(&format!("inu.ui navigation failed to install: {e:?}")))
        .ok()?;
      let notification_host: Rc<dyn NotificationHost> = bridge.clone();
      let notification_grants: Rc<dyn GrantHost> = bridge.clone();
      let notifications = ctx
        .with(|ctx| {
          let globals = Globals::get(&ctx)?;
          install_notifications(&ctx, notification_host, notification_grants, lifecycle.clone(), log.clone(), &globals)
        })
        .map_err(|e| log(&format!("inu.android.addNotificationCenterDelegate failed to install: {e:?}")))
        .ok()?;
      let random_host: Rc<dyn RandomHost> = bridge.clone();
      let external = ExternalMemory::new();
      let blobs = ctx
        .with(|ctx| install_globals(&ctx, random_host, &spill_dir, external.clone()))
        .map_err(|e| log(&format!("sandbox globals failed to install: {e:?}")))
        .ok()?;
      let canvas_host: Rc<dyn CanvasHost> = bridge.clone();
      let canvas = ctx
        .with(|ctx| {
          let globals = Globals::get(&ctx)?;
          install_canvas(&ctx, canvas_host, blobs.clone(), external.clone(), spill_dir.clone(), log.clone(), &globals)
        })
        .map_err(|e| log(&format!("inu.canvas failed to install: {e:?}")))
        .ok()?;
      let reads_host: Rc<dyn ReadsHost> = bridge.clone();
      let writes_host: Rc<dyn WritesHost> = bridge.clone();
      let grants: Rc<dyn GrantHost> = bridge.clone();
      let (shared, reads, writes) = ctx
        .with(|ctx| -> rquickjs::Result<_> {
          let globals = Globals::get(&ctx)?;
          let utils_host: Rc<dyn UtilsHost> = bridge.clone();
          let shared = install_utils_with_host(&ctx, utils_host, &globals)?;
          install_message(&ctx, &shared, &globals)?;
          let shared_root = Persistent::save(&ctx, shared.clone());
          let reads =
            install_reads(&ctx, reads_host, grants.clone(), views.clone(), &shared, &account, log.clone(), &globals)?;
          let deps = WritesDeps {
            host: writes_host,
            grants,
            views: views.clone(),
            blobs: blobs.clone(),
            stage_dir: spill_dir.clone(),
            log: log.clone(),
          };
          let writes = install_writes(&ctx, deps, &shared, &account, &globals)?;
          Ok((shared_root, reads, writes))
        })
        .map_err(|e| log(&format!("inu.utils/inu.Message/Account reads+writes failed to install: {e:?}")))
        .ok()?;
      let timer_host: Rc<dyn TimerHost> = bridge.clone();
      let timers = ctx
        .with(|ctx| install_timers(&ctx, timer_host, lifecycle.clone(), log.clone()))
        .map_err(|e| log(&format!("timers failed to install: {e:?}")))
        .ok()?;
      let fetch_host: Rc<dyn FetchHost> = bridge.clone();
      let fetch_grants: Rc<dyn GrantHost> = bridge.clone();
      let fetch = ctx
        .with(|ctx| {
          let globals = Globals::get(&ctx)?;
          install_fetch(&ctx, fetch_host, fetch_grants, blobs.clone(), log.clone(), &globals)
        })
        .map_err(|e| log(&format!("fetch failed to install: {e:?}")))
        .ok()?;
      if install_fs_enabled {
        install_engine_fs(
          &ctx,
          &bridge,
          &blobs,
          &canvas,
          &fs_dir,
          fs_quota_bytes,
          fs_unscoped,
          &android_dirs,
          log.as_ref(),
        )?;
      }
      let (deserialize, rpc) = install_engine_rpc(&ctx, &bridge, &views, &lifecycle, &account, &shared, log.as_ref())?;

      Some(Engine {
        ctx,
        _rt: rt,
        bridge,
        lifecycle,
        shared: Some(shared),
        rpc,
        deserialize,
        lifecycle_state,
        dialogs,
        ui,
        screens,
        actions,
        account,
        reads,
        writes,
        fetch,
        canvas,
        timers,
        notifications,
        jvm,
        xposed,
      })
    })() else {
      return 0;
    };
    pump(&engine);
    insert_engine(engine)
  })
}

fn install_engine_jvm(
  ctx: &Context,
  bridge: &Rc<JniBridge>,
  lifecycle: &Rc<Lifecycle>,
  log: &(dyn Fn(&str) + Send + Sync),
) -> Option<Rc<jvm::JvmState>> {
  ctx
    .with(|ctx| {
      let globals = Globals::get(&ctx)?;
      install_jvm(&ctx, bridge.clone(), bridge.clone(), lifecycle.clone(), make_log(bridge.console.clone()), &globals)
    })
    .map_err(|e| log(&format!("inu.jvm failed to install: {e:?}")))
    .ok()
}

fn install_engine_xposed(
  ctx: &Context,
  bridge: &Rc<JniBridge>,
  lifecycle: &Rc<Lifecycle>,
  log: &(dyn Fn(&str) + Send + Sync),
  jvm: Option<Rc<jvm::JvmState>>,
) -> Option<Rc<xposed::XposedState>> {
  let Some(jvm) = jvm else {
    log("inu.xposed needs inu.jvm, which is not installed");
    return None;
  };
  ctx
    .with(|ctx| {
      let globals = Globals::get(&ctx)?;
      install_xposed(
        &ctx,
        bridge.clone(),
        bridge.clone(),
        lifecycle.clone(),
        jvm,
        make_log(bridge.console.clone()),
        &globals,
      )
    })
    .map_err(|e| log(&format!("inu.xposed failed to install: {e:?}")))
    .ok()
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
    let _deadline = arm_entry_deadline();
    let method_wire = jstring_to_string(env, &method_wire);
    let this_wire = jstring_to_string(env, &this_wire);
    let args = read_string_array(env, &args);
    let Some(engine) = get_engine(ptr) else {
      return std::ptr::null_mut();
    };
    let answer = match engine.xposed.as_ref() {
      Some(state) => state.dispatch_before(
        &engine._rt,
        &engine.ctx,
        dispatch_id,
        site,
        &xposed::Invocation {
          method: &method_wire,
          this: &this_wire,
          args: &args,
        },
      ),
      None => std::iter::once("P0".to_string()).chain(args.iter().cloned()).collect(),
    };
    match JniBridge::new_jstring_array(env, "xposedBefore", &answer) {
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
    let _deadline = arm_entry_deadline();
    let result = jstring_to_string(env, &result);
    let answer = match get_engine(ptr) {
      Some(engine) => match engine.xposed.as_ref() {
        Some(state) => state.dispatch_after(&engine._rt, &engine.ctx, dispatch_id, &result),
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
  xposed::HOOK_BUDGET_MS
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeXposedRelease(
  _env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  dispatch_id: jlong,
) {
  let _deadline = arm_entry_deadline();
  let Some(engine) = get_engine(ptr) else {
    return;
  };
  let Some(state) = engine.xposed.as_ref() else {
    return;
  };
  state.release_dispatch(&engine.ctx, dispatch_id);
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_platform_PluginXposed_00024Native_nativeInit(
  mut env: EnvUnowned,
  _this: JObject,
) -> jboolean {
  in_env(&mut env, false, |env| xposed::lsplant::init(env))
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
    xposed::lsplant::hook(env, target.as_raw(), hooker.as_raw(), callback.as_raw())
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_platform_PluginXposed_00024Native_nativeUnhook<'local>(
  mut env: EnvUnowned<'local>,
  _this: JObject<'local>,
  target: JObject<'local>,
) -> jboolean {
  in_env(&mut env, false, |env| unsafe { xposed::lsplant::unhook(env, target.as_raw()) })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_platform_PluginXposed_00024Native_nativeIsHooked<'local>(
  mut env: EnvUnowned<'local>,
  _this: JObject<'local>,
  target: JObject<'local>,
) -> jboolean {
  in_env(&mut env, false, |env| unsafe { xposed::lsplant::is_hooked(env, target.as_raw()) })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_platform_PluginXposed_00024Native_nativeDeoptimize<'local>(
  mut env: EnvUnowned<'local>,
  _this: JObject<'local>,
  method: JObject<'local>,
) -> jboolean {
  in_env(&mut env, false, |env| unsafe { xposed::lsplant::deoptimize(env, method.as_raw()) })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_platform_PluginXposed_00024Native_nativeMakeInheritable<
  'local,
>(
  mut env: EnvUnowned<'local>,
  _this: JObject<'local>,
  target: JObject<'local>,
) -> jboolean {
  in_env(&mut env, false, |env| unsafe { xposed::lsplant::make_inheritable(env, target.as_raw()) })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_platform_PluginXposed_00024Native_nativeAllocateInstance<
  'local,
>(
  mut env: EnvUnowned<'local>,
  _this: JObject<'local>,
  target: JObject<'local>,
) -> jobject {
  in_env(&mut env, std::ptr::null_mut(), |env| {
    let _deadline = arm_entry_deadline();
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
    let _deadline = arm_entry_deadline();
    xposed::lsplant::disable_profile_saver()
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeJvmCallback(
  _env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  callback_id: jint,
) {
  let _deadline = arm_entry_deadline();
  let Some(engine) = get_engine(ptr) else {
    return;
  };
  let Some(state) = engine.jvm.as_ref() else {
    return;
  };
  state.dispatch_callback(&engine._rt, &engine.ctx, callback_id as u32);
}

fn install_engine_fs(
  ctx: &Context,
  bridge: &Rc<JniBridge>,
  blobs: &Rc<crate::api::io::blob::BlobState>,
  canvas: &Rc<canvas::CanvasState>,
  dir: &Path,
  quota_bytes: jlong,
  unscoped: jboolean,
  android_dirs: &str,
  log: &(dyn Fn(&str) + Send + Sync),
) -> Option<()> {
  let grants: Rc<dyn GrantHost> = bridge.clone();
  let quota = if quota_bytes < 0 {
    crate::api::io::fs::UNCAPPED
  } else if quota_bytes == 0 {
    crate::api::io::fs::DEFAULT_QUOTA_BYTES
  } else {
    quota_bytes as u64
  };
  let state = ctx
    .with(|ctx| {
      let globals = Globals::get(&ctx)?;
      install_fs(&ctx, grants, blobs.clone(), dir, quota, unscoped, android_dirs, &globals)
    })
    .map_err(|e| log(&format!("inu.fs failed to install: {e:?}")))
    .ok()?;
  canvas.attach_fs(state);
  Some(())
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeCanvasResult(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  request_id: jlong,
  result_wire: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.canvas;
    let result_wire = jstring_to_string(env, &result_wire);
    state.resolve(&engine._rt, &engine.ctx, request_id, &result_wire);
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeFetchResult(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  request_id: jlong,
  result_wire: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.fetch;
    let result_wire = jstring_to_string(env, &result_wire);
    state.resolve(&engine._rt, &engine.ctx, request_id, &result_wire);
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeRunTimers(
  _env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
) {
  let _deadline = crate::sandbox::limits::arm_entry_deadline();
  let Some(engine) = get_engine(ptr) else {
    return;
  };
  let state = &engine.timers;
  state.run_due(&engine._rt, &engine.ctx);
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeAppVisibilityChanged(
  _env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  visible: jboolean,
) {
  let _deadline = arm_entry_deadline();
  let Some(engine) = get_engine(ptr) else {
    return;
  };

  engine.timers.set_visible(visible);
  engine.lifecycle_state.app_visibility_changed(&engine._rt, &engine.ctx, visible);
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeUiRender(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  page_id: jlong,
) -> jstring {
  in_env(&mut env, std::ptr::null_mut(), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return std::ptr::null_mut();
    };
    let state = &engine.ui;
    match state.render(&engine._rt, &engine.ctx, page_id) {
      Some(json) => env.new_string(json).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut()),
      None => std::ptr::null_mut(),
    }
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeUiEvent(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  page_id: jlong,
  slot: jint,
  arg_json: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.ui;
    let arg_json = jstring_to_string(env, &arg_json);
    state.dispatch_event(&engine._rt, &engine.ctx, page_id, slot as u32, &arg_json);
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeUiMenuClick(
  _env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  menu_id: jlong,
  slot: jint,
) {
  let _deadline = crate::sandbox::limits::arm_entry_deadline();
  let Some(engine) = get_engine(ptr) else {
    return;
  };
  let state = &engine.ui;
  state.dispatch_menu_click(&engine._rt, &engine.ctx, menu_id, slot);
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeUiPageClosed(
  _env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  page_id: jlong,
) {
  let _deadline = crate::sandbox::limits::arm_entry_deadline();
  let Some(engine) = get_engine(ptr) else {
    return;
  };
  let state = &engine.ui;
  state.close_page(&engine._rt, &engine.ctx, page_id);
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeRenderActions(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  kind: jint,
  surface_json: JString,
) -> jstring {
  in_env(&mut env, std::ptr::null_mut(), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return std::ptr::null_mut();
    };
    let state = &engine.actions;
    let surface_json = jstring_to_string(env, &surface_json);
    match state.render(&engine._rt, &engine.ctx, kind, &surface_json) {
      Some(json) => env.new_string(json).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut()),
      None => std::ptr::null_mut(),
    }
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchAction(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  kind: jint,
  token: jint,
  surface_json: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.actions;
    let surface_json = jstring_to_string(env, &surface_json);
    state.dispatch(&engine._rt, &engine.ctx, kind, token as u32, &surface_json);
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeResolvePrompt(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  request_id: jlong,
  text: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.ui;
    let text = if text.is_null() { None } else { Some(jstring_to_string(env, &text)) };
    state.resolve_prompt(&engine._rt, &engine.ctx, request_id, text.as_deref());
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeResolveChooser(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  request_id: jlong,
  picked: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.dialogs;
    let picked = if picked.is_null() { None } else { Some(jstring_to_string(env, &picked)) };
    state.resolve_chooser(&engine._rt, &engine.ctx, request_id, picked.as_deref());
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchScreenChange(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  change_json: JString,
  stack_json: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.screens;
    let change_json = jstring_to_string(env, &change_json);
    let stack_json = jstring_to_string(env, &stack_json);
    state.dispatch_change(&engine._rt, &engine.ctx, &change_json, &stack_json);
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchNotification(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  callback_id: jint,
  name: JString,
  account: jint,
  args_json: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.notifications;
    let name = jstring_to_string(env, &name);
    let args_json = jstring_to_string(env, &args_json);
    state.dispatch(&engine._rt, &engine.ctx, callback_id as u32, &name, account, &args_json);
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeResolveDialog(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  request_id: jlong,
  result: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.dialogs;
    let result = jstring_to_string(env, &result);
    state.resolve_dialog(&engine._rt, &engine.ctx, request_id, &result);
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeNotifyUnload(
  _env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
) {
  let _deadline = arm_entry_deadline();
  let Some(engine) = get_engine(ptr) else {
    return;
  };
  engine.lifecycle.begin_unload();
  engine.account.notify_unload(&engine._rt, &engine.ctx);
  engine.lifecycle_state.notify_unload(&engine._rt, &engine.ctx);
  engine.timers.notify_unload(&engine.ctx);
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeAccountsChanged(
  _env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
) {
  let _deadline = crate::sandbox::limits::arm_entry_deadline();
  let Some(engine) = get_engine(ptr) else {
    return;
  };
  let state = &engine.account;
  state.accounts_changed(&engine._rt, &engine.ctx);
}

fn install_engine_rpc(
  ctx: &Context,
  bridge: &Rc<JniBridge>,
  views: &Rc<TlViews>,
  lifecycle: &Rc<Lifecycle>,
  account: &Rc<crate::api::telegram::account::AccountState>,
  shared: &Persistent<Object<'static>>,
  log: &(dyn Fn(&str) + Send + Sync),
) -> Option<(Rc<crate::api::telegram::deserialize::DeserializeState>, Rc<crate::api::telegram::rpc::RpcState>)> {
  let host: Rc<dyn RpcHost> = bridge.clone();
  let tl = views.clone();
  let grants: Rc<dyn GrantHost> = bridge.clone();
  let rules_host: Rc<dyn DeserializeHost> = bridge.clone();
  let rules_grants: Rc<dyn GrantHost> = bridge.clone();
  let rules_lifecycle = lifecycle.clone();
  let rules_tl = views.clone();
  let rules_log = make_log(bridge.console.clone());
  ctx
    .with(|ctx| {
      let globals = Globals::get(&ctx)?;
      let rules = crate::api::telegram::deserialize::install_deserialize(
        &ctx,
        rules_host,
        rules_grants,
        rules_lifecycle,
        rules_tl,
        rules_log,
        &globals,
      )?;
      let shared = shared.clone().restore(&ctx)?;
      let rpc = crate::api::telegram::rpc::install_rpc(
        &ctx,
        host,
        tl,
        grants,
        lifecycle.clone(),
        Some(account.clone()),
        shared,
        make_log(bridge.console.clone()),
        &globals,
      )?;
      Ok::<_, rquickjs::Error>((rules, rpc))
    })
    .map_err(|e| log(&format!("inu.interceptRpc/onUpdate failed to install: {e:?}")))
    .ok()
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchRpc(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  callback_id: jint,
  dispatch_id: jlong,
  method: JString,
  account_id: jint,
  request_wire: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.rpc;
    let method = jstring_to_string(env, &method);
    let request_wire = jstring_to_string(env, &request_wire);
    state.dispatch(&engine._rt, &engine.ctx, callback_id as u32, dispatch_id, &method, account_id, &request_wire);
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeCompleteNext(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  dispatch_id: jlong,
  result_wire: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.rpc;
    let result_wire = jstring_to_string(env, &result_wire);
    state.complete_next(&engine._rt, &engine.ctx, dispatch_id, &result_wire);
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeAbandonDispatch(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  dispatch_id: jlong,
  reason_wire: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.rpc;
    let reason_wire = jstring_to_string(env, &reason_wire);
    state.abandon_dispatch(&engine._rt, &engine.ctx, dispatch_id, &reason_wire);
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeResolveInvoke(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  invoke_id: jlong,
  result_wire: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.rpc;
    let result_wire = jstring_to_string(env, &result_wire);
    state.resolve_invoke(&engine._rt, &engine.ctx, invoke_id, &result_wire);
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeResolvePeerResult(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  request_id: jlong,
  result_wire: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.reads;
    let result_wire = jstring_to_string(env, &result_wire);
    state.resolve_peer(&engine._rt, &engine.ctx, request_id, &result_wire);
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeAccountFetchResult(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  request_id: jlong,
  result_wire: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.reads;
    let result_wire = jstring_to_string(env, &result_wire);
    state.resolve_account_fetch(&engine._rt, &engine.ctx, request_id, &result_wire);
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeWriteResult(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  request_id: jlong,
  result_wire: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.writes;
    let result_wire = jstring_to_string(env, &result_wire);
    state.resolve_write(&engine._rt, &engine.ctx, request_id, &result_wire);
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeWriteProgress(
  _env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  request_id: jlong,
  loaded: jlong,
  total: jlong,
) {
  let _deadline = crate::sandbox::limits::arm_entry_deadline();
  let Some(engine) = get_engine(ptr) else {
    return;
  };
  let state = &engine.writes;
  state.report_progress(&engine._rt, &engine.ctx, request_id, loaded, total);
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchUpdate(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  type_name: JString,
  account_id: jint,
  update_wire: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.rpc;
    let type_name = jstring_to_string(env, &type_name);
    let update_wire = jstring_to_string(env, &update_wire);
    state.dispatch_update(&engine._rt, &engine.ctx, &type_name, account_id, &update_wire);
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchDeserialize(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  callback_id: jint,
  object_wire: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.deserialize;
    let object_wire = jstring_to_string(env, &object_wire);
    state.dispatch(&engine.ctx, callback_id as u32, &object_wire);
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchUpdateIntercept(
  mut env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  callback_id: jint,
  dispatch_id: jlong,
  type_name: JString,
  account_id: jint,
  update_wire: JString,
) {
  in_env(&mut env, (), |env| {
    let _deadline = crate::sandbox::limits::arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
      return;
    };
    let state = &engine.rpc;
    let type_name = jstring_to_string(env, &type_name);
    let update_wire = jstring_to_string(env, &update_wire);
    state.dispatch_update_intercept(
      &engine._rt,
      &engine.ctx,
      callback_id as u32,
      dispatch_id,
      &type_name,
      account_id,
      &update_wire,
    );
  })
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeAbandonUpdateDispatch(
  _env: EnvUnowned,
  _this: JObject,
  ptr: jlong,
  dispatch_id: jlong,
) {
  let _deadline = crate::sandbox::limits::arm_entry_deadline();
  let Some(engine) = get_engine(ptr) else {
    return;
  };
  let state = &engine.rpc;
  state.abandon_update_dispatch(&engine._rt, &engine.ctx, dispatch_id);
}

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
    let _deadline = arm_entry_deadline();
    let Some(engine) = get_engine(ptr) else {
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
      let globals = Globals::get(&ctx)?;
      install_inu(&ctx, info, &globals)
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
    let Some(engine) = get_engine(ptr) else {
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

    pump(&engine);

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
  if let Some(mut engine) = remove_engine(ptr) {
    engine.rpc.dispose(&engine.ctx);
    engine.deserialize.dispose(&engine.ctx);
    engine.lifecycle_state.dispose(&engine.ctx);
    engine.dialogs.dispose(&engine.ctx);
    engine.ui.dispose(&engine.ctx);
    engine.screens.dispose(&engine.ctx);
    engine.actions.dispose(&engine.ctx);
    engine.writes.dispose(&engine.ctx);
    engine.reads.dispose(&engine.ctx);
    engine.account.dispose(&engine.ctx);
    engine.fetch.dispose(&engine.ctx);
    engine.canvas.dispose(&engine.ctx);
    engine.timers.dispose(&engine.ctx);
    engine.notifications.dispose(&engine.ctx);
    if let Some(state) = engine.xposed.as_ref() {
      state.dispose(&engine.ctx);
    }
    if let Some(state) = engine.jvm.as_ref() {
      state.dispose(&engine.ctx);
    }
    engine.ctx.with(|ctx| {
      drop(engine.shared.take().unwrap().restore(&ctx));
      drop(ctx.remove_userdata::<Globals>().unwrap());
      dispose_rejection_tracker(&ctx);
    });
  }
}

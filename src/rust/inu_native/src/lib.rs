//! JNI bridge for the inugram plugin engine (desu.inugram.helpers.plugins.QuickJs), backed by
//! rquickjs (quickjs-ng). Exposes `console.*`, `inu.info()`, the rpc bridge (`rpc.rs`) and the
//! kv/ui/onUnload api surface (`api.rs`).
//!
//! Thread model: a context is created, used and destroyed on a single thread (PluginManager funnels
//! every engine op through Utilities.globalQueue). rquickjs Context is !Send; we only ever touch the
//! boxed Engine from that one thread, so the raw-pointer handoff across JNI is sound.

use std::rc::Rc;
use std::sync::Arc;

use jni::objects::{GlobalRef, JMethodID, JObject, JObjectArray, JString, JValue};
use jni::signature::{Primitive, ReturnType};
use jni::sys::{jboolean, jint, jlong, jstring, jvalue};
use jni::{JNIEnv, JavaVM};

use rquickjs::function::Rest;
use rquickjs::{Coerced, Context, Ctx, Function, Object, Runtime, Value};

mod api;
mod rpc;
mod tl_proxy;
mod ui;
use api::ApiHost;
use rpc::RpcHost;
use tl_proxy::TlHost;
use ui::UiHost;

struct Engine {
    ctx: Context,
    _rt: Runtime,
    bridge: Rc<JniBridge>,
    rpc: Option<Rc<rpc::RpcState>>,
    api: Option<Rc<api::ApiState>>,
    ui: Option<Rc<ui::UiState>>,
}

/// clears (and logcat-dumps, via ExceptionDescribe) any pending Java exception; true if one was
/// pending. MUST run after every JNI upcall - most JNI calls are UB while an exception is pending,
/// and an uncleared one would fire at an arbitrary later JNI call instead of its source.
fn clear_exception(env: &mut JNIEnv) -> bool {
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
        true
    } else {
        false
    }
}

/// The single JNI upcall surface into the Java `QuickJs` object: one GlobalRef + jmethodIDs cached
/// once at [`Java_desu_inugram_helpers_plugins_QuickJs_nativeCreate`] (jmethodIDs are stable for
/// the lifetime of the class, and proxy traps hit these on every field access - per-call name
/// lookups were measurable pure waste).
///
/// Failure policy is fail-closed: a JNI-level failure or a throwing Java listener surfaces as an
/// *error* to JS (never as "null == ok, proceed"), and the pending Java exception is always
/// cleared before this thread touches JNI again.
struct JniBridge {
    vm: JavaVM,
    target: GlobalRef,
    on_console: JMethodID,
    on_rpc_register: JMethodID,
    on_invoke_rpc: JMethodID,
    on_rpc_next: JMethodID,
    on_rpc_complete: JMethodID,
    on_update_register: JMethodID,
    on_tl_get: JMethodID,
    on_tl_set: JMethodID,
    on_tl_has: JMethodID,
    on_tl_own_keys: JMethodID,
    on_tl_copy: JMethodID,
    on_tl_release: JMethodID,
    on_kv: JMethodID,
    on_ui_toast: JMethodID,
    on_ui_dialog: JMethodID,
    on_ui_prompt: JMethodID,
    on_ui_open_page: JMethodID,
    on_ui_register_settings: JMethodID,
    on_ui_invalidate: JMethodID,
    on_ui_open_menu: JMethodID,
}

impl JniBridge {
    fn new(env: &mut JNIEnv, this: &JObject) -> Option<Rc<JniBridge>> {
        let vm = env.get_java_vm().ok()?;
        let target = env.new_global_ref(this).ok()?;
        let class = env.get_object_class(this).ok()?;
        let mut method = |name: &str, sig: &str| env.get_method_id(&class, name, sig).ok();
        let bridge = JniBridge {
            on_console: method("onConsole", "(ILjava/lang/String;)V")?,
            on_rpc_register: method("onRpcRegister", "([Ljava/lang/String;I)Ljava/lang/String;")?,
            on_invoke_rpc: method("onInvokeRpc", "(JLjava/lang/String;)Ljava/lang/String;")?,
            on_rpc_next: method("onRpcNext", "(JLjava/lang/String;)Ljava/lang/String;")?,
            on_rpc_complete: method("onRpcComplete", "(JLjava/lang/String;)V")?,
            on_update_register: method("onUpdateRegister", "(I)Ljava/lang/String;")?,
            on_tl_get: method("onTlGet", "(JLjava/lang/String;)Ljava/lang/String;")?,
            on_tl_set: method("onTlSet", "(JLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;")?,
            on_tl_has: method("onTlHas", "(JLjava/lang/String;)I")?,
            on_tl_own_keys: method("onTlOwnKeys", "(J)Ljava/lang/String;")?,
            on_tl_copy: method("onTlCopy", "(J)Ljava/lang/String;")?,
            on_tl_release: method("onTlRelease", "(J)V")?,
            on_kv: method("onKv", "(ILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;")?,
            on_ui_toast: method("onUiToast", "(Ljava/lang/String;)V")?,
            on_ui_dialog: method("onUiDialog", "(JLjava/lang/String;)Ljava/lang/String;")?,
            on_ui_prompt: method("onUiPrompt", "(JLjava/lang/String;)Ljava/lang/String;")?,
            on_ui_open_page: method("onUiOpenPage", "(J)Ljava/lang/String;")?,
            on_ui_register_settings: method("onUiRegisterSettings", "(J)V")?,
            on_ui_invalidate: method("onUiInvalidate", "(J)V")?,
            on_ui_open_menu: method("onUiOpenMenu", "(JLjava/lang/String;)Ljava/lang/String;")?,
            vm,
            target,
        };
        Some(Rc::new(bridge))
    }

    /// `Ok(None)` = Java returned null, `Ok(Some)` = a string, `Err(msg)` = JNI failure or a
    /// throwing Java listener - callers map `Err` onto their own error channel, never onto success
    fn call_string(&self, what: &str, method: JMethodID, args: &[jvalue]) -> Result<Option<String>, String> {
        let Ok(mut env) = self.vm.get_env() else {
            return Err(format!("{what}: JNI env unavailable"));
        };
        let result = unsafe { env.call_method_unchecked(&self.target, method, ReturnType::Object, args) };
        if clear_exception(&mut env) {
            return Err(format!("{what}: host callback threw"));
        }
        let obj = result
            .and_then(|v| v.l())
            .map_err(|e| format!("{what}: {e}"))?;
        if obj.is_null() {
            return Ok(None);
        }
        env.get_string(&JString::from(obj))
            .map(|j| Some(j.into()))
            .map_err(|e| format!("{what}: {e}"))
    }

    fn call_void(&self, method: JMethodID, args: &[jvalue]) {
        let Ok(mut env) = self.vm.get_env() else { return };
        let _ = unsafe { env.call_method_unchecked(&self.target, method, ReturnType::Primitive(Primitive::Void), args) };
        clear_exception(&mut env);
    }

    fn call_int(&self, method: JMethodID, args: &[jvalue], fallback: i32) -> i32 {
        let Ok(mut env) = self.vm.get_env() else { return fallback };
        let result = unsafe { env.call_method_unchecked(&self.target, method, ReturnType::Primitive(Primitive::Int), args) };
        if clear_exception(&mut env) {
            return fallback;
        }
        result.and_then(|v| v.i()).unwrap_or(fallback)
    }

    /// console.* / engine diagnostics -> QuickJs.onConsole(level, message)
    fn emit_console(&self, level: i32, message: &str) {
        let Ok(mut env) = self.vm.get_env() else { return };
        let Ok(jmsg) = env.new_string(message) else {
            clear_exception(&mut env);
            return;
        };
        let args = [JValue::Int(level).as_jni(), JValue::Object(&jmsg).as_jni()];
        self.call_void(self.on_console, &args);
    }

    /// allocates a Java string or reports the failure through `on_fail`'s error message
    fn new_jstring<'l>(&self, env: &mut JNIEnv<'l>, what: &str, s: &str) -> Result<jni::objects::JString<'l>, String> {
        match env.new_string(s) {
            Ok(j) => Ok(j),
            Err(e) => {
                clear_exception(env);
                Err(format!("{what}: {e}"))
            }
        }
    }
}

impl RpcHost for JniBridge {
    fn on_register(&self, methods: &[String], callback_id: u32) -> Option<String> {
        let Ok(mut env) = self.vm.get_env() else {
            return Some("interceptRpc: JNI env unavailable".to_string());
        };
        let arr = match env.new_object_array(methods.len() as i32, "java/lang/String", JObject::null()) {
            Ok(a) => a,
            Err(e) => {
                clear_exception(&mut env);
                return Some(format!("interceptRpc: {e}"));
            }
        };
        for (i, m) in methods.iter().enumerate() {
            let jm = match self.new_jstring(&mut env, "interceptRpc", m) {
                Ok(j) => j,
                Err(e) => return Some(e),
            };
            if let Err(e) = env.set_object_array_element(&arr, i as i32, &jm) {
                clear_exception(&mut env);
                return Some(format!("interceptRpc: {e}"));
            }
        }
        let args = [JValue::Object(&arr).as_jni(), JValue::Int(callback_id as i32).as_jni()];
        self.call_string("interceptRpc", self.on_rpc_register, &args)
            .unwrap_or_else(Some)
    }

    fn on_invoke(&self, invoke_id: i64, request_wire: &str) -> Option<String> {
        let Ok(mut env) = self.vm.get_env() else {
            return Some("invokeRpc: JNI env unavailable".to_string());
        };
        let jwire = match self.new_jstring(&mut env, "invokeRpc", request_wire) {
            Ok(j) => j,
            Err(e) => return Some(e),
        };
        let args = [JValue::Long(invoke_id).as_jni(), JValue::Object(&jwire).as_jni()];
        self.call_string("invokeRpc", self.on_invoke_rpc, &args)
            .unwrap_or_else(Some)
    }

    fn on_next(&self, dispatch_id: i64, request_wire: &str) -> Option<String> {
        let Ok(mut env) = self.vm.get_env() else {
            return Some("next(): JNI env unavailable".to_string());
        };
        let jwire = match self.new_jstring(&mut env, "next()", request_wire) {
            Ok(j) => j,
            Err(e) => return Some(e),
        };
        let args = [JValue::Long(dispatch_id).as_jni(), JValue::Object(&jwire).as_jni()];
        self.call_string("next()", self.on_rpc_next, &args)
            .unwrap_or_else(Some)
    }

    fn on_complete(&self, dispatch_id: i64, result_wire: &str) {
        let Ok(mut env) = self.vm.get_env() else { return };
        let Ok(jwire) = env.new_string(result_wire) else {
            clear_exception(&mut env);
            return;
        };
        let args = [JValue::Long(dispatch_id).as_jni(), JValue::Object(&jwire).as_jni()];
        self.call_void(self.on_rpc_complete, &args);
    }

    fn on_update_register(&self, callback_id: u32) -> Option<String> {
        let args = [JValue::Int(callback_id as i32).as_jni()];
        self.call_string("onUpdate", self.on_update_register, &args)
            .unwrap_or_else(Some)
    }
}

impl TlHost for JniBridge {
    fn tl_get(&self, handle: i64, key: &str) -> String {
        let Ok(mut env) = self.vm.get_env() else {
            return tl_proxy::encode_error("tlGet: JNI env unavailable");
        };
        let jkey = match self.new_jstring(&mut env, "tlGet", key) {
            Ok(j) => j,
            Err(e) => return tl_proxy::encode_error(&e),
        };
        let args = [JValue::Long(handle).as_jni(), JValue::Object(&jkey).as_jni()];
        match self.call_string("tlGet", self.on_tl_get, &args) {
            Ok(Some(wire)) => wire,
            Ok(None) => tl_proxy::encode_error("tlGet: host returned null"),
            Err(e) => tl_proxy::encode_error(&e),
        }
    }

    fn tl_set(&self, handle: i64, key: &str, value_wire: &str) -> Option<String> {
        let Ok(mut env) = self.vm.get_env() else {
            return Some("tlSet: JNI env unavailable".to_string());
        };
        let jkey = match self.new_jstring(&mut env, "tlSet", key) {
            Ok(j) => j,
            Err(e) => return Some(e),
        };
        let jvalue = match self.new_jstring(&mut env, "tlSet", value_wire) {
            Ok(j) => j,
            Err(e) => return Some(e),
        };
        let args = [
            JValue::Long(handle).as_jni(),
            JValue::Object(&jkey).as_jni(),
            JValue::Object(&jvalue).as_jni(),
        ];
        self.call_string("tlSet", self.on_tl_set, &args)
            .unwrap_or_else(Some)
    }

    fn tl_has(&self, handle: i64, key: &str) -> i32 {
        let Ok(mut env) = self.vm.get_env() else { return -1 };
        let Ok(jkey) = env.new_string(key) else {
            clear_exception(&mut env);
            return -1;
        };
        let args = [JValue::Long(handle).as_jni(), JValue::Object(&jkey).as_jni()];
        self.call_int(self.on_tl_has, &args, -1)
    }

    fn tl_own_keys(&self, handle: i64) -> Option<String> {
        let args = [JValue::Long(handle).as_jni()];
        // Err (JNI failure / throwing listener) folds into None: the caller's expired path, which
        // throws into JS - still fail-closed, just with a less precise message (the exception
        // itself has already been dumped to logcat by clear_exception)
        self.call_string("tlOwnKeys", self.on_tl_own_keys, &args).ok().flatten()
    }

    fn tl_copy(&self, handle: i64) -> Option<String> {
        let args = [JValue::Long(handle).as_jni()];
        self.call_string("tlCopy", self.on_tl_copy, &args).ok().flatten()
    }

    fn tl_release(&self, handle: i64) {
        let args = [JValue::Long(handle).as_jni()];
        self.call_void(self.on_tl_release, &args);
    }
}

impl ApiHost for JniBridge {
    fn kv(&self, op: i32, key: &str, value: &str) -> String {
        let Ok(mut env) = self.vm.get_env() else {
            return tl_proxy::encode_error("kv: JNI env unavailable");
        };
        let jkey = match self.new_jstring(&mut env, "kv", key) {
            Ok(j) => j,
            Err(e) => return tl_proxy::encode_error(&e),
        };
        let jvalue = match self.new_jstring(&mut env, "kv", value) {
            Ok(j) => j,
            Err(e) => return tl_proxy::encode_error(&e),
        };
        let args = [
            JValue::Int(op).as_jni(),
            JValue::Object(&jkey).as_jni(),
            JValue::Object(&jvalue).as_jni(),
        ];
        match self.call_string("kv", self.on_kv, &args) {
            Ok(Some(wire)) => wire,
            Ok(None) => tl_proxy::encode_error("kv: host returned null"),
            Err(e) => tl_proxy::encode_error(&e),
        }
    }

    fn ui_toast(&self, text: &str) {
        let Ok(mut env) = self.vm.get_env() else { return };
        let Ok(jtext) = env.new_string(text) else {
            clear_exception(&mut env);
            return;
        };
        let args = [JValue::Object(&jtext).as_jni()];
        self.call_void(self.on_ui_toast, &args);
    }

    fn ui_dialog(&self, request_id: i64, options_json: &str) -> Option<String> {
        let Ok(mut env) = self.vm.get_env() else {
            return Some("dialog: JNI env unavailable".to_string());
        };
        let jjson = match self.new_jstring(&mut env, "dialog", options_json) {
            Ok(j) => j,
            Err(e) => return Some(e),
        };
        let args = [JValue::Long(request_id).as_jni(), JValue::Object(&jjson).as_jni()];
        self.call_string("dialog", self.on_ui_dialog, &args)
            .unwrap_or_else(Some)
    }
}

impl UiHost for JniBridge {
    fn ui_prompt(&self, request_id: i64, options_json: &str) -> Option<String> {
        let Ok(mut env) = self.vm.get_env() else {
            return Some("prompt: JNI env unavailable".to_string());
        };
        let jjson = match self.new_jstring(&mut env, "prompt", options_json) {
            Ok(j) => j,
            Err(e) => return Some(e),
        };
        let args = [JValue::Long(request_id).as_jni(), JValue::Object(&jjson).as_jni()];
        self.call_string("prompt", self.on_ui_prompt, &args)
            .unwrap_or_else(Some)
    }

    fn ui_open_page(&self, page_id: i64) -> Option<String> {
        let args = [JValue::Long(page_id).as_jni()];
        self.call_string("openPage", self.on_ui_open_page, &args)
            .unwrap_or_else(Some)
    }

    fn ui_register_settings(&self, page_id: i64) {
        let args = [JValue::Long(page_id).as_jni()];
        self.call_void(self.on_ui_register_settings, &args);
    }

    fn ui_invalidate(&self, page_id: i64) {
        let args = [JValue::Long(page_id).as_jni()];
        self.call_void(self.on_ui_invalidate, &args);
    }

    fn ui_open_menu(&self, menu_id: i64, items_json: &str) -> Option<String> {
        let Ok(mut env) = self.vm.get_env() else {
            return Some("openMenu: JNI env unavailable".to_string());
        };
        let jjson = match self.new_jstring(&mut env, "openMenu", items_json) {
            Ok(j) => j,
            Err(e) => return Some(e),
        };
        let args = [JValue::Long(menu_id).as_jni(), JValue::Object(&jjson).as_jni()];
        self.call_string("openMenu", self.on_ui_open_menu, &args)
            .unwrap_or_else(Some)
    }
}

/// backing data for inu.info(); built fresh into a JS object on each call
struct InuInfo {
    app_version: String,
    app_build: String,
    api_version: i32,
    layer: i32,
    language: String,
    header: Vec<(String, String)>,
}

fn install_console(ctx: &Ctx, bridge: Rc<JniBridge>) -> rquickjs::Result<()> {
    let console = Object::new(ctx.clone())?;
    for (name, level) in [("log", 0), ("info", 1), ("warn", 2), ("error", 3), ("debug", 4)] {
        let bridge = bridge.clone();
        let f = Function::new(ctx.clone(), move |args: Rest<Coerced<String>>| {
            let joined = args
                .0
                .iter()
                .map(|c| c.0.as_str())
                .collect::<Vec<_>>()
                .join(" ");
            bridge.emit_console(level, &joined);
        })?;
        console.set(name, f)?;
    }
    ctx.globals().set("console", console)?;
    Ok(())
}

fn build_info_object<'js>(ctx: Ctx<'js>, info: &InuInfo) -> rquickjs::Result<Object<'js>> {
    let obj = Object::new(ctx.clone())?;
    obj.set("platform", "android")?;
    obj.set("appVersion", info.app_version.as_str())?;
    obj.set("appBuild", info.app_build.as_str())?;
    obj.set("apiVersion", info.api_version)?;
    obj.set("layer", info.layer)?;
    obj.set("language", info.language.as_str())?;
    let header = Object::new(ctx.clone())?;
    for (k, v) in info.header.iter() {
        header.set(k.as_str(), v.as_str())?;
    }
    obj.set("header", header)?;
    Ok(obj)
}

fn install_inu(ctx: &Ctx, info: Arc<InuInfo>) -> rquickjs::Result<()> {
    let info_fn = Function::new(ctx.clone(), move |ctx| build_info_object(ctx, &info))?;
    let inu = rpc::get_or_create_inu(ctx)?;
    inu.set("info", info_fn)?;
    Ok(())
}

/// formats the pending exception (already raised; caught via ctx.catch) into "message\nstack"
fn format_exception(ctx: &Ctx) -> String {
    use rquickjs::FromJs;
    let exc = ctx.catch();
    let mut msg = Coerced::<String>::from_js(ctx, exc.clone())
        .map(|c| c.0)
        .unwrap_or_else(|_| "JS exception".to_string());
    if let Some(obj) = exc.as_object() {
        if let Ok(stack) = obj.get::<_, String>("stack") {
            if !stack.is_empty() {
                msg.push('\n');
                msg.push_str(&stack);
            }
        }
    }
    msg
}

fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|j| j.into()).unwrap_or_default()
}

fn read_header(
    env: &mut JNIEnv,
    keys: &JObjectArray,
    values: &JObjectArray,
) -> Vec<(String, String)> {
    let n = env.get_array_length(keys).unwrap_or(0);
    let mut out = Vec::with_capacity(n.max(0) as usize);
    for i in 0..n {
        let Ok(k) = env.get_object_array_element(keys, i) else { continue };
        let Ok(v) = env.get_object_array_element(values, i) else { continue };
        let key = jstring_to_string(env, &JString::from(k));
        let value = jstring_to_string(env, &JString::from(v));
        out.push((key, value));
    }
    out
}

/// drains microtasks, logging job errors + unhandled rejections through the engine's rpc log
/// (or dropping them silently pre-installRpc, when there's nowhere to log yet)
fn pump(engine: &Engine) {
    if let Some(state) = engine.rpc.as_ref() {
        rpc::pump_jobs(&engine._rt, &engine.ctx, state.log.as_ref());
    } else if let Some(state) = engine.api.as_ref() {
        rpc::pump_jobs(&engine._rt, &engine.ctx, state.log.as_ref());
    } else {
        rpc::pump_jobs(&engine._rt, &engine.ctx, &|_| {});
    }
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeCreate(
    mut env: JNIEnv,
    this: JObject,
) -> jlong {
    let Some(bridge) = JniBridge::new(&mut env, &this) else { return 0 };

    let Ok(rt) = Runtime::new() else { return 0 };
    let Ok(ctx) = Context::full(&rt) else { return 0 };

    let installed = ctx.with(|ctx| install_console(&ctx, bridge.clone()));
    if installed.is_err() {
        return 0;
    }

    // set outside ctx.with(): the tracker setter locks the runtime, which ctx.with() also holds
    let reject_bridge = bridge.clone();
    let reject_log: Rc<dyn Fn(&str)> = Rc::new(move |msg: &str| reject_bridge.emit_console(3, msg));
    rpc::install_rejection_tracker(&rt, reject_log);

    let engine = Box::new(Engine { ctx, _rt: rt, bridge, rpc: None, api: None, ui: None });
    Box::into_raw(engine) as jlong
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeInstallApi(
    _env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    allow_kv: jboolean,
) {
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_mut() }) else { return };
    let host: Rc<dyn ApiHost> = engine.bridge.clone();
    let log_bridge = engine.bridge.clone();
    let log: Rc<dyn Fn(&str)> = Rc::new(move |msg: &str| log_bridge.emit_console(3, msg));

    let installed = engine.ctx.with(|ctx| api::install_api(&ctx, host, log.clone(), allow_kv != 0));
    if let Ok(state) = installed {
        engine.api = Some(state);
    }
    let ui_host: Rc<dyn UiHost> = engine.bridge.clone();
    let installed = engine.ctx.with(|ctx| ui::install_ui(&ctx, ui_host, log));
    if let Ok(state) = installed {
        engine.ui = Some(state);
    }
    pump(engine);
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeUiRender(
    env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    page_id: jlong,
) -> jstring {
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else {
        return std::ptr::null_mut();
    };
    let Some(state) = engine.ui.as_ref() else {
        return std::ptr::null_mut();
    };
    match ui::render_page(&engine._rt, &engine.ctx, state, page_id) {
        Some(json) => env.new_string(json).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeUiEvent(
    mut env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    page_id: jlong,
    slot: jint,
    arg_json: JString,
) {
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else { return };
    let Some(state) = engine.ui.as_ref() else { return };
    let arg_json = jstring_to_string(&mut env, &arg_json);
    ui::dispatch_ui_event(&engine._rt, &engine.ctx, state, page_id, slot as u32, &arg_json);
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeUiMenuClick(
    _env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    menu_id: jlong,
    slot: jint,
) {
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else { return };
    let Some(state) = engine.ui.as_ref() else { return };
    ui::dispatch_menu_click(&engine._rt, &engine.ctx, state, menu_id, slot);
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeUiPageClosed(
    _env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    page_id: jlong,
) {
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else { return };
    let Some(state) = engine.ui.as_ref() else { return };
    ui::page_closed(&engine._rt, &engine.ctx, state, page_id);
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeResolvePrompt(
    mut env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    request_id: jlong,
    text: JString,
) {
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else { return };
    let Some(state) = engine.ui.as_ref() else { return };
    let text = if text.is_null() {
        None
    } else {
        Some(jstring_to_string(&mut env, &text))
    };
    ui::resolve_prompt(&engine._rt, &engine.ctx, state, request_id, text.as_deref());
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeResolveDialog(
    mut env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    request_id: jlong,
    result: JString,
) {
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else { return };
    let Some(state) = engine.api.as_ref() else { return };
    let result = jstring_to_string(&mut env, &result);
    api::resolve_dialog(&engine._rt, &engine.ctx, state, request_id, &result);
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeNotifyUnload(
    _env: JNIEnv,
    _this: JObject,
    ptr: jlong,
) {
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else { return };
    let Some(state) = engine.api.as_ref() else { return };
    api::notify_unload(&engine._rt, &engine.ctx, state);
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeInstallRpc(
    _env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    allow_intercept: jboolean,
    allow_invoke: jboolean,
    allow_updates: jboolean,
) {
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_mut() }) else { return };
    let host: Rc<dyn RpcHost> = engine.bridge.clone();
    let tl_host: Rc<dyn TlHost> = engine.bridge.clone();
    let log_bridge = engine.bridge.clone();
    let log: Rc<dyn Fn(&str)> = Rc::new(move |msg: &str| log_bridge.emit_console(3, msg));

    let installed = engine.ctx.with(|ctx| {
        rpc::install_rpc(
            &ctx,
            host,
            tl_host,
            log,
            allow_intercept != 0,
            allow_invoke != 0,
            allow_updates != 0,
        )
    });
    if let Ok(state) = installed {
        engine.rpc = Some(state);
        pump(engine);
    }
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchRpc(
    mut env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    callback_id: jint,
    dispatch_id: jlong,
    method: JString,
    request_wire: JString,
) {
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else { return };
    let Some(state) = engine.rpc.as_ref() else { return };
    let method = jstring_to_string(&mut env, &method);
    let request_wire = jstring_to_string(&mut env, &request_wire);
    rpc::dispatch_rpc(&engine._rt, &engine.ctx, state, callback_id as u32, dispatch_id, &method, &request_wire);
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeCompleteNext(
    mut env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    dispatch_id: jlong,
    result_wire: JString,
) {
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else { return };
    let Some(state) = engine.rpc.as_ref() else { return };
    let result_wire = jstring_to_string(&mut env, &result_wire);
    rpc::complete_next(&engine._rt, &engine.ctx, state, dispatch_id, &result_wire);
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeResolveInvoke(
    mut env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    invoke_id: jlong,
    result_wire: JString,
) {
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else { return };
    let Some(state) = engine.rpc.as_ref() else { return };
    let result_wire = jstring_to_string(&mut env, &result_wire);
    rpc::resolve_invoke(&engine._rt, &engine.ctx, state, invoke_id, &result_wire);
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeDispatchUpdate(
    mut env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    update_json: JString,
) {
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else { return };
    let Some(state) = engine.rpc.as_ref() else { return };
    let update_json = jstring_to_string(&mut env, &update_json);
    rpc::dispatch_update(&engine._rt, &engine.ctx, state, &update_json);
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeInstallInfo(
    mut env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    app_version: JString,
    app_build: JString,
    api_version: jint,
    layer: jint,
    language: JString,
    header_keys: JObjectArray,
    header_values: JObjectArray,
) {
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else { return };
    let info = Arc::new(InuInfo {
        app_version: jstring_to_string(&mut env, &app_version),
        app_build: jstring_to_string(&mut env, &app_build),
        api_version,
        layer,
        language: jstring_to_string(&mut env, &language),
        header: read_header(&mut env, &header_keys, &header_values),
    });
    let _ = engine.ctx.with(|ctx| install_inu(&ctx, info));
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeEvaluate(
    mut env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    code: JString,
    filename: JString,
) -> jstring {
    let Some(engine) = (unsafe { (ptr as *mut Engine).as_ref() }) else {
        return std::ptr::null_mut();
    };
    let code = jstring_to_string(&mut env, &code).into_bytes();
    let filename = jstring_to_string(&mut env, &filename);

    let result: Result<String, String> = engine.ctx.with(|ctx| {
        let mut options = rquickjs::context::EvalOptions::default();
        options.filename = Some(filename);
        match ctx.eval_with_options::<Value, _>(code, options) {
            Ok(v) => {
                use rquickjs::FromJs;
                Ok(Coerced::<String>::from_js(&ctx, v)
                    .map(|c| c.0)
                    .unwrap_or_default())
            }
            Err(rquickjs::Error::Exception) => Err(format_exception(&ctx)),
            Err(e) => Err(e.to_string()),
        }
    });

    // drain microtasks from the initial run so top-level async work settles and any unhandled
    // rejection is surfaced
    pump(engine);

    match result {
        Ok(s) => env
            .new_string(s)
            .map(|j| j.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(msg) => {
            let _ = env.throw_new("java/lang/RuntimeException", msg);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_desu_inugram_helpers_plugins_QuickJs_nativeDestroy(
    _env: JNIEnv,
    _this: JObject,
    ptr: jlong,
) {
    if ptr != 0 {
        let mut engine = unsafe { Box::from_raw(ptr as *mut Engine) };
        if let Some(state) = engine.rpc.take() {
            rpc::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.api.take() {
            api::dispose(&engine.ctx, &state);
        }
        if let Some(state) = engine.ui.take() {
            ui::dispose(&engine.ctx, &state);
        }
        engine.ctx.with(|ctx| rpc::dispose_rejection_tracker(&ctx));
        drop(engine);
    }
}

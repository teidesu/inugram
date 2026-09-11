use jni::objects::{Auto, Global, JByteArray, JByteBuffer, JMethodID, JObject, JObjectArray, JString, JValue};
use jni::refs::IntoAuto;
use jni::signature::{MethodSignature, Primitive, ReturnType, RuntimeMethodSignature};
use jni::strings::JNIString;
use jni::sys::jvalue;
use jni::Env;
use std::rc::Rc;
use std::sync::Arc;
use std::thread::{self, ThreadId};

use super::log::ConsoleSink;
use crate::LEVEL_ERROR;

use super::env::{clear_exception, with_current_env};

/// What a callback arriving on its caller's thread may still reach. Everything else keeps host
/// state that only `Utilities.globalQueue` touches, and a plain `HashMap` is what it keeps it in.
/// A member here either holds no state, guards its own, or hands it to
/// `EngineDispatch.createHostDispatcher`, which posts to `globalQueue` when the caller is not it;
/// what it reads of stock is concurrent, and what it shows it posts to the ui thread itself, so
/// the answer is still decided on this thread and a refusal still reaches the plugin.
///
/// This gates only the calls that answer something: `call_void` has nothing to answer a refusal
/// with, so a void host is never asked and the void names below are here for the contract rather
/// than for the check. Every one of them hands its work to `EngineDispatch.createHostDispatcher`
/// or to the ui thread on the kotlin side, which is what actually keeps them off `globalQueue`.
const CALLER_THREAD_HOSTS: &[&str] = &[
  "jvm",
  "xposed",
  "canvasRelease",
  "toast",
  "bulletin",
  "openUrl",
  "clipboardWrite",
  "openPage",
  "timerSchedule",
  "tlGet",
  "tlReadField",
  "tlResolveField",
  "tlSet",
  "tlSetBytes",
  "tlHas",
  "tlOwnKeys",
  "tlCopy",
  "tlRelease",
  "kv",
];

thread_local! {
  /// `thread::current()` clones a handle to answer this, and a callback asks on every call
  static CURRENT_THREAD: ThreadId = thread::current().id();
}

pub(crate) struct JniBridge {
  owner_thread: ThreadId,
  pub(crate) target: Global<JObject<'static>>,
  pub(crate) console: Arc<ConsoleSink>,
  pub(crate) on_rpc_register: JMethodID,
  pub(crate) on_rpc_unregister: JMethodID,
  pub(crate) on_invoke_rpc: JMethodID,
  pub(crate) on_invoke_raw: JMethodID,
  pub(crate) on_takeout: JMethodID,
  pub(crate) on_rpc_next: JMethodID,
  pub(crate) on_rpc_complete: JMethodID,
  pub(crate) on_update_register: JMethodID,
  pub(crate) on_update_unregister: JMethodID,
  pub(crate) on_intercept_update_register: JMethodID,
  pub(crate) on_intercept_update_unregister: JMethodID,
  pub(crate) on_update_verdict: JMethodID,
  pub(crate) on_tl_get: JMethodID,
  pub(crate) on_tl_read_field: JMethodID,
  pub(crate) on_tl_resolve_field: JMethodID,
  pub(crate) on_tl_set: JMethodID,
  pub(crate) on_tl_set_bytes: JMethodID,
  pub(crate) on_tl_has: JMethodID,
  pub(crate) on_tl_own_keys: JMethodID,
  pub(crate) on_tl_copy: JMethodID,
  pub(crate) on_tl_release: JMethodID,
  pub(crate) on_account_read: JMethodID,
  pub(crate) on_resolve_peer: JMethodID,
  pub(crate) on_account_fetch: JMethodID,
  pub(crate) on_account_write: JMethodID,
  pub(crate) on_message_file: JMethodID,
  pub(crate) on_kv: JMethodID,
  pub(crate) on_accounts: JMethodID,
  pub(crate) on_ui_toast: JMethodID,
  pub(crate) on_ui_bulletin: JMethodID,
  pub(crate) on_ui_modal: JMethodID,
  pub(crate) on_ui_current_screen: JMethodID,
  pub(crate) on_format: JMethodID,
  pub(crate) on_open_url: JMethodID,
  pub(crate) on_clipboard_read: JMethodID,
  pub(crate) on_clipboard_write: JMethodID,
  pub(crate) on_ui_open_page: JMethodID,
  pub(crate) on_ui_open_fragment: JMethodID,
  pub(crate) on_ui_open_screen: JMethodID,
  pub(crate) on_ui_register_settings: JMethodID,
  pub(crate) on_ui_unregister_settings: JMethodID,
  pub(crate) on_ui_invalidate: JMethodID,
  pub(crate) on_ui_open_menu: JMethodID,
  pub(crate) on_icon_resolves: JMethodID,
  pub(crate) on_common_icon: JMethodID,
  pub(crate) on_action_register: JMethodID,
  pub(crate) on_action_unregister: JMethodID,
  pub(crate) on_action_editor: JMethodID,
  pub(crate) on_random_bytes: JMethodID,
  pub(crate) on_timer_schedule: JMethodID,
  pub(crate) on_fetch: JMethodID,
  pub(crate) on_canvas: JMethodID,
  pub(crate) on_fetch_abort: JMethodID,
  pub(crate) on_notification_register: JMethodID,
  pub(crate) on_notification_unregister: JMethodID,
  pub(crate) on_jvm: JMethodID,
  pub(crate) on_jvm_resolve: JMethodID,
  pub(crate) on_xposed: JMethodID,
  /// where `tlReadField` puts a value. Written by the host and read here between the call and the
  /// next one, which is exclusive because a TL read only ever runs under the engine lease
  read_buffer: *mut u8,
  read_buffer_len: usize,
  /// what keeps the buffer, and so the address above, alive for as long as this bridge
  _read_buffer_ref: Global<JObject<'static>>,
}

impl JniBridge {
  pub(crate) fn new(env: &mut Env, this: &JObject) -> Option<Rc<JniBridge>> {
    let target = env.new_global_ref(this).ok()?;
    let console_target = env.new_global_ref(this).ok()?;
    let class = env.get_object_class(this).ok()?;

    // taken before the id cache below borrows `env` for the rest of the constructor
    let tl_buffer_sig = RuntimeMethodSignature::from_str("()Ljava/nio/ByteBuffer;").ok()?;
    let tl_buffer = env
      .get_method_id(&class, JNIString::from("tlBuffer"), MethodSignature::from(&tl_buffer_sig))
      .ok()?;
    let buffer = unsafe { env.call_method_unchecked(this, tl_buffer, ReturnType::Object, &[]) };
    if clear_exception(env) {
      return None;
    }
    let buffer = buffer.and_then(|value| value.l()).ok()?;
    let read_buffer_ref = env.new_global_ref(&buffer).ok()?;
    let buffer = unsafe { JByteBuffer::from_raw(env, buffer.into_raw()) }.auto();
    let read_buffer = env.get_direct_buffer_address(&buffer).ok()?;
    let read_buffer_len = env.get_direct_buffer_capacity(&buffer).ok()?;

    let mut method = |name: &str, sig: &str| {
      let parsed = RuntimeMethodSignature::from_str(sig).ok();
      let found = parsed
        .as_ref()
        .and_then(|parsed| env.get_method_id(&class, JNIString::from(name), MethodSignature::from(parsed)).ok());
      match found {
        Some(id) => Some(id),
        None => {
          clear_exception(env);
          let _ = env.throw_new(
            JNIString::from("java/lang/NoSuchMethodError"),
            JNIString::from(format!("PluginBridge.{name}{sig} is missing; the plugin engine cannot be created")),
          );
          None
        }
      }
    };
    let console = Arc::new(ConsoleSink {
      target: console_target,
      on_console: method("onConsole", "(ILjava/lang/String;)V")?,
    });
    let bridge = JniBridge {
      owner_thread: thread::current().id(),
      console,
      on_rpc_register: method(
        "onRpcRegister",
        "([Ljava/lang/String;ILjava/lang/String;ZLjava/lang/String;)Ljava/lang/String;",
      )?,
      on_rpc_unregister: method("onRpcUnregister", "(I)V")?,
      on_invoke_rpc: method("onInvokeRpc", "(JILjava/lang/String;)Ljava/lang/String;")?,
      on_invoke_raw: method("onInvokeRaw", "(JI[B)Ljava/lang/String;")?,
      on_takeout: method("onTakeout", "(JIILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;")?,
      on_rpc_next: method("onRpcNext", "(JLjava/lang/String;)Ljava/lang/String;")?,
      on_rpc_complete: method("onRpcComplete", "(JLjava/lang/String;)V")?,
      on_update_register: method("onUpdateRegister", "(I[Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")?,
      on_update_unregister: method("onUpdateUnregister", "(I)V")?,
      on_intercept_update_register: method("onInterceptUpdateRegister", "(I[Ljava/lang/String;)Ljava/lang/String;")?,
      on_intercept_update_unregister: method("onInterceptUpdateUnregister", "(I)V")?,
      on_update_verdict: method("onUpdateVerdict", "(JZ)V")?,
      on_tl_get: method("tlGet", "(JLjava/lang/String;)Ljava/lang/String;")?,
      on_tl_read_field: method("tlReadField", "(JII)I")?,
      on_tl_resolve_field: method("resolveField", "(ILjava/lang/String;)I")?,
      on_tl_set: method("tlSet", "(JLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;")?,
      on_tl_set_bytes: method("tlSetBytes", "(JLjava/lang/String;[B)Ljava/lang/String;")?,
      on_tl_has: method("tlHas", "(JLjava/lang/String;)I")?,
      on_tl_own_keys: method("tlOwnKeys", "(J)Ljava/lang/String;")?,
      on_tl_copy: method("tlCopy", "(J)Ljava/lang/String;")?,
      on_tl_release: method("tlRelease", "(J)V")?,
      on_account_read: method("accountRead", "(IILjava/lang/String;)Ljava/lang/String;")?,
      on_resolve_peer: method("resolvePeer", "(IJLjava/lang/String;I)Ljava/lang/String;")?,
      on_account_fetch: method("accountFetch", "(IJILjava/lang/String;)Ljava/lang/String;")?,
      on_account_write: method("accountWrite", "(IJILjava/lang/String;[Ljava/lang/String;)Ljava/lang/String;")?,
      on_message_file: method("messageFile", "(ILjava/lang/String;)Ljava/lang/String;")?,
      on_kv: method("kv", "(ILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;")?,
      on_accounts: method("accounts", "()Ljava/lang/String;")?,
      on_ui_toast: method("uiToast", "(Ljava/lang/String;)V")?,
      on_ui_bulletin: method("uiBulletin", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")?,
      on_ui_modal: method("uiModal", "(IJLjava/lang/String;)Ljava/lang/String;")?,
      on_ui_current_screen: method("uiCurrentScreen", "()Ljava/lang/String;")?,
      on_format: method("format", "(IJ)Ljava/lang/String;")?,
      on_open_url: method("openUrl", "(Ljava/lang/String;)V")?,
      on_clipboard_read: method("clipboardRead", "()Ljava/lang/String;")?,
      on_clipboard_write: method("clipboardWrite", "(Ljava/lang/String;)V")?,
      on_ui_open_page: method("uiOpenPage", "(J)Ljava/lang/String;")?,
      on_ui_open_fragment: method("uiOpenFragment", "(J)Ljava/lang/String;")?,
      on_ui_open_screen: method("uiOpenScreen", "(Ljava/lang/String;)Ljava/lang/String;")?,
      on_ui_register_settings: method("uiRegisterSettings", "(J)V")?,
      on_ui_unregister_settings: method("uiUnregisterSettings", "(J)V")?,
      on_ui_invalidate: method("uiInvalidate", "(J)V")?,
      on_ui_open_menu: method("uiOpenMenu", "(JJLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;")?,
      on_icon_resolves: method("iconResolves", "(ILjava/lang/String;)Z")?,
      on_common_icon: method("commonIcon", "(Ljava/lang/String;)Ljava/lang/String;")?,
      on_action_register: method(
        "actionRegister",
        "(IILjava/lang/String;ILjava/lang/String;Ljava/lang/String;I)Ljava/lang/String;",
      )?,
      on_action_unregister: method("actionUnregister", "(II)V")?,
      on_action_editor: method("actionEditor", "(IJLjava/lang/String;)Ljava/lang/String;")?,
      on_random_bytes: method("onRandomBytes", "(I)[B")?,
      on_timer_schedule: method("onTimerSchedule", "(J)V")?,
      on_canvas: method("canvas", "(IJLjava/lang/String;[B)Ljava/lang/String;")?,
      on_fetch: method("fetch", "(JLjava/lang/String;Ljava/lang/String;[B)Ljava/lang/String;")?,
      on_fetch_abort: method("abort", "(J)V")?,
      on_notification_register: method("register", "(I[Ljava/lang/String;)Ljava/lang/String;")?,
      on_notification_unregister: method("unregister", "(I)V")?,
      on_jvm: method("jvm", "(IJLjava/lang/String;[Ljava/lang/String;)Ljava/lang/String;")?,
      on_jvm_resolve: method("jvmResolve", "(Ljava/lang/Object;Ljava/lang/String;I)[Ljava/lang/Object;")?,
      on_xposed: method("xposed", "(IJLjava/lang/String;[Ljava/lang/String;)Ljava/lang/String;")?,
      target,
      read_buffer,
      read_buffer_len,
      _read_buffer_ref: read_buffer_ref,
    };
    Some(Rc::new(bridge))
  }

  /// The bytes `tlReadField` just wrote. Sound because the buffer is a direct `ByteBuffer` the
  /// host allocated once and this bridge pins, and because only one thread is ever inside a read.
  pub(crate) fn read_buffer(&self) -> &[u8] {
    if self.read_buffer.is_null() {
      return &[];
    }
    unsafe { std::slice::from_raw_parts(self.read_buffer, self.read_buffer_len) }
  }

  fn check_host_thread(&self, what: &str) -> Result<(), String> {
    if CURRENT_THREAD.with(|id| *id) != self.owner_thread && !CALLER_THREAD_HOSTS.contains(&what) {
      let error = format!("{what}: this API requires globalQueue; unavailable in a caller-thread callback");
      self.emit_console(LEVEL_ERROR, &error);
      return Err(error);
    }
    Ok(())
  }

  pub(crate) fn marshal<'l>(
    &self,
    env: &mut Env<'l>,
    what: &str,
    args: &[Arg<'_>],
  ) -> Result<Vec<Marshalled<'l>>, String> {
    let mut out = Vec::with_capacity(args.len());
    for arg in args {
      out.push(match arg {
        Arg::Int(v) => Marshalled::Prim(JValue::Int(*v).as_jni()),
        Arg::Long(v) => Marshalled::Prim(JValue::Long(*v).as_jni()),
        Arg::Bool(v) => Marshalled::Prim(JValue::Bool(*v).as_jni()),
        Arg::Str(s) => Marshalled::Obj(Self::new_jstring(env, what, s)?),
        Arg::OptStr(Some(s)) => Marshalled::Obj(Self::new_jstring(env, what, s)?),
        Arg::Strs(items) => Marshalled::Obj(Self::new_jstring_array(env, what, items)?),
        Arg::Bytes(Some(bytes)) => match env.byte_array_from_slice(bytes) {
          Ok(array) => Marshalled::Obj(JObject::from(array).auto()),
          Err(e) => {
            clear_exception(env);
            return Err(format!("{what}: {e}"));
          }
        },
        Arg::OptStr(None) | Arg::Bytes(None) => Marshalled::Obj(JObject::null().auto()),
      });
    }
    Ok(out)
  }

  pub(crate) fn call_string(&self, what: &str, method: JMethodID, args: &[Arg<'_>]) -> Result<Option<String>, String> {
    with_current_env(|env| self.call_string_in(env, what, method, args))
      .unwrap_or_else(|| Err(format!("{what}: JNI env unavailable")))
  }

  pub(crate) fn call_string_in(
    &self,
    env: &mut Env,
    what: &str,
    method: JMethodID,
    args: &[Arg<'_>],
  ) -> Result<Option<String>, String> {
    self.check_host_thread(what)?;
    let marshalled = self.marshal(env, what, args)?;
    let jargs = jvalues(&marshalled);
    let result = unsafe { env.call_method_unchecked(&self.target, method, ReturnType::Object, &jargs) };
    if clear_exception(env) {
      return Err(format!("{what}: host callback threw"));
    }
    let obj = result.and_then(|v| v.l()).map_err(|e| format!("{what}: {e}"))?;
    if obj.is_null() {
      return Ok(None);
    }
    let obj = unsafe { JString::from_raw(env, obj.into_raw()) }.auto();
    obj.try_to_string(env).map(Some).map_err(|e| format!("{what}: {e}"))
  }

  pub(crate) fn call_wire(&self, what: &str, method: JMethodID, args: &[Arg<'_>]) -> String {
    match self.call_string(what, method, args) {
      Ok(Some(wire)) => wire,
      Ok(None) => crate::api::tl::proxy::encode_error(&format!("{what}: host returned null")),
      Err(e) => crate::api::tl::proxy::encode_error(&e),
    }
  }

  pub(crate) fn call_refusal(&self, what: &str, method: JMethodID, args: &[Arg<'_>]) -> Option<String> {
    self.call_string(what, method, args).unwrap_or_else(Some)
  }

  pub(crate) fn call_void(&self, what: &str, method: JMethodID, args: &[Arg<'_>]) {
    with_current_env(|env| {
      let Ok(marshalled) = self.marshal(env, what, args) else {
        return;
      };
      let jargs = jvalues(&marshalled);
      let _ =
        unsafe { env.call_method_unchecked(&self.target, method, ReturnType::Primitive(Primitive::Void), &jargs) };
      clear_exception(env);
    });
  }

  pub(crate) fn call_bool(&self, what: &str, method: JMethodID, args: &[Arg<'_>]) -> bool {
    if self.check_host_thread(what).is_err() {
      return false;
    }
    let answered = with_current_env(|env| {
      let marshalled = match self.marshal(env, what, args) {
        Ok(m) => m,
        Err(e) => {
          self.emit_console(LEVEL_ERROR, &e);
          return false;
        }
      };
      let jargs = jvalues(&marshalled);
      let result =
        unsafe { env.call_method_unchecked(&self.target, method, ReturnType::Primitive(Primitive::Boolean), &jargs) };
      if clear_exception(env) {
        self.emit_console(LEVEL_ERROR, &format!("{what}: host callback threw"));
        return false;
      }
      result.and_then(|v| v.z()).unwrap_or(false)
    });
    match answered {
      Some(answer) => answer,
      None => {
        self.emit_console(LEVEL_ERROR, &format!("{what}: JNI env unavailable"));
        false
      }
    }
  }

  /// [`Self::call_int`] for arguments that are already jni values: no [`Self::marshal`], and so
  /// neither of the two vectors it and [`jvalues`] allocate. Worth having only where a call is made
  /// per field rather than per request.
  pub(crate) fn call_int_prims(&self, what: &str, method: JMethodID, args: &[jvalue], fallback: i32) -> i32 {
    if self.check_host_thread(what).is_err() {
      return fallback;
    }
    with_current_env(|env| {
      let result =
        unsafe { env.call_method_unchecked(&self.target, method, ReturnType::Primitive(Primitive::Int), args) };
      // a pending exception aborts the process at the next jni call, so this is never skipped
      if clear_exception(env) {
        return fallback;
      }
      result.and_then(|v| v.i()).unwrap_or(fallback)
    })
    .unwrap_or(fallback)
  }

  pub(crate) fn call_int(&self, what: &str, method: JMethodID, args: &[Arg<'_>], fallback: i32) -> i32 {
    if self.check_host_thread(what).is_err() {
      return fallback;
    }
    with_current_env(|env| {
      let Ok(marshalled) = self.marshal(env, what, args) else {
        return fallback;
      };
      let jargs = jvalues(&marshalled);
      let result =
        unsafe { env.call_method_unchecked(&self.target, method, ReturnType::Primitive(Primitive::Int), &jargs) };
      if clear_exception(env) {
        return fallback;
      }
      result.and_then(|v| v.i()).unwrap_or(fallback)
    })
    .unwrap_or(fallback)
  }

  pub(crate) fn call_bytes(&self, what: &str, method: JMethodID, args: &[Arg<'_>], out: &mut [u8]) -> bool {
    if self.check_host_thread(what).is_err() {
      return false;
    }
    with_current_env(|env| {
      let Ok(marshalled) = self.marshal(env, what, args) else {
        return false;
      };
      let jargs = jvalues(&marshalled);
      let result = unsafe { env.call_method_unchecked(&self.target, method, ReturnType::Object, &jargs) };
      if clear_exception(env) {
        return false;
      }
      let Ok(array) = result.and_then(|v| v.l()) else {
        return false;
      };
      if array.is_null() {
        return false;
      }
      let array = unsafe { JByteArray::from_raw(env, array.into_raw()) }.auto();
      let Ok(bytes) = env.convert_byte_array(&array) else {
        clear_exception(env);
        return false;
      };
      if bytes.len() != out.len() {
        return false;
      }
      out.copy_from_slice(&bytes);
      true
    })
    .unwrap_or(false)
  }

  pub(crate) fn emit_console(&self, level: i32, message: &str) {
    self.console.emit(level, message);
  }

  pub(crate) fn new_jstring_array<'l>(
    env: &mut Env<'l>,
    what: &str,
    items: &[String],
  ) -> Result<Auto<'l, JObject<'l>>, String> {
    let array = match JObjectArray::<JString>::new(env, items.len(), &JString::null()) {
      Ok(a) => a.auto(),
      Err(e) => {
        clear_exception(env);
        return Err(format!("{what}: {e}"));
      }
    };
    for (i, item) in items.iter().enumerate() {
      let item = match env.new_string(item) {
        Ok(item) => item.auto(),
        Err(e) => {
          clear_exception(env);
          return Err(format!("{what}: {e}"));
        }
      };
      if let Err(e) = array.set_element(env, i, &item) {
        clear_exception(env);
        return Err(format!("{what}: {e}"));
      }
    }
    Ok(JObject::from(array.unwrap()).auto())
  }

  pub(crate) fn new_jstring<'l>(env: &mut Env<'l>, what: &str, s: &str) -> Result<Auto<'l, JObject<'l>>, String> {
    match env.new_string(s) {
      Ok(j) => Ok(JObject::from(j).auto()),
      Err(e) => {
        clear_exception(env);
        Err(format!("{what}: {e}"))
      }
    }
  }
}

pub(crate) enum Arg<'a> {
  Int(i32),
  Long(i64),
  Bool(bool),
  Str(&'a str),
  OptStr(Option<&'a str>),
  Strs(&'a [String]),
  Bytes(Option<&'a [u8]>),
}

pub(crate) enum Marshalled<'l> {
  Prim(jvalue),
  Obj(Auto<'l, JObject<'l>>),
}

pub(crate) fn jvalues(marshalled: &[Marshalled<'_>]) -> Vec<jvalue> {
  marshalled
    .iter()
    .map(|m| match m {
      Marshalled::Prim(v) => *v,
      Marshalled::Obj(o) => JValue::Object(o.as_ref()).as_jni(),
    })
    .collect()
}

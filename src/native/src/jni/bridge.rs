//! The one Java object every upcall goes through, and the argument marshalling around it.

use jni::objects::{Auto, Global, JByteArray, JMethodID, JObject, JObjectArray, JString, JValue};
use jni::refs::IntoAuto;
use jni::signature::{MethodSignature, Primitive, ReturnType, RuntimeMethodSignature};
use jni::strings::JNIString;
use jni::sys::jvalue;
use jni::Env;
use std::rc::Rc;
use std::sync::Arc;

use super::log::ConsoleSink;
use crate::LEVEL_ERROR;

use super::env::{clear_exception, with_current_env};

/// The single JNI upcall surface into the Java `QuickJs` object: one global reference + jmethodIDs cached
/// once at [`Java_desu_inugram_helpers_plugins_QuickJs_nativeCreate`] (jmethodIDs are stable for
/// the lifetime of the class, and proxy traps hit these on every field access - per-call name
/// lookups were measurable pure waste).
///
/// Failure policy is fail-closed: a JNI-level failure or a throwing Java listener surfaces as an
/// *error* to JS (never as "null == ok, proceed"), and the pending Java exception is always
/// cleared before this thread touches JNI again.
pub(crate) struct JniBridge {
    pub(crate) target: Global<JObject<'static>>,
    pub(crate) console: Arc<ConsoleSink>,
    pub(crate) on_rpc_register: JMethodID,
    pub(crate) on_rpc_unregister: JMethodID,
    pub(crate) on_invoke_rpc: JMethodID,
    pub(crate) on_rpc_next: JMethodID,
    pub(crate) on_rpc_complete: JMethodID,
    pub(crate) on_update_register: JMethodID,
    pub(crate) on_update_unregister: JMethodID,
    pub(crate) on_intercept_update_register: JMethodID,
    pub(crate) on_intercept_update_unregister: JMethodID,
    pub(crate) on_update_verdict: JMethodID,
    pub(crate) on_deserialize_register: JMethodID,
    pub(crate) on_deserialize_unregister: JMethodID,
    pub(crate) on_deserialize_middleware_register: JMethodID,
    pub(crate) on_deserialize_middleware_unregister: JMethodID,
    pub(crate) on_tl_get: JMethodID,
    pub(crate) on_tl_set: JMethodID,
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
    pub(crate) on_ui_dialog: JMethodID,
    pub(crate) on_ui_chooser: JMethodID,
    pub(crate) on_ui_current_screen: JMethodID,
    pub(crate) on_open_url: JMethodID,
    pub(crate) on_clipboard_read: JMethodID,
    pub(crate) on_clipboard_write: JMethodID,
    pub(crate) on_ui_prompt: JMethodID,
    pub(crate) on_ui_open_page: JMethodID,
    pub(crate) on_ui_open_fragment: JMethodID,
    pub(crate) on_ui_register_settings: JMethodID,
    pub(crate) on_ui_unregister_settings: JMethodID,
    pub(crate) on_ui_invalidate: JMethodID,
    pub(crate) on_ui_open_menu: JMethodID,
    pub(crate) on_icon_resolves: JMethodID,
    pub(crate) on_action_register: JMethodID,
    pub(crate) on_action_unregister: JMethodID,
    pub(crate) on_action_editor: JMethodID,
    pub(crate) on_check_grant: JMethodID,
    pub(crate) on_random_bytes: JMethodID,
    pub(crate) on_timer_schedule: JMethodID,
    pub(crate) on_fetch: JMethodID,
    pub(crate) on_canvas: JMethodID,
    pub(crate) on_fetch_abort: JMethodID,
    pub(crate) on_notification_register: JMethodID,
    pub(crate) on_notification_unregister: JMethodID,
    pub(crate) on_jvm: JMethodID,
    pub(crate) on_xposed: JMethodID,
}

impl JniBridge {
    pub(crate) fn new(env: &mut Env, this: &JObject) -> Option<Rc<JniBridge>> {
        // there is one JavaVM per process and jni owns it, so the bridge and the console sink
        // both name the singleton rather than each carrying a handle
        let target = env.new_global_ref(this).ok()?;
        let console_target = env.new_global_ref(this).ok()?;
        let class = env.get_object_class(this).ok()?;
        // one wrong descriptor makes the whole bridge `None`, `nativeCreate` answer 0 and every
        // plugin fail at its first call with "context is closed" - so the name is carried out on the
        // pending exception the failed lookup left, which is the only place it is still known
        let mut method = |name: &str, sig: &str| {
            let parsed = RuntimeMethodSignature::from_str(sig).ok();
            let found = parsed.as_ref().and_then(|parsed| {
                env.get_method_id(&class, JNIString::from(name), MethodSignature::from(parsed)).ok()
            });
            match found {
                Some(id) => Some(id),
                None => {
                    clear_exception(env);
                    let _ = env.throw_new(
                        JNIString::from("java/lang/NoSuchMethodError"),
                        JNIString::from(format!(
                            "PluginBridge.{name}{sig} is missing; the plugin engine cannot be created"
                        )),
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
            console,
            on_rpc_register: method("onRpcRegister", "([Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;")?,
            on_rpc_unregister: method("onRpcUnregister", "(I)V")?,
            on_invoke_rpc: method("onInvokeRpc", "(JILjava/lang/String;)Ljava/lang/String;")?,
            on_rpc_next: method("onRpcNext", "(JLjava/lang/String;)Ljava/lang/String;")?,
            on_rpc_complete: method("onRpcComplete", "(JLjava/lang/String;)V")?,
            on_update_register: method(
                "onUpdateRegister",
                "(I[Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            )?,
            on_update_unregister: method("onUpdateUnregister", "(I)V")?,
            on_intercept_update_register: method(
                "onInterceptUpdateRegister",
                "(I[Ljava/lang/String;)Ljava/lang/String;",
            )?,
            on_intercept_update_unregister: method("onInterceptUpdateUnregister", "(I)V")?,
            on_update_verdict: method("onUpdateVerdict", "(JZ)V")?,
            on_deserialize_register: method("onDeserializeRegister", "(ILjava/lang/String;)Ljava/lang/String;")?,
            on_deserialize_unregister: method("onDeserializeUnregister", "(I)V")?,
            on_deserialize_middleware_register: method(
                "onDeserializeMiddlewareRegister",
                "(ILjava/lang/String;)Ljava/lang/String;",
            )?,
            on_deserialize_middleware_unregister: method("onDeserializeMiddlewareUnregister", "(I)V")?,
            on_tl_get: method("tlGet", "(JLjava/lang/String;)Ljava/lang/String;")?,
            on_tl_set: method("tlSet", "(JLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;")?,
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
            on_ui_dialog: method("uiDialog", "(JLjava/lang/String;)Ljava/lang/String;")?,
            on_ui_chooser: method("uiChooser", "(JLjava/lang/String;)Ljava/lang/String;")?,
            on_ui_current_screen: method("uiCurrentScreen", "()Ljava/lang/String;")?,
            on_open_url: method("openUrl", "(Ljava/lang/String;)V")?,
            on_clipboard_read: method("clipboardRead", "()Ljava/lang/String;")?,
            on_clipboard_write: method("clipboardWrite", "(Ljava/lang/String;)V")?,
            on_ui_prompt: method("uiPrompt", "(JLjava/lang/String;)Ljava/lang/String;")?,
            on_ui_open_page: method("uiOpenPage", "(J)Ljava/lang/String;")?,
            on_ui_open_fragment: method("uiOpenFragment", "(J)Ljava/lang/String;")?,
            on_ui_register_settings: method("uiRegisterSettings", "(J)V")?,
            on_ui_unregister_settings: method("uiUnregisterSettings", "(J)V")?,
            on_ui_invalidate: method("uiInvalidate", "(J)V")?,
            on_ui_open_menu: method("uiOpenMenu", "(JJLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;")?,
            on_icon_resolves: method("iconResolves", "(ILjava/lang/String;)Z")?,
            on_action_register: method("actionRegister", "(IILjava/lang/String;)Ljava/lang/String;")?,
            on_action_unregister: method("actionUnregister", "(II)V")?,
            on_action_editor: method("actionEditor", "(IJLjava/lang/String;)Ljava/lang/String;")?,
            on_check_grant: method("onCheckGrant", "(Ljava/lang/String;Ljava/lang/String;I)Z")?,
            on_random_bytes: method("onRandomBytes", "(I)[B")?,
            on_timer_schedule: method("onTimerSchedule", "(J)V")?,
            on_canvas: method("canvas", "(IJLjava/lang/String;[B)Ljava/lang/String;")?,
            on_fetch: method("fetch", "(JLjava/lang/String;Ljava/lang/String;[B)Ljava/lang/String;")?,
            on_fetch_abort: method("abort", "(J)V")?,
            on_notification_register: method("register", "(I[Ljava/lang/String;)Ljava/lang/String;")?,
            on_notification_unregister: method("unregister", "(I)V")?,
            on_jvm: method("jvm", "(IJLjava/lang/String;[Ljava/lang/String;)Ljava/lang/String;")?,
            on_xposed: method("xposed", "(IJLjava/lang/String;[Ljava/lang/String;)Ljava/lang/String;")?,
            target,
        };
        Some(Rc::new(bridge))
    }

    /// one argument of an upcall, in the vocabulary the java signatures are written in.
    ///
    /// Marshalling used to be written out per upcall, twenty times, because a local reference has
    /// to outlive the `jvalue` array that points at it - so every one of them repeated the same
    /// allocate-then-borrow dance and its own copy of the failure path. [`Marshalled`] holds the
    /// references for the length of the call instead, and every upcall below is one line.
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
                Arg::Str(s) => Marshalled::Obj(self.new_jstring(env, what, s)?),
                Arg::OptStr(Some(s)) => Marshalled::Obj(self.new_jstring(env, what, s)?),
                Arg::Strs(items) => Marshalled::Obj(self.new_jstring_array(env, what, items)?),
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

    /// `Ok(None)` = Java returned null, `Ok(Some)` = a string, `Err(msg)` = JNI failure or a
    /// throwing Java listener - callers map `Err` onto their own error channel, never onto success
    pub(crate) fn call_string(
        &self,
        what: &str,
        method: JMethodID,
        args: &[Arg<'_>],
    ) -> Result<Option<String>, String> {
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
        // safe by construction: the descriptor these ids were looked up under says the return type
        let obj = unsafe { JString::from_raw(env, obj.into_raw()) }.auto();
        obj.try_to_string(env).map(Some).map_err(|e| format!("{what}: {e}"))
    }

    /// [`call_string`](Self::call_string) for the channels that carry a wire rather than a nullable
    /// string: every failure, including a null, becomes an `E` wire the caller can throw
    pub(crate) fn call_wire(&self, what: &str, method: JMethodID, args: &[Arg<'_>]) -> String {
        match self.call_string(what, method, args) {
            Ok(Some(wire)) => wire,
            Ok(None) => crate::tl::proxy::encode_error(&format!("{what}: host returned null")),
            Err(e) => crate::tl::proxy::encode_error(&e),
        }
    }

    /// [`call_string`](Self::call_string) for the `Some(msg) == error` channels
    pub(crate) fn call_refusal(&self, what: &str, method: JMethodID, args: &[Arg<'_>]) -> Option<String> {
        self.call_string(what, method, args).unwrap_or_else(Some)
    }

    pub(crate) fn call_void(&self, what: &str, method: JMethodID, args: &[Arg<'_>]) {
        with_current_env(|env| {
            let Ok(marshalled) = self.marshal(env, what, args) else {
                return;
            };
            let jargs = jvalues(&marshalled);
            let _ = unsafe {
                env.call_method_unchecked(&self.target, method, ReturnType::Primitive(Primitive::Void), &jargs)
            };
            clear_exception(env);
        });
    }

    /// fails closed: a JNI failure or a throwing Java listener denies, never allows
    pub(crate) fn call_bool(&self, what: &str, method: JMethodID, args: &[Arg<'_>]) -> bool {
        let answered = with_current_env(|env| {
            let marshalled = match self.marshal(env, what, args) {
                Ok(m) => m,
                Err(e) => {
                    self.emit_console(LEVEL_ERROR, &e);
                    return false;
                }
            };
            let jargs = jvalues(&marshalled);
            let result = unsafe {
                env.call_method_unchecked(&self.target, method, ReturnType::Primitive(Primitive::Boolean), &jargs)
            };
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

    pub(crate) fn call_int(&self, what: &str, method: JMethodID, args: &[Arg<'_>], fallback: i32) -> i32 {
        with_current_env(|env| {
            let Ok(marshalled) = self.marshal(env, what, args) else {
                return fallback;
            };
            let jargs = jvalues(&marshalled);
            let result = unsafe {
                env.call_method_unchecked(&self.target, method, ReturnType::Primitive(Primitive::Int), &jargs)
            };
            if clear_exception(env) {
                return fallback;
            }
            result.and_then(|v| v.i()).unwrap_or(fallback)
        })
        .unwrap_or(fallback)
    }

    /// fills [out] from a `byte[]` the host answers with; false leaves it untouched, and the caller
    /// must treat that as a failure rather than as zeroes
    pub(crate) fn call_bytes(&self, what: &str, method: JMethodID, args: &[Arg<'_>], out: &mut [u8]) -> bool {
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
            // safe by construction: `onRandomBytes` is declared to answer a byte[]
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

    /// console.* / engine diagnostics -> QuickJs.onConsole(level, message)
    pub(crate) fn emit_console(&self, level: i32, message: &str) {
        self.console.emit(level, message);
    }

    /// allocates a `String[]`, auto-deleted for the same reason [`JniBridge::new_jstring`]'s result is
    pub(crate) fn new_jstring_array<'l>(
        &self,
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
            let item = self.new_jstring(env, what, item)?;
            // safe by construction: the element type is what this array was made of
            let item = unsafe { JString::from_raw(env, item.as_raw()) };
            if let Err(e) = array.set_element(env, i, &item) {
                clear_exception(env);
                return Err(format!("{what}: {e}"));
            }
        }
        // the array outlives this frame as a plain object reference; the element locals above are
        // dropped with it, which is safe because the array itself holds them
        let raw = array.unwrap().into_raw();
        Ok(unsafe { JObject::from_raw(env, raw) }.auto())
    }

    /// allocates a Java string or reports the failure through `on_fail`'s error message. The result
    /// is auto-deleted: proxy traps hit these per field access, and ART's per-frame local reference
    /// table (512 entries) would otherwise overflow inside one long-running native call.
    pub(crate) fn new_jstring<'l>(
        &self,
        env: &mut Env<'l>,
        what: &str,
        s: &str,
    ) -> Result<Auto<'l, JObject<'l>>, String> {
        match env.new_string(s) {
            Ok(j) => Ok(JObject::from(j).auto()),
            Err(e) => {
                clear_exception(env);
                Err(format!("{what}: {e}"))
            }
        }
    }
}

/// one upcall argument, before it has been turned into a JNI local reference
pub(crate) enum Arg<'a> {
    Int(i32),
    Long(i64),
    Bool(bool),
    Str(&'a str),
    /// a `String` parameter the host may be handed null for
    OptStr(Option<&'a str>),
    Strs(&'a [String]),
    /// a `byte[]` parameter, null when absent
    Bytes(Option<&'a [u8]>),
}

/// an [`Arg`] that has been marshalled. Holds the local reference for as long as the `jvalue`
/// array built from it is alive - which is the invariant the per-upcall code existed to maintain.
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

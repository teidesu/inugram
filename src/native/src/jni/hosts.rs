//! Every host trait the modules declare, answered by one JNI upcall each.

use crate::LEVEL_ERROR;

use crate::api::canvas::CanvasHost;
use crate::api::globals::RandomHost;
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
use crate::api::telegram::rpc::RpcHost;
use crate::api::telegram::writes::WritesHost;
use crate::api::timers::TimerHost;
use crate::api::tl::proxy::TlHost;
use crate::api::ui::actions::ActionHost;
use crate::api::ui::dialogs::DialogHost;
use crate::api::ui::icons::IconHost;
use crate::api::ui::pages::UiHost;
use crate::api::ui::screens::ScreenHost;
use crate::sandbox::grants::GrantHost;

use super::bridge::{Arg, JniBridge};

impl RpcHost for JniBridge {
    fn on_register(&self, methods: &[String], callback_id: u32, scope: &str) -> Option<String> {
        self.call_refusal(
            "interceptRpc",
            self.on_rpc_register,
            &[Arg::Strs(methods), Arg::Int(callback_id as i32), Arg::Str(scope)],
        )
    }

    fn on_unregister(&self, callback_id: u32) {
        self.call_void("interceptRpc", self.on_rpc_unregister, &[Arg::Int(callback_id as i32)]);
    }

    fn on_invoke(&self, invoke_id: i64, slot: i32, request_wire: &str) -> Option<String> {
        self.call_refusal(
            "invokeRpc",
            self.on_invoke_rpc,
            &[Arg::Long(invoke_id), Arg::Int(slot), Arg::Str(request_wire)],
        )
    }

    fn on_next(&self, dispatch_id: i64, request_wire: &str) -> Option<String> {
        self.call_refusal("next()", self.on_rpc_next, &[Arg::Long(dispatch_id), Arg::Str(request_wire)])
    }

    fn on_complete(&self, dispatch_id: i64, result_wire: &str) {
        self.call_void("next()", self.on_rpc_complete, &[Arg::Long(dispatch_id), Arg::Str(result_wire)]);
    }

    fn on_update_register(&self, callback_id: u32, types: &[String], scope: &str) -> Option<String> {
        self.call_refusal(
            "onUpdate",
            self.on_update_register,
            &[Arg::Int(callback_id as i32), Arg::Strs(types), Arg::Str(scope)],
        )
    }

    fn on_update_unregister(&self, callback_id: u32) {
        self.call_void("onUpdate", self.on_update_unregister, &[Arg::Int(callback_id as i32)]);
    }

    fn on_intercept_update_register(&self, callback_id: u32, types: &[String]) -> Option<String> {
        self.call_refusal(
            "interceptUpdate",
            self.on_intercept_update_register,
            &[Arg::Int(callback_id as i32), Arg::Strs(types)],
        )
    }

    fn on_intercept_update_unregister(&self, callback_id: u32) {
        self.call_void("interceptUpdate", self.on_intercept_update_unregister, &[Arg::Int(callback_id as i32)]);
    }

    fn on_update_verdict(&self, dispatch_id: i64, deliver: bool) {
        self.call_void("interceptUpdate", self.on_update_verdict, &[Arg::Long(dispatch_id), Arg::Bool(deliver)]);
    }
}

impl DeserializeHost for JniBridge {
    fn on_rules_register(&self, callback_id: u32, rules_json: &str) -> Option<String> {
        self.call_refusal(
            "interceptDeserialize",
            self.on_deserialize_register,
            &[Arg::Int(callback_id as i32), Arg::Str(rules_json)],
        )
    }

    fn on_rules_unregister(&self, callback_id: u32) {
        self.call_void("interceptDeserialize", self.on_deserialize_unregister, &[Arg::Int(callback_id as i32)]);
    }

    fn on_middleware_register(&self, callback_id: u32, types_json: &str) -> Option<String> {
        self.call_refusal(
            "interceptDeserialize",
            self.on_deserialize_middleware_register,
            &[Arg::Int(callback_id as i32), Arg::Str(types_json)],
        )
    }

    fn on_middleware_unregister(&self, callback_id: u32) {
        self.call_void(
            "interceptDeserialize",
            self.on_deserialize_middleware_unregister,
            &[Arg::Int(callback_id as i32)],
        );
    }
}

impl AccountHost for JniBridge {
    fn accounts(&self) -> Option<String> {
        match self.call_string("accounts", self.on_accounts, &[]) {
            Ok(Some(json)) => Some(json),
            // "we could not ask" is not "nobody is logged in": the caller caches this answer, and
            // reading an empty list out of a transient JNI failure would tear down every live scope
            Ok(None) => {
                self.emit_console(LEVEL_ERROR, "accounts: the host returned null");
                None
            }
            Err(e) => {
                self.emit_console(LEVEL_ERROR, &e);
                None
            }
        }
    }
}

impl TlHost for JniBridge {
    fn tl_get(&self, handle: i64, key: &str) -> String {
        self.call_wire("tlGet", self.on_tl_get, &[Arg::Long(handle), Arg::Str(key)])
    }

    fn tl_set(&self, handle: i64, key: &str, value_wire: &str) -> Option<String> {
        self.call_refusal("tlSet", self.on_tl_set, &[Arg::Long(handle), Arg::Str(key), Arg::Str(value_wire)])
    }

    fn tl_has(&self, handle: i64, key: &str) -> i32 {
        self.call_int("tlHas", self.on_tl_has, &[Arg::Long(handle), Arg::Str(key)], -1)
    }

    fn tl_own_keys(&self, handle: i64) -> Option<String> {
        // Err (JNI failure / throwing listener) folds into None: the caller's expired path, which
        // throws into JS - still fail-closed, just with a less precise message (the exception
        // itself has already been dumped to logcat by clear_exception)
        self.call_string("tlOwnKeys", self.on_tl_own_keys, &[Arg::Long(handle)]).ok().flatten()
    }

    fn tl_copy(&self, handle: i64) -> Option<String> {
        self.call_string("tlCopy", self.on_tl_copy, &[Arg::Long(handle)]).ok().flatten()
    }

    fn tl_release(&self, handle: i64) {
        self.call_void("tlRelease", self.on_tl_release, &[Arg::Long(handle)]);
    }
}

impl ReadsHost for JniBridge {
    fn account_read(&self, account_id: i32, op: i32, arg: &str) -> String {
        self.call_wire("accountRead", self.on_account_read, &[Arg::Int(account_id), Arg::Int(op), Arg::Str(arg)])
    }

    fn resolve_peer(&self, account_id: i32, request_id: i64, spec: &str, kind: i32) -> Option<String> {
        self.call_refusal(
            "resolvePeer",
            self.on_resolve_peer,
            &[Arg::Int(account_id), Arg::Long(request_id), Arg::Str(spec), Arg::Int(kind)],
        )
    }

    fn account_fetch(&self, account_id: i32, request_id: i64, op: i32, arg: &str) -> Option<String> {
        self.call_refusal(
            "accountFetch",
            self.on_account_fetch,
            &[Arg::Int(account_id), Arg::Long(request_id), Arg::Int(op), Arg::Str(arg)],
        )
    }
}

impl WritesHost for JniBridge {
    fn account_write(&self, account_id: i32, request_id: i64, op: i32, arg: &str, values: &[String]) -> Option<String> {
        self.call_refusal(
            "accountWrite",
            self.on_account_write,
            &[Arg::Int(account_id), Arg::Long(request_id), Arg::Int(op), Arg::Str(arg), Arg::Strs(values)],
        )
    }

    fn message_file(&self, account_id: i32, value: &str) -> String {
        self.call_wire("getMessageFile", self.on_message_file, &[Arg::Int(account_id), Arg::Str(value)])
    }
}

impl KvHost for JniBridge {
    fn kv(&self, op: i32, key: &str, value: &str) -> String {
        self.call_wire("kv", self.on_kv, &[Arg::Int(op), Arg::Str(key), Arg::Str(value)])
    }
}

impl DialogHost for JniBridge {
    fn toast(&self, text: &str) {
        self.call_void("toast", self.on_ui_toast, &[Arg::Str(text)]);
    }

    fn dialog(&self, request_id: i64, options_json: &str) -> Option<String> {
        self.call_refusal("dialog", self.on_ui_dialog, &[Arg::Long(request_id), Arg::Str(options_json)])
    }

    fn chooser(&self, request_id: i64, options_json: &str) -> Option<String> {
        self.call_refusal("chooser", self.on_ui_chooser, &[Arg::Long(request_id), Arg::Str(options_json)])
    }
}

impl OpenUrlHost for JniBridge {
    fn open_url(&self, url: &str) {
        self.call_void("openUrl", self.on_open_url, &[Arg::Str(url)]);
    }
}

impl ClipboardHost for JniBridge {
    // "" for every failure, which is also what an empty clipboard answers: the channel carries the
    // user's own text and so cannot be tagged (see `ClipboardHost`'s own doc)
    fn read(&self) -> String {
        self.call_string("clipboardRead", self.on_clipboard_read, &[]).unwrap_or_default().unwrap_or_default()
    }

    fn write(&self, text: &str) {
        self.call_void("clipboardWrite", self.on_clipboard_write, &[Arg::Str(text)]);
    }
}

impl ScreenHost for JniBridge {
    fn current_screen(&self) -> String {
        self.call_string("getCurrentScreen", self.on_ui_current_screen, &[]).unwrap_or_default().unwrap_or_default()
    }
}

impl NotificationHost for JniBridge {
    fn notification_register(&self, callback_id: u32, events: &[String]) -> Option<String> {
        self.call_refusal(
            "addNotificationCenterDelegate",
            self.on_notification_register,
            &[Arg::Int(callback_id as i32), Arg::Strs(events)],
        )
    }

    fn notification_unregister(&self, callback_id: u32) {
        self.call_void(
            "addNotificationCenterDelegate",
            self.on_notification_unregister,
            &[Arg::Int(callback_id as i32)],
        );
    }
}

impl UiHost for JniBridge {
    fn ui_prompt(&self, request_id: i64, options_json: &str) -> Option<String> {
        self.call_refusal("prompt", self.on_ui_prompt, &[Arg::Long(request_id), Arg::Str(options_json)])
    }

    fn ui_open_page(&self, page_id: i64) -> Option<String> {
        self.call_refusal("openPage", self.on_ui_open_page, &[Arg::Long(page_id)])
    }

    fn ui_open_fragment(&self, handle: i64) -> Option<String> {
        self.call_refusal("openPage", self.on_ui_open_fragment, &[Arg::Long(handle)])
    }

    fn ui_register_settings(&self, page_id: i64) {
        self.call_void("registerSettingsPage", self.on_ui_register_settings, &[Arg::Long(page_id)]);
    }

    fn ui_unregister_settings(&self, page_id: i64) {
        self.call_void("registerSettingsPage", self.on_ui_unregister_settings, &[Arg::Long(page_id)]);
    }

    fn ui_invalidate(&self, page_id: i64) {
        self.call_void("invalidate", self.on_ui_invalidate, &[Arg::Long(page_id)]);
    }

    fn ui_open_menu(&self, menu_id: i64, page_id: i64, anchor_key: &str, items_json: &str) -> Option<String> {
        self.call_refusal(
            "openMenu",
            self.on_ui_open_menu,
            &[Arg::Long(menu_id), Arg::Long(page_id), Arg::Str(anchor_key), Arg::Str(items_json)],
        )
    }
}

impl IconHost for JniBridge {
    fn icon_resolves(&self, kind: i32, value: &str) -> bool {
        self.call_bool("iconResolves", self.on_icon_resolves, &[Arg::Int(kind), Arg::Str(value)])
    }
}

impl JvmHost for JniBridge {
    fn jvm(&self, op: i32, target: i64, name: &str, args: &[String]) -> String {
        self.call_wire("jvm", self.on_jvm, &[Arg::Int(op), Arg::Long(target), Arg::Str(name), Arg::Strs(args)])
    }
}

impl XposedHost for JniBridge {
    fn xposed(&self, op: i32, target: i64, name: &str, args: &[String]) -> String {
        self.call_wire("xposed", self.on_xposed, &[Arg::Int(op), Arg::Long(target), Arg::Str(name), Arg::Strs(args)])
    }
}

impl ActionHost for JniBridge {
    fn action_register(&self, kind: i32, token: u32, id: &str) -> Option<String> {
        self.call_refusal(
            "registerAction",
            self.on_action_register,
            &[Arg::Int(kind), Arg::Int(token as i32), Arg::Str(id)],
        )
    }

    fn action_unregister(&self, kind: i32, token: u32) {
        self.call_void("registerAction", self.on_action_unregister, &[Arg::Int(kind), Arg::Int(token as i32)]);
    }

    fn action_editor(&self, op: i32, surface: i64, payload_json: &str) -> Option<String> {
        self.call_refusal("action", self.on_action_editor, &[Arg::Int(op), Arg::Long(surface), Arg::Str(payload_json)])
    }
}

impl RandomHost for JniBridge {
    fn random_bytes(&self, out: &mut [u8]) -> bool {
        let wanted = out.len() as i32;
        self.call_bytes("randomBytes", self.on_random_bytes, &[Arg::Int(wanted)], out)
    }
}

impl TimerHost for JniBridge {
    fn schedule_wake(&self, delay_ms: i64) {
        self.call_void("timerSchedule", self.on_timer_schedule, &[Arg::Long(delay_ms)]);
    }
}

impl FetchHost for JniBridge {
    fn send(&self, request_id: i64, url: &str, spec_json: &str, body: Option<&[u8]>) -> Option<String> {
        // the body is the one thing here that can be megabytes, and it is bounded on the rust side
        // (`crate::api::io::blob::BUILD_LIMIT_BYTES`) before it ever reaches this allocation
        self.call_refusal(
            "fetch",
            self.on_fetch,
            &[Arg::Long(request_id), Arg::Str(url), Arg::Str(spec_json), Arg::Bytes(body)],
        )
    }

    fn abort(&self, request_id: i64) {
        self.call_void("fetch", self.on_fetch_abort, &[Arg::Long(request_id)]);
    }
}

impl CanvasHost for JniBridge {
    fn canvas(&self, op: i32, id: i64, arg: &str, bytes: Option<&[u8]>) -> String {
        // the command buffer, which is the one thing here that can be a megabyte - and is bounded
        // on this side by `crate::api::canvas::FLUSH_AT_BYTES` before it ever reaches this allocation
        match self.call_string(
            "canvas",
            self.on_canvas,
            &[Arg::Int(op), Arg::Long(id), Arg::Str(arg), Arg::Bytes(bytes)],
        ) {
            Ok(Some(answer)) => answer,
            Ok(None) => String::new(),
            Err(e) => e,
        }
    }
}

impl GrantHost for JniBridge {
    fn is_granted(&self, name: &str, target: Option<&str>, mode: i32) -> bool {
        self.call_bool("checkGrant", self.on_check_grant, &[Arg::Str(name), Arg::OptStr(target), Arg::Int(mode)])
    }
}

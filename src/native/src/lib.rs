//! The inugram plugin engine: an rquickjs (quickjs-ng) sandbox per plugin, reached from
//! `desu.inugram.helpers.plugins.QuickJs` through [`jni`].
//!
//! A context is created, used and destroyed on a single thread (PluginManager funnels every engine
//! op through Utilities.globalQueue), and there is no exception. rquickjs's `parallel` feature is
//! deliberately **off**: it would swap the runtime's `RefCell` for a non-reentrant
//! `std::sync::Mutex` (rquickjs `safe_ref.rs`), turning a same-thread re-entry from a loud
//! `BorrowMutError` abort into a silent deadlock of whichever thread did it - on a queue shared
//! with the whole app. The two surfaces the app calls synchronously from its own threads
//! (`interceptDeserialize`'s middleware form, `inu.xposed`) post to globalQueue and park the caller
//! on the answer.

use std::sync::Arc;

/// `Send + Sync` because rquickjs's handler types demand it of anything hung off the `Runtime`,
/// which two of its holders are: `limits`'s interrupt handler and `rpc`'s promise-rejection
/// tracker. Every engine is still entered from one thread.
pub type Log = Arc<dyn Fn(&str) + Send + Sync>;

mod api;
mod jni;
mod sandbox;
#[cfg(test)]
mod testing;
mod utils;

/// `QuickJs.onConsole` level for an engine diagnostic the plugin survives - a JNI failure, a
/// throwing host listener, a wire the bridge could not decode. Same level `console.error` binds.
const LEVEL_ERROR: i32 = 3;

/// `QuickJs.LEVEL_FAULT`: plugin code threw or rejected somewhere the engine could only report it
/// (a middleware, an `onUpdate` handler, a settings callback, an unhandled rejection, a stage cut
/// down by the entry deadline). The host stores the message and disables the plugin, so a
/// middleware that fails every `messages.sendMessage` fails it once instead of forever.
/// `console.*` binds levels 0..4 only.
const LEVEL_FAULT: i32 = 5;

/// Marks a diagnostic as a fault for [`classify_log`], which strips it before the message leaves
/// for the host. Only this crate writes it, and only at the front of a message it composed itself,
/// so a plugin whose own error text contains one cannot promote its diagnostic into a fault.
const FAULT_PREFIX: &str = "\u{1}";

pub(crate) fn fault(message: impl std::fmt::Display) -> String {
    format!("{FAULT_PREFIX}{message}")
}

fn classify_log(message: &str) -> (i32, &str) {
    match message.strip_prefix(FAULT_PREFIX) {
        Some(rest) => (LEVEL_FAULT, rest),
        None => (LEVEL_ERROR, message),
    }
}

#[cfg(test)]
#[path = "lib_tests.rs"]
mod lib_tests;

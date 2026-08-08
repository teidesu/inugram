//! Reaching the app and the runtime under it: reflection, method hooking, the app's own event bus,
//! the clipboard and handing the system a page to open. `xposed/` carries the two libraries hooking
//! is built on, which a plugin never names.

pub(crate) mod clipboard;
pub(crate) mod jvm;
pub(crate) mod notifications;
pub(crate) mod open_url;
pub(crate) mod xposed;

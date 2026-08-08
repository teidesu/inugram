//! Reaching the app and the runtime under it: the three surfaces a plugin calls - reflection,
//! method hooking, and the app's own event bus. `xposed/` carries the two libraries hooking is
//! built on, which a plugin never names.

pub(crate) mod jvm;
pub(crate) mod notifications;
pub(crate) mod xposed;

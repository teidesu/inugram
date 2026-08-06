//! Reaching the app and the runtime under it: reflection, method hooking and the two libraries
//! that make hooking possible, plus the app's own event bus.

pub(crate) mod elf;
pub(crate) mod jvm;
pub(crate) mod lsplant;
pub(crate) mod notifications;
pub(crate) mod xposed;

//! Everything a plugin can name, grouped by domain.
//!
//! The membership rule is the whole point of the folder: a module belongs here if plugin code can
//! reach it. What is left outside is [`crate::jni`] (the bridge), [`crate::sandbox`] (what bounds a
//! plugin: the permission gate, the ceilings, the registration bookkeeping) and [`crate::utils`]
//! (plumbing that installs nothing and is owned by no domain).
//!
//! The six loose modules belong to no domain but to the realm itself: the error vocabulary, the
//! globals quickjs-ng and the prelude install, `inu.info()`, the plugin's own lifetime callbacks,
//! the timer wheel, and the whatwg url classes - which share their file with the http egress screen
//! `fetch` and `inu.openUrl` run, deliberately not the same parser.

pub(crate) mod canvas;
pub(crate) mod error;
pub(crate) mod globals;
pub(crate) mod info;
pub(crate) mod io;
pub(crate) mod lifecycle;
pub(crate) mod platform;
pub(crate) mod telegram;
pub(crate) mod timers;
pub(crate) mod tl;
pub(crate) mod ui;
pub(crate) mod url;

//! What a plugin puts on screen: settings pages, rows in menus the app owns, icons, the modals it
//! can only ask the app for, and the navigation events it can watch.

/// The three modals cross as one upcall (`PluginListener.uiModal`), being one contract - `None` ==
/// shown and settled later, `Some(msg)` == an immediate refusal - over one queue; which of them is
/// asked for is this op. They keep their own host traits all the same: [`dialogs`] and [`pages`]
/// own different pending tables and are settled by different natives, so the merge stops at the
/// bridge. Keep in sync with Kotlin `PluginUi.OP_*`.
pub(crate) const OP_DIALOG: i32 = 0;
pub(crate) const OP_PROMPT: i32 = 1;
pub(crate) const OP_CHOOSER: i32 = 2;

pub(crate) mod actions;
pub(crate) mod dialogs;
pub(crate) mod icons;
pub(crate) mod pages;
pub(crate) mod screens;

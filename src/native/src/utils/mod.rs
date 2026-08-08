//! Plumbing with no surface of its own: nothing here installs anything a plugin can name.
//!
//! That is the whole membership rule. Reading a plugin's arguments, loading a prelude's bytecode
//! and defining the property forms a rust-built prototype uses are each used by most of the crate
//! and owned by none of it - which is why they are not in [`crate::sandbox`], whose modules *are*
//! the realm a plugin runs in.

pub(crate) mod arguments;
pub(crate) mod prelude;
pub(crate) mod shape;

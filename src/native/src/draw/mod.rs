//! `inu.canvas`. The engine records commands and the host rasterizes; what crosses is a command
//! buffer, and everything in it has already been decomposed to move/line/cubic/close.

pub(crate) mod canvas;
pub(crate) mod css;
pub(crate) mod geom;

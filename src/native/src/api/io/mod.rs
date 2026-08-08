//! Bytes a plugin can hold, keep, fetch or store. Each bounds what it moves, because a native op
//! runs past the execution deadline's back-edge polling.

pub(crate) mod blob;
pub(crate) mod fetch;
pub(crate) mod fs;
pub(crate) mod kv;

//! What bounds a plugin, and nothing it can name: the single gate every permission decision goes
//! through, the cpu and native-memory ceilings, and the registration bookkeeping that makes the
//! `Disposer` rules `src/plugins/common.d.ts` states hold identically for every `on*`/`intercept*`.

pub(crate) mod grants;
pub(crate) mod limits;
pub(crate) mod registry;

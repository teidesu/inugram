//! The sandbox every api runs inside: argument conversion, the cpu and memory ceilings,
//! the error vocabulary, the globals quickjs-ng and the prelude install, registration bookkeeping,
//! the timer wheel and the http url screen the two apis that take one share.

pub(crate) mod argv;
pub(crate) mod deadline;
pub(crate) mod error;
pub(crate) mod globals;
pub(crate) mod registry;
pub(crate) mod shape;
pub(crate) mod timers;
pub(crate) mod url;

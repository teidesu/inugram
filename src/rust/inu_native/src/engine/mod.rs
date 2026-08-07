//! The sandbox every api runs inside: argument conversion, the cpu and memory ceilings,
//! the error vocabulary, the globals quickjs-ng and the prelude install, registration bookkeeping,
//! the timer wheel, and urls - both the whatwg `URL` classes and the http egress screen the two
//! apis that *send* one share, which are deliberately not the same parser.

pub(crate) mod argv;
pub(crate) mod deadline;
pub(crate) mod error;
pub(crate) mod globals;
pub(crate) mod registry;
pub(crate) mod shape;
pub(crate) mod timers;
pub(crate) mod url;

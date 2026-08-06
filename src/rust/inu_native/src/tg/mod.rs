//! The telegram account surface: reading the app's caches, writing through its send path, and
//! the three interception points on the request/update/deserialize pipelines.

pub(crate) mod account;
pub(crate) mod deserialize;
pub(crate) mod progress;
pub(crate) mod reads;
pub(crate) mod rpc;
pub(crate) mod writes;

use std::sync::Arc;

pub type Log = Arc<dyn Fn(&str) + Send + Sync>;

macro_rules! set_fn {
  ($object:expr, $name:expr, $ctx:expr, $state:ident, $f:expr) => {{
    let $state = $state.clone();
    $object.set($name, rquickjs::Function::new($ctx.clone(), $f)?)?;
  }};
}

mod api;
mod jni;
mod runtime;
mod sandbox;
#[cfg(test)]
mod testing;
mod utils;

const LEVEL_ERROR: i32 = 3;

const LEVEL_FAULT: i32 = 5;

const FAULT_PREFIX: &str = "\u{1}";

pub(crate) fn fault(message: impl std::fmt::Display) -> String {
  format!("{FAULT_PREFIX}{message}")
}

fn classify_log(message: &str) -> (i32, &str) {
  match message.strip_prefix(FAULT_PREFIX) {
    Some(rest) => (LEVEL_FAULT, rest),
    None => (LEVEL_ERROR, message),
  }
}

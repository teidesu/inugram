use std::collections::{HashMap, HashSet};
use std::rc::Rc;

use rquickjs::{Ctx, Result as JsResult};

use crate::api::error::PluginErrorCode;

pub const MATCH_EXACT: i32 = 0;
pub const MATCH_DOMAIN: i32 = 1;
pub const MATCH_NAMESPACE: i32 = 2;

pub trait GrantHost {
  fn is_granted(&self, name: &str, target: Option<&str>, mode: i32) -> bool;

  fn check_grant(&self, ctx: &Ctx<'_>, name: &str, target: Option<&str>, mode: i32) -> JsResult<()> {
    if self.is_granted(name, target, mode) {
      return Ok(());
    }
    let token = grant_token(name, target);
    {
      let message: &str = &format!("missing grant: {token}");
      let grant: &str = &token;
      PluginErrorCode::NotGranted(grant).throw(ctx, message)
    }
  }
}

fn grant_token(name: &str, target: Option<&str>) -> String {
  match target {
    Some(target) => format!("{name}({target})"),
    None => name.to_string(),
  }
}

pub(crate) struct CachedGrantHost {
  unscoped: HashSet<String>,
  scoped: HashMap<String, Vec<String>>,
}

impl CachedGrantHost {
  /// `name, scope` pairs as `PluginPermissions.toPairs` hands them over, an unscoped grant's scope
  /// being empty: the manifest is parsed once, host-side, and nothing here reads a token
  pub(crate) fn from_pairs<T: AsRef<str>>(pairs: &[T]) -> Rc<Self> {
    let mut unscoped = HashSet::new();
    let mut scoped: HashMap<String, Vec<String>> = HashMap::new();
    for pair in pairs.chunks_exact(2) {
      let (name, scope) = (pair[0].as_ref(), pair[1].as_ref());
      if scope.is_empty() {
        unscoped.insert(name.to_string());
      } else {
        scoped.entry(name.to_string()).or_default().push(scope.to_string());
      }
    }
    Rc::new(CachedGrantHost { unscoped, scoped })
  }

  /// fixtures spell grants the way a manifest does
  #[cfg(test)]
  pub(crate) fn new<T: AsRef<str>>(tokens: impl IntoIterator<Item = T>) -> Rc<Self> {
    let mut pairs = Vec::new();
    for token in tokens {
      let token = token.as_ref().trim();
      match token.split_once('(') {
        None if !token.is_empty() => pairs.extend([token.to_string(), String::new()]),
        Some((name, rest)) => {
          for scope in rest.strip_suffix(')').into_iter().flat_map(|s| s.split(',')).map(str::trim) {
            if !scope.is_empty() {
              pairs.extend([name.trim().to_string(), scope.to_string()]);
            }
          }
        }
        None => {}
      }
    }
    Self::from_pairs(&pairs)
  }

  pub(crate) fn as_host(self: &Rc<Self>) -> Rc<dyn GrantHost> {
    self.clone()
  }
}

impl GrantHost for CachedGrantHost {
  fn is_granted(&self, name: &str, target: Option<&str>, mode: i32) -> bool {
    if self.unscoped.contains(name) {
      return true;
    }
    let Some(target) = target else {
      return self.scoped.contains_key(name);
    };
    self
      .scoped
      .get(name)
      .is_some_and(|scopes| scopes.iter().any(|scope| scope_matches(scope, target, mode)))
  }
}

fn scope_matches(scope: &str, target: &str, mode: i32) -> bool {
  let scope_lower = scope.to_ascii_lowercase();
  let target_lower = target.to_ascii_lowercase();
  match mode {
    MATCH_DOMAIN => target_lower == scope_lower || target_lower.ends_with(&format!(".{scope_lower}")),
    MATCH_NAMESPACE if scope == "*" => true,
    MATCH_NAMESPACE => match scope.strip_suffix(".*") {
      Some(prefix) => target.starts_with(&format!("{prefix}.")),
      None => scope == target,
    },
    MATCH_EXACT => scope == target,
    _ => false,
  }
}

#[cfg(test)]
pub(crate) type TestGrantHost = CachedGrantHost;

#[cfg(test)]
#[path = "grants_tests.rs"]
mod grants_tests;

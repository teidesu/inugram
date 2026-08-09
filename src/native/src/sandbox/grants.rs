use std::rc::Rc;

use rquickjs::{Ctx, Result as JsResult};

use crate::api::error::throw_plugin_error;

pub const MATCH_EXACT: i32 = 0;
pub const MATCH_DOMAIN: i32 = 1;
pub const MATCH_NAMESPACE: i32 = 2;

pub trait GrantHost {
    fn is_granted(&self, name: &str, target: Option<&str>, mode: i32) -> bool;
}

fn grant_token(name: &str, target: Option<&str>) -> String {
    match target {
        Some(target) => format!("{name}({target})"),
        None => name.to_string(),
    }
}

pub fn check_grant(
    ctx: &Ctx<'_>,
    host: &Rc<dyn GrantHost>,
    name: &str,
    target: Option<&str>,
    mode: i32,
) -> JsResult<()> {
    if host.is_granted(name, target, mode) {
        return Ok(());
    }
    let token = grant_token(name, target);
    throw_plugin_error(ctx, "not-granted", &format!("missing grant: {token}"), Some(&token), None, None)
}

#[cfg(test)]
pub(crate) struct TestGrantHost {
    allowed: std::collections::HashSet<(String, Option<String>)>,
}

#[cfg(test)]
impl TestGrantHost {
    pub(crate) fn new(tokens: &[&str]) -> Rc<Self> {
        let mut allowed = std::collections::HashSet::new();
        for token in tokens {
            match token.split_once('(') {
                Some((name, rest)) => {
                    let scopes = rest.trim_end_matches(')');
                    for scope in scopes.split(',').filter(|s| !s.is_empty()) {
                        allowed.insert((name.to_string(), Some(scope.to_string())));
                    }
                }
                None => {
                    allowed.insert((token.to_string(), None));
                }
            }
        }
        Rc::new(TestGrantHost { allowed })
    }

    pub(crate) fn as_host(self: &Rc<Self>) -> Rc<dyn GrantHost> {
        self.clone()
    }
}

#[cfg(test)]
impl GrantHost for TestGrantHost {
    fn is_granted(&self, name: &str, target: Option<&str>, mode: i32) -> bool {
        if self.allowed.contains(&(name.to_string(), None)) {
            return true;
        }
        let Some(target) = target else {
            return self.allowed.iter().any(|(granted, _)| granted == name);
        };
        self.allowed.iter().any(|(granted, scope)| {
            let Some(scope) = scope else { return false };
            granted == name && scope_matches(scope, target, mode)
        })
    }
}

#[cfg(test)]
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
        _ => scope == target,
    }
}

#[cfg(test)]
#[path = "grants_tests.rs"]
mod grants_tests;

//! The one gate every permission decision goes through.
//!
//! Grants are checked at call/registration time, never at binding-install time: every gated
//! binding exists in the realm whatever the manifest says, and a plugin missing a grant gets a
//! `not-granted` error naming the exact token that would have allowed the call. Which is why this
//! is one function: `check_grant` -> `onCheckGrant` -> `PluginPermissions.allows`, fail-closed, and
//! nothing else anywhere may decide it.

use std::rc::Rc;

use rquickjs::{Ctx, Result as JsResult};

use crate::sandbox::error::throw_plugin_error;

/// ordinals of `desu.inugram.core.plugins.ScopeMatch`, which is what the `onCheckGrant` upcall's
/// `mode` argument is
pub const MATCH_EXACT: i32 = 0;
pub const MATCH_DOMAIN: i32 = 1;
pub const MATCH_NAMESPACE: i32 = 2;

/// stand-in for the Kotlin `QuickJs.onCheckGrant` upcall; fail-closed, `false` == denied
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
    /// `tokens` are grant tokens as a manifest writes them: `kv`, `invokeRpc(messages.sendMessage)`
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
        // like the real host (`PluginManager`'s `grantChecker`), a `None` target asks whether the
        // grant is held at all - `PluginPermissions.has` - and not whether it is unscoped
        let Some(target) = target else {
            return self.allowed.iter().any(|(granted, _)| granted == name);
        };
        self.allowed.iter().any(|(granted, scope)| {
            let Some(scope) = scope else { return false };
            granted == name && scope_matches(scope, target, mode)
        })
    }
}

/// mirrors `PluginPermissions.scopeMatches`, which is what the real host behind this trait runs.
/// Only the modes a test actually passes are implemented; anything else stays exact, which is the
/// fail-closed reading.
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
mod tests {
    use super::*;
    use crate::sandbox::error::install_plugin_error;
    use rquickjs::{Context, Runtime};

    fn setup() -> (Runtime, Context) {
        let rt = Runtime::new().unwrap();
        let ctx = Context::full(&rt).unwrap();
        ctx.with(|ctx| install_plugin_error(&ctx).unwrap());
        (rt, ctx)
    }

    #[test]
    fn check_grant_throws_a_not_granted_error_naming_the_token() {
        let (_rt, ctx) = setup();
        let host: Rc<dyn GrantHost> = TestGrantHost::new(&["invokeRpc(users.getUsers)"]).as_host();
        ctx.with(|ctx| {
            assert!(check_grant(&ctx, &host, "invokeRpc", Some("users.getUsers"), MATCH_EXACT).is_ok());
            let err = check_grant(&ctx, &host, "invokeRpc", Some("messages.sendMessage"), MATCH_EXACT)
                .unwrap_err();
            assert!(matches!(err, rquickjs::Error::Exception));
            ctx.globals().set("e", ctx.catch()).unwrap();
            let got: String = ctx
                .eval("JSON.stringify([e.code, e.grant, e.message, e instanceof inu.PluginError])")
                .unwrap();
            assert_eq!(
                got,
                r#"["not-granted","invokeRpc(messages.sendMessage)","missing grant: invokeRpc(messages.sendMessage)",true]"#,
            );
        });
    }

    #[test]
    fn an_unscoped_grant_allows_any_target() {
        let host = TestGrantHost::new(&["kv", "interceptRpc"]);
        assert!(host.is_granted("kv", None, MATCH_EXACT));
        assert!(host.is_granted("interceptRpc", Some("users.getUsers"), MATCH_EXACT));
        assert!(!host.is_granted("invokeRpc", Some("users.getUsers"), MATCH_EXACT));
    }
}

use super::*;
use crate::api::error::install_plugin_error;
use rquickjs::{Context, Runtime};

fn setup() -> (Runtime, Context) {
  let (rt, ctx) = crate::testing::harness::new_engine();
  ctx.with(|ctx| install_plugin_error(&ctx).unwrap());
  (rt, ctx)
}

#[test]
fn check_grant_throws_a_not_granted_error_naming_the_token() {
  let (_rt, ctx) = setup();
  let host: Rc<dyn GrantHost> = CachedGrantHost::new(["invokeRpc(users.getUsers)"]);
  ctx.with(|ctx| {
    assert!(host.check_grant(&ctx, "invokeRpc", Some("users.getUsers"), MATCH_EXACT).is_ok());
    let err = host.check_grant(&ctx, "invokeRpc", Some("messages.sendMessage"), MATCH_EXACT).unwrap_err();
    assert!(matches!(err, rquickjs::Error::Exception));
    ctx.globals().set("e", ctx.catch()).unwrap();
    let got: String = ctx.eval("JSON.stringify([e.code, e.grant, e.message, e instanceof inu.PluginError])").unwrap();
    assert_eq!(
      got,
      r#"["not-granted","invokeRpc(messages.sendMessage)","missing grant: invokeRpc(messages.sendMessage)",true]"#,
    );
  });
}

#[test]
fn scope_matching_is_exact_suffix_or_prefix_by_mode() {
  const CASES: &[(&str, &str, i32, bool)] = &[
    ("users.getUsers", "users.getUsers", MATCH_EXACT, true),
    ("users.getUsers", "Users.getUsers", MATCH_EXACT, false),
    ("users.getUsers", "users.getUsersX", MATCH_EXACT, false),
    ("example.com", "example.com", MATCH_DOMAIN, true),
    ("Example.com", "api.EXAMPLE.com", MATCH_DOMAIN, true),
    ("example.com", "badexample.com", MATCH_DOMAIN, false),
    ("example.com", "example.com.evil.org", MATCH_DOMAIN, false),
    ("*", "java.io.File", MATCH_NAMESPACE, true),
    ("java.lang.*", "java.lang.String", MATCH_NAMESPACE, true),
    ("java.lang.*", "java.lang.reflect.Method", MATCH_NAMESPACE, true),
    ("java.lang.*", "java.lang", MATCH_NAMESPACE, false),
    ("java.lang.*", "java.language.X", MATCH_NAMESPACE, false),
    ("java.lang.String", "java.lang.String", MATCH_NAMESPACE, true),
    ("java.lang.String", "java.lang.StringBuilder", MATCH_NAMESPACE, false),
    ("example.com", "example.com", 99, false),
  ];
  for &(scope, target, mode, granted) in CASES {
    let host = CachedGrantHost::from_pairs(&["x", scope]);
    assert_eq!(host.is_granted("x", Some(target), mode), granted, "{scope} vs {target}");
  }
}

#[test]
fn an_empty_scope_in_a_pair_is_an_unscoped_grant() {
  let host = CachedGrantHost::from_pairs(&["openUrl", "", "fetch", "a.com", "fetch", "b.com"]);
  assert!(host.is_granted("openUrl", Some("anything"), MATCH_EXACT));
  assert!(host.is_granted("fetch", Some("x.b.com"), MATCH_DOMAIN));
  assert!(!host.is_granted("fetch", Some("c.com"), MATCH_DOMAIN));
  assert!(host.is_granted("fetch", None, MATCH_EXACT));
  assert!(!host.is_granted("clipboard", None, MATCH_EXACT));
}

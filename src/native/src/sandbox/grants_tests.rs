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
  let host: Rc<dyn GrantHost> = TestGrantHost::new(&["invokeRpc(users.getUsers)"]).as_host();
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
fn an_unscoped_grant_allows_any_target() {
  let host = TestGrantHost::new(&["openUrl", "interceptRpc"]);
  assert!(host.is_granted("openUrl", None, MATCH_EXACT));
  assert!(host.is_granted("interceptRpc", Some("users.getUsers"), MATCH_EXACT));
  assert!(!host.is_granted("invokeRpc", Some("users.getUsers"), MATCH_EXACT));
}

#[test]
fn cached_grants_match_domains_and_namespaces() {
  let host = CachedGrantHost::new(["fetch(Example.com)", "unsafe.jvm(java.lang.*)"]);
  assert!(host.is_granted("fetch", Some("cdn.example.com"), MATCH_DOMAIN));
  assert!(host.is_granted("unsafe.jvm", Some("java.lang.String"), MATCH_NAMESPACE));
  assert!(!host.is_granted("unsafe.jvm", Some("java.io.File"), MATCH_NAMESPACE));
  assert!(!host.is_granted("fetch", Some("example.com"), 99));
}

/// the same table `PluginPermissionsTest` reads, so the two matchers cannot drift apart silently
#[test]
fn scope_matching_agrees_with_the_host_s_table() {
  const TABLE: &str = include_str!("../../../test/grants/scope-matches.tsv");
  let mut rows = 0;
  for line in TABLE.lines().filter(|l| !l.starts_with('#') && !l.is_empty()) {
    let [scope, target, mode, granted]: [&str; 4] = line.split('\t').collect::<Vec<_>>().try_into().unwrap();
    let mode = match mode {
      "exact" => MATCH_EXACT,
      "domain" => MATCH_DOMAIN,
      "namespace" => MATCH_NAMESPACE,
      other => panic!("unknown mode {other}"),
    };
    let host = CachedGrantHost::from_pairs(&["x", scope]);
    assert_eq!(host.is_granted("x", Some(target), mode), granted == "true", "{line}");
    rows += 1;
  }
  assert!(rows > 10);
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

use super::*;
use crate::api::error::install_plugin_error;
use rquickjs::{Context, Runtime};

fn setup() -> (Runtime, Context) {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  ctx.with(|ctx| install_plugin_error(&ctx, &crate::testing::harness::inu_namespace(&ctx)).unwrap());
  (rt, ctx)
}

#[test]
fn check_grant_throws_a_not_granted_error_naming_the_token() {
  let (_rt, ctx) = setup();
  let host: Rc<dyn GrantHost> = TestGrantHost::new(&["invokeRpc(users.getUsers)"]).as_host();
  ctx.with(|ctx| {
    assert!(check_grant(&ctx, &host, "invokeRpc", Some("users.getUsers"), MATCH_EXACT).is_ok());
    let err = check_grant(&ctx, &host, "invokeRpc", Some("messages.sendMessage"), MATCH_EXACT).unwrap_err();
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
  let host = TestGrantHost::new(&["kv", "interceptRpc"]);
  assert!(host.is_granted("kv", None, MATCH_EXACT));
  assert!(host.is_granted("interceptRpc", Some("users.getUsers"), MATCH_EXACT));
  assert!(!host.is_granted("invokeRpc", Some("users.getUsers"), MATCH_EXACT));
}

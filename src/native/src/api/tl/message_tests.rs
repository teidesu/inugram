use super::*;
use rquickjs::{Context, Runtime};

fn setup() -> (Runtime, Context) {
  let (rt, ctx) = crate::testing::harness::new_engine();
  ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    let shared = crate::api::tl::utils::install_utils(&ctx, &inu).unwrap();
    install_message(&ctx, &shared, &inu).unwrap();
  });
  (rt, ctx)
}

#[test]
fn the_bundled_message_test_plugin_passes() {
  let (rt, ctx) = setup();
  let lines =
    crate::testing::harness::run_capturing_console(&rt, &ctx, crate::testing::test_plugin!("message-test.js"));
  // this fixture is `inu.Message` alone, which is the whole point of the surface being pure;
  // the oracle's live half is `rpc.rs`'s to answer
  crate::testing::harness::assert_oracle_exact_skipping(
    &lines,
    "message test done",
    65,
    &["SKIP the live half: no invokeRpc in this context"],
  );
}

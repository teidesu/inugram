use super::*;

const ORACLE: &str = crate::testing::test_plugin!("info-test.js");

/// needs no JNI: the header the app hands [`install_inu`] is `PluginManifest.raw` flattened, which the
/// oracle's own manifest supplies
#[test]
fn the_bundled_info_test_plugin_passes() {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let info = Arc::new(InuInfo {
    app_version: "6.81".into(),
    app_build: "6822".into(),
    api_version: 1,
    layer: 214,
    language: "en".into(),
    header: crate::testing::harness::manifest_header(ORACLE),
  });
  ctx.with(|ctx| install_inu(&ctx, info, &crate::testing::harness::get_api_globals(&ctx)).unwrap());

  let lines = crate::testing::harness::run_capturing_console(&rt, &ctx, ORACLE);
  crate::testing::harness::assert_oracle_exact(&lines, "info test done", 11);
}

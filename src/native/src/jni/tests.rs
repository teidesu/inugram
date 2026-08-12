#[cfg(test)]
mod info_tests {
  use crate::api::info::{build_info_object, install_inu, InuInfo};
  use rquickjs::{Context, Runtime};
  use std::sync::Arc;

  #[test]
  fn a_repeated_header_directive_reaches_js_as_an_array() {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let info = InuInfo {
      app_version: "1.0".into(),
      app_build: "1".into(),
      api_version: 1,
      layer: 1,
      language: "en".into(),
      // the wire repeats the key once per value, exactly as QuickJs.installInfo flattens it
      header: vec![("grant".into(), "kv".into()), ("name".into(), "demo".into()), ("grant".into(), "openUrl".into())],
    };
    ctx.with(|ctx| {
      let obj = build_info_object(ctx.clone(), &info).unwrap();
      ctx.globals().set("__info", obj).unwrap();
      let is_array: bool = ctx.eval("Array.isArray(__info.header.grant)").unwrap();
      assert!(is_array, "a repeated directive must not collapse to a string");
      let grants: Vec<String> = ctx.eval("__info.header.grant").unwrap();
      assert_eq!(grants, vec!["kv".to_string(), "openUrl".to_string()]);
      let names: Vec<String> = ctx.eval("__info.header.name").unwrap();
      assert_eq!(names, vec!["demo".to_string()], "a single-value directive is still an array");
    });
  }

  const ORACLE: &str = include_str!("../../../res/assets-debug/inu_plugins/info-test.js");

  /// The bundled oracle is the only test `inu.info()` gets on a device, and it needs no JNI to
  /// run here: [`install_inu`] is an ordinary crate function, and the header the app hands it is
  /// `PluginManifest.raw` flattened key-per-value, which the oracle's *own* manifest supplies.
  /// So what the app would have built the object out of is what builds it here.
  #[test]
  fn the_bundled_info_test_plugin_passes() {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
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
}

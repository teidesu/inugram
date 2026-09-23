pub(crate) mod harness;

macro_rules! test_plugin {
  ($name:literal) => {
    concat!(
      include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/../test/plugins/test-prelude.js")),
      include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/../test/plugins/", $name))
    )
  };
}
pub(crate) use test_plugin;

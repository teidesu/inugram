use std::{env, fs, path::PathBuf};

use rquickjs::{
  module::{Module, WriteOptions, WriteOptionsEndianness},
  CatchResultExt, Context, Runtime,
};

const PRELUDES: &[(&str, &str, &str)] = &[
  ("console", "<inu:console>", "src/js/console.js"),
  ("globals", "<inu:globals>", "src/js/globals.js"),
  ("url", "<inu:url>", "src/js/url.js"),
  ("fetch", "<inu:fetch>", "src/js/fetch.js"),
  ("jvm", "<inu:jvm>", "src/js/jvm.js"),
  ("events", "<inu:events>", "src/js/events.js"),
  ("reads", "<inu:reads>", "src/js/reads.js"),
  ("send_message", "<inu:send_message>", "src/js/send_message.js"),
  ("writes", "<inu:writes>", "src/js/writes.js"),
  ("message", "<inu:message>", "src/js/message.js"),
  ("utils", "<inu:utils>", "src/js/utils.js"),
];

fn main() {
  println!("cargo:rerun-if-changed=build.rs");
  let out = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR"));
  let runtime = Runtime::new().expect("quickjs runtime");
  let context = Context::full(&runtime).expect("quickjs context");
  context.with(|ctx| {
    for (stem, name, path) in PRELUDES {
      println!("cargo:rerun-if-changed={path}");
      let source = fs::read_to_string(path).unwrap_or_else(|e| panic!("{path}: {e}"));
      let module = Module::declare(ctx.clone(), *name, format!("export default {source}"))
        .catch(&ctx)
        .unwrap_or_else(|e| panic!("{path} failed to compile: {e}"));
      let bytecode = module
        .write(WriteOptions {
          endianness: WriteOptionsEndianness::Little,
          // Source text is only used by `Function.prototype.toString` on private helpers. Keep the
          // line numbers produced by `strip_debug`.
          strip_source: true,
          ..Default::default()
        })
        .catch(&ctx)
        .unwrap_or_else(|e| panic!("{path} failed to serialize: {e}"));
      fs::write(out.join(format!("{stem}.qbc")), bytecode).unwrap_or_else(|e| panic!("{stem}.qbc: {e}"));
    }
  });
}

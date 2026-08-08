//! Compiles every prelude to quickjs bytecode, so an engine loads its object graph in one pass over
//! a flat buffer instead of parsing ~87 KB of javascript per plugin.
//!
//! The bytecode format is tied to the exact quickjs build that reads it, which is why this is
//! generated here rather than committed: the `rquickjs` compiling it below and the one linked into
//! the cdylib are the same dependency, resolved from the same lockfile. It is also endian-dependent
//! and carries no marker of which it is, so it is written little-endian explicitly - every abi the
//! app ships is little-endian, and a host that is not would otherwise emit bytecode that reads as
//! garbage on a device.

use std::{env, fs, path::PathBuf};

use rquickjs::{
    module::{Module, WriteOptions, WriteOptionsEndianness},
    CatchResultExt, Context, Runtime,
};

/// `(artifact stem, the name a stack trace shows, source)`. Each file is one parenthesized arrow
/// expression: the factory its module calls with the host state that surface is allowed to see.
const PRELUDES: &[(&str, &str, &str)] = &[
    ("globals", "<inu:globals>", "src/js/globals.js"),
    ("url", "<inu:url>", "src/js/url.js"),
    ("fetch", "<inu:fetch>", "src/js/fetch.js"),
    ("jvm", "<inu:jvm>", "src/js/jvm.js"),
    ("xposed", "<inu:xposed>", "src/js/xposed.js"),
    ("events", "<inu:events>", "src/js/events.js"),
    ("reads", "<inu:reads>", "src/js/reads.js"),
    ("sendmsg", "<inu:sendmsg>", "src/js/sendmsg.js"),
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
                    // the source text backs nothing but `Function.prototype.toString` on helpers no
                    // plugin is handed; line numbers are `strip_debug`'s and stay
                    strip_source: true,
                    ..Default::default()
                })
                .catch(&ctx)
                .unwrap_or_else(|e| panic!("{path} failed to serialize: {e}"));
            fs::write(out.join(format!("{stem}.qbc")), bytecode).unwrap_or_else(|e| panic!("{stem}.qbc: {e}"));
        }
    });
}

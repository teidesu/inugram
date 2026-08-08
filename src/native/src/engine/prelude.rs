//! Loading a prelude: the one place `build.rs`'s artifacts are turned back into the factory the
//! surface that owns them calls.
//!
//! Every prelude is a module with a single default export, so what a caller gets here is what
//! `ctx.eval` used to hand it. The name a stack trace shows is baked into the bytecode at build
//! time rather than passed per call, which is what keeps `<inu:reads>` reading the same as before.

use rquickjs::{module::Evaluated, Ctx, Function, Module, Result as JsResult};

/// # Safety
///
/// `bytecode` must be `build.rs` output for this build. `JS_ReadObject` trusts what it is handed,
/// so bytecode from a different quickjs is undefined behaviour rather than an error - which is why
/// nothing but `include_bytes!` of an `OUT_DIR` artifact may reach this.
pub fn load<'js>(ctx: &Ctx<'js>, bytecode: &[u8]) -> JsResult<Function<'js>> {
    let module = unsafe { Module::load(ctx.clone(), bytecode) }?;
    // a prelude has no top-level await, so the promise this answers with is already settled and
    // the factory is readable in the same turn
    let (module, _): (Module<Evaluated>, _) = module.eval()?;
    module.get("default")
}

#[cfg(test)]
#[path = "prelude_tests.rs"]
mod tests;

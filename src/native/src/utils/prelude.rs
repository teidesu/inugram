use rquickjs::{module::Evaluated, Ctx, Function, Module, Result as JsResult};

pub fn load<'js>(ctx: &Ctx<'js>, bytecode: &[u8]) -> JsResult<Function<'js>> {
  // SAFETY: build.rs produces the bytecode with this linked QuickJS build and explicitly sets its byte order.
  let module = unsafe { Module::load(ctx.clone(), bytecode) }?;
  let (module, _): (Module<Evaluated>, _) = module.eval()?;
  module.get("default")
}

#[cfg(test)]
#[path = "prelude_tests.rs"]
mod tests;

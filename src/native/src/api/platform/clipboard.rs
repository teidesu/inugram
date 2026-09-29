use std::rc::Rc;

use rquickjs::{Ctx, Function, Object, Result as JsResult};

use crate::sandbox::grants::{GrantHost, MATCH_EXACT};

pub trait ClipboardHost {
  fn read(&self) -> String;
  fn write(&self, text: &str);
}

pub fn install_clipboard<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn ClipboardHost>,
  grants: Rc<dyn GrantHost>,
  globals: &crate::api::Globals<'js>,
) -> JsResult<()> {
  let clipboard = Object::new(ctx.clone())?;
  let (read_host, read_grants) = (host.clone(), grants.clone());
  clipboard.set(
    "read",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<String> {
      read_grants.check_grant(&ctx, "clipboard.read", None, MATCH_EXACT)?;
      Ok(read_host.read())
    })?,
  )?;
  clipboard.set(
    "write",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, text: rquickjs::Coerced<String>| -> JsResult<()> {
      grants.check_grant(&ctx, "clipboard.write", None, MATCH_EXACT)?;
      host.write(&text.0);
      Ok(())
    })?,
  )?;

  globals.inu.set("clipboard", clipboard)?;
  Ok(())
}

#[cfg(test)]
#[path = "clipboard_tests.rs"]
mod clipboard_tests;

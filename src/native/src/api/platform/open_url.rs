use std::rc::Rc;

use rquickjs::{Ctx, Function, Object, Result as JsResult};

use crate::{
  api::error::PluginErrorCode,
  sandbox::grants::{check_grant, GrantHost, MATCH_EXACT},
};

pub trait OpenUrlHost {
  fn open_url(&self, url: &str);
}

pub(crate) fn screen_external_url(url: &str) -> Result<(), String> {
  crate::api::url::parse_http_url("openUrl", url).map(|_| ())
}

pub fn install_open_url<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn OpenUrlHost>,
  grants: Rc<dyn GrantHost>,
  globals: &crate::api::Globals<'js>,
) -> JsResult<()> {
  globals.inu.set(
    "openUrl",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, url: String| -> JsResult<()> {
      check_grant(&ctx, &grants, "openUrl", None, MATCH_EXACT)?;
      if let Err(why) = screen_external_url(&url) {
        return PluginErrorCode::InvalidArgument.throw(&ctx, &why);
      }
      host.open_url(&url);
      Ok(())
    })?,
  )?;
  Ok(())
}

#[cfg(test)]
#[path = "open_url_tests.rs"]
mod open_url_tests;

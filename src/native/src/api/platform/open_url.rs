use std::rc::Rc;

use rquickjs::{Ctx, Function, Object, Result as JsResult};

use crate::sandbox::grants::{check_grant, GrantHost, MATCH_EXACT};

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
  inu: &Object<'js>,
) -> JsResult<()> {
  let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, url: String| -> JsResult<()> {
    check_grant(&ctx, &grants, "openUrl", None, MATCH_EXACT)?;
    if let Err(why) = screen_external_url(&url) {
      return crate::api::error::PluginErrorCode::InvalidArgument.throw(&ctx, &why);
    }
    host.open_url(&url);
    Ok(())
  })?;
  inu.set("openUrl", f)?;
  Ok(())
}

#[cfg(test)]
#[path = "open_url_tests.rs"]
mod open_url_tests;

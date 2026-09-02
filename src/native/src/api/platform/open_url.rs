use std::rc::Rc;

use rquickjs::{Ctx, Function, Object, Result as JsResult};

use crate::{
  api::error::PluginErrorCode,
  sandbox::grants::{GrantHost, MATCH_EXACT},
};

pub trait OpenUrlHost {
  fn open_url(&self, url: &str);
}

pub(crate) fn screen_external_url(url: &str) -> Result<(), String> {
  if url.chars().any(|c| c.is_whitespace() || c.is_control()) {
    return Err("openUrl: a url may not contain whitespace or control characters".to_string());
  }
  if let Some((scheme, rest)) = url.split_once(':') {
    if scheme.eq_ignore_ascii_case("tg") {
      let action = rest
        .strip_prefix("//")
        .unwrap_or(rest)
        .trim_start_matches('/')
        .split(['?', '#'])
        .next()
        .unwrap_or_default();
      if action.is_empty() {
        return Err("openUrl: a tg url has no action".to_string());
      }
      return Ok(());
    }
  }
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
      grants.check_grant(&ctx, "openUrl", None, MATCH_EXACT)?;
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

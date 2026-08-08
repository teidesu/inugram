//! `inu.openUrl`: hands the system a page to open, and nothing else.

use std::rc::Rc;

use rquickjs::{Ctx, Function, Object, Result as JsResult};

use crate::api::error;
use crate::sandbox::grants::{check_grant, GrantHost, MATCH_EXACT};

/// stand-in for the Kotlin `QuickJs.OpenUrlListener` interface. Already screened by
/// [`screen_external_url`]; fire-and-forget, there being no ui to fail into.
pub trait OpenUrlHost {
    fn open_url(&self, url: &str);
}

/// What `inu.openUrl` may hand to the system, and nothing else: [`crate::api::url`]'s screen,
/// which `fetch` runs too, since both end up handing the string to something that re-parses it.
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
            return error::throw_plugin_error(&ctx, "invalid-argument", &why, None, None, None);
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

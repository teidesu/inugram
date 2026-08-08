//! `inu.openUrl`: hands the system a page to open, and nothing else.

use std::rc::Rc;

use rquickjs::{Ctx, Function, Object, Result as JsResult};

use super::ApiState;
use crate::grants::{check_grant, MATCH_EXACT};
use crate::sandbox::error;

/// What `inu.openUrl` may hand to the system, and nothing else: [`crate::sandbox::url`]'s screen,
/// which `fetch` runs too, since both end up handing the string to something that re-parses it.
pub(crate) fn screen_external_url(url: &str) -> Result<(), String> {
    crate::sandbox::url::parse_http_url("openUrl", url).map(|_| ())
}

pub(super) fn install<'js>(ctx: &Ctx<'js>, state: &Rc<ApiState>, inu: &Object<'js>) -> JsResult<()> {
    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, url: String| -> JsResult<()> {
            check_grant(&ctx, &state2.grants, "openUrl", None, MATCH_EXACT)?;
            if let Err(why) = screen_external_url(&url) {
                return error::throw_plugin_error(&ctx, "invalid-argument", &why, None, None, None);
            }
            state2.host.open_url(&url);
            Ok(())
        })?;
        inu.set("openUrl", f)?;
    }
    Ok(())
}

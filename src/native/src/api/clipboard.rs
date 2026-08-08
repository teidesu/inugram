//! `inu.clipboard`: two grants two tiers apart, because reading what the user copied and writing
//! what they will paste are different powers.

use std::rc::Rc;

use rquickjs::{Ctx, Function, Object, Result as JsResult};

use super::ApiState;
use crate::grants::{check_grant, MATCH_EXACT};

pub(super) fn install<'js>(ctx: &Ctx<'js>, state: &Rc<ApiState>, inu: &Object<'js>) -> JsResult<()> {
    let clipboard = Object::new(ctx.clone())?;
    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<String> {
            check_grant(&ctx, &state2.grants, "clipboard.read", None, MATCH_EXACT)?;
            Ok(state2.host.clipboard_read())
        })?;
        clipboard.set("read", f)?;
    }
    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, text: rquickjs::Coerced<String>| -> JsResult<()> {
            check_grant(&ctx, &state2.grants, "clipboard.write", None, MATCH_EXACT)?;
            state2.host.clipboard_write(&text.0);
            Ok(())
        })?;
        clipboard.set("write", f)?;
    }
    inu.set("clipboard", clipboard)?;
    Ok(())
}

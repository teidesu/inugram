//! `inu.clipboard`: two grants two tiers apart, because reading what the user copied and writing
//! what they will paste are different powers.

use std::rc::Rc;

use rquickjs::{Ctx, Function, Object, Result as JsResult};

use crate::sandbox::grants::{check_grant, GrantHost, MATCH_EXACT};

/// stand-in for the Kotlin `QuickJs.ClipboardListener` interface.
///
/// [`ClipboardHost::read`] is the one upcall in this crate that is **not** a tagged wire and cannot
/// be: it carries whatever the user last copied, so a leading `E` is a plain clipboard far more
/// often than it is an error wire. It answers text or the empty string, and so does a host that
/// cannot read the clipboard at all.
pub trait ClipboardHost {
    fn read(&self) -> String;
    fn write(&self, text: &str);
}

struct ClipboardState {
    host: Rc<dyn ClipboardHost>,
    grants: Rc<dyn GrantHost>,
}

pub fn install_clipboard<'js>(
    ctx: &Ctx<'js>,
    host: Rc<dyn ClipboardHost>,
    grants: Rc<dyn GrantHost>,
    inu: &Object<'js>,
) -> JsResult<()> {
    let state = Rc::new(ClipboardState { host, grants });
    let clipboard = Object::new(ctx.clone())?;
    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<String> {
            check_grant(&ctx, &state2.grants, "clipboard.read", None, MATCH_EXACT)?;
            Ok(state2.host.read())
        })?;
        clipboard.set("read", f)?;
    }
    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, text: rquickjs::Coerced<String>| -> JsResult<()> {
            check_grant(&ctx, &state2.grants, "clipboard.write", None, MATCH_EXACT)?;
            state2.host.write(&text.0);
            Ok(())
        })?;
        clipboard.set("write", f)?;
    }
    inu.set("clipboard", clipboard)?;
    Ok(())
}

#[cfg(test)]
#[path = "clipboard_tests.rs"]
mod clipboard_tests;

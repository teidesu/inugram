//! The `inu` object every surface hangs its members off.
//!
//! Created by whichever installs first and read by all the others, which is why it is here rather
//! than owned by one of them: it had been living in [`crate::api::error`], where nothing about
//! the name says that most of that module's 52 importers want this and not an error.

use rquickjs::{Ctx, Object, Result as JsResult};

pub(crate) fn get_or_create_inu<'js>(ctx: &Ctx<'js>) -> JsResult<Object<'js>> {
    match ctx.globals().get::<_, Object>("inu") {
        Ok(o) => Ok(o),
        Err(_) => {
            let o = Object::new(ctx.clone())?;
            ctx.globals().set("inu", o.clone())?;
            Ok(o)
        }
    }
}

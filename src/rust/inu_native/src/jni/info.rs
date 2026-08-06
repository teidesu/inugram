//! `inu.info()`: what the manifest and the app say about the running plugin.

use rquickjs::{Ctx, Function, Object};
use std::sync::Arc;

/// backing data for inu.info(); built fresh into a JS object on each call
pub(crate) struct InuInfo {
    pub(crate) app_version: String,
    pub(crate) app_build: String,
    pub(crate) api_version: i32,
    pub(crate) layer: i32,
    pub(crate) language: String,
    pub(crate) header: Vec<(String, String)>,
}

pub(crate) fn build_info_object<'js>(ctx: Ctx<'js>, info: &InuInfo) -> rquickjs::Result<Object<'js>> {
    let obj = Object::new(ctx.clone())?;
    obj.set("platform", "android")?;
    obj.set("appVersion", info.app_version.as_str())?;
    obj.set("appBuild", info.app_build.as_str())?;
    obj.set("apiVersion", info.api_version)?;
    obj.set("layer", info.layer)?;
    obj.set("language", info.language.as_str())?;
    // the key repeats once per value on the wire (a directive may appear more than once), and
    // `common.d.ts` declares `header` as Record<string, string[]>, so each one groups into an array
    let header = Object::new(ctx.clone())?;
    for (k, v) in info.header.iter() {
        let existing: Option<rquickjs::Array> = header.get(k.as_str()).ok();
        let values = match existing {
            Some(array) => array,
            None => {
                let array = rquickjs::Array::new(ctx.clone())?;
                header.set(k.as_str(), array.clone())?;
                array
            }
        };
        values.set(values.len(), v.as_str())?;
    }
    obj.set("header", header)?;
    Ok(obj)
}

pub(crate) fn install_inu(ctx: &Ctx, info: Arc<InuInfo>) -> rquickjs::Result<()> {
    let info_fn = Function::new(ctx.clone(), move |ctx| build_info_object(ctx, &info))?;
    let inu = crate::engine::error::get_or_create_inu(ctx)?;
    inu.set("info", info_fn)?;
    Ok(())
}

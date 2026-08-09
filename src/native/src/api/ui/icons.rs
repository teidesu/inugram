use std::rc::Rc;

use rquickjs::{Ctx, Exception, Function, Object, Result as JsResult, Value};

use crate::api::{
  error::{make_quota_error, throw_invalid_argument, throw_not_found, throw_not_granted},
  platform::jvm::{self, JvmState},
};

pub const SVG_LIMIT_BYTES: usize = 64 * 1024;

const MAX_RESOURCE_NAME: usize = 128;

const ICON_TAG: &str = "__inuIcon";
pub(crate) const RETAINED_VALUE_TAG: &str = "__inuRetainedIconValue";

pub const KIND_RESOURCE: i32 = 0;
pub const KIND_SVG: i32 = 1;

pub(crate) struct Icon<'js> {
  pub(crate) spec: String,
  pub(crate) retained_value: Option<Value<'js>>,
}

pub trait IconHost {
  fn icon_resolves(&self, kind: i32, value: &str) -> bool;
}

const COMMON_ICONS: &[(&str, &str)] = &[
  ("archive", "msg_archive"),
  ("bookmark", "msg_saved"),
  ("bot", "msg_bot"),
  ("channel", "msg_channel"),
  ("check", "ic_ab_done"),
  ("close", "msg_close"),
  ("copy", "msg_copy"),
  ("delete", "msg_delete"),
  ("download", "msg_download"),
  ("edit", "msg_edit"),
  ("eye", "msg_views"),
  ("eyeOff", "msg_archive_hide"),
  ("forward", "msg_forward"),
  ("group", "msg_groups"),
  ("info", "msg_info"),
  ("link", "msg_link"),
  ("lock", "msg_secret"),
  ("minus", "msg_remove"),
  ("more", "ic_ab_other"),
  ("mute", "msg_mute"),
  ("pin", "msg_pin"),
  ("plus", "msg_add"),
  ("refresh", "msg_retry"),
  ("reply", "menu_reply"),
  ("search", "msg_search"),
  ("settings", "msg_settings"),
  ("share", "msg_share"),
  ("star", "msg_fave"),
  ("translate", "msg_translate"),
  ("unmute", "msg_unmute"),
  ("user", "msg_contacts"),
];

fn lookup_common(name: &str) -> Option<&'static str> {
  COMMON_ICONS.binary_search_by(|(api, _)| (*api).cmp(name)).ok().map(|at| COMMON_ICONS[at].1)
}

fn is_resource_name(name: &str) -> bool {
  !name.is_empty()
    && name.len() <= MAX_RESOURCE_NAME
    && !name.as_bytes()[0].is_ascii_digit()
    && name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
}

enum SvgReject {
  TooLarge(usize),
  NotSvg,
  Markup,
}

fn check_svg(source: &str) -> Result<(), SvgReject> {
  if source.len() > SVG_LIMIT_BYTES {
    return Err(SvgReject::TooLarge(source.len()));
  }
  if !source.contains("<svg") {
    return Err(SvgReject::NotSvg);
  }
  let mut rest = source;
  while let Some(at) = rest.find("<!") {
    if !rest[at..].starts_with("<!--") {
      return Err(SvgReject::Markup);
    }
    rest = &rest[at + 2..];
  }
  Ok(())
}

fn resource_spec(name: &str) -> String {
  format!("r{name}")
}

fn svg_spec(source: &str) -> String {
  format!("s{source}")
}

fn validate_spec<'js>(ctx: &Ctx<'js>, what: &str, spec: &str) -> JsResult<()> {
  let valid = match spec.as_bytes().first() {
    Some(b'r') => is_resource_name(&spec[1..]),
    Some(b's') => check_svg(&spec[1..]).is_ok(),
    _ => false,
  };
  if valid {
    return Ok(());
  }
  throw_invalid_argument(ctx, &format!("{what}: 'icon' is not an icon inu.icons handed out"))
}

pub fn opt_icon<'js>(
  ctx: &Ctx<'js>,
  obj: &Object<'js>,
  what: &str,
  jvm: Option<&Rc<JvmState>>,
) -> JsResult<Option<Icon<'js>>> {
  let value: Value = obj.get("icon").map_err(|_| Exception::throw_type(ctx, &format!("{what}: cannot read 'icon'")))?;
  if value.is_undefined() || value.is_null() {
    return Ok(None);
  }
  let icon = value
    .as_object()
    .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: 'icon' must come from inu.icons")))?;
  let spec = icon
    .get::<_, Option<String>>(ICON_TAG)
    .ok()
    .flatten()
    .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: 'icon' must come from inu.icons")))?;
  if let Some(handle) = spec.strip_prefix('j') {
    let retained_value: Value = icon
      .get(RETAINED_VALUE_TAG)
      .map_err(|_| Exception::throw_type(ctx, &format!("{what}: 'icon' must come from inu.icons")))?;
    let Some(jvm) = jvm else {
      return Err(Exception::throw_type(ctx, &format!("{what}: 'icon' must come from inu.icons")));
    };
    let expected = handle.parse::<i64>().ok();
    if expected.is_none() || jvm::handle_id(ctx, jvm, &retained_value)? != expected.unwrap() {
      return Err(Exception::throw_type(ctx, &format!("{what}: 'icon' must come from inu.icons")));
    }
    return Ok(Some(Icon {
      spec,
      retained_value: Some(retained_value),
    }));
  }
  validate_spec(ctx, what, &spec)?;
  Ok(Some(Icon { spec, retained_value: None }))
}

fn new_icon<'js>(ctx: &Ctx<'js>, spec: String) -> JsResult<Object<'js>> {
  let obj = Object::new(ctx.clone())?;
  obj.set(ICON_TAG, spec)?;
  Ok(obj)
}

fn as_str<'js>(ctx: &Ctx<'js>, what: &str, value: &Value<'js>) -> JsResult<String> {
  match value.as_string() {
    Some(s) => Ok(s.to_string()?),
    None => Err(Exception::throw_type(ctx, &format!("{what}: expected a string"))),
  }
}

fn js_common<'js>(ctx: &Ctx<'js>, host: &Rc<dyn IconHost>, name: Value<'js>) -> JsResult<Object<'js>> {
  let name = as_str(ctx, "icons.common", &name)?;
  let Some(resource) = lookup_common(&name) else {
    return throw_invalid_argument(ctx, &format!("icons.common: unknown icon '{name}'"));
  };
  if !host.icon_resolves(KIND_RESOURCE, resource) {
    return throw_not_found(ctx, &format!("icons.common: this app ships no '{resource}' for '{name}'"));
  }
  new_icon(ctx, resource_spec(resource))
}

fn js_resource_icon<'js>(ctx: &Ctx<'js>, host: &Rc<dyn IconHost>, name: Value<'js>) -> JsResult<Object<'js>> {
  let name = as_str(ctx, "android.resourceIcon", &name)?;
  if !is_resource_name(&name) {
    return throw_invalid_argument(ctx, &format!("android.resourceIcon: '{name}' is not a drawable name"));
  }
  if !host.icon_resolves(KIND_RESOURCE, &name) {
    return throw_not_found(ctx, &format!("android.resourceIcon: no drawable named '{name}'"));
  }
  new_icon(ctx, resource_spec(&name))
}

fn js_svg<'js>(ctx: &Ctx<'js>, host: &Rc<dyn IconHost>, source: Value<'js>) -> JsResult<Object<'js>> {
  let source = as_str(ctx, "icons.svg", &source)?;
  match check_svg(&source) {
    Ok(()) => {}
    Err(SvgReject::TooLarge(size)) => {
      let error = make_quota_error(
        ctx,
        &format!("icons.svg: {size} bytes of source, the limit is {SVG_LIMIT_BYTES}"),
        size as i64,
        SVG_LIMIT_BYTES as i64,
      )?;
      return Err(ctx.throw(error));
    }
    Err(SvgReject::NotSvg) => return throw_invalid_argument(ctx, "icons.svg: the source carries no <svg> element"),
    Err(SvgReject::Markup) => {
      return throw_invalid_argument(ctx, "icons.svg: a doctype or other markup declaration is not allowed")
    }
  }
  if !host.icon_resolves(KIND_SVG, &source) {
    return throw_invalid_argument(ctx, "icons.svg: the source did not parse");
  }
  new_icon(ctx, svg_spec(&source))
}

fn js_drawable_icon<'js>(ctx: &Ctx<'js>, jvm: &Rc<JvmState>, drawable: Value<'js>) -> JsResult<Object<'js>> {
  let handle = jvm::handle_id(ctx, jvm, &drawable)?;
  if handle < 0 {
    return Err(Exception::throw_type(ctx, "android.drawableIcon: expected a java object from inu.jvm"));
  }
  let icon = new_icon(ctx, format!("j{handle}"))?;
  icon.set(RETAINED_VALUE_TAG, drawable)?;
  Ok(icon)
}

pub fn install_icons<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn IconHost>,
  jvm: Option<Rc<JvmState>>,
  inu: &Object<'js>,
) -> JsResult<()> {
  let icons = Object::new(ctx.clone())?;
  {
    let host = host.clone();
    icons.set(
      "common",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, name: Value<'js>| js_common(&ctx, &host, name))?,
    )?;
  }
  {
    let host = host.clone();
    icons.set(
      "svg",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, source: Value<'js>| js_svg(&ctx, &host, source))?,
    )?;
  }
  inu.set("icons", icons)?;

  let android: Object = match inu.get::<_, Object>("android") {
    Ok(o) => o,
    Err(_) => {
      let o = Object::new(ctx.clone())?;
      inu.set("android", o.clone())?;
      o
    }
  };
  android.set(
    "resourceIcon",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, name: Value<'js>| js_resource_icon(&ctx, &host, name))?,
  )?;
  android.set(
    "drawableIcon",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, drawable: Value<'js>| match jvm.as_ref() {
      Some(jvm) => js_drawable_icon(&ctx, jvm, drawable),
      None => throw_not_granted(&ctx, "android.drawableIcon: needs @grant unsafe.jvm", "unsafe.jvm"),
    })?,
  )?;

  Ok(())
}

#[cfg(test)]
#[path = "icons_tests.rs"]
pub(crate) mod tests;

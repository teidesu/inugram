use std::rc::Rc;

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rquickjs::function::Opt;
use rquickjs::{Ctx, Exception, Function, IntoJs, Object, Result as JsResult, Value};

use crate::api::{error::PluginErrorCode, platform::jvm::JvmState};

pub const SVG_LIMIT_BYTES: usize = 64 * 1024;

const MAX_RESOURCE_NAME: usize = 128;

const ICON_TAG: &str = "__inuIcon";
pub(crate) const RETAINED_VALUE_TAG: &str = "__inuRetainedIconValue";

pub const KIND_RESOURCE: i32 = 0;
pub const KIND_SVG: i32 = 1;
pub const KIND_RAW_ANIMATION: i32 = 2;

const STICKER_SLUG_LIMIT: usize = 64;
const STICKER_EMOJI_LIMIT: usize = 64;

pub(crate) struct Icon<'js> {
  pub(crate) spec: String,
  pub(crate) retained_value: Option<Value<'js>>,
}

pub trait IconHost {
  fn icon_resolves(&self, kind: i32, value: &str) -> bool;

  /// the curated name -> drawable table lives in the host (`CommonIcons.kt`): None = unknown name
  fn common_icon(&self, name: &str) -> Option<String>;
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

fn is_positive_id(value: &str) -> bool {
  value.parse::<i64>().is_ok_and(|id| id > 0)
}

fn is_sticker_slug(value: &str) -> bool {
  !value.is_empty()
    && value.len() <= STICKER_SLUG_LIMIT
    && !value.as_bytes()[0].is_ascii_digit()
    && value.bytes().all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
}

fn is_sticker_spec(spec: &str) -> bool {
  let Some((selector, slug)) = spec.split_once('\n') else {
    return false;
  };
  if !is_sticker_slug(slug) || selector.len() < 2 {
    return false;
  }
  match selector.as_bytes()[0] {
    b'i' => selector[1..].parse::<usize>().is_ok_and(|index| index <= u16::MAX as usize),
    b'd' => is_positive_id(&selector[1..]),
    b'e' => URL_SAFE_NO_PAD
      .decode(&selector[1..])
      .is_ok_and(|emoji| !emoji.is_empty() && emoji.len() <= STICKER_EMOJI_LIMIT && String::from_utf8(emoji).is_ok()),
    _ => false,
  }
}

fn animation_payload(spec: &str) -> Option<&str> {
  let mode = spec.get(1..)?;
  match mode.as_bytes().first()? {
    b'0' | b'1' | b's' => mode.get(1..),
    b'n' => {
      let (count, payload) = mode.get(1..)?.split_once(':')?;
      count.parse::<u16>().ok().filter(|count| *count > 0).map(|_| payload)
    }
    _ => None,
  }
}

fn validate_spec<'js>(ctx: &Ctx<'js>, what: &str, spec: &str) -> JsResult<()> {
  let valid = match spec.as_bytes().first() {
    Some(b'r') => is_resource_name(&spec[1..]),
    Some(b's') => check_svg(&spec[1..]).is_ok(),
    Some(b'a') => animation_payload(spec).is_some_and(is_resource_name),
    Some(b'e') => animation_payload(spec).is_some_and(is_positive_id),
    Some(b't') => animation_payload(spec).is_some_and(is_sticker_spec),
    _ => false,
  };
  if valid {
    return Ok(());
  }
  {
    let message: &str = &format!("{what}: 'icon' is not an icon inu.icons handed out");
    PluginErrorCode::InvalidArgument.throw(ctx, message)
  }
}

pub fn opt_icon<'js>(
  ctx: &Ctx<'js>,
  obj: &Object<'js>,
  what: &str,
  jvm: Option<&Rc<JvmState>>,
) -> JsResult<Option<Icon<'js>>> {
  let value: Value = obj.get("icon").map_err(|_| Exception::throw_type(ctx, &format!("{what}: cannot read 'icon'")))?;
  icon_from_value(ctx, value, what, jvm)
}

pub(crate) fn icon_from_value<'js>(
  ctx: &Ctx<'js>,
  value: Value<'js>,
  what: &str,
  jvm: Option<&Rc<JvmState>>,
) -> JsResult<Option<Icon<'js>>> {
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
    let Ok(expected) = handle.parse::<i64>() else {
      return Err(Exception::throw_type(ctx, &format!("{what}: 'icon' must come from inu.icons")));
    };
    if jvm.handle_id(ctx, &retained_value)? != expected {
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
  let Some(resource) = host.common_icon(&name).filter(|r| is_resource_name(r)) else {
    return {
      let message: &str = &format!("icons.common: unknown icon '{name}'");
      PluginErrorCode::InvalidArgument.throw(ctx, message)
    };
  };
  if !host.icon_resolves(KIND_RESOURCE, &resource) {
    return {
      let message: &str = &format!("icons.common: this app ships no '{resource}' for '{name}'");
      PluginErrorCode::NotFound.throw(ctx, message)
    };
  }
  new_icon(ctx, resource_spec(&resource))
}

fn js_resource_icon<'js>(ctx: &Ctx<'js>, host: &Rc<dyn IconHost>, name: Value<'js>) -> JsResult<Object<'js>> {
  let name = as_str(ctx, "android.resourceIcon", &name)?;
  if !is_resource_name(&name) {
    return {
      let message: &str = &format!("android.resourceIcon: '{name}' is not a drawable name");
      PluginErrorCode::InvalidArgument.throw(ctx, message)
    };
  }
  if !host.icon_resolves(KIND_RESOURCE, &name) {
    return {
      let message: &str = &format!("android.resourceIcon: no drawable named '{name}'");
      PluginErrorCode::NotFound.throw(ctx, message)
    };
  }
  new_icon(ctx, resource_spec(&name))
}

fn js_svg<'js>(ctx: &Ctx<'js>, host: &Rc<dyn IconHost>, source: Value<'js>) -> JsResult<Object<'js>> {
  let source = as_str(ctx, "icons.svg", &source)?;
  match check_svg(&source) {
    Ok(()) => {}
    Err(SvgReject::TooLarge(size)) => {
      return PluginErrorCode::QuotaExceeded(size as i64, SVG_LIMIT_BYTES as i64)
        .throw(ctx, &format!("icons.svg: {size} bytes of source, the limit is {SVG_LIMIT_BYTES}"));
    }
    Err(SvgReject::NotSvg) => {
      return PluginErrorCode::InvalidArgument.throw(ctx, "icons.svg: the source carries no <svg> element")
    }
    Err(SvgReject::Markup) => {
      return PluginErrorCode::InvalidArgument
        .throw(ctx, "icons.svg: a doctype or other markup declaration is not allowed")
    }
  }
  if !host.icon_resolves(KIND_SVG, &source) {
    return PluginErrorCode::InvalidArgument.throw(ctx, "icons.svg: the source did not parse");
  }
  new_icon(ctx, svg_spec(&source))
}

#[derive(Clone, Copy)]
enum AnimationMode {
  Once,
  Forever,
  Repeat(u16),
  Static,
}

impl AnimationMode {
  fn wire(self) -> String {
    match self {
      Self::Once => "0".to_string(),
      Self::Forever => "1".to_string(),
      Self::Repeat(count) => format!("n{count}:"),
      Self::Static => "s".to_string(),
    }
  }
}

fn read_animation_mode<'js>(
  ctx: &Ctx<'js>,
  options: Opt<Value<'js>>,
  what: &str,
  loops_by_default: bool,
) -> JsResult<AnimationMode> {
  let Some(options) = options.0 else {
    return Ok(if loops_by_default { AnimationMode::Forever } else { AnimationMode::Once });
  };
  let Some(options) = options.as_object() else {
    return Err(Exception::throw_type(ctx, &format!("{what}: options must be an object")));
  };
  let loop_value: Value = options.get("loop")?;
  let static_value: Value = options.get("static")?;
  let loop_is_explicit = !loop_value.is_undefined();
  let mode = if loop_value.is_undefined() {
    if loops_by_default {
      AnimationMode::Forever
    } else {
      AnimationMode::Once
    }
  } else if let Some(loop_animation) = loop_value.as_bool() {
    if loop_animation {
      AnimationMode::Forever
    } else {
      AnimationMode::Once
    }
  } else if let Some(repeats) = loop_value.as_number() {
    if !repeats.is_finite() || repeats.fract() != 0.0 || !(0.0..=f64::from(u16::MAX)).contains(&repeats) {
      return PluginErrorCode::InvalidArgument
        .throw(ctx, &format!("{what}: options.loop must be an integer from 0 to {}", u16::MAX));
    }
    if repeats == 0.0 {
      AnimationMode::Once
    } else {
      AnimationMode::Repeat(repeats as u16)
    }
  } else {
    return Err(Exception::throw_type(ctx, &format!("{what}: options.loop must be a boolean or number")));
  };
  let static_animation = if static_value.is_undefined() {
    false
  } else {
    static_value
      .as_bool()
      .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: options.static must be a boolean")))?
  };
  if loop_is_explicit && !matches!(mode, AnimationMode::Once) && static_animation {
    return PluginErrorCode::InvalidArgument
      .throw(ctx, &format!("{what}: options.loop and options.static cannot both be true"));
  }
  Ok(if static_animation { AnimationMode::Static } else { mode })
}

fn js_raw_animation<'js>(
  ctx: &Ctx<'js>,
  host: &Rc<dyn IconHost>,
  name: Value<'js>,
  options: Opt<Value<'js>>,
  what: &str,
  loops_by_default: bool,
) -> JsResult<Object<'js>> {
  let name = as_str(ctx, what, &name)?;
  if !is_resource_name(&name) {
    return PluginErrorCode::InvalidArgument.throw(ctx, &format!("{what}: '{name}' is not a raw resource name"));
  }
  if !host.icon_resolves(KIND_RAW_ANIMATION, &name) {
    return PluginErrorCode::NotFound.throw(ctx, &format!("{what}: no animation named '{name}'"));
  }
  let mode = read_animation_mode(ctx, options, what, loops_by_default)?;
  new_icon(ctx, format!("a{}{name}", mode.wire()))
}

fn js_animation<'js>(ctx: &Ctx<'js>, host: &Rc<dyn IconHost>, name: Value<'js>) -> JsResult<Object<'js>> {
  let preset = as_str(ctx, "icons.animation", &name)?;
  let resource = match preset.as_str() {
    "success" => "done",
    "error" => "error",
    "info" => "info",
    "loading" => "timer_3",
    _ => return PluginErrorCode::InvalidArgument.throw(ctx, &format!("icons.animation: unknown preset '{preset}'")),
  };
  js_raw_animation(ctx, host, resource.into_js(ctx)?, Opt(None), "icons.animation", false)
}

fn js_custom_emoji<'js>(ctx: &Ctx<'js>, id: Value<'js>, options: Opt<Value<'js>>) -> JsResult<Object<'js>> {
  let id = as_str(ctx, "icons.customEmoji", &id)?;
  if !is_positive_id(&id) {
    return PluginErrorCode::InvalidArgument.throw(ctx, "icons.customEmoji: expected a positive int64 string");
  }
  let mode = read_animation_mode(ctx, options, "icons.customEmoji", true)?;
  new_icon(ctx, format!("e{}{id}", mode.wire()))
}

fn js_sticker<'js>(ctx: &Ctx<'js>, options: Value<'js>) -> JsResult<Object<'js>> {
  let Some(options) = options.as_object() else {
    return Err(Exception::throw_type(ctx, "icons.sticker: expected an options object"));
  };
  let slug: String = options
    .get("slug")
    .map_err(|_| Exception::throw_type(ctx, "icons.sticker: 'slug' must be a string"))?;
  if !is_sticker_slug(&slug) {
    return PluginErrorCode::InvalidArgument.throw(ctx, "icons.sticker: invalid sticker-set slug");
  }
  let index: Value = options.get("index")?;
  let emoji: Value = options.get("emoji")?;
  let id: Value = options.get("id")?;
  let count = [!index.is_undefined(), !emoji.is_undefined(), !id.is_undefined()]
    .into_iter()
    .filter(|selected| *selected)
    .count();
  if count != 1 {
    return PluginErrorCode::InvalidArgument
      .throw(ctx, "icons.sticker: provide exactly one of 'index', 'emoji', or 'id'");
  }
  let selector = if !index.is_undefined() {
    let Some(index) = index.as_int().filter(|index| *index >= 0 && *index <= u16::MAX as i32) else {
      return PluginErrorCode::InvalidArgument.throw(ctx, "icons.sticker: 'index' must be an integer from 0 to 65535");
    };
    format!("i{index}")
  } else if !emoji.is_undefined() {
    let emoji = as_str(ctx, "icons.sticker", &emoji)?;
    if emoji.is_empty() || emoji.len() > STICKER_EMOJI_LIMIT {
      return PluginErrorCode::InvalidArgument.throw(ctx, "icons.sticker: 'emoji' must be 1 to 64 UTF-8 bytes");
    }
    format!("e{}", URL_SAFE_NO_PAD.encode(emoji))
  } else {
    let id = as_str(ctx, "icons.sticker", &id)?;
    if !is_positive_id(&id) {
      return PluginErrorCode::InvalidArgument.throw(ctx, "icons.sticker: 'id' must be a positive int64 string");
    }
    format!("d{id}")
  };
  let mode = read_animation_mode(ctx, Opt(Some(options.clone().into_value())), "icons.sticker", true)?;
  new_icon(ctx, format!("t{}{selector}\n{slug}", mode.wire()))
}

fn js_drawable_icon<'js>(ctx: &Ctx<'js>, jvm: &Rc<JvmState>, drawable: Value<'js>) -> JsResult<Object<'js>> {
  let handle = jvm.handle_id(ctx, &drawable)?;
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
  globals: &crate::api::Globals<'js>,
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
      "animation",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, name: Value<'js>| js_animation(&ctx, &host, name))?,
    )?;
  }
  icons.set(
    "customEmoji",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, id: Value<'js>, options: Opt<Value<'js>>| {
      js_custom_emoji(&ctx, id, options)
    })?,
  )?;
  icons.set(
    "sticker",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, options: Value<'js>| js_sticker(&ctx, options))?,
  )?;
  {
    let host = host.clone();
    icons.set(
      "svg",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, source: Value<'js>| js_svg(&ctx, &host, source))?,
    )?;
  }
  globals.inu.set("icons", icons)?;

  let android: Object = match globals.inu.get::<_, Object>("android") {
    Ok(o) => o,
    Err(_) => {
      let o = Object::new(ctx.clone())?;
      globals.inu.set("android", o.clone())?;
      o
    }
  };
  {
    let host = host.clone();
    android.set(
      "resourceIcon",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, name: Value<'js>| js_resource_icon(&ctx, &host, name))?,
    )?;
  }
  {
    let host = host.clone();
    android.set(
      "rawAnimation",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, name: Value<'js>, options: Opt<Value<'js>>| {
        js_raw_animation(&ctx, &host, name, options, "android.rawAnimation", false)
      })?,
    )?;
  }
  android.set(
    "drawableIcon",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, drawable: Value<'js>| match jvm.as_ref() {
      Some(jvm) => js_drawable_icon(&ctx, jvm, drawable),
      None => {
        let ctx: &Ctx<'js> = &ctx;
        PluginErrorCode::NotGranted("unsafe.jvm").throw(ctx, "android.drawableIcon: needs @grant unsafe.jvm")
      }
    })?,
  )?;

  Ok(())
}

#[cfg(test)]
#[path = "icons_tests.rs"]
pub(crate) mod tests;

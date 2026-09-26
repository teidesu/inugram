use rquickjs::{Array, Ctx, Function, Object, Result as JsResult, Value};
use tgtext::{DateFormat, Entity, EntityKind, Sub, TextWithEntities};

use crate::api::error::PluginErrorCode;

const KIND_MARKDOWN: i32 = 0;
const KIND_HTML: i32 = 1;
const KIND_HTML_RAW: i32 = 2;

pub fn install_text<'js>(ctx: &Ctx<'js>) -> JsResult<Object<'js>> {
  let natives = Object::new(ctx.clone())?;

  natives.set(
    "parse",
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>, kind: i32, parts: Value<'js>, subs: Value<'js>| -> JsResult<Object<'js>> {
        let parts = read_parts(&ctx, &parts)?;
        let subs = read_subs(&ctx, &subs)?;
        let borrowed: Vec<&str> = parts.iter().map(String::as_str).collect();
        let parsed = match kind {
          KIND_MARKDOWN => tgtext::markdown::parse(&borrowed, &subs),
          KIND_HTML => tgtext::html::parse(false, &borrowed, &subs),
          KIND_HTML_RAW => tgtext::html::parse(true, &borrowed, &subs),
          _ => return PluginErrorCode::InvalidArgument.throw(&ctx, "parse: unknown format"),
        };
        text_to_js(&ctx, &parsed)
      },
    )?,
  )?;

  natives.set(
    "unparse",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, kind: i32, text: String, entities: Value<'js>| -> JsResult<String> {
      let entities = read_entities(&ctx, &entities)?;
      Ok(match kind {
        KIND_MARKDOWN => tgtext::markdown::unparse(&text, &entities),
        KIND_HTML => tgtext::html::unparse(false, &text, &entities),
        KIND_HTML_RAW => tgtext::html::unparse(true, &text, &entities),
        _ => return PluginErrorCode::InvalidArgument.throw(&ctx, "unparse: unknown format"),
      })
    })?,
  )?;

  natives.set(
    "escape",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, kind: i32, text: String, quote: bool| -> JsResult<String> {
      Ok(match kind {
        KIND_MARKDOWN => tgtext::markdown::escape(&text),
        KIND_HTML | KIND_HTML_RAW => tgtext::html::escape(&text, quote),
        _ => return PluginErrorCode::InvalidArgument.throw(&ctx, "escape: unknown format"),
      })
    })?,
  )?;

  Ok(natives)
}

fn read_parts<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<Vec<String>> {
  let Some(array) = value.as_array() else {
    return PluginErrorCode::InvalidArgument.throw(ctx, "parse: expected template parts");
  };
  let mut parts = Vec::with_capacity(array.len());
  for item in array.iter::<Value>() {
    let item = item?;
    let Some(text) = item.as_string() else {
      return PluginErrorCode::InvalidArgument.throw(ctx, "parse: template parts must be strings");
    };
    parts.push(text.to_string()?);
  }
  Ok(parts)
}

fn read_subs<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<Vec<Sub>> {
  let Some(array) = value.as_array() else {
    return PluginErrorCode::InvalidArgument.throw(ctx, "parse: expected interpolated values");
  };
  let mut subs = Vec::with_capacity(array.len());
  for item in array.iter::<Value>() {
    let item = item?;
    if item.is_null() || item.is_undefined() {
      subs.push(Sub::Skip);
      continue;
    }
    if let Some(text) = item.as_string() {
      subs.push(Sub::Text(text.to_string()?));
      continue;
    }
    let Some(object) = item.as_object() else {
      return PluginErrorCode::InvalidArgument.throw(ctx, "parse: expected a string or { text, entities }");
    };
    let text: Value = object.get("text")?;
    let Some(text) = text.as_string() else {
      return PluginErrorCode::InvalidArgument.throw(ctx, "parse: expected a string or { text, entities }");
    };
    let entities = read_entities(ctx, &object.get("entities")?)?;
    subs.push(Sub::Rich(TextWithEntities { text: text.to_string()?, entities }));
  }
  Ok(subs)
}

fn read_entities<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<Vec<Entity>> {
  if value.is_undefined() || value.is_null() {
    return Ok(Vec::new());
  }
  let Some(array) = value.as_array() else {
    return PluginErrorCode::InvalidArgument.throw(ctx, "entities must be an array");
  };
  let mut entities = Vec::with_capacity(array.len());
  for item in array.iter::<Value>() {
    entities.push(read_entity(ctx, &item?)?);
  }
  Ok(entities)
}

fn read_entity<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<Entity> {
  let Some(object) = value.as_object() else {
    return PluginErrorCode::InvalidArgument.throw(ctx, "entities: expected message entity objects");
  };
  let name: Value = object.get("_")?;
  let Some(name) = name.as_string() else {
    return PluginErrorCode::InvalidArgument.throw(ctx, "entities: an entity needs its constructor name");
  };
  let name = name.to_string()?;
  let offset = get_number_field(ctx, object, "offset")?;
  let length = get_number_field(ctx, object, "length")?;

  let kind = match name.as_str() {
    "messageEntityBold" => EntityKind::Bold,
    "messageEntityItalic" => EntityKind::Italic,
    "messageEntityUnderline" => EntityKind::Underline,
    "messageEntityStrike" => EntityKind::Strike,
    "messageEntitySpoiler" => EntityKind::Spoiler,
    "messageEntityCode" => EntityKind::Code,
    "messageEntityPre" => EntityKind::Pre {
      language: get_string_field(object, "language")?.unwrap_or_default(),
    },
    "messageEntityBlockquote" => EntityKind::Blockquote {
      collapsed: get_bool_field(object, "collapsed")?,
    },
    "messageEntityTextUrl" => EntityKind::TextUrl {
      url: get_string_field(object, "url")?.unwrap_or_default(),
    },
    "messageEntityMentionName" => EntityKind::MentionName {
      user_id: get_int64_field(object, "user_id")?.unwrap_or_default(),
    },
    "inputMessageEntityMentionName" => {
      let user: Value = object.get("user_id")?;
      let user = user.as_object();
      let user_id = user.as_ref().and_then(|user| get_int64_field(user, "user_id").ok().flatten()).unwrap_or_default();
      let access_hash = user
        .as_ref()
        .and_then(|user| get_int64_field(user, "access_hash").ok().flatten())
        .unwrap_or_default();
      EntityKind::InputMentionName { user_id, access_hash }
    }
    "messageEntityCustomEmoji" => EntityKind::CustomEmoji {
      document_id: id_string_field(object, "document_id")?.unwrap_or_else(|| "0".into()),
    },
    "messageEntityFormattedDate" => EntityKind::FormattedDate {
      date: get_int64_field(object, "date")?.unwrap_or_default(),
      format: DateFormat {
        relative: get_bool_field(object, "relative")?,
        day_of_week: get_bool_field(object, "day_of_week")?,
        short_date: get_bool_field(object, "short_date")?,
        long_date: get_bool_field(object, "long_date")?,
        short_time: get_bool_field(object, "short_time")?,
        long_time: get_bool_field(object, "long_time")?,
      },
    },
    "messageEntityUrl" => EntityKind::Url,
    "messageEntityEmail" => EntityKind::Email,
    "messageEntityMention" => EntityKind::Mention,
    _ => EntityKind::Other(name),
  };

  Ok(Entity::new(kind, offset, length))
}

fn text_to_js<'js>(ctx: &Ctx<'js>, value: &TextWithEntities) -> JsResult<Object<'js>> {
  let out = Object::new(ctx.clone())?;
  out.set("text", value.text.as_str())?;
  let entities = Array::new(ctx.clone())?;
  for (index, entity) in value.entities.iter().enumerate() {
    entities.set(index, entity_to_js(ctx, entity)?)?;
  }
  out.set("entities", entities)?;
  Ok(out)
}

fn entity_to_js<'js>(ctx: &Ctx<'js>, entity: &Entity) -> JsResult<Object<'js>> {
  let out = Object::new(ctx.clone())?;
  out.set("_", entity.kind.name())?;
  // these cross as js numbers, not bigints: an offset is a count of code units and an id the app
  // itself writes as a number wherever it fits
  out.set("offset", entity.offset as f64)?;
  out.set("length", entity.length as f64)?;

  match &entity.kind {
    EntityKind::Pre { language } => out.set("language", language.as_str())?,
    EntityKind::Blockquote { collapsed } => out.set("collapsed", *collapsed)?,
    EntityKind::TextUrl { url } => out.set("url", url.as_str())?,
    EntityKind::MentionName { user_id } => out.set("user_id", *user_id as f64)?,
    EntityKind::InputMentionName { user_id, access_hash } => {
      let user = Object::new(ctx.clone())?;
      user.set("_", "inputUser")?;
      user.set("user_id", *user_id as f64)?;
      // an int64 the plugin never does arithmetic on, so it crosses as the app's decimal string
      user.set("access_hash", access_hash.to_string())?;
      out.set("user_id", user)?;
    }
    EntityKind::CustomEmoji { document_id } => out.set("document_id", document_id.as_str())?,
    EntityKind::FormattedDate { date, format } => {
      out.set("date", *date as f64)?;
      for (name, set) in [
        ("relative", format.relative),
        ("day_of_week", format.day_of_week),
        ("short_date", format.short_date),
        ("long_date", format.long_date),
        ("short_time", format.short_time),
        ("long_time", format.long_time),
      ] {
        if set {
          out.set(name, true)?;
        }
      }
    }
    _ => {}
  }

  Ok(out)
}

fn get_number_field<'js>(ctx: &Ctx<'js>, object: &Object<'js>, name: &str) -> JsResult<i64> {
  let value: Value = object.get(name)?;
  match value.as_number() {
    Some(value) if value.is_finite() && value.fract() == 0.0 => Ok(value as i64),
    _ => PluginErrorCode::InvalidArgument.throw(ctx, &format!("entities: {name} must be an integer")),
  }
}

fn get_string_field<'js>(object: &Object<'js>, name: &str) -> JsResult<Option<String>> {
  let value: Value = object.get(name)?;
  match value.as_string() {
    Some(value) => Ok(Some(value.to_string()?)),
    None => Ok(None),
  }
}

fn get_bool_field<'js>(object: &Object<'js>, name: &str) -> JsResult<bool> {
  let value: Value = object.get(name)?;
  Ok(value.as_bool().unwrap_or(false))
}

/// An int64 field, which the app writes as a number where it fits and as a decimal string otherwise.
fn get_int64_field<'js>(object: &Object<'js>, name: &str) -> JsResult<Option<i64>> {
  let value: Value = object.get(name)?;
  if let Some(value) = value.as_number() {
    if value.is_finite() && value.fract() == 0.0 {
      return Ok(Some(value as i64));
    }
  }
  if let Some(value) = value.as_string() {
    return Ok(value.to_string()?.parse::<i64>().ok());
  }
  Ok(None)
}

/// Same, kept as text: a document id is only ever passed along, and 2^53 is not its ceiling.
fn id_string_field<'js>(object: &Object<'js>, name: &str) -> JsResult<Option<String>> {
  let value: Value = object.get(name)?;
  if let Some(value) = value.as_string() {
    let value = value.to_string()?;
    return Ok(if value.is_empty() { None } else { Some(value) });
  }
  if let Some(value) = value.as_number() {
    if value.is_finite() && value.fract() == 0.0 {
      return Ok(Some((value as i64).to_string()));
    }
  }
  Ok(None)
}

use crate::runtime::Dispose;
use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use rquickjs::function::Constructor;
use rquickjs::{Array, Ctx, Exception, Function, IntoJs, Object, Persistent, Result as JsResult, Value};
use tgtext::TextWithEntities;

use crate::api::error::{self, describe_js_error, error_value_to_string, format_thrown};
use crate::api::tl::proxy::json_parse_tl;
use crate::api::tl::text::{read_entities, text_to_js};
use crate::runtime::{enter_js, pump_jobs};
use crate::sandbox::registry::{make_disposer, noop_disposer, Lifecycle, Registry};
use crate::utils::arguments::{array_values, opt_str, req_fn, req_str, stringify_json};

const WHAT: &str = "registerTranslationProvider";

pub trait TranslationHost {
  fn translation_register(&self, token: u32, id: &str, name: &str) -> Option<String>;
  fn translation_unregister(&self, token: u32);
  /// `S` and a json array of `{ text, entities }`, one per text asked for, or `E` and a reason
  fn translation_result(&self, dispatch_id: i64, wire: &str);
}

#[derive(Clone, Copy)]
enum Format {
  Entities,
  Html,
  Plain,
}

struct Provider {
  token: u32,
  format: Format,
  translate: Persistent<Function<'static>>,
}

pub struct TranslationState {
  host: Rc<dyn TranslationHost>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  providers: Registry<Rc<Provider>>,
  abort_controller: RefCell<Option<Persistent<Constructor<'static>>>>,
  /// a running translation's abort controller, absent once settled or abandoned
  dispatches: RefCell<HashMap<i64, Persistent<Object<'static>>>>,
}

pub fn install_translation<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn TranslationHost>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<TranslationState>> {
  let abort_controller = crate::api::globals::save_abort_controller(ctx)?
    .ok_or_else(|| Exception::throw_message(ctx, "translate: AbortController is not installed"))?;
  let state = Rc::new(TranslationState {
    host,
    lifecycle,
    log,
    providers: Registry::default(),
    abort_controller: RefCell::new(Some(abort_controller)),
    dispatches: RefCell::new(HashMap::new()),
  });
  let state2 = state.clone();
  globals.inu.set(
    WHAT,
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, opts: Object<'js>| state2.js_register(&ctx, opts))?,
  )?;
  Ok(state)
}

impl TranslationState {
  fn js_register<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, opts: Object<'js>) -> JsResult<Function<'js>> {
    let id = req_str(ctx, &opts, WHAT, "id")?;
    if id.is_empty() {
      return Err(Exception::throw_type(ctx, &format!("{WHAT}: 'id' must not be empty")));
    }
    let name = req_str(ctx, &opts, WHAT, "name")?;
    if name.trim().is_empty() {
      return Err(Exception::throw_type(ctx, &format!("{WHAT}: 'name' must not be empty")));
    }
    let format = match opt_str(ctx, &opts, WHAT, "format")?.as_deref() {
      None | Some("plain") => Format::Plain,
      Some("html") => Format::Html,
      Some("entities") => Format::Entities,
      Some(other) => {
        return Err(Exception::throw_type(
          ctx,
          &format!("{WHAT}: unknown format '{other}', expected 'plain', 'html' or 'entities'"),
        ))
      }
    };
    let translate = req_fn(ctx, &opts, WHAT, "translate")?;

    if self.lifecycle.is_unloading() {
      return noop_disposer(ctx);
    }

    let token = self.providers.alloc();
    if let Some(err) = self.host.translation_register(token, &id, &name) {
      return Err(ctx.throw(error::host_error_to_js(ctx, &err)?));
    }
    let provider = Rc::new(Provider {
      token,
      format,
      translate: Persistent::save(ctx, translate),
    });
    if let Some(previous) = self.providers.insert(token, Some(id), provider) {
      self.host.translation_unregister(previous.token);
    }

    let state = self.clone();
    make_disposer(ctx, move |_ctx| {
      if state.providers.remove(token).is_some() {
        state.host.translation_unregister(token);
      }
    })
  }

  /// reports [wire] unless the dispatch already settled or was abandoned
  fn settle(&self, ctx: &Ctx<'_>, dispatch_id: i64, wire: &str) {
    let Some(controller) = self.dispatches.borrow_mut().remove(&dispatch_id) else {
      return;
    };
    let _ = controller.restore(ctx);
    self.host.translation_result(dispatch_id, wire);
  }

  fn try_dispatch<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    token: u32,
    dispatch_id: i64,
    request_json: &str,
  ) -> JsResult<()> {
    let Some(provider) = self.providers.get(token) else {
      self.host.translation_result(dispatch_id, "Ethe translation provider was disposed");
      return Ok(());
    };

    let request = json_parse_tl(ctx, request_json)?
      .into_object()
      .ok_or_else(|| Exception::throw_type(ctx, "translate: malformed request"))?;
    let texts: Array = request.get("texts")?;
    let count = texts.len();
    let input = Array::new(ctx.clone())?;
    for (index, item) in texts.iter::<Object>().enumerate() {
      let item = item?;
      let value = match provider.format {
        Format::Entities => item.into_value(),
        Format::Html => {
          let text: String = item.get("text")?;
          let entities = read_entities(ctx, &item.get("entities")?)?;
          tgtext::html::unparse(true, &text, &entities).into_js(ctx)?
        }
        Format::Plain => item.get("text")?,
      };
      input.set(index, value)?;
    }

    let context = Object::new(ctx.clone())?;
    context.set("texts", input)?;
    context.set("from", request.get::<_, Array>("from")?)?;
    context.set("to", request.get::<_, String>("to")?)?;
    if let Some(tone) = request.get::<_, Option<String>>("tone")? {
      context.set("tone", tone)?;
    }
    let ctor = self.abort_controller.borrow().clone();
    let Some(ctor) = ctor else {
      return Err(Exception::throw_message(ctx, "translate: the engine is disposed"));
    };
    let controller: Object = ctor.restore(ctx)?.construct(())?;
    context.set("signal", controller.get::<_, Value>("signal")?)?;
    self.dispatches.borrow_mut().insert(dispatch_id, Persistent::save(ctx, controller));

    let translate = provider.translate.clone().restore(ctx)?;
    let result = match translate.call::<_, Value>((context,)) {
      Ok(value) => value,
      Err(rquickjs::Error::Exception) => {
        let caught = ctx.catch();
        (self.log)(&format!("translate threw: {}", format_thrown(ctx, &caught)));
        self.settle(ctx, dispatch_id, &format!("E{}", error_value_to_string(ctx, &caught)));
        return Ok(());
      }
      Err(e) => return Err(e),
    };

    let format = provider.format;
    let ok_fn = {
      let state = self.clone();
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| {
        if !state.dispatches.borrow().contains_key(&dispatch_id) {
          return;
        }
        let wire = match read_result(&ctx, format, count, value) {
          Ok(json) => format!("S{json}"),
          Err(e) => {
            let reason = describe_js_error(&ctx, e);
            (state.log)(&format!("translate returned an unusable result: {reason}"));
            format!("E{reason}")
          }
        };
        state.settle(&ctx, dispatch_id, &wire);
      })?
    };
    let err_fn = {
      let state = self.clone();
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| {
        if !state.dispatches.borrow().contains_key(&dispatch_id) {
          return;
        }
        (state.log)(&format!("translate rejected: {}", format_thrown(&ctx, &value)));
        state.settle(&ctx, dispatch_id, &format!("E{}", error_value_to_string(&ctx, &value)));
      })?
    };
    crate::utils::qjs::resolve_and_then(ctx, result, ok_fn, err_fn)
  }

  /// answered exactly once through [`TranslationHost::translation_result`], unless abandoned first
  pub fn dispatch(self: &Rc<Self>, context: &rquickjs::Context, token: u32, dispatch_id: i64, request_json: &str) {
    enter_js(context, |ctx| {
      if let Err(e) = self.try_dispatch(&ctx, token, dispatch_id, request_json) {
        let reason = describe_js_error(&ctx, e);
        (self.log)(&format!("translate dispatch failed: {reason}"));
        let wire = format!("E{reason}");
        if self.dispatches.borrow().contains_key(&dispatch_id) {
          self.settle(&ctx, dispatch_id, &wire);
        } else {
          self.host.translation_result(dispatch_id, &wire);
        }
      }
    });
    pump_jobs(context, self.log.as_ref());
  }

  /// the host stopped waiting: aborts the signal, and a late result is dropped
  pub fn abandon(self: &Rc<Self>, context: &rquickjs::Context, dispatch_id: i64, timed_out: bool) {
    enter_js(context, |ctx| {
      let Some(controller) = self.dispatches.borrow_mut().remove(&dispatch_id) else {
        return;
      };
      let (code, message) = if timed_out {
        (error::PluginErrorCode::TimedOut, "the translation ran past its budget")
      } else {
        (error::PluginErrorCode::Aborted, "the app no longer needs this translation")
      };
      let aborted = controller.restore(&ctx).and_then(|controller| {
        let abort: Function = controller.get("abort")?;
        let reason = error::make_plugin_error(&ctx, code.name(), message, None, None, None)?;
        abort.call::<_, ()>((rquickjs::function::This(controller), reason))
      });
      if let Err(e) = aborted {
        (self.log)(&format!("translate: failed to abort the signal: {}", describe_js_error(&ctx, e)));
      }
    });
    pump_jobs(context, self.log.as_ref());
  }
}

fn read_result<'js>(ctx: &Ctx<'js>, format: Format, count: usize, value: Value<'js>) -> JsResult<String> {
  let array = value
    .as_array()
    .ok_or_else(|| Exception::throw_type(ctx, "translate must return an array, one translation per text"))?;
  let items = array_values(ctx, array, "translate")?;
  if items.len() != count {
    return Err(Exception::throw_type(
      ctx,
      &format!("translate returned {} translations for {count} texts", items.len()),
    ));
  }
  let out = Array::new(ctx.clone())?;
  for (index, item) in items.into_iter().enumerate() {
    let translated = match (format, item.as_string()) {
      (Format::Html, Some(html)) => tgtext::html::parse(true, &[html.to_string()?.as_str()], &[]),
      (_, Some(text)) => TextWithEntities {
        text: text.to_string()?,
        entities: Vec::new(),
      },
      (Format::Entities, None) => read_text_with_entities(ctx, index, &item)?,
      (_, None) => return Err(Exception::throw_type(ctx, &format!("translate: [{index}] must be a string"))),
    };
    out.set(index, text_to_js(ctx, &translated)?)?;
  }
  stringify_json(ctx, out.into_value(), "translate: the result did not serialize")
}

fn read_text_with_entities<'js>(ctx: &Ctx<'js>, index: usize, item: &Value<'js>) -> JsResult<TextWithEntities> {
  let invalid =
    || Exception::throw_type(ctx, &format!("translate: [{index}] must be a string or {{ text, entities }}"));
  let object = item.as_object().ok_or_else(invalid)?;
  let text = object.get::<_, Value>("text")?.as_string().ok_or_else(invalid)?.to_string()?;
  let entities = read_entities(ctx, &object.get("entities")?)?;
  let length = text.encode_utf16().count() as i64;
  if let Some(entity) = entities.iter().find(|e| e.offset < 0 || e.length <= 0 || e.offset + e.length > length) {
    return Err(Exception::throw_type(
      ctx,
      &format!(
        "translate: [{index}] has a {} entity at {}+{} outside its {length} UTF-16 units",
        entity.kind.name(),
        entity.offset,
        entity.length
      ),
    ));
  }
  Ok(TextWithEntities { text, entities })
}

impl Dispose for TranslationState {
  fn dispose(&self, context: &rquickjs::Context) {
    enter_js(context, |_| {
      drop(self.providers.remove_matching(|_| true));
      drop(self.dispatches.borrow_mut().drain().collect::<Vec<_>>());
      drop(self.abort_controller.take());
    });
  }
}

#[cfg(test)]
#[path = "translation_tests.rs"]
mod tests;

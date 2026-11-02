use crate::runtime::Dispose;
use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::{Arc, RwLock};

use percent_encoding::{utf8_percent_encode, AsciiSet, NON_ALPHANUMERIC};
use regex::{Captures, Regex};
use rquickjs::function::{Constructor, This};
use rquickjs::{Array, Ctx, Exception, Function, Object, Persistent, Result as JsResult, Value};
use serde_json::{json, Map, Value as Json};

use super::filters::{read_matcher, read_regex, Matcher, Probe, NEEDS_ANY};
use crate::api::error::{self, call_callback, describe_js_error, format_thrown};
use crate::api::telegram::account::{dispatch_account, AccountState};
use crate::api::tl::proxy::json_parse_tl;
use crate::api::ui::pages::{MenuAnchor, UiState};
use crate::runtime::{enter_js, pump_jobs};
use crate::sandbox::registry::{make_disposer, noop_disposer, CallbackRegistry, Lifecycle};
use crate::utils::arguments::{opt_fn, opt_str};

const WHAT: &str = "registerTextEntity";
const OPTION_KEYS: &[&str] = &["pattern", "filter", "url", "callback", "secondaryCallback"];
/// per plugin and text, so a pattern matching every word cannot flood the layout with spans
pub(crate) const MAX_MATCHES: usize = 100;

/// what `encodeURIComponent` leaves as is
const URI_COMPONENT: &AsciiSet = &NON_ALPHANUMERIC
  .remove(b'-')
  .remove(b'_')
  .remove(b'.')
  .remove(b'!')
  .remove(b'~')
  .remove(b'*')
  .remove(b'\'')
  .remove(b'(')
  .remove(b')');

pub trait TextEntityHost {
  /// `needs` is the `filters::NEEDS_*` bits of the plugin's entities, 0 once it has none
  fn text_entities_changed(&self, needs: i32);
  /// the tap handed to [`TextEntityState::dispatch`] is over, unless it was abandoned first
  fn text_entity_settled(&self, tap_id: i64);
}

#[derive(Clone)]
struct Rule {
  token: u32,
  /// the long-tap callback's token
  secondary: Option<u32>,
  pattern: Regex,
  filter: Option<Matcher>,
  /// a `$1`-style template, `None` for a callback entity
  url: Option<String>,
}

/// A plugin's entities, read by the app from any thread without the engine lease
#[derive(Default)]
pub(crate) struct TextEntities {
  rules: RwLock<Arc<Vec<Rule>>>,
}

impl TextEntities {
  /// `{ token, start, end }` in registration order, offsets in utf-16 units, with the expanded
  /// `url` or the `match` and `groups` a callback gets. `probe` is for the filters, `text` is what
  /// the app shows. A filter is checked once its pattern matches, as most texts match no pattern
  pub(crate) fn matches(&self, text: &str, probe: &Probe<'_>, html: &mut Option<String>) -> Vec<Json> {
    let rules = self.rules.read().unwrap_or_else(|e| e.into_inner()).clone();
    let mut out = Vec::new();
    'rules: for rule in rules.iter() {
      let mut filter = rule.filter.as_ref();
      let mut cursor = (0, 0);
      for caps in rule.pattern.captures_iter(text) {
        if out.len() == MAX_MATCHES {
          break 'rules;
        }
        let Some(whole) = caps.get(0).filter(|whole| !whole.is_empty()) else {
          continue;
        };
        if filter.take().is_some_and(|filter| !filter.matches(probe, html)) {
          continue 'rules;
        }
        let start = count_utf16(text, &mut cursor, whole.start());
        let end = count_utf16(text, &mut cursor, whole.end());
        let mut entry = json!({ "token": rule.token, "start": start, "end": end });
        match &rule.url {
          Some(template) => {
            entry["url"] = expand_url(template, &caps, rule.pattern.capture_names().flatten().next().is_some()).into()
          }
          None => {
            entry["match"] = json!(caps.iter().map(|group| group.map(|group| group.as_str())).collect::<Vec<_>>());
            entry["groups"] = rule
              .pattern
              .capture_names()
              .flatten()
              .filter_map(|name| Some((name.to_string(), caps.name(name)?.as_str().into())))
              .collect::<Map<_, _>>()
              .into();
          }
        }
        out.push(entry);
      }
    }
    out
  }

  /// answers the `NEEDS_*` bits left, and whether any rule changed
  fn update(&self, change: impl FnOnce(&mut Vec<Rule>)) -> (i32, bool) {
    let mut current = self.rules.write().unwrap_or_else(|e| e.into_inner());
    let mut rules = current.as_ref().clone();
    let before = rules.len();
    change(&mut rules);
    let changed = rules.len() != before;
    let needs = rules
      .iter()
      .fold(0, |needs, rule| needs | NEEDS_ANY | rule.filter.as_ref().map_or(0, Matcher::needs));
    *current = Arc::new(rules);
    (needs, changed)
  }
}

/// `byte` in utf-16 units, counting on from `cursor`, which only moves forward
fn count_utf16(text: &str, cursor: &mut (usize, usize), byte: usize) -> usize {
  cursor.1 += text[cursor.0..byte].encode_utf16().count();
  cursor.0 = byte;
  cursor.1
}

/// `String.prototype.replace` substitutions (`$$`, `$&`, `$1`..`$99`, `$<name>`), each group
/// encoded like `encodeURIComponent`. A group that did not take part is empty, and `$<name>` stays
/// as is without `has_names`
fn expand_url(template: &str, caps: &Captures<'_>, has_names: bool) -> String {
  let mut out = String::with_capacity(template.len());
  let mut rest = template;
  while let Some(at) = rest.find('$') {
    out.push_str(&rest[..at]);
    rest = &rest[at + 1..];
    let digits = rest.bytes().take(2).take_while(u8::is_ascii_digit).count();
    let index = (1..=digits)
      .rev()
      .find_map(|len| Some((rest[..len].parse().ok().filter(|&n| n >= 1 && n < caps.len())?, len)));
    let (group, len) = if rest.starts_with('$') {
      out.push('$');
      (None, 1)
    } else if rest.starts_with('&') {
      (caps.get(0), 1)
    } else if let Some(close) = rest.strip_prefix('<').filter(|_| has_names).and_then(|name| name.find('>')) {
      (caps.name(&rest[1..close + 1]), close + 2)
    } else if let Some((index, len)) = index {
      (caps.get(index), len)
    } else {
      out.push('$');
      continue;
    };
    if let Some(group) = group {
      out.extend(utf8_percent_encode(group.as_str(), URI_COMPONENT));
    }
    rest = &rest[len..];
  }
  out.push_str(rest);
  out
}

pub struct TextEntityState {
  host: Rc<dyn TextEntityHost>,
  lifecycle: Rc<Lifecycle>,
  accounts: Option<Rc<AccountState>>,
  ui: Option<Rc<UiState>>,
  log: crate::Log,
  pub(crate) entities: Arc<TextEntities>,
  callbacks: CallbackRegistry,
  abort_controller: RefCell<Option<Persistent<Constructor<'static>>>>,
  /// a running tap's abort controller, absent once settled or abandoned
  taps: RefCell<HashMap<i64, Persistent<Object<'static>>>>,
}

pub fn install_text_entities<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn TextEntityHost>,
  lifecycle: Rc<Lifecycle>,
  accounts: Option<Rc<AccountState>>,
  ui: Option<Rc<UiState>>,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<TextEntityState>> {
  let abort_controller = crate::api::globals::save_abort_controller(ctx)?
    .ok_or_else(|| Exception::throw_message(ctx, &format!("{WHAT}: AbortController is not installed")))?;
  let state = Rc::new(TextEntityState {
    host,
    lifecycle,
    accounts,
    ui,
    log,
    entities: Arc::default(),
    callbacks: CallbackRegistry::default(),
    abort_controller: RefCell::new(Some(abort_controller)),
    taps: RefCell::default(),
  });
  let registering = state.clone();
  globals.inu.set(
    WHAT,
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, options: Value<'js>| registering.js_register(&ctx, options))?,
  )?;
  Ok(state)
}

impl TextEntityState {
  fn js_register<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, options: Value<'js>) -> JsResult<Function<'js>> {
    if self.lifecycle.is_unloading() {
      return noop_disposer(ctx);
    }
    let Some(options) = options.as_object() else {
      return Err(Exception::throw_type(ctx, &format!("{WHAT}: the options must be an object")));
    };
    // a misspelled key would otherwise drop a filter or a callback without a word
    for key in options.keys::<String>() {
      let key = key?;
      if !OPTION_KEYS.contains(&key.as_str()) {
        return Err(Exception::throw_type(ctx, &format!("{WHAT}: unknown option '{key}'")));
      }
    }
    let pattern = read_regex(ctx, WHAT, &options.get("pattern")?, "pattern", "a RegExp")?
      .ok_or_else(|| Exception::throw_type(ctx, &format!("{WHAT}: pattern must be a RegExp")))?;
    let filter: Value = options.get("filter")?;
    let filter = match filter.is_undefined() {
      true => None,
      false => Some(read_matcher(ctx, WHAT, &filter)?),
    };
    let url = opt_str(ctx, options, WHAT, "url")?;
    let callback = opt_fn(ctx, options, WHAT, "callback")?;
    let secondary_callback = opt_fn(ctx, options, WHAT, "secondaryCallback")?;
    if url.is_some() == callback.is_some() {
      return Err(Exception::throw_type(ctx, &format!("{WHAT}: pass either url or callback")));
    }
    if url.is_some() && secondary_callback.is_some() {
      return Err(Exception::throw_type(ctx, &format!("{WHAT}: secondaryCallback needs a callback, not a url")));
    }

    let token = self.callbacks.alloc();
    if let Some(callback) = callback {
      self.callbacks.register(ctx, token, callback);
    }
    let secondary = secondary_callback.map(|callback| {
      let token = self.callbacks.alloc();
      self.callbacks.register(ctx, token, callback);
      token
    });
    let (needs, _) = self.entities.update(|rules| {
      rules.push(Rule {
        token,
        secondary,
        pattern,
        filter,
        url,
      })
    });
    self.host.text_entities_changed(needs);
    let state = self.clone();
    make_disposer(ctx, move |ctx| {
      state.callbacks.dispose(ctx, token);
      if let Some(secondary) = secondary {
        state.callbacks.dispose(ctx, secondary);
      }
      let (needs, changed) = state.entities.update(|rules| rules.retain(|rule| rule.token != token));
      if changed {
        state.host.text_entities_changed(needs);
      }
    })
  }

  /// Runs the callback of the entity [TextEntities::matches] named. `tap_json` is
  /// `{ accountId, dialogId, message, match, groups, anchored }`, `anchored` when the tapped link can
  /// hold a menu; a long tap without a `secondaryCallback`
  /// runs the `callback`. Answered once through [`TextEntityHost::text_entity_settled`] when the
  /// callback and its promise are done, unless abandoned first
  pub(crate) fn dispatch(
    self: &Rc<Self>,
    context: &rquickjs::Context,
    token: u32,
    secondary: bool,
    tap_id: i64,
    tap_json: &str,
  ) {
    enter_js(context, |ctx| match self.try_dispatch(&ctx, token, secondary, tap_id, tap_json) {
      Ok(true) => {}
      Ok(false) => self.settle(tap_id),
      Err(e) => {
        (self.log)(&format!("{WHAT}: the tap did not reach the plugin: {}", describe_js_error(&ctx, e)));
        self.settle(tap_id);
      }
    });
    pump_jobs(context, self.log.as_ref());
  }

  /// whether the callback is running, so [`Self::settle`] is up to its promise
  fn try_dispatch<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    token: u32,
    secondary: bool,
    tap_id: i64,
    tap_json: &str,
  ) -> JsResult<bool> {
    if self.lifecycle.is_unloading() {
      return Ok(false);
    }
    let rules = self.entities.rules.read().unwrap_or_else(|e| e.into_inner()).clone();
    let Some(rule) = rules.iter().find(|rule| rule.token == token) else {
      return Ok(false);
    };
    let (callback_token, name) = match rule.secondary.filter(|_| secondary) {
      Some(secondary) => (secondary, "secondaryCallback"),
      None => (token, "callback"),
    };
    let Some(callback) = self.callbacks.restore(ctx, callback_token) else {
      return Ok(false);
    };
    let tap = self.build_tap(ctx, tap_id, tap_json)?;
    let ctor = self.abort_controller.borrow().clone();
    let Some(ctor) = ctor else {
      return Ok(false);
    };
    let controller: Object = ctor.restore(ctx)?.construct(())?;
    tap.set("signal", controller.get::<_, Value>("signal")?)?;
    self.taps.borrow_mut().insert(tap_id, Persistent::save(ctx, controller));

    let what = format!("{WHAT} {name}");
    let Some(result) = call_callback(ctx, &self.log, &what, &callback, (tap,)) else {
      self.taps.borrow_mut().remove(&tap_id);
      return Ok(false);
    };
    let ok_fn = {
      let state = self.clone();
      Function::new(ctx.clone(), move || state.settle_running(tap_id))?
    };
    let err_fn = {
      let state = self.clone();
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| {
        if state.taps.borrow().contains_key(&tap_id) {
          (state.log)(&format!("{what} rejected: {}", format_thrown(&ctx, &value)));
          state.settle_running(tap_id);
        }
      })?
    };
    crate::utils::qjs::resolve_and_then(ctx, result, ok_fn, err_fn)?;
    Ok(true)
  }

  fn settle(&self, tap_id: i64) {
    self.taps.borrow_mut().remove(&tap_id);
    self.host.text_entity_settled(tap_id);
  }

  /// [`Self::settle`], unless the tap was abandoned
  fn settle_running(&self, tap_id: i64) {
    if self.taps.borrow_mut().remove(&tap_id).is_some() {
      self.host.text_entity_settled(tap_id);
    }
  }

  /// the host stopped waiting: aborts the tap's signal, and its settling goes unreported
  pub(crate) fn abandon(&self, context: &rquickjs::Context, tap_id: i64, timed_out: bool) {
    enter_js(context, |ctx| {
      let Some(controller) = self.taps.borrow_mut().remove(&tap_id) else {
        return;
      };
      let (code, message) = if timed_out {
        (error::PluginErrorCode::TimedOut, "the tap ran past its budget")
      } else {
        (error::PluginErrorCode::Aborted, "the user tapped another link or left the chat")
      };
      let aborted = controller.restore(&ctx).and_then(|controller| {
        let abort: Function = controller.get("abort")?;
        let reason = error::make_plugin_error(&ctx, code.name(), message, None, None, None)?;
        abort.call::<_, ()>((This(controller), reason))
      });
      if let Err(e) = aborted {
        (self.log)(&format!("{WHAT}: failed to abort the signal: {}", describe_js_error(&ctx, e)));
      }
    });
    pump_jobs(context, self.log.as_ref());
  }

  fn build_tap<'js>(&self, ctx: &Ctx<'js>, tap_id: i64, tap_json: &str) -> JsResult<Object<'js>> {
    let tap = json_parse_tl(ctx, tap_json)?
      .into_object()
      .ok_or_else(|| Exception::throw_type(ctx, "malformed tap"))?;
    let anchored = tap.get::<_, Option<bool>>("anchored")?.unwrap_or_default();
    tap.remove("anchored")?;
    if let Some(ui) = self.ui.as_ref().filter(|_| anchored) {
      tap.set("anchor", ui.make_anchor(ctx, MenuAnchor::TextEntityTap(tap_id))?)?;
    }
    let account_id: i32 = tap.get("accountId")?;
    tap.remove("accountId")?;
    tap.set("account", dispatch_account(ctx, &self.accounts, account_id)?)?;
    let message: Value = tap.get("message")?;
    tap.set("message", crate::api::Globals::get(ctx)?.get_message(ctx)?.construct::<_, Value>((message,))?)?;
    let groups: Array = tap.get("match")?;
    for (index, group) in groups.clone().iter::<Value>().enumerate() {
      if group?.is_null() {
        groups.set(index, Value::new_undefined(ctx.clone()))?;
      }
    }
    Ok(tap)
  }
}

impl Dispose for TextEntityState {
  fn dispose(&self, context: &rquickjs::Context) {
    self.entities.update(Vec::clear);
    enter_js(context, |_| {
      drop(self.callbacks.remove_matching(|_| true));
      drop(self.taps.borrow_mut().drain().collect::<Vec<_>>());
      drop(self.abort_controller.take());
    });
  }
}

#[cfg(test)]
#[path = "text_entities_tests.rs"]
mod tests;

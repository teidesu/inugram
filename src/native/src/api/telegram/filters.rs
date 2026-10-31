use crate::runtime::Dispose;
use std::rc::Rc;
use std::sync::{Arc, RwLock};

use regex::{Regex, RegexBuilder};
use rquickjs::{Ctx, Exception, Function, Object, Result as JsResult, Value};
use tgtext::TextWithEntities;

use super::rpc::sends::{read_name, read_one_or_many, MAX_SAFE_INTEGER, MEDIA_KINDS, PEER_TYPES};
use crate::api::error::{describe_js_error, report_callback_error};
use crate::api::telegram::account::{dispatch_account, AccountState};
use crate::api::tl::proxy::json_parse_tl;
use crate::api::tl::text::{read_entities, read_entities_json, text_to_js};
use crate::runtime::{enter_js, pump_jobs};
use crate::sandbox::grants::{GrantHost, MATCH_EXACT};
use crate::sandbox::registry::{make_disposer, noop_disposer, CallbackRegistry, Lifecycle};
use crate::utils::arguments::stringify_json;
use crate::utils::qjs::qjs_is_regexp;

const WHAT: &str = "filterMessages";
/// the app holds incoming messages back while it waits for verdicts, so each one must come quickly
pub(crate) const FILTER_DEADLINE_MS: u64 = 50;
const FILTER_KEYS: &[&str] = &["text", "html", "buttons", "sender", "peer", "media", "forward"];

/// bits of [FilterHost::message_filters_changed]: what the app has to read off a message for the
/// plugin's rules to see it
pub const NEEDS_ANY: i32 = 1;
pub const NEEDS_ENTITIES: i32 = 2;
pub const NEEDS_BUTTONS: i32 = 4;

/// first item of [MessageFilters::matches]
pub const MATCHED_NONE: i32 = 0;
pub const MATCHED_SPOILER: i32 = 1;
pub const MATCHED_HIDE: i32 = 2;

pub trait FilterHost {
  /// `needs` is the `NEEDS_*` bits of the plugin's rules, 0 once it has none
  fn message_filters_changed(&self, needs: i32);
}

#[derive(Clone)]
enum Action {
  Spoiler,
  Hide,
  Callback(u32),
}

#[derive(Clone)]
enum Presence<T> {
  Present(bool),
  Matching(T),
}

#[derive(Clone)]
struct Matcher {
  text: Option<Presence<Regex>>,
  html: Option<Regex>,
  buttons: Option<Regex>,
  senders: Vec<i64>,
  peers: Vec<i64>,
  /// the `PEER_*` bits of [Probe::peer_type]
  peer_types: i32,
  media: Option<Presence<Vec<String>>>,
  forward: Option<bool>,
}

#[derive(Clone)]
struct Rule {
  /// the registration the rule came from, which one disposer removes
  group: u32,
  matcher: Matcher,
  action: Action,
}

/// What a rule sees of a message, all of it the app's own: no rule matches a message by what it
/// does not carry, so an empty `text` matches no pattern
pub(crate) struct Probe<'a> {
  pub text: &'a str,
  /// the message's entities as `TlJson` writes them, `""` unless [NEEDS_ENTITIES]
  pub entities_json: &'a str,
  /// url button targets, one per line, `""` unless [NEEDS_BUTTONS]
  pub buttons: &'a str,
  /// marked peer ids, 0 when the app does not know them
  pub sender: i64,
  pub peer: i64,
  /// 1 for a user, 2 for a group, 4 for a broadcast channel
  pub peer_type: i32,
  /// `Message.mediaType`, `""` without media
  pub media: &'a str,
  pub forward: bool,
}

impl Matcher {
  /// cheap checks first: rendering html is the only allocation, and it happens once per probe
  fn matches(&self, probe: &Probe<'_>, html: &mut Option<String>) -> bool {
    (self.senders.is_empty() || self.senders.contains(&probe.sender))
      && (self.peers.is_empty() && self.peer_types == 0
        || self.peers.contains(&probe.peer)
        || self.peer_types & probe.peer_type != 0)
      && self.forward.is_none_or(|forward| forward == probe.forward)
      && match &self.media {
        None => true,
        Some(Presence::Present(present)) => *present == !probe.media.is_empty(),
        Some(Presence::Matching(kinds)) => kinds.iter().any(|kind| kind == probe.media),
      }
      && match &self.text {
        None => true,
        Some(Presence::Present(present)) => *present == !probe.text.is_empty(),
        Some(Presence::Matching(pattern)) => !probe.text.is_empty() && pattern.is_match(probe.text),
      }
      && self.buttons.as_ref().is_none_or(|pattern| probe.buttons.lines().any(|url| pattern.is_match(url)))
      && self.html.as_ref().is_none_or(|pattern| {
        !probe.text.is_empty()
          && pattern.is_match(
            html
              .get_or_insert_with(|| tgtext::html::unparse(true, probe.text, &read_entities_json(probe.entities_json))),
          )
      })
  }
}

/// A plugin's rules, read by the app from any thread without the engine lease
#[derive(Default)]
pub(crate) struct MessageFilters {
  rules: RwLock<Arc<Vec<Rule>>>,
}

impl MessageFilters {
  /// `[MATCHED_*, ...callback ids]`: the callbacks whose rules matched, in registration order. A
  /// static hide leaves none, since nothing they answer can show the message again
  pub(crate) fn matches(&self, probe: &Probe<'_>) -> Vec<i32> {
    let rules = self.rules.read().unwrap_or_else(|e| e.into_inner()).clone();
    let mut html = None;
    let mut matched = vec![MATCHED_NONE];
    // ponytail: one pass over every rule, a RegexSet per field if blocklists get into the thousands
    for rule in rules.iter() {
      let seen = match rule.action {
        Action::Spoiler => matched[0] == MATCHED_SPOILER,
        Action::Callback(id) => matched[1..].contains(&(id as i32)),
        Action::Hide => false,
      };
      if seen || !rule.matcher.matches(probe, &mut html) {
        continue;
      }
      match rule.action {
        Action::Hide => return vec![MATCHED_HIDE],
        Action::Spoiler => matched[0] = MATCHED_SPOILER,
        Action::Callback(id) => matched.push(id as i32),
      }
    }
    matched
  }

  /// answers the `NEEDS_*` bits left, and whether any rule changed
  fn update(&self, change: impl FnOnce(&mut Vec<Rule>)) -> (i32, bool) {
    let mut current = self.rules.write().unwrap_or_else(|e| e.into_inner());
    let mut rules = current.as_ref().clone();
    let before = rules.len();
    change(&mut rules);
    let changed = rules.len() != before;
    let needs = rules.iter().fold(0, |needs, rule| {
      needs
        | NEEDS_ANY
        | if rule.matcher.html.is_some() { NEEDS_ENTITIES } else { 0 }
        | if rule.matcher.buttons.is_some() { NEEDS_BUTTONS } else { 0 }
    });
    *current = Arc::new(rules);
    (needs, changed)
  }
}

enum Verdict {
  Show,
  Hide,
  Spoiler,
  /// the json of `{ text, entities }`
  Rewrite(String),
}

pub struct FilterState {
  host: Rc<dyn FilterHost>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  accounts: Option<Rc<AccountState>>,
  log: crate::Log,
  pub(crate) rules: Arc<MessageFilters>,
  callbacks: CallbackRegistry,
}

pub fn install_filters<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn FilterHost>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  accounts: Option<Rc<AccountState>>,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<FilterState>> {
  let state = Rc::new(FilterState {
    host,
    grants,
    lifecycle,
    accounts,
    log,
    rules: Arc::default(),
    callbacks: CallbackRegistry::default(),
  });
  let registering = state.clone();
  globals.inu.set(
    WHAT,
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, filter: Value<'js>, verdict: Value<'js>| {
      registering.js_register(&ctx, filter, verdict)
    })?,
  )?;
  Ok(state)
}

impl FilterState {
  fn js_register<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    filter: Value<'js>,
    verdict: Value<'js>,
  ) -> JsResult<Function<'js>> {
    if self.lifecycle.is_unloading() {
      return noop_disposer(ctx);
    }
    self.grants.check_grant(ctx, WHAT, None, MATCH_EXACT)?;
    let matchers = read_matchers(ctx, &filter)?;
    let callback = verdict.as_function().cloned();
    let fixed = match verdict.as_string().map(|verdict| verdict.to_string()).transpose()?.as_deref() {
      Some("hide") => Some(Action::Hide),
      Some("spoiler") => Some(Action::Spoiler),
      _ if callback.is_some() => None,
      _ => {
        return Err(Exception::throw_type(ctx, &format!("{WHAT}: the verdict must be 'hide', 'spoiler' or a function")))
      }
    };
    let token = self.callbacks.alloc();
    let action = fixed.unwrap_or(Action::Callback(token));
    if let Some(callback) = callback {
      self.callbacks.register(ctx, token, callback);
    }
    let (needs, _) = self.rules.update(|rules| {
      rules.extend(matchers.into_iter().map(|matcher| Rule {
        group: token,
        matcher,
        action: action.clone(),
      }))
    });
    self.host.message_filters_changed(needs);
    let state = self.clone();
    make_disposer(ctx, move |ctx| {
      state.callbacks.dispose(ctx, token);
      let (needs, changed) = state.rules.update(|rules| rules.retain(|rule| rule.group != token));
      if changed {
        state.host.message_filters_changed(needs);
      }
    })
  }

  /// Runs the callbacks [MessageFilters::matches] named over one message. Answers `H` for a hide,
  /// otherwise `S` (spoiler) or `V` (visible) and the json of the first rewrite
  pub(crate) fn dispatch(
    &self,
    context: &rquickjs::Context,
    callback_ids: &[i32],
    account_id: i32,
    message_json: &str,
  ) -> String {
    let verdict = enter_js(context, |ctx| {
      let args = json_parse_tl(&ctx, message_json)
        .and_then(|raw| crate::api::Globals::get(&ctx)?.get_message(&ctx)?.construct::<_, Value>((raw,)))
        .and_then(|message| Ok((message, dispatch_account(&ctx, &self.accounts, account_id)?)));
      let (message, account) = match args {
        Ok(args) => args,
        Err(e) => {
          (self.log)(&format!("{WHAT}: the message did not reach the plugin: {}", describe_js_error(&ctx, e)));
          return String::new();
        }
      };
      let mut spoiler = false;
      let mut rewrite = None;
      for &id in callback_ids {
        let Some(callback) = self.callbacks.restore(&ctx, id as u32) else {
          continue;
        };
        let verdict = callback
          .call::<_, Value>((message.clone(), account.clone()))
          .and_then(|verdict| read_verdict(&ctx, verdict));
        match verdict {
          Ok(Verdict::Show) => {}
          Ok(Verdict::Hide) => return "H".to_string(),
          Ok(Verdict::Spoiler) => spoiler = true,
          Ok(Verdict::Rewrite(json)) => {
            rewrite.get_or_insert(json);
          }
          Err(e) => {
            report_callback_error(&self.log, &ctx, &format!("{WHAT} callback"), e);
            if crate::sandbox::limits::is_tripped() {
              break;
            }
          }
        }
      }
      format!("{}{}", if spoiler { 'S' } else { 'V' }, rewrite.unwrap_or_default())
    });
    pump_jobs(context, self.log.as_ref());
    verdict
  }
}

impl Dispose for FilterState {
  fn dispose(&self, context: &rquickjs::Context) {
    self.rules.update(Vec::clear);
    enter_js(context, |_| drop(self.callbacks.remove_matching(|_| true)));
  }
}

fn read_verdict<'js>(ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<Verdict> {
  if value.is_undefined() {
    return Ok(Verdict::Show);
  }
  if let Some(verdict) = value.as_string() {
    return match verdict.to_string()?.as_str() {
      "show" => Ok(Verdict::Show),
      "hide" => Ok(Verdict::Hide),
      "spoiler" => Ok(Verdict::Spoiler),
      other => Err(Exception::throw_type(ctx, &format!("unknown verdict '{other}'"))),
    };
  }
  if value.is_promise() {
    return Err(Exception::throw_type(ctx, "the verdict must be returned synchronously, not as a promise"));
  }
  let invalid = || Exception::throw_type(ctx, "the verdict must be 'show', 'hide', 'spoiler' or { rewrite }");
  let rewrite: Value = value.as_object().ok_or_else(invalid)?.get("rewrite")?;
  let text = if let Some(text) = rewrite.as_string() {
    TextWithEntities {
      text: text.to_string()?,
      entities: Vec::new(),
    }
  } else {
    let rewrite = rewrite.as_object().ok_or_else(invalid)?;
    let text: Value = rewrite.get("text")?;
    TextWithEntities {
      text: text
        .as_string()
        .ok_or_else(|| Exception::throw_type(ctx, "rewrite.text must be a string"))?
        .to_string()?,
      entities: read_entities(ctx, &rewrite.get("entities")?)?,
    }
  };
  Ok(Verdict::Rewrite(stringify_json(
    ctx,
    text_to_js(ctx, &text)?.into_value(),
    "the rewrite did not serialize",
  )?))
}

fn read_matchers<'js>(ctx: &Ctx<'js>, filter: &Value<'js>) -> JsResult<Vec<Matcher>> {
  let Some(filters) = filter.as_array() else {
    return Ok(vec![read_matcher(ctx, filter)?]);
  };
  if filters.is_empty() {
    return Err(Exception::throw_type(ctx, &format!("{WHAT}: the filter list must not be empty")));
  }
  filters.iter::<Value>().map(|filter| read_matcher(ctx, &filter?)).collect()
}

/// unknown keys are refused: a misspelled field would otherwise widen the rule to every message
fn read_matcher<'js>(ctx: &Ctx<'js>, filter: &Value<'js>) -> JsResult<Matcher> {
  let Some(filter) = filter.as_object() else {
    return Err(Exception::throw_type(ctx, &format!("{WHAT}: a filter must be an object")));
  };
  for key in filter.keys::<String>() {
    let key = key?;
    if !FILTER_KEYS.contains(&key.as_str()) {
      return Err(Exception::throw_type(ctx, &format!("{WHAT}: unknown filter field '{key}'")));
    }
  }
  let text: Value = filter.get("text")?;
  let text = match text.as_bool() {
    Some(present) => Some(Presence::Present(present)),
    None => read_regex(ctx, &text, "text", "a RegExp or a boolean")?.map(Presence::Matching),
  };
  let html = read_regex(ctx, &filter.get("html")?, "html", "a RegExp")?;
  let buttons = read_regex(ctx, &filter.get("buttons")?, "buttons", "a RegExp")?;
  let senders = read_one_or_many(ctx, WHAT, filter, "sender")?
    .iter()
    .map(read_peer_id)
    .collect::<Option<Vec<_>>>()
    .ok_or_else(|| Exception::throw_type(ctx, &format!("{WHAT}: filter.sender must hold peer ids")))?;
  let (mut peers, mut peer_types) = (Vec::new(), 0);
  for peer in read_one_or_many(ctx, WHAT, filter, "peer")? {
    match read_peer_id(&peer) {
      Some(id) => peers.push(id),
      None => {
        let name = read_name(&peer, PEER_TYPES).ok_or_else(|| {
          Exception::throw_type(ctx, &format!("{WHAT}: filter.peer must hold peer ids, 'user', 'group' or 'broadcast'"))
        })?;
        peer_types |= 1 << PEER_TYPES.iter().position(|known| *known == name).unwrap_or_default();
      }
    }
  }
  let media: Value = filter.get("media")?;
  let media = match media.as_bool() {
    Some(present) => Some(Presence::Present(present)),
    None => {
      let kinds = read_one_or_many(ctx, WHAT, filter, "media")?
        .iter()
        .map(|kind| read_name(kind, MEDIA_KINDS))
        .collect::<Option<Vec<_>>>()
        .ok_or_else(|| {
          Exception::throw_type(ctx, &format!("{WHAT}: filter.media must be a boolean, or one or more media types"))
        })?;
      (!kinds.is_empty()).then_some(Presence::Matching(kinds))
    }
  };
  let forward: Value = filter.get("forward")?;
  let forward = match forward.is_undefined() {
    true => None,
    false => Some(
      forward
        .as_bool()
        .ok_or_else(|| Exception::throw_type(ctx, &format!("{WHAT}: filter.forward must be a boolean")))?,
    ),
  };
  if text.is_none()
    && html.is_none()
    && buttons.is_none()
    && senders.is_empty()
    && peers.is_empty()
    && peer_types == 0
    && media.is_none()
    && forward.is_none()
  {
    return Err(Exception::throw_type(ctx, &format!("{WHAT}: a filter must constrain at least one field")));
  }
  Ok(Matcher {
    text,
    html,
    buttons,
    senders,
    peers,
    peer_types,
    media,
    forward,
  })
}

fn read_peer_id(value: &Value<'_>) -> Option<i64> {
  value.as_number().filter(|id| id.fract() == 0.0 && id.abs() <= MAX_SAFE_INTEGER).map(|id| id as i64)
}

/// The plugin's RegExp, compiled by the `regex` crate: linear-time, so no rule can stall the app,
/// at the cost of lookaround and backreferences. `y` anchors at the start; `g` and `d` mean nothing here
fn read_regex<'js>(ctx: &Ctx<'js>, value: &Value<'js>, key: &str, expected: &str) -> JsResult<Option<Regex>> {
  if value.is_undefined() {
    return Ok(None);
  }
  let not_regexp = || Exception::throw_type(ctx, &format!("{WHAT}: filter.{key} must be {expected}"));
  let regexp: &Object = value.as_object().filter(|regexp| qjs_is_regexp(regexp)).ok_or_else(not_regexp)?;
  let source: String = regexp.get("source").map_err(|_| not_regexp())?;
  let flags: String = regexp.get("flags").map_err(|_| not_regexp())?;
  if let Some(flag) = flags.chars().find(|flag| !"dgimsuy".contains(*flag)) {
    return Err(Exception::throw_type(ctx, &format!("{WHAT}: filter.{key} uses the unsupported flag '{flag}'")));
  }
  let pattern = match flags.contains('y') {
    true => format!(r"\A(?:{source})"),
    false => source.clone(),
  };
  RegexBuilder::new(&pattern)
    .case_insensitive(flags.contains('i'))
    .multi_line(flags.contains('m'))
    .dot_matches_new_line(flags.contains('s'))
    .build()
    .map(Some)
    .map_err(|e| Exception::throw_type(ctx, &format!("{WHAT}: filter.{key} /{source}/ is not supported: {e}")))
}

#[cfg(test)]
#[path = "filters_tests.rs"]
mod tests;

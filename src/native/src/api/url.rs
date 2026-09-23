use std::cell::RefCell;

use rquickjs::class::Trace;
use rquickjs::function::{Opt, This};
use rquickjs::{Array, Class, Coerced, Ctx, Exception, Function, JsLifetime, Object, Result as JsResult, Value};
use url::{form_urlencoded, quirks, Host, Url};

use crate::utils::qjs::qjs_load_prelude;

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/url.qbc"));

/// refuses the spellings whose meaning depends on which parser reads them, before any parser
/// does: the whatwg parser behind [`Url`] strips tabs and newlines, reads `\\` as `/`, and skips
/// any number of slashes after a special scheme, while the host's `java.net.URI` does none of that
pub(crate) fn screen_url_spelling(api: &str, url: &str) -> Result<(), String> {
  if url.chars().any(|c| c.is_whitespace() || c.is_control() || c == '\\') {
    return Err(format!("{api}: a url may not contain whitespace, control characters or backslashes"));
  }
  if let Some((_, rest)) = url.split_once("://") {
    if rest.starts_with('/') {
      return Err(format!("{api}: the url has no host"));
    }
    if rest.split(['/', '?', '#']).next().unwrap_or_default().contains('@') {
      return Err(format!("{api}: a url with userinfo in it is refused"));
    }
  }
  Ok(())
}

pub fn parse_http_url(api: &str, url: &str) -> Result<String, String> {
  screen_url_spelling(api, url)?;
  let parsed = Url::parse(url).map_err(|e| format!("{api}: this is not a url: {e}"))?;
  let scheme = parsed.scheme();
  if scheme != "http" && scheme != "https" {
    return Err(format!("{api}: '{scheme}' is not a scheme this api speaks; http and https only"));
  }
  let host = match parsed.host() {
    Some(Host::Domain(domain)) => domain.trim_end_matches('.').to_string(),
    Some(Host::Ipv4(address)) => address.to_string(),
    Some(Host::Ipv6(address)) => address.to_string(),
    None => String::new(),
  };
  if host.is_empty() {
    return Err(format!("{api}: the url has no host"));
  }
  Ok(host)
}

#[derive(Trace, JsLifetime)]
#[rquickjs::class(rename = "URL", frozen)]
pub struct UrlBox {
  #[qjs(skip_trace)]
  inner: RefCell<Url>,
}

#[rquickjs::methods]
impl<'js> UrlBox {
  #[qjs(constructor)]
  fn new(ctx: Ctx<'js>, input: Coerced<String>, base: Opt<Value<'js>>) -> JsResult<Self> {
    let (input, base) = read_args(input, base);
    match parse(&input, base.as_deref()) {
      Ok(url) => Ok(UrlBox { inner: RefCell::new(url) }),
      Err(e) => Err(Exception::throw_type(&ctx, &format!("URL: {e}"))),
    }
  }

  #[qjs(static, rename = "canParse")]
  fn can_parse(input: Coerced<String>, base: Opt<Value<'js>>) -> bool {
    let (input, base) = read_args(input, base);
    parse(&input, base.as_deref()).is_ok()
  }

  #[qjs(static, rename = "parse")]
  fn parse_or_null(ctx: Ctx<'js>, input: Coerced<String>, base: Opt<Value<'js>>) -> JsResult<Value<'js>> {
    let (input, base) = read_args(input, base);
    match parse(&input, base.as_deref()) {
      Ok(url) => Ok(Class::instance(ctx.clone(), UrlBox { inner: RefCell::new(url) })?.into_value()),
      Err(_) => Ok(Value::new_null(ctx)),
    }
  }

  #[qjs(get, enumerable, configurable, rename = "href")]
  fn get_href(&self) -> String {
    quirks::href(&self.inner.borrow()).to_string()
  }

  #[qjs(set, rename = "href")]
  fn set_href(&self, ctx: Ctx<'js>, value: Coerced<String>) -> JsResult<()> {
    quirks::set_href(&mut self.inner.borrow_mut(), &value.0)
      .map_err(|e| Exception::throw_type(&ctx, &format!("URL: href: {e}")))
  }

  #[qjs(get, enumerable, configurable, rename = "origin")]
  fn get_origin(&self) -> String {
    quirks::origin(&self.inner.borrow())
  }

  #[qjs(get, enumerable, configurable, rename = "protocol")]
  fn get_protocol(&self) -> String {
    quirks::protocol(&self.inner.borrow()).to_string()
  }

  #[qjs(set, rename = "protocol")]
  fn set_protocol(&self, value: Coerced<String>) {
    let _ = quirks::set_protocol(&mut self.inner.borrow_mut(), &value.0);
  }

  #[qjs(get, enumerable, configurable, rename = "username")]
  fn get_username(&self) -> String {
    quirks::username(&self.inner.borrow()).to_string()
  }

  #[qjs(set, rename = "username")]
  fn set_username(&self, value: Coerced<String>) {
    let _ = quirks::set_username(&mut self.inner.borrow_mut(), &value.0);
  }

  #[qjs(get, enumerable, configurable, rename = "password")]
  fn get_password(&self) -> String {
    quirks::password(&self.inner.borrow()).to_string()
  }

  #[qjs(set, rename = "password")]
  fn set_password(&self, value: Coerced<String>) {
    let _ = quirks::set_password(&mut self.inner.borrow_mut(), &value.0);
  }

  #[qjs(get, enumerable, configurable, rename = "host")]
  fn get_host(&self) -> String {
    quirks::host(&self.inner.borrow()).to_string()
  }

  #[qjs(set, rename = "host")]
  fn set_host(&self, value: Coerced<String>) {
    let _ = quirks::set_host(&mut self.inner.borrow_mut(), &value.0);
  }

  #[qjs(get, enumerable, configurable, rename = "hostname")]
  fn get_hostname(&self) -> String {
    quirks::hostname(&self.inner.borrow()).to_string()
  }

  #[qjs(set, rename = "hostname")]
  fn set_hostname(&self, value: Coerced<String>) {
    let _ = quirks::set_hostname(&mut self.inner.borrow_mut(), &value.0);
  }

  #[qjs(get, enumerable, configurable, rename = "port")]
  fn get_port(&self) -> String {
    quirks::port(&self.inner.borrow()).to_string()
  }

  #[qjs(set, rename = "port")]
  fn set_port(&self, value: Coerced<String>) {
    let _ = quirks::set_port(&mut self.inner.borrow_mut(), &value.0);
  }

  #[qjs(get, enumerable, configurable, rename = "pathname")]
  fn get_pathname(&self) -> String {
    quirks::pathname(&self.inner.borrow()).to_string()
  }

  #[qjs(set, rename = "pathname")]
  fn set_pathname(&self, value: Coerced<String>) {
    quirks::set_pathname(&mut self.inner.borrow_mut(), &value.0);
  }

  #[qjs(get, enumerable, configurable, rename = "search")]
  fn get_search(&self) -> String {
    quirks::search(&self.inner.borrow()).to_string()
  }

  #[qjs(set, rename = "search")]
  fn set_search(&self, value: Coerced<String>) {
    quirks::set_search(&mut self.inner.borrow_mut(), &value.0);
  }

  #[qjs(get, enumerable, configurable, rename = "hash")]
  fn get_hash(&self) -> String {
    quirks::hash(&self.inner.borrow()).to_string()
  }

  #[qjs(set, rename = "hash")]
  fn set_hash(&self, value: Coerced<String>) {
    quirks::set_hash(&mut self.inner.borrow_mut(), &value.0);
  }

  #[qjs(rename = "toString")]
  fn serialize(&self) -> String {
    self.get_href()
  }

  #[qjs(rename = "toJSON")]
  fn serialize_json(&self) -> String {
    self.get_href()
  }
}

fn parse(input: &str, base: Option<&str>) -> Result<Url, String> {
  match base {
    Some(base) => {
      let base = Url::parse(base).map_err(|e| format!("invalid base url: {e}"))?;
      base.join(input).map_err(|e| e.to_string())
    }
    None => Url::parse(input).map_err(|e| e.to_string()),
  }
}

fn read_args(input: Coerced<String>, base: Opt<Value<'_>>) -> (String, Option<String>) {
  let base = base.0.filter(|v| !v.is_undefined()).map(|v| match v.get::<Coerced<String>>() {
    Ok(s) => s.0,
    Err(_) => String::new(),
  });
  (input.0, base)
}

pub fn install_url<'js>(ctx: &Ctx<'js>) -> JsResult<()> {
  Class::<UrlBox>::define(&ctx.globals())?;

  let natives = Object::new(ctx.clone())?;
  natives.set("parseQuery", Function::new(ctx.clone(), parse_query)?)?;
  natives.set("serializeQuery", Function::new(ctx.clone(), serialize_query)?)?;
  natives.set(
    "getQuery",
    Function::new(ctx.clone(), |url: This<rquickjs::Class<'js, UrlBox>>| {
      url.0.borrow().inner.borrow().query().unwrap_or_default().to_string()
    })?,
  )?;
  natives.set(
    "setQuery",
    Function::new(ctx.clone(), |url: This<rquickjs::Class<'js, UrlBox>>, value: Coerced<String>| {
      let class = url.0.borrow();
      let mut inner = class.inner.borrow_mut();
      inner.set_query(if value.0.is_empty() { None } else { Some(&value.0) });
    })?,
  )?;

  let factory = qjs_load_prelude(ctx, PRELUDE)?;
  factory.call::<_, ()>((natives,))?;
  Ok(())
}

fn parse_query<'js>(ctx: Ctx<'js>, input: Coerced<String>) -> JsResult<Array<'js>> {
  let out = Array::new(ctx.clone())?;
  let source = input.0.strip_prefix('?').unwrap_or(&input.0);
  for (i, (key, value)) in form_urlencoded::parse(source.as_bytes()).enumerate() {
    let pair = Array::new(ctx.clone())?;
    pair.set(0, key.as_ref())?;
    pair.set(1, value.as_ref())?;
    out.set(i, pair)?;
  }
  Ok(out)
}

fn serialize_query(pairs: Array<'_>) -> JsResult<String> {
  let mut out = form_urlencoded::Serializer::new(String::new());
  for pair in pairs.iter::<Array>() {
    let pair = pair?;
    let key: Coerced<String> = pair.get(0)?;
    let value: Coerced<String> = pair.get(1)?;
    out.append_pair(&key.0, &value.0);
  }
  Ok(out.finish())
}

#[cfg(test)]
#[path = "url_tests.rs"]
mod tests;

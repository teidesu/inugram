//! Urls: the whatwg `URL`/`URLSearchParams` globals, and the http(s) egress screen the two apis
//! that *send* one share.
//!
//! Two different jobs over one concept, in one module deliberately. [`parse_http_url`] answers
//! "may this string leave the device, and under whose name", which is a question about the string a
//! plugin typed; the classes answer "what does this string mean", which is the whatwg parser's.
//! Keeping them apart in different modules is how a codebase ends up with two url parsers that
//! disagree, and a disagreement between the thing that *screens* a url and the thing that *builds*
//! one is a bypass by construction.
//!
//! They stay separate *functions* for the same reason they share a module: the screen must keep
//! reading the string the caller passed, never a normalized form of it. Normalization is what makes
//! a url unambiguous, and the screen's whole job is refusing strings whose meaning depends on who
//! is parsing - so screening `Url::parse(input).as_str()` would screen the one form that was never
//! in question and let the ambiguous original through to the system's `ACTION_VIEW`. A plugin that
//! wants the normalizing behaviour can call `new URL(...).href` itself and pass *that*, which is
//! the same string the screen would then see.

use std::cell::RefCell;

use rquickjs::class::{JsClass, Readable, Trace, Tracer};
use rquickjs::function::{Constructor, Opt, This};
use rquickjs::{Array, Coerced, Ctx, Exception, Function, JsLifetime, Object, Result as JsResult, Value};
use url::{form_urlencoded, Url};

use crate::engine::shape::{define_accessor, define_getter, define_method};

const PRELUDE: &str = include_str!("url.js");

/// The host, lowercased, with a trailing dot and any ipv6 brackets stripped - which is the form a
/// grant's domain match is against, `a.` and `a` being one name to dns and two strings to it.
///
/// Hand-rolled rather than `Url::parse`d on purpose - see the module doc. What it refuses is a
/// string that reads as one host and parses as another, which a parser that *resolves* the
/// ambiguity cannot report.
pub fn parse_http_url(api: &str, url: &str) -> Result<String, String> {
    if url.chars().any(|c| c.is_whitespace() || c.is_control()) {
        return Err(format!("{api}: a url may not contain whitespace or control characters"));
    }
    let Some((scheme, rest)) = url.split_once("://") else {
        return Err(format!("{api}: the url has no scheme"));
    };
    let scheme = scheme.to_ascii_lowercase();
    if scheme != "http" && scheme != "https" {
        return Err(format!("{api}: '{scheme}' is not a scheme this api speaks; http and https only"));
    }
    let authority = rest.split(['/', '?', '#']).next().unwrap_or_default();
    if authority.contains('@') {
        return Err(format!("{api}: a url with userinfo in it is refused"));
    }
    if authority.contains('\\') {
        return Err(format!("{api}: a url with a backslash in its authority is refused"));
    }
    let host = match authority.strip_prefix('[') {
        Some(rest) => match rest.split_once(']') {
            Some((inside, _)) => inside,
            None => return Err(format!("{api}: the url has an unterminated ipv6 literal")),
        },
        None => authority.split(':').next().unwrap_or_default(),
    };
    let host = host.trim_end_matches('.').to_ascii_lowercase();
    if host.is_empty() {
        return Err(format!("{api}: the url has no host"));
    }
    Ok(host)
}

/// one `URL` as js holds it. `Readable` + a `RefCell` rather than `Mutable`, matching every other
/// class here: a setter re-enters js to coerce its argument, so the borrow must not span that.
pub struct UrlBox {
    inner: RefCell<Url>,
}

impl<'js> Trace<'js> for UrlBox {
    fn trace<'a>(&self, _tracer: Tracer<'a, 'js>) {}
}

unsafe impl<'js> JsLifetime<'js> for UrlBox {
    type Changed<'to> = UrlBox;
}

impl<'js> JsClass<'js> for UrlBox {
    const NAME: &'static str = "URL";
    type Mutable = Readable;

    fn constructor(_ctx: &Ctx<'js>) -> JsResult<Option<Constructor<'js>>> {
        Ok(None)
    }
}

/// `new URL(input, base)`. `base` absent and `input` relative is the failure the spec makes a
/// `TypeError`, which is what the crate's `RelativeUrlWithoutBase` becomes here.
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
    // `undefined` passed explicitly is `Some` to rquickjs but absent to webidl, and `new
    // URL(x, undefined)` must behave as `new URL(x)` rather than resolving against the literal
    // text "undefined" - the hazard `crate::engine::argv` exists for
    let base = base.0.filter(|v| !v.is_undefined()).map(|v| match v.get::<Coerced<String>>() {
        Ok(s) => s.0,
        Err(_) => String::new(),
    });
    (input.0, base)
}

/// the whatwg `search`/`hash` rule: one leading delimiter is dropped, and the empty string clears
/// the component rather than leaving `?`/`#` behind in `href`
fn optional_component(value: &str, delimiter: char) -> Option<String> {
    let trimmed = value.strip_prefix(delimiter).unwrap_or(value);
    if trimmed.is_empty() {
        None
    } else {
        Some(trimmed.to_string())
    }
}

fn mint<'js>(ctx: &Ctx<'js>, url: Url) -> JsResult<rquickjs::Class<'js, UrlBox>> {
    rquickjs::Class::instance(ctx.clone(), UrlBox { inner: RefCell::new(url) })
}

pub fn install_url<'js>(ctx: &Ctx<'js>) -> JsResult<()> {
    let proto = rquickjs::Class::<UrlBox>::prototype(ctx)?
        .ok_or_else(|| Exception::throw_message(ctx, "URL: the class has no prototype"))?;
    install_members(ctx, &proto)?;

    let ctor = Constructor::new_class::<UrlBox, _, _>(
        ctx.clone(),
        |ctx: Ctx<'js>, input: Coerced<String>, base: Opt<Value<'js>>| -> JsResult<Value<'js>> {
            let (input, base) = read_args(input, base);
            match parse(&input, base.as_deref()) {
                Ok(url) => Ok(mint(&ctx, url)?.into_value()),
                Err(e) => Err(Exception::throw_type(&ctx, &format!("URL: {e}"))),
            }
        },
    )?;

    let f = Function::new(ctx.clone(), |input: Coerced<String>, base: Opt<Value<'js>>| {
        let (input, base) = read_args(input, base);
        parse(&input, base.as_deref()).is_ok()
    })?;
    define_method(&ctor, "canParse", f)?;

    let f = Function::new(
        ctx.clone(),
        |ctx: Ctx<'js>, input: Coerced<String>, base: Opt<Value<'js>>| -> JsResult<Value<'js>> {
            let (input, base) = read_args(input, base);
            match parse(&input, base.as_deref()) {
                Ok(url) => Ok(mint(&ctx, url)?.into_value()),
                Err(_) => Ok(Value::new_null(ctx.clone())),
            }
        },
    )?;
    define_method(&ctor, "parse", f)?;

    ctx.globals().set("URL", ctor)?;

    // the query codec `URLSearchParams` is written against, so the prelude never re-implements
    // application/x-www-form-urlencoded and cannot disagree with what `href` says
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

    let mut options = rquickjs::context::EvalOptions::default();
    options.filename = Some("<inu:url>".to_string());
    let factory: Function = ctx.eval_with_options(PRELUDE, options)?;
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

/// Every accessor reads through the `RefCell`, so a plugin that kept a `URL` sees each component as
/// of now rather than as of construction - which is what makes `searchParams` writing back through
/// `setQuery` visible on `href` with nothing to invalidate.
fn install_members<'js>(ctx: &Ctx<'js>, proto: &Object<'js>) -> JsResult<()> {
    type Me<'js> = This<rquickjs::Class<'js, UrlBox>>;

    define_accessor(
        proto,
        "href",
        |this: Me<'js>| this.0.borrow().inner.borrow().as_str().to_string(),
        |ctx: Ctx<'js>, this: Me<'js>, value: Coerced<String>| -> JsResult<()> {
            // the one setter the spec *does* throw from: it is a re-parse of the whole url
            match Url::parse(&value.0) {
                Ok(parsed) => {
                    *this.0.borrow().inner.borrow_mut() = parsed;
                    Ok(())
                }
                Err(e) => Err(Exception::throw_type(&ctx, &format!("URL: href: {e}"))),
            }
        },
    )?;

    define_getter(proto, "origin", |this: Me<'js>| this.0.borrow().inner.borrow().origin().ascii_serialization())?;

    define_accessor(
        proto,
        "protocol",
        |this: Me<'js>| format!("{}:", this.0.borrow().inner.borrow().scheme()),
        |this: Me<'js>, value: Coerced<String>| {
            let scheme = value.0.strip_suffix(':').unwrap_or(&value.0).to_string();
            let _ = this.0.borrow().inner.borrow_mut().set_scheme(&scheme);
        },
    )?;

    define_accessor(
        proto,
        "username",
        |this: Me<'js>| this.0.borrow().inner.borrow().username().to_string(),
        |this: Me<'js>, value: Coerced<String>| {
            let _ = this.0.borrow().inner.borrow_mut().set_username(&value.0);
        },
    )?;

    define_accessor(
        proto,
        "password",
        |this: Me<'js>| this.0.borrow().inner.borrow().password().unwrap_or_default().to_string(),
        |this: Me<'js>, value: Coerced<String>| {
            let password = if value.0.is_empty() { None } else { Some(value.0.as_str()) };
            let _ = this.0.borrow().inner.borrow_mut().set_password(password);
        },
    )?;

    define_accessor(
        proto,
        "host",
        |this: Me<'js>| {
            let class = this.0.borrow();
            let url = class.inner.borrow();
            match (url.host_str(), url.port()) {
                (Some(host), Some(port)) => format!("{host}:{port}"),
                (Some(host), None) => host.to_string(),
                (None, _) => String::new(),
            }
        },
        |this: Me<'js>, value: Coerced<String>| {
            let class = this.0.borrow();
            let mut url = class.inner.borrow_mut();
            let (host, port) = split_host_port(&value.0);
            if url.set_host(Some(host)).is_ok() {
                // only after the host took: a port applied to the old host is a url the plugin
                // never asked for, and worse than the setter having done nothing
                let _ = url.set_port(port);
            }
        },
    )?;

    define_accessor(
        proto,
        "hostname",
        |this: Me<'js>| this.0.borrow().inner.borrow().host_str().unwrap_or_default().to_string(),
        |this: Me<'js>, value: Coerced<String>| {
            // the spec's hostname setter stops at the port delimiter rather than reading one
            let (host, _) = split_host_port(&value.0);
            let _ = this.0.borrow().inner.borrow_mut().set_host(Some(host));
        },
    )?;

    define_accessor(
        proto,
        "port",
        |this: Me<'js>| this.0.borrow().inner.borrow().port().map(|p| p.to_string()).unwrap_or_default(),
        |this: Me<'js>, value: Coerced<String>| {
            let class = this.0.borrow();
            let mut url = class.inner.borrow_mut();
            if value.0.is_empty() {
                let _ = url.set_port(None);
            } else if let Ok(port) = value.0.parse::<u16>() {
                let _ = url.set_port(Some(port));
            }
        },
    )?;

    define_accessor(
        proto,
        "pathname",
        |this: Me<'js>| this.0.borrow().inner.borrow().path().to_string(),
        |this: Me<'js>, value: Coerced<String>| this.0.borrow().inner.borrow_mut().set_path(&value.0),
    )?;

    define_accessor(
        proto,
        "search",
        |this: Me<'js>| match this.0.borrow().inner.borrow().query() {
            Some(query) if !query.is_empty() => format!("?{query}"),
            _ => String::new(),
        },
        |this: Me<'js>, value: Coerced<String>| {
            let query = optional_component(&value.0, '?');
            this.0.borrow().inner.borrow_mut().set_query(query.as_deref());
        },
    )?;

    define_accessor(
        proto,
        "hash",
        |this: Me<'js>| match this.0.borrow().inner.borrow().fragment() {
            Some(fragment) if !fragment.is_empty() => format!("#{fragment}"),
            _ => String::new(),
        },
        |this: Me<'js>, value: Coerced<String>| {
            let fragment = optional_component(&value.0, '#');
            this.0.borrow().inner.borrow_mut().set_fragment(fragment.as_deref());
        },
    )?;

    let f = Function::new(ctx.clone(), |this: Me<'js>| this.0.borrow().inner.borrow().as_str().to_string())?;
    define_method(proto, "toString", f.clone())?;
    define_method(proto, "toJSON", f)?;
    Ok(())
}

/// `host:port` as the spec's host setter reads it: an ipv6 literal keeps its brackets and its
/// colons, everything else splits at the first one.
fn split_host_port(value: &str) -> (&str, Option<u16>) {
    let rest = match value.strip_prefix('[') {
        Some(inside) => match inside.split_once(']') {
            Some((_, rest)) => rest,
            None => return (value, None),
        },
        None => value,
    };
    let Some((_, port)) = rest.split_once(':') else {
        return (value, None);
    };
    let host = &value[..value.len() - port.len() - 1];
    (host, port.parse::<u16>().ok())
}

#[cfg(test)]
#[path = "url_tests.rs"]
mod tests;

//! `inu.icons.common`/`inu.icons.svg` and `inu.android.resourceIcon`.
//!
//! An icon is a *descriptor*, never a drawable: what crosses is one spec string (`r<resource name>`
//! or `s<svg source>`) and the host turns it into a `Drawable` on the ui thread as a row binds. So
//! minting one needs no `Activity`, and a rotation or an icon-pack change re-resolves the same spec
//! with nothing to invalidate.
//!
//! The host is asked one question, [`IconHost::icon_resolves`]. Everything else - the curated
//! table, the shape of a resource name, the size and markup rules on an svg - is decided here, so
//! the policy is testable without a device.

use std::rc::Rc;

use rquickjs::{Ctx, Exception, Function, Object, Result as JsResult, Value};

use crate::sandbox::error::{get_or_create_inu, make_plugin_error, throw_plugin_error};

/// the most utf-8 an `inu.icons.svg` source may be. The host parses it with the platform's xml
/// reader, which no interpreter deadline can interrupt (it is one host call), so the bound is a
/// byte count the contract can state rather than time spent - same rule as `blob.rs`'s
/// `BUILD_LIMIT_BYTES`. An icon is a glyph; 64 KiB is an order of magnitude more than one needs.
pub const SVG_LIMIT_BYTES: usize = 64 * 1024;

/// longest drawable name the host will be asked about
const MAX_RESOURCE_NAME: usize = 128;

/// where the spec lives on the object `inu.icons.*` hands back
const ICON_TAG: &str = "__inuIcon";

pub const KIND_RESOURCE: i32 = 0;
pub const KIND_SVG: i32 = 1;

/// stand-in for the icon half of the Kotlin `QuickJs.ApiListener`
pub trait IconHost {
    /// `kind` is [`KIND_RESOURCE`] (a bare drawable name) or [`KIND_SVG`] (svg source). `false`
    /// means nothing on this host resolves it; which error that is, is decided here.
    fn icon_resolves(&self, kind: i32, value: &str) -> bool;
}

/// The set `inu.icons.common` answers for, mapped onto the drawable the app ships for it. Sorted
/// by api name so the lookup can binary-search; a test pins both the order and that the set is
/// exactly the union `common.d.ts` declares.
///
/// Everything here is a stock drawable rather than a fork asset, and none of them is a `_solar`
/// variant: `IconsResources` swaps a stock id for the user's icon pack at `getDrawable` time, so
/// naming the plain one is what makes a plugin's icon follow the pack.
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

/// A bare `[A-Za-z0-9_]` name and nothing else, because the host looks it up with
/// `Resources.getIdentifier`, which also accepts a qualified `package:type/name` - a plugin that
/// could write one would be naming any resource of any type in any installed package rather than
/// a drawable in this one.
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
    // a document type declaration is the only thing in xml that can name an external resource or
    // expand to more of itself, and the host parses this with whichever expat android ships rather
    // than one we configured. refused at the door instead: `<!` may only ever open a comment.
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

/// The rules a spec must satisfy wherever it is read, not only where it was minted. The object
/// carrying it is an ordinary one a plugin can build itself, so an element that takes an icon
/// re-checks rather than trusting the tag: a forged spec then names a drawable that does not
/// exist (and renders nothing), never an unbounded string or a qualified resource reference.
fn validate_spec<'js>(ctx: &Ctx<'js>, what: &str, spec: &str) -> JsResult<()> {
    let valid = match spec.as_bytes().first() {
        Some(b'r') => is_resource_name(&spec[1..]),
        Some(b's') => check_svg(&spec[1..]).is_ok(),
        _ => false,
    };
    if valid {
        return Ok(());
    }
    throw_plugin_error(
        ctx,
        "invalid-argument",
        &format!("{what}: 'icon' is not an icon inu.icons handed out"),
        None,
        None,
        None,
    )
}

/// reads an optional `icon` off an element's options object, answering the spec to put on the wire
pub fn opt_icon<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str) -> JsResult<Option<String>> {
    let value: Value =
        obj.get("icon").map_err(|_| Exception::throw_type(ctx, &format!("{what}: cannot read 'icon'")))?;
    if value.is_undefined() || value.is_null() {
        return Ok(None);
    }
    let spec = value
        .as_object()
        .and_then(|o| o.get::<_, Option<String>>(ICON_TAG).ok().flatten())
        .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: 'icon' must come from inu.icons")))?;
    validate_spec(ctx, what, &spec)?;
    Ok(Some(spec))
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
        return throw_plugin_error(
            ctx,
            "invalid-argument",
            &format!("icons.common: unknown icon '{name}'"),
            None,
            None,
            None,
        );
    };
    if !host.icon_resolves(KIND_RESOURCE, resource) {
        // the name is in the curated set, so this is the app having dropped the drawable behind it
        return throw_plugin_error(
            ctx,
            "not-found",
            &format!("icons.common: this app ships no '{resource}' for '{name}'"),
            None,
            None,
            None,
        );
    }
    new_icon(ctx, resource_spec(resource))
}

fn js_resource_icon<'js>(ctx: &Ctx<'js>, host: &Rc<dyn IconHost>, name: Value<'js>) -> JsResult<Object<'js>> {
    let name = as_str(ctx, "android.resourceIcon", &name)?;
    if !is_resource_name(&name) {
        return throw_plugin_error(
            ctx,
            "invalid-argument",
            &format!("android.resourceIcon: '{name}' is not a drawable name"),
            None,
            None,
            None,
        );
    }
    if !host.icon_resolves(KIND_RESOURCE, &name) {
        return throw_plugin_error(
            ctx,
            "not-found",
            &format!("android.resourceIcon: no drawable named '{name}'"),
            None,
            None,
            None,
        );
    }
    new_icon(ctx, resource_spec(&name))
}

fn js_svg<'js>(ctx: &Ctx<'js>, host: &Rc<dyn IconHost>, source: Value<'js>) -> JsResult<Object<'js>> {
    let source = as_str(ctx, "icons.svg", &source)?;
    match check_svg(&source) {
        Ok(()) => {}
        Err(SvgReject::TooLarge(size)) => {
            let error = make_plugin_error(
                ctx,
                "quota-exceeded",
                &format!("icons.svg: {size} bytes of source, the limit is {SVG_LIMIT_BYTES}"),
                None,
                Some(size as i64),
                Some(SVG_LIMIT_BYTES as i64),
            )?;
            return Err(ctx.throw(error));
        }
        Err(SvgReject::NotSvg) => {
            return throw_plugin_error(
                ctx,
                "invalid-argument",
                "icons.svg: the source carries no <svg> element",
                None,
                None,
                None,
            )
        }
        Err(SvgReject::Markup) => {
            return throw_plugin_error(
                ctx,
                "invalid-argument",
                "icons.svg: a doctype or other markup declaration is not allowed",
                None,
                None,
                None,
            )
        }
    }
    if !host.icon_resolves(KIND_SVG, &source) {
        return throw_plugin_error(ctx, "invalid-argument", "icons.svg: the source did not parse", None, None, None);
    }
    new_icon(ctx, svg_spec(&source))
}

pub fn install_icons<'js>(ctx: &Ctx<'js>, host: Rc<dyn IconHost>) -> JsResult<()> {
    let inu = get_or_create_inu(ctx)?;

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

    Ok(())
}

#[cfg(test)]
#[path = "icons_tests.rs"]
pub(crate) mod tests;

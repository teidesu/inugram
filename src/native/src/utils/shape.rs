//! Building the shape a plugin sees: the two property forms every rust-built prototype in the
//! crate uses.
//!
//! Both are enumerable and configurable, which is what an object literal gives and what
//! `structuredClone`, `Object.keys` and a plugin's own `Object.defineProperty` over one then
//! behave like. A class method would be non-enumerable; these are deliberately not that.

use rquickjs::object::{Accessor, Property};
use rquickjs::{Function, Object, Result as JsResult};

pub fn define_getter<'js, F, P>(target: &Object<'js>, name: &str, get: F) -> JsResult<()>
where
    F: rquickjs::function::IntoJsFunc<'js, P> + 'js,
{
    target.prop(name, Accessor::new_get(get).enumerable().configurable())
}

pub fn define_method<'js>(target: &Object<'js>, name: &str, f: Function<'js>) -> JsResult<()> {
    target.prop(name, Property::from(f).writable().enumerable().configurable())
}

/// a read/write attribute. Separate from [`define_getter`] because a webidl setter that refuses its
/// input does **not** throw - it leaves the attribute alone - so every setter built with this
/// returns `()` and swallows what it could not apply.
pub fn define_accessor<'js, G, S, PG, PS>(target: &Object<'js>, name: &str, get: G, set: S) -> JsResult<()>
where
    G: rquickjs::function::IntoJsFunc<'js, PG> + 'js,
    S: rquickjs::function::IntoJsFunc<'js, PS> + 'js,
{
    target.prop(name, Accessor::new(get, set).enumerable().configurable())
}

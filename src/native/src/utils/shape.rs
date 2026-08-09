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

pub fn define_accessor<'js, G, S, PG, PS>(target: &Object<'js>, name: &str, get: G, set: S) -> JsResult<()>
where
  G: rquickjs::function::IntoJsFunc<'js, PG> + 'js,
  S: rquickjs::function::IntoJsFunc<'js, PS> + 'js,
{
  target.prop(name, Accessor::new(get, set).enumerable().configurable())
}

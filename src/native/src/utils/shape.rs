use rquickjs::class::JsClass;
use rquickjs::object::{Accessor, Property};
use rquickjs::{Class, Ctx, Exception, Function, Object, Result as JsResult};

use crate::utils::qjs::qjs_symbol_dispose_atom;

pub fn define_getter<'js, F, P>(target: &Object<'js>, name: &str, get: F) -> JsResult<()>
where
  F: rquickjs::function::IntoJsFunc<'js, P> + 'js,
{
  target.prop(name, Accessor::new_get(get).enumerable().configurable())
}

pub fn define_method<'js>(target: &Object<'js>, name: &str, f: Function<'js>) -> JsResult<()> {
  target.prop(name, Property::from(f).writable().enumerable().configurable())
}

pub fn alias_dispose<'js, C: JsClass<'js>>(ctx: &Ctx<'js>) -> JsResult<()> {
  let proto = get_class_prototype::<C>(ctx)?;
  let dispose: Function = proto.get("dispose")?;
  proto.prop(qjs_symbol_dispose_atom(ctx)?, Property::from(dispose).writable().configurable())
}

pub fn get_class_prototype<'js, C: JsClass<'js>>(ctx: &Ctx<'js>) -> JsResult<Object<'js>> {
  Class::<C>::prototype(ctx)?
    .ok_or_else(|| Exception::throw_message(ctx, &format!("{}: the class has no prototype", C::NAME)))
}

use rquickjs::class::JsClass;
use rquickjs::object::{Accessor, Property};
use rquickjs::{qjs, Atom, Class, Ctx, Exception, Function, Object, Result as JsResult, Value};

pub fn define_getter<'js, F, P>(target: &Object<'js>, name: &str, get: F) -> JsResult<()>
where
  F: rquickjs::function::IntoJsFunc<'js, P> + 'js,
{
  target.prop(name, Accessor::new_get(get).enumerable().configurable())
}

pub fn define_method<'js>(target: &Object<'js>, name: &str, f: Function<'js>) -> JsResult<()> {
  target.prop(name, Property::from(f).writable().enumerable().configurable())
}

/// `dispose()` plus `[Symbol.dispose]`, so a handle is also a `using` resource. The symbol is
/// non-enumerable per the explicit-resource-management proposal, unlike the named method.
pub fn define_disposable<'js>(ctx: &Ctx<'js>, target: &Object<'js>, f: Function<'js>) -> JsResult<()> {
  define_method(target, "dispose", f.clone())?;
  target.prop(dispose_atom(ctx)?, Property::from(f).writable().configurable())
}

/// rquickjs has no `dispose` entry in its well-known symbols or `PredefinedAtom`. Read the atom
/// directly from quickjs-ng, where it always exists, rather than from the mutable global `Symbol`
/// object.
fn dispose_atom<'js>(ctx: &Ctx<'js>) -> JsResult<Atom<'js>> {
  // SAFETY: a static atom of this runtime, turned into a value this scope then owns
  let symbol = unsafe {
    let raw = qjs::JS_AtomToValue(ctx.as_raw().as_ptr(), qjs::JS_ATOM_Symbol_dispose as qjs::JSAtom);
    Value::from_raw(ctx.clone(), raw)
  };
  Atom::from_value(ctx.clone(), &symbol)
}

pub fn define_accessor<'js, G, S, PG, PS>(target: &Object<'js>, name: &str, get: G, set: S) -> JsResult<()>
where
  G: rquickjs::function::IntoJsFunc<'js, PG> + 'js,
  S: rquickjs::function::IntoJsFunc<'js, PS> + 'js,
{
  target.prop(name, Accessor::new(get, set).enumerable().configurable())
}

/// the prototype members of a handle class are defined on, named by the class it belongs to
pub fn get_class_prototype<'js, C: JsClass<'js>>(ctx: &Ctx<'js>) -> JsResult<Object<'js>> {
  Class::<C>::prototype(ctx)?
    .ok_or_else(|| Exception::throw_message(ctx, &format!("{}: the class has no prototype", C::NAME)))
}

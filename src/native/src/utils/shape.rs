use rquickjs::object::{Accessor, Property};
use rquickjs::{qjs, Atom, Ctx, Function, Object, Result as JsResult, Value};

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

/// rquickjs' own well-known symbols stop at `asyncIterator` and `PredefinedAtom` has no variant
/// for this one, so this is its `impl_symbols!` by hand. The atom is one quickjs-ng always has,
/// which is why it is taken from the runtime rather than looked up on the global `Symbol` - a
/// lookup would read whatever that object happens to hold.
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

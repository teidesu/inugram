use std::cell::RefCell;
use std::rc::Rc;

use rquickjs::function::Constructor;
use rquickjs::{Ctx, Exception, Function, JsLifetime, Object, Result as JsResult};

pub(crate) mod canvas;
pub(crate) mod error;
pub(crate) mod globals;
pub(crate) mod info;
pub(crate) mod io;
pub(crate) mod lifecycle;
pub(crate) mod platform;
pub(crate) mod telegram;
pub(crate) mod timers;
pub(crate) mod tl;
pub(crate) mod ui;
pub(crate) mod url;

/// `Reflect` as it was before any plugin code ran, for a proxy trap handing an access back to
/// ordinary semantics: rquickjs has no property access that takes a receiver
#[derive(Clone, JsLifetime)]
pub(crate) struct ReflectFns<'js> {
  pub(crate) get: Function<'js>,
  pub(crate) set: Function<'js>,
  pub(crate) define_property: Function<'js>,
  pub(crate) delete_property: Function<'js>,
  pub(crate) get_own_property_descriptor: Function<'js>,
  pub(crate) own_keys: Function<'js>,
}

/// Captured before any plugin code runs, so a plugin replacing a global cannot change what the
/// engine calls.
#[derive(Clone, JsLifetime)]
pub(crate) struct Globals<'js> {
  pub(crate) inu: Object<'js>,
  pub(crate) plugin_error: Constructor<'js>,
  pub(crate) reflect: ReflectFns<'js>,
  message: Rc<RefCell<Option<Constructor<'js>>>>,
  rpc_error: Rc<RefCell<Option<Constructor<'js>>>>,
}

impl<'js> Globals<'js> {
  pub(crate) fn install(ctx: &Ctx<'js>, plugin_error: Constructor<'js>) -> JsResult<()> {
    let inu = Object::new(ctx.clone())?;
    inu.set("PluginError", plugin_error.clone())?;
    ctx.globals().set("inu", inu.clone())?;
    let reflect: Object = ctx.globals().get("Reflect")?;
    ctx.store_userdata(Self {
      inu,
      plugin_error,
      reflect: ReflectFns {
        get: reflect.get("get")?,
        set: reflect.get("set")?,
        define_property: reflect.get("defineProperty")?,
        delete_property: reflect.get("deleteProperty")?,
        get_own_property_descriptor: reflect.get("getOwnPropertyDescriptor")?,
        own_keys: reflect.get("ownKeys")?,
      },
      message: Rc::new(RefCell::new(None)),
      rpc_error: Rc::new(RefCell::new(None)),
    })?;
    Ok(())
  }

  pub(crate) fn get(ctx: &Ctx<'js>) -> JsResult<Self> {
    let globals = ctx
      .userdata::<Self>()
      .ok_or_else(|| Exception::throw_message(ctx, "engine globals are not installed"))?;
    Ok((*globals).clone())
  }

  pub(crate) fn get_namespace(&self, ctx: &Ctx<'js>, name: &str) -> JsResult<Object<'js>> {
    if let Ok(existing) = self.inu.get::<_, Object>(name) {
      return Ok(existing);
    }
    let fresh = Object::new(ctx.clone())?;
    self.inu.set(name, fresh.clone())?;
    Ok(fresh)
  }

  pub(crate) fn set_message(&self, message: Constructor<'js>) -> JsResult<()> {
    self.inu.set("Message", message.clone())?;
    *self.message.borrow_mut() = Some(message);
    Ok(())
  }

  pub(crate) fn get_message(&self, ctx: &Ctx<'js>) -> JsResult<Constructor<'js>> {
    self
      .message
      .borrow()
      .clone()
      .ok_or_else(|| Exception::throw_message(ctx, "inu.Message is not installed"))
  }

  pub(crate) fn set_rpc_error(&self, rpc_error: Constructor<'js>) -> JsResult<()> {
    self.inu.set("RpcError", rpc_error.clone())?;
    *self.rpc_error.borrow_mut() = Some(rpc_error);
    Ok(())
  }

  pub(crate) fn get_rpc_error(&self, ctx: &Ctx<'js>) -> JsResult<Constructor<'js>> {
    self
      .rpc_error
      .borrow()
      .clone()
      .ok_or_else(|| Exception::throw_message(ctx, "inu.RpcError is not installed"))
  }
}

use std::cell::RefCell;
use std::rc::Rc;

use rquickjs::function::Constructor;
use rquickjs::{Ctx, Exception, JsLifetime, Object, Result as JsResult};

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

#[derive(Clone)]
pub(crate) struct Globals<'js> {
  pub(crate) inu: Object<'js>,
  pub(crate) plugin_error: Constructor<'js>,
  message: Rc<RefCell<Option<Constructor<'js>>>>,
  rpc_error: Rc<RefCell<Option<Constructor<'js>>>>,
}

// SAFETY: every JavaScript-lifetime-bound field uses the struct's `'js` lifetime.
unsafe impl<'js> JsLifetime<'js> for Globals<'js> {
  type Changed<'to> = Globals<'to>;
}

impl<'js> Globals<'js> {
  pub(crate) fn install(ctx: &Ctx<'js>, plugin_error: Constructor<'js>) -> JsResult<()> {
    let inu = Object::new(ctx.clone())?;
    inu.set("PluginError", plugin_error.clone())?;
    ctx.globals().set("inu", inu.clone())?;
    ctx.store_userdata(Self {
      inu,
      plugin_error,
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

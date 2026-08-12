use std::rc::Rc;

use rquickjs::{Ctx, Exception, Function, Object, Result as JsResult, Value};

use crate::api::error;
use crate::sandbox::grants::{GrantHost, MATCH_EXACT};

pub const KV_GET: i32 = 0;
pub const KV_SET: i32 = 1;
pub const KV_DEL: i32 = 2;
pub const KV_KEYS: i32 = 3;
pub const KV_CLEAR: i32 = 4;
pub const KV_GET_ALL: i32 = 5;
pub const KV_INSERT_ALL: i32 = 6;
pub const KV_HAS: i32 = 7;
pub const KV_USAGE: i32 = 8;

pub trait KvHost {
  fn kv(&self, op: i32, key: &str, value: &str) -> String;
}

struct KvState {
  host: Rc<dyn KvHost>,
  grants: Rc<dyn GrantHost>,
}

fn kv_result_to_js<'js>(ctx: &Ctx<'js>, wire: &str) -> JsResult<Value<'js>> {
  use rquickjs::IntoJs;
  if let Some(built) = error::wire_error_to_js(ctx, wire) {
    return Err(ctx.throw(built?));
  }
  let Some(tag) = wire.chars().next() else {
    return Err(Exception::throw_message(ctx, "kv: empty host response"));
  };
  let payload = &wire[tag.len_utf8()..];
  match tag {
    'N' => Ok(Value::new_null(ctx.clone())),
    'S' => payload.into_js(ctx),
    'J' => ctx.json_parse(payload),
    _ => Err(Exception::throw_message(ctx, &format!("kv: malformed host response tag '{tag}'"))),
  }
}

impl KvState {
  fn call<'js>(&self, ctx: &Ctx<'js>, op: i32, key: &str, value: &str) -> JsResult<Value<'js>> {
    self.grants.check_grant(ctx, "kv", None, MATCH_EXACT)?;
    let wire = self.host.kv(op, key, value);
    kv_result_to_js(ctx, &wire)
  }
}

pub fn install_kv<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn KvHost>,
  grants: Rc<dyn GrantHost>,
  globals: &crate::api::Globals<'js>,
) -> JsResult<()> {
  let state = Rc::new(KvState { host, grants });
  let kv = Object::new(ctx.clone())?;

  for (name, op) in [("get", KV_GET), ("del", KV_DEL), ("has", KV_HAS)] {
    let state2 = state.clone();
    kv.set(name, Function::new(ctx.clone(), move |ctx: Ctx<'js>, key: String| state2.call(&ctx, op, &key, ""))?)?;
  }
  for (name, op) in [("keys", KV_KEYS), ("clear", KV_CLEAR), ("getAll", KV_GET_ALL), ("usage", KV_USAGE)] {
    let state2 = state.clone();
    kv.set(name, Function::new(ctx.clone(), move |ctx: Ctx<'js>| state2.call(&ctx, op, "", ""))?)?;
  }

  let state2 = state.clone();
  kv.set(
    "set",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, key: String, value: String| {
      state2.call(&ctx, KV_SET, &key, &value)
    })?,
  )?;

  let state2 = state.clone();
  kv.set(
    "insertAll",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, values: Value<'js>| -> JsResult<Value<'js>> {
      if !values.is_object() {
        return Err(Exception::throw_type(&ctx, "kv.insertAll: expected an object"));
      }
      let json = ctx
        .json_stringify(values)?
        .map(|s| s.to_string())
        .transpose()?
        .ok_or_else(|| Exception::throw_type(&ctx, "kv.insertAll: expected an object"))?;
      state2.call(&ctx, KV_INSERT_ALL, "", &json)
    })?,
  )?;
  globals.inu.set("kv", kv)?;
  Ok(())
}

#[cfg(test)]
#[path = "kv_tests.rs"]
mod kv_tests;

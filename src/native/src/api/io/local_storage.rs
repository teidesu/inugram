use std::cell::{Cell, RefCell, RefMut};
use std::collections::BTreeMap;
use std::fs::{self, File};
use std::io;
use std::ops::Bound;
use std::path::{Path, PathBuf};
use std::rc::Rc;

use rquickjs::atom::PredefinedAtom;
use rquickjs::class::Trace;
use rquickjs::function::{Opt, This};
use rquickjs::object::Property;
use rquickjs::proxy::ProxyHandler;
use rquickjs::{
  Array, Atom, Class, Coerced, Ctx, Exception, Function, JsLifetime, Object, Proxy, Result as JsResult, Value,
};

use crate::api::error::PluginErrorCode;
use crate::api::Globals;
use crate::utils::shape::{define_getter, define_method, get_class_prototype};

/// `dom.d.ts`: 1 MB per plugin, keys and values both counted as utf-8 bytes
pub(crate) const QUOTA_BYTES: usize = 1 << 20;

/// Flushed whole, once per microtask turn and on drop. A file that is not an object of strings moves to
/// `<path>.corrupt`; `PluginLocalStorage.wipe` removes it and `<path>.tmp`.
struct Store {
  path: PathBuf,
  entries: BTreeMap<String, String>,
  used: usize,
  dirty: bool,
  /// the last `key(i)` answer, so walking `0..length` costs a step each rather than a scan
  cursor: Option<(usize, String)>,
}

impl Store {
  fn open(ctx: &Ctx<'_>, path: &Path) -> JsResult<Store> {
    let entries: BTreeMap<String, String> = match fs::read(path) {
      Ok(bytes) => match serde_json::from_slice(&bytes) {
        Ok(entries) => entries,
        Err(_) => {
          fs::rename(path, path.with_added_extension("corrupt"))
            .or_else(|e| PluginErrorCode::Internal.throw(ctx, &format!("localStorage: {}", e)))?;
          BTreeMap::new()
        }
      },
      Err(e) if e.kind() == io::ErrorKind::NotFound => BTreeMap::new(),
      Err(e) => return PluginErrorCode::Internal.throw(ctx, &format!("localStorage: {}", e)),
    };
    let used = entries.iter().map(|(key, value)| key.len() + value.len()).sum();
    Ok(Store {
      path: path.to_path_buf(),
      entries,
      used,
      dirty: false,
      cursor: None,
    })
  }

  fn set_all(&mut self, pairs: Vec<(String, String)>) -> bool {
    let mut used = self.used;
    for (key, value) in &pairs {
      used -= self.entries.get(key).map_or(0, |old| key.len() + old.len());
      used += key.len() + value.len();
    }
    if used > QUOTA_BYTES {
      return false;
    }
    for (key, value) in pairs {
      if self.entries.get(&key) != Some(&value) {
        self.entries.insert(key, value);
        self.dirty = true;
        self.cursor = None;
      }
    }
    self.used = used;
    true
  }

  fn delete(&mut self, key: &str) {
    if let Some(old) = self.entries.remove(key) {
      self.used -= key.len() + old.len();
      self.dirty = true;
      self.cursor = None;
    }
  }

  fn clear(&mut self) {
    if !self.entries.is_empty() {
      self.entries.clear();
      self.used = 0;
      self.dirty = true;
      self.cursor = None;
    }
  }

  fn key_at(&mut self, index: usize) -> Option<&str> {
    let key = match self.cursor.take() {
      Some((at, key)) if at == index => Some(key),
      Some((at, key)) if at + 1 == index => self
        .entries
        .range::<str, _>((Bound::Excluded(key.as_str()), Bound::Unbounded))
        .next()
        .map(|(k, _)| k.clone()),
      _ => self.entries.keys().nth(index).cloned(),
    }?;
    Some(&self.cursor.insert((index, key)).1)
  }

  /// Syncs before renaming, so a crash cannot leave the path pointing at unwritten data.
  fn flush(&mut self) -> io::Result<()> {
    if !self.dirty {
      return Ok(());
    }
    if self.entries.is_empty() {
      match fs::remove_file(&self.path) {
        Ok(()) => {}
        Err(e) if e.kind() == io::ErrorKind::NotFound => {}
        Err(e) => return Err(e),
      }
    } else {
      let staged = self.path.with_added_extension("tmp");
      let mut file = File::create(&staged)?;
      serde_json::to_writer(&mut file, &self.entries)?;
      file.sync_all()?;
      fs::rename(&staged, &self.path)?;
    }
    self.dirty = false;
    Ok(())
  }
}

impl Drop for Store {
  fn drop(&mut self) {
    let _ = self.flush();
  }
}

struct StorageState {
  path: PathBuf,
  store: RefCell<Option<Store>>,
  flush_queued: Cell<bool>,
}

impl StorageState {
  /// Opens on first use to avoid startup I/O for plugins that never touch it.
  fn open_store(&self, ctx: &Ctx<'_>) -> JsResult<RefMut<'_, Store>> {
    if self.path.as_os_str().is_empty() {
      return PluginErrorCode::Internal.throw(ctx, "localStorage: this plugin has no store");
    }
    let mut slot = self.store.borrow_mut();
    if slot.is_none() {
      *slot = Some(Store::open(ctx, &self.path)?);
    }
    Ok(RefMut::map(slot, |slot| slot.as_mut().expect("opened above")))
  }

  fn get<'js>(&self, ctx: &Ctx<'js>, key: &str) -> JsResult<Option<Value<'js>>> {
    match self.open_store(ctx)?.entries.get(key) {
      Some(value) => Ok(Some(rquickjs::String::from_str(ctx.clone(), value)?.into_value())),
      None => Ok(None),
    }
  }

  fn contains(&self, ctx: &Ctx<'_>, key: &str) -> JsResult<bool> {
    Ok(self.open_store(ctx)?.entries.contains_key(key))
  }

  fn flush(&self, ctx: &Ctx<'_>) -> JsResult<()> {
    self.flush_queued.set(false);
    let Some(store) = self.store.borrow_mut().as_mut().map(Store::flush) else {
      return Ok(());
    };
    store.or_else(|e| PluginErrorCode::Internal.throw(ctx, &format!("localStorage: {}", e)))
  }
}

/// The target behind `localStorage`. It never holds a string-keyed property: every one is an item,
/// routed to the store by the traps, and only a symbol-keyed one lands on the target itself.
///
/// The store is reached only by native code, between conversions: a key's `toString`, a prototype
/// getter or a proxy up the chain is plugin code, and a store borrowed across one of them would
/// panic the engine when it reenters.
#[derive(JsLifetime, Trace)]
#[rquickjs::class(rename = "Storage", frozen)]
pub struct StorageTarget<'js> {
  #[qjs(skip_trace)]
  state: Rc<StorageState>,
  flush: Function<'js>,
}

impl<'js> StorageTarget<'js> {
  fn set_all(&self, ctx: &Ctx<'js>, pairs: Vec<(String, String)>) -> JsResult<()> {
    if !self.state.open_store(ctx)?.set_all(pairs) {
      return Err(Exception::throw_dom(ctx, "QuotaExceededError", "localStorage: the 1 MB quota is exceeded"));
    }
    self.queue_flush()
  }

  fn delete(&self, ctx: &Ctx<'js>, key: &str) -> JsResult<()> {
    self.state.open_store(ctx)?.delete(key);
    self.queue_flush()
  }

  fn clear(&self, ctx: &Ctx<'js>) -> JsResult<()> {
    self.state.open_store(ctx)?.clear();
    self.queue_flush()
  }

  fn queue_flush(&self) -> JsResult<()> {
    let dirty = self.state.store.borrow().as_ref().is_some_and(|store| store.dirty);
    if !dirty || self.state.flush_queued.replace(true) {
      return Ok(());
    }
    self.flush.defer(())
  }
}

type Target<'js> = Class<'js, StorageTarget<'js>>;

fn get_target<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<Target<'js>> {
  Target::from_value(value).map_err(|_| Exception::throw_type(ctx, "localStorage: not a storage target"))
}

/// what a member's `this` stands for: `localStorage` itself, which is the proxy, or its target
/// when a prototype getter reaches it through the ordinary lookup a trap falls back to
fn get_this_target<'js>(ctx: &Ctx<'js>, this: &Value<'js>) -> JsResult<Target<'js>> {
  let target = match this.as_proxy() {
    Some(proxy) => proxy.target()?.into_value(),
    None => this.clone(),
  };
  Target::from_value(&target).map_err(|_| Exception::throw_type(ctx, "Illegal invocation"))
}

/// WebIDL's named property visibility: an item is a property only when nothing on the prototype
/// chain has its name, so `getItem` and `length` always read as the members they are
fn get_visible_key<'js>(ctx: &Ctx<'js>, target: &Target<'js>, prop: &Value<'js>) -> JsResult<Option<String>> {
  let Some(key) = prop.as_string() else {
    return Ok(None);
  };
  if target.as_inner().contains_key(Atom::from_value(ctx.clone(), prop)?)? {
    return Ok(None);
  }
  key.to_string().map(Some)
}

fn is_proxy_for_target<'js>(receiver: &Value<'js>, target: &Target<'js>) -> JsResult<bool> {
  match receiver.as_proxy() {
    Some(proxy) => Ok(&proxy.target()? == target.as_inner()),
    None => Ok(false),
  }
}

fn build_item_descriptor<'js>(ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<Object<'js>> {
  let descriptor = Object::new(ctx.clone())?;
  descriptor.set("value", value)?;
  descriptor.set("writable", true)?;
  descriptor.set("enumerable", true)?;
  descriptor.set("configurable", true)?;
  Ok(descriptor)
}

/// Named properties need a trap for every way to reach one. An exotic class would take them without
/// a proxy, but rquickjs 0.14's exotic hooks double-free every assigned value, cannot intercept
/// `defineProperty`, and hand symbol keys over as strings (`private/rquickjs-trap-issue.md`).
fn build_handler<'js>(ctx: &Ctx<'js>) -> JsResult<Object<'js>> {
  let handler = Object::new(ctx.clone())?;

  handler.set(
    PredefinedAtom::Getter,
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>, target: Value<'js>, prop: Value<'js>, receiver: Value<'js>| -> JsResult<Value<'js>> {
        let storage = get_target(&ctx, &target)?;
        if let Some(key) = get_visible_key(&ctx, &storage, &prop)? {
          if let Some(value) = storage.borrow().state.get(&ctx, &key)? {
            return Ok(value);
          }
        }
        let get = Globals::get(&ctx)?.reflect.get;
        get.call((target, prop, receiver))
      },
    )?,
  )?;

  handler.set(
    PredefinedAtom::Setter,
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>,
       target: Value<'js>,
       prop: Value<'js>,
       value: Value<'js>,
       receiver: Value<'js>|
       -> JsResult<bool> {
        let storage = get_target(&ctx, &target)?;
        if let Some(key) = prop.as_string() {
          if is_proxy_for_target(&receiver, &storage)? {
            let pair = (key.to_string()?, to_dom_string(value)?);
            storage.borrow().set_all(&ctx, vec![pair])?;
            return Ok(true);
          }
        }
        let set = Globals::get(&ctx)?.reflect.set;
        set.call((target, prop, value, receiver))
      },
    )?,
  )?;

  handler.set(
    PredefinedAtom::DefineProperty,
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>, target: Value<'js>, prop: Value<'js>, descriptor: Object<'js>| -> JsResult<bool> {
        let storage = get_target(&ctx, &target)?;
        let Some(key) = prop.as_string() else {
          let define_property = Globals::get(&ctx)?.reflect.define_property;
          return define_property.call((target, prop, descriptor));
        };
        // proxy invariants refuse a non-configurable property the target lacks, and would do so
        // only after the item was already written
        if descriptor.contains_key("get")?
          || descriptor.contains_key("set")?
          || descriptor.get::<_, Option<bool>>("configurable")? == Some(false)
        {
          return Ok(false);
        }
        let pair = (key.to_string()?, to_dom_string(descriptor.get("value")?)?);
        storage.borrow().set_all(&ctx, vec![pair])?;
        Ok(true)
      },
    )?,
  )?;

  handler.set(
    PredefinedAtom::Has,
    Function::new(ctx.clone(), |ctx: Ctx<'js>, target: Value<'js>, prop: Value<'js>| -> JsResult<bool> {
      let storage = get_target(&ctx, &target)?;
      if storage.as_inner().contains_key(Atom::from_value(ctx.clone(), &prop)?)? {
        return Ok(true);
      }
      match prop.as_string() {
        Some(key) => storage.borrow().state.contains(&ctx, &key.to_string()?),
        None => Ok(false),
      }
    })?,
  )?;

  handler.set(
    PredefinedAtom::DeleteProperty,
    Function::new(ctx.clone(), |ctx: Ctx<'js>, target: Value<'js>, prop: Value<'js>| -> JsResult<bool> {
      let storage = get_target(&ctx, &target)?;
      if let Some(key) = get_visible_key(&ctx, &storage, &prop)? {
        storage.borrow().delete(&ctx, &key)?;
        return Ok(true);
      }
      let delete_property = Globals::get(&ctx)?.reflect.delete_property;
      delete_property.call((target, prop))
    })?,
  )?;

  handler.set(
    PredefinedAtom::GetOwnPropertyDescriptor,
    Function::new(ctx.clone(), |ctx: Ctx<'js>, target: Value<'js>, prop: Value<'js>| -> JsResult<Value<'js>> {
      let storage = get_target(&ctx, &target)?;
      if let Some(key) = get_visible_key(&ctx, &storage, &prop)? {
        if let Some(value) = storage.borrow().state.get(&ctx, &key)? {
          return Ok(build_item_descriptor(&ctx, value)?.into_value());
        }
      }
      let get_own_property_descriptor = Globals::get(&ctx)?.reflect.get_own_property_descriptor;
      get_own_property_descriptor.call((target, prop))
    })?,
  )?;

  handler.set(
    PredefinedAtom::OwnKeys,
    Function::new(ctx.clone(), |ctx: Ctx<'js>, target: Value<'js>| -> JsResult<Array<'js>> {
      let storage = get_target(&ctx, &target)?;
      let items: Vec<String> = storage.borrow().state.open_store(&ctx)?.entries.keys().cloned().collect();
      let keys = Array::new(ctx.clone())?;
      let mut len = 0;
      for item in items {
        if !storage.as_inner().contains_key(item.as_str())? {
          keys.set(len, item)?;
          len += 1;
        }
      }
      let own_keys = Globals::get(&ctx)?.reflect.own_keys;
      for key in own_keys.call::<_, Array>((target,))?.iter::<Value>() {
        keys.set(len, key?)?;
        len += 1;
      }
      Ok(keys)
    })?,
  )?;

  // WebIDL: a platform object with named properties refuses to become non-extensible, and a proxy
  // whose target did would have to stop reporting its items
  handler.set(
    PredefinedAtom::PreventExtensions,
    Function::new(ctx.clone(), |_target: Value<'js>| -> bool { false })?,
  )?;

  Ok(handler)
}

/// WebIDL counts an explicit `undefined` as passed, so only a missing argument is refused
fn require_args<'js, const N: usize>(
  ctx: &Ctx<'js>,
  method: &str,
  args: [Opt<Value<'js>>; N],
) -> JsResult<[Value<'js>; N]> {
  let present = args.iter().take_while(|arg| arg.0.is_some()).count();
  if present < N {
    let plural = if N == 1 { "" } else { "s" };
    return Err(Exception::throw_type(
      ctx,
      &format!("Storage.{method}: {N} argument{plural} required, but only {present} present"),
    ));
  }
  Ok(args.map(|arg| arg.0.expect("counted above")))
}

fn to_dom_string(value: Value<'_>) -> JsResult<String> {
  Ok(value.get::<Coerced<String>>()?.0)
}

/// WebIDL's `unsigned long` conversion: truncated, then wrapped modulo 2^32
fn to_unsigned_long(value: f64) -> usize {
  if !value.is_finite() {
    return 0;
  }
  value.trunc().rem_euclid(4_294_967_296.0) as usize
}

/// Reads and converts every pair before the store is borrowed: a getter or a `toString` is
/// plugin code that can reenter `localStorage`.
fn read_pairs<'js>(ctx: &Ctx<'js>, values: &Value<'js>) -> JsResult<Vec<(String, String)>> {
  let object = match values.as_object() {
    Some(object) if !values.is_array() && !values.is_function() => object,
    _ => return Err(Exception::throw_type(ctx, "Storage.setItems: expected an object of items")),
  };
  let mut pairs = Vec::new();
  for prop in object.props::<String, Value>() {
    let (key, value) = prop?;
    pairs.push((key, to_dom_string(value)?));
  }
  Ok(pairs)
}

fn install_members<'js>(ctx: &Ctx<'js>, proto: &Object<'js>) -> JsResult<()> {
  type Me<'js> = This<Value<'js>>;

  define_getter(proto, "length", |ctx: Ctx<'js>, this: Me<'js>| -> JsResult<usize> {
    let storage = get_this_target(&ctx, &this.0)?;
    let len = storage.borrow().state.open_store(&ctx)?.entries.len();
    Ok(len)
  })?;
  define_method(
    proto,
    "key",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, this: Me<'js>, index: Opt<Value<'js>>| -> JsResult<Value<'js>> {
      let storage = get_this_target(&ctx, &this.0)?;
      let [index] = require_args(&ctx, "key", [index])?;
      let index = to_unsigned_long(index.get::<Coerced<f64>>()?.0);
      let storage = storage.borrow();
      let mut store = storage.state.open_store(&ctx)?;
      match store.key_at(index) {
        Some(key) => Ok(rquickjs::String::from_str(ctx.clone(), key)?.into_value()),
        None => Ok(Value::new_null(ctx.clone())),
      }
    })?
    .with_name("key")?,
  )?;
  define_method(
    proto,
    "getItem",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, this: Me<'js>, key: Opt<Value<'js>>| -> JsResult<Value<'js>> {
      let storage = get_this_target(&ctx, &this.0)?;
      let [key] = require_args(&ctx, "getItem", [key])?;
      let key = to_dom_string(key)?;
      let value = storage.borrow().state.get(&ctx, &key)?;
      Ok(value.unwrap_or_else(|| Value::new_null(ctx.clone())))
    })?
    .with_name("getItem")?,
  )?;
  define_method(
    proto,
    "setItem",
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>, this: Me<'js>, key: Opt<Value<'js>>, value: Opt<Value<'js>>| -> JsResult<()> {
        let storage = get_this_target(&ctx, &this.0)?;
        let [key, value] = require_args(&ctx, "setItem", [key, value])?;
        let pair = (to_dom_string(key)?, to_dom_string(value)?);
        let done = storage.borrow().set_all(&ctx, vec![pair]);
        done
      },
    )?
    .with_name("setItem")?,
  )?;
  define_method(
    proto,
    "setItems",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, this: Me<'js>, items: Opt<Value<'js>>| -> JsResult<()> {
      let storage = get_this_target(&ctx, &this.0)?;
      let [items] = require_args(&ctx, "setItems", [items])?;
      let pairs = read_pairs(&ctx, &items)?;
      let done = storage.borrow().set_all(&ctx, pairs);
      done
    })?
    .with_name("setItems")?,
  )?;
  define_method(
    proto,
    "removeItem",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, this: Me<'js>, key: Opt<Value<'js>>| -> JsResult<()> {
      let storage = get_this_target(&ctx, &this.0)?;
      let [key] = require_args(&ctx, "removeItem", [key])?;
      let key = to_dom_string(key)?;
      let done = storage.borrow().delete(&ctx, &key);
      done
    })?
    .with_name("removeItem")?,
  )?;
  define_method(
    proto,
    "clear",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, this: Me<'js>| -> JsResult<()> {
      let storage = get_this_target(&ctx, &this.0)?;
      let done = storage.borrow().clear(&ctx);
      done
    })?
    .with_name("clear")?,
  )?;
  proto.prop(PredefinedAtom::SymbolToStringTag, Property::from("Storage").configurable())?;
  Ok(())
}

pub fn install_local_storage<'js>(ctx: &Ctx<'js>, path: PathBuf) -> JsResult<()> {
  let proto = get_class_prototype::<StorageTarget>(ctx)?;
  install_members(ctx, &proto)?;
  let ctor = Function::new(ctx.clone(), |ctx: Ctx<'js>| -> JsResult<()> {
    Err(Exception::throw_type(&ctx, "Illegal constructor"))
  })?
  .with_name("Storage")?;
  ctor.set_constructor(true);
  ctor.prop("prototype", Property::from(proto.clone()))?;
  proto.prop("constructor", Property::from(ctor.clone()).writable().configurable())?;

  let state = Rc::new(StorageState {
    path,
    store: RefCell::new(None),
    flush_queued: Cell::new(false),
  });
  let flush = {
    let state = state.clone();
    Function::new(ctx.clone(), move |ctx: Ctx<'js>| state.flush(&ctx))?
  };
  let target = Class::instance(ctx.clone(), StorageTarget { state, flush })?;
  let storage = Proxy::new(ctx.clone(), target, ProxyHandler::from_object(build_handler(ctx)?)?)?;

  let globals = ctx.globals();
  globals.prop("Storage", Property::from(ctor).writable().configurable())?;
  globals.prop("localStorage", Property::from(storage).writable().configurable())?;
  Ok(())
}

#[cfg(test)]
#[path = "local_storage_tests.rs"]
mod tests;

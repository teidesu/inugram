use std::cell::{RefCell, RefMut};
use std::collections::BTreeMap;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::ops::Bound;
use std::path::{Path, PathBuf};
use std::rc::Rc;

use rquickjs::atom::PredefinedAtom;
use rquickjs::class::{JsClass, Readable, Trace, Tracer};
use rquickjs::function::{Opt, This};
use rquickjs::object::Property;
use rquickjs::proxy::ProxyHandler;
use rquickjs::{
  Array, Atom, Class, Coerced, Constructor, Ctx, Exception, Function, JsLifetime, Object, Proxy, Result as JsResult,
  Value,
};

use crate::api::error::PluginErrorCode;
use crate::utils::shape::{define_getter, define_method, get_class_prototype};

/// `dom.d.ts`: 1 MB per plugin, keys and values both counted as utf-8 bytes
pub(crate) const QUOTA_BYTES: usize = 1 << 20;

const MAGIC: &[u8] = b"INUKV\x01";
const FRAME_HEADER: usize = 4;
const TAG_SET: u8 = b'S';
const TAG_DEL: u8 = b'D';
/// a log smaller than this is never worth rewriting, whatever share of it is dead
const COMPACT_FLOOR: u64 = 64 * 1024;

enum Change<'a> {
  Set(&'a str, &'a str),
  Del(&'a str),
}

/// An append-only log replayed into memory on open. Each frame contains a length and changes. If
/// process death interrupts a write, replay stops at the first incomplete frame; the next write
/// rewrites the recovered store. A file that does not start with the magic was not torn by this
/// format, so it is moved to [`quarantine_path`] rather than rewritten.
struct Store {
  path: PathBuf,
  entries: BTreeMap<String, String>,
  used: usize,
  log: Option<File>,
  log_bytes: u64,
  /// the last `key(i)` answer, so walking `0..length` costs a step each rather than a scan
  cursor: Option<(usize, String)>,
}

impl Store {
  fn open(path: &Path) -> io::Result<Store> {
    let mut bytes = match fs::read(path) {
      Ok(bytes) => bytes,
      Err(e) if e.kind() == io::ErrorKind::NotFound => Vec::new(),
      Err(e) => return Err(e),
    };
    if !bytes.starts_with(MAGIC) && !MAGIC.starts_with(&bytes) {
      fs::rename(path, quarantine_path(path))?;
      bytes.clear();
    }
    let mut entries = BTreeMap::new();
    let whole = bytes.len() < MAGIC.len() || replay(&bytes, &mut entries);
    let used = entries.iter().map(|(key, value)| key.len() + value.len()).sum();
    let mut store = Store {
      path: path.to_path_buf(),
      entries,
      used,
      log: None,
      log_bytes: 0,
      cursor: None,
    };
    if !whole {
      store.compact()?;
    } else if bytes.len() >= MAGIC.len() {
      store.log = Some(OpenOptions::new().append(true).open(path)?);
      store.log_bytes = bytes.len() as u64;
    }
    Ok(store)
  }

  fn set_all(&mut self, pairs: &[(String, String)]) -> Result<(), Refusal> {
    let mut used = self.used;
    for (key, value) in pairs {
      used -= self.entries.get(key).map_or(0, |old| key.len() + old.len());
      used += key.len() + value.len();
    }
    if used > QUOTA_BYTES {
      return Err(Refusal::Quota);
    }
    let changes: Vec<Change> = pairs
      .iter()
      .filter(|(key, value)| self.entries.get(key) != Some(value))
      .map(|(key, value)| Change::Set(key, value))
      .collect();
    if changes.is_empty() {
      return Ok(());
    }
    self.append(&changes).map_err(Refusal::Io)?;
    for (key, value) in pairs {
      self.entries.insert(key.clone(), value.clone());
    }
    self.cursor = None;
    self.used = used;
    self.compact_if_sparse();
    Ok(())
  }

  fn delete(&mut self, key: &str) -> io::Result<()> {
    let Some(old) = self.entries.get(key) else {
      return Ok(());
    };
    let freed = key.len() + old.len();
    self.append(&[Change::Del(key)])?;
    self.entries.remove(key);
    self.cursor = None;
    self.used -= freed;
    self.compact_if_sparse();
    Ok(())
  }

  fn clear(&mut self) -> io::Result<()> {
    self.log = None;
    match fs::remove_file(&self.path) {
      Ok(()) => {}
      Err(e) if e.kind() == io::ErrorKind::NotFound => {}
      Err(e) => return Err(e),
    }
    self.entries.clear();
    self.cursor = None;
    self.used = 0;
    self.log_bytes = 0;
    Ok(())
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

  fn append(&mut self, changes: &[Change]) -> io::Result<()> {
    let frame = encode_frame(changes);
    if self.log.is_none() {
      if self.entries.is_empty() {
        let mut file = OpenOptions::new().create(true).truncate(true).write(true).open(&self.path)?;
        file.write_all(MAGIC)?;
        self.log = Some(OpenOptions::new().append(true).open(&self.path)?);
        self.log_bytes = MAGIC.len() as u64;
      } else {
        self.compact()?;
      }
    }
    let log = self.log.as_mut().expect("opened above");
    if let Err(e) = log.write_all(&frame) {
      // a torn frame left in place would stop replay before every frame appended after it
      if log.set_len(self.log_bytes).is_err() {
        self.log = None;
      }
      return Err(e);
    }
    self.log_bytes += frame.len() as u64;
    Ok(())
  }

  fn compact_if_sparse(&mut self) {
    let live = (MAGIC.len() + FRAME_HEADER + self.used + self.entries.len() * 9) as u64;
    if self.log_bytes > COMPACT_FLOOR && self.log_bytes > live * 2 {
      let _ = self.compact();
    }
  }

  /// Writes the whole store as one frame and syncs before renaming, so a crash cannot leave the new
  /// path pointing to unwritten data.
  fn compact(&mut self) -> io::Result<()> {
    self.log = None;
    if self.entries.is_empty() {
      return self.clear();
    }
    let changes: Vec<Change> = self.entries.iter().map(|(key, value)| Change::Set(key, value)).collect();
    let frame = encode_frame(&changes);
    let staged = staged_path(&self.path);
    {
      let mut file = File::create(&staged)?;
      file.write_all(MAGIC)?;
      file.write_all(&frame)?;
      file.sync_all()?;
    }
    fs::rename(&staged, &self.path)?;
    self.log = Some(OpenOptions::new().append(true).open(&self.path)?);
    self.log_bytes = (MAGIC.len() + frame.len()) as u64;
    Ok(())
  }
}

enum Refusal {
  Quota,
  Io(io::Error),
}

/// where [`Store::compact`] writes before the rename; `PluginLocalStorage.wipe` removes it alongside the store
pub(crate) fn staged_path(path: &Path) -> PathBuf {
  suffixed(path, ".tmp")
}

/// Where a file without the magic is moved aside: a newer build's format or a damaged header, either
/// of which may still be recovered. One slot; `PluginLocalStorage.wipe` removes it alongside the store.
pub(crate) fn quarantine_path(path: &Path) -> PathBuf {
  suffixed(path, ".corrupt")
}

fn suffixed(path: &Path, suffix: &str) -> PathBuf {
  let mut name = path.as_os_str().to_owned();
  name.push(suffix);
  PathBuf::from(name)
}

fn encode_frame(changes: &[Change]) -> Vec<u8> {
  let mut payload = Vec::new();
  for change in changes {
    match change {
      Change::Set(key, value) => {
        payload.push(TAG_SET);
        push_str(&mut payload, key);
        push_str(&mut payload, value);
      }
      Change::Del(key) => {
        payload.push(TAG_DEL);
        push_str(&mut payload, key);
      }
    }
  }
  let mut frame = Vec::with_capacity(FRAME_HEADER + payload.len());
  frame.extend_from_slice(&(payload.len() as u32).to_le_bytes());
  frame.extend_from_slice(&payload);
  frame
}

fn push_str(out: &mut Vec<u8>, text: &str) {
  out.extend_from_slice(&(text.len() as u32).to_le_bytes());
  out.extend_from_slice(text.as_bytes());
}

/// false when anything past the magic is not a whole, well-formed frame; what came before it stands
fn replay(bytes: &[u8], entries: &mut BTreeMap<String, String>) -> bool {
  let Some(mut rest) = bytes.strip_prefix(MAGIC) else {
    return false;
  };
  while !rest.is_empty() {
    let Some((payload, next)) = take_sized(rest) else {
      return false;
    };
    let Some(changes) = decode_changes(payload) else {
      return false;
    };
    for change in changes {
      match change {
        Change::Set(key, value) => entries.insert(key.to_string(), value.to_string()),
        Change::Del(key) => entries.remove(key),
      };
    }
    rest = next;
  }
  true
}

fn take_sized(bytes: &[u8]) -> Option<(&[u8], &[u8])> {
  let (len, rest) = bytes.split_first_chunk::<FRAME_HEADER>()?;
  let len = u32::from_le_bytes(*len) as usize;
  (rest.len() >= len).then(|| rest.split_at(len))
}

fn take_str(bytes: &[u8]) -> Option<(&str, &[u8])> {
  let (text, rest) = take_sized(bytes)?;
  Some((std::str::from_utf8(text).ok()?, rest))
}

fn decode_changes(mut payload: &[u8]) -> Option<Vec<Change<'_>>> {
  let mut changes = Vec::new();
  while let Some((&tag, rest)) = payload.split_first() {
    let (key, rest) = take_str(rest)?;
    match tag {
      TAG_SET => {
        let (value, rest) = take_str(rest)?;
        changes.push(Change::Set(key, value));
        payload = rest;
      }
      TAG_DEL => {
        changes.push(Change::Del(key));
        payload = rest;
      }
      _ => return None,
    }
  }
  Some(changes)
}

struct StorageState {
  path: PathBuf,
  store: RefCell<Option<Store>>,
}

impl StorageState {
  /// Opens on first use to avoid startup I/O for plugins that never touch it.
  fn open_store(&self, ctx: &Ctx<'_>) -> JsResult<RefMut<'_, Store>> {
    if self.path.as_os_str().is_empty() {
      return PluginErrorCode::Internal.throw(ctx, "localStorage: this plugin has no store");
    }
    let mut slot = self.store.borrow_mut();
    if slot.is_none() {
      match Store::open(&self.path) {
        Ok(store) => *slot = Some(store),
        Err(e) => return throw_io(ctx, e),
      }
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

  fn delete(&self, ctx: &Ctx<'_>, key: &str) -> JsResult<()> {
    let done = self.open_store(ctx)?.delete(key);
    done.or_else(|e| throw_io(ctx, e))
  }
}

fn throw_io<T>(ctx: &Ctx<'_>, e: io::Error) -> JsResult<T> {
  PluginErrorCode::Internal.throw(ctx, &format!("localStorage: {e}"))
}

/// `Reflect` as it was before any plugin code ran, for what a trap hands back to ordinary semantics
struct ReflectFns<'js> {
  get: Function<'js>,
  set: Function<'js>,
  define_property: Function<'js>,
  delete_property: Function<'js>,
  get_own_property_descriptor: Function<'js>,
  own_keys: Function<'js>,
}

impl<'js> ReflectFns<'js> {
  fn take(ctx: &Ctx<'js>) -> JsResult<Self> {
    let reflect: Object = ctx.globals().get("Reflect")?;
    Ok(Self {
      get: reflect.get("get")?,
      set: reflect.get("set")?,
      define_property: reflect.get("defineProperty")?,
      delete_property: reflect.get("deleteProperty")?,
      get_own_property_descriptor: reflect.get("getOwnPropertyDescriptor")?,
      own_keys: reflect.get("ownKeys")?,
    })
  }
}

/// The target behind `localStorage`. It never holds a string-keyed property: every one is an item,
/// routed to the store by the traps, and only a symbol-keyed one lands on the target itself.
///
/// The store is reached only by native code, between conversions: a key's `toString`, a prototype
/// getter or a proxy up the chain is plugin code, and a store borrowed across one of them would
/// panic the engine when it reenters.
pub struct StorageTarget<'js> {
  state: Rc<StorageState>,
  reflect: ReflectFns<'js>,
  dom_exception: Constructor<'js>,
}

impl<'js> Trace<'js> for StorageTarget<'js> {
  fn trace<'a>(&self, tracer: Tracer<'a, 'js>) {
    let reflect = &self.reflect;
    for f in [
      &reflect.get,
      &reflect.set,
      &reflect.define_property,
      &reflect.delete_property,
      &reflect.get_own_property_descriptor,
      &reflect.own_keys,
    ] {
      f.trace(tracer);
    }
    self.dom_exception.trace(tracer);
  }
}

// SAFETY: every JavaScript-lifetime-bound field uses the struct's `'js` lifetime.
unsafe impl<'js> JsLifetime<'js> for StorageTarget<'js> {
  type Changed<'to> = StorageTarget<'to>;
}

impl<'js> JsClass<'js> for StorageTarget<'js> {
  const NAME: &'static str = "Storage";
  type Mutable = Readable;

  fn constructor(_ctx: &Ctx<'js>) -> JsResult<Option<Constructor<'js>>> {
    Ok(None)
  }
}

impl<'js> StorageTarget<'js> {
  fn set_all(&self, ctx: &Ctx<'js>, pairs: &[(String, String)]) -> JsResult<()> {
    let outcome = self.state.open_store(ctx)?.set_all(pairs);
    match outcome {
      Ok(()) => Ok(()),
      Err(Refusal::Quota) => {
        let error: Value =
          self.dom_exception.construct(("localStorage: the 1 MB quota is exceeded", "QuotaExceededError"))?;
        Err(ctx.throw(error))
      }
      Err(Refusal::Io(e)) => throw_io(ctx, e),
    }
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

fn is_proxy_of<'js>(receiver: &Value<'js>, target: &Target<'js>) -> JsResult<bool> {
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
        let get = storage.borrow().reflect.get.clone();
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
          if is_proxy_of(&receiver, &storage)? {
            let pair = (key.to_string()?, to_dom_string(value)?);
            storage.borrow().set_all(&ctx, &[pair])?;
            return Ok(true);
          }
        }
        let set = storage.borrow().reflect.set.clone();
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
          let define_property = storage.borrow().reflect.define_property.clone();
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
        storage.borrow().set_all(&ctx, &[pair])?;
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
        storage.borrow().state.delete(&ctx, &key)?;
        return Ok(true);
      }
      let delete_property = storage.borrow().reflect.delete_property.clone();
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
      let get_own_property_descriptor = storage.borrow().reflect.get_own_property_descriptor.clone();
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
      let own_keys = storage.borrow().reflect.own_keys.clone();
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
        let done = storage.borrow().set_all(&ctx, &[pair]);
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
      let done = storage.borrow().set_all(&ctx, &pairs);
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
      let done = storage.borrow().state.delete(&ctx, &key);
      done
    })?
    .with_name("removeItem")?,
  )?;
  define_method(
    proto,
    "clear",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, this: Me<'js>| -> JsResult<()> {
      let storage = get_this_target(&ctx, &this.0)?;
      let storage = storage.borrow();
      let done = storage.state.open_store(&ctx)?.clear();
      done.or_else(|e| throw_io(&ctx, e))
    })?
    .with_name("clear")?,
  )?;
  proto.prop(PredefinedAtom::SymbolToStringTag, Property::from("Storage").configurable())?;
  Ok(())
}

/// Installs `Storage` and `localStorage`. `Reflect` and `DOMException` are taken now, before any
/// plugin code runs and could replace them.
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

  let target = Class::instance(
    ctx.clone(),
    StorageTarget {
      state: Rc::new(StorageState { path, store: RefCell::new(None) }),
      reflect: ReflectFns::take(ctx)?,
      dom_exception: ctx.globals().get("DOMException")?,
    },
  )?;
  let storage = Proxy::new(ctx.clone(), target, ProxyHandler::from_object(build_handler(ctx)?)?)?;

  let globals = ctx.globals();
  globals.prop("Storage", Property::from(ctor).writable().configurable())?;
  globals.prop("localStorage", Property::from(storage).writable().configurable())?;
  Ok(())
}

#[cfg(test)]
#[path = "local_storage_tests.rs"]
mod tests;

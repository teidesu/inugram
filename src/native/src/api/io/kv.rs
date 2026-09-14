use std::cell::RefCell;
use std::collections::BTreeMap;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::rc::Rc;

use rquickjs::object::Property;
use rquickjs::{Array, Ctx, Function, IntoJs, Object, Result as JsResult, Value};

use crate::api::error::PluginErrorCode;
use crate::sandbox::grants::{GrantHost, MATCH_EXACT};

/// `common.d.ts`: 1 MB per plugin, keys and values both counted as utf-8 bytes
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

/// An append-only log of framed changes, replayed into memory on open. A frame is a length and the
/// changes it carries, so a write torn by process death loses exactly that frame: replay stops at
/// the first frame that is not whole, and the next write rewrites the file from what was read.
struct Store {
  path: PathBuf,
  entries: BTreeMap<String, String>,
  used: usize,
  log: Option<File>,
  log_bytes: u64,
}

impl Store {
  fn open(path: &Path) -> io::Result<Store> {
    let bytes = match fs::read(path) {
      Ok(bytes) => bytes,
      Err(e) if e.kind() == io::ErrorKind::NotFound => Vec::new(),
      Err(e) => return Err(e),
    };
    let mut entries = BTreeMap::new();
    let whole = bytes.is_empty() || replay(&bytes, &mut entries);
    let used = entries.iter().map(|(key, value)| key.len() + value.len()).sum();
    let mut store = Store { path: path.to_path_buf(), entries, used, log: None, log_bytes: 0 };
    if !whole {
      store.compact()?;
    } else if !bytes.is_empty() {
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
      return Err(Refusal::Quota(used));
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
    self.used = 0;
    self.log_bytes = 0;
    Ok(())
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

  /// the whole store as one frame, synced before the rename so a crash cannot leave the rename
  /// pointing at data the filesystem never wrote
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
  Quota(usize),
  Io(io::Error),
}

/// where [`Store::compact`] writes before the rename; `PluginKv.wipe` removes it alongside the store
pub(crate) fn staged_path(path: &Path) -> PathBuf {
  let mut name = path.as_os_str().to_owned();
  name.push(".tmp");
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

struct KvState {
  path: PathBuf,
  store: RefCell<Option<Store>>,
  grants: Rc<dyn GrantHost>,
}

impl KvState {
  /// opened on first use: most plugins holding the grant touch it rarely, and a boot pays for none of them
  fn with_store<'js, T>(&self, ctx: &Ctx<'js>, run: impl FnOnce(&mut Store) -> JsResult<T>) -> JsResult<T> {
    self.grants.check_grant(ctx, "kv", None, MATCH_EXACT)?;
    if self.path.as_os_str().is_empty() {
      return PluginErrorCode::Internal.throw(ctx, "kv: this plugin has no store");
    }
    let mut slot = self.store.borrow_mut();
    if slot.is_none() {
      match Store::open(&self.path) {
        Ok(store) => *slot = Some(store),
        Err(e) => return throw_io(ctx, e),
      }
    }
    run(slot.as_mut().expect("opened above"))
  }

  fn set_all<'js>(&self, ctx: &Ctx<'js>, pairs: &[(String, String)]) -> JsResult<Value<'js>> {
    self.with_store(ctx, |store| match store.set_all(pairs) {
      Ok(()) => Ok(Value::new_undefined(ctx.clone())),
      Err(Refusal::Quota(used)) => PluginErrorCode::QuotaExceeded(used as i64, QUOTA_BYTES as i64)
        .throw(ctx, "kv: 1 MB per-plugin quota exceeded"),
      Err(Refusal::Io(e)) => throw_io(ctx, e),
    })
  }
}

fn throw_io<T>(ctx: &Ctx<'_>, e: io::Error) -> JsResult<T> {
  PluginErrorCode::Internal.throw(ctx, &format!("kv: {e}"))
}

fn undefined_or_io<'js>(ctx: &Ctx<'js>, done: io::Result<()>) -> JsResult<Value<'js>> {
  match done {
    Ok(()) => Ok(Value::new_undefined(ctx.clone())),
    Err(e) => throw_io(ctx, e),
  }
}

/// the pairs of a plain object, all strings, read before the store is borrowed: a getter on it is
/// plugin code and may itself call `inu.kv`
fn string_pairs<'js>(ctx: &Ctx<'js>, values: &Value<'js>) -> JsResult<Vec<(String, String)>> {
  let object = match values.as_object() {
    Some(object) if !values.is_array() && !values.is_function() => object,
    _ => return PluginErrorCode::InvalidArgument.throw(ctx, "kv.insertAll: expected an object"),
  };
  let mut pairs = Vec::new();
  for prop in object.props::<String, Value>() {
    let (key, value) = prop?;
    let Some(value) = value.as_string() else {
      return PluginErrorCode::InvalidArgument.throw(ctx, &format!("kv.insertAll: value for '{key}' must be a string"));
    };
    pairs.push((key, value.to_string()?));
  }
  Ok(pairs)
}

pub fn install_kv<'js>(
  ctx: &Ctx<'js>,
  path: PathBuf,
  grants: Rc<dyn GrantHost>,
  globals: &crate::api::Globals<'js>,
) -> JsResult<()> {
  let state = Rc::new(KvState { path, store: RefCell::new(None), grants });
  let kv = Object::new(ctx.clone())?;

  let s = state.clone();
  kv.set(
    "get",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, key: String| {
      s.with_store(&ctx, |store| match store.entries.get(&key) {
        Some(value) => value.as_str().into_js(&ctx),
        None => Ok(Value::new_null(ctx.clone())),
      })
    })?,
  )?;
  let s = state.clone();
  kv.set(
    "has",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, key: String| {
      s.with_store(&ctx, |store| Ok(store.entries.contains_key(&key)))
    })?,
  )?;
  let s = state.clone();
  kv.set(
    "set",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, key: String, value: String| s.set_all(&ctx, &[(key, value)]))?,
  )?;
  let s = state.clone();
  kv.set(
    "del",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, key: String| {
      s.with_store(&ctx, |store| undefined_or_io(&ctx, store.delete(&key)))
    })?,
  )?;
  let s = state.clone();
  kv.set(
    "keys",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>| {
      s.with_store(&ctx, |store| {
        let array = Array::new(ctx.clone())?;
        for (index, key) in store.entries.keys().enumerate() {
          array.set(index, key.as_str())?;
        }
        Ok(array)
      })
    })?,
  )?;
  let s = state.clone();
  kv.set(
    "clear",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>| s.with_store(&ctx, |store| undefined_or_io(&ctx, store.clear())))?,
  )?;
  let s = state.clone();
  kv.set(
    "getAll",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>| {
      s.with_store(&ctx, |store| {
        let all = Object::new(ctx.clone())?;
        for (key, value) in &store.entries {
          all.prop(key.as_str(), Property::from(value.as_str()).writable().enumerable().configurable())?;
        }
        Ok(all)
      })
    })?,
  )?;
  let s = state.clone();
  kv.set(
    "insertAll",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, values: Value<'js>| -> JsResult<Value<'js>> {
      s.grants.check_grant(&ctx, "kv", None, MATCH_EXACT)?;
      let pairs = string_pairs(&ctx, &values)?;
      s.set_all(&ctx, &pairs)
    })?,
  )?;
  let s = state;
  kv.set(
    "usage",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>| s.with_store(&ctx, |store| Ok(store.used as f64)))?,
  )?;
  globals.inu.set("kv", kv)?;
  Ok(())
}

#[cfg(test)]
#[path = "kv_tests.rs"]
mod kv_tests;

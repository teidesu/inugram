use super::*;
use base64::Engine;
use rquickjs::{Context, Runtime};
use std::cell::RefCell;
use std::collections::{HashMap, HashSet};

/// in-memory stand-in for `TlHandles.kt`'s handle table, driven entirely through wire strings.
///
/// [`FakeValue::Nested`] mirrors real `TlHandles.kt`'s `encodeFieldValue`: reading a
/// TLObject/vector-typed field mints a **fresh handle id every access**, all pointing at the
/// same underlying object, never a cached id.
#[derive(Clone)]
enum FakeValue {
  Wire(String),
  Nested(Rc<RefCell<FakeEntry>>),
}

enum FakeEntry {
  Object { type_name: String, fields: HashMap<String, FakeValue> },
  Vector(Vec<FakeValue>),
}

struct FakeHandle {
  entry: Rc<RefCell<FakeEntry>>,
  read_only: bool,
}

#[derive(Default)]
struct FakeCounts {
  gets: RefCell<Vec<String>>,
  has: RefCell<Vec<String>>,
  sets: RefCell<Vec<String>>,
  byte_sets: RefCell<Vec<(String, Vec<u8>)>>,
  own_keys: Cell<u32>,
}

#[derive(Default)]
struct FakeTlHost {
  table: RefCell<HashMap<i64, FakeHandle>>,
  next_id: Cell<i64>,
  counts: FakeCounts,
  /// keys reading as `N` while reporting absent - a cleared flag bit, Kotlin-side
  cleared_bits: RefCell<HashSet<String>>,
  /// `(trigger, revealed, wire)`: setting `trigger` also makes `revealed` appear, mirroring
  /// `syncFlagBit` flipping a bit shared with a sibling field
  reveal_on_set: RefCell<Option<(String, String, String)>>,
  /// mutate, then report failure - what a throwing `syncFlagBit` looks like from here
  set_fails: Cell<bool>,
}

impl FakeTlHost {
  fn mint(&self, entry: FakeEntry) -> i64 {
    self.mint_shared(Rc::new(RefCell::new(entry)), false)
  }

  fn mint_read_only(&self, entry: FakeEntry) -> i64 {
    self.mint_shared(Rc::new(RefCell::new(entry)), true)
  }

  /// mints a brand-new handle id naming an *existing* shared entry - the fixture's equivalent
  /// of real Kotlin minting a fresh handle for the same underlying TLObject/ArrayList target.
  fn mint_shared(&self, entry: Rc<RefCell<FakeEntry>>, read_only: bool) -> i64 {
    let id = self.next_id.get() + 1;
    self.next_id.set(id);
    self.table.borrow_mut().insert(id, FakeHandle { entry, read_only });
    id
  }

  fn is_alive(&self, handle: i64) -> bool {
    self.table.borrow().contains_key(&handle)
  }

  fn live_count(&self) -> usize {
    self.table.borrow().len()
  }

  fn reset_counts(&self) {
    self.counts.gets.borrow_mut().clear();
    self.counts.has.borrow_mut().clear();
    self.counts.sets.borrow_mut().clear();
    self.counts.own_keys.set(0);
  }

  fn gets_of(&self, key: &str) -> usize {
    self.counts.gets.borrow().iter().filter(|k| k.as_str() == key).count()
  }

  fn get_count(&self) -> usize {
    self.counts.gets.borrow().len()
  }

  fn has_of(&self, key: &str) -> usize {
    self.counts.has.borrow().iter().filter(|k| k.as_str() == key).count()
  }

  fn has_count(&self) -> usize {
    self.counts.has.borrow().len()
  }

  fn set_count(&self) -> usize {
    self.counts.sets.borrow().len()
  }

  fn own_keys_count(&self) -> u32 {
    self.counts.own_keys.get()
  }

  fn lookup(&self, handle: i64) -> Option<(Rc<RefCell<FakeEntry>>, bool)> {
    self.table.borrow().get(&handle).map(|h| (h.entry.clone(), h.read_only))
  }
}

fn object_entry(type_name: &str, fields: &[(&str, &str)]) -> FakeEntry {
  FakeEntry::Object {
    type_name: type_name.to_string(),
    fields: fields.iter().map(|(k, v)| (k.to_string(), FakeValue::Wire(v.to_string()))).collect(),
  }
}

fn nested_entry(type_name: &str, key: &str, child: Rc<RefCell<FakeEntry>>) -> FakeEntry {
  let mut fields = HashMap::new();
  fields.insert(key.to_string(), FakeValue::Nested(child));
  FakeEntry::Object { type_name: type_name.to_string(), fields }
}

fn add_nested(entry: &Rc<RefCell<FakeEntry>>, key: &str, child: Rc<RefCell<FakeEntry>>) {
  match &mut *entry.borrow_mut() {
    FakeEntry::Object { fields, .. } => {
      fields.insert(key.to_string(), FakeValue::Nested(child));
    }
    _ => panic!("expected object entry"),
  }
}

/// renders a scalar wire tag (`N`/`S`/`I`/`D`/`B`/`Y`/`J`) as a JSON literal - enough for
/// [`FakeTlHost`] to build `tl_copy`'s plain-value payload without a full wire->JSON codec.
fn wire_to_test_json(wire: &str) -> String {
  let mut chars = wire.chars();
  let tag = chars.next().unwrap_or('N');
  let payload = chars.as_str();
  match tag {
    'N' => "null".to_string(),
    'S' => format!("\"{payload}\""),
    'I' | 'D' | 'J' => payload.to_string(),
    'B' => if payload == "1" { "true" } else { "false" }.to_string(),
    'Y' => format!("{{\"{BYTES_MARKER_KEY}\":\"{payload}\"}}"),
    _ => "null".to_string(),
  }
}

fn fake_value_to_json(value: &FakeValue) -> String {
  match value {
    FakeValue::Wire(w) => wire_to_test_json(w),
    FakeValue::Nested(entry) => fake_entry_to_json(&entry.borrow()),
  }
}

fn fake_entry_to_json(entry: &FakeEntry) -> String {
  match entry {
    FakeEntry::Object { type_name, fields } => {
      let mut keys: Vec<&String> = fields.keys().collect();
      keys.sort();
      let body = keys
        .iter()
        .map(|k| format!("\"{k}\":{}", fake_value_to_json(&fields[*k])))
        .collect::<Vec<_>>()
        .join(",");
      format!("{{\"_\":\"{type_name}\",{body}}}")
    }
    FakeEntry::Vector(items) => {
      format!("[{}]", items.iter().map(fake_value_to_json).collect::<Vec<_>>().join(","))
    }
  }
}

/// mints a fresh handle for `value` if it's a [`FakeValue::Nested`] (matching real Kotlin's
/// per-access minting, read-only-ness inherited from the parent entry), or returns the
/// already-final wire as-is.
fn fake_value_to_wire(host: &FakeTlHost, value: &FakeValue, read_only: bool) -> String {
  match value {
    FakeValue::Wire(w) => w.clone(),
    FakeValue::Nested(entry) => {
      let is_vector = matches!(&*entry.borrow(), FakeEntry::Vector(_));
      let id = host.mint_shared(entry.clone(), read_only);
      encode_handle(is_vector, read_only, id)
    }
  }
}

/// mirrors `PluginWire.encodeExpired()` Kotlin-side
fn expired_wire() -> String {
  format!("Phandle-expired\n\n\n\n{HANDLE_EXPIRED_MESSAGE}")
}

/// mirrors `PluginWire.encodePluginError("forbidden", ...)` Kotlin-side
fn forbidden_wire() -> String {
  format!("Pforbidden\n\n\n\n{READ_ONLY_MESSAGE}")
}

impl TlHost for FakeTlHost {
  fn tl_get(&self, handle: i64, key: &str) -> String {
    self.counts.gets.borrow_mut().push(key.to_string());
    let Some((entry_rc, read_only)) = self.lookup(handle) else {
      return expired_wire();
    };
    let result = match &*entry_rc.borrow() {
      FakeEntry::Object { type_name, fields } => {
        if key == "_" {
          format!("S{type_name}")
        } else if self.cleared_bits.borrow().contains(key) {
          "N".to_string()
        } else {
          match fields.get(key) {
            None => encode_error(&format!("no such field '{key}'")),
            Some(value) => fake_value_to_wire(self, value, read_only),
          }
        }
      }
      FakeEntry::Vector(items) => {
        if key == "length" {
          format!("I{}", items.len())
        } else {
          match key.parse::<usize>().ok().and_then(|i| items.get(i)) {
            None => encode_error("index out of range"),
            Some(value) => fake_value_to_wire(self, value, read_only),
          }
        }
      }
    };
    result
  }

  /// the host stores what it was handed; recording it separately is how a test says the bytes
  /// never went through `tl_set`'s wire
  fn tl_set_bytes(&self, handle: i64, key: &str, value: &[u8]) -> Option<String> {
    self.counts.byte_sets.borrow_mut().push((key.to_string(), value.to_vec()));
    self.tl_set(
      handle,
      key,
      &format!("Y{}", base64::Engine::encode(&base64::engine::general_purpose::STANDARD, value)),
    )
  }

  fn tl_set(&self, handle: i64, key: &str, value_wire: &str) -> Option<String> {
    self.counts.sets.borrow_mut().push(key.to_string());
    let (entry_rc, read_only) = self.lookup(handle)?;
    if read_only {
      return Some(forbidden_wire());
    }
    let result = match &mut *entry_rc.borrow_mut() {
      FakeEntry::Object { fields, .. } => {
        fields.insert(key.to_string(), FakeValue::Wire(value_wire.to_string()));
        if let Some((trigger, revealed, wire)) = self.reveal_on_set.borrow().as_ref() {
          if trigger == key {
            fields.insert(revealed.clone(), FakeValue::Wire(wire.clone()));
          }
        }
        if self.set_fails.get() {
          Some("simulated set failure".to_string())
        } else {
          None
        }
      }
      FakeEntry::Vector(items) => {
        if key == "length" {
          // mirrors TlHandles.setVectorProp: outbound scalar sets are always `J`-tagged
          // JSON (never a raw `I` tag), so accept both here.
          let n: usize =
            value_wire.strip_prefix('I').or_else(|| value_wire.strip_prefix('J')).and_then(|s| s.parse().ok())?;
          items.truncate(n);
          None
        } else {
          let idx: usize = key.parse().ok()?;
          if idx == items.len() {
            items.push(FakeValue::Wire(value_wire.to_string()));
            None
          } else if idx < items.len() {
            items[idx] = FakeValue::Wire(value_wire.to_string());
            None
          } else {
            Some("index out of range".to_string())
          }
        }
      }
    };
    result
  }

  fn tl_has(&self, handle: i64, key: &str) -> i32 {
    self.counts.has.borrow_mut().push(key.to_string());
    let Some((entry_rc, _)) = self.lookup(handle) else {
      return -1;
    };
    let present = match &*entry_rc.borrow() {
      FakeEntry::Object { fields, .. } => {
        key == "_" || (!self.cleared_bits.borrow().contains(key) && fields.contains_key(key))
      }
      FakeEntry::Vector(items) => key == "length" || key.parse::<usize>().is_ok_and(|i| i < items.len()),
    };
    if present {
      1
    } else {
      0
    }
  }

  fn tl_own_keys(&self, handle: i64) -> Option<String> {
    self.counts.own_keys.set(self.counts.own_keys.get() + 1);
    let (entry_rc, _) = self.lookup(handle)?;
    let result = match &*entry_rc.borrow() {
      FakeEntry::Object { fields, .. } => {
        let cleared = self.cleared_bits.borrow();
        let mut keys: Vec<&String> = fields.keys().filter(|k| !cleared.contains(*k)).collect();
        keys.sort();
        let mut out = vec!["_"];
        out.extend(keys.iter().map(|k| k.as_str()));
        Some(out.join(","))
      }
      FakeEntry::Vector(_) => None,
    };
    result
  }

  fn tl_copy(&self, handle: i64) -> Option<String> {
    let (entry_rc, _) = self.lookup(handle)?;
    let json = fake_entry_to_json(&entry_rc.borrow());
    Some(json)
  }

  fn tl_release(&self, handle: i64) {
    self.table.borrow_mut().remove(&handle);
  }
}

/// serves every field through the ordinal path with one canned reply, and refuses to be read by name
struct OrdinalHost {
  reply: Vec<u8>,
}

impl TlHost for OrdinalHost {
  fn tl_get(&self, _handle: i64, key: &str) -> String {
    panic!("'{key}' was read by name instead of by ordinal")
  }

  fn tl_resolve_field(&self, _class_id: i32, _key: &str) -> i32 {
    0
  }

  fn tl_read_field(&self, _handle: i64, _class_id: i32, _ordinal: i32) -> i32 {
    self.reply.len() as i32
  }

  fn read_buffer(&self) -> &[u8] {
    &self.reply
  }

  fn tl_set(&self, _handle: i64, _key: &str, _value_wire: &str) -> Option<String> {
    None
  }

  fn tl_set_bytes(&self, _handle: i64, _key: &str, _value: &[u8]) -> Option<String> {
    None
  }

  fn tl_has(&self, _handle: i64, _key: &str) -> i32 {
    1
  }

  fn tl_own_keys(&self, _handle: i64) -> Option<String> {
    None
  }

  fn tl_copy(&self, _handle: i64) -> Option<String> {
    None
  }

  fn tl_release(&self, _handle: i64) {}
}

fn read_ordinal_reply(tag: u8, value: i64) -> String {
  let mut reply = vec![tag];
  reply.extend_from_slice(&value.to_le_bytes());
  let views = TlViews::new(Rc::new(OrdinalHost { reply }));
  let (_rt, ctx) = make_ctx();
  ctx.with(|ctx| {
    let view = views.wire_to_js_value(&ctx, &format!("{}.0", encode_handle(false, true, 1)), ViewLife::Dispatch).unwrap();
    ctx.globals().set("obj", view).unwrap();
    ctx.eval("`${typeof obj.field}:${obj.field}`").unwrap()
  })
}

#[test]
fn an_int53_long_reads_as_a_number_and_any_other_long_as_a_string() {
  assert_eq!(read_ordinal_reply(tag::INT53, 5_000_000_001), "number:5000000001");
  assert_eq!(read_ordinal_reply(tag::LONG, i64::MIN), "string:-9223372036854775808");
}

fn make_ctx() -> (Runtime, Context) {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  ctx.with(|ctx| crate::api::error::install_plugin_error(&ctx).unwrap());
  (rt, ctx)
}

fn views_of(host: &Rc<FakeTlHost>) -> Rc<TlViews> {
  TlViews::new(host.clone())
}

/// installs a view over `id` as `globalThis.<name>`
fn bind<'js>(
  ctx: &Ctx<'js>,
  views: &Rc<TlViews>,
  name: &str,
  is_vector: bool,
  read_only: bool,
  life: ViewLife,
  id: i64,
) -> Value<'js> {
  let value = views.wire_to_js_value(ctx, &encode_handle(is_vector, read_only, id), life).unwrap();
  ctx.globals().set(name, value.clone()).unwrap();
  value
}

fn bind_object<'js>(ctx: &Ctx<'js>, views: &Rc<TlViews>, name: &str, life: ViewLife, id: i64) -> Value<'js> {
  bind(ctx, views, name, false, false, life, id)
}

#[test]
fn object_reads_type_name_fields_has_and_own_keys() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("myNamespace.myClass", &[("x", "I1"), ("name", "Shello")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Dispatch, id);

    let type_name: String = ctx.eval("obj._").unwrap();
    assert_eq!(type_name, "myNamespace.myClass");
    let x: i64 = ctx.eval("obj.x").unwrap();
    assert_eq!(x, 1);
    let name: String = ctx.eval("obj.name").unwrap();
    assert_eq!(name, "hello");
    let has_x: bool = ctx.eval("'x' in obj").unwrap();
    assert!(has_x);
    let has_z: bool = ctx.eval("'z' in obj").unwrap();
    assert!(!has_z);
    let mut keys: Vec<String> = ctx.eval("Object.keys(obj)").unwrap();
    keys.sort();
    assert_eq!(keys, vec!["_".to_string(), "name".to_string(), "x".to_string()]);
  });
}

/// the write half of `TAG_BYTES`: a `Uint8Array` reaches the host as bytes, never as base64 in a
/// wire string, and everything that is not one still takes the wire
#[test]
fn assigning_a_uint8array_crosses_as_bytes() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Dispatch, id);
    ctx.eval::<(), _>("obj.bytes = new Uint8Array([1, 2, 250]); obj.name = 'not bytes'").unwrap();
  });

  // exactly one write took the byte path, and it is the one holding bytes
  assert_eq!(host.counts.byte_sets.borrow().clone(), vec![("bytes".to_string(), vec![1u8, 2, 250])]);
}

#[test]
fn object_set_field_mutates_the_real_handle_target() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Dispatch, id);
    ctx.eval::<(), _>("obj.x = 42").unwrap();
  });

  // outbound scalar sets are always `J`-tagged JSON, never a raw `I` tag - see js_value_to_wire
  let (entry_rc, _) = host.lookup(id).unwrap();
  match &*entry_rc.borrow() {
    FakeEntry::Object { fields, .. } => {
      assert!(matches!(fields.get("x"), Some(FakeValue::Wire(w)) if w == "J42"))
    }
    _ => panic!("expected object entry"),
  };
}

#[test]
fn vector_length_indexing_push_and_iteration() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(FakeEntry::Vector(vec![FakeValue::Wire("I10".to_string()), FakeValue::Wire("I20".to_string())]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind(&ctx, &views, "vec", true, false, ViewLife::Dispatch, id);

    let len: i64 = ctx.eval("vec.length").unwrap();
    assert_eq!(len, 2);
    let first: i64 = ctx.eval("vec[0]").unwrap();
    assert_eq!(first, 10);

    ctx.eval::<(), _>("vec[2] = 30").unwrap();
    let len2: i64 = ctx.eval("vec.length").unwrap();
    assert_eq!(len2, 3);

    let summed: i64 = ctx.eval("[...vec].reduce((a, b) => a + b, 0)").unwrap();
    assert_eq!(summed, 60);

    ctx.eval::<(), _>("vec.length = 1").unwrap();
    let len3: i64 = ctx.eval("vec.length").unwrap();
    assert_eq!(len3, 1);
  });
}

fn base64_encode(bytes: &[u8]) -> String {
  base64::engine::general_purpose::STANDARD.encode(bytes)
}

#[test]
fn bytes_field_roundtrips_through_uint8array_and_base64() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("x", &[("data", &format!("Y{}", base64_encode(&[1, 2, 3, 255])))]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Dispatch, id);

    let is_typed: bool = ctx.eval("obj.data instanceof Uint8Array").unwrap();
    assert!(is_typed);
    let sum: i64 = ctx.eval("obj.data[0] + obj.data[1] + obj.data[2] + obj.data[3]").unwrap();
    assert_eq!(sum, 1 + 2 + 3 + 255);

    ctx.eval::<(), _>("obj.data = new Uint8Array([9, 8, 7])").unwrap();
  });

  let (entry_rc, _) = host.lookup(id).unwrap();
  match &*entry_rc.borrow() {
    FakeEntry::Object { fields, .. } => {
      assert!(
        matches!(fields.get("data"), Some(FakeValue::Wire(w)) if w == &format!("Y{}", base64_encode(&[9, 8, 7])))
      );
    }
    _ => panic!("expected object entry"),
  };
}

/// mirrors real `TlHandles.kt`: reading the same TLObject-typed field twice mints two
/// *independent* handle ids naming the same underlying value - so the first, transient proxy
/// becoming unreachable and GC'd (freeing its own id) must not affect the second read.
#[test]
fn nested_object_field_yields_its_own_live_proxy() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let child = Rc::new(RefCell::new(object_entry("child.type", &[("y", "I7")])));
  let parent_id = host.mint(nested_entry("parent.type", "child", child));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Dispatch, parent_id);

    let child_type: String = ctx.eval("obj.child._").unwrap();
    assert_eq!(child_type, "child.type");
    let y: i64 = ctx.eval("obj.child.y").unwrap();
    assert_eq!(y, 7);
  });

  // two reads of "child" minted two distinct handle ids (both != parent_id); a fresh id
  // was allocated for each access, matching real Kotlin's no-caching design
  assert!(host.next_id.get() >= parent_id + 2);
}

#[test]
fn dropping_last_reference_and_running_gc_releases_the_handle() {
  let (rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Dispatch, id);
    ctx.eval::<(), _>("obj = undefined").unwrap();
  });

  // QuickJS frees non-cyclic garbage via refcounting as soon as the last reference drops
  // (here, immediately on `obj = undefined`); `run_gc()` only sweeps reference cycles, so
  // it's a no-op backstop here - the handle is already gone by the time it runs
  rt.run_gc();
  assert!(!host.is_alive(id), "dropping the proxy's last reference must have released its handle");
}

#[test]
fn double_release_is_a_no_op() {
  let host = FakeTlHost::default();
  let id = host.mint(object_entry("foo", &[]));

  host.tl_release(id);
  assert!(!host.is_alive(id));
  // releasing again (e.g. GC finalizer firing after `releaseScope` already
  // freed it in bulk) must be a harmless no-op, not a panic
  host.tl_release(id);
  assert!(!host.is_alive(id));
}

#[test]
fn expired_chain_handle_does_not_crash_when_its_proxy_is_later_gcd() {
  let (rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Dispatch, id);
  });

  // simulates `TlHandles.releaseScope` hard-invalidating the handle out from under a proxy
  // that's still JS-reachable at the time
  host.tl_release(id);
  assert!(!host.is_alive(id));

  ctx.with(|ctx| {
    assert!(ctx.eval::<Value, _>("obj.x").is_err(), "already-released handle must read as expired, not crash");
    ctx.eval::<(), _>("obj = undefined").unwrap();
  });

  // the proxy's HandleBox finalizer now fires `tl_release(id)` a second time once GC
  // collects it - must not panic even though the entry is long gone
  rt.run_gc();
  assert!(!host.is_alive(id));
}

#[test]
fn passing_a_live_proxy_back_reuses_its_handle_wire_without_copying() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("x", &[]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    let proxy = bind_object(&ctx, &views, "obj", ViewLife::Dispatch, id);
    let wire_out = js_value_to_wire(&ctx, proxy).unwrap();
    assert_eq!(wire_out, format!("HOW{id}"));
  });
}

#[test]
fn plain_object_falls_back_to_json_wire() {
  let (_rt, ctx) = make_ctx();
  ctx.with(|ctx| {
    let value: Value = ctx.eval("({a: 1})").unwrap();
    let wire = js_value_to_wire(&ctx, value).unwrap();
    assert_eq!(wire, "J{\"a\":1}");
  });
}

#[test]
fn expired_handle_get_throws_a_handle_expired_plugin_error() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Dispatch, 999);
    assert!(ctx.eval::<Value, _>("obj.x").is_err());
    assert!(ctx.eval::<Value, _>("Object.keys(obj)").is_err());

    let caught: String = ctx
      .eval(
        r#"(() => {
                    const seen = [];
                    for (const read of [() => obj.x, () => Object.keys(obj), () => obj.toJSON()]) {
                        try { read(); seen.push('no-throw'); }
                        catch (e) { seen.push([e instanceof inu.PluginError, e.code].join('|')); }
                    }
                    return JSON.stringify(seen);
                })()"#,
      )
      .unwrap();
    assert_eq!(caught, r#"["true|handle-expired","true|handle-expired","true|handle-expired"]"#);

    let message: String = ctx.eval("(() => { try { obj.x } catch (e) { return e.message } })()").unwrap();
    assert_eq!(message, HANDLE_EXPIRED_MESSAGE);
  });
}

#[test]
fn expired_handle_presence_probes_throw_instead_of_answering_absent() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Dispatch, 999);

    let caught: String = ctx
      .eval(
        r#"(() => {
                    const probes = [
                        () => 'peer' in obj,
                        () => Reflect.has(obj, 'peer'),
                        () => Object.getOwnPropertyDescriptor(obj, 'peer'),
                        () => Object.prototype.hasOwnProperty.call(obj, 'peer'),
                    ];
                    return JSON.stringify(probes.map((probe) => {
                        try { return 'no-throw:' + String(probe()) }
                        catch (e) { return [e instanceof inu.PluginError, e.code, e.message].join('|') }
                    }));
                })()"#,
      )
      .unwrap();
    let refusal = format!("true|handle-expired|{HANDLE_EXPIRED_MESSAGE}");
    assert_eq!(caught, format!(r#"["{refusal}","{refusal}","{refusal}","{refusal}"]"#));
  });
}

#[test]
fn tojson_detaches_object_into_a_plain_mutation_safe_value() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("x", "I5")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Dispatch, id);

    let json: String = ctx.eval("(() => { const c = obj.toJSON(); c.x = 999; return JSON.stringify(c); })()").unwrap();
    assert_eq!(json, r#"{"_":"foo","x":999}"#);
  });

  let (entry_rc, _) = host.lookup(id).unwrap();
  match &*entry_rc.borrow() {
    FakeEntry::Object { fields, .. } => {
      assert!(matches!(fields.get("x"), Some(FakeValue::Wire(w)) if w == "I5"))
    }
    _ => panic!("expected object entry"),
  };
}

#[test]
fn snapshot_bytes_revive_as_uint8array_and_round_trip_through_stringify() {
  let (_rt, ctx) = make_ctx();
  ctx.with(|ctx| {
    // AQID = [1, 2, 3]
    let value = json_parse_tl(&ctx, r#"{"_":"foo","data":{"$inuBytes":"AQID"}}"#).unwrap();
    ctx.globals().set("snap", value).unwrap();

    let is_u8: bool = ctx.eval("snap.data instanceof Uint8Array").unwrap();
    assert!(is_u8);
    let bytes: Vec<u8> = ctx.eval::<Vec<u8>, _>("Array.from(snap.data)").unwrap();
    assert_eq!(bytes, vec![1, 2, 3]);

    // plugin-side stringify re-wraps via the array's own toJSON
    let json: String = ctx.eval("JSON.stringify(snap)").unwrap();
    assert_eq!(json, r#"{"_":"foo","data":{"$inuBytes":"AQID"}}"#);

    // a plugin-created Uint8Array (no toJSON) is wrapped by the outbound replacer
    let literal: Value = ctx.eval("({_: 'bar', data: new Uint8Array([9, 8])})").unwrap();
    let wire = js_value_to_wire(&ctx, literal).unwrap();
    assert_eq!(wire, r#"J{"_":"bar","data":{"$inuBytes":"CQg="}}"#);
  });
}

#[test]
fn the_tl_json_marshalling_ignores_a_hijacked_json_global() {
  let (_rt, ctx) = make_ctx();
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__seen = [];
            globalThis.JSON = {
                parse: (s) => { globalThis.__seen.push(String(s)); return {stolen: true} },
                stringify: () => { globalThis.__seen.push('stringify'); return '"hijacked"' },
            };
            "#,
      )
      .unwrap();

    let value = json_parse_tl(&ctx, r#"{"_":"foo","data":{"$inuBytes":"AQID"}}"#).unwrap();
    ctx.globals().set("snap", value).unwrap();
    let shape: String = ctx.eval("[snap._, snap.stolen, Array.from(snap.data).join('-')].join(',')").unwrap();
    assert_eq!(shape, "foo,,1-2-3", "the wire is parsed natively, so a hijacked parse neither sees nor shapes it");

    let literal: Value = ctx.eval("({_: 'bar', data: new Uint8Array([9, 8])})").unwrap();
    assert_eq!(js_value_to_wire(&ctx, literal).unwrap(), r#"J{"_":"bar","data":{"$inuBytes":"CQg="}}"#);

    let seen: Vec<String> = ctx.eval("globalThis.__seen").unwrap();
    assert!(seen.is_empty(), "nothing crossed through the plugin-owned JSON: {seen:?}");
  });
}

#[test]
fn a_polluted_object_prototype_does_not_make_every_parsed_object_bytes() {
  let (_rt, ctx) = make_ctx();
  ctx.with(|ctx| {
    ctx.eval::<(), _>(r#"Object.prototype['$inuBytes'] = 'AQID';"#).unwrap();
    let value = json_parse_tl(&ctx, r#"{"_":"foo","peer":{"_":"peerUser"}}"#).unwrap();
    ctx.globals().set("snap", value).unwrap();
    let shape: String = ctx.eval("[snap.peer._, snap.peer instanceof Uint8Array].join(',')").unwrap();
    assert_eq!(shape, "peerUser,false", "the marker is read as an own key");
  });
}

#[test]
fn json_stringify_on_a_proxy_uses_tojson_snapshot() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("x", "I5")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Dispatch, id);

    // JSON.stringify must not throw "no such field 'toJSON'"; it serializes the snapshot
    let json: String = ctx.eval("JSON.stringify(obj)").unwrap();
    assert_eq!(json, r#"{"_":"foo","x":5}"#);
    // toJSON is directly callable and detached from the live object
    let typ: String = ctx.eval("typeof obj.toJSON").unwrap();
    assert_eq!(typ, "function");
  });
}

#[test]
fn json_stringify_of_a_vector_view_produces_the_array() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let items = vec![
    FakeValue::Wire("I10".to_string()),
    FakeValue::Nested(Rc::new(RefCell::new(object_entry("item", &[("a", "Shi")])))),
  ];
  let id = host.mint(FakeEntry::Vector(items));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind(&ctx, &views, "vec", true, false, ViewLife::Plugin, id);

    let json: String = ctx.eval("JSON.stringify(vec)").unwrap();
    assert_eq!(json, r#"[10,{"_":"item","a":"hi"}]"#);
    let nested: String = ctx.eval("JSON.stringify({v: vec})").unwrap();
    assert_eq!(nested, r#"{"v":[10,{"_":"item","a":"hi"}]}"#);

    assert_eq!(ctx.eval::<String, _>("typeof vec.toJSON").unwrap(), "function");
    assert!(ctx.eval::<bool, _>("'toJSON' in vec").unwrap());
    assert!(ctx.eval::<bool, _>("Array.isArray(vec.toJSON())").unwrap());
    assert!(ctx.eval::<bool, _>("Object.getOwnPropertyDescriptor(vec, 'toJSON') === undefined").unwrap());
  });
  assert_eq!(host.gets_of(TO_JSON_KEY), 0, "'toJSON' must never fall through to a vector index read");
}

#[test]
fn read_only_view_refuses_writes_with_a_forbidden_plugin_error() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint_read_only(object_entry("foo", &[("x", "I1")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind(&ctx, &views, "obj", false, true, ViewLife::Plugin, id);

    let caught: String = ctx
      .eval(
        r#"(() => {
                    const seen = [];
                    for (const write of [() => { obj.x = 2 }, () => { delete obj.x }]) {
                        try { write(); seen.push('no-throw'); }
                        catch (e) { seen.push([e instanceof inu.PluginError, e.code, e.message].join('|')); }
                    }
                    return JSON.stringify(seen);
                })()"#,
      )
      .unwrap();
    assert_eq!(caught, format!(r#"["true|forbidden|{READ_ONLY_MESSAGE}","true|forbidden|{READ_ONLY_MESSAGE}"]"#));
  });
  assert_eq!(host.set_count(), 0, "a refused write must never reach the host");
}

#[test]
fn writable_view_still_mutates_while_its_read_only_alias_does_not() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let entry = Rc::new(RefCell::new(object_entry("foo", &[("x", "I1")])));
  let rw = host.mint_shared(entry.clone(), false);
  let ro = host.mint_shared(entry, true);
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind(&ctx, &views, "rw", false, false, ViewLife::Plugin, rw);
    bind(&ctx, &views, "ro", false, true, ViewLife::Plugin, ro);

    ctx.eval::<(), _>("rw.x = 9").unwrap();
    let seen: i64 = ctx.eval("ro.x").unwrap();
    assert_eq!(seen, 9);
    assert!(ctx.eval::<Value, _>("ro.x = 10").is_err());
    let still: i64 = ctx.eval("ro.x").unwrap();
    assert_eq!(still, 9);
  });
}

#[test]
fn define_property_on_a_read_only_view_is_forbidden() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint_read_only(object_entry("foo", &[("x", "I1")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind(&ctx, &views, "obj", false, true, ViewLife::Plugin, id);

    let caught: String = ctx
      .eval(
        r#"(() => {
                    const defines = [
                        () => Object.defineProperty(obj, 'x', { value: 5, configurable: true }),
                        () => Object.defineProperty(obj, 'z', { value: 5 }),
                        () => Object.defineProperty(obj, Symbol.for('inu.tl.cache'), { value: {} }),
                        () => Reflect.defineProperty(obj, 'x', { value: 5 }),
                    ];
                    return JSON.stringify(defines.map((define) => {
                        try { define(); return 'no-throw' }
                        catch (e) { return [e instanceof inu.PluginError, e.code, e.message].join('|') }
                    }));
                })()"#,
      )
      .unwrap();
    let refusal = format!("true|forbidden|{READ_ONLY_MESSAGE}");
    assert_eq!(caught, format!(r#"["{refusal}","{refusal}","{refusal}","{refusal}"]"#));
    assert_eq!(ctx.eval::<i64, _>("obj.x").unwrap(), 1);
  });
  assert_eq!(host.set_count(), 0, "a refused define must never reach the host");
}

/// a symbol's *description* is what `property_key_string` answers, so an unguarded delete trap
/// would let a private `Symbol.for('message')` key clear the app's real `message`
#[test]
fn a_symbol_keyed_delete_cannot_reach_a_field_of_the_same_name() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("message", "Shi")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    host.reset_counts();
    let seen: String = ctx
      .eval(
        r#"(() => {
                    let caught = 'no-throw';
                    try { delete obj[Symbol.for('message')] } catch (e) { caught = e.message }
                    return JSON.stringify([caught, obj.message]);
                })()"#,
      )
      .unwrap();
    assert_eq!(seen, r#"["tl proxy: cannot delete a symbol-keyed property","hi"]"#);
  });
  assert_eq!(host.set_count(), 0, "a refused delete must never reach the host");
}

#[test]
fn define_property_cannot_forge_the_field_cache() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("x", "I1")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    assert_eq!(ctx.eval::<i64, _>("obj.x").unwrap(), 1);

    host.reset_counts();
    let seen: String = ctx
      .eval(
        r#"(() => {
                    const forged = { perm: {}, val: { x: 666 }, has: {} };
                    let caught = 'no-throw';
                    try {
                        Object.defineProperty(obj, Symbol.for('inu.tl.cache'), { value: forged, configurable: true });
                    } catch (e) { caught = e.message }
                    return JSON.stringify([caught, obj.x]);
                })()"#,
      )
      .unwrap();
    assert_eq!(seen, r#"["tl proxy: cannot define a symbol-keyed property",1]"#);
  });
  assert_eq!(host.gets_of("x"), 0, "the real cache must have answered, untouched");
  assert_eq!(host.set_count(), 0);
}

#[test]
fn define_property_with_a_value_writes_through_like_an_assignment() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("x", "I1")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    assert_eq!(ctx.eval::<i64, _>("obj.x").unwrap(), 1);

    ctx.eval::<(), _>("Object.defineProperty(obj, 'x', { value: 42 })").unwrap();
    ctx
      .eval::<(), _>(
        "Object.defineProperty(obj, 'name', { value: 'hi', writable: true, enumerable: true, configurable: true })",
      )
      .unwrap();
    assert!(ctx.eval::<bool, _>("Reflect.defineProperty(obj, 'flag', { value: true })").unwrap());

    assert_eq!(ctx.eval::<i64, _>("obj.x").unwrap(), 42, "the define must have bumped the epoch");
  });

  assert_eq!(host.set_count(), 3);
  let (entry_rc, _) = host.lookup(id).unwrap();
  match &*entry_rc.borrow() {
    FakeEntry::Object { fields, .. } => {
      assert!(matches!(fields.get("x"), Some(FakeValue::Wire(w)) if w == "J42"));
      assert!(matches!(fields.get("name"), Some(FakeValue::Wire(w)) if w == r#"J"hi""#));
      assert!(matches!(fields.get("flag"), Some(FakeValue::Wire(w)) if w == "Jtrue"));
    }
    _ => panic!("expected object entry"),
  };
}

#[test]
fn define_property_refuses_anything_but_a_plain_value_descriptor() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("x", "I1")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);

    let caught: String = ctx
      .eval(
        r#"(() => {
                    const descriptors = [
                        { get: () => 7 },
                        { set: (v) => {} },
                        { enumerable: true },
                        {},
                        { value: 5, configurable: false },
                        { value: 5, writable: false },
                        { value: 5, enumerable: false },
                    ];
                    return JSON.stringify(descriptors.map((descriptor) => {
                        try { Object.defineProperty(obj, 'x', descriptor); return 'no-throw' }
                        catch (e) { return [e instanceof inu.PluginError, e.code].join('|') }
                    }));
                })()"#,
      )
      .unwrap();
    assert_eq!(caught, format!("[{}]", [r#""true|unsupported""#; 7].join(",")));

    let message: String = ctx
      .eval("(() => { try { Object.defineProperty(obj, 'x', { get: () => 7 }) } catch (e) { return e.message } })()")
      .unwrap();
    assert_eq!(message, DESCRIPTOR_MESSAGE);
    assert_eq!(ctx.eval::<i64, _>("obj.x").unwrap(), 1);
  });
  assert_eq!(host.set_count(), 0);
}

#[test]
fn a_view_cannot_be_sealed_or_frozen() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("x", "I1")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);

    let caught: String = ctx
      .eval(
        r#"(() => {
                    const seals = [
                        () => Object.preventExtensions(obj),
                        () => Object.freeze(obj),
                        () => Object.seal(obj),
                        () => Reflect.preventExtensions(obj),
                    ];
                    return JSON.stringify(seals.map((seal) => {
                        try { seal(); return 'no-throw' }
                        catch (e) { return [e instanceof inu.PluginError, e.code, e.message].join('|') }
                    }));
                })()"#,
      )
      .unwrap();
    let refusal = format!("true|unsupported|{NOT_EXTENSIBLE_MESSAGE}");
    assert_eq!(caught, format!(r#"["{refusal}","{refusal}","{refusal}","{refusal}"]"#));

    // the refusal kept the target extensible, so the view's own descriptors stay legal
    assert!(ctx.eval::<bool, _>("Object.isExtensible(obj)").unwrap());
    assert!(!ctx.eval::<bool, _>("Object.isFrozen(obj)").unwrap());
    let described: String = ctx.eval("JSON.stringify(Object.getOwnPropertyDescriptor(obj, 'x'))").unwrap();
    assert_eq!(described, r#"{"value":1,"writable":true,"enumerable":true,"configurable":true}"#);
  });
}

#[test]
fn a_view_is_not_a_thenable() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("x", "I1")]));
  let vec_id = host.mint(FakeEntry::Vector(vec![FakeValue::Wire("I1".to_string())]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    bind(&ctx, &views, "vec", true, false, ViewLife::Plugin, vec_id);
    let seen: String = ctx.eval("JSON.stringify([typeof obj.then, typeof vec.then, 'then' in obj])").unwrap();
    assert_eq!(seen, r#"["undefined","undefined",false]"#);
  });
  assert_eq!(host.gets_of("then"), 0);
}

#[test]
fn handle_wire_roundtrip() {
  for is_vector in [false, true] {
    for read_only in [false, true] {
      let wire = encode_handle(is_vector, read_only, 42);
      assert_eq!(parse_handle(&wire[1..]), Some((is_vector, read_only, 42, ORDINAL_FALLBACK, None)));
    }
  }
  assert_eq!(encode_handle(false, false, 1), "HOW1");
  assert_eq!(encode_handle(true, true, 7), "HVR7");
  for malformed in ["", "O", "OW", "XW1", "OX1", "O1", "OW1x", "VR-"] {
    assert!(parse_handle(malformed).is_none(), "'{malformed}' must not parse");
  }

  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint_read_only(FakeEntry::Vector(vec![]));
  let views = views_of(&host);
  ctx.with(|ctx| {
    let proxy = bind(&ctx, &views, "vec", true, true, ViewLife::Plugin, id);
    assert_eq!(js_value_to_wire(&ctx, proxy).unwrap(), format!("HVR{id}"));
  });
}

/// the crate is `panic = "abort"`, so a wire byte-indexed mid-character would kill the app
#[test]
fn a_multi_byte_wire_is_an_error_not_a_panic() {
  for malformed in ["é", "日本", "\u{1F600}W1", "ОW1", "OЖ1"] {
    assert!(parse_handle(malformed).is_none(), "'{malformed}' must not parse");
  }

  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("x", "é"), ("y", ""), ("z", "H日1")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    for wire in ["", "é", "日本語", "H日1", "HОW1", "\u{1F600}"] {
      assert!(views.wire_to_js_value(&ctx, wire, ViewLife::Plugin).is_err(), "'{wire}' must decode to an error");
    }

    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    for key in ["x", "y", "z"] {
      assert!(ctx.eval::<Value, _>(format!("obj.{key}")).is_err());
    }
  });
}

#[test]
fn dispatch_scoped_view_stores_nothing_on_its_target() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let child = Rc::new(RefCell::new(object_entry("child", &[("y", "I7")])));
  let mut fields = HashMap::new();
  fields.insert("x".to_string(), FakeValue::Wire("I1".to_string()));
  fields.insert("child".to_string(), FakeValue::Nested(child));
  let id = host.mint(FakeEntry::Object { type_name: "foo".to_string(), fields });
  let views = views_of(&host);

  ctx.with(|ctx| {
    let proxy = bind_object(&ctx, &views, "obj", ViewLife::Dispatch, id);

    ctx.eval::<i64, _>("obj.x").unwrap();
    ctx.eval::<i64, _>("obj.x").unwrap();
    assert_eq!(host.gets_of("x"), 2);

    let distinct: bool = ctx.eval("obj.child !== obj.child").unwrap();
    assert!(distinct);
    assert_eq!(host.gets_of("child"), 2);

    let target = proxy.as_proxy().unwrap().target().unwrap();
    let handle = Class::<HandleBox>::from_object(&target).unwrap();
    assert!(handle.borrow().vol.borrow().is_none());
  });
}

#[test]
fn plugin_lifetime_scalar_read_hits_the_host_once() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("x", "I1")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    assert_eq!(ctx.eval::<i64, _>("obj.x").unwrap(), 1);
    assert_eq!(ctx.eval::<i64, _>("obj.x").unwrap(), 1);
  });
  assert_eq!(host.gets_of("x"), 1);
}

#[test]
fn plugin_lifetime_child_view_is_identical_across_reads() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let child = Rc::new(RefCell::new(object_entry("child", &[("y", "I7")])));
  let id = host.mint(nested_entry("parent", "child", child));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    let same: bool = ctx.eval("obj.child === obj.child").unwrap();
    assert!(same);
    assert_eq!(ctx.eval::<i64, _>("obj.child.y").unwrap(), 7);
  });
  assert_eq!(host.gets_of("child"), 1);
  assert_eq!(host.next_id.get(), id + 1, "exactly one child handle was minted");
}

#[test]
fn vector_elements_and_length_are_never_cached() {
  let (rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let items: Vec<FakeValue> = (0..3)
    .map(|i| FakeValue::Nested(Rc::new(RefCell::new(object_entry("item", &[("a", &format!("I{i}"))])))))
    .collect();
  let id = host.mint(FakeEntry::Vector(items));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind(&ctx, &views, "vec", true, false, ViewLife::Plugin, id);

    let distinct: bool = ctx.eval("vec[0] !== vec[0]").unwrap();
    assert!(distinct);
    assert_eq!(host.gets_of("0"), 2);

    host.reset_counts();
    ctx.eval::<i64, _>("vec.length").unwrap();
    ctx.eval::<i64, _>("vec.length").unwrap();
    assert_eq!(host.gets_of("length"), 2);
  });

  let before = host.live_count();
  ctx.with(|ctx| {
    let summed: i64 = ctx.eval("(() => { let s = 0; for (const m of vec) s += m.a; return s })()").unwrap();
    assert_eq!(summed, 3);
  });
  rt.run_gc();
  assert_eq!(host.live_count(), before, "iterating must not retain a handle per element");
}

/// the cache keeps presence and the key list under names of its own; a plugin asking for one must
/// see what any other absent field answers, not the bookkeeping
#[test]
fn the_caches_own_names_are_not_fields() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("x", "I1")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    assert!(ctx.eval::<bool, _>("'x' in obj").unwrap());
    assert_eq!(ctx.eval::<i64, _>("obj.x").unwrap(), 1);

    let answers: String = ctx
      .eval(
        r##"JSON.stringify([
             ...["#x", "@keys"].map((k) => { try { return String(obj[k]) } catch (e) { return e.message } }),
             "#x" in obj,
             "@keys" in obj,
           ])"##,
      )
      .unwrap();
    assert_eq!(answers, r##"["no such field '#x'","no such field '@keys'",false,false]"##);
  });
}

/// a dispatch view caches nothing, so the vector `updates.updates` names lives only as long as the
/// expression: the iterator has to hold it, or the handle is released before the first `next()`
#[test]
fn a_temporary_vector_survives_being_iterated() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let items = Rc::new(RefCell::new(FakeEntry::Vector(vec![
    FakeValue::Wire("I1".to_string()),
    FakeValue::Wire("I2".to_string()),
  ])));
  let id = host.mint(nested_entry("updates", "updates", items));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Dispatch, id);
    let summed: i64 = ctx.eval("(() => { let s = 0; for (const u of obj.updates) s += u; return s })()").unwrap();
    assert_eq!(summed, 3);
    let spread: i64 = ctx.eval("[...obj.updates].reduce((a, b) => a + b, 0)").unwrap();
    assert_eq!(spread, 3);
  });
}

#[test]
fn object_inside_a_vector_is_still_cached() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let items = vec![FakeValue::Nested(Rc::new(RefCell::new(object_entry("item", &[("a", "I5")]))))];
  let id = host.mint(FakeEntry::Vector(items));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind(&ctx, &views, "vec", true, false, ViewLife::Plugin, id);
    host.reset_counts();
    ctx.eval::<(), _>("globalThis.m = vec[0]").unwrap();
    assert_eq!(ctx.eval::<i64, _>("m.a").unwrap(), 5);
    assert_eq!(ctx.eval::<i64, _>("m.a").unwrap(), 5);
  });
  assert_eq!(host.gets_of("a"), 1);
  assert_eq!(host.gets_of("0"), 1);
}

#[test]
fn shared_flag_bit_sibling_is_refetched_after_a_write() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("x", "I1")]));
  *host.reveal_on_set.borrow_mut() = Some(("x".to_string(), "y".to_string(), "I9".to_string()));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    assert_eq!(ctx.eval::<i64, _>("obj.x").unwrap(), 1);
    assert!(!ctx.eval::<bool, _>("'y' in obj").unwrap());
    assert_eq!(ctx.eval::<Vec<String>, _>("Object.keys(obj)").unwrap(), vec!["_", "x"]);

    ctx.eval::<(), _>("obj.x = 5").unwrap();

    host.reset_counts();
    assert_eq!(ctx.eval::<i64, _>("obj.x").unwrap(), 5);
    assert!(ctx.eval::<bool, _>("'y' in obj").unwrap());
    assert_eq!(ctx.eval::<Vec<String>, _>("Object.keys(obj)").unwrap(), vec!["_", "x", "y"]);
    assert_eq!(ctx.eval::<i64, _>("obj.y").unwrap(), 9);
  });
  assert_eq!(host.gets_of("x"), 1);
  assert_eq!(host.has_of("y"), 1);
  assert_eq!(host.own_keys_count(), 1);
}

#[test]
fn write_through_a_child_invalidates_the_parent() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let entities = Rc::new(RefCell::new(FakeEntry::Vector(vec![
    FakeValue::Wire("I1".to_string()),
    FakeValue::Wire("I2".to_string()),
  ])));
  let id = host.mint(nested_entry("message", "entities", entities));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    ctx.eval::<(), _>("obj.entities").unwrap();
    assert!(ctx.eval::<bool, _>("'entities' in obj").unwrap());
    assert_eq!(ctx.eval::<Vec<String>, _>("Object.keys(obj)").unwrap(), vec!["_", "entities"]);

    host.reset_counts();
    ctx.eval::<(), _>("obj.entities.length = 0").unwrap();
    assert_eq!(host.gets_of("entities"), 0, "the child view came from the parent's cache");

    host.reset_counts();
    ctx.eval::<Vec<String>, _>("Object.keys(obj)").unwrap();
    ctx.eval::<bool, _>("'entities' in obj").unwrap();
    ctx.eval::<(), _>("obj.entities").unwrap();
  });
  assert_eq!(host.own_keys_count(), 1);
  assert_eq!(host.has_of("entities"), 1);
  assert_eq!(host.gets_of("entities"), 1);
}

#[test]
fn aliased_views_see_each_others_writes() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let entry = Rc::new(RefCell::new(object_entry("foo", &[("x", "I1")])));
  let a = host.mint_shared(entry.clone(), false);
  let b = host.mint_shared(entry, false);
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind(&ctx, &views, "a", false, false, ViewLife::Plugin, a);
    bind(&ctx, &views, "b", false, false, ViewLife::Plugin, b);
    assert_eq!(ctx.eval::<i64, _>("a.x").unwrap(), 1);
    assert_eq!(ctx.eval::<i64, _>("b.x").unwrap(), 1);

    ctx.eval::<(), _>("a.x = 42").unwrap();
    assert_eq!(ctx.eval::<i64, _>("b.x").unwrap(), 42);
  });
}

#[test]
fn a_failing_tl_set_still_invalidates() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("x", "I1")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    assert_eq!(ctx.eval::<i64, _>("obj.x").unwrap(), 1);

    host.set_fails.set(true);
    let message: String = ctx
      .eval("(() => { try { obj.x = 5; return 'no-throw' } catch (e) { return e.message } })()")
      .unwrap();
    assert_eq!(message, "simulated set failure");

    host.reset_counts();
    assert_eq!(ctx.eval::<i64, _>("obj.x").unwrap(), 5);
  });
  assert_eq!(host.gets_of("x"), 1);
}

#[test]
fn refused_write_on_a_read_only_view_does_not_invalidate() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint_read_only(object_entry("foo", &[("x", "I1")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind(&ctx, &views, "obj", false, true, ViewLife::Plugin, id);
    assert_eq!(ctx.eval::<i64, _>("obj.x").unwrap(), 1);

    host.reset_counts();
    assert!(ctx.eval::<Value, _>("obj.x = 2").is_err());
    assert!(ctx.eval::<Value, _>("delete obj.x").is_err());
    assert_eq!(ctx.eval::<i64, _>("obj.x").unwrap(), 1);
  });
  assert_eq!(host.set_count(), 0);
  assert_eq!(host.gets_of("x"), 0, "the cached read must have survived the refusals");
}

#[test]
fn type_name_and_tojson_survive_an_epoch_bump() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("x", "I1")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    assert_eq!(ctx.eval::<String, _>("obj._").unwrap(), "foo");
    ctx.eval::<(), _>("globalThis.f1 = obj.toJSON").unwrap();

    ctx.eval::<(), _>("obj.x = 7").unwrap();

    host.reset_counts();
    assert_eq!(ctx.eval::<String, _>("obj._").unwrap(), "foo");
    assert_eq!(host.gets_of("_"), 0);
    assert!(ctx.eval::<bool, _>("f1 === obj.toJSON").unwrap());

    assert!(ctx.eval::<bool, _>("obj.toJSON() !== obj.toJSON()").unwrap());
    assert_eq!(ctx.eval::<i64, _>("obj.toJSON().x").unwrap(), 7);
  });
}

#[test]
fn has_is_cached_separately_from_values() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("x", "N"), ("y", "I3")]));
  host.cleared_bits.borrow_mut().insert("y".to_string());
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);

    assert!(ctx.eval::<bool, _>("'x' in obj").unwrap());
    assert!(ctx.eval::<bool, _>("obj.x === null").unwrap());

    assert!(!ctx.eval::<bool, _>("'y' in obj").unwrap());
    assert!(ctx.eval::<bool, _>("obj.y === null").unwrap());

    host.reset_counts();
    assert!(ctx.eval::<bool, _>("'x' in obj").unwrap());
    assert!(!ctx.eval::<bool, _>("'y' in obj").unwrap());
    assert!(ctx.eval::<bool, _>("obj.x === null").unwrap());
    assert!(ctx.eval::<bool, _>("obj.y === null").unwrap());
  });
  assert_eq!(host.has_count(), 0);
  assert_eq!(host.get_count(), 0);
}

#[test]
fn gopd_never_reports_tojson_and_does_not_poison_the_has_cache() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("x", "I1")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    assert!(ctx.eval::<bool, _>("'toJSON' in obj").unwrap());
    assert!(ctx.eval::<bool, _>("Object.getOwnPropertyDescriptor(obj, 'toJSON') === undefined").unwrap());

    ctx.eval::<Value, _>("({...obj})").unwrap();

    assert!(ctx.eval::<bool, _>("'toJSON' in obj").unwrap());
    assert!(ctx.eval::<bool, _>("Object.getOwnPropertyDescriptor(obj, 'toJSON') === undefined").unwrap());
  });
}

#[test]
fn gopd_reports_writable_false_configurable_true_on_a_read_only_view() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let entry = Rc::new(RefCell::new(object_entry("foo", &[("x", "I1")])));
  let ro = host.mint_shared(entry.clone(), true);
  let rw = host.mint_shared(entry, false);
  let views = views_of(&host);

  ctx.with(|ctx| {
        bind(&ctx, &views, "ro", false, true, ViewLife::Plugin, ro);
        bind(&ctx, &views, "rw", false, false, ViewLife::Plugin, rw);

        let described: String = ctx
            .eval(
                r#"JSON.stringify([
                    Object.getOwnPropertyDescriptor(ro, 'x'),
                    Object.getOwnPropertyDescriptor(rw, 'x'),
                    {...ro},
                    Object.entries(ro),
                    Object.isFrozen(ro),
                ])"#,
            )
            .unwrap();
        assert_eq!(
            described,
            r#"[{"value":1,"writable":false,"enumerable":true,"configurable":true},{"value":1,"writable":true,"enumerable":true,"configurable":true},{"_":"foo","x":1},[["_","foo"],["x",1]],false]"#
        );
    });
}

#[test]
fn second_enumeration_costs_no_upcalls() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("x", "I1"), ("y", "S2")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    let first: String = ctx.eval("JSON.stringify({...obj})").unwrap();
    let second: String = ctx.eval("JSON.stringify({...obj})").unwrap();
    assert_eq!(first, r#"{"_":"foo","x":1,"y":"2"}"#);
    assert_eq!(second, first);
  });
  assert_eq!(host.own_keys_count(), 1);
  assert_eq!(host.has_count(), 3);
  assert_eq!(host.get_count(), 3);
}

#[test]
fn own_keys_expiry_is_not_cached() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, 999);
    assert!(ctx.eval::<Value, _>("Object.keys(obj)").is_err());
    assert!(ctx.eval::<Value, _>("Object.keys(obj)").is_err());
  });
  assert_eq!(host.own_keys_count(), 2);
}

#[test]
fn has_expiry_is_not_cached() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("x", "I1")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    assert!(ctx.eval::<bool, _>("'x' in obj").unwrap());

    host.tl_release(id);
    host.reset_counts();
    assert!(ctx.eval::<Value, _>("'y' in obj").is_err());
    assert!(ctx.eval::<Value, _>("'y' in obj").is_err());
  });
  assert_eq!(host.has_of("y"), 2);
}

#[test]
fn errors_are_never_cached() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    assert!(ctx.eval::<Value, _>("obj.missing").is_err());
    assert!(ctx.eval::<Value, _>("obj.missing").is_err());
  });
  assert_eq!(host.gets_of("missing"), 2);
}

#[test]
fn cache_never_answers_with_a_prototype_member() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("foo", &[("x", "I1")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    let probe = r#"(() => JSON.stringify([
            (() => { try { return obj.constructor } catch (e) { return e.message } })(),
            (() => { try { return obj.toString } catch (e) { return e.message } })(),
            (() => { try { return obj.__proto__ } catch (e) { return e.message } })(),
            'constructor' in obj,
        ]))()"#;
    let expected = r#"["no such field 'constructor'","no such field 'toString'","no such field '__proto__'",false]"#;
    assert_eq!(ctx.eval::<String, _>(probe).unwrap(), expected);
    ctx.eval::<Value, _>("({...obj})").unwrap();
    assert_eq!(ctx.eval::<String, _>(probe).unwrap(), expected);
  });
}

#[test]
fn cached_children_die_with_their_parent() {
  let (rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let child = Rc::new(RefCell::new(object_entry("child", &[("y", "I7")])));
  let id = host.mint(nested_entry("parent", "child", child));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    assert_eq!(ctx.eval::<i64, _>("obj.child.y").unwrap(), 7);
    assert_eq!(host.live_count(), 2);
    ctx.eval::<(), _>("globalThis.obj = undefined").unwrap();
  });

  rt.run_gc();
  assert_eq!(host.live_count(), 0);
}

#[test]
fn dropping_the_runtime_releases_every_cached_handle() {
  let host = Rc::new(FakeTlHost::default());
  let child = Rc::new(RefCell::new(object_entry("child", &[("y", "I7")])));
  let id = host.mint(nested_entry("parent", "child", child));

  {
    let (rt, ctx) = make_ctx();
    let views = views_of(&host);
    ctx.with(|ctx| {
      bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
      assert_eq!(ctx.eval::<i64, _>("obj.child.y").unwrap(), 7);
    });
    assert_eq!(host.live_count(), 2);
    drop(ctx);
    drop(rt);
  }

  assert_eq!(host.live_count(), 0);
}

#[test]
fn deep_chain_over_a_cyclic_graph_releases_fully() {
  let (rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let a = Rc::new(RefCell::new(object_entry("a", &[])));
  let b = Rc::new(RefCell::new(object_entry("b", &[])));
  add_nested(&a, "b", b.clone());
  add_nested(&b, "a", a.clone());
  let root = host.mint_shared(a, false);
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "root", ViewLife::Plugin, root);
    ctx
      .eval::<(), _>("(() => { let cur = root; for (let i = 0; i < 100; i++) cur = i % 2 === 0 ? cur.b : cur.a })()")
      .unwrap();
    assert_eq!(host.live_count(), 101, "every hop is pinned by its parent's cache");
    ctx.eval::<(), _>("globalThis.root = undefined").unwrap();
  });

  rt.run_gc();
  assert_eq!(host.live_count(), 0);
}

#[test]
fn write_clears_the_writing_views_bag_promptly() {
  let (rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let child = Rc::new(RefCell::new(object_entry("child", &[("y", "I7")])));
  let id = host.mint(nested_entry("parent", "child", child));
  let views = views_of(&host);

  ctx.with(|ctx| {
    bind_object(&ctx, &views, "obj", ViewLife::Plugin, id);
    assert_eq!(ctx.eval::<i64, _>("obj.child.y").unwrap(), 7);
    assert_eq!(host.live_count(), 2);
    ctx.eval::<(), _>("obj.other = 1").unwrap();
  });

  rt.run_gc();
  assert_eq!(host.live_count(), 1, "the write must have dropped the cached child view");
}

/// a handle can arrive with the scalars kotlin already read: they are the cache, so reading them
/// never crosses, and what was not sent is read the way it always was
#[test]
fn a_projected_handle_answers_its_scalars_without_the_host() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id =
    host.mint_read_only(object_entry("dialog", &[("id", "S5"), ("date", "I9"), ("draft", "N"), ("pinned", "B1")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    let wire =
      format!("{}|{{\"_\":\"dialog\",\"id\":\"5\",\"date\":9,\"draft\":null}}", encode_handle(false, true, id));
    let value = views.wire_to_js_value(&ctx, &wire, ViewLife::Plugin).unwrap();
    ctx.globals().set("d", value).unwrap();

    let read: String = ctx.eval("`${d._} ${d.id} ${d.date} ${d.draft}`").unwrap();
    assert_eq!(read, "dialog 5 9 null");
    assert_eq!(host.get_count(), 0, "projected scalars must not cross");

    let present: bool = ctx.eval("'id' in d").unwrap();
    assert!(present);
    assert_eq!(host.has_count(), 0, "a cached value proves presence");
    let absent: bool = ctx.eval("'draft' in d").unwrap();
    assert!(absent, "the fake answers present for every field it holds");
    assert_eq!(host.has_count(), 1, "a cached null cannot say which way the bit went");

    let pinned: bool = ctx.eval("d.pinned").unwrap();
    assert!(pinned);
    assert_eq!(host.gets_of("pinned"), 1, "what was not projected is read as before");

    let keys: Vec<String> = ctx.eval("Object.keys(d)").unwrap();
    assert!(keys.contains(&"pinned".to_string()) && keys.contains(&"_".to_string()));
    assert_eq!(host.own_keys_count(), 1, "the projection does not claim to be the whole object");
  });
}

#[test]
fn a_projection_is_dropped_by_a_write_like_any_cached_value() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let projected = host.mint_read_only(object_entry("dialog", &[("id", "S5")]));
  let other = host.mint(object_entry("bar", &[("x", "I1")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    let wire = format!("{}|{{\"_\":\"dialog\",\"id\":\"5\"}}", encode_handle(false, true, projected));
    let value = views.wire_to_js_value(&ctx, &wire, ViewLife::Plugin).unwrap();
    ctx.globals().set("d", value).unwrap();
    bind_object(&ctx, &views, "other", ViewLife::Plugin, other);

    let _: () = ctx.eval("other.x = 2").unwrap();
    let id: String = ctx.eval("d.id").unwrap();
    assert_eq!(id, "5");
    assert_eq!(host.gets_of("id"), 1, "after a write the value is read again");
    let type_name: String = ctx.eval("d._").unwrap();
    assert_eq!(type_name, "dialog");
    assert_eq!(host.gets_of("_"), 0, "the type name never changes");
  });
}

/// a dispatch-lifetime view caches nothing, so it adopts nothing either
#[test]
fn a_dispatch_view_ignores_a_projection() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint(object_entry("dialog", &[("id", "S5")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    let wire = format!("{}|{{\"id\":\"stale\"}}", encode_handle(false, false, id));
    let value = views.wire_to_js_value(&ctx, &wire, ViewLife::Dispatch).unwrap();
    ctx.globals().set("d", value).unwrap();
    let id: String = ctx.eval("d.id").unwrap();
    assert_eq!(id, "5");
    assert_eq!(host.gets_of("id"), 1);
  });
}

#[test]
fn a_malformed_projection_is_refused() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let id = host.mint_read_only(object_entry("dialog", &[("id", "S5")]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    for bad in ["[1]", "not json", ""] {
      let wire = format!("{}|{bad}", encode_handle(false, true, id));
      assert!(views.wire_to_js_value(&ctx, &wire, ViewLife::Plugin).is_err(), "{bad:?}");
    }
  });
}

/// one handler serves every view of a context; a view costs its target and its proxy
#[test]
fn every_view_shares_one_handler() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let a = host.mint(object_entry("a", &[]));
  let b = host.mint_read_only(object_entry("b", &[]));
  let views = views_of(&host);

  ctx.with(|ctx| {
    let a = bind(&ctx, &views, "a", false, false, ViewLife::Dispatch, a);
    let b = bind(&ctx, &views, "b", true, true, ViewLife::Plugin, b);
    let handler_a = a.as_proxy().unwrap().handler().unwrap();
    let handler_b = b.as_proxy().unwrap().handler().unwrap();
    assert_eq!(handler_a, handler_b);
  });
}

/// Not a test: a benchmark of what a list answer costs to build and to read, on the rust side of
/// the bridge alone (the fake host answers from memory). `cargo test --release bench_views -- --ignored --nocapture`.
#[test]
#[ignore]
fn bench_views() {
  let (_rt, ctx) = make_ctx();
  let host = Rc::new(FakeTlHost::default());
  let count = 200;
  let views = views_of(&host);
  let mint_all = || {
    let mut wires = Vec::new();
    for i in 0..count {
      let peer = host.mint_read_only(object_entry("peerUser", &[("user_id", &format!("I{i}"))]));
      let entry = Rc::new(RefCell::new(object_entry(
        "dialog",
        &[("id", &format!("S{i}")), ("last_message_date", &format!("I{}", 1_000_000 + i)), ("top_message", "I7")],
      )));
      let (peer_entry, _) = host.lookup(peer).unwrap();
      add_nested(&entry, "peer", peer_entry);
      let id = host.mint_shared(entry, true);
      wires.push(encode_handle(false, true, id));
    }
    wires
  };
  let rounds = 6;
  let mut build = Vec::new();
  let mut cold = Vec::new();
  let mut cached = Vec::new();
  let mut nested = Vec::new();
  for _ in 0..rounds {
    let wires = mint_all();
    ctx.with(|ctx| {
      let started = std::time::Instant::now();
      let array = Array::new(ctx.clone()).unwrap();
      for (index, wire) in wires.iter().enumerate() {
        array.set(index, views.wire_to_js_value(&ctx, wire, ViewLife::Plugin).unwrap()).unwrap();
      }
      ctx.globals().set("ds", array).unwrap();
      build.push(started.elapsed());
      let started = std::time::Instant::now();
      if let Err(e) =
        ctx.eval::<(), _>("globalThis.s = 0; for (const d of ds) { s += d.id.length; s += d.last_message_date; }")
      {
        panic!("{e}: {:?}", ctx.catch().as_exception().map(|x| x.message()));
      }
      cold.push(started.elapsed());
      let started = std::time::Instant::now();
      let _: () = ctx.eval("for (const d of ds) { s += d.id.length; s += d.last_message_date; }").unwrap();
      cached.push(started.elapsed());
      let started = std::time::Instant::now();
      let _: () = ctx.eval("for (const d of ds) { s += d.peer.user_id; }").unwrap();
      nested.push(started.elapsed());
      let _: () = ctx.eval("ds = null").unwrap();
    });
    _rt.run_gc();
  }
  let median = |v: &mut Vec<std::time::Duration>| {
    v.remove(0);
    v.sort();
    v[v.len() / 2].as_secs_f64() * 1000.0
  };
  println!(
    "bench_views {count}: build={:.3}ms cold={:.3}ms cached={:.3}ms nested={:.3}ms",
    median(&mut build),
    median(&mut cold),
    median(&mut cached),
    median(&mut nested)
  );
}

fn log_view(host: &Rc<FakeTlHost>, is_vector: bool, id: i64) -> Vec<String> {
  let (_rt, ctx) = make_ctx();
  let views = views_of(host);
  let lines = crate::testing::harness::install_capturing_console(&ctx);
  ctx.with(|ctx| {
    bind(&ctx, &views, "view", is_vector, false, ViewLife::Dispatch, id);
    ctx.eval::<(), _>("console.log(view)").unwrap();
  });
  let lines = lines.borrow();
  lines.clone()
}

#[test]
fn console_prints_a_view_as_its_type_and_fields() {
  let host = Rc::new(FakeTlHost::default());
  let peer = Rc::new(RefCell::new(object_entry("peerUser", &[("user_id", "I7")])));
  let message = Rc::new(RefCell::new(object_entry("message", &[("id", "I5"), ("message", "Shi")])));
  add_nested(&message, "peer_id", peer);
  let id = host.mint_shared(message, false);
  assert_eq!(log_view(&host, false, id), vec!["message { id: 5, message: 'hi', peer_id: peerUser { user_id: 7 } }"]);
}

#[test]
fn console_prints_a_vector_view_as_an_array() {
  let host = Rc::new(FakeTlHost::default());
  let peer = Rc::new(RefCell::new(object_entry("peerUser", &[("user_id", "I7")])));
  let id = host.mint(FakeEntry::Vector(vec![FakeValue::Wire("I1".to_string()), FakeValue::Nested(peer)]));
  assert_eq!(log_view(&host, true, id), vec!["[ 1, peerUser { user_id: 7 } ]"]);
}

#[test]
fn console_collapses_a_view_nested_past_two_levels_to_its_type() {
  let host = Rc::new(FakeTlHost::default());
  let d = Rc::new(RefCell::new(object_entry("d.type", &[("x", "I1")])));
  let c = Rc::new(RefCell::new(nested_entry("c.type", "d", d)));
  let b = Rc::new(RefCell::new(nested_entry("b.type", "c", c)));
  let id = host.mint(nested_entry("a.type", "b", b));
  assert_eq!(log_view(&host, false, id), vec!["a.type { b: b.type { c: c.type { d: [d.type] } } }"]);
}

#[test]
fn console_prints_an_expired_view_without_throwing() {
  let host = Rc::new(FakeTlHost::default());
  assert_eq!(log_view(&host, false, 999), vec![format!("[TL view: {HANDLE_EXPIRED_MESSAGE}]")]);
}

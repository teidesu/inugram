use super::*;
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
                    let n: usize = value_wire
                        .strip_prefix('I')
                        .or_else(|| value_wire.strip_prefix('J'))
                        .and_then(|s| s.parse().ok())?;
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

fn make_ctx() -> (Runtime, Context) {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    ctx.with(|ctx| crate::engine::error::install_plugin_error(&ctx).unwrap());
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
    let value = wire_to_js_value(ctx, views, &encode_handle(is_vector, read_only, id), life).unwrap();
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

        let json: String =
            ctx.eval("(() => { const c = obj.toJSON(); c.x = 999; return JSON.stringify(c); })()").unwrap();
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
        ctx.eval::<(), _>(
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
        ctx.eval::<(), _>(
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
            assert_eq!(parse_handle(&wire[1..]), Some((is_vector, read_only, 42)));
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
            assert!(
                wire_to_js_value(&ctx, &views, wire, ViewLife::Plugin).is_err(),
                "'{wire}' must decode to an error"
            );
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
        assert!(!target.contains_key(cache_marker(&ctx).unwrap().as_atom()).unwrap());
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
        let message: String =
            ctx.eval("(() => { try { obj.x = 5; return 'no-throw' } catch (e) { return e.message } })()").unwrap();
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
        let expected =
            r#"["no such field 'constructor'","no such field 'toString'","no such field '__proto__'",false]"#;
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
        ctx.eval::<(), _>(
            "(() => { let cur = root; for (let i = 0; i < 100; i++) cur = i % 2 === 0 ? cur.b : cur.a })()",
        )
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

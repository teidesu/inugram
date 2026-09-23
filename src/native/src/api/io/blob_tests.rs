use super::*;
use rquickjs::{Context, Runtime};

use crate::testing::harness::TestDir;

struct Fixture {
  _rt: Runtime,
  ctx: Context,
  state: Rc<BlobState>,
  dir: TestDir,
}

fn setup(name: &str) -> Fixture {
  setup_with(name, true, BlobLimits::default())
}

fn setup_with(name: &str, spilling: bool, limits: BlobLimits) -> Fixture {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let dir = TestDir::new(name);
  let spill_dir = if spilling { dir.path().to_path_buf() } else { PathBuf::new() };
  let state = ctx.with(|ctx| {
    crate::api::error::install_plugin_error(&ctx).unwrap();
    install_with_limits(&ctx, &spill_dir, ExternalMemory::new(), limits).unwrap()
  });
  Fixture { _rt: rt, ctx, state, dir }
}

fn eval(fixture: &Fixture, code: &str) -> String {
  crate::testing::harness::eval_string(&fixture.ctx, code)
}

fn run(fixture: &Fixture, code: &str) {
  crate::testing::harness::eval_unit(&fixture.ctx, code)
}

/// awaits `expr` and stringifies the outcome, since every read answers with a promise. The
/// microtask that settles it is already queued, so one drain is enough.
fn settle(fixture: &Fixture, expr: &str) -> String {
  run(
    fixture,
    &format!(
      r#"
        globalThis.__out = 'pending';
        Promise.resolve().then(() => {expr}).then(
          v => {{ globalThis.__out = 'ok:' + v; }},
          e => {{ globalThis.__out = `${{e.name}}:${{e.code}}:${{e.message}}`; }},
        );
      "#,
    ),
  );
  fixture._rt.execute_pending_job().ok();
  while fixture._rt.is_job_pending() {
    fixture._rt.execute_pending_job().ok();
  }
  eval(fixture, "String(globalThis.__out)")
}

#[test]
fn a_slice_past_either_end_clamps_to_the_content() {
  let f = setup("slice");
  run(&f, "globalThis.__b = new Blob(['0123456789']);");
  assert_eq!(settle(&f, "__b.slice(-100, 100).text()"), "ok:0123456789");
  assert_eq!(settle(&f, "__b.slice(50).text()"), "ok:");
}

/// rquickjs's `Opt` is arity, not webidl, so an argument passed explicitly as `undefined` used
/// to arrive as `Coerced(NaN)` and discard the default - an empty blob out of the ordinary
/// `slice(offset, end)` idiom where `end` is an unset variable, with nothing thrown
#[test]
fn an_explicitly_undefined_argument_is_the_same_as_an_omitted_one() {
  let f = setup("slice-undefined");
  run(&f, "globalThis.__b = new Blob(['0123456789'], { type: 'text/plain' });");
  assert_eq!(settle(&f, "__b.slice(2, undefined).text()"), "ok:23456789");
  assert_eq!(settle(&f, "__b.slice(undefined, 3).text()"), "ok:012");
  assert_eq!(eval(&f, "String(__b.slice(2, undefined).size)"), "8");
  assert_eq!(eval(&f, "__b.slice(0, 1, undefined).type"), "", "not the text 'undefined'");
}

#[test]
fn a_small_blob_stays_in_memory_and_one_byte_more_spills() {
  let f = setup("threshold");
  run(&f, &format!("globalThis.__small = new Blob([new Uint8Array({})]);", SPILL_THRESHOLD_BYTES));
  assert_eq!(f.state.charged_bytes(), SPILL_THRESHOLD_BYTES as usize);
  assert_eq!(f.state.spilled_bytes(), 0);
  assert!(f.dir.entries().is_empty(), "a blob at the threshold must not touch the disk");

  run(&f, &format!("globalThis.__big = new Blob([new Uint8Array({})]);", SPILL_THRESHOLD_BYTES + 1));
  assert_eq!(
    f.state.charged_bytes(),
    SPILL_THRESHOLD_BYTES as usize,
    "a spilled blob charges the native budget nothing",
  );
  assert_eq!(f.state.spilled_bytes(), SPILL_THRESHOLD_BYTES + 1);
  assert_eq!(f.dir.entries().len(), 1);
  assert_eq!(
    fs::metadata(&f.dir.entries()[0]).unwrap().len(),
    SPILL_THRESHOLD_BYTES + 1,
    "the whole content has to be on disk, not just the part past the threshold",
  );
  assert_eq!(eval(&f, "String(__big.size)"), (SPILL_THRESHOLD_BYTES + 1).to_string());
}

#[test]
fn building_from_a_spilled_blob_never_charges_the_native_budget() {
  let f = setup("spill-of-spill");
  run(
    &f,
    &format!(
      r#"
        globalThis.__first = new Blob([new Uint8Array({}).fill(7)]);
        globalThis.__second = new Blob([__first, __first]);
      "#,
      SPILL_THRESHOLD_BYTES + 1,
    ),
  );
  assert_eq!(f.state.charged_bytes(), 0);
  assert_eq!(f.state.spilled_bytes(), 3 * (SPILL_THRESHOLD_BYTES + 1));
  assert_eq!(eval(&f, "String(__second.size)"), (2 * (SPILL_THRESHOLD_BYTES + 1)).to_string());
  assert_eq!(settle(&f, "__second.slice(0, 3).bytes().then(b => b.join(','))"), "ok:7,7,7");
}

/// the native budget is a routing decision for a blob, never a failure: it has somewhere else
/// to put the bytes
#[test]
fn a_full_native_budget_spills_instead_of_throwing() {
  let f = setup("pressure");
  let held = f
    .ctx
    .with(|ctx| f.state.external.try_charge(&ctx, crate::sandbox::limits::EXTERNAL_LIMIT_BYTES).unwrap());
  run(&f, "globalThis.__b = new Blob(['tiny']);");
  assert_eq!(f.dir.entries().len(), 1, "a refused charge must land on disk");
  assert_eq!(f.state.spilled_bytes(), 4);
  assert_eq!(settle(&f, "__b.text()"), "ok:tiny");
  drop(held);
}

#[test]
fn without_a_spill_directory_blobs_still_work_in_memory() {
  let f = setup_with("no-dir", false, BlobLimits::default());
  run(&f, &format!("globalThis.__b = new Blob([new Uint8Array({}).fill(3)]);", SPILL_THRESHOLD_BYTES + 1));
  assert_eq!(f.state.charged_bytes(), (SPILL_THRESHOLD_BYTES + 1) as usize);
  assert_eq!(f.state.spilled_bytes(), 0);
  assert_eq!(settle(&f, "__b.slice(0, 2).bytes().then(b => b.join(','))"), "ok:3,3");
}

#[test]
fn a_spill_past_the_ceiling_is_refused_and_leaves_nothing_behind() {
  let f = setup_with(
    "spill-limit",
    true,
    BlobLimits {
      spill_bytes: SPILL_THRESHOLD_BYTES + 1024,
      ..BlobLimits::default()
    },
  );
  let out = eval(
    &f,
    &format!(
      r#"
        let out = 'no-throw';
        try {{ new Blob([new Uint8Array({})]); }}
        catch (e) {{ out = [e instanceof inu.PluginError, e.code, e.quota].join('|'); }}
        out;
      "#,
      SPILL_THRESHOLD_BYTES * 2,
    ),
  );
  assert_eq!(out, format!("true|quota-exceeded|{}", SPILL_THRESHOLD_BYTES + 1024));
  assert!(f.dir.entries().is_empty(), "the half-written spill must be gone");
  assert_eq!(f.state.spilled_bytes(), 0, "and its reservation released");
}

/// lets js read the native budget mid-construction, which is the only way to see *when* a
/// growing buffer is charged rather than only what it ends up charging
fn install_charge_probe(f: &Fixture) {
  let state = f.state.clone();
  f.ctx.with(|ctx| {
    let probe = Function::new(ctx.clone(), move || state.charged_bytes() as f64).unwrap();
    ctx.globals().set("__charged", probe).unwrap();
  });
}

/// a buffer that doubled its way to the right length holds about twice what it says it does,
/// and a budget told only the length is a budget a plugin can be over while inside
#[test]
fn a_memory_blob_charges_for_what_was_allocated_and_hands_back_the_slack() {
  let f = setup("capacity");
  run(
    &f,
    r#"
      const parts = [];
      for (let i = 0; i < 300; i++) parts.push('0123456789'.repeat(10));
      globalThis.__b = new Blob(parts);
    "#,
  );
  assert_eq!(f.state.charged_bytes(), 30000, "the slack the growth left has to go back");
}

/// An engine with nowhere to spill must refuse the growth it cannot afford *before* making it.
/// Charging at the end means the allocation that overruns the budget has already happened, and
/// past the budget there is no refusal left to make: on a device that is an abort.
#[test]
fn with_nowhere_to_spill_the_buffer_is_charged_as_it_grows() {
  let f = setup_with("grow-charge", false, BlobLimits::default());
  install_charge_probe(&f);
  let room = 4 * 1024 * 1024;
  let held = f
    .ctx
    .with(|ctx| f.state.external.try_charge(&ctx, crate::sandbox::limits::EXTERNAL_LIMIT_BYTES - room).unwrap());
  let out = eval(
    &f,
    r#"
      globalThis.__seen = [];
      const part = { toString() { __seen.push(__charged()); return 'x'.repeat(1024 * 1024); } };
      let code = 'no-throw';
      try { new Blob(Array(16).fill(part)); } catch (e) { code = e.code; }
      [code, __seen.length, __seen[__seen.length - 1] - __seen[0]].join('|');
    "#,
  );
  drop(held);
  let parts: Vec<&str> = out.split('|').collect();
  assert_eq!(parts[0], "quota-exceeded", "got: {out}");
  let consumed: usize = parts[1].parse().unwrap();
  assert!(
    consumed < 16,
    "the construction has to stop at the part it cannot afford, not read all of them: {out}",
  );
  let grew: u64 = parts[2].parse().unwrap();
  assert!(grew >= 2 * 1024 * 1024, "the buffer has to be charged for while it grows: {out}",);
}

/// the ceiling on one construction, which is the only bound on how long it blocks the queue
/// every plugin shares: the execution deadline is polled on js back-edges and sees none of it
#[test]
fn a_construction_past_what_one_call_may_move_is_refused_before_it_reads() {
  let f = setup_with(
    "build-limit",
    true,
    BlobLimits {
      build: 64 * 1024,
      ..BlobLimits::default()
    },
  );
  let path = write_app_file(&f.dir, "huge.bin", b"x");
  // sealed far past the ceiling while being one byte long, so a refusal naming the ceiling is
  // the proof nothing read it: a read would have failed as `handle-expired`
  mint_test_app_file(&f, &path, 1024 * 1024, None);
  let out = eval(
    &f,
    r#"
      let out = 'no-throw';
      try { new Blob(['head', __f]); }
      catch (e) { out = [e instanceof inu.PluginError, e.code, e.usage, e.quota].join('|'); }
      out;
    "#,
  );
  assert_eq!(out, format!("true|quota-exceeded|{}|{}", 1024 * 1024 + 4, 64 * 1024));
  assert_eq!(f.dir.entries().len(), 1, "nothing was written: {:?}", f.dir.entries());
  assert_eq!(f.state.spilled_bytes(), 0);
  assert_eq!(f.state.charged_bytes(), 0);
  assert_eq!(
    eval(&f, "String(new Blob([new Uint8Array(64 * 1024)]).size)"),
    "65536",
    "a construction at the ceiling is still allowed",
  );
}

/// every spill is an fd held for the backing's life, and the byte ceiling bounds none of them:
/// content spills at four bytes once the native budget is full
#[test]
fn a_plugin_may_not_hold_more_spill_files_than_the_engine_will_open() {
  let f = setup_with("fd-limit", true, BlobLimits { spill_files: 3, ..BlobLimits::default() });
  let held = f
    .ctx
    .with(|ctx| f.state.external.try_charge(&ctx, crate::sandbox::limits::EXTERNAL_LIMIT_BYTES).unwrap());
  let out = eval(
    &f,
    r#"
      globalThis.__kept = [];
      let out = 'no-throw';
      try { for (let i = 0; i < 10; i++) __kept.push(new Blob(['x'])); }
      catch (e) { out = [e instanceof inu.PluginError, e.code, __kept.length, e.usage, e.quota].join('|'); }
      out;
    "#,
  );
  assert_eq!(out, "true|quota-exceeded|3|4|3");
  assert_eq!(f.state.open_spills(), 3);
  assert_eq!(f.dir.entries().len(), 3);

  run(&f, "__kept.pop().dispose();");
  assert_eq!(f.state.open_spills(), 2);
  run(&f, "__kept.push(new Blob(['y']));");
  assert_eq!(f.state.open_spills(), 3);
  drop(held);
}

/// spilled content held only by a reference cycle is quota a plugin cannot get back by any
/// means available to it, so both spill ceilings collect before they refuse
#[test]
fn an_unreachable_spill_is_collected_before_either_ceiling_refuses() {
  let make = format!("new Blob([new Uint8Array({})])", SPILL_THRESHOLD_BYTES + 1);
  let orphan = format!("globalThis.cycle = {{ blob: {make} }}; cycle.self = cycle; globalThis.cycle = null;");

  let f = setup_with(
    "spill-bytes-gc",
    true,
    BlobLimits {
      spill_bytes: 2 * SPILL_THRESHOLD_BYTES + 1,
      ..BlobLimits::default()
    },
  );
  run(&f, &orphan);
  assert_eq!(f.state.spilled_bytes(), SPILL_THRESHOLD_BYTES + 1);
  run(&f, &format!("globalThis.__b = {make};"));
  assert_eq!(f.state.spilled_bytes(), SPILL_THRESHOLD_BYTES + 1);
  assert_eq!(f.state.open_spills(), 1);

  let f = setup_with("spill-files-gc", true, BlobLimits { spill_files: 1, ..BlobLimits::default() });
  run(&f, &orphan);
  assert_eq!(f.state.open_spills(), 1);
  run(&f, &format!("globalThis.__b = {make};"));
  assert_eq!(f.state.open_spills(), 1);
}

/// what the oracle cannot see: the content was on disk, and disposing gives the disk back
#[test]
fn disposing_a_spilled_blob_unlinks_it_even_while_slices_remain() {
  let f = setup("dispose-root");
  run(
    &f,
    &format!(
      "globalThis.__b = new Blob([new Uint8Array({}).fill(1)]); globalThis.__s = __b.slice(0, 4);",
      SPILL_THRESHOLD_BYTES + 1,
    ),
  );
  assert_eq!(f.dir.entries().len(), 1);
  run(&f, "__b.dispose();");
  assert!(f.dir.entries().is_empty());
  assert_eq!(f.state.spilled_bytes(), 0);
}

#[test]
fn a_memory_blob_gives_its_charge_back_when_it_is_disposed() {
  let f = setup("dispose-charge");
  run(&f, "globalThis.__b = new Blob([new Uint8Array(1024)]);");
  assert_eq!(f.state.charged_bytes(), 1024);
  run(&f, "__b.dispose();");
  assert_eq!(f.state.charged_bytes(), 0);
}

/// the case dispose exists for: an unreachable cycle refcounting never frees, which nothing
/// about the js heap gives quickjs a reason to sweep
#[test]
fn the_finalizer_frees_a_collected_blob() {
  let f = setup("finalizer");
  run(
    &f,
    &format!(
      r#"
        globalThis.cycle = {{ blob: new Blob([new Uint8Array({}).fill(2)]) }};
        cycle.self = cycle;
      "#,
      SPILL_THRESHOLD_BYTES + 1,
    ),
  );
  assert_eq!(f.dir.entries().len(), 1);
  run(&f, "globalThis.cycle = null;");
  assert_eq!(f.dir.entries().len(), 1, "an unreachable cycle is not freed by dropping it");
  f._rt.run_gc();
  assert!(f.dir.entries().is_empty(), "a collected blob unlinks its spill");
  assert_eq!(f.state.spilled_bytes(), 0);
}

#[test]
fn dropping_the_engine_removes_every_spill_it_made() {
  let dir = TestDir::new("teardown");
  {
    let (_rt, ctx) = crate::testing::harness::new_engine();
    ctx.with(|ctx| {
      crate::api::error::install_plugin_error(&ctx).unwrap();
      install(&ctx, dir.path(), ExternalMemory::new()).unwrap();
      ctx
        .eval::<(), _>(format!(
          r#"
            globalThis.kept = [];
            for (let i = 0; i < 3; i++) kept.push(new Blob([new Uint8Array({})]));
          "#,
          SPILL_THRESHOLD_BYTES + 1,
        ))
        .unwrap();
    });
    assert_eq!(dir.entries().len(), 3);
  }
  assert!(dir.entries().is_empty());
}

#[test]
fn a_spill_unlinked_underneath_the_engine_still_reads() {
  let f = setup("unlinked");
  run(&f, &format!("globalThis.__b = new Blob(['head', new Uint8Array({})]);", SPILL_THRESHOLD_BYTES));
  let path = f.dir.entries()[0].clone();
  fs::remove_file(&path).unwrap();
  assert!(!path.exists());
  assert_eq!(
    settle(&f, "__b.slice(0, 4).text()"),
    "ok:head",
    "the descriptor is held for the backing's life, so cache eviction cannot break a read",
  );
}

fn write_app_file(dir: &TestDir, name: &str, content: &[u8]) -> PathBuf {
  let path = dir.path().join(name);
  fs::write(&path, content).unwrap();
  path
}

fn mint_test_app_file(f: &Fixture, path: &Path, size: u64, name: Option<&str>) {
  let mtime = mtime_millis(fs::metadata(path).unwrap().modified().ok());
  f.ctx.with(|ctx| {
    let value = mint_app_file(&ctx, path, size, "image/JPEG", name, mtime).unwrap();
    ctx.globals().set("__f", value).unwrap();
  });
}

#[test]
fn an_app_file_that_vanishes_fails_reads_and_keeps_answering_metadata() {
  let f = setup("app-file");
  let path = write_app_file(&f.dir, "photo.jpg", b"0123456789");
  mint_test_app_file(&f, &path, 10, Some("photo/of/a/dog.jpg"));

  assert_eq!(settle(&f, "__f.slice(2, 5).text()"), "ok:234");
  assert_eq!(f.state.charged_bytes(), 0, "the app's own file costs no budget");
  assert_eq!(
    eval(&f, "[__f.size, __f.type, __f.name, __f instanceof File].join('|')"),
    "10|image/jpeg|photo:of:a:dog.jpg|true",
  );

  fs::remove_file(&path).unwrap();
  assert_eq!(
    settle(&f, "__f.bytes()"),
    "PluginError:handle-expired:the file behind this blob is gone or has been replaced",
  );
  assert_eq!(
    eval(&f, "[__f.size, __f.type, __f.name].join('|')"),
    "10|image/jpeg|photo:of:a:dog.jpg",
    "metadata was answered from the moment it was handed over and does not start lying",
  );
}

#[test]
fn an_app_file_replaced_with_other_content_reads_as_gone() {
  let f = setup("app-file-replaced");
  let path = write_app_file(&f.dir, "media.bin", b"0123456789");
  mint_test_app_file(&f, &path, 10, None);
  assert_eq!(settle(&f, "__f.text()"), "ok:0123456789");

  // stock re-downloads into the same path, so "replaced" has to be the same event as "deleted"
  std::thread::sleep(std::time::Duration::from_millis(20));
  fs::write(&path, b"9876543210").unwrap();
  assert_eq!(
    settle(&f, "__f.text()"),
    "PluginError:handle-expired:the file behind this blob is gone or has been replaced",
  );

  let f2 = setup("app-file-truncated");
  let path = write_app_file(&f2.dir, "short.bin", b"0123");
  mint_test_app_file(&f2, &path, 10, None);
  assert_eq!(
    settle(&f2, "__f.text()"),
    "PluginError:handle-expired:the file behind this blob is gone or has been replaced",
    "fewer bytes than `size` promised is a lie, not a short read",
  );
}

#[test]
fn an_app_file_blob_is_not_a_file_without_a_name() {
  let f = setup("app-file-anonymous");
  let path = write_app_file(&f.dir, "anon.bin", b"abc");
  mint_test_app_file(&f, &path, 3, None);
  assert_eq!(
    eval(&f, "[__f instanceof Blob, __f instanceof File, String(__f.name)].join('|')"),
    "true|false|undefined",
  );
}

/// the app's own media is minted by the host, so what makes it a `File` must not be something
/// a plugin can reassign out from under it
#[test]
fn a_reassigned_file_global_cannot_decide_what_an_app_file_is() {
  let f = setup("proto-swap");
  run(&f, "globalThis.Fake = function Fake() {}; globalThis.File = Fake;");
  let path = write_app_file(&f.dir, "media.jpg", b"abc");
  mint_test_app_file(&f, &path, 3, Some("media.jpg"));
  assert_eq!(eval(&f, "[__f instanceof Fake, __f.name, __f.size].join('|')"), "false|media.jpg|3",);
}

#[test]
fn a_subclass_of_blob_keeps_its_prototype() {
  let f = setup("subclass");
  let out = eval(
    &f,
    r#"
      class Mine extends Blob { get label() { return 'mine'; } }
      const m = new Mine(['xy']);
      [m instanceof Mine, m instanceof Blob, m.label, m.size].join('|');
    "#,
  );
  assert_eq!(out, "true|true|mine|2");
}

#[test]
fn the_constructor_refuses_what_is_not_a_list_of_parts() {
  let f = setup("bad-parts");
  let out = eval(
    &f,
    r#"
      const refused = (fn) => { try { fn(); return 'no-throw'; } catch (e) { return e.constructor.name; } };
      JSON.stringify([
        refused(() => new Blob('not an array')),
        refused(() => new File(['x'])),
        new Blob([1, {}, null]).size,
      ]);
    "#,
  );
  // a non-BufferSource part is stringified, exactly as the spec says
  assert_eq!(out, r#"["TypeError","TypeError",20]"#);
}

#[test]
fn a_disposed_blob_refuses_every_use() {
  let f = setup("disposed-use");
  run(&f, "globalThis.__b = new Blob(['abc']); __b.dispose();");
  let out = eval(
    &f,
    r#"
      const code = (fn) => { try { fn(); return 'no-throw'; } catch (e) { return e.code; } };
      JSON.stringify([
        code(() => __b.size), code(() => __b.type), code(() => __b.slice(0, 1)),
        code(() => new Blob([__b])),
      ]);
    "#,
  );
  assert_eq!(out, r#"["handle-expired","handle-expired","handle-expired","handle-expired"]"#,);
}

/// an export is taken from a *handle*, so it reaches that handle's range and nothing else: a
/// plugin handing over a four-byte header sliced off a download did not hand over the download
#[test]
fn an_export_reads_only_its_handles_range_and_a_disposed_blob_has_none() {
  let f = setup("export-range");
  run(&f, "globalThis.__b = new Blob(['0123456789']); globalThis.__s = __b.slice(3, 7);");
  let export = f.ctx.with(|ctx| export_blob(&ctx.globals().get::<_, Value>("__s").unwrap()).unwrap());
  assert_eq!(export.len(), 4);
  assert_eq!(export.read(0, 4).ok().as_deref(), Some(&b"3456"[..]));
  assert_eq!(export.read(1, 2).ok().as_deref(), Some(&b"45"[..]));
  assert!(export.read(0, 5).is_err(), "an export must not reach past its own end");
  assert!(export.read(4, 1).is_err());
  assert!(export.read(u64::MAX, 1).is_err(), "and must not wrap into one that can");

  run(&f, "__b.dispose();");
  assert!(f.ctx.with(|ctx| export_blob(&ctx.globals().get::<_, Value>("__b").unwrap()).is_none()));
}

#[test]
fn the_bundled_blob_test_plugin_passes() {
  const ORACLE: &str = crate::testing::test_plugin!("blob-test.js");
  let (rt, ctx) = crate::testing::harness::new_engine();
  let dir = TestDir::new("oracle");
  ctx.with(|ctx| {
    crate::api::error::install_plugin_error(&ctx).unwrap();
    crate::testing::harness::install_sandbox_globals(&ctx, dir.path()).unwrap();
  });
  let lines = crate::testing::harness::run_capturing_console(&rt, &ctx, ORACLE);
  crate::testing::harness::assert_oracle_exact(&lines, "blob test done", 52);
}

/// a handle is ~100 bytes of js heap, so an uncapped label is a plugin turning that into
/// arbitrarily much process memory the engine charges to nobody.
#[test]
fn a_label_cannot_carry_unbounded_content_into_the_handle() {
  let huge = "a".repeat(4 * 1024 * 1024);
  assert_eq!(normalize_mime(&huge).len(), LABEL_LIMIT_CHARS);
  assert_eq!(sanitize_name(&huge).len(), LABEL_LIMIT_CHARS);
}

#[test]
fn a_label_truncated_mid_character_stays_valid_utf8() {
  let wide = "\u{1F436}".repeat(LABEL_LIMIT_CHARS);
  assert!(sanitize_name(&wide).chars().count() <= LABEL_LIMIT_CHARS);
}

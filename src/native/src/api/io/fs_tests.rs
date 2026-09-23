use super::*;
use crate::sandbox::grants::CachedGrantHost;
use crate::testing::harness::{install_sandbox_globals, TestDir};
use rquickjs::{Context, Runtime};

struct Fixture {
  _rt: Runtime,
  ctx: Context,
  dir: TestDir,
  /// separate from the fs root, as it is on a device: a spilled blob lives in the cache area
  /// and must not be charged against the plugin's durable quota
  _spill: TestDir,
  _outside: TestDir,
  outside_path: PathBuf,
  state: Rc<FsState>,
}

fn setup(name: &str, grants: &[&str], quota: u64, unscoped: bool) -> Fixture {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let dir = TestDir::new(name);
  let spill = TestDir::new(&format!("{name}-spill"));
  let outside = TestDir::new(&format!("{name}-outside"));
  let outside_path = outside.path().canonicalize().unwrap();
  fs::write(outside_path.join("secret.txt"), b"a secret").unwrap();
  let grants = CachedGrantHost::new(grants);
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    install_sandbox_globals(&ctx, spill.path()).unwrap();
    install_fs(&ctx, grants, dir.path(), quota, unscoped, TEST_ANDROID_DIRS, &inu).unwrap()
  });
  Fixture {
    _rt: rt,
    ctx,
    dir,
    _spill: spill,
    _outside: outside,
    outside_path,
    state,
  }
}

fn scoped(name: &str) -> Fixture {
  setup(name, &["fs"], DEFAULT_QUOTA_BYTES, false)
}

#[test]
fn the_app_directories_answer_what_the_host_gave() {
  let fixture = setup("android-dirs", &["unsafe.fs"], UNCAPPED, true);
  assert_eq!(eval(&fixture, "inu.android.getPluginsDir()"), "/data/plugins");
  assert_eq!(eval(&fixture, "inu.android.getCacheDir()"), "/data/cache");
  assert_eq!(eval(&fixture, "inu.android.getMediaDir('files')"), "/media/files");
  assert_eq!(eval(&fixture, "inu.android.getMediaDir('documents')"), "/media/documents");
  // a media kind the app has not made a directory for is a real answer, not a failure
  assert_eq!(
    caught(&fixture, "inu.android.getMediaDir('videos')"),
    "not-found|getMediaDir: the app has no such directory",
  );
  assert_eq!(
    caught(&fixture, "inu.android.getMediaDir('downloads')"),
    "invalid-argument|getMediaDir: 'downloads' is not one of files, images, videos, audios, documents",
  );
}

fn eval(fixture: &Fixture, code: &str) -> String {
  crate::testing::harness::eval_string(&fixture.ctx, code)
}

fn caught(fixture: &Fixture, code: &str) -> String {
  eval(
    fixture,
    &format!(
      r#"
        (() => {{
          try {{ {code}; return 'no-throw' }}
          catch (e) {{ return `${{e.code}}|${{e.message}}` }}
        }})()
      "#,
    ),
  )
}

fn catch_error_code(fixture: &Fixture, code: &str) -> String {
  caught(fixture, code).split('|').next().unwrap().to_string()
}

/// `cargo test` runs these on several threads, so the suite root is made and unmade
/// concurrently. Nothing here asserts on `inu.fs`: what it defends is every *other* test in the
/// binary, since a fixture that panics while building itself fails whichever one was unlucky.
#[test]
fn fixtures_built_and_dropped_concurrently_share_one_root() {
  let threads: Vec<_> = (0..8)
    .map(|n| {
      std::thread::spawn(move || {
        for round in 0..40 {
          let dir = TestDir::new(&format!("race-{n}-{round}"));
          assert!(dir.path().is_dir(), "{}", dir.path().display());
        }
      })
    })
    .collect();
  for thread in threads {
    thread.join().expect("a fixture failed to build under contention");
  }
}

#[test]
fn a_refused_dot_dot_write_lands_nothing_outside() {
  let f = scoped("dotdot");
  assert_eq!(catch_error_code(&f, "inu.fs.write('../escaped.txt', new Uint8Array([1]))"), "not-granted");
  assert!(!f.dir.path().parent().unwrap().join("escaped.txt").exists());
}

#[test]
fn a_symlink_out_of_the_directory_is_refused_after_it_resolves() {
  let f = scoped("symlink");
  // a plugin can plant this itself: a link is a file it is allowed to create
  std::os::unix::fs::symlink(&f.outside_path, f.dir.path().join("out")).unwrap();
  assert_eq!(catch_error_code(&f, "inu.fs.read('out/secret.txt')"), "not-granted");
  assert_eq!(catch_error_code(&f, "inu.fs.readdir('out')"), "not-granted");
  assert_eq!(catch_error_code(&f, "inu.fs.write('out/planted.txt', new Uint8Array([1]))"), "not-granted");
  assert!(!f.outside_path.join("planted.txt").exists(), "nothing may be written through a link");
}

#[test]
fn a_symlink_to_a_single_file_outside_is_refused_too() {
  let f = scoped("symlink-file");
  std::os::unix::fs::symlink(f.outside_path.join("secret.txt"), f.dir.path().join("s.txt")).unwrap();
  assert_eq!(catch_error_code(&f, "inu.fs.read('s.txt')"), "not-granted");
  assert_eq!(catch_error_code(&f, "inu.fs.stat('s.txt')"), "not-granted");
  // `exists` too, or it is an oracle for what is on the other side of the link
  assert_eq!(catch_error_code(&f, "inu.fs.exists('s.txt')"), "not-granted");
}

#[test]
fn a_symlink_inside_the_directory_still_works() {
  let f = scoped("symlink-inside");
  fs::create_dir(f.dir.path().join("real")).unwrap();
  fs::write(f.dir.path().join("real/x.txt"), b"inside").unwrap();
  std::os::unix::fs::symlink("real", f.dir.path().join("link")).unwrap();
  assert_eq!(eval(&f, "new TextDecoder().decode(inu.fs.read('link/x.txt'))"), "inside");
}

/// `..` after a link means the parent of where the link *led*, which is what the kernel does and
/// what a lexical pass would get wrong in the plugin's favour
#[test]
fn a_dot_dot_after_a_symlink_pops_the_resolved_parent() {
  let f = scoped("symlink-dotdot");
  fs::create_dir(f.dir.path().join("real")).unwrap();
  std::os::unix::fs::symlink(&f.outside_path, f.dir.path().join("real/out")).unwrap();
  // lexically 'real/out/..' is 'real'; through the link it is the outside directory's parent
  assert_eq!(catch_error_code(&f, "inu.fs.readdir('real/out/../')"), "not-granted");
}

#[test]
fn a_symlink_cycle_terminates() {
  let f = scoped("symlink-cycle");
  std::os::unix::fs::symlink("b", f.dir.path().join("a")).unwrap();
  std::os::unix::fs::symlink("a", f.dir.path().join("b")).unwrap();
  assert_eq!(catch_error_code(&f, "inu.fs.read('a')"), "invalid-argument");
}

#[test]
fn an_absolute_path_naming_the_own_directory_is_still_refused() {
  let f = scoped("absolute");
  let own = format!("{}/a.txt", f.dir.path().display());
  assert_eq!(catch_error_code(&f, &format!("inu.fs.write({own:?}, new Uint8Array([1]))")), "not-granted");
}

/// a sibling whose name merely starts with the root's is not inside it, which a string prefix
/// check would get wrong
#[test]
fn a_sibling_with_the_roots_name_as_a_prefix_is_outside() {
  let f = scoped("prefix");
  let sibling = PathBuf::from(format!("{}-evil", f.dir.path().display()));
  fs::create_dir_all(&sibling).unwrap();
  fs::write(sibling.join("x.txt"), b"nope").unwrap();
  let escape = format!("../{}-evil/x.txt", f.dir.path().file_name().unwrap().to_string_lossy());
  let got = catch_error_code(&f, &format!("inu.fs.read({escape:?})"));
  let _ = fs::remove_dir_all(&sibling);
  assert_eq!(got, "not-granted");
}

#[test]
fn unscoped_mode_reaches_outside_and_relative_paths_still_land_inside() {
  let f = setup("unscoped", &["unsafe.fs"], UNCAPPED, true);
  let secret = f.outside_path.join("secret.txt");
  assert_eq!(
    eval(&f, &format!("new TextDecoder().decode(inu.fs.read({:?}))", secret.to_string_lossy())),
    "a secret",
  );
  eval(&f, "inu.fs.write('own.txt', new Uint8Array([1])); 'ok'");
  assert!(f.dir.path().join("own.txt").exists(), "a relative path is still the plugin's own directory",);
  assert_eq!(eval(&f, "String(inu.fs.quota())"), "Infinity");
}

#[test]
fn the_unscoped_mode_asks_for_the_unsafe_grant() {
  // the host installed it unscoped, but the manifest only carries the safe token
  let f = setup("unscoped-ungranted", &["fs"], UNCAPPED, true);
  assert_eq!(caught(&f, "inu.fs.read('x')"), "not-granted|missing grant: unsafe.fs");
}

#[test]
fn the_scoped_mode_asks_for_the_plain_grant() {
  let f = setup("scoped-ungranted", &["unsafe.fs"], DEFAULT_QUOTA_BYTES, false);
  assert_eq!(caught(&f, "inu.fs.read('x')"), "not-granted|missing grant: fs");
}

#[test]
fn every_member_is_gated() {
  let f = setup("ungranted", &[], DEFAULT_QUOTA_BYTES, false);
  for call in [
    "inu.fs.read('a')",
    "inu.fs.write('a', new Uint8Array([1]))",
    "inu.fs.append('a', new Uint8Array([1]))",
    "inu.fs.mkdir('a')",
    "inu.fs.rm('a')",
    "inu.fs.exists('a')",
    "inu.fs.readdir('.')",
    "inu.fs.stat('a')",
    "inu.fs.copy('a', 'b')",
    "inu.fs.move('a', 'b')",
    "inu.fs.usage()",
    "inu.fs.quota()",
  ] {
    assert_eq!(catch_error_code(&f, call), "not-granted", "'{call}' is not gated");
  }
  assert!(f.dir.path().read_dir().unwrap().next().is_none(), "and none of them touched the disk",);
}

#[test]
fn a_spilled_blob_writes_through_without_materializing() {
  let f = scoped("blob-spill");
  let got = eval(
    &f,
    r#"
      const big = new Uint8Array(3 * 1024 * 1024)
      for (let i = 0; i < big.length; i++) big[i] = i % 251
      inu.fs.write('big.bin', new Blob(['head', big]))
      const back = inu.fs.stat('big.bin')
      const tail = inu.fs.read('big.bin')
      const head = new TextDecoder().decode(tail.subarray(0, 4))
      back.size + '|' + head + '|' + tail[4 + 1024]
    "#,
  );
  assert_eq!(got, format!("{}|head|{}", 3 * 1024 * 1024 + 4, 1024 % 251));
}

#[test]
fn listing_what_is_not_there_is_not_found() {
  let f = scoped("missing");
  assert_eq!(catch_error_code(&f, "inu.fs.readdir('nope')"), "not-found");
}

#[test]
fn writing_an_app_file_blob_whose_file_is_gone_is_an_expired_handle() {
  let f = scoped("gone-source");
  let source = f.outside_path.join("media.bin");
  fs::write(&source, b"0123456789").unwrap();
  f.ctx.with(|ctx| {
    let blob = crate::api::io::blob::mint_app_file_at(&ctx, &source, "application/octet-stream").unwrap();
    ctx.globals().set("__f", blob).unwrap();
  });
  fs::remove_file(&source).unwrap();
  assert_eq!(catch_error_code(&f, "inu.fs.write('copy.bin', __f)"), "handle-expired");
}

#[test]
fn a_directory_is_not_a_file() {
  let f = scoped("dir-read");
  eval(&f, "inu.fs.mkdir('d'); 'ok'");
  assert_eq!(catch_error_code(&f, "inu.fs.read('d')"), "invalid-argument");
  assert_eq!(catch_error_code(&f, "inu.fs.copy('d', 'e')"), "invalid-argument");
  assert_eq!(catch_error_code(&f, "inu.fs.write('d', new Uint8Array([1]))"), "invalid-argument");
}

#[test]
fn usage_tracks_what_is_stored_and_a_rewrite_is_charged_for_its_difference() {
  let f = setup("usage", &["fs"], 4096, false);
  let got = eval(
    &f,
    r#"
      const out = [inu.fs.usage()]
      inu.fs.write('a.bin', new Uint8Array(1000))
      out.push(inu.fs.usage())
      inu.fs.write('a.bin', new Uint8Array(10))
      out.push(inu.fs.usage())
      inu.fs.append('a.bin', new Uint8Array(5))
      out.push(inu.fs.usage())
      inu.fs.copy('a.bin', 'b.bin')
      out.push(inu.fs.usage())
      inu.fs.rm('b.bin')
      out.push(inu.fs.usage())
      out.push(inu.fs.quota())
      JSON.stringify(out)
    "#,
  );
  assert_eq!(got, "[0,1000,10,15,30,15,4096]");
}

/// a rewrite that shrinks a file must not be charged for its whole size, or a plugin holding a
/// file at the cap could never replace it
#[test]
fn rewriting_the_biggest_file_in_place_is_allowed() {
  let f = setup("rewrite", &["fs"], 4096, false);
  eval(&f, "inu.fs.write('a.bin', new Uint8Array(4000)); 'ok'");
  assert_eq!(catch_error_code(&f, "inu.fs.write('a.bin', new Uint8Array(4096))"), "no-throw");
  assert_eq!(eval(&f, "String(inu.fs.usage())"), "4096");
}

#[test]
fn a_copy_past_the_quota_is_refused() {
  let f = setup("copy-quota", &["fs"], 150, false);
  eval(&f, "inu.fs.write('a.bin', new Uint8Array(100)); 'ok'");
  assert_eq!(catch_error_code(&f, "inu.fs.copy('a.bin', 'b.bin')"), "quota-exceeded");
  assert_eq!(eval(&f, "String(inu.fs.exists('b.bin'))"), "false");
}

/// the cache is a shortcut, never the truth: a total the module did not compute itself has to
/// come back from the tree
#[test]
fn the_cached_total_is_dropped_by_anything_whose_delta_is_not_obvious() {
  let f = setup("usage-cache", &["fs"], 4096, false);
  eval(&f, "inu.fs.write('a.bin', new Uint8Array(100)); inu.fs.usage(); 'ok'");
  assert_eq!(f.state.usage.get(), Some(100));
  eval(&f, "inu.fs.copy('a.bin', 'b.bin'); 'ok'");
  assert_eq!(f.state.usage.get(), None, "a copy leaves the total to be walked again");
  assert_eq!(eval(&f, "String(inu.fs.usage())"), "200");
  eval(&f, "inu.fs.move('b.bin', 'c.bin'); 'ok'");
  assert_eq!(f.state.usage.get(), None);
  assert_eq!(eval(&f, "String(inu.fs.usage())"), "200");
}

#[test]
fn unscoped_mode_has_no_cap_at_all() {
  let f = setup("unscoped-quota", &["unsafe.fs"], UNCAPPED, true);
  assert_eq!(catch_error_code(&f, "inu.fs.write('a.bin', new Uint8Array(200000))"), "no-throw");
  assert_eq!(eval(&f, "String(inu.fs.quota())"), "Infinity");
  assert_eq!(eval(&f, "String(inu.fs.usage())"), "200000", "usage still answers for the tree");
}

#[test]
fn a_read_bigger_than_one_materialization_is_refused_before_it_reads() {
  let f = setup("read-ceiling", &["unsafe.fs"], UNCAPPED, true);
  let path = f.dir.path().join("huge.bin");
  // sparse: the ceiling is checked against the size, so nothing here has to be written
  let file = fs::File::create(&path).unwrap();
  file.set_len(MATERIALIZE_LIMIT_BYTES + 1).unwrap();
  drop(file);
  assert_eq!(catch_error_code(&f, "inu.fs.read('huge.bin')"), "quota-exceeded");
}

#[test]
fn an_engine_without_a_directory_fails_every_call_rather_than_landing_elsewhere() {
  let (_rt, ctx) = crate::testing::harness::new_engine();
  let spill = TestDir::new("no-root-spill");
  let grants = CachedGrantHost::new(["fs"]);
  ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    install_sandbox_globals(&ctx, spill.path()).unwrap();
    install_fs(&ctx, grants, Path::new(""), DEFAULT_QUOTA_BYTES, false, TEST_ANDROID_DIRS, &inu).unwrap();
    for call in ["inu.fs.write('a', new Uint8Array([1]))", "inu.fs.read('a')", "inu.fs.usage()"] {
      let got: String = ctx
        .eval(format!(r#"(() => {{ try {{ {call}; return 'no-throw' }} catch (e) {{ return e.code }} }})()"#,))
        .unwrap();
      assert_eq!(got, "internal", "'{call}'");
    }
  });
}

/// the fs oracle's only other run is on a device
#[test]
fn the_bundled_fs_test_plugin_passes() {
  const ORACLE: &str = crate::testing::test_plugin!("fs-test.js");
  let (rt, ctx) = crate::testing::harness::new_engine();
  let dir = TestDir::new("oracle");
  let spill = TestDir::new("oracle-spill");
  ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    install_sandbox_globals(&ctx, spill.path()).unwrap();
    let grants = CachedGrantHost::new(["fs"]);
    install_fs(&ctx, grants, dir.path(), 64 * 1024, false, TEST_ANDROID_DIRS, &inu).unwrap();
  });
  let lines = crate::testing::harness::run_capturing_console(&rt, &ctx, ORACLE);
  crate::testing::harness::assert_oracle_exact(&lines, "fs test done", 60);
}

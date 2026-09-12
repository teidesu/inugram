use super::*;
use crate::api::error::install_plugin_error;
use crate::api::globals::{install_globals, RandomHost};
use crate::sandbox::grants::TestGrantHost;
use rquickjs::{Context, Runtime};
use std::sync::atomic::{AtomicU64, Ordering};

/// the sandbox globals this surface is used next to (`TextEncoder`, `Blob`), installed the way
/// the engine installs them - a fixture that stubbed `Blob` would be testing its own stub
struct NoRandomness;

impl RandomHost for NoRandomness {
  fn random_bytes(&self, _out: &mut [u8]) -> bool {
    false
  }
}

pub(crate) fn install_sandbox_globals(ctx: &Ctx<'_>, spill_dir: &Path) -> JsResult<Rc<BlobState>> {
  install_globals(ctx, Rc::new(NoRandomness), spill_dir, crate::sandbox::limits::ExternalMemory::new())
}

static NEXT_DIR: AtomicU64 = AtomicU64::new(0);
static SUITE_ROOT: std::sync::OnceLock<PathBuf> = std::sync::OnceLock::new();
/// how many [`TestDir`]s are live, and the lock every create/remove of the suite root is taken
/// under. `cargo test` runs the fixtures on several threads at once, so making and unmaking the
/// shared parent has to be serialized: the kernel fails a `mkdir` whose parent is being unlinked
/// under it (EINVAL on apfs), which surfaced as ~3% of runs failing in whichever test happened
/// to be building a fixture at the time.
static LIVE_DIRS: std::sync::Mutex<usize> = std::sync::Mutex::new(0);

/// The one directory this process owns, and the parent of every fixture.
///
/// The escape assertions are of the shape "nothing landed in `<fixture>/..`", so a fixture
/// rooted straight in the machine's temp directory makes them read whatever anyone else left
/// there: an unrelated `out.txt` fails them, and one real escape poisons every later run.
fn suite_root() -> &'static Path {
  SUITE_ROOT.get_or_init(|| {
    let path = std::env::temp_dir().join(format!("inu-fs-suite-{}", std::process::id()));
    let _ = fs::remove_dir_all(&path);
    fs::create_dir_all(&path).unwrap();
    path
  })
}

/// a scratch directory that goes away with the test, failing path included
pub(crate) struct TestDir(PathBuf);

impl TestDir {
  pub(crate) fn new(name: &str) -> Self {
    let unique = NEXT_DIR.fetch_add(1, Ordering::Relaxed);
    let path = suite_root().join(format!("inu-fs-{name}-{unique}"));
    let mut live = LIVE_DIRS.lock().unwrap_or_else(|e| e.into_inner());
    let _ = fs::remove_dir_all(&path);
    fs::create_dir_all(&path).unwrap();
    *live += 1;
    TestDir(path)
  }

  pub(crate) fn path(&self) -> &Path {
    &self.0
  }
}

impl Drop for TestDir {
  fn drop(&mut self) {
    let _ = fs::remove_dir_all(&self.0);
    let mut live = LIVE_DIRS.lock().unwrap_or_else(|e| e.into_inner());
    *live -= 1;
    if *live == 0 {
      let _ = fs::remove_dir(suite_root());
    }
  }
}

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
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let dir = TestDir::new(name);
  let spill = TestDir::new(&format!("{name}-spill"));
  let outside = TestDir::new(&format!("{name}-outside"));
  let outside_path = outside.path().canonicalize().unwrap();
  fs::write(outside_path.join("secret.txt"), b"a secret").unwrap();
  let grants = TestGrantHost::new(grants).as_host();
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    install_plugin_error(&ctx).unwrap();
    let blobs = install_sandbox_globals(&ctx, spill.path()).unwrap();
    install_fs(&ctx, grants, blobs, dir.path(), quota, unscoped, TEST_ANDROID_DIRS, &inu).unwrap()
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

/// they hand over absolute paths outside the scoped root, which is the whole of what `unsafe.fs`
/// buys, so a plugin holding only `fs` is refused the *string* rather than the read it would go
/// on to fail
#[test]
fn the_app_directories_need_the_unscoped_grant() {
  let fixture = scoped("android-dirs-scoped");
  for call in ["inu.android.getPluginsDir()", "inu.android.getCacheDir()", "inu.android.getMediaDir('images')"] {
    assert_eq!(caught(&fixture, call), "not-granted|missing grant: unsafe.fs", "'{call}'");
  }
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

/// runs `code` and reports `<code>|<message>` of whatever it threw, or "no-throw"
fn caught(fixture: &Fixture, code: &str) -> String {
  eval(
    fixture,
    &format!(
      r#"(() => {{
                try {{ {code}; return 'no-throw' }}
                catch (e) {{ return `${{e.code}}|${{e.message}}` }}
            }})()"#,
    ),
  )
}

fn code_of(fixture: &Fixture, code: &str) -> String {
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
fn a_dot_dot_is_resolved_before_containment_is_checked() {
  let f = scoped("dotdot");
  // the classic bypass: the string starts with the root, the path does not stay in it
  assert_eq!(code_of(&f, "inu.fs.read('a/../../secret.txt')"), "not-granted");
  assert_eq!(code_of(&f, "inu.fs.write('../escaped.txt', new Uint8Array([1]))"), "not-granted");
  assert!(!f.dir.path().parent().unwrap().join("escaped.txt").exists());
}

#[test]
fn a_dot_dot_that_comes_back_is_allowed() {
  let f = scoped("dotdot-back");
  eval(&f, "inu.fs.write('a.txt', new TextEncoder().encode('hi')); 'ok'");
  assert_eq!(
    eval(&f, "new TextDecoder().decode(inu.fs.read('sub/../a.txt'))"),
    "hi",
    "resolving is not a ban on '..', only on where it lands",
  );
}

#[test]
fn doubled_separators_and_dots_normalize_away() {
  let f = scoped("separators");
  eval(&f, "inu.fs.mkdir('one/two'); inu.fs.write('one/two/x.txt', new Uint8Array([65])); 'ok'");
  for path in ["one//two//x.txt", "./one/./two/x.txt", "one/two//./x.txt", "one/two/../two/x.txt"] {
    assert_eq!(
      eval(&f, &format!("String(inu.fs.read('{path}')[0])")),
      "65",
      "'{path}' must resolve to the same file",
    );
  }
}

#[test]
fn a_symlink_out_of_the_directory_is_refused_after_it_resolves() {
  let f = scoped("symlink");
  // a plugin can plant this itself: a link is a file it is allowed to create
  std::os::unix::fs::symlink(&f.outside_path, f.dir.path().join("out")).unwrap();
  assert_eq!(code_of(&f, "inu.fs.read('out/secret.txt')"), "not-granted");
  assert_eq!(code_of(&f, "inu.fs.readdir('out')"), "not-granted");
  assert_eq!(code_of(&f, "inu.fs.write('out/planted.txt', new Uint8Array([1]))"), "not-granted");
  assert!(!f.outside_path.join("planted.txt").exists(), "nothing may be written through a link");
}

#[test]
fn a_symlink_to_a_single_file_outside_is_refused_too() {
  let f = scoped("symlink-file");
  std::os::unix::fs::symlink(f.outside_path.join("secret.txt"), f.dir.path().join("s.txt")).unwrap();
  assert_eq!(code_of(&f, "inu.fs.read('s.txt')"), "not-granted");
  assert_eq!(code_of(&f, "inu.fs.stat('s.txt')"), "not-granted");
  // `exists` too, or it is an oracle for what is on the other side of the link
  assert_eq!(code_of(&f, "inu.fs.exists('s.txt')"), "not-granted");
}

#[test]
fn a_symlink_inside_the_directory_still_works() {
  let f = scoped("symlink-inside");
  fs::create_dir(f.dir.path().join("real")).unwrap();
  fs::write(f.dir.path().join("real/x.txt"), b"inside").unwrap();
  std::os::unix::fs::symlink(f.dir.path().join("real"), f.dir.path().join("link")).unwrap();
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
  assert_eq!(code_of(&f, "inu.fs.readdir('real/out/../')"), "not-granted");
}

#[test]
fn a_symlink_cycle_terminates() {
  let f = scoped("symlink-cycle");
  std::os::unix::fs::symlink(f.dir.path().join("b"), f.dir.path().join("a")).unwrap();
  std::os::unix::fs::symlink(f.dir.path().join("a"), f.dir.path().join("b")).unwrap();
  assert_eq!(code_of(&f, "inu.fs.read('a')"), "invalid-argument");
}

#[test]
fn an_absolute_path_is_refused_outright() {
  let f = scoped("absolute");
  let secret = f.outside_path.join("secret.txt");
  assert_eq!(code_of(&f, &format!("inu.fs.read({:?})", secret.to_string_lossy())), "not-granted");
  assert_eq!(code_of(&f, "inu.fs.read('/etc/hosts')"), "not-granted");
  // including one that names the plugin's own directory: it is still the shape the scoped
  // mode does not take, and accepting it would make the mode's rule "sometimes"
  let own = format!("{}/a.txt", f.dir.path().display());
  assert_eq!(code_of(&f, &format!("inu.fs.write({own:?}, new Uint8Array([1]))")), "not-granted");
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
  let got = code_of(&f, &format!("inu.fs.read({escape:?})"));
  let _ = fs::remove_dir_all(&sibling);
  assert_eq!(got, "not-granted");
}

#[test]
fn a_path_that_cannot_name_anything_is_invalid() {
  let f = scoped("bad-paths");
  assert_eq!(code_of(&f, "inu.fs.read('')"), "invalid-argument");
  assert_eq!(code_of(&f, "inu.fs.read('a\\u0000b')"), "invalid-argument");
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
    assert_eq!(code_of(&f, call), "not-granted", "'{call}' is not gated");
  }
  assert!(f.dir.path().read_dir().unwrap().next().is_none(), "and none of them touched the disk",);
}

#[test]
fn a_round_trip_keeps_the_bytes() {
  let f = scoped("round-trip");
  let got = eval(
    &f,
    r#"
        inu.fs.write('a.bin', new Uint8Array([0, 1, 255]))
        inu.fs.append('a.bin', new Uint8Array([7]))
        Array.from(inu.fs.read('a.bin')).join(',')
        "#,
  );
  assert_eq!(got, "0,1,255,7");
}

#[test]
fn a_blob_is_written_without_crossing_into_js() {
  let f = scoped("blob-write");
  let got = eval(
    &f,
    r#"
        inu.fs.write('b.txt', new Blob(['hello ', 'world']))
        inu.fs.append('b.txt', new Blob(['!']))
        new TextDecoder().decode(inu.fs.read('b.txt'))
        "#,
  );
  assert_eq!(got, "hello world!");
}

#[test]
fn a_slice_writes_its_own_range_and_not_the_whole_backing() {
  let f = scoped("blob-slice");
  let got = eval(
    &f,
    r#"
        inu.fs.write('s.txt', new Blob(['0123456789']).slice(2, 5))
        new TextDecoder().decode(inu.fs.read('s.txt'))
        "#,
  );
  assert_eq!(got, "234");
}

#[test]
fn a_disposed_blob_cannot_be_written() {
  let f = scoped("blob-disposed");
  assert_eq!(code_of(&f, "const b = new Blob(['x']); b.dispose(); inu.fs.write('d.txt', b)"), "handle-expired",);
  assert_eq!(eval(&f, "String(inu.fs.exists('d.txt'))"), "false");
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
fn write_takes_only_a_blob_or_bytes() {
  let f = scoped("write-type");
  assert_eq!(code_of(&f, "inu.fs.write('x', 'a string')"), "invalid-argument");
  assert_eq!(code_of(&f, "inu.fs.write('x', [1, 2, 3])"), "invalid-argument");
  assert_eq!(code_of(&f, "inu.fs.write('x', null)"), "invalid-argument");
}

#[test]
fn mkdir_makes_parents_and_readdir_names_only_children() {
  let f = scoped("mkdir");
  let got = eval(
    &f,
    r#"
        inu.fs.mkdir('a/b/c')
        inu.fs.write('a/b/c/z.txt', new Uint8Array([1]))
        inu.fs.write('a/b/c/a.txt', new Uint8Array([1]))
        JSON.stringify([inu.fs.readdir('a/b/c'), inu.fs.readdir('a')])
        "#,
  );
  assert_eq!(got, r#"[["a.txt","z.txt"],["b"]]"#);
}

#[test]
fn stat_tells_a_file_from_a_directory() {
  let f = scoped("stat");
  let got = eval(
    &f,
    r#"
        inu.fs.mkdir('d')
        inu.fs.write('d/f.txt', new Uint8Array([1, 2, 3]))
        const file = inu.fs.stat('d/f.txt')
        const dir = inu.fs.stat('d')
        JSON.stringify([file.isFile, file.isDirectory, file.size, dir.isFile, dir.isDirectory, file.mtime > 0])
        "#,
  );
  assert_eq!(got, "[true,false,3,false,true,true]");
}

#[test]
fn rm_is_idempotent_and_needs_a_flag_for_a_directory() {
  let f = scoped("rm");
  let got = eval(
    &f,
    r#"
        inu.fs.mkdir('d/e')
        inu.fs.write('d/e/f.txt', new Uint8Array([1]))
        const out = []
        try { inu.fs.rm('d') } catch (e) { out.push(e.code) }
        inu.fs.rm('d', { recursive: true })
        out.push(inu.fs.exists('d'))
        inu.fs.rm('d')
        inu.fs.rm('never-existed')
        out.push('idempotent')
        JSON.stringify(out)
        "#,
  );
  assert_eq!(got, r#"["invalid-argument",false,"idempotent"]"#);
}

#[test]
fn the_plugins_own_directory_cannot_be_removed() {
  let f = scoped("rm-root");
  assert_eq!(code_of(&f, "inu.fs.rm('.', { recursive: true })"), "invalid-argument");
  assert!(f.dir.path().is_dir());
}

#[test]
fn copy_and_move_stay_inside() {
  let f = scoped("copy-move");
  let got = eval(
    &f,
    r#"
        inu.fs.write('a.txt', new TextEncoder().encode('content'))
        inu.fs.copy('a.txt', 'b.txt')
        inu.fs.move('b.txt', 'c.txt')
        JSON.stringify([
          inu.fs.exists('a.txt'), inu.fs.exists('b.txt'), inu.fs.exists('c.txt'),
          new TextDecoder().decode(inu.fs.read('c.txt')),
        ])
        "#,
  );
  assert_eq!(got, r#"[true,false,true,"content"]"#);
  assert_eq!(code_of(&f, "inu.fs.copy('a.txt', '../out.txt')"), "not-granted");
  assert_eq!(code_of(&f, "inu.fs.move('a.txt', '../out.txt')"), "not-granted");
  assert!(!f.dir.path().parent().unwrap().join("out.txt").exists());
  assert_eq!(code_of(&f, "inu.fs.copy('missing.txt', 'x.txt')"), "not-found");
  assert_eq!(code_of(&f, "inu.fs.move('missing.txt', 'x.txt')"), "not-found");
}

#[test]
fn reading_what_is_not_there_is_not_found() {
  let f = scoped("missing");
  assert_eq!(code_of(&f, "inu.fs.read('nope.txt')"), "not-found");
  assert_eq!(code_of(&f, "inu.fs.stat('nope.txt')"), "not-found");
  assert_eq!(code_of(&f, "inu.fs.readdir('nope')"), "not-found");
  assert_eq!(eval(&f, "String(inu.fs.exists('nope.txt'))"), "false");
}

#[test]
fn a_directory_is_not_a_file() {
  let f = scoped("dir-read");
  eval(&f, "inu.fs.mkdir('d'); 'ok'");
  assert_eq!(code_of(&f, "inu.fs.read('d')"), "invalid-argument");
  assert_eq!(code_of(&f, "inu.fs.copy('d', 'e')"), "invalid-argument");
  assert_eq!(code_of(&f, "inu.fs.write('d', new Uint8Array([1]))"), "invalid-argument");
}

#[test]
fn a_write_past_the_quota_is_refused_before_it_writes() {
  let f = setup("quota", &["fs"], 4096, false);
  let got = caught(&f, "inu.fs.write('big.bin', new Uint8Array(8192))");
  assert!(got.starts_with("quota-exceeded|"), "{got}");
  assert!(!f.dir.path().join("big.bin").exists(), "a refused call writes nothing");
  let numbers = eval(
    &f,
    r#"(() => {
            try { inu.fs.write('big.bin', new Uint8Array(8192)); return 'no-throw' }
            catch (e) { return `${e.usage}|${e.quota}` }
        })()"#,
  );
  assert_eq!(numbers, "8192|4096");
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
  assert_eq!(code_of(&f, "inu.fs.write('a.bin', new Uint8Array(4096))"), "no-throw");
  assert_eq!(eval(&f, "String(inu.fs.usage())"), "4096");
}

#[test]
fn append_is_charged_for_what_it_adds() {
  let f = setup("append-quota", &["fs"], 100, false);
  eval(&f, "inu.fs.write('a.bin', new Uint8Array(90)); 'ok'");
  assert_eq!(code_of(&f, "inu.fs.append('a.bin', new Uint8Array(20))"), "quota-exceeded");
  assert_eq!(eval(&f, "String(inu.fs.stat('a.bin').size)"), "90", "and nothing was appended");
}

#[test]
fn a_copy_past_the_quota_is_refused() {
  let f = setup("copy-quota", &["fs"], 150, false);
  eval(&f, "inu.fs.write('a.bin', new Uint8Array(100)); 'ok'");
  assert_eq!(code_of(&f, "inu.fs.copy('a.bin', 'b.bin')"), "quota-exceeded");
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
  assert_eq!(code_of(&f, "inu.fs.write('a.bin', new Uint8Array(200000))"), "no-throw");
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
  assert_eq!(code_of(&f, "inu.fs.read('huge.bin')"), "quota-exceeded");
}

#[test]
fn an_engine_without_a_directory_fails_every_call_rather_than_landing_elsewhere() {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let spill = TestDir::new("no-root-spill");
  let grants = TestGrantHost::new(&["fs"]).as_host();
  ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    install_plugin_error(&ctx).unwrap();
    let blobs = install_sandbox_globals(&ctx, spill.path()).unwrap();
    install_fs(&ctx, grants, blobs, Path::new(""), DEFAULT_QUOTA_BYTES, false, TEST_ANDROID_DIRS, &inu).unwrap();
    for call in ["inu.fs.write('a', new Uint8Array([1]))", "inu.fs.read('a')", "inu.fs.usage()"] {
      let got: String = ctx
        .eval(format!(r#"(() => {{ try {{ {call}; return 'no-throw' }} catch (e) {{ return e.code }} }})()"#,))
        .unwrap();
      assert_eq!(got, "internal", "'{call}'");
    }
  });
}

/// the id table `blob.rs` keeps for values crossing to the host is per engine and swept as it
/// grows, so a plugin writing blobs in a loop must not grow it forever
#[test]
fn writing_in_a_loop_does_not_grow_the_export_table() {
  let f = scoped("loop-write");
  eval(
    &f,
    r#"
        const b = new Blob(['x'])
        for (let i = 0; i < 200; i++) inu.fs.write('loop.txt', b)
        for (let i = 0; i < 200; i++) inu.fs.write('loop.txt', new Blob(['y']))
        'ok'
        "#,
  );
  assert_eq!(eval(&f, "new TextDecoder().decode(inu.fs.read('loop.txt'))"), "y");
}

/// The bundled oracle is the only thing that runs this surface on a device, and an oracle nobody
/// runs is one nobody notices going green on a broken engine. So it runs here too, against a real
/// directory and the install path a plugin gets.
#[cfg(test)]
mod bundled_oracle {
  use super::tests::{install_sandbox_globals, TestDir};
  use super::*;
  use crate::api::error::install_plugin_error;
  use crate::sandbox::grants::TestGrantHost;
  use rquickjs::{Context, Runtime};

  const ORACLE: &str = include_str!("../../../../test/plugins/fs-test.js");

  /// `FsQuota.parseSize`'s shape, the one `GrantValidator` refuses an install over
  fn parse_grant_size(scope: &str) -> Option<u64> {
    let digits = scope.len() - scope.trim_start_matches(|c: char| c.is_ascii_digit()).len();
    let (amount, unit) = scope.split_at(digits);
    let unit: u64 = match unit.to_ascii_lowercase().as_str() {
      "kb" => 1024,
      "mb" => 1024 * 1024,
      "gb" => 1024 * 1024 * 1024,
      _ => return None,
    };
    amount.parse::<u64>().ok().map(|n| n * unit)
  }

  /// `FsQuota.forGrants`'s rule, over the oracle's *own* `@grant` header: the device sizes the
  /// directory off that line and nothing else, so a fixture naming a number of its own runs the
  /// oracle against a cap the app would never hand it. Largest of what the manifest named, since
  /// adding a grant line must not be able to take storage away.
  fn oracle_quota() -> u64 {
    let mut quota: Option<u64> = None;
    let mut saw_fs = false;
    for token in crate::testing::harness::manifest_grants(ORACLE) {
      let (name, scopes) = match token.split_once('(') {
        Some((name, rest)) => (name.trim_end(), rest.trim_end_matches(')')),
        None => (token, ""),
      };
      assert_ne!(name, "unsafe.fs", "this fixture installs the scoped mode");
      if name != "fs" {
        continue;
      }
      saw_fs = true;
      for scope in scopes.split(',').filter(|s| !s.is_empty()) {
        if let Some(size) = parse_grant_size(scope.trim()) {
          quota = Some(quota.unwrap_or(0).max(size));
        }
      }
    }
    assert!(saw_fs, "the oracle's manifest no longer asks for `fs`");
    quota.unwrap_or(DEFAULT_QUOTA_BYTES)
  }

  #[test]
  fn the_bundled_fs_test_plugin_passes() {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let dir = TestDir::new("oracle");
    let spill = TestDir::new("oracle-spill");
    let lines = crate::testing::harness::install_capturing_console(&ctx);
    ctx.with(|ctx| {
      let inu = crate::testing::harness::get_api_globals(&ctx);
      install_plugin_error(&ctx).unwrap();
      let blobs = install_sandbox_globals(&ctx, spill.path()).unwrap();
      install_fs(
        &ctx,
        TestGrantHost::new(&["fs"]).as_host(),
        blobs,
        dir.path(),
        oracle_quota(),
        false,
        TEST_ANDROID_DIRS,
        &inu,
      )
      .unwrap();
      match ctx.eval::<(), _>(ORACLE) {
        Ok(()) => {}
        Err(rquickjs::Error::Exception) => {
          panic!("{}", crate::api::error::format_exception(&ctx))
        }
        Err(e) => panic!("{e:?}"),
      }
    });
    while rt.is_job_pending() {
      rt.execute_pending_job().ok();
    }
    let lines = lines.borrow().clone();
    crate::testing::harness::assert_oracle_exact(&lines, "fs test done", 60);
  }
}

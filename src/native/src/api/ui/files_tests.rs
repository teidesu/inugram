use super::*;
use crate::{
  testing::harness::DisposeOnDrop,
  testing::harness::{install_sandbox_globals, TestDir},
};
use rquickjs::{Context, Runtime};
use std::cell::RefCell;

#[derive(Default)]
struct TestFilesHost {
  asks: RefCell<Vec<(i32, i64, String)>>,
  refuse: RefCell<Option<String>>,
}

impl FilesHost for TestFilesHost {
  fn ui_files(&self, op: i32, request_id: i64, options_json: &str) -> Option<String> {
    self.asks.borrow_mut().push((op, request_id, options_json.to_string()));
    self.refuse.borrow().clone()
  }
}

type Disposing = DisposeOnDrop<FilesState>;

struct Fixture {
  _rt: Runtime,
  ctx: Context,
  host: Rc<TestFilesHost>,
  dir: TestDir,
  _state: Disposing,
}

fn setup(name: &str, grants: &[&str]) -> Fixture {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let dir = TestDir::new(name);
  let host = Rc::new(TestFilesHost::default());
  let host_dyn: Rc<dyn FilesHost> = host.clone();
  let grants = crate::sandbox::grants::CachedGrantHost::new(grants);
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    let blobs = install_sandbox_globals(&ctx, dir.path()).unwrap();
    let state =
      install_files(&ctx, host_dyn, blobs.clone(), dir.path().to_path_buf(), std::sync::Arc::new(|_: &str| {}), &inu)
        .unwrap();
    let fs = crate::api::io::fs::install_fs(
      &ctx,
      grants,
      dir.path(),
      crate::api::io::fs::DEFAULT_QUOTA_BYTES,
      false,
      "",
      &inu,
    )
    .unwrap();
    state.attach_fs(fs);
    state
  });
  let state = Disposing::new(&ctx, state, |ctx, state| state.dispose(ctx));
  Fixture { _rt: rt, ctx, host, dir, _state: state }
}

fn run(f: &Fixture, code: &str) {
  crate::testing::harness::eval_unit(&f.ctx, code)
}

fn eval(f: &Fixture, code: &str) -> String {
  crate::testing::harness::eval_string(&f.ctx, code)
}

fn refusal(f: &Fixture, code: &str) -> String {
  eval(
    f,
    &format!(
      r#"(() => {{ try {{ {code}; return 'no error' }} catch (e) {{ return `${{e.code}}:${{e.message}}` }} }})()"#
    ),
  )
}

/// the value of one string field of the options json, there being no json reader in this crate
fn field(options: &str, name: &str) -> String {
  let at = options.find(&format!("\"{name}\":\"")).unwrap_or_else(|| panic!("no '{name}' in {options}"));
  let rest = &options[at + name.len() + 4..];
  rest[..rest.find('"').unwrap()].to_string()
}

fn asked(f: &Fixture) -> (i32, i64, String) {
  f.host.asks.borrow().last().cloned().expect("the host was never asked")
}

fn answer(f: &Fixture, request_id: i64, wire: &str) {
  f._state.settle(&f.ctx, request_id, wire);
}

fn settled(f: &Fixture) -> String {
  while f._rt.is_job_pending() {
    f._rt.execute_pending_job().ok();
  }
  eval(f, "String(globalThis.out)")
}

fn pick(f: &Fixture, options: &str) -> i64 {
  run(
    f,
    &format!(
      r#"
        globalThis.out = 'pending'
        inu.ui.pickFile({options}).then(
          v => {{ globalThis.picked = v; globalThis.out = Array.isArray(v) ? `[${{v.length}}]` : (v && v.name) }},
          e => {{ globalThis.out = `${{e.code}}:${{e.message}}` }},
        )
      "#
    ),
  );
  asked(f).1
}

#[test]
fn a_pick_asks_the_host_for_the_types_the_plugin_named() {
  let f = setup("pick-ask", &[]);
  pick(&f, "{ accept: ['font/ttf', 'application/octet-stream'] }");
  let (op, _, options) = asked(&f);
  assert_eq!(op, OP_PICK_FILE);
  assert_eq!(options, r#"{"accept":["font/ttf","application/octet-stream"],"multiple":false}"#);

  pick(&f, "{ multiple: true }");
  assert_eq!(asked(&f).2, r#"{"multiple":true}"#);
}

#[test]
fn what_the_picker_answers_becomes_a_file_over_the_copy_the_host_made() {
  let f = setup("pick-files", &[]);
  let one = f.dir.path().join("one.ttf");
  std::fs::write(&one, b"a font, honestly").unwrap();
  let request = pick(&f, "{}");
  answer(
    &f,
    request,
    &format!(r#"J[{{"path":"{}","name":"Cool.ttf","type":"font/ttf"}}]"#, one.to_string_lossy()),
  );

  assert_eq!(settled(&f), "Cool.ttf");
  assert_eq!(eval(&f, "`${picked.size}|${picked.type}|${picked instanceof File}`"), "16|font/ttf|true");
  run(&f, "picked.text().then(t => { globalThis.out = t })");
  assert_eq!(settled(&f), "a font, honestly");
}

#[test]
fn a_picked_file_belongs_to_the_plugin_and_the_copy_goes_when_the_file_does() {
  let f = setup("pick-owned", &[]);
  let one = f.dir.path().join("owned.bin");
  std::fs::write(&one, b"picked content").unwrap();
  let request = pick(&f, "{}");
  answer(&f, request, &format!(r#"J[{{"path":"{}","name":"owned.bin","type":""}}]"#, one.to_string_lossy()));
  assert_eq!(settled(&f), "owned.bin");
  assert!(one.exists(), "the copy was taken away while the plugin still held it");

  run(&f, "picked.dispose()");
  assert!(!one.exists(), "{} outlived the file that owned it", one.display());
}

/// a picker that answered with two when one was asked for: the host copies one, and this reads one
#[test]
fn only_the_files_a_pick_asked_for_are_taken() {
  let f = setup("pick-extra", &[]);
  let one = f.dir.path().join("a.bin");
  let two = f.dir.path().join("b.bin");
  std::fs::write(&one, b"a").unwrap();
  std::fs::write(&two, b"b").unwrap();
  let request = pick(&f, "{}");
  answer(
    &f,
    request,
    &format!(
      r#"J[{{"path":"{}","name":"a.bin","type":""}},{{"path":"{}","name":"b.bin","type":""}}]"#,
      one.to_string_lossy(),
      two.to_string_lossy(),
    ),
  );
  assert_eq!(settled(&f), "a.bin");
}

#[test]
fn an_answer_this_cannot_read_rejects_rather_than_leaving_the_promise_hanging() {
  let f = setup("pick-garbage", &[]);
  let request = pick(&f, "{}");
  answer(&f, request, "not json at all");
  assert!(settled(&f).starts_with("undefined:"), "a malformed answer did not reject");

  let request = pick(&f, "{}");
  let gone = f.dir.path().join("gone.bin");
  answer(&f, request, &format!(r#"J[{{"path":"{}","name":"gone.bin","type":""}}]"#, gone.to_string_lossy()));
  assert_eq!(settled(&f), "internal:pickFile: the copy of this file is gone");
}

#[test]
fn picking_nothing_is_null_for_one_file_and_empty_for_many() {
  let f = setup("pick-cancel", &[]);
  let request = pick(&f, "{}");
  answer(&f, request, "J[]");
  assert_eq!(settled(&f), "null");

  let request = pick(&f, "{ multiple: true }");
  answer(&f, request, "J[]");
  assert_eq!(settled(&f), "[0]");
}

#[test]
fn a_pick_the_host_refuses_rejects_with_what_it_said() {
  let f = setup("pick-refused", &[]);
  *f.host.refuse.borrow_mut() = Some("Punsupported\n\n\n\npickFile: there is no screen".to_string());
  pick(&f, "{}");
  assert_eq!(settled(&f), "unsupported:pickFile: there is no screen");
}

#[test]
fn accept_takes_media_types_and_only_so_many_of_them() {
  let f = setup("pick-accept", &[]);
  assert_eq!(
    refusal(&f, "inu.ui.pickFile({ accept: 'font/ttf' })"),
    "undefined:pickFile: 'accept' must be an array"
  );
  assert!(refusal(&f, "inu.ui.pickFile({ accept: [42] })").starts_with("invalid-argument:"));
  let many = (0..40).map(|n| format!("'a/{n}'")).collect::<Vec<_>>().join(",");
  assert!(refusal(&f, &format!("inu.ui.pickFile({{ accept: [{many}] }})")).starts_with("invalid-argument:"));
}

fn save(f: &Fixture, content: &str, options: &str) -> i64 {
  run(
    f,
    &format!(
      r#"
        globalThis.out = 'pending'
        inu.ui.saveFile({content}, {options}).then(
          v => {{ globalThis.out = String(v) }},
          e => {{ globalThis.out = `${{e.code}}:${{e.message}}` }},
        )
      "#
    ),
  );
  asked(f).1
}

#[test]
fn a_save_hands_the_host_the_content_written_out_and_answers_whether_it_happened() {
  let f = setup("save-blob", &[]);
  let request = save(&f, "new Blob(['saved bytes'])", "{ fileName: 'notes.txt', type: 'text/plain' }");
  let (op, _, options) = asked(&f);
  assert_eq!(op, OP_SAVE_FILE);
  assert_eq!(field(&options, "fileName"), "notes.txt");
  assert_eq!(field(&options, "type"), "text/plain");
  let staged = PathBuf::from(field(&options, "path"));
  assert_eq!(std::fs::read(&staged).unwrap(), b"saved bytes");

  answer(&f, request, "B1");
  assert_eq!(settled(&f), "true");
  assert!(!staged.exists(), "{} was left behind", staged.display());
}

#[test]
fn a_save_the_user_backed_out_of_is_false_rather_than_a_failure() {
  let f = setup("save-cancel", &[]);
  let request = save(&f, "new Uint8Array([1, 2, 3])", "{}");
  answer(&f, request, "B0");
  assert_eq!(settled(&f), "false");
}

#[test]
fn a_save_that_failed_rejects_so_it_is_told_apart_from_one_the_user_declined() {
  let f = setup("save-failed", &[]);
  let request = save(&f, "new Uint8Array([1])", "{}");
  answer(&f, request, "Pinternal\n\n\n\nsaveFile: the file could not be written");
  assert_eq!(settled(&f), "internal:saveFile: the file could not be written");
}

#[test]
fn a_named_file_is_saved_from_where_it_is_rather_than_copied_first() {
  let f = setup("save-path", &["fs"]);
  std::fs::write(f.dir.path().join("own.txt"), b"already mine").unwrap();
  let request = save(&f, "{ path: 'own.txt' }", "{}");
  assert_eq!(PathBuf::from(field(&asked(&f).2, "path")), f.dir.path().canonicalize().unwrap().join("own.txt"),);

  answer(&f, request, "B1");
  assert_eq!(settled(&f), "true");
  assert!(f.dir.path().join("own.txt").exists());
}

#[test]
fn saving_something_that_is_not_a_file_at_all_is_refused() {
  let f = setup("save-shape", &[]);
  assert!(refusal(&f, "inu.ui.saveFile(42)").starts_with("invalid-argument:"));
  assert!(refusal(&f, "inu.ui.saveFile()").starts_with("invalid-argument:"));
}

use std::{fs, path::Path};

const BUILD_RS: &str = include_str!("../../build.rs");

fn walk(dir: &Path, extension: &str, out: &mut Vec<(String, String)>) {
  for entry in fs::read_dir(dir).expect("read dir") {
    let path = entry.expect("entry").path();
    if path.is_dir() {
      walk(&path, extension, out);
    } else if path.extension().is_some_and(|e| e == extension) {
      let relative = path
        .strip_prefix(Path::new(env!("CARGO_MANIFEST_DIR")))
        .expect("under the crate")
        .to_string_lossy()
        .replace('\\', "/");
      out.push((relative, fs::read_to_string(&path).expect("read file")));
    }
  }
}

fn sources(extension: &str) -> Vec<(String, String)> {
  let mut out = Vec::new();
  walk(&Path::new(env!("CARGO_MANIFEST_DIR")).join("src"), extension, &mut out);
  out
}

/// A prelude `build.rs` does not know about is not compiled, and the module that owns it then has
/// nothing to `include_bytes!` - which fails the build only if someone remembered to write that
/// line. Left as an `include_str!` and evaluated at runtime it costs the parse this whole mechanism
/// exists to avoid, and nothing anywhere says so.
#[test]
fn every_prelude_is_compiled_by_build_rs() {
  let preludes = sources("js");
  assert!(!preludes.is_empty(), "found no preludes at all, so this lint is checking nothing");
  for (path, _) in &preludes {
    assert!(BUILD_RS.contains(&format!("\"{path}\"")), "{path} is not in build.rs's PRELUDES");
  }
}

/// The other half: a prelude reached as text is one being parsed per engine, whatever `build.rs`
/// did with it.
#[test]
fn no_prelude_is_evaluated_from_source() {
  for (path, text) in sources("rs") {
    if path.ends_with("_tests.rs") {
      continue;
    }
    for (index, _) in text.match_indices("include_str!(\"") {
      let argument = &text[index + "include_str!(\"".len()..];
      let argument = &argument[..argument.find('"').expect("unterminated include_str!")];
      assert!(
        !argument.ends_with(".js") || argument.contains("test/plugins"),
        "{path} reads {argument} as source; it should include_bytes! the OUT_DIR artifact"
      );
    }
  }
}

//! Holds the crate to the rule that every bundled oracle has a run site.
//!
//! The per-surface run sites live with the module they exercise; what cannot live there is the
//! question "is there one at all", which is a property of the *directory* and of the whole crate.
//! Nine of these were unrun at one point and four still were after a pass that fixed five, with
//! nothing asserting the number either time - an oracle nobody runs is one nobody notices going
//! green on a broken engine, and adding a plugin file is how the count silently goes back up.

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

/// Bundled plugins that are demonstrations rather than oracles: they print, they do not assert, and
/// there is nothing for a run site to hold them to. Named one at a time on purpose - a demo that
/// grows assertions has to be given a run site, and this list is what makes that a failing test
/// rather than a judgement call.
const DEMOS: &[&str] = &["disable-ads.js"];

/// what an oracle prints on its own last line; [`crate::testing::util::assert_oracle_exact`] is written
/// around it, so a file carrying one is a file some test is meant to be driving to completion
const MARKER: &str = "test done";

fn plugin_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../res/assets-debug/inu_plugins")
}

fn bundled_plugins() -> Vec<(String, String)> {
    let mut out: Vec<(String, String)> = std::fs::read_dir(plugin_dir())
        .expect("the bundled plugin directory")
        .map(|entry| entry.expect("a directory entry").path())
        .filter(|path| path.extension().is_some_and(|e| e == "js"))
        .map(|path| {
            let name = path.file_name().expect("a file name").to_string_lossy().into_owned();
            (name, std::fs::read_to_string(&path).expect("a readable plugin"))
        })
        .collect();
    out.sort();
    assert!(!out.is_empty(), "no bundled plugins were found at all");
    out
}

/// every plugin file some `.rs` in the crate pulls in with `include_str!`, by file name
fn included_plugins() -> BTreeSet<String> {
    const MACRO: &str = "include_str!(";
    const PREFIX: &str = "res/assets-debug/inu_plugins/";
    let mut out = BTreeSet::new();
    let mut sources = vec![Path::new(env!("CARGO_MANIFEST_DIR")).join("src")];
    while let Some(dir) = sources.pop() {
        for entry in std::fs::read_dir(dir).expect("a crate source directory") {
            let path = entry.expect("a directory entry").path();
            if path.is_dir() {
                sources.push(path);
                continue;
            }
            if path.extension().is_none_or(|e| e != "rs") {
                continue;
            }
            let source = std::fs::read_to_string(&path).expect("a readable rust source");
            let mut rest = source.as_str();
            while let Some(offset) = rest.find(MACRO) {
                rest = &rest[offset + MACRO.len()..];
                // rustfmt puts a long path on its own line, so the quote need not follow the paren
                let Some(open) = rest.trim_start().strip_prefix('"') else {
                    continue;
                };
                let Some(end) = open.find('"') else { break };
                if let Some((_, name)) = open[..end].split_once(PREFIX) {
                    out.insert(name.to_string());
                }
                rest = &open[end..];
            }
        }
    }
    out
}

#[test]
fn every_bundled_oracle_has_a_run_site_and_every_demo_is_named() {
    let included = included_plugins();
    let mut unrun = Vec::new();
    let mut unmarked = Vec::new();
    let mut demo_asserts = Vec::new();
    let mut missing_demos: Vec<&str> = DEMOS.to_vec();

    for (name, source) in bundled_plugins() {
        let is_demo = DEMOS.contains(&name.as_str());
        missing_demos.retain(|d| *d != name);
        if is_demo {
            if source.contains(MARKER) {
                demo_asserts.push(name);
            }
            continue;
        }
        if !source.contains(MARKER) {
            unmarked.push(name.clone());
        }
        if !included.contains(&name) {
            unrun.push(name);
        }
    }

    assert!(unrun.is_empty(), "bundled oracles no test ever runs: {unrun:?}");
    assert!(unmarked.is_empty(), "bundled plugins with no '{MARKER}' marker that are not listed demos: {unmarked:?}",);
    assert!(
        demo_asserts.is_empty(),
        "these are listed as demos but assert something; give them a run site: {demo_asserts:?}",
    );
    assert!(missing_demos.is_empty(), "stale entries in the demo list: {missing_demos:?}");
}

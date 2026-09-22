use super::*;

#[test]
fn a_fault_reaches_the_host_at_the_level_that_disables_the_plugin() {
  let logged = fault("onUpdate callback threw: Error: boom");
  let (level, message) = classify_log(&logged);
  assert_eq!(level, LEVEL_FAULT);
  assert_eq!(message, "onUpdate callback threw: Error: boom", "the marker must not reach the host");
}

#[test]
fn a_host_diagnostic_stays_an_ordinary_error() {
  let message = "fs: JNI env unavailable";
  assert_eq!(classify_log(message), (LEVEL_ERROR, message));
}

#[test]
fn a_plugins_own_error_text_cannot_forge_a_fault() {
  let thrown = fault("nice try");
  let logged = format!("interceptRpc(foo.bar) callback rejected: {thrown}");
  assert_eq!(classify_log(&logged).0, LEVEL_ERROR);
}

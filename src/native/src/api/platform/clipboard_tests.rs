use crate::testing::harness::setup_apis as setup;

/// the clipboard channel carries the user's own text, so it is the one upcall here that cannot
/// be tagged: every one of these would be an error wire on any other channel
#[test]
fn clipboard_read_hands_over_text_no_wire_tag_could_survive() {
  let (_rt, ctx, host, _lifecycle, _dialogs, _logs) = setup(&["clipboard.read", "clipboard.write"]);
  for text in ["", "Error while assigning 'peer'", "N", "Pquota-exceeded\n\n\n\nnope", "J{\"a\":1}"] {
    *host.clipboard.borrow_mut() = text.to_string();
    let got: String = ctx.with(|ctx| ctx.eval("inu.clipboard.read()").unwrap());
    assert_eq!(got, text);
  }
  ctx.with(|ctx| ctx.eval::<(), _>("inu.clipboard.write(42)").unwrap());
  assert_eq!(*host.writes.borrow(), vec!["42".to_string()], "write coerces like ui.toast");
}

use super::*;
use rquickjs::{Context, Runtime};

fn setup() -> (Runtime, Context) {
  let (rt, ctx) = crate::testing::harness::new_engine();
  ctx.with(|ctx| install_plugin_error(&ctx).unwrap());
  (rt, ctx)
}

#[test]
fn a_plugin_error_wire_decodes_to_its_fields_and_leaves_empty_ones_absent() {
  let (_rt, ctx) = setup();
  for (host_channel, wire, want) in [
    (
      false,
      "Pquota-exceeded\n\n1500\n1048576\nfs is full",
      r#"[true,"PluginError","quota-exceeded","fs is full",null,1500,1048576,true,true]"#,
    ),
    (
      false,
      "Pinternal\n\n\n\nline one\nline two\nline three",
      r#"[true,"PluginError","internal","line one\nline two\nline three",null,null,null,false,false]"#,
    ),
    (
      false,
      "Pnot-granted\nfs\n\n\nmissing grant: fs",
      r#"[true,"PluginError","not-granted","missing grant: fs","fs",null,null,false,false]"#,
    ),
    (
      true,
      "Pquota-exceeded\nfs\n1500\n1024\nfs is full",
      r#"[true,"PluginError","quota-exceeded","fs is full","fs",1500,1024,true,true]"#,
    ),
  ] {
    let got = ctx.with(|ctx| {
      let value = if host_channel {
        host_error_to_js(&ctx, wire).unwrap()
      } else {
        wire_error_to_js(&ctx, wire).expect("expected an error wire").unwrap()
      };
      ctx.globals().set("e", value).unwrap();
      ctx
        .eval::<String, _>(
          "JSON.stringify([e instanceof inu.PluginError, e.name, e.code, e.message, e.grant ?? null, \
             e.usage ?? null, e.quota ?? null, 'usage' in e, 'quota' in e])",
        )
        .unwrap()
    });
    assert_eq!(got, want, "{wire:?}");
  }
}

#[test]
fn replacing_inu_plugin_error_does_not_change_host_errors() {
  let (_rt, ctx) = setup();
  let got = ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        "globalThis.__realPluginError = inu.PluginError; inu.PluginError = class Impostor extends Error {}",
      )
      .unwrap();
    let value = make_plugin_error(&ctx, "internal", "boom", None, None, None).unwrap();
    ctx.globals().set("e", value).unwrap();
    ctx
      .eval::<String, _>("[e instanceof __realPluginError, e instanceof inu.PluginError, e.code].join('|')")
      .unwrap()
  });
  assert_eq!(got, "true|false|internal");
}

#[test]
fn malformed_plugin_error_wire_is_not_an_error_wire() {
  let (_rt, ctx) = setup();
  ctx.with(|ctx| {
    assert!(wire_error_to_js(&ctx, "Plugin host unavailable").is_none());
    assert!(wire_error_to_js(&ctx, "P\n\n\n\nno code").is_none());
    assert!(wire_error_to_js(&ctx, "Pinternal\n\nnope\n\nbad usage").is_none());
    assert!(wire_error_to_js(&ctx, "J{\"a\":1}").is_none());
  });
}

#[test]
fn a_bare_host_message_keeps_its_leading_tag_letter() {
  let (_rt, ctx) = setup();
  ctx.with(|ctx| {
    for message in [
      "Plugin host unavailable",
      "Expected receiver of type TLRPC$TL_message, but got java.lang.Long",
      "Error while assigning 'peer'",
      "Rate limited: try again later",
      "Pending flag sync failed",
    ] {
      let value = host_error_to_js(&ctx, message).unwrap();
      ctx.globals().set("e", value).unwrap();
      let got: String = ctx.eval("e.name + '|' + e.message + '|' + (e instanceof inu.PluginError)").unwrap();
      assert_eq!(got, format!("PluginError|{message}|true"));
    }
    // the same string on a value channel, where the tag is mandatory, is still an `E` wire
    assert!(wire_error_to_js(&ctx, "Error while assigning 'peer'").is_some());
  });
}

#[test]
fn a_reason_that_raises_while_being_formatted_leaves_nothing_pending() {
  let (rt, ctx, logs, log) = setup_rejection_tracker();
  crate::testing::harness::eval_unit(&ctx, "Promise.reject(new Error('caught')).catch(() => {});");
  crate::runtime::pump_jobs(&rt, &ctx, log.as_ref());
  assert!(logs.borrow().is_empty(), "a caught rejection must not log, got: {:?}", logs.borrow());

  crate::testing::harness::eval_unit(
    &ctx,
    r#"
      Promise.reject({
        toString() { throw new Error('nested'); },
        get stack() { throw new Error('nested'); },
      });
    "#,
  );
  crate::runtime::pump_jobs(&rt, &ctx, log.as_ref());

  assert!(!logs.borrow().is_empty(), "the rejection still has to be reported");
  // the tracker returns straight into quickjs, so a raise left pending here would surface at
  // whatever unrelated call touched the context next
  ctx.with(|ctx| {
    let leftover = ctx.catch();
    assert_eq!(
      leftover.type_of(),
      rquickjs::Type::Uninitialized,
      "formatting left an exception pending: {leftover:?}"
    );
  });
}

fn setup_rejection_tracker() -> (Runtime, Context, std::sync::Arc<crate::testing::harness::Logs>, crate::Log) {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let logs = crate::testing::harness::Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  install_rejection_tracker(&rt, &ctx, log.clone()).unwrap();
  (rt, ctx, logs, log)
}

#[test]
fn a_rejection_made_on_another_thread_is_reported_by_the_next_pump() {
  let (rt, ctx, logs, log) = setup_rejection_tracker();
  let caller = ctx.clone();
  std::thread::spawn(move || caller.with(|ctx| ctx.eval::<(), _>("Promise.reject(new Error('elsewhere'))").unwrap()))
    .join()
    .unwrap();
  crate::runtime::pump_jobs(&rt, &ctx, log.as_ref());
  assert!(logs.borrow().iter().any(|line| line.contains("elsewhere")), "got: {:?}", logs.borrow());
}

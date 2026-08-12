use super::*;
use rquickjs::{Context, Runtime};

fn setup() -> (Runtime, Context) {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  ctx.with(|ctx| install_plugin_error(&ctx).unwrap());
  (rt, ctx)
}

fn describe(ctx: &Context, wire: &str) -> String {
  ctx.with(|ctx| {
    let value = wire_error_to_js(&ctx, wire).expect("expected an error wire").unwrap();
    ctx.globals().set("e", value).unwrap();
    ctx
      .eval::<String, _>(
        r#"JSON.stringify({
                isPlugin: e instanceof inu.PluginError,
                name: e.name,
                code: e.code,
                message: e.message,
                grant: e.grant ?? null,
                usage: e.usage ?? null,
                quota: e.quota ?? null,
                usageType: typeof e.usage,
            })"#,
      )
      .unwrap()
  })
}

#[test]
fn plugin_error_wire_carries_grant_usage_and_quota() {
  let (_rt, ctx) = setup();
  let got = describe(&ctx, "Pquota-exceeded\n\n1500\n1048576\nkv is full");
  assert_eq!(
    got,
    r#"{"isPlugin":true,"name":"PluginError","code":"quota-exceeded","message":"kv is full","grant":null,"usage":1500,"quota":1048576,"usageType":"number"}"#,
  );
}

#[test]
fn plugin_error_wire_message_may_contain_newlines() {
  let (_rt, ctx) = setup();
  let got = describe(&ctx, "Pinternal\n\n\n\nline one\nline two\nline three");
  assert_eq!(
    got,
    r#"{"isPlugin":true,"name":"PluginError","code":"internal","message":"line one\nline two\nline three","grant":null,"usage":null,"quota":null,"usageType":"undefined"}"#,
  );
}

#[test]
fn plugin_error_wire_empty_fields_become_absent_props() {
  let (_rt, ctx) = setup();
  let has_own = ctx.with(|ctx| {
    let value = wire_error_to_js(&ctx, "Pnot-granted\nkv\n\n\nmissing grant: kv")
      .expect("expected an error wire")
      .unwrap();
    ctx.globals().set("e", value).unwrap();
    ctx
      .eval::<String, _>(r#"JSON.stringify([e.grant, 'usage' in e, 'quota' in e, 'grant' in e])"#)
      .unwrap()
  });
  assert_eq!(has_own, r#"["kv",false,false,true]"#);
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
fn host_error_falls_back_to_a_plain_error() {
  let (_rt, ctx) = setup();
  let got = ctx.with(|ctx| {
    let value = host_error_to_js(&ctx, "Plugin host unavailable").unwrap();
    ctx.globals().set("e", value).unwrap();
    ctx.eval::<String, _>("e.name + '|' + e.message + '|' + (e instanceof inu.PluginError)").unwrap()
  });
  assert_eq!(got, "Error|Plugin host unavailable|false");
}

#[test]
fn a_bare_host_message_keeps_its_leading_tag_letter() {
  let (_rt, ctx) = setup();
  ctx.with(|ctx| {
    for message in [
      "Expected receiver of type TLRPC$TL_message, but got java.lang.Long",
      "Error while assigning 'peer'",
      "Rate limited: try again later",
      "Pending flag sync failed",
    ] {
      let value = host_error_to_js(&ctx, message).unwrap();
      ctx.globals().set("e", value).unwrap();
      let got: String = ctx.eval("e.name + '|' + e.message + '|' + (e instanceof inu.PluginError)").unwrap();
      assert_eq!(got, format!("Error|{message}|false"));
    }
    // the same string on a value channel, where the tag is mandatory, is still an `E` wire
    assert!(wire_error_to_js(&ctx, "Error while assigning 'peer'").is_some());
  });
}

#[test]
fn the_host_error_channel_still_decodes_a_plugin_error_wire() {
  let (_rt, ctx) = setup();
  let got = ctx.with(|ctx| {
    let value = host_error_to_js(&ctx, "Pquota-exceeded\nkv\n1500\n1024\nkv is full").unwrap();
    ctx.globals().set("e", value).unwrap();
    ctx
      .eval::<String, _>("[e instanceof inu.PluginError, e.code, e.grant, e.usage, e.quota, e.message].join('|')")
      .unwrap()
  });
  assert_eq!(got, "true|quota-exceeded|kv|1500|1024|kv is full");
}

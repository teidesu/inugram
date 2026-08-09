use super::*;
use crate::api::tl::proxy;
use crate::testing::harness::setup_apis as setup;

#[test]
fn kv_round_trips_all_operations() {
  let (_rt, ctx, _host, _lifecycle, _dialogs, _logs) = setup(&["kv"]);
  let result: String = ctx.with(|ctx| {
    ctx
      .eval::<String, _>(
        r#"
            const out = [];
            out.push(inu.kv.get('a'));
            inu.kv.set('a', '1');
            inu.kv.set('b', '2');
            out.push(inu.kv.get('a'));
            out.push(JSON.stringify(inu.kv.keys()));
            out.push(JSON.stringify(inu.kv.getAll()));
            inu.kv.del('a');
            out.push(inu.kv.get('a'));
            inu.kv.insertAll({ c: '3', d: '4' });
            out.push(JSON.stringify(inu.kv.keys()));
            inu.kv.clear();
            out.push(JSON.stringify(inu.kv.keys()));
            JSON.stringify(out);
            "#,
      )
      .unwrap()
  });
  assert_eq!(result, r#"[null,"1","[\"a\",\"b\"]","{\"a\":\"1\",\"b\":\"2\"}",null,"[\"b\",\"c\",\"d\"]","[]"]"#,);
}

#[test]
fn kv_has_and_usage_answer_scalars() {
  let (_rt, ctx, _host, _lifecycle, _dialogs, _logs) = setup(&["kv"]);
  let result: String = ctx.with(|ctx| {
    ctx
      .eval::<String, _>(
        r#"
            const out = [];
            out.push(inu.kv.has('a'));
            out.push(inu.kv.usage());
            inu.kv.set('a', 'xyz');
            out.push(inu.kv.has('a'));
            out.push(inu.kv.usage());
            JSON.stringify(out);
            "#,
      )
      .unwrap()
  });
  assert_eq!(result, "[false,0,true,4]");
}

#[test]
fn kv_has_and_usage_need_the_grant() {
  let (_rt, ctx, _host, _lifecycle, _dialogs, _logs) = setup(&[]);
  let caught: String = ctx.with(|ctx| {
    ctx
      .eval::<String, _>(
        r#"
            const out = [];
            for (const call of [() => inu.kv.has('a'), () => inu.kv.usage()]) {
                try { call(); out.push('no-throw') } catch (e) { out.push(e.code) }
            }
            out.join(',');
            "#,
      )
      .unwrap()
  });
  assert_eq!(caught, "not-granted,not-granted");
}

#[test]
fn kv_error_wire_throws_into_js() {
  let (_rt, ctx, host, _lifecycle, _dialogs, _logs) = setup(&["kv"]);
  *host.fail_kv.borrow_mut() = Some(proxy::encode_error("quota exceeded"));
  let caught: String = ctx.with(|ctx| {
    ctx
      .eval::<String, _>(
        r#"
            let m = 'no-throw';
            try { inu.kv.set('a', '1'); } catch (e) { m = e.message; }
            m;
            "#,
      )
      .unwrap()
  });
  assert_eq!(caught, "quota exceeded");
}

#[test]
fn kv_without_grant_throws_a_not_granted_plugin_error() {
  let (_rt, ctx, host, _lifecycle, _dialogs, _logs) = setup(&[]);
  let caught: String = ctx.with(|ctx| {
    ctx
      .eval::<String, _>(
        r#"
            let out = 'no-throw';
            try {
                inu.kv.get('a');
            } catch (e) {
                out = JSON.stringify([e instanceof inu.PluginError, e.name, e.code, e.grant]);
            }
            out;
            "#,
      )
      .unwrap()
  });
  assert_eq!(caught, r#"[true,"PluginError","not-granted","kv"]"#);
  assert!(host.store.borrow().is_empty());
}

#[test]
fn kv_with_grant_reaches_the_host() {
  let (_rt, ctx, host, _lifecycle, _dialogs, _logs) = setup(&["kv"]);
  ctx.with(|ctx| ctx.eval::<(), _>("inu.kv.set('a', '1');").unwrap());
  assert_eq!(host.store.borrow().get("a").map(String::as_str), Some("1"));
}

#[test]
fn kv_quota_error_wire_throws_a_plugin_error_with_usage_and_quota() {
  let (_rt, ctx, host, _lifecycle, _dialogs, _logs) = setup(&["kv"]);
  *host.fail_kv.borrow_mut() = Some("Pquota-exceeded\n\n1048600\n1048576\nkv is full".to_string());
  let caught: String = ctx.with(|ctx| {
    ctx
      .eval::<String, _>(
        r#"
            let out = 'no-throw';
            try {
                inu.kv.set('a', '1');
            } catch (e) {
                out = JSON.stringify([e.code, e.message, e.usage, e.quota, typeof e.usage]);
            }
            out;
            "#,
      )
      .unwrap()
  });
  assert_eq!(caught, r#"["quota-exceeded","kv is full",1048600,1048576,"number"]"#);
}

const API_ORACLE: &str = include_str!("../../../../res/assets-debug/inu_plugins/api-test.js");

/// its last two halves are the ones a device only reaches through a person: the dialog settles
/// when the user picks a button, and the unload callbacks run when the plugin is stopped. Both
/// are driven here, so the count covers the whole file.
#[test]
fn the_bundled_api_test_plugin_passes() {
  let (rt, ctx, host, lifecycle, dialogs, _logs) = setup(&crate::testing::harness::manifest_grants(API_ORACLE));
  // `inu.ui` is one object two modules install into, and the oracle asserts on what the
  // *dialog* does with an element the other one builds
  let ui_host: Rc<dyn crate::api::ui::pages::UiHost> = Rc::new(crate::api::ui::icons::tests::SilentUiHost);
  let ui = ctx.with(|ctx| {
    crate::api::ui::pages::install_ui(
      &ctx,
      ui_host,
      crate::sandbox::registry::Lifecycle::new(),
      crate::testing::harness::log_sink(&crate::testing::harness::Logs::new()),
      None,
      &crate::testing::harness::inu_namespace(&ctx),
    )
    .unwrap()
  });
  let _ui = crate::testing::harness::DisposeOnDrop::new(&ctx, ui, crate::api::ui::pages::dispose);
  let lines = crate::testing::harness::install_capturing_console(&ctx);
  ctx.with(|ctx| match ctx.eval::<(), _>(API_ORACLE) {
    Ok(()) => {}
    Err(rquickjs::Error::Exception) => panic!("{}", crate::api::telegram::rpc::format_exception(&ctx)),
    Err(e) => panic!("{e:?}"),
  });

  let request_id = host.dialogs.borrow().last().expect("a dialog was opened").0;
  crate::api::ui::dialogs::resolve_dialog(&rt, &ctx, &dialogs, request_id, "positive");
  crate::api::lifecycle::notify_unload(&rt, &ctx, &lifecycle);

  let lines = lines.borrow().clone();
  crate::testing::harness::assert_oracle_exact(&lines, "api test done", 13);
  // what "did not throw" cannot say: the refused dialog never reached the host, and the
  // accepted one did
  assert_eq!(host.dialogs.borrow().len(), 1);
  assert_eq!(*host.toasts.borrow(), vec!["api-test loaded (run #1)".to_string(), "dialog: positive".to_string()],);
}

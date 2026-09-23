use crate::testing::harness::setup_apis as setup;
use rquickjs::Value;

#[test]
fn open_url_takes_any_case_of_scheme_an_ipv6_host_but_not_a_bare_tg() {
  let (_rt, ctx, host, _lifecycle, _dialogs, _logs) = setup(&["openUrl"]);
  let outcomes = crate::testing::harness::eval_json(
    &ctx,
    r#"
      ['HTTP://Example.COM', 'http://[2001:db8::1]:8080/x', 'tg://'].map((url) => {
        try { inu.openUrl(url); return 'opened' }
        catch (e) { return e instanceof inu.PluginError ? e.code : 'Error' }
      })
    "#,
  );
  assert_eq!(outcomes, r#"["opened","opened","invalid-argument"]"#);
  assert_eq!(
    *host.opened.borrow(),
    vec!["HTTP://Example.COM".to_string(), "http://[2001:db8::1]:8080/x".to_string()]
  );
}

#[test]
fn open_url_and_the_two_clipboard_halves_are_three_separate_grants() {
  let (_rt, ctx, host, _lifecycle, _dialogs, _logs) = setup(&["clipboard.write"]);
  *host.clipboard.borrow_mut() = "hunter2".to_string();
  let got: String = ctx.with(|ctx| {
    ctx
      .eval::<String, _>(
        r#"
          const out = [];
          const attempt = f => {
            try { out.push(f() ?? 'ok'); }
            catch (e) { out.push(e.code + '/' + e.grant); }
          };
          attempt(() => inu.clipboard.read());
          attempt(() => inu.openUrl('https://example.com'));
          attempt(() => inu.clipboard.write('mine'));
          JSON.stringify(out);
        "#,
      )
      .unwrap()
  });
  assert_eq!(got, r#"["not-granted/clipboard.read","not-granted/openUrl","ok"]"#);
  assert_eq!(*host.writes.borrow(), vec!["mine".to_string()]);
  assert!(host.opened.borrow().is_empty(), "a refused openUrl must not reach the host");
}

const SHELL_ORACLE: &str = crate::testing::test_plugin!("shell-test.js");

/// the half a device reaches from a button is called and answered here, so the count covers both
#[test]
fn the_bundled_shell_test_plugin_passes() {
  let (rt, ctx, host, _lifecycle, dialogs, _logs) = setup(&crate::testing::harness::manifest_grants(SHELL_ORACLE));
  let lines = crate::testing::harness::install_capturing_console(&ctx);
  crate::testing::harness::eval_unit(&ctx, SHELL_ORACLE);
  ctx.with(|ctx| {
    ctx.eval::<Value, _>("globalThis.__shell()").unwrap();
  });

  for picked in ["J[2]", "J[0,2]", "N"] {
    let request_id = host.choosers.borrow().last().expect("a chooser was opened").0;
    dialogs.settle(&rt, &ctx, request_id, picked);
  }

  let lines = lines.borrow().clone();
  crate::testing::harness::assert_oracle_exact(&lines, "shell test done", 23);
  // what "did not throw" cannot say: the accepted url and the write reached the host
  assert_eq!(
    *host.opened.borrow(),
    vec!["tg://resolve?domain=durov".to_string(), "https://telegram.org/".to_string()]
  );
  assert_eq!(*host.writes.borrow(), vec!["inugram shell test".to_string()]);
}

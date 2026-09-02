use crate::api::telegram::rpc::format_exception;
use crate::testing::harness::setup_apis as setup;
use rquickjs::Value;

#[test]
fn open_url_accepts_http_https_and_tg_and_refuses_every_other_shape() {
  let (_rt, ctx, host, _lifecycle, _dialogs, _logs) = setup(&["openUrl"]);
  let outcomes: String = ctx.with(|ctx| {
    ctx
      .eval::<String, _>(
        r#"
            const urls = [
              'https://example.com/a?b=1#c',
              'HTTP://Example.COM',
              'http://[2001:db8::1]:8080/x',
              'tg://resolve?domain=telegram',
              'tg://',
              'intent://scan/#Intent;scheme=zxing;end',
              'file:///data/data/org.telegram.messenger/files',
              'content://sms/inbox',
              'javascript:alert(1)',
              'example.com',
              'https://telegram.org@evil.com/',
              'https://evil.com\\@telegram.org/',
              'https:///nohost',
              'https://exam\nple.com/',
            ];
            const out = [];
            for (const url of urls) {
              try { inu.openUrl(url); out.push('opened'); }
              catch (e) { out.push(e instanceof inu.PluginError ? e.code : 'Error'); }
            }
            JSON.stringify(out);
            "#,
      )
      .unwrap()
  });
  assert_eq!(
    outcomes,
    r#"["opened","opened","opened","opened","invalid-argument","invalid-argument","invalid-argument","invalid-argument","invalid-argument","invalid-argument","invalid-argument","invalid-argument","invalid-argument","invalid-argument"]"#,
  );
  assert_eq!(
    *host.opened.borrow(),
    vec![
      "https://example.com/a?b=1#c".to_string(),
      "HTTP://Example.COM".to_string(),
      "http://[2001:db8::1]:8080/x".to_string(),
      "tg://resolve?domain=telegram".to_string(),
    ],
    "only http(s) and tg urls may reach the host",
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

const SHELL_ORACLE: &str = include_str!("../../../../test/plugins/shell-test.js");

/// the bundled oracle is the only test this surface gets on a device. its load-time half runs
/// on its own; the half a device reaches from a button is called here and answered, so the
/// count covers both and is exact - a member that vanished reads as a refusal in a suite
/// written out of `expectThrow`, and only the count tells those apart
#[test]
fn the_bundled_shell_test_plugin_passes() {
  let (rt, ctx, host, _lifecycle, dialogs, _logs) = setup(&crate::testing::harness::manifest_grants(SHELL_ORACLE));
  let lines = crate::testing::harness::install_capturing_console(&ctx);
  ctx.with(|ctx| match ctx.eval::<(), _>(SHELL_ORACLE) {
    Ok(()) => {}
    Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
    Err(e) => panic!("{e:?}"),
  });
  ctx.with(|ctx| {
    ctx.eval::<Value, _>("globalThis.__shell()").unwrap();
  });

  for picked in [Some("2"), Some("0,2"), None] {
    let request_id = host.choosers.borrow().last().expect("a chooser was opened").0;
    dialogs.resolve_chooser(&rt, &ctx, request_id, picked);
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

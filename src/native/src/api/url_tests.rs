use super::*;
use rquickjs::{Context, Runtime};

fn setup() -> (Runtime, Context) {
  let (rt, ctx) = crate::testing::harness::new_engine();
  ctx.with(|ctx| install_url(&ctx).unwrap());
  (rt, ctx)
}

/// each case runs in its own block, so cases can reuse bindings like `const u`
fn eval(ctx: &Context, source: &str) -> String {
  let source = format!("{{ {source} }}");
  ctx.with(|ctx| match ctx.eval::<Coerced<String>, _>(source.as_str()) {
    Ok(value) => value.0,
    Err(_) => {
      let thrown = ctx.catch();
      let message = thrown
        .as_exception()
        .and_then(|e| e.message())
        .or_else(|| thrown.get::<Coerced<String>>().ok().map(|c| c.0))
        .unwrap_or_else(|| "<no message>".to_string());
      format!("THREW: {message}")
    }
  })
}

mod screen {
  use super::*;

  #[test]
  fn the_host_comes_back_in_the_form_a_grant_matches_against() {
    assert_eq!(parse_http_url("fetch", "https://Example.COM./a?b#c"), Ok("example.com".to_string()));
    assert_eq!(parse_http_url("fetch", "https://[::1]:8080/x"), Ok("::1".to_string()));
  }

  /// what a grant is matched against is the host the connection would use, so the spellings a
  /// resolver reads as the same address or name answer that one form
  #[test]
  fn alternate_host_spellings_answer_their_canonical_form() {
    assert_eq!(parse_http_url("fetch", "http://0x7f.1/"), Ok("127.0.0.1".to_string()));
    assert_eq!(parse_http_url("fetch", "https://Bücher.de/"), Ok("xn--bcher-kva.de".to_string()));
    assert_eq!(parse_http_url("fetch", "https://[0:0::1]/"), Ok("::1".to_string()));
  }

  #[test]
  fn the_ambiguous_spellings_stay_refused_now_that_a_real_parser_is_in_the_binary() {
    for url in [
      "https://telegram.org@evil.com/",
      "https://evil.com\\@ok.com/",
      "https://ok.com\n/",
      "https://ok.com\t/",
      "file:///etc/passwd",
      "tg://resolve?domain=x",
      "intent://x#Intent;end",
      "//example.com/",
      "https:///example.com/",
      "https://@evil.com/",
      "https://ok.com:pw@evil.com/",
      "http://[::1/",
      "https://./",
    ] {
      assert!(parse_http_url("fetch", url).is_err(), "{url} must not pass the screen");
    }
  }

  /// the module doc's claim, pinned: the screen reads the caller's string and not a normalized
  /// form of it, so adding the `url` crate cannot have quietly widened what may leave the device
  #[test]
  fn normalizing_a_refused_url_does_not_launder_it_through_the_screen() {
    let laundered = Url::parse("https://ok.com\t/").map(|u| u.to_string());
    assert_eq!(laundered.as_deref(), Ok("https://ok.com/"), "the whatwg parser strips the tab");
    assert!(parse_http_url("fetch", "https://ok.com\t/").is_err(), "the screen must still see the tab");
  }
}

mod parsing {
  use super::*;

  #[test]
  fn the_components_read_as_the_spec_names_them() {
    let (_rt, ctx) = setup();
    assert_eq!(
      eval(
        &ctx,
        "const u = new URL('https://user:pw@example.com:8443/a/b?x=1&y=2#frag'); \
         JSON.stringify([u.protocol, u.username, u.password, u.host, u.hostname, u.port, u.pathname, u.search, u.hash, u.origin])",
      ),
      r##"["https:","user","pw","example.com:8443","example.com","8443","/a/b","?x=1&y=2","#frag","https://example.com:8443"]"##,
    );
  }

  #[test]
  fn a_relative_url_resolves_against_its_base_and_needs_one() {
    let (_rt, ctx) = setup();
    assert_eq!(eval(&ctx, "new URL('/b', 'https://a.com/x/y').href"), "https://a.com/b");
    assert_eq!(eval(&ctx, "new URL('c', 'https://a.com/x/y').href"), "https://a.com/x/c");
    assert!(eval(&ctx, "new URL('/b').href").starts_with("THREW:"));
  }

  /// `crate::utils::arguments`'s hazard on this surface: webidl reads an explicit `undefined` as absent
  #[test]
  fn an_explicit_undefined_base_is_the_same_as_no_base() {
    let (_rt, ctx) = setup();
    assert_eq!(eval(&ctx, "new URL('https://a.com/x', undefined).href"), "https://a.com/x");
  }

  #[test]
  fn the_statics_answer_without_throwing() {
    let (_rt, ctx) = setup();
    assert_eq!(eval(&ctx, "String(URL.canParse('https://a.com'))"), "true");
    assert_eq!(eval(&ctx, "String(URL.canParse('nope'))"), "false");
    assert_eq!(eval(&ctx, "URL.parse('https://a.com/x').href"), "https://a.com/x");
    assert_eq!(eval(&ctx, "String(URL.parse('nope'))"), "null");
  }

  #[test]
  fn tostring_and_tojson_are_the_href() {
    let (_rt, ctx) = setup();
    assert_eq!(eval(&ctx, "new URL('https://a.com/x?q#h').toString()"), "https://a.com/x?q#h");
    assert_eq!(eval(&ctx, "JSON.stringify({u: new URL('https://a.com/x')})"), r#"{"u":"https://a.com/x"}"#);
  }
}

mod setters {
  use super::*;

  #[test]
  fn every_component_is_writable_and_shows_up_in_href() {
    let (_rt, ctx) = setup();
    let src = r"
            const u = new URL('https://a.com/one')
            u.protocol = 'http:'
            u.hostname = 'b.com'
            u.port = '99'
            u.pathname = '/two'
            u.search = 'k=v'
            u.hash = 'frag'
            u.href
        ";
    assert_eq!(eval(&ctx, src), "http://b.com:99/two?k=v#frag");
  }

  /// the whatwg rule: one leading delimiter is dropped, and the empty string clears the component
  /// rather than leaving a bare `?`/`#` in `href`
  #[test]
  fn search_and_hash_take_their_delimiter_either_way_and_clear_on_empty() {
    let (_rt, ctx) = setup();
    assert_eq!(eval(&ctx, "const u = new URL('https://a.com/'); u.search = '?k=v'; u.search"), "?k=v");
    assert_eq!(eval(&ctx, "const u = new URL('https://a.com/'); u.search = 'k=v'; u.search"), "?k=v");
    assert_eq!(eval(&ctx, "const u = new URL('https://a.com/?k=v'); u.search = ''; u.href"), "https://a.com/");
    assert_eq!(eval(&ctx, "const u = new URL('https://a.com/#h'); u.hash = ''; u.href"), "https://a.com/");
  }

  #[test]
  fn the_host_setter_takes_a_port_and_the_hostname_setter_does_not() {
    let (_rt, ctx) = setup();
    assert_eq!(eval(&ctx, "const u = new URL('https://a.com/'); u.host = 'b.com:81'; u.href"), "https://b.com:81/");
    assert_eq!(eval(&ctx, "const u = new URL('https://a.com/'); u.hostname = 'b.com:81'; u.href"), "https://a.com/");
    assert_eq!(eval(&ctx, "const u = new URL('https://a.com:81/'); u.port = ''; u.href"), "https://a.com/");
  }

  #[test]
  fn an_ipv6_host_keeps_its_brackets_and_still_splits_its_port() {
    let (_rt, ctx) = setup();
    assert_eq!(eval(&ctx, "const u = new URL('https://a.com/'); u.host = '[::1]:81'; u.href"), "https://[::1]:81/");
    assert_eq!(eval(&ctx, "const u = new URL('https://a.com/'); u.host = '[::1]'; u.hostname"), "[::1]");
  }

  /// a webidl setter that cannot apply its input leaves the attribute alone; only `href` throws
  #[test]
  fn a_refused_setter_is_a_no_op_rather_than_a_throw() {
    let (_rt, ctx) = setup();
    assert_eq!(eval(&ctx, "const u = new URL('https://a.com/'); u.port = 'nope'; u.href"), "https://a.com/");
    assert_eq!(eval(&ctx, "const u = new URL('https://a.com/'); u.hostname = ''; u.href"), "https://a.com/");
    assert!(eval(&ctx, "const u = new URL('https://a.com/'); u.href = 'nope'; u.href").starts_with("THREW:"));
  }
}

mod search_params {
  use super::*;

  #[test]
  fn it_reads_and_writes_through_to_the_url_it_came_off() {
    let (_rt, ctx) = setup();
    let src = r"
            const u = new URL('https://a.com/?a=1&b=2')
            u.searchParams.append('c', '3')
            u.searchParams.delete('a')
            u.href
        ";
    assert_eq!(eval(&ctx, src), "https://a.com/?b=2&c=3");
  }

  /// the spec's identity rule, and the reason it matters: a plugin holding the params object must
  /// still be writing into the url
  #[test]
  fn the_params_object_is_the_same_one_every_time() {
    let (_rt, ctx) = setup();
    assert_eq!(eval(&ctx, "const u = new URL('https://a.com/'); String(u.searchParams === u.searchParams)"), "true");
    let src = r"
            const u = new URL('https://a.com/')
            const p = u.searchParams
            u.search = 'x=9'
            p.get('x')
        ";
    assert_eq!(eval(&ctx, src), "9");
  }

  #[test]
  fn a_free_one_holds_its_own_pairs() {
    let (_rt, ctx) = setup();
    assert_eq!(eval(&ctx, "new URLSearchParams('a=1&b=2').toString()"), "a=1&b=2");
    assert_eq!(eval(&ctx, "new URLSearchParams('?a=1').get('a')"), "1");
    assert_eq!(eval(&ctx, "new URLSearchParams([['a','1'],['b','2']]).toString()"), "a=1&b=2");
    assert_eq!(eval(&ctx, "new URLSearchParams({a: '1', b: 2}).toString()"), "a=1&b=2");
    assert_eq!(eval(&ctx, "new URLSearchParams(new URLSearchParams('a=1')).toString()"), "a=1");
    assert_eq!(eval(&ctx, "new URLSearchParams().toString()"), "");
  }

  #[test]
  fn the_accessors_behave_as_the_spec_says() {
    let (_rt, ctx) = setup();
    assert_eq!(
      eval(
        &ctx,
        "const p = new URLSearchParams('a=1&b=2&a=3'); \
         const read = [p.get('a'), p.get('zz'), p.getAll('a'), p.has('b'), p.has('a', '3'), p.has('a', '9'), p.size]; \
         p.delete('a', '3'); JSON.stringify([...read, p.toString()])",
      ),
      r#"["1",null,["1","3"],true,true,false,3,"a=1&b=2"]"#,
    );
  }

  /// `set` replaces the first match in place and drops the rest, which is what keeps the ordering
  /// a plugin round-tripping a query would otherwise lose
  #[test]
  fn set_replaces_in_place_and_appends_when_absent() {
    let (_rt, ctx) = setup();
    assert_eq!(eval(&ctx, "const p = new URLSearchParams('a=1&b=2&a=3'); p.set('a','9'); p.toString()"), "a=9&b=2");
    assert_eq!(eval(&ctx, "const p = new URLSearchParams('a=1'); p.set('z','9'); p.toString()"), "a=1&z=9");
  }

  #[test]
  fn sort_is_by_name_and_stable() {
    let (_rt, ctx) = setup();
    assert_eq!(eval(&ctx, "const p = new URLSearchParams('b=1&a=2&b=0'); p.sort(); p.toString()"), "a=2&b=1&b=0");
  }

  #[test]
  fn it_iterates_every_way_the_spec_offers() {
    let (_rt, ctx) = setup();
    assert_eq!(
      eval(
        &ctx,
        "const p = new URLSearchParams('a=1&b=2'); const o = []; p.forEach((v, k) => o.push(k + '=' + v)); \
         JSON.stringify([[...p], [...p.entries()], [...p.keys()], [...p.values()], o])",
      ),
      r#"[[["a","1"],["b","2"]],[["a","1"],["b","2"]],["a","b"],["1","2"],["a=1","b=2"]]"#,
    );
  }

  /// one codec, so what the params say and what `href` says cannot come apart on the characters
  /// that have to be escaped
  #[test]
  fn the_query_codec_is_the_one_href_uses() {
    let (_rt, ctx) = setup();
    let src = r"
            const u = new URL('https://a.com/')
            u.searchParams.append('a b', 'c&d=e')
            u.search + '|' + u.searchParams.get('a b')
        ";
    assert_eq!(eval(&ctx, src), "?a+b=c%26d%3De|c&d=e");
  }
}

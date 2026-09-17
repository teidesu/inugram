use super::*;
use crate::DateFormat;

fn entity(kind: EntityKind, offset: i64, length: i64) -> Entity {
  Entity::new(kind, offset, length)
}

fn html(text: &str) -> TextWithEntities {
  parse(false, &[text], &[])
}

fn thtml(text: &str) -> TextWithEntities {
  parse(true, &[text], &[])
}

fn check(result: TextWithEntities, expected_text: &str, expected: &[Entity]) {
  assert_eq!(result.text, expected_text);
  assert_eq!(result.entities, expected);
}

#[test]
fn unparse_returns_plain_text_unchanged() {
  assert_eq!(unparse(false, "", &[]), "");
  assert_eq!(unparse(false, "some text", &[]), "some text");
}

#[test]
fn unparse_handles_the_simple_tags() {
  assert_eq!(
    unparse(
      false,
      "plain bold italic underline strikethrough plain",
      &[
        entity(EntityKind::Bold, 6, 4),
        entity(EntityKind::Italic, 11, 6),
        entity(EntityKind::Underline, 18, 9),
        entity(EntityKind::Strike, 28, 13),
      ],
    ),
    "plain <b>bold</b> <i>italic</i> <u>underline</u> <s>strikethrough</s> plain"
  );
}

#[test]
fn unparse_handles_code_pre_blockquote_and_spoiler() {
  assert_eq!(
    unparse(
      false,
      "plain code pre blockquote spoiler plain",
      &[
        entity(EntityKind::Code, 6, 4),
        entity(EntityKind::Pre { language: String::new() }, 11, 3),
        entity(EntityKind::Blockquote { collapsed: false }, 15, 10),
        entity(EntityKind::Spoiler, 26, 7),
      ],
    ),
    "plain <code>code</code> <pre>pre</pre> <blockquote>blockquote</blockquote> <spoiler>spoiler</spoiler> plain"
  );
  assert_eq!(
    unparse(false, "plain blockquote plain", &[entity(EntityKind::Blockquote { collapsed: true }, 6, 10)]),
    "plain <blockquote collapsible>blockquote</blockquote> plain"
  );
}

#[test]
fn unparse_handles_links_and_mentions() {
  assert_eq!(
    unparse(
      false,
      "plain https://google.com google @durov Pavel Durov mail@mail.ru plain",
      &[
        entity(EntityKind::Url, 6, 18),
        entity(EntityKind::TextUrl { url: "https://google.com".into() }, 25, 6),
        entity(EntityKind::Mention, 32, 6),
        entity(EntityKind::MentionName { user_id: 36265675 }, 39, 11),
        entity(EntityKind::Email, 51, 12),
      ],
    ),
    "plain <a href=\"https://google.com\">https://google.com</a> <a href=\"https://google.com\">google</a> @durov <a href=\"tg://user?id=36265675\">Pavel Durov</a> <a href=\"mailto:mail@mail.ru\">mail@mail.ru</a> plain"
  );
}

#[test]
fn unparse_handles_dates() {
  assert_eq!(
    unparse(
      false,
      "meet at 22:45",
      &[entity(
        EntityKind::FormattedDate {
          date: 1647531900,
          format: DateFormat { short_time: true, ..Default::default() }
        },
        8,
        5
      )],
    ),
    "meet at <tg-time unix=\"1647531900\" format=\"t\">22:45</tg-time>"
  );
  assert_eq!(
    unparse(
      false,
      "meet at 22:45",
      &[entity(
        EntityKind::FormattedDate {
          date: 1647531900,
          format: DateFormat::default()
        },
        8,
        5
      )],
    ),
    "meet at <tg-time unix=\"1647531900\">22:45</tg-time>"
  );
}

#[test]
fn unparse_handles_overlapping_entities() {
  assert_eq!(
    unparse(
      false,
      "Welcome to the gym zone!",
      &[entity(EntityKind::Italic, 0, 14), entity(EntityKind::Bold, 8, 10)]
    ),
    "<i>Welcome <b>to the</b></i><b> gym</b> zone!"
  );
  assert_eq!(
    unparse(
      false,
      "plain bold bold-italic bold-italic-underline underline plain",
      &[entity(EntityKind::Bold, 6, 38), entity(EntityKind::Italic, 11, 33), entity(EntityKind::Underline, 23, 31)],
    ),
    "plain <b>bold <i>bold-italic <u>bold-italic-underline</u></i></b><u> underline</u> plain"
  );
}

#[test]
fn unparse_escapes_and_rewrites_whitespace() {
  assert_eq!(
    unparse(false, "<&> < & > <&>", &[entity(EntityKind::Bold, 4, 5)]),
    "&lt;&amp;&gt; <b>&lt; &amp; &gt;</b> &lt;&amp;&gt;"
  );
  assert_eq!(unparse(false, "plain\n\nplain", &[]), "plain<br><br>plain");
  assert_eq!(
    unparse(false, "plain\n\nplain", &[entity(EntityKind::Pre { language: String::new() }, 0, 12)]),
    "<pre>plain\n\nplain</pre>"
  );
  assert_eq!(unparse(false, "plain    plain", &[]), "plain&nbsp;&nbsp;&nbsp;&nbsp;plain");
}

#[test]
fn unparse_keeping_whitespace_leaves_it_alone() {
  assert_eq!(unparse(true, "plain\n\nplain", &[]), "plain\n\nplain");
  assert_eq!(unparse(true, "plain    plain", &[]), "plain    plain");
  assert_eq!(unparse(true, "<&>", &[]), "&lt;&amp;&gt;");
  assert_eq!(unparse(true, "hello\n  world", &[entity(EntityKind::Bold, 0, 5)]), "<b>hello</b>\n  world");
}

#[test]
fn parse_handles_the_simple_tags() {
  check(
    html("plain <b>bold</b> <i>italic</i> <u>underline</u> <s>strikethrough</s> plain"),
    "plain bold italic underline strikethrough plain",
    &[
      entity(EntityKind::Bold, 6, 4),
      entity(EntityKind::Italic, 11, 6),
      entity(EntityKind::Underline, 18, 9),
      entity(EntityKind::Strike, 28, 13),
    ],
  );
  check(
    html("plain <ins>underline</ins> plain"),
    "plain underline plain",
    &[entity(EntityKind::Underline, 6, 9)],
  );
}

#[test]
fn parse_handles_code_pre_blockquote_and_spoiler() {
  check(
    html("plain <code>code</code> <pre>pre</pre> <blockquote>blockquote</blockquote> <spoiler>spoiler</spoiler> plain"),
    "plain code pre blockquote spoiler plain",
    &[
      entity(EntityKind::Code, 6, 4),
      entity(EntityKind::Pre { language: String::new() }, 11, 3),
      entity(EntityKind::Blockquote { collapsed: false }, 15, 10),
      entity(EntityKind::Spoiler, 26, 7),
    ],
  );
  check(
    html("plain <blockquote collapsible>blockquote</blockquote> plain"),
    "plain blockquote plain",
    &[entity(EntityKind::Blockquote { collapsed: true }, 6, 10)],
  );
  check(
    html("plain <span class=\"tg-spoiler\">spoiler</span> plain"),
    "plain spoiler plain",
    &[entity(EntityKind::Spoiler, 6, 7)],
  );
  check(html("plain <span class=\"other\">text</span> plain"), "plain text plain", &[]);
}

#[test]
fn parse_handles_pre_language() {
  check(
    html("plain <pre language=\"javascript\">console.log(\"Hello, world!\")</pre> <pre>some code</pre> plain"),
    "plain console.log(\"Hello, world!\") some code plain",
    &[
      entity(EntityKind::Pre { language: "javascript".into() }, 6, 28),
      entity(EntityKind::Pre { language: String::new() }, 35, 9),
    ],
  );
  check(
    html("<pre><code class=\"language-python\">print(\"hello\")</code></pre>"),
    "print(\"hello\")",
    &[entity(EntityKind::Pre { language: "python".into() }, 0, 14)],
  );
  check(
    html("<pre><code>some code</code></pre>"),
    "some code",
    &[entity(EntityKind::Pre { language: String::new() }, 0, 9)],
  );
  check(
    html("<pre><b>bold</b> and not bold</pre>"),
    "bold and not bold",
    &[entity(EntityKind::Pre { language: String::new() }, 0, 17)],
  );
  check(
    html("<pre><pre>pre inside pre</pre> so cool</pre>"),
    "pre inside pre so cool",
    &[entity(EntityKind::Pre { language: String::new() }, 0, 22)],
  );
}

#[test]
fn parse_handles_links_and_mentions() {
  check(
    html(
      "plain https://google.com <a href=\"https://google.com\">google</a> @durov <a href=\"tg://user?id=36265675\">Pavel Durov</a> plain",
    ),
    "plain https://google.com google @durov Pavel Durov plain",
    &[
      entity(EntityKind::TextUrl { url: "https://google.com".into() }, 25, 6),
      entity(EntityKind::MentionName { user_id: 36265675 }, 39, 11),
    ],
  );
  check(
    html("<a href=\"tg://user?id=1234567&hash=aabbccddaabbccdd\">user</a>"),
    "user",
    &[entity(
      EntityKind::InputMentionName {
        user_id: 1234567,
        access_hash: -6144092014192636707,
      },
      0,
      4,
    )],
  );
  check(html("<a href=\"\">link</a> <a>link</a>"), "link link", &[]);
}

#[test]
fn parse_handles_time_tags() {
  check(
    html("meet at <tg-time unix=\"1647531900\" format=\"t\">22:45</tg-time>"),
    "meet at 22:45",
    &[entity(
      EntityKind::FormattedDate {
        date: 1647531900,
        format: DateFormat { short_time: true, ..Default::default() },
      },
      8,
      5,
    )],
  );
  check(
    html("<time datetime=\"2022-03-17T22:45:00Z\">22:45</time>"),
    "22:45",
    &[entity(
      EntityKind::FormattedDate {
        date: 1647557100,
        format: DateFormat::default(),
      },
      0,
      5,
    )],
  );
}

#[test]
fn parse_handles_custom_emoji() {
  check(
    html("<tg-emoji id=\"123123123123\">🚀</tg-emoji>"),
    "🚀",
    &[entity(EntityKind::CustomEmoji { document_id: "123123123123".into() }, 0, 2)],
  );
}

#[test]
fn parse_collapses_whitespace() {
  check(html("this is some text\n\nwith newlines"), "this is some text with newlines", &[]);
  check(
    html("<b>this is some text\n\nwith</b> newlines"),
    "this is some text with newlines",
    &[entity(EntityKind::Bold, 0, 22)],
  );
  check(
    html("<b>this is some text ending with\n\n</b> newlines"),
    "this is some text ending with newlines",
    &[entity(EntityKind::Bold, 0, 29)],
  );
  check(
    html(
      "\n                this  is  some  indented  text\n                with    newlines     and\n                <b>\n                    indented tags\n                </b> yeah <i>so cool\n                </i>\n                ",
    ),
    "this is some indented text with newlines and indented tags yeah so cool",
    &[entity(EntityKind::Bold, 45, 13), entity(EntityKind::Italic, 64, 7)],
  );
}

#[test]
fn parse_keeps_whitespace_in_pre() {
  check(
    html("<pre>this is some text\n\nwith newlines</pre>"),
    "this is some text\n\nwith newlines",
    &[entity(EntityKind::Pre { language: String::new() }, 0, 32)],
  );
}

#[test]
fn parse_handles_br_and_nbsp() {
  check(
    html("this is some text<br><br>with actual newlines"),
    "this is some text\n\nwith actual newlines",
    &[],
  );
  check(
    html("<b>this is some text<br><br>meow</b> with actual newlines"),
    "this is some text\n\nmeow with actual newlines",
    &[entity(EntityKind::Bold, 0, 23)],
  );
  check(
    html("<blockquote>3<br>brs<br>hello world!</blockquote>hi"),
    "3\nbrs\nhello world!hi",
    &[entity(EntityKind::Blockquote { collapsed: false }, 0, 18)],
  );
  check(
    html("one    space, many&nbsp;&nbsp;&nbsp;&nbsp;spaces, and<br>a newline"),
    "one space, many    spaces, and\na newline",
    &[],
  );
}

#[test]
fn parse_handles_special_symbols() {
  check(html("<&> <b>< & ></b> <&>"), "<&> < & > <&>", &[entity(EntityKind::Bold, 4, 5)]);
  check(
    html("&lt;&amp;&gt; <b>&lt; &amp; &gt;</b> &lt;&amp;&gt; <a href=\"/?a=&quot;hello&quot;&amp;b\">link</a>"),
    "<&> < & > <&> link",
    &[entity(EntityKind::Bold, 4, 5), entity(EntityKind::TextUrl { url: "/?a=\"hello\"&b".into() }, 14, 4)],
  );
}

#[test]
fn parse_ignores_unknown_tags() {
  check(html("<script>alert(1)</script>"), "alert(1)", &[]);
}

#[test]
fn parse_counts_offsets_in_utf16() {
  check(
    html("<i>best flower</i>: <b>🌸</b>. <i>don't</i> you even doubt it."),
    "best flower: 🌸. don't you even doubt it.",
    &[entity(EntityKind::Italic, 0, 11), entity(EntityKind::Bold, 13, 2), entity(EntityKind::Italic, 17, 5)],
  );
}

#[test]
fn template_adds_plain_strings_as_text() {
  check(
    parse(false, &["some text ", " some more text"], &[Sub::Text("<b>not bold yea</b>".into())]),
    "some text <b>not bold yea</b> some more text",
    &[],
  );
}

#[test]
fn template_interpolates_into_attributes() {
  check(
    parse(
      false,
      &["<a href=\"", "://example.com/&quot;", "/bar/", "?foo=bar&baz=", "\">link</a>"],
      &[Sub::Text("https".into()), Sub::Text("foo".into()), Sub::Text("baz".into()), Sub::Text("egg".into())],
    ),
    "link",
    &[entity(
      EntityKind::TextUrl {
        url: "https://example.com/\"foo/bar/baz?foo=bar&baz=egg".into(),
      },
      0,
      4,
    )],
  );

  check(
    parse(
      false,
      &["<a href=tg://user?id=", "&hash=", ">user</a>"],
      &[Sub::Text("1234567".into()), Sub::Text("aabbccddaabbccdd".into())],
    ),
    "user",
    &[entity(
      EntityKind::InputMentionName {
        user_id: 1234567,
        access_hash: -6144092014192636707,
      },
      0,
      4,
    )],
  );
}

#[test]
fn template_processes_entities() {
  let inner = html("<b>bold</b>");
  check(
    parse(false, &["some text ", " some more text"], &[Sub::Rich(inner.clone())]),
    "some text bold some more text",
    &[entity(EntityKind::Bold, 10, 4)],
  );
  check(
    parse(false, &["<b>bold ", " more bold</b>"], &[Sub::Rich(html("<i>bold italic</i>"))]),
    "bold bold italic more bold",
    &[entity(EntityKind::Italic, 5, 11), entity(EntityKind::Bold, 0, 26)],
  );
}

#[test]
fn template_keeps_whitespace_of_interpolated_text() {
  check(
    parse(false, &["this  is", "some  text", "xd"], &[Sub::Text("  ∙  ".into()), Sub::Text("\n".into())]),
    "this is  ∙  some text\nxd",
    &[],
  );
}

#[test]
fn a_part_is_written_once_however_many_subs_there_are() {
  check(parse(false, &["a"], &[Sub::Text("x".into()), Sub::Text("y".into())]), "axy", &[]);
}

#[test]
fn an_apostrophe_cannot_end_a_single_quoted_attribute() {
  check(
    parse(false, &["<a href='", "'>x</a>"], &[Sub::Text("https://e.com/it's/here".into())]),
    "x",
    &[entity(EntityKind::TextUrl { url: "https://e.com/it's/here".into() }, 0, 1)],
  );
}

#[test]
fn unparse_rewrites_the_whitespace_around_entities_too() {
  assert_eq!(unparse(false, "a\nb  c", &[entity(EntityKind::Bold, 0, 1)]), "<b>a</b><br>b&nbsp;&nbsp;c");
}

#[test]
fn unparse_escapes_what_a_pre_holds() {
  assert_eq!(
    unparse(false, "b<c && d>e", &[entity(EntityKind::Pre { language: String::new() }, 0, 10)]),
    "<pre>b&lt;c &amp;&amp; d&gt;e</pre>"
  );
}

#[test]
fn thtml_preserves_whitespace_and_dedents() {
  check(thtml("this is some text\n\nwith newlines"), "this is some text\n\nwith newlines", &[]);
  check(thtml("multiple   spaces   here"), "multiple   spaces   here", &[]);
  check(thtml("\n          hello\n          world\n        "), "hello\nworld", &[]);
  check(
    thtml("\n          hello\n            indented\n          back\n        "),
    "hello\n  indented\nback",
    &[],
  );
  check(
    thtml("\n          <b>bold</b>\n          <i>italic</i>\n        "),
    "bold\nitalic",
    &[entity(EntityKind::Bold, 0, 4), entity(EntityKind::Italic, 5, 6)],
  );
  check(thtml("hello  <b>bold</b>  world"), "hello  bold  world", &[entity(EntityKind::Bold, 7, 4)]);
  // the part after an interpolation continues its line, so the shared indent is not taken off it
  check(parse(true, &["\n    a ", " b\n    c"], &[Sub::Text("X".into())]), "a X b\nc", &[]);
  check(thtml("hello<br>world"), "hello\nworld", &[]);
  check(thtml("hello&nbsp;&nbsp;world"), "hello  world", &[]);
}

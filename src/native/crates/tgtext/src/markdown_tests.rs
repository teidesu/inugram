use super::*;
use crate::DateFormat;

fn entity(kind: EntityKind, offset: i64, length: i64) -> Entity {
  Entity::new(kind, offset, length)
}

fn parse_str(text: &str) -> TextWithEntities {
  parse(&[text], &[])
}

fn check(texts: &[&str], expected_text: &str, expected: &[Entity]) {
  for text in texts {
    let result = parse_str(text);
    assert_eq!(result.text, expected_text, "text of {text:?}");
    assert_eq!(result.entities, expected, "entities of {text:?}");
  }
}

#[test]
fn unparse_returns_plain_text_unchanged() {
  assert_eq!(unparse("", &[]), "");
  assert_eq!(unparse("some text", &[]), "some text");
}

#[test]
fn unparse_handles_the_simple_tags() {
  assert_eq!(
    unparse(
      "plain bold italic underline strikethrough spoiler plain",
      &[
        entity(EntityKind::Bold, 6, 4),
        entity(EntityKind::Italic, 11, 6),
        entity(EntityKind::Underline, 18, 9),
        entity(EntityKind::Strike, 28, 13),
        entity(EntityKind::Spoiler, 42, 7),
      ],
    ),
    "plain **bold** __italic__ --underline-- ~~strikethrough~~ ||spoiler|| plain"
  );
}

#[test]
fn unparse_handles_code_and_pre() {
  assert_eq!(
    unparse(
      "plain code pre __ignored__ plain",
      &[
        entity(EntityKind::Code, 6, 4),
        entity(EntityKind::Pre { language: String::new() }, 11, 3),
        entity(EntityKind::Code, 15, 11),
      ],
    ),
    "plain `code` ```\npre\n``` `\\_\\_ignored\\_\\_` plain"
  );
}

#[test]
fn unparse_handles_links_and_mentions() {
  assert_eq!(
    unparse(
      "plain https://google.com google @durov Pavel Durov mail@mail.ru plain",
      &[
        entity(EntityKind::TextUrl { url: "https://google.com".into() }, 25, 6),
        entity(EntityKind::Mention, 32, 6),
        entity(EntityKind::MentionName { user_id: 36265675 }, 39, 11),
        entity(EntityKind::Email, 51, 12),
      ],
    ),
    "plain https://google.com [google](https://google.com) @durov [Pavel Durov](tg://user?id=36265675) mail@mail.ru plain"
  );
}

#[test]
fn unparse_handles_dates() {
  let date = |format: DateFormat| entity(EntityKind::FormattedDate { date: 1647531900, format }, 8, 5);
  assert_eq!(
    unparse("meet at 22:45", &[date(DateFormat { short_time: true, ..Default::default() })]),
    "meet at [22:45](tg://time?unix=1647531900&format=t)"
  );
  assert_eq!(
    unparse("meet at 22:45", &[date(DateFormat { relative: true, ..Default::default() })]),
    "meet at [22:45](tg://time?unix=1647531900&format=r)"
  );
  assert_eq!(
    unparse(
      "meet at 22:45",
      &[date(DateFormat {
        day_of_week: true,
        long_date: true,
        short_time: true,
        ..Default::default()
      })]
    ),
    "meet at [22:45](tg://time?unix=1647531900&format=wDt)"
  );
  assert_eq!(
    unparse("meet at 22:45", &[date(DateFormat::default())]),
    "meet at [22:45](tg://time?unix=1647531900)"
  );
}

#[test]
fn unparse_clamps_and_drops_out_of_range_entities() {
  assert_eq!(
    unparse("Hello, world", &[entity(EntityKind::Bold, -2, 7), entity(EntityKind::Bold, 7, 10)]),
    "**Hello**, **world**"
  );
  assert_eq!(unparse("Hello, world", &[entity(EntityKind::Bold, 50, 5)]), "Hello, world");
}

#[test]
fn unparse_handles_nested_entities() {
  assert_eq!(
    unparse("Welcome to the gym zone!", &[entity(EntityKind::Italic, 0, 24), entity(EntityKind::Bold, 15, 8)]),
    "__Welcome to the **gym zone**!__"
  );
}

#[test]
fn unparse_handles_overlapping_entities() {
  assert_eq!(
    unparse("Welcome to the gym zone!", &[entity(EntityKind::Italic, 0, 14), entity(EntityKind::Bold, 8, 10)]),
    "__Welcome **to the__ gym** zone!"
  );
}

#[test]
fn unparse_counts_offsets_in_utf16() {
  assert_eq!(
    unparse(
      "best flower: 🌸. don't you even doubt it.",
      &[entity(EntityKind::Italic, 0, 11), entity(EntityKind::Bold, 13, 2), entity(EntityKind::Italic, 17, 5)],
    ),
    "__best flower__: **🌸**. __don't__ you even doubt it."
  );
}

#[test]
fn unparse_escapes_reserved_symbols() {
  assert_eq!(
    unparse(
      "* ** *** _ __ ___ - -- --- ~ ~~ ~~~ [ [[ ` `` ``` ```` \\ \\\\",
      &[entity(EntityKind::Italic, 9, 8)]
    ),
    "\\* \\*\\* \\*\\*\\* __\\_ \\_\\_ \\_\\_\\___ \\- \\-\\- \\-\\-\\- \\~ \\~\\~ \\~\\~\\~ \\[ \\[\\[ \\` \\`\\` \\`\\`\\` \\`\\`\\`\\` \\\\ \\\\\\\\"
  );
}

#[test]
fn parse_handles_the_simple_tags() {
  check(
    &["plain **bold** __italic__ --underline-- ~~strikethrough~~ ||spoiler|| plain"],
    "plain bold italic underline strikethrough spoiler plain",
    &[
      entity(EntityKind::Bold, 6, 4),
      entity(EntityKind::Italic, 11, 6),
      entity(EntityKind::Underline, 18, 9),
      entity(EntityKind::Strike, 28, 13),
      entity(EntityKind::Spoiler, 42, 7),
    ],
  );
}

#[test]
fn parse_trims_whitespace() {
  check(&["  || spoiler || :3"], "spoiler  :3", &[entity(EntityKind::Spoiler, 0, 8)]);
  check(&["meow || spoiler ||"], "meow  spoiler", &[entity(EntityKind::Spoiler, 5, 8)]);
  check(&["|| spoiler ||", " || spoiler || "], "spoiler", &[entity(EntityKind::Spoiler, 0, 7)]);
}

#[test]
fn parse_handles_code_and_pre() {
  check(
    &[
      "plain `code` ```\npre\n``` `__ignored__` plain",
      "plain `code` ```\npre\n``` `\\_\\_ignored\\_\\_` plain",
      "plain `code` ```\npre``` `\\_\\_ignored\\_\\_` plain",
    ],
    "plain code pre __ignored__ plain",
    &[
      entity(EntityKind::Code, 6, 4),
      entity(EntityKind::Pre { language: String::new() }, 11, 3),
      entity(EntityKind::Code, 15, 11),
    ],
  );

  check(
    &["plain ```\npre with ` and ``\n``` plain"],
    "plain pre with ` and `` plain",
    &[entity(EntityKind::Pre { language: String::new() }, 6, 17)],
  );

  check(
    &["plain ```\npre with \n`\n and \n``\nend\n``` plain"],
    "plain pre with \n`\n and \n``\nend plain",
    &[entity(EntityKind::Pre { language: String::new() }, 6, 24)],
  );
}

#[test]
fn parse_handles_language_in_pre() {
  check(
    &["plain ```javascript\nconsole.log(\"Hello, world!\")\n``` ```\nsome code\n``` plain"],
    "plain console.log(\"Hello, world!\") some code plain",
    &[
      entity(EntityKind::Pre { language: "javascript".into() }, 6, 28),
      entity(EntityKind::Pre { language: String::new() }, 35, 9),
    ],
  );
}

#[test]
fn parse_handles_links_and_mentions() {
  check(
    &["plain https://google.com [google](https://google.com) @durov [Pavel Durov](tg://user?id=36265675) plain"],
    "plain https://google.com google @durov Pavel Durov plain",
    &[
      entity(EntityKind::TextUrl { url: "https://google.com".into() }, 25, 6),
      entity(EntityKind::MentionName { user_id: 36265675 }, 39, 11),
    ],
  );

  check(
    &["[user](tg://user?id=1234567&hash=aabbccddaabbccdd)"],
    "user",
    // `Long.fromString('aabbccddaabbccdd', false, 16)`, which is signed and so negative here
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
fn parse_handles_date_links() {
  check(
    &["[22:45](tg://time?unix=1647531900&format=t)"],
    "22:45",
    &[entity(
      EntityKind::FormattedDate {
        date: 1647531900,
        format: DateFormat { short_time: true, ..Default::default() },
      },
      0,
      5,
    )],
  );
  check(
    &["[22:45](tg://time?unix=1647531900)"],
    "22:45",
    &[entity(
      EntityKind::FormattedDate {
        date: 1647531900,
        format: DateFormat::default(),
      },
      0,
      5,
    )],
  );
}

#[test]
fn parse_handles_custom_emoji_links() {
  check(
    &["[emoji](tg://emoji?id=123123)"],
    "emoji",
    &[entity(EntityKind::CustomEmoji { document_id: "123123".into() }, 0, 5)],
  );
}

#[test]
fn parse_supports_overlapping_entities() {
  check(
    &["__Welcome **to the__ gym** zone!"],
    "Welcome to the gym zone!",
    &[entity(EntityKind::Italic, 0, 14), entity(EntityKind::Bold, 8, 10)],
  );
  check(
    &["plain **bold __bold-italic --bold-italic-underline**__ underline-- plain"],
    "plain bold bold-italic bold-italic-underline underline plain",
    &[entity(EntityKind::Bold, 6, 38), entity(EntityKind::Italic, 11, 33), entity(EntityKind::Underline, 23, 31)],
  );
  check(
    &["plain **bold __bold-italic --bold-italic-underline__** underline-- plain"],
    "plain bold bold-italic bold-italic-underline underline plain",
    &[entity(EntityKind::Italic, 11, 33), entity(EntityKind::Bold, 6, 38), entity(EntityKind::Underline, 23, 31)],
  );
}

#[test]
fn parse_supports_nested_entities() {
  check(
    &["__Welcome to the **gym zone**!__"],
    "Welcome to the gym zone!",
    &[entity(EntityKind::Bold, 15, 8), entity(EntityKind::Italic, 0, 24)],
  );
  check(
    &["plain [__google__](https://google.com) plain"],
    "plain google plain",
    &[entity(EntityKind::Italic, 6, 6), entity(EntityKind::TextUrl { url: "https://google.com".into() }, 6, 6)],
  );
  check(
    &["plain [plain __google__ plain](https://google.com) plain"],
    "plain plain google plain plain",
    &[entity(EntityKind::Italic, 12, 6), entity(EntityKind::TextUrl { url: "https://google.com".into() }, 6, 18)],
  );
}

#[test]
fn parse_handles_escaped_symbols() {
  check(
    &[
      "\\* \\*\\* \\*\\*\\* __\\_ \\_\\_ \\_\\_\\___ \\- \\-\\- \\-\\-\\- \\~ \\~\\~ \\~\\~\\~ \\[ \\[\\[ \\` \\`\\` \\`\\`\\` \\`\\`\\`\\` \\\\ \\\\\\\\",
    ],
    "* ** *** _ __ ___ - -- --- ~ ~~ ~~~ [ [[ ` `` ``` ```` \\ \\\\",
    &[entity(EntityKind::Italic, 9, 8)],
  );
}

#[test]
fn parse_ignores_empty_urls() {
  check(&["[link]() [link]"], "link [link]", &[]);
}

#[test]
fn parse_treats_malformed_input_as_text() {
  check(
    &["plain [link](https://google.com but unclosed"],
    "plain [link](https://google.com but unclosed",
    &[],
  );
  check(
    &["plain [**bold link**](https://google.com but __unclosed__"],
    "plain [bold link](https://google.com but unclosed",
    &[entity(EntityKind::Bold, 7, 9), entity(EntityKind::Italic, 41, 8)],
  );
  check(&["plain ```pre without linebreaks```"], "plain ```pre without linebreaks```", &[]);
  check(&["plain **bold but unclosed"], "plain **bold but unclosed", &[]);
  check(&["meow**"], "meow**", &[]);
  check(&["meow**__"], "meow**__", &[]);
  check(&["meow`woof"], "meow`woof", &[]);
  check(&["meow```woof"], "meow```woof", &[]);
  check(&["meow```woof **bold**"], "meow```woof bold", &[entity(EntityKind::Bold, 12, 4)]);
  check(&["plain **bold and __also italic but unclosed"], "plain **bold and __also italic but unclosed", &[]);
  check(&["plain **bold and __italic__"], "plain **bold and italic", &[entity(EntityKind::Italic, 17, 6)]);
}

#[test]
fn parse_recovers_unclosed_code_around_an_interpolated_marker() {
  let result = parse(&["`", ""], &[Sub::Text("x`y".into())]);
  assert_eq!(result.text, "`x`y");
  assert_eq!(result.entities, vec![]);

  let result = parse(&["```\n", ""], &[Sub::Text("x\n```y".into())]);
  assert_eq!(result.text, "x\n```y");
  assert_eq!(result.entities, vec![]);
}

#[test]
fn parse_recovery_keeps_escaped_and_code_text_literal() {
  check(&["**a \\*\\*"], "**a **", &[]);
  check(&["**x `a **b`"], "**x a **b", &[entity(EntityKind::Code, 4, 5)]);
}

#[test]
fn parse_recovery_grows_an_entity_the_tag_lands_inside() {
  check(&["__x **y__ z"], "x **y z", &[entity(EntityKind::Italic, 0, 5)]);
}

#[test]
fn parse_shifts_entities_inside_brackets_without_a_url() {
  check(&["[**a**] x"], "[a] x", &[entity(EntityKind::Bold, 1, 1)]);
  check(&["**q** [**a**] x"], "q [a] x", &[entity(EntityKind::Bold, 0, 1), entity(EntityKind::Bold, 3, 1)]);
}

#[test]
fn parse_handles_quotes() {
  let result = parse_str("some text :3\n> btw i like quotes\n> and also **formatting**\n>   :3\nsome more text\n");
  assert_eq!(result.text, "some text :3\nbtw i like quotes\nand also formatting\n:3\nsome more text");
  assert_eq!(
    result.entities,
    vec![entity(EntityKind::Bold, 40, 10), entity(EntityKind::Blockquote { collapsed: false }, 13, 40)]
  );
}

#[test]
fn template_adds_plain_strings_as_is() {
  let result = parse(&["", ""], &[Sub::Text("**plain**".into())]);
  assert_eq!(result.text, "**plain**");
  assert!(result.entities.is_empty());
}

#[test]
fn template_skips_dropped_values() {
  let result = parse(&["some text ", " more text ", ""], &[Sub::Skip, Sub::Skip]);
  assert_eq!(result.text, "some text  more text");
}

#[test]
fn a_part_is_written_once_however_many_subs_there_are() {
  assert_eq!(parse(&["a"], &[Sub::Text("x".into()), Sub::Text("y".into())]).text, "axy");
}

#[test]
fn unparse_drops_an_entity_that_covers_nothing() {
  assert_eq!(unparse("ab", &[entity(EntityKind::TextUrl { url: "u".into() }, 1, 0)]), "ab");
}

#[test]
fn template_processes_entities() {
  let inner = parse_str("**bold**");
  let result = parse(&["some text ", " some more text"], &[Sub::Rich(inner.clone())]);
  assert_eq!(result.text, "some text bold some more text");
  assert_eq!(result.entities, vec![entity(EntityKind::Bold, 10, 4)]);

  let result = parse(&["**bold ", " more bold**"], &[Sub::Rich(parse_str("__bold italic__"))]);
  assert_eq!(result.text, "bold bold italic more bold");
  assert_eq!(result.entities, vec![entity(EntityKind::Italic, 5, 11), entity(EntityKind::Bold, 0, 26)]);
}

#[test]
fn template_interpolates_inside_links() {
  let result = parse(
    &["[", "](", "://example.com/\\)", "/bar/", "?foo=bar&baz=", ")"],
    &[
      Sub::Text("link".into()),
      Sub::Text("https".into()),
      Sub::Text("foo".into()),
      Sub::Text("baz".into()),
      Sub::Text("egg".into()),
    ],
  );
  assert_eq!(result.text, "link");
  assert_eq!(
    result.entities,
    vec![entity(
      EntityKind::TextUrl {
        url: "https://example.com/)foo/bar/baz?foo=bar&baz=egg".into()
      },
      0,
      4
    )]
  );

  let result = parse(&["[emoji](tg://emoji?id=", ")"], &[Sub::Text("123123".into())]);
  assert_eq!(result.entities, vec![entity(EntityKind::CustomEmoji { document_id: "123123".into() }, 0, 5)]);
}

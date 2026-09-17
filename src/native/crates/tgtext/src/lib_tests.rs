use super::*;

#[test]
fn date_format_parses_telegram_format_strings() {
  assert_eq!(DateFormat::parse("r"), Some(DateFormat { relative: true, ..Default::default() }));
  assert_eq!(DateFormat::parse("R"), Some(DateFormat { relative: true, ..Default::default() }));
  assert_eq!(DateFormat::parse(""), Some(DateFormat::default()));
  assert_eq!(
    DateFormat::parse("wDt"),
    Some(DateFormat {
      day_of_week: true,
      long_date: true,
      short_time: true,
      ..Default::default()
    })
  );
  assert_eq!(DateFormat::parse("x"), None);
}

#[test]
fn date_format_round_trips() {
  for format in ["", "r", "t", "T", "d", "D", "w", "wdDtT"] {
    let parsed = DateFormat::parse(format).unwrap();
    let printed = parsed.to_format_string();
    assert_eq!(DateFormat::parse(&printed).unwrap(), parsed, "{format}");
  }
}

#[test]
fn iso8601_matches_javascript_date() {
  // `new Date('2022-03-17T22:45:00Z').getTime() / 1000`
  assert_eq!(parse_iso8601("2022-03-17T22:45:00Z"), Some(1_647_557_100));
  assert_eq!(parse_iso8601("2022-03-17T22:45:00.500Z"), Some(1_647_557_100));
  assert_eq!(parse_iso8601("2022-03-18T01:45:00+03:00"), Some(1_647_557_100));
  assert_eq!(parse_iso8601("2022-03-17T19:45:00-03:00"), Some(1_647_557_100));
  assert_eq!(parse_iso8601("1970-01-01"), Some(0));
  assert_eq!(parse_iso8601("not a date"), None);
  assert_eq!(parse_iso8601("2022-13-01"), None);
  assert_eq!(parse_iso8601("2022-02-31"), None);
  assert_eq!(parse_iso8601("2022-04-31"), None);
  assert_eq!(parse_iso8601("2024-02-29"), Some(1_709_164_800));
  assert_eq!(parse_iso8601("2023-02-29"), None);
  assert_eq!(parse_iso8601("2000-02-29"), Some(951_782_400));
  assert_eq!(parse_iso8601("2100-02-29"), None);
}

#[test]
fn utf16_map_indexes_surrogate_pairs() {
  assert_eq!(utf16_len("a😀b"), 4);
  // both units of the pair map to the character's first byte, so slicing between them is empty
  // rather than half a character
  assert_eq!(utf16_map("a😀b"), vec![0, 1, 1, 5, 6]);
  assert_eq!(utf16_map(""), vec![0]);
}

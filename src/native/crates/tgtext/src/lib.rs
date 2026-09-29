pub mod html;
pub mod markdown;

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct DateFormat {
  pub relative: bool,
  pub day_of_week: bool,
  pub short_date: bool,
  pub long_date: bool,
  pub short_time: bool,
  pub long_time: bool,
}

impl DateFormat {
  /// `r|w?[dD]?[tT]?`, telegram's own format string. An unknown character refuses the whole string,
  /// which is how a malformed `format=` attribute leaves the entity unformatted rather than wrong.
  pub fn parse(format: &str) -> Option<Self> {
    if format == "r" || format == "R" {
      return Some(Self { relative: true, ..Default::default() });
    }
    let mut result = Self::default();
    for c in format.chars() {
      match c {
        't' => result.short_time = true,
        'T' => result.long_time = true,
        'd' => result.short_date = true,
        'D' => result.long_date = true,
        'w' | 'W' => result.day_of_week = true,
        _ => return None,
      }
    }
    Some(result)
  }

  pub fn to_format_string(&self) -> String {
    if self.relative {
      return "r".into();
    }
    let mut result = String::new();
    if self.day_of_week {
      result.push('w');
    }
    if self.short_date {
      result.push('d');
    }
    if self.long_date {
      result.push('D');
    }
    if self.short_time {
      result.push('t');
    }
    if self.long_time {
      result.push('T');
    }
    result
  }
}

/// A message entity without its span. Parsers produce formatting kinds; other kinds are needed when
/// rendering the app's entity list, which can include server-detected URLs and mentions.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum EntityKind {
  Bold,
  Italic,
  Underline,
  Strike,
  Spoiler,
  Code,
  Pre {
    language: String,
  },
  Blockquote {
    collapsed: bool,
  },
  TextUrl {
    url: String,
  },
  MentionName {
    user_id: i64,
  },
  /// `inputMessageEntityMentionName`: a mention carrying the access hash it needs to be sent.
  InputMentionName {
    user_id: i64,
    access_hash: i64,
  },
  CustomEmoji {
    document_id: String,
  },
  FormattedDate {
    date: i64,
    format: DateFormat,
  },
  Url,
  Email,
  Mention,
  /// Any other constructor, carried by name so unparsing can pass it through untouched.
  Other(String),
}

impl EntityKind {
  pub fn name(&self) -> &str {
    match self {
      Self::Bold => "messageEntityBold",
      Self::Italic => "messageEntityItalic",
      Self::Underline => "messageEntityUnderline",
      Self::Strike => "messageEntityStrike",
      Self::Spoiler => "messageEntitySpoiler",
      Self::Code => "messageEntityCode",
      Self::Pre { .. } => "messageEntityPre",
      Self::Blockquote { .. } => "messageEntityBlockquote",
      Self::TextUrl { .. } => "messageEntityTextUrl",
      Self::MentionName { .. } => "messageEntityMentionName",
      Self::InputMentionName { .. } => "inputMessageEntityMentionName",
      Self::CustomEmoji { .. } => "messageEntityCustomEmoji",
      Self::FormattedDate { .. } => "messageEntityFormattedDate",
      Self::Url => "messageEntityUrl",
      Self::Email => "messageEntityEmail",
      Self::Mention => "messageEntityMention",
      Self::Other(name) => name,
    }
  }
}

/// An entity and the span it covers, in utf-16 code units. Offsets are signed because an app-made
/// entity may start before the text an unparse was handed, and that case is clamped rather than refused.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Entity {
  pub kind: EntityKind,
  pub offset: i64,
  pub length: i64,
}

impl Entity {
  pub fn new(kind: EntityKind, offset: i64, length: i64) -> Self {
    Self { kind, offset, length }
  }
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct TextWithEntities {
  pub text: String,
  pub entities: Vec<Entity>,
}

/// Numbers and int64s become [`Sub::Text`] before reaching this crate.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Sub {
  Skip,
  Text(String),
  Rich(TextWithEntities),
}

pub(crate) fn utf16_len(text: &str) -> i64 {
  text.chars().map(|c| c.len_utf16() as i64).sum()
}

/// Maps UTF-16 indices to byte offsets in `text`, including one past the end. Entity offsets use
/// UTF-16, so slicing uses this table. Indices inside a surrogate pair map to the character's first
/// byte.
pub(crate) fn utf16_map(text: &str) -> Vec<usize> {
  let mut map = Vec::with_capacity(text.len() + 1);
  for (at, c) in text.char_indices() {
    for _ in 0..c.len_utf16() {
      map.push(at);
    }
  }
  map.push(text.len());
  map
}

/// `YYYY-MM-DD` with an optional `THH:MM(:SS(.fff)?)?` and an optional `Z`/`±HH:MM` offset, which is
/// what a `<time datetime>` attribute carries in practice. Anything else leaves the tag ignored.
pub(crate) fn parse_iso8601(value: &str) -> Option<i64> {
  let bytes = value.as_bytes();
  if bytes.len() < 10 {
    return None;
  }
  let number = |range: std::ops::Range<usize>| -> Option<i64> { value.get(range)?.parse::<i64>().ok() };
  if bytes[4] != b'-' || bytes[7] != b'-' {
    return None;
  }
  let year = number(0..4)?;
  let month = number(5..7)?;
  let day = number(8..10)?;
  if !(1..=12).contains(&month) || !(1..=days_in_month(year, month)).contains(&day) {
    return None;
  }

  let mut seconds = days_from_civil(year, month, day) * 86400;
  let rest = &value[10..];
  if rest.is_empty() {
    return Some(seconds);
  }

  let rest = match rest.strip_prefix(['T', 't', ' ']) {
    Some(rest) => rest,
    None => return None,
  };
  let (time, zone) = match rest.find(['Z', 'z', '+']) {
    Some(at) => rest.split_at(at),
    // a negative offset shares its sign with nothing else past the time, the date being behind us
    None => match rest.rfind('-') {
      Some(at) => rest.split_at(at),
      None => (rest, ""),
    },
  };

  let time = time.split('.').next().unwrap_or(time);
  let mut parts = time.split(':');
  let hour = parts.next()?.parse::<i64>().ok()?;
  let minute = parts.next().unwrap_or("0").parse::<i64>().ok()?;
  let second = parts.next().unwrap_or("0").parse::<i64>().ok()?;
  if parts.next().is_some() || !(0..=23).contains(&hour) || !(0..=59).contains(&minute) || !(0..=60).contains(&second) {
    return None;
  }
  seconds += hour * 3600 + minute * 60 + second;

  if !zone.is_empty() && !zone.eq_ignore_ascii_case("Z") {
    let sign = if zone.starts_with('-') { 1 } else { -1 };
    let offset = &zone[1..];
    let mut parts = offset.split(':');
    let hours = parts.next()?.parse::<i64>().ok()?;
    let minutes = parts.next().unwrap_or("0").parse::<i64>().ok()?;
    if parts.next().is_some() || !(0..=23).contains(&hours) || !(0..=59).contains(&minutes) {
      return None;
    }
    seconds += sign * (hours * 3600 + minutes * 60);
  }

  Some(seconds)
}

fn days_in_month(year: i64, month: i64) -> i64 {
  match month {
    4 | 6 | 9 | 11 => 30,
    2 if year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) => 29,
    2 => 28,
    _ => 31,
  }
}

/// Days between 1970-01-01 and the given civil date. Howard Hinnant's algorithm, which is branchless
/// and needs no month-length table.
fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
  let year = if month <= 2 { year - 1 } else { year };
  let era = if year >= 0 { year } else { year - 399 } / 400;
  let year_of_era = year - era * 400;
  let day_of_year = (153 * (if month > 2 { month - 3 } else { month + 9 }) + 2) / 5 + day - 1;
  let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
  era * 146097 + day_of_era - 719468
}

#[cfg(test)]
#[path = "lib_tests.rs"]
mod tests;

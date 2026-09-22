//! mtcute's markdown dialect: `**bold**`, `__italic__`, `--underline--`, `~~strike~~`,
//! `||spoiler||`, `` `code` ``, ```` ```pre ````, `[text](url)` and `> quote`.

use std::borrow::Cow;

use crate::{DateFormat, Entity, EntityKind, Sub, TextWithEntities, utf16_len, utf16_map};

const TAG_BOLD: &str = "**";
const TAG_ITALIC: &str = "__";
const TAG_UNDERLINE: &str = "--";
const TAG_STRIKE: &str = "~~";
const TAG_SPOILER: &str = "||";
const TAG_CODE: &str = "`";
const TAG_PRE: &str = "```";

fn is_escapable(c: char) -> bool {
  matches!(c, '*' | '_' | '-' | '~' | '`' | '[' | ']' | '|' | '\\')
}

/// Escape text so markdown parsing gives it back unchanged.
pub fn escape(text: &str) -> String {
  let mut out = String::with_capacity(text.len());
  for c in text.chars() {
    if is_escapable(c) {
      out.push('\\');
    }
    out.push(c);
  }
  out
}

/// Render text and its entities back to markdown.
pub fn unparse(text: &str, entities: &[Entity]) -> String {
  let mut escaped = Vec::new();
  let mut out = String::with_capacity(text.len());
  let mut index: i64 = 0;
  for c in text.chars() {
    if is_escapable(c) {
      escaped.push(index);
      out.push('\\');
    }
    out.push(c);
    index += c.len_utf16() as i64;
  }
  let has_escaped = !escaped.is_empty();
  let text_len = utf16_len(&out);

  let mut insert: Vec<(i64, Cow<'static, str>)> = Vec::new();

  for entity in entities {
    let mut start = entity.offset;
    let mut end = start + entity.length;

    if entity.length <= 0 || start > text_len {
      continue;
    }
    if start < 0 {
      start = 0;
    }
    if end > text_len {
      end = text_len;
    }

    if has_escaped {
      // the escapes inserted before each edge, counted the way the javascript counted them: one
      // walk over the position list, carried from the start edge to the end edge
      let mut escaped_pos = 0;
      while escaped_pos < escaped.len() && escaped[escaped_pos] < start {
        escaped_pos += 1;
      }
      start += escaped_pos as i64;
      while escaped_pos < escaped.len() && escaped[escaped_pos] <= end {
        escaped_pos += 1;
      }
      end += escaped_pos as i64;
    }

    let (start_tag, end_tag): (Cow<'static, str>, Cow<'static, str>) = match &entity.kind {
      EntityKind::Bold => (TAG_BOLD.into(), TAG_BOLD.into()),
      EntityKind::Italic => (TAG_ITALIC.into(), TAG_ITALIC.into()),
      EntityKind::Underline => (TAG_UNDERLINE.into(), TAG_UNDERLINE.into()),
      EntityKind::Strike => (TAG_STRIKE.into(), TAG_STRIKE.into()),
      EntityKind::Spoiler => (TAG_SPOILER.into(), TAG_SPOILER.into()),
      EntityKind::Code => (TAG_CODE.into(), TAG_CODE.into()),
      EntityKind::Pre { language } => (format!("{TAG_PRE}{language}\n").into(), format!("\n{TAG_PRE}").into()),
      EntityKind::TextUrl { url } => ("[".into(), format!("]({url})").into()),
      EntityKind::MentionName { user_id } | EntityKind::InputMentionName { user_id, .. } => {
        ("[".into(), format!("](tg://user?id={user_id})").into())
      }
      EntityKind::CustomEmoji { document_id } => ("[".into(), format!("](tg://emoji?id={document_id})").into()),
      EntityKind::FormattedDate { date, format } => {
        let format = format.to_format_string();
        let suffix = if format.is_empty() { String::new() } else { format!("&format={format}") };
        ("[".into(), format!("](tg://time?unix={date}{suffix})").into())
      }
      _ => continue,
    };

    insert.push((start, start_tag));
    insert.push((end, end_tag));
  }

  // descending, and stable, then walked backwards: two tags at the same offset come out with the
  // later entity's tag first, so an entity ending where another one starts closes before it opens
  insert.sort_by_key(|(offset, _)| std::cmp::Reverse(*offset));

  let map = utf16_map(&out);
  let mut result = String::with_capacity(out.len() + insert.iter().map(|(_, tag)| tag.len()).sum::<usize>());
  let mut written = 0;
  for (offset, tag) in insert.iter().rev() {
    let offset = *offset;
    let byte = map[offset.clamp(0, text_len) as usize];
    result.push_str(&out[written..byte]);
    result.push_str(tag);
    written = byte;
  }
  result.push_str(&out[written..]);
  result
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum StackName {
  Link,
  Code,
  Pre,
  Bold,
  Italic,
  Underline,
  Strike,
  Spoiler,
}

impl StackName {
  fn tag(self) -> &'static str {
    match self {
      Self::Bold => TAG_BOLD,
      Self::Italic => TAG_ITALIC,
      Self::Underline => TAG_UNDERLINE,
      Self::Strike => TAG_STRIKE,
      Self::Spoiler => TAG_SPOILER,
      Self::Code => TAG_CODE,
      Self::Link | Self::Pre => "",
    }
  }

  fn kind(self) -> EntityKind {
    match self {
      Self::Bold => EntityKind::Bold,
      Self::Italic => EntityKind::Italic,
      Self::Underline => EntityKind::Underline,
      Self::Strike => EntityKind::Strike,
      Self::Spoiler => EntityKind::Spoiler,
      Self::Code => EntityKind::Code,
      Self::Pre => EntityKind::Pre { language: String::new() },
      // a link only becomes an entity once its url is read, so this is never asked for
      Self::Link => EntityKind::TextUrl { url: String::new() },
    }
  }
}

/// Where an unterminated entity would reopen: `offset` counts utf-16 units, for the entity it
/// becomes, and `byte` indexes the text, for the splitting and inserting that recovery does.
struct Pending {
  offset: i64,
  byte: usize,
  language: String,
}

/// Parse markdown, interpolating `subs` between `parts` - one fewer sub than parts, as a tagged
/// template gives them.
pub fn parse(parts: &[&str], subs: &[Sub]) -> TextWithEntities {
  let mut parser = Parser::default();

  for (index, sub) in subs.iter().enumerate() {
    if let Some(part) = parts.get(index) {
      parser.feed(part);
    }
    parser.interpolate(sub);
  }
  if let Some(part) = parts.get(subs.len()) {
    parser.feed(part);
  }

  parser.finish()
}

#[derive(Default)]
struct Parser {
  result: String,
  /// the utf-16 length of `result`, which is what every entity offset is counted in
  result_len16: i64,
  entities: Vec<Entity>,
  // ordered by first use: an unterminated entity is recovered in the order the stacks were created
  stacks: Vec<(StackName, Vec<Pending>)>,
  inside_code: bool,
  inside_pre: bool,
  inside_link: bool,
  inside_link_url: bool,
  pending_link_url: String,
  blockquote_start: Option<i64>,
  prev_blockquote: Option<(i64, i64)>,
}

impl Parser {
  fn stack(&mut self, name: StackName) -> &mut Vec<Pending> {
    if let Some(index) = self.stacks.iter().position(|(existing, _)| *existing == name) {
      return &mut self.stacks[index].1;
    }
    self.stacks.push((name, Vec::new()));
    &mut self.stacks.last_mut().expect("just pushed").1
  }

  fn push_char(&mut self, c: char) {
    self.result.push(c);
    self.result_len16 += c.len_utf16() as i64;
  }

  fn push_str(&mut self, text: &str) {
    self.result.push_str(text);
    self.result_len16 += utf16_len(text);
  }

  fn push_entity(&mut self, kind: EntityKind, offset: i64) {
    let length = self.result_len16 - offset;
    self.entities.push(Entity::new(kind, offset, length));
  }

  fn pending(&self) -> Pending {
    Pending {
      offset: self.result_len16,
      byte: self.result.len(),
      language: String::new(),
    }
  }

  fn interpolate(&mut self, sub: &Sub) {
    match sub {
      Sub::Skip => {}
      Sub::Text(text) => {
        if self.inside_link_url {
          self.pending_link_url.push_str(text);
        } else {
          self.push_str(text);
        }
      }
      Sub::Rich(rich) => {
        if self.inside_link_url {
          self.pending_link_url.push_str(&rich.text);
          return;
        }
        let base = self.result_len16;
        self.push_str(&rich.text);
        for entity in &rich.entities {
          self.entities.push(Entity::new(entity.kind.clone(), entity.offset + base, entity.length));
        }
      }
    }
  }

  fn feed(&mut self, text: &str) {
    let bytes = text.as_bytes();
    let len = bytes.len();
    let mut pos = 0;
    let at = |index: usize| -> Option<u8> { bytes.get(index).copied() };
    let is = |index: usize, c: char| at(index) == Some(c as u8);
    // every marker is ascii, so no byte of a multi-byte character can match one and it reaches the
    // text branch whole
    let char_at = |index: usize| -> char { text[index..].chars().next().expect("index is a boundary") };

    while pos < len {
      let b = bytes[pos];

      if b == b'\\' {
        // an escape at the very end has nothing to escape; javascript appended the string
        // "undefined" here, which is a bug rather than a dialect
        if pos + 1 < len {
          let next = char_at(pos + 1);
          if self.inside_link_url {
            self.pending_link_url.push(next);
          } else {
            self.push_char(next);
          }
          pos += 1 + next.len_utf8();
        } else {
          pos += 2;
        }
        continue;
      }

      if self.inside_code {
        if b == b'`' {
          let entity = self.stack(StackName::Code).pop().expect("inside code");
          self.push_entity(EntityKind::Code, entity.offset);
          self.inside_code = false;
          pos += 1;
        } else {
          let c = char_at(pos);
          pos += c.len_utf8();
          self.push_char(c);
        }
        continue;
      }

      if self.inside_pre {
        if b == b'`' || (b == b'\n' && is(pos + 1, '`')) {
          if b == b'\n' {
            pos += 1;
          }

          if is(pos + 1, '`') && is(pos + 2, '`') {
            let entity = self.stack(StackName::Pre).pop().expect("inside pre");
            self.push_entity(EntityKind::Pre { language: entity.language }, entity.offset);
            self.inside_pre = false;
            pos += 3;
            continue;
          } else if b == b'\n' {
            // closed with one or two backticks, so not closed at all; undo the newline we ate
            pos -= 1;
          }
        }

        let c = char_at(pos);
        pos += c.len_utf8();
        self.push_char(c);
        continue;
      }

      if self.inside_link && b == b']' {
        let entity = self.stack(StackName::Link).pop().expect("inside link");

        if !is(pos + 1, '(') {
          // `[link text]` with no url: put the brackets back as plain text
          self.result.insert(entity.byte, '[');
          self.result_len16 += 1;
          self.adjust_offsets(entity.offset, 1);
          self.push_char(']');
          pos += 1;
          self.inside_link = false;
          continue;
        }

        pos += 2;
        self.inside_link = false;
        self.inside_link_url = true;
        self.stack(StackName::Link).push(entity);
        continue;
      }

      if self.inside_link_url {
        let c = char_at(pos);
        pos += c.len_utf8();

        if c != ')' {
          self.pending_link_url.push(c);
          continue;
        }

        let entity = self.stack(StackName::Link).pop().expect("inside link url");
        let url = std::mem::take(&mut self.pending_link_url);
        self.inside_link_url = false;

        if url.is_empty() {
          continue;
        }

        let kind = link_kind(url);
        self.push_entity(kind, entity.offset);
        continue;
      }

      if b == b'[' && !self.inside_link {
        pos += 1;
        self.inside_link = true;
        let pending = self.pending();
        self.stack(StackName::Link).push(pending);
        continue;
      }

      if b == b'`' {
        let is_pre = is(pos + 1, '`') && is(pos + 2, '`');

        if is_pre {
          pos += 3;
          let start = pos;
          while pos < len && bytes[pos] != b'\n' {
            pos += 1;
          }
          let language = text[start..pos].to_string();
          pos += 1;

          if pos > len {
            // no newline after the fence: plain text, and the "language" is more of the message
            self.push_str(TAG_PRE);
            self.feed(&language);
          } else {
            let mut pending = self.pending();
            pending.language = language;
            self.stack(StackName::Pre).push(pending);
            self.inside_pre = true;
          }
        } else {
          pos += 1;
          let pending = self.pending();
          self.stack(StackName::Code).push(pending);
          self.inside_code = true;
        }

        continue;
      }

      if at(pos + 1) == Some(b) {
        let name = match b {
          b'_' => Some(StackName::Italic),
          b'*' => Some(StackName::Bold),
          b'-' => Some(StackName::Underline),
          b'~' => Some(StackName::Strike),
          b'|' => Some(StackName::Spoiler),
          _ => None,
        };

        if let Some(name) = name {
          let is_begin = self.stack(name).is_empty();

          if is_begin {
            let pending = self.pending();
            self.stack(name).push(pending);
          } else {
            let entity = self.stack(name).pop().expect("not begin");
            self.push_entity(name.kind(), entity.offset);
          }

          pos += 2;
          continue;
        }
      }

      if b == b'\n' {
        if !self.result.is_empty() {
          self.push_char('\n');
        }

        let non_whitespace = bytes[pos + 1..].iter().position(|&unit| unit != b' ' && unit != b'\t');

        match non_whitespace {
          Some(index) => pos += index + 1,
          None => pos = len,
        }

        if let Some(start) = self.blockquote_start.take() {
          let previous_end = self.prev_blockquote.map(|(offset, length)| offset + length);
          match self.prev_blockquote {
            Some((_, ref mut length)) if previous_end == Some(start - 1) => {
              *length += self.result_len16 - start;
            }
            _ => {
              if let Some((offset, length)) = self.prev_blockquote {
                self.entities.push(Entity::new(EntityKind::Blockquote { collapsed: false }, offset, length));
              }
              self.prev_blockquote = Some((start, self.result_len16 - start - 1));
            }
          }
        }
        continue;
      }

      if b == b'>' && (self.result.is_empty() || self.result.ends_with('\n')) {
        self.blockquote_start = Some(self.result_len16);
        pos += 1;
        continue;
      }

      if b == b' ' && self.blockquote_start == Some(self.result_len16) {
        pos += 1;
        continue;
      }

      let c = char_at(pos);
      self.push_char(c);
      pos += c.len_utf8();
    }
  }

  fn adjust_offsets(&mut self, from: i64, by: i64) {
    for entity in &mut self.entities {
      if entity.offset < from {
        if by > 0 && entity.offset + entity.length > from {
          entity.length += by;
        }
        continue;
      }
      if by >= 0 {
        entity.offset += by;
        continue;
      }
      let magnitude = -by;
      let adjust_total = entity.offset.min(magnitude);
      let adjust_internal = (magnitude - entity.offset).max(0);
      entity.offset -= adjust_total;
      entity.length -= adjust_internal;
    }
    // the tags recovery inserts are ascii, so a byte moves exactly as far as a code unit does
    for (_, items) in &mut self.stacks {
      for item in items {
        if item.offset >= from {
          item.offset = (item.offset + by).max(0);
          item.byte = (item.byte as i64 + by).max(0) as usize;
        }
      }
    }
  }

  fn finish(mut self) -> TextWithEntities {
    if let Some((offset, length)) = self.prev_blockquote.take() {
      self.entities.push(Entity::new(EntityKind::Blockquote { collapsed: false }, offset, length));
    }

    // Restore the opening marker of an unterminated entity as plain text. Keep content already
    // parsed; parse link URLs now because they were not previously treated as text.
    for index in 0..self.stacks.len() {
      let (name, items) = &mut self.stacks[index];
      let name = *name;
      let Some(item) = items.pop() else { continue };

      let tag = if name == StackName::Link { "[" } else { name.tag() };
      self.result.insert_str(item.byte, tag);
      self.result_len16 += utf16_len(tag);
      self.adjust_offsets(item.offset, utf16_len(tag));

      if name == StackName::Link {
        self.inside_link = false;
        self.inside_link_url = false;
        let url = std::mem::take(&mut self.pending_link_url);
        self.push_str("](");
        self.feed(&url);
      }
    }

    let leading = self.result.len() - self.result.trim_start().len();
    let removed = utf16_len(&self.result[..leading]);
    self.result.drain(..leading);
    self.result_len16 -= removed;
    self.adjust_offsets(0, -removed);

    let trailing = self.result.trim_end().len();
    self.result.truncate(trailing);
    let text_len = utf16_len(&self.result);

    self.entities.retain_mut(|entity| {
      if entity.offset + entity.length > text_len {
        entity.length = text_len - entity.offset;
      }
      entity.length > 0
    });

    TextWithEntities {
      text: self.result,
      entities: self.entities,
    }
  }
}

/// What a `[text](url)` link means: telegram's own `tg://` urls address a user, a custom emoji or a
/// date, and anything else is an ordinary link.
fn link_kind(url: String) -> EntityKind {
  if let Some(rest) = url.strip_prefix("tg://user?id=") {
    if let Some((user_id, hash)) = parse_mention(rest) {
      return match hash {
        Some(access_hash) => EntityKind::InputMentionName { user_id, access_hash },
        None => EntityKind::MentionName { user_id },
      };
    }
  } else if let Some(rest) = url.strip_prefix("tg://emoji?id=") {
    if let Some(document_id) = parse_signed_digits(rest) {
      return EntityKind::CustomEmoji { document_id };
    }
  } else if let Some(rest) = url.strip_prefix("tg://time?unix=") {
    if let Some((date, format)) = parse_time(rest) {
      return EntityKind::FormattedDate { date, format };
    }
  }

  let url = if url.starts_with("//") { format!("http:{url}") } else { url };
  EntityKind::TextUrl { url }
}

fn split_digits(rest: &str) -> (&str, &str) {
  rest.split_at(rest.find(|c: char| !c.is_ascii_digit()).unwrap_or(rest.len()))
}

/// Split off a run of `digit` bytes that may start with a `-`.
fn split_signed(rest: &str, digit: fn(&u8) -> bool) -> (&str, &str) {
  let len = rest
    .bytes()
    .enumerate()
    .position(|(index, byte)| !(digit(&byte) || (byte == b'-' && index == 0)))
    .unwrap_or(rest.len());
  rest.split_at(len)
}

pub(crate) fn parse_mention(rest: &str) -> Option<(i64, Option<i64>)> {
  let (digits, rest) = split_digits(rest);
  if digits.is_empty() {
    return None;
  }
  let user_id = digits.parse::<i64>().ok()?;

  if rest.is_empty() {
    return Some((user_id, None));
  }
  if let Some(rest) = rest.strip_prefix("&hash=") {
    let (hash, rest) = split_signed(rest, u8::is_ascii_hexdigit);
    let digits = hash.strip_prefix('-').unwrap_or(hash);
    if digits.is_empty() {
      return None;
    }
    if !rest.is_empty() && !rest.starts_with('&') {
      return None;
    }
    return Some((user_id, Some(parse_hex_i64(hash))));
  }
  if rest.starts_with('&') {
    return Some((user_id, None));
  }
  None
}

/// Telegram's int64 ids arrive as text and stay as text: an id past 2^53 is still exact here, and
/// the app takes a decimal string for one.
fn parse_signed_digits(rest: &str) -> Option<String> {
  let (value, _) = split_signed(rest, u8::is_ascii_digit);
  let digits = value.strip_prefix('-').unwrap_or(value);
  if digits.is_empty() {
    return None;
  }
  Some(value.to_string())
}

fn parse_time(rest: &str) -> Option<(i64, DateFormat)> {
  let (digits, rest) = split_digits(rest);
  if digits.is_empty() {
    return None;
  }
  let date = digits.parse::<i64>().ok()?;
  let format = match rest.strip_prefix("&format=") {
    Some(rest) => {
      let value = rest.find(['&', ')']).map_or(rest, |end| &rest[..end]);
      // an unknown format character leaves the date unformatted rather than failing the parse
      DateFormat::parse(value).unwrap_or_default()
    }
    None => DateFormat::default(),
  };
  Some((date, format))
}

/// Parses a mention link's hexadecimal access hash like `Long.fromString`: signed, with wrapping
/// for values wider than 64 bits.
fn parse_hex_i64(value: &str) -> i64 {
  let (negative, digits) = match value.strip_prefix('-') {
    Some(digits) => (true, digits),
    None => (false, value),
  };
  let mut result: u64 = 0;
  for c in digits.chars() {
    let digit = c.to_digit(16).unwrap_or(0) as u64;
    result = result.wrapping_mul(16).wrapping_add(digit);
  }
  let result = result as i64;
  if negative { result.wrapping_neg() } else { result }
}

/// Whether the last character of the text is whitespace, for hosts that assemble several parses.
pub fn ends_with_whitespace(text: &str) -> bool {
  text.chars().last().is_some_and(char::is_whitespace)
}

#[cfg(test)]
#[path = "markdown_tests.rs"]
mod tests;

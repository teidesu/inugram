use std::borrow::Cow;
use std::fmt::Write as _;
use std::ops::Range;

use crate::{DateFormat, Entity, EntityKind, Sub, TextWithEntities, parse_iso8601, utf16_len, utf16_map};

pub fn escape(text: &str, quote: bool) -> String {
  let mut out = String::with_capacity(text.len());
  escape_into(&mut out, text, quote);
  out
}

fn escape_into(out: &mut String, text: &str, quote: bool) {
  for c in text.chars() {
    push_escaped(out, c, quote);
  }
}

fn escape_char(c: char, quote: bool) -> Option<&'static str> {
  match c {
    '&' => Some("&amp;"),
    '<' => Some("&lt;"),
    '>' => Some("&gt;"),
    '"' if quote => Some("&quot;"),
    '\'' if quote => Some("&apos;"),
    _ => None,
  }
}

fn push_escaped(out: &mut String, c: char, quote: bool) {
  match escape_char(c, quote) {
    Some(escaped) => out.push_str(escaped),
    None => out.push(c),
  }
}

#[derive(Clone, Copy, PartialEq, Eq, Default)]
enum State {
  #[default]
  Text,
  Reference,
  BeforeTagName,
  InTagName,
  InClosingTagName,
  BeforeAttributeName,
  InAttributeName,
  AfterAttributeName,
  BeforeAttributeValue,
  InAttributeValue(Quote),
  InSpecial,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Quote {
  Double,
  Single,
  None,
}

impl Quote {
  fn ends(self, c: char) -> bool {
    match self {
      Quote::Double => c == '"',
      Quote::Single => c == '\'',
      Quote::None => c.is_whitespace() || c == '>',
    }
  }
}

/// Supported named HTML references. The full WHATWG table has about 2200 names and uses 32 KiB.
/// Unsupported names remain literal text, as does an unescaped `&` in a message.
#[rustfmt::skip]
const NAMED: &[(&str, char)] = &[
  ("amp", '&'), 
  ("lt", '<'),
  ("gt", '>'), 
  ("quot", '"'), 
  ("apos", '\''),
  ("nbsp", '\u{a0}')
];

fn decode_reference(body: &str) -> Option<char> {
  if let Some(digits) = body.strip_prefix('#') {
    let code = match digits.strip_prefix(['x', 'X']) {
      Some(hex) => u32::from_str_radix(hex, 16).ok()?,
      None => digits.parse::<u32>().ok()?,
    };
    return char::from_u32(code);
  }
  NAMED.iter().find(|(name, _)| *name == body).map(|(_, value)| *value)
}

/// Attribute values are decoded the same way text is: an `&quot;` inside `href` is a quote.
fn decode_attribute(value: &str) -> Cow<'_, str> {
  if !value.contains('&') {
    return Cow::Borrowed(value);
  }
  let mut out = String::with_capacity(value.len());
  let mut rest = value;
  while let Some(at) = rest.find('&') {
    out.push_str(&rest[..at]);
    rest = &rest[at + 1..];
    match rest.find(';') {
      Some(end) if end <= 32 => match decode_reference(&rest[..end]) {
        Some(decoded) => {
          out.push(decoded);
          rest = &rest[end + 1..];
        }
        None => {
          out.push('&');
        }
      },
      _ => out.push('&'),
    }
  }
  out.push_str(rest);
  Cow::Owned(out)
}

/// Parse html, interpolating `subs` between `parts`. `keep_whitespace` is the `thtml` variant: the
/// text keeps its newlines and runs of spaces, and the parts are dedented first.
pub fn parse(keep_whitespace: bool, parts: &[&str], subs: &[Sub]) -> TextWithEntities {
  let dedented: Vec<String>;
  let borrowed: Vec<&str>;
  let parts: &[&str] = if keep_whitespace {
    dedented = dedent(parts);
    borrowed = dedented.iter().map(String::as_str).collect();
    &borrowed
  } else {
    parts
  };

  let mut parser = Parser::new(keep_whitespace);

  for (index, sub) in subs.iter().enumerate() {
    if let Some(part) = parts.get(index) {
      parser.write(part);
    }
    parser.interpolate(sub);
  }
  if let Some(part) = parts.get(subs.len()) {
    parser.write(part);
  }

  parser.finish()
}

/// The tags that open an entity, one per name: a `</strong>` closes a `<strong>`, not a `<b>`.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Tag {
  B,
  Strong,
  I,
  Em,
  U,
  Ins,
  S,
  Del,
  Strike,
  Blockquote,
  Code,
  Pre,
  Spoiler,
  TgSpoiler,
  Span,
  Emoji,
  TgEmoji,
  TgTime,
  Time,
  A,
}

impl Tag {
  fn parse(name: &str) -> Option<Self> {
    Some(match name {
      "b" => Self::B,
      "strong" => Self::Strong,
      "i" => Self::I,
      "em" => Self::Em,
      "u" => Self::U,
      "ins" => Self::Ins,
      "s" => Self::S,
      "del" => Self::Del,
      "strike" => Self::Strike,
      "blockquote" => Self::Blockquote,
      "code" => Self::Code,
      "pre" => Self::Pre,
      "spoiler" => Self::Spoiler,
      "tg-spoiler" => Self::TgSpoiler,
      "span" => Self::Span,
      "emoji" => Self::Emoji,
      "tg-emoji" => Self::TgEmoji,
      "tg-time" => Self::TgTime,
      "time" => Self::Time,
      "a" => Self::A,
      _ => return None,
    })
  }
}

/// One attribute of the tag being read, as ranges into `Parser::attribute_buf`: the name, then the
/// value running from the name's end to `value_end`.
struct Attribute {
  name: Range<usize>,
  value_end: usize,
}

fn find_attribute<'a>(buf: &'a str, attributes: &[Attribute], key: &str) -> Option<Cow<'a, str>> {
  attributes
    .iter()
    .find(|attribute| &buf[attribute.name.clone()] == key)
    .map(|attribute| decode_attribute(&buf[attribute.name.end..attribute.value_end]))
}

fn has_attribute(buf: &str, attributes: &[Attribute], key: &str) -> bool {
  attributes.iter().any(|attribute| &buf[attribute.name.clone()] == key)
}

struct Parser {
  keep_whitespace: bool,
  state: State,
  reference: String,
  tag_name: String,
  closing: bool,
  attribute_buf: String,
  attribute_name: Range<usize>,
  attributes: Vec<Attribute>,
  stack: Vec<(Tag, Entity)>,
  pre_depth: usize,
  entities: Vec<Entity>,
  plain_text: String,
  plain_len16: i64,
  pending_text: String,
}

impl Parser {
  fn new(keep_whitespace: bool) -> Self {
    Self {
      keep_whitespace,
      state: State::Text,
      reference: String::new(),
      tag_name: String::new(),
      closing: false,
      attribute_buf: String::new(),
      attribute_name: 0..0,
      attributes: Vec::new(),
      stack: Vec::new(),
      pre_depth: 0,
      entities: Vec::new(),
      plain_text: String::new(),
      plain_len16: 0,
      pending_text: String::new(),
    }
  }

  fn in_attribute(&self) -> bool {
    matches!(self.state, State::AfterAttributeName | State::BeforeAttributeValue | State::InAttributeValue(_))
  }

  fn write(&mut self, chunk: &str) {
    for c in chunk.chars() {
      self.feed(c);
    }
  }

  fn write_escaped(&mut self, text: &str) {
    for c in text.chars() {
      match escape_char(c, true) {
        Some(escaped) => self.write(escaped),
        None => self.feed(c),
      }
    }
  }

  fn interpolate(&mut self, sub: &Sub) {
    match sub {
      Sub::Skip => {}
      Sub::Text(text) if self.in_attribute() => self.write_escaped(text),
      Sub::Rich(rich) if self.in_attribute() => self.write_escaped(&rich.text),
      Sub::Text(text) => {
        self.process_pending(false, false);
        self.pending_text.push_str(text);
        self.process_pending(false, true);
      }
      Sub::Rich(rich) => {
        self.process_pending(false, false);
        let base = self.plain_len16;
        self.pending_text.push_str(&rich.text);
        for entity in &rich.entities {
          self.entities.push(Entity::new(entity.kind.clone(), entity.offset + base, entity.length));
        }
        self.process_pending(false, true);
      }
    }
  }

  fn finish(mut self) -> TextWithEntities {
    self.flush_reference();
    self.process_pending(true, false);
    TextWithEntities {
      text: self.plain_text,
      entities: self.entities,
    }
  }

  fn flush_reference(&mut self) {
    if self.state != State::Reference {
      return;
    }
    self.pending_text.push('&');
    self.pending_text.push_str(&self.reference);
    self.reference.clear();
    self.state = State::Text;
  }

  fn finish_tag(&mut self) {
    self.state = State::Text;
    self.tag_name.make_ascii_lowercase();
    if self.tag_name.is_empty() {
      return;
    }
    let name = std::mem::take(&mut self.tag_name);
    if self.closing {
      self.close(&name);
    } else {
      self.open(&name);
      let void = matches!(name.as_str(), "br" | "hr" | "img" | "input" | "meta" | "link");
      if void {
        self.close(&name);
      }
    }
    self.tag_name = name;
    self.tag_name.clear();
  }

  fn start_attribute(&mut self, c: char) {
    self.attribute_name.start = self.attribute_buf.len();
    self.attribute_buf.push(c);
    self.state = State::InAttributeName;
  }

  fn end_attribute_name(&mut self) {
    self.attribute_name.end = self.attribute_buf.len();
  }

  fn finish_attribute(&mut self) {
    self.attribute_buf[self.attribute_name.clone()].make_ascii_lowercase();
    self.attributes.push(Attribute {
      name: self.attribute_name.clone(),
      value_end: self.attribute_buf.len(),
    });
  }

  fn feed(&mut self, c: char) {
    match self.state {
      State::Text => match c {
        '<' => self.state = State::BeforeTagName,
        '&' => {
          self.state = State::Reference;
          self.reference.clear();
        }
        _ => self.pending_text.push(c),
      },
      State::Reference => {
        if c == ';' {
          self.state = State::Text;
          match decode_reference(&self.reference) {
            Some(decoded) => self.pending_text.push(decoded),
            None => {
              self.pending_text.push('&');
              self.pending_text.push_str(&self.reference);
              self.pending_text.push(';');
            }
          }
          self.reference.clear();
        } else if c.is_ascii_alphanumeric() || c == '#' {
          self.reference.push(c);
          // a reference is never this long, so this is text that happened to start with `&`
          if self.reference.len() > 32 {
            self.flush_reference();
          }
        } else {
          self.flush_reference();
          self.feed(c);
        }
      }
      State::BeforeTagName => {
        self.closing = false;
        self.tag_name.clear();
        self.attribute_buf.clear();
        self.attributes.clear();
        match c {
          '/' => {
            self.closing = true;
            self.state = State::InClosingTagName;
          }
          '!' | '?' => self.state = State::InSpecial,
          c if c.is_ascii_alphabetic() => {
            self.tag_name.push(c);
            self.state = State::InTagName;
          }
          // `<` that opens nothing is just text, which is what an unescaped `<` in a message is
          _ => {
            self.pending_text.push('<');
            self.state = State::Text;
            self.feed(c);
          }
        }
      }
      State::InSpecial => {
        if c == '>' {
          self.state = State::Text;
        }
      }
      State::InTagName | State::InClosingTagName => match c {
        '>' => self.finish_tag(),
        '/' => self.state = State::BeforeAttributeName,
        c if c.is_whitespace() => self.state = State::BeforeAttributeName,
        _ => self.tag_name.push(c),
      },
      State::BeforeAttributeName => match c {
        '>' => self.finish_tag(),
        '/' => {}
        c if c.is_whitespace() => {}
        _ => self.start_attribute(c),
      },
      State::InAttributeName => match c {
        '=' => {
          self.end_attribute_name();
          self.state = State::BeforeAttributeValue;
        }
        '>' => {
          self.end_attribute_name();
          self.finish_attribute();
          self.finish_tag();
        }
        '/' => {
          self.end_attribute_name();
          self.finish_attribute();
          self.state = State::BeforeAttributeName;
        }
        c if c.is_whitespace() => {
          self.end_attribute_name();
          self.state = State::AfterAttributeName;
        }
        _ => self.attribute_buf.push(c),
      },
      State::AfterAttributeName => match c {
        '=' => self.state = State::BeforeAttributeValue,
        '>' => {
          self.finish_attribute();
          self.finish_tag();
        }
        c if c.is_whitespace() => {}
        _ => {
          self.finish_attribute();
          self.start_attribute(c);
        }
      },
      State::BeforeAttributeValue => match c {
        '"' => self.state = State::InAttributeValue(Quote::Double),
        '\'' => self.state = State::InAttributeValue(Quote::Single),
        '>' => {
          self.finish_attribute();
          self.finish_tag();
        }
        c if c.is_whitespace() => {}
        _ => {
          self.attribute_buf.push(c);
          self.state = State::InAttributeValue(Quote::None);
        }
      },
      State::InAttributeValue(quote) => {
        if !quote.ends(c) {
          self.attribute_buf.push(c);
          return;
        }
        self.finish_attribute();
        if c == '>' {
          self.finish_tag();
        } else {
          self.state = State::BeforeAttributeName;
        }
      }
    }
  }

  fn inside_pre(&self) -> bool {
    self.pre_depth > 0
  }

  /// Move the pending text into the plain text, collapsing whitespace unless something keeps it.
  /// `/[^\S ]+/g` to a single space: every whitespace run collapses, except that a non-breaking
  /// space is text rather than whitespace and becomes an ordinary space instead.
  fn process_pending(&mut self, tag_end: bool, force_keep_whitespace: bool) {
    if self.pending_text.is_empty() {
      return;
    }
    let start = self.plain_text.len();

    if self.inside_pre() || self.keep_whitespace || force_keep_whitespace {
      for c in self.pending_text.chars() {
        self.plain_text.push(if c == '\u{a0}' { ' ' } else { c });
      }
    } else {
      let mut trimming_start = self.plain_text.is_empty() || self.plain_text.ends_with(char::is_whitespace);
      let mut in_run = false;
      for c in self.pending_text.chars() {
        if trimming_start && c.is_whitespace() {
          continue;
        }
        trimming_start = false;
        if c == '\u{a0}' {
          in_run = false;
          self.plain_text.push(' ');
        } else if c.is_whitespace() {
          if !in_run {
            self.plain_text.push(' ');
            in_run = true;
          }
        } else {
          in_run = false;
          self.plain_text.push(c);
        }
      }
      if tag_end {
        let kept = self.plain_text[start..].trim_end().len();
        self.plain_text.truncate(start + kept);
      }
    }
    self.pending_text.clear();

    let added = utf16_len(&self.plain_text[start..]);
    for (_, entity) in &mut self.stack {
      entity.length += added;
    }
    self.plain_len16 += added;
  }

  fn open(&mut self, name: &str) {
    self.process_pending(false, false);

    // inside `pre` nothing is markup, except a `code` naming the language and the closing `pre`
    if self.inside_pre() && name != "pre" {
      if name == "code" {
        let language = find_attribute(&self.attribute_buf, &self.attributes, "class").and_then(|class| {
          class.split_whitespace().find_map(|part| part.strip_prefix("language-")).map(str::to_string)
        });
        if let Some(language) = language {
          if let Some((
            Tag::Pre,
            Entity {
              kind: EntityKind::Pre { language: current },
              ..
            },
          )) = self.stack.iter_mut().rev().find(|(tag, _)| *tag == Tag::Pre)
          {
            *current = language;
          }
        }
      }
      return;
    }

    if name == "br" {
      self.pending_text.push('\n');
      self.process_pending(true, true);
      return;
    }

    let Some(tag) = Tag::parse(name) else { return };
    let attribute = |key: &str| find_attribute(&self.attribute_buf, &self.attributes, key);
    let kind = match tag {
      Tag::B | Tag::Strong => EntityKind::Bold,
      Tag::I | Tag::Em => EntityKind::Italic,
      Tag::U | Tag::Ins => EntityKind::Underline,
      Tag::S | Tag::Del | Tag::Strike => EntityKind::Strike,
      Tag::Blockquote => EntityKind::Blockquote {
        collapsed: has_attribute(&self.attribute_buf, &self.attributes, "collapsible")
          || has_attribute(&self.attribute_buf, &self.attributes, "expandable"),
      },
      Tag::Code => EntityKind::Code,
      Tag::Pre => EntityKind::Pre {
        language: attribute("language").map(Cow::into_owned).unwrap_or_default(),
      },
      Tag::Spoiler | Tag::TgSpoiler => EntityKind::Spoiler,
      Tag::Span => {
        if attribute("class").as_deref() != Some("tg-spoiler") {
          return;
        }
        EntityKind::Spoiler
      }
      Tag::Emoji | Tag::TgEmoji => {
        let Some(id) = attribute("id").filter(|id| !id.is_empty()).or_else(|| attribute("emoji-id")) else {
          return;
        };
        if !is_signed_digits(&id) {
          return;
        }
        EntityKind::CustomEmoji { document_id: id.into_owned() }
      }
      Tag::TgTime => {
        let Some(unix) = attribute("unix") else { return };
        if unix.is_empty() || !unix.chars().all(|c| c.is_ascii_digit()) {
          return;
        }
        let Ok(date) = unix.parse::<i64>() else { return };
        EntityKind::FormattedDate {
          date,
          format: date_format(attribute("format").as_deref()),
        }
      }
      Tag::Time => {
        let Some(datetime) = attribute("datetime") else { return };
        let Some(date) = parse_iso8601(&datetime) else { return };
        EntityKind::FormattedDate {
          date,
          format: date_format(attribute("format").as_deref()),
        }
      }
      Tag::A => {
        let Some(url) = attribute("href").filter(|url| !url.is_empty()) else { return };
        link_kind(url.into_owned())
      }
    };

    if tag == Tag::Pre {
      self.pre_depth += 1;
    }
    self.stack.push((tag, Entity::new(kind, self.plain_len16, 0)));
  }

  fn close(&mut self, name: &str) {
    self.process_pending(true, false);

    if name != "pre" && self.inside_pre() {
      return;
    }

    let Some(tag) = Tag::parse(name) else { return };
    let Some(index) = self.stack.iter().rposition(|(open, _)| *open == tag) else {
      return;
    };
    let (_, entity) = self.stack.remove(index);

    // a `pre` inside a `pre` closes without becoming an entity of its own
    if tag == Tag::Pre {
      self.pre_depth -= 1;
      if self.inside_pre() {
        return;
      }
    }
    self.entities.push(entity);
  }
}

fn date_format(format: Option<&str>) -> DateFormat {
  match format {
    Some(format) => DateFormat::parse(format).unwrap_or_default(),
    None => DateFormat::default(),
  }
}

fn is_signed_digits(value: &str) -> bool {
  let digits = value.strip_prefix('-').unwrap_or(value);
  !digits.is_empty() && digits.chars().all(|c| c.is_ascii_digit())
}

fn link_kind(url: String) -> EntityKind {
  if let Some(rest) = url.strip_prefix("tg://user?id=") {
    if let Some((user_id, hash)) = crate::markdown::parse_mention(rest) {
      return match hash {
        Some(access_hash) => EntityKind::InputMentionName { user_id, access_hash },
        None => EntityKind::MentionName { user_id },
      };
    }
  }
  let url = if url.starts_with("//") { format!("http:{url}") } else { url };
  EntityKind::TextUrl { url }
}

fn dedent(parts: &[&str]) -> Vec<String> {
  // a line spanning an interpolation is one line, and its indent is measured up to the
  // interpolation when nothing but whitespace precedes it
  let mut min_indent = usize::MAX;
  for (part_index, part) in parts.iter().enumerate() {
    let continues = part_index + 1 < parts.len();
    let newlines = part.bytes().filter(|byte| *byte == b'\n').count();
    for (line_index, line) in part.split('\n').enumerate().skip(1) {
      let indent = line
        .chars()
        .position(|c| !c.is_whitespace())
        .or_else(|| (continues && line_index == newlines).then(|| line.chars().count()));
      if let Some(indent) = indent {
        min_indent = min_indent.min(indent);
      }
    }
  }
  if min_indent == usize::MAX {
    min_indent = 0;
  }

  let mut result: Vec<String> = parts
    .iter()
    .map(|part| {
      let mut out = String::with_capacity(part.len());
      for (line_index, line) in part.split('\n').enumerate() {
        // a part's first line continues the line the interpolation before it broke, and only a
        // line that really starts one carries the indent
        if line_index == 0 {
          out.push_str(line);
          continue;
        }
        out.push('\n');
        let skip = line.char_indices().nth(min_indent).map_or(line.len(), |(offset, _)| offset);
        out.push_str(&line[skip..]);
      }
      out
    })
    .collect();

  if let Some(first) = result.first_mut() {
    if first.starts_with('\n') {
      first.remove(0);
    }
  }
  if let Some(last) = result.last_mut() {
    let trimmed = last.trim_end_matches([' ', '\t']);
    if trimmed.ends_with('\n') {
      last.truncate(trimmed.len() - 1);
    }
  }

  result
}

pub fn unparse(keep_whitespace: bool, text: &str, entities: &[Entity]) -> String {
  let map = utf16_map(text);
  let length = (map.len() - 1) as i64;
  let mut out = String::with_capacity(text.len());
  unparse_inner(&mut out, keep_whitespace, text, &map, entities, 0, 0, length, length);
  out
}

/// `bound` is the absolute end of the enclosing substring: an entity whose length runs past its
/// parent is truncated to it, rather than reaching back into the rest of the text.
#[allow(clippy::too_many_arguments)]
fn unparse_inner(
  out: &mut String,
  keep_whitespace: bool,
  full: &str,
  map: &[usize],
  entities: &[Entity],
  entities_offset: usize,
  offset: i64,
  length: i64,
  bound: i64,
) {
  let text = substring(full, map, offset, (offset + length).min(bound));
  if text.is_empty() {
    return;
  }

  if entities.len() == entities_offset {
    write_text(out, keep_whitespace, text);
    return;
  }

  let end = offset + length;
  let mut last_offset: i64 = 0;

  for index in entities_offset..entities.len() {
    let entity = &entities[index];
    if entity.offset >= end {
      break;
    }

    let mut entity_offset = entity.offset;
    let mut entity_length = entity.length;

    if entity_offset < 0 {
      entity_length += entity_offset;
      entity_offset = 0;
    }

    let mut relative_offset = entity_offset - offset;

    if relative_offset > last_offset {
      write_text(
        out,
        keep_whitespace,
        substring(full, map, offset + last_offset, (offset + relative_offset).min(bound)),
      );
    } else if relative_offset < last_offset {
      entity_length -= last_offset - relative_offset;
      relative_offset = last_offset;
    }

    if entity_length <= 0 || relative_offset >= end || relative_offset < 0 {
      continue;
    }

    let inner_bound = (offset + relative_offset + entity_length).min(bound);
    let substr = substring(full, map, offset + relative_offset, inner_bound);
    if substr.is_empty() {
      continue;
    }

    let write_inner = |out: &mut String| {
      unparse_inner(
        out,
        keep_whitespace,
        full,
        map,
        entities,
        index + 1,
        offset + relative_offset,
        entity_length,
        inner_bound,
      )
    };

    match &entity.kind {
      EntityKind::Pre { language } => {
        out.push_str("<pre");
        if !language.is_empty() {
          let _ = write!(out, " language=\"{language}\"");
        }
        out.push('>');
        escape_into(out, substr, false);
        out.push_str("</pre>");
      }
      // the link text doubles as its target, so it is rendered once and written twice
      EntityKind::Email | EntityKind::Url => {
        let mut inner = String::new();
        write_inner(&mut inner);
        let scheme = if entity.kind == EntityKind::Email { "mailto:" } else { "" };
        let _ = write!(out, "<a href=\"{scheme}{inner}\">{inner}</a>");
      }
      kind => {
        let Some(close) = write_open_tag(out, kind) else {
          last_offset = relative_offset;
          continue;
        };
        write_inner(out);
        out.push_str(close);
      }
    }

    last_offset = relative_offset + entity_length;
  }

  write_text(out, keep_whitespace, substring(full, map, offset + last_offset, (offset + length).min(bound)));
}

fn write_open_tag(out: &mut String, kind: &EntityKind) -> Option<&'static str> {
  let close = match kind {
    EntityKind::Bold => {
      out.push_str("<b>");
      "</b>"
    }
    EntityKind::Italic => {
      out.push_str("<i>");
      "</i>"
    }
    EntityKind::Underline => {
      out.push_str("<u>");
      "</u>"
    }
    EntityKind::Strike => {
      out.push_str("<s>");
      "</s>"
    }
    EntityKind::Code => {
      out.push_str("<code>");
      "</code>"
    }
    EntityKind::Spoiler => {
      out.push_str("<spoiler>");
      "</spoiler>"
    }
    EntityKind::Blockquote { collapsed } => {
      out.push_str(if *collapsed { "<blockquote collapsible>" } else { "<blockquote>" });
      "</blockquote>"
    }
    EntityKind::TextUrl { url } => {
      out.push_str("<a href=\"");
      escape_into(out, url, true);
      out.push_str("\">");
      "</a>"
    }
    EntityKind::MentionName { user_id } | EntityKind::InputMentionName { user_id, .. } => {
      let _ = write!(out, "<a href=\"tg://user?id={user_id}\">");
      "</a>"
    }
    EntityKind::FormattedDate { date, format } => {
      let _ = write!(out, "<tg-time unix=\"{date}\"");
      let format = format.to_format_string();
      if !format.is_empty() {
        let _ = write!(out, " format=\"{format}\"");
      }
      out.push('>');
      "</tg-time>"
    }
    EntityKind::Pre { .. }
    | EntityKind::Email
    | EntityKind::Url
    | EntityKind::CustomEmoji { .. }
    | EntityKind::Mention
    | EntityKind::Other(_) => return None,
  };
  Some(close)
}

fn write_text(out: &mut String, keep_whitespace: bool, text: &str) {
  if keep_whitespace {
    escape_into(out, text, false);
    return;
  }
  let mut run = 0;
  for c in text.chars() {
    if c == ' ' {
      run += 1;
      continue;
    }
    write_spaces(out, run);
    run = 0;
    if c == '\n' {
      out.push_str("<br>");
    } else {
      push_escaped(out, c, false);
    }
  }
  write_spaces(out, run);
}

fn write_spaces(out: &mut String, run: usize) {
  if run >= 2 {
    for _ in 0..run {
      out.push_str("&nbsp;");
    }
  } else if run == 1 {
    out.push(' ');
  }
}

fn substring<'a>(text: &'a str, map: &[usize], start: i64, end: i64) -> &'a str {
  let last = (map.len() - 1) as i64;
  let start = start.clamp(0, last) as usize;
  let end = end.clamp(start as i64, last) as usize;
  &text[map[start]..map[end]]
}

#[cfg(test)]
#[path = "html_tests.rs"]
mod tests;

use csscolorparser::Color;

pub fn parse_color(text: &str) -> Option<i32> {
  let text = text.trim();
  if text.is_empty() {
    return None;
  }
  if text.eq_ignore_ascii_case("currentcolor") {
    return Some(0);
  }
  let Color { r, g, b, a } = text.parse::<Color>().ok()?;
  let channel = |value: f32| (value.clamp(0.0, 1.0) * 255.0).round() as u32;
  let argb = (channel(a) << 24) | (channel(r) << 16) | (channel(g) << 8) | channel(b);
  Some(argb as i32)
}

#[derive(Debug, Clone, PartialEq)]
pub struct Font {
  pub size: f32,
  pub weight: u16,
  pub italic: bool,
  pub small_caps: bool,
  pub families: Vec<String>,
}

impl Font {
  pub fn default_font() -> Font {
    Font {
      size: 10.0,
      weight: 400,
      italic: false,
      small_caps: false,
      families: vec!["sans-serif".to_string()],
    }
  }
}

fn px_per_unit(unit: &str) -> Option<f32> {
  Some(match unit {
    "px" => 1.0,
    "in" => 96.0,
    "cm" => 96.0 / 2.54,
    "mm" => 96.0 / 25.4,
    "q" => 96.0 / 101.6,
    "pt" => 96.0 / 72.0,
    "pc" => 16.0,
    _ => return None,
  })
}

fn parse_length(text: &str) -> Option<f32> {
  let lower = text.to_ascii_lowercase();
  let at = lower.find(|c: char| c.is_ascii_alphabetic() || c == '%')?;
  let (number, unit) = lower.split_at(at);
  let value: f32 = number.parse().ok()?;
  if !value.is_finite() {
    return None;
  }
  Some(value * px_per_unit(unit)?)
}

fn parse_weight(token: &str) -> Option<u16> {
  match token {
    "normal" => Some(400),
    "bold" => Some(700),
    "lighter" => Some(100),
    "bolder" => Some(700),
    _ => {
      let value: f32 = token.parse().ok()?;
      let rounded = value.round();
      (1.0..=1000.0).contains(&rounded).then_some(rounded as u16)
    }
  }
}

fn tokenize(text: &str) -> Option<Vec<String>> {
  let mut tokens = Vec::new();
  let mut current = String::new();
  let mut quote: Option<char> = None;

  for ch in text.chars() {
    match quote {
      Some(open) if ch == open => {
        quote = None;
        tokens.push(std::mem::take(&mut current));
      }
      Some(_) => current.push(ch),
      None if ch == '"' || ch == '\'' => {
        if !current.is_empty() {
          tokens.push(std::mem::take(&mut current));
        }
        quote = Some(ch);
      }
      None if ch.is_whitespace() || ch == ',' => {
        if !current.is_empty() {
          tokens.push(std::mem::take(&mut current));
        }
        if ch == ',' {
          tokens.push(",".to_string());
        }
      }
      None => current.push(ch),
    }
  }
  if quote.is_some() {
    return None;
  }
  if !current.is_empty() {
    tokens.push(current);
  }
  Some(tokens)
}

pub fn parse_font(text: &str) -> Option<Font> {
  let lowered = text.trim();
  if lowered.is_empty() {
    return None;
  }
  let tokens = tokenize(lowered)?;
  if tokens.is_empty() {
    return None;
  }

  let mut font = Font {
    size: 0.0,
    weight: 400,
    italic: false,
    small_caps: false,
    families: Vec::new(),
  };

  let mut size_at = None;
  for (index, token) in tokens.iter().enumerate() {
    let (size_part, line_part) = match token.split_once('/') {
      Some((size, line)) => (size, Some(line)),
      None => (token.as_str(), None),
    };
    let Some(size) = parse_length(size_part) else {
      continue;
    };
    if size <= 0.0 || !size.is_finite() {
      return None;
    }
    if let Some(line) = line_part {
      if parse_length(line).is_none() && !line.eq_ignore_ascii_case("normal") && line.parse::<f32>().is_err() {
        return None;
      }
    }
    font.size = size;
    size_at = Some(index);
    break;
  }

  let size_at = size_at?;

  for token in &tokens[..size_at] {
    let lower = token.to_ascii_lowercase();
    match lower.as_str() {
      "normal" => {}
      "italic" | "oblique" => font.italic = true,
      "small-caps" => font.small_caps = true,
      "ultra-condensed" | "extra-condensed" | "condensed" | "semi-condensed" | "semi-expanded" | "expanded"
      | "extra-expanded" | "ultra-expanded" => {}
      _ => font.weight = parse_weight(&lower)?,
    }
  }

  for token in &tokens[size_at + 1..] {
    if token == "," {
      continue;
    }
    font.families.push(token.clone());
  }
  if font.families.is_empty() {
    return None;
  }
  Some(font)
}

#[cfg(test)]
#[path = "css_tests.rs"]
mod css_tests;

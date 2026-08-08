//! The two css parsers `inu.canvas` needs and the platform does not have.
//!
//! Android's `Color.parseColor` covers hex and ~140 names and refuses everything else, and nothing
//! on the platform reads the `font` shorthand at all - so `ctx.fillStyle = 'rgb(255 0 0 / 50%)'`
//! and `ctx.font = 'italic bold 24px Roboto'` are ours to understand. Both are pure string work,
//! which is why they are here rather than in the host: rust is where they can be unit-tested, and
//! the host would have to be handed the parsed form regardless.
//!
//! Colour is `csscolorparser`'s; what this module owns is the ARGB packing the host wants and the
//! `currentColor`/`transparent` spellings a canvas can meet. The font shorthand is entirely ours.

use csscolorparser::Color;

/// One colour, as the packed `0xAARRGGBB` int android's `Paint.setColor` takes.
pub fn parse_color(text: &str) -> Option<i32> {
    let text = text.trim();
    if text.is_empty() {
        return None;
    }
    // canvas has no cascade, so there is no colour to inherit; the spec makes it fully transparent
    // black rather than an error
    if text.eq_ignore_ascii_case("currentcolor") {
        return Some(0);
    }
    let Color { r, g, b, a } = text.parse::<Color>().ok()?;
    let channel = |value: f32| (value.clamp(0.0, 1.0) * 255.0).round() as u32;
    let argb = (channel(a) << 24) | (channel(r) << 16) | (channel(g) << 8) | channel(b);
    Some(argb as i32)
}

/// A parsed `font` shorthand, in the form the host's `Paint`/`Typeface` want.
#[derive(Debug, Clone, PartialEq)]
pub struct Font {
    /// px, already resolved: the shorthand's `pt`/`in`/`cm`/`mm`/`pc`/`Q` are absolute, and the
    /// relative units (`em`, `%`, `rem`, ...) have nothing to be relative *to* on a canvas
    pub size: f32,
    /// px, or `None` for the shorthand's `normal`, which the host reads as "the font's own"
    pub line_height: Option<f32>,
    /// 100..=900, the css numeric scale; `normal` is 400 and `bold` is 700
    pub weight: u16,
    pub italic: bool,
    pub small_caps: bool,
    /// every family named, in the order the shorthand listed them, unquoted
    pub families: Vec<String>,
}

impl Font {
    /// The canvas default, which is what a context starts at and what an unparseable assignment
    /// leaves it as.
    pub fn default_font() -> Font {
        Font {
            size: 10.0,
            line_height: None,
            weight: 400,
            italic: false,
            small_caps: false,
            families: vec!["sans-serif".to_string()],
        }
    }
}

/// css absolute length units, in px per unit. `1in` is 96px by definition, and every other one is
/// defined against that.
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
        // there is no parent font on a canvas, so the two relative keywords resolve against the
        // initial value rather than being refused
        "lighter" => Some(100),
        "bolder" => Some(700),
        _ => {
            let value: f32 = token.parse().ok()?;
            let rounded = value.round();
            (1.0..=1000.0).contains(&rounded).then_some(rounded as u16)
        }
    }
}

/// Splits on top-level whitespace, keeping quoted runs whole.
///
/// A family name may be quoted and contain spaces (`"Noto Color Emoji"`), so a plain
/// `split_whitespace` would turn one family into three.
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
                // a comma is a separator *and* a token: it is what ends one family name and starts
                // the next, and the size/family boundary has to be able to see it
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

/// The css `font` shorthand, per the subset a canvas can mean anything by.
///
/// The spec's grammar is `[style || variant || weight || stretch]? size[/line-height] family`, and
/// what makes it parseable at all is that **the size is the first token that is a length**:
/// everything before it is an unordered keyword pile and everything after it is the family list.
/// `stretch` is read and discarded, `android.d.ts` listing it among the knobs with no mapping.
pub fn parse_font(text: &str) -> Option<Font> {
    let lowered = text.trim();
    if lowered.is_empty() {
        return None;
    }
    let tokens = tokenize(lowered)?;
    if tokens.is_empty() {
        return None;
    }

    let mut font =
        Font { size: 0.0, line_height: None, weight: 400, italic: false, small_caps: false, families: Vec::new() };

    let mut size_at = None;
    for (index, token) in tokens.iter().enumerate() {
        let (size_part, line_part) = match token.split_once('/') {
            Some((size, line)) => (size, Some(line)),
            None => (token.as_str(), None),
        };
        let Some(size) = parse_length(size_part) else {
            continue;
        };
        // a family may legally be called `10px`, but only after the size, so the *first* length
        // wins and everything past it is a name
        if size <= 0.0 || !size.is_finite() {
            return None;
        }
        font.size = size;
        font.line_height = match line_part {
            Some(line) => match parse_length(line) {
                Some(value) => Some(value),
                // `normal`, or a unitless multiplier, which is the one relative form that resolves
                None if line.eq_ignore_ascii_case("normal") => None,
                None => Some(line.parse::<f32>().ok()? * size),
            },
            None => None,
        };
        size_at = Some(index);
        break;
    }

    // a shorthand without a size is not a shorthand: the spec requires both it and a family
    let size_at = size_at?;

    for token in &tokens[..size_at] {
        let lower = token.to_ascii_lowercase();
        match lower.as_str() {
            "normal" => {}
            "italic" | "oblique" => font.italic = true,
            "small-caps" => font.small_caps = true,
            // read and dropped: the platform's `Typeface` has no width axis to set
            "ultra-condensed" | "extra-condensed" | "condensed" | "semi-condensed" | "semi-expanded" | "expanded"
            | "extra-expanded" | "ultra-expanded" => {}
            _ => match parse_weight(&lower) {
                Some(weight) => font.weight = weight,
                None => return None,
            },
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

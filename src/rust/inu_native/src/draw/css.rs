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
mod colors {
    use super::*;

    fn argb(text: &str) -> Option<u32> {
        parse_color(text).map(|value| value as u32)
    }

    #[test]
    fn the_shapes_the_platform_already_handles_still_work() {
        assert_eq!(argb("#f00"), Some(0xffff0000));
        assert_eq!(argb("#ff0000"), Some(0xffff0000));
        assert_eq!(argb("#ff000080"), Some(0x80ff0000));
        assert_eq!(argb("red"), Some(0xffff0000));
        assert_eq!(argb("rebeccapurple"), Some(0xff663399));
    }

    #[test]
    fn the_shapes_it_does_not_are_the_reason_this_exists() {
        assert_eq!(argb("rgb(255, 0, 0)"), Some(0xffff0000));
        assert_eq!(argb("rgb(255 0 0)"), Some(0xffff0000));
        assert_eq!(argb("rgba(255, 0, 0, 0.5)"), Some(0x80ff0000));
        assert_eq!(argb("rgb(255 0 0 / 50%)"), Some(0x80ff0000));
        assert_eq!(argb("hsl(0, 100%, 50%)"), Some(0xffff0000));
        assert_eq!(argb("hsl(0deg 100% 50% / 100%)"), Some(0xffff0000));
        assert_eq!(argb("hwb(0 0% 0%)"), Some(0xffff0000));
    }

    #[test]
    fn transparent_is_a_colour_and_not_a_refusal() {
        assert_eq!(argb("transparent"), Some(0));
        assert_eq!(argb("rgba(0, 0, 0, 0)"), Some(0));
    }

    #[test]
    fn current_color_is_transparent_black_rather_than_an_error() {
        // a canvas has no cascade, so there is nothing for it to inherit; the spec says so
        assert_eq!(argb("currentColor"), Some(0));
        assert_eq!(argb("CURRENTCOLOR"), Some(0));
    }

    #[test]
    fn the_four_digit_hex_is_rgba_and_not_a_typo() {
        // css colour 4 added it, so `#ff00` is opaque-yellow-at-zero-alpha rather than nonsense
        assert_eq!(argb("#ff00"), Some(0x00ffff00));
        assert_eq!(argb("#f00f"), Some(0xffff0000));
    }

    #[test]
    fn a_colour_is_case_and_whitespace_insensitive() {
        assert_eq!(argb("  RED  "), Some(0xffff0000));
        assert_eq!(argb("#FF0000"), Some(0xffff0000));
    }

    #[test]
    fn nonsense_is_refused_rather_than_rendered_as_black() {
        for text in ["", "   ", "notacolour", "#ff", "#ff000", "rgb(", "rgb(1,2)", "12345"] {
            assert_eq!(parse_color(text), None, "accepted {text:?}");
        }
    }

    #[test]
    fn an_out_of_range_channel_is_clamped_the_way_css_clamps_it() {
        assert_eq!(argb("rgb(300, -20, 0)"), Some(0xffff0000));
        assert_eq!(argb("rgba(0, 0, 0, 5)"), Some(0xff000000));
    }
}

#[cfg(test)]
mod fonts {
    use super::*;

    fn font(text: &str) -> Font {
        parse_font(text).unwrap_or_else(|| panic!("could not parse {text:?}"))
    }

    #[test]
    fn the_minimal_shorthand_is_a_size_and_a_family() {
        let parsed = font("10px sans-serif");
        assert_eq!(parsed.size, 10.0);
        assert_eq!(parsed.families, vec!["sans-serif".to_string()]);
        assert_eq!(parsed.weight, 400);
        assert!(!parsed.italic);
    }

    #[test]
    fn the_keyword_pile_before_the_size_is_unordered() {
        // the spec's `[style || variant || weight || stretch]?` is an unordered set, so every
        // permutation below is the same declaration
        for text in [
            "italic small-caps bold 24px Roboto",
            "bold italic small-caps 24px Roboto",
            "small-caps bold italic 24px Roboto",
        ] {
            let parsed = font(text);
            assert!(parsed.italic, "{text}");
            assert!(parsed.small_caps, "{text}");
            assert_eq!(parsed.weight, 700, "{text}");
            assert_eq!(parsed.size, 24.0, "{text}");
            assert_eq!(parsed.families, vec!["Roboto".to_string()], "{text}");
        }
    }

    #[test]
    fn a_numeric_weight_is_the_css_scale() {
        assert_eq!(font("100 12px x").weight, 100);
        assert_eq!(font("350 12px x").weight, 350);
        assert_eq!(font("900 12px x").weight, 900);
        assert_eq!(font("normal 12px x").weight, 400);
        assert_eq!(font("bold 12px x").weight, 700);
    }

    #[test]
    fn the_relative_weights_resolve_against_the_initial_value() {
        // there is no parent font on a canvas, so `lighter`/`bolder` have to mean something fixed
        assert_eq!(font("lighter 12px x").weight, 100);
        assert_eq!(font("bolder 12px x").weight, 700);
    }

    #[test]
    fn every_absolute_length_unit_resolves_to_px() {
        assert_eq!(font("12px x").size, 12.0);
        assert_eq!(font("12pt x").size, 16.0);
        assert_eq!(font("1in x").size, 96.0);
        assert_eq!(font("1pc x").size, 16.0);
        assert!((font("2.54cm x").size - 96.0).abs() < 0.01);
        assert!((font("25.4mm x").size - 96.0).abs() < 0.01);
    }

    #[test]
    fn a_relative_unit_has_nothing_to_be_relative_to_and_is_refused() {
        // a canvas has no parent box and no root font, so `2em` names no size
        for text in ["2em x", "150% x", "2rem x", "5vw x"] {
            assert_eq!(parse_font(text), None, "accepted {text:?}");
        }
    }

    #[test]
    fn a_line_height_rides_on_the_size_token() {
        assert_eq!(font("12px/20px x").line_height, Some(20.0));
        assert_eq!(font("12px/normal x").line_height, None);
        // a unitless line-height is a multiplier, the one relative form that resolves here
        assert_eq!(font("12px/1.5 x").line_height, Some(18.0));
        assert_eq!(font("12px x").line_height, None);
        assert_eq!(font("12px/20px x").size, 12.0);
    }

    #[test]
    fn a_family_list_keeps_its_order_and_drops_the_commas() {
        let parsed = font("12px Roboto, Arial, sans-serif");
        assert_eq!(parsed.families, vec!["Roboto".to_string(), "Arial".to_string(), "sans-serif".to_string()]);
    }

    #[test]
    fn a_quoted_family_stays_one_family() {
        // this is the whole reason the tokenizer is not `split_whitespace`
        assert_eq!(font("12px \"Noto Color Emoji\"").families, vec!["Noto Color Emoji".to_string()]);
        assert_eq!(font("12px 'Times New Roman'").families, vec!["Times New Roman".to_string()]);
        assert_eq!(
            font("12px \"Noto Color Emoji\", Roboto").families,
            vec!["Noto Color Emoji".to_string(), "Roboto".to_string()]
        );
    }

    #[test]
    fn the_first_length_is_the_size_and_everything_after_it_is_a_name() {
        // a family may legally be called `10px`; only its position says which one it is
        let parsed = font("bold 24px 10px");
        assert_eq!(parsed.size, 24.0);
        assert_eq!(parsed.families, vec!["10px".to_string()]);
    }

    #[test]
    fn a_shorthand_missing_either_half_is_refused() {
        for text in ["", "   ", "bold", "12px", "bold italic", "sans-serif"] {
            assert_eq!(parse_font(text), None, "accepted {text:?}");
        }
    }

    #[test]
    fn an_unknown_keyword_before_the_size_is_refused_rather_than_ignored() {
        // silently dropping it would render at a weight the plugin did not ask for
        assert_eq!(parse_font("wobbly 12px x"), None);
        assert_eq!(parse_font("bold nonsense 12px x"), None);
    }

    #[test]
    fn a_stretch_keyword_parses_and_is_dropped() {
        // `android.d.ts` lists fontStretch among the knobs with no mapping, so it must not be a
        // parse failure either
        let parsed = font("condensed bold 12px x");
        assert_eq!(parsed.weight, 700);
        assert_eq!(parsed.families, vec!["x".to_string()]);
    }

    #[test]
    fn a_nonpositive_or_infinite_size_is_refused() {
        for text in ["0px x", "-12px x"] {
            assert_eq!(parse_font(text), None, "accepted {text:?}");
        }
    }

    #[test]
    fn an_unterminated_quote_is_refused_rather_than_swallowing_the_rest() {
        assert_eq!(parse_font("12px \"Roboto"), None);
    }

    #[test]
    fn keywords_are_case_insensitive_but_a_family_name_is_not() {
        let parsed = font("ITALIC BOLD 12px Roboto");
        assert!(parsed.italic);
        assert_eq!(parsed.weight, 700);
        // a family is matched against what the host registered, which is case-sensitive
        assert_eq!(parsed.families, vec!["Roboto".to_string()]);
    }

    #[test]
    fn the_default_is_the_one_a_context_starts_at() {
        let parsed = Font::default_font();
        assert_eq!(parsed.size, 10.0);
        assert_eq!(parsed.families, vec!["sans-serif".to_string()]);
        assert_eq!(parse_font("10px sans-serif"), Some(parsed));
    }
}

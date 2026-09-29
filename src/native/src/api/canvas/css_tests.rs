use crate::api::canvas::css::*;

fn font(text: &str) -> Font {
  parse_font(text).unwrap_or_else(|| panic!("could not parse {text:?}"))
}

#[test]
fn every_css_colour_shape_parses_to_argb() {
  for (text, argb) in [
    ("#f00", 0xffff0000),
    ("#ff0000", 0xffff0000),
    ("#ff000080", 0x80ff0000),
    ("red", 0xffff0000),
    ("rebeccapurple", 0xff663399),
    ("rgb(255, 0, 0)", 0xffff0000),
    ("rgb(255 0 0)", 0xffff0000),
    ("rgba(255, 0, 0, 0.5)", 0x80ff0000),
    ("rgb(255 0 0 / 50%)", 0x80ff0000),
    ("hsl(0, 100%, 50%)", 0xffff0000),
    ("hsl(0deg 100% 50% / 100%)", 0xffff0000),
    ("hwb(0 0% 0%)", 0xffff0000),
    ("transparent", 0),
    ("rgba(0, 0, 0, 0)", 0),
    // a canvas has no cascade, so currentColor has nothing to inherit; the spec says transparent black
    ("currentColor", 0),
    ("CURRENTCOLOR", 0),
    // css colour 4's four-digit hex is rgba
    ("#ff00", 0x00ffff00),
    ("#f00f", 0xffff0000),
    ("  RED  ", 0xffff0000),
    ("#FF0000", 0xffff0000),
    ("rgb(300, -20, 0)", 0xffff0000),
    ("rgba(0, 0, 0, 5)", 0xff000000),
  ] {
    assert_eq!(parse_color(text).map(|value| value as u32), Some(argb), "{text:?}");
  }
}

#[test]
fn nonsense_is_refused_rather_than_rendered_as_black() {
  for text in ["", "   ", "notacolour", "#ff", "#ff000", "rgb(", "rgb(1,2)", "12345"] {
    assert_eq!(parse_color(text), None, "accepted {text:?}");
  }
}

#[test]
fn the_minimal_shorthand_is_a_size_and_a_family() {
  let parsed = font("10px sans-serif");
  assert_eq!(parsed.size, 10.0);
  assert_eq!(parsed.families, vec!["sans-serif".to_string()]);
  assert_eq!(parsed.weight, 400);
  assert!(!parsed.italic);
}

/// the spec's `[style || variant || weight || stretch]?` is an unordered set
#[test]
fn the_keyword_pile_before_the_size_is_unordered_and_case_insensitive() {
  for text in [
    "italic small-caps bold 24px Roboto",
    "bold italic small-caps 24px Roboto",
    "small-caps bold italic 24px Roboto",
    "SMALL-CAPS ITALIC BOLD 24px Roboto",
  ] {
    let parsed = font(text);
    assert!(parsed.italic, "{text}");
    assert!(parsed.small_caps, "{text}");
    assert_eq!(parsed.weight, 700, "{text}");
    assert_eq!(parsed.size, 24.0, "{text}");
    assert_eq!(parsed.families, vec!["Roboto".to_string()], "{text}");
  }
}

/// there is no parent font on a canvas, so `lighter`/`bolder` resolve against the initial value
#[test]
fn a_weight_is_the_css_scale() {
  for (text, weight) in [
    ("100 12px x", 100),
    ("350 12px x", 350),
    ("900 12px x", 900),
    ("normal 12px x", 400),
    ("bold 12px x", 700),
    ("lighter 12px x", 100),
    ("bolder 12px x", 700),
    ("condensed bold 12px x", 700),
  ] {
    assert_eq!(font(text).weight, weight, "{text}");
  }
}

#[test]
fn every_absolute_length_unit_resolves_to_px() {
  for (text, size) in [("12px x", 12.0), ("12pt x", 16.0), ("1in x", 96.0), ("1pc x", 16.0), ("2.54cm x", 96.0)] {
    assert!((font(text).size - size).abs() < 0.01, "{text}");
  }
  assert!((font("25.4mm x").size - 96.0).abs() < 0.01);
}

#[test]
fn a_font_the_canvas_cannot_honour_is_refused() {
  for text in [
    "2em x",
    "150% x",
    "2rem x",
    "5vw x",
    "12px/wobbly x",
    "",
    "   ",
    "bold",
    "12px",
    "bold italic",
    "sans-serif",
    "wobbly 12px x",
    "bold nonsense 12px x",
    "0px x",
    "-12px x",
    "12px \"Roboto",
  ] {
    assert_eq!(parse_font(text), None, "accepted {text:?}");
  }
}

#[test]
fn a_line_height_is_accepted_and_has_no_effect() {
  for text in ["12px/20px x", "12px/normal x", "12px/1.5 x"] {
    let parsed = font(text);
    assert_eq!(parsed.size, 12.0, "{text}");
    assert_eq!(parsed.families, vec!["x".to_string()], "{text}");
  }
}

#[test]
fn a_family_list_keeps_its_order_quotes_and_case() {
  for (text, families) in [
    ("12px Roboto, Arial, sans-serif", &["Roboto", "Arial", "sans-serif"][..]),
    ("12px \"Noto Color Emoji\"", &["Noto Color Emoji"]),
    ("12px 'Times New Roman'", &["Times New Roman"]),
    ("12px \"Noto Color Emoji\", Roboto", &["Noto Color Emoji", "Roboto"]),
    ("bold 24px 10px", &["10px"]),
  ] {
    assert_eq!(font(text).families, families, "{text}");
  }
}

use super::*;

#[test]
fn mutf_encodes_nul_and_supplementary_characters() {
  let mut bytes = vec![];
  write_mutf8(&mut bytes, "\0😀");
  assert_eq!(bytes, [3, 0xc0, 0x80, 0xed, 0xa0, 0xbd, 0xed, 0xb8, 0x80, 0]);
}

#[test]
fn checksums_map_alignment_and_section_order_match_the_file() {
  let bytes = super::super::build("inu.test.Empty", "Ljava/lang/Object;", &[], &[], &[]).unwrap();
  let read = |at| u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap()) as usize;
  assert_eq!(read(32), bytes.len());
  assert_eq!(read(36), 112);
  assert_eq!(read(40), 0x12345678);
  assert_eq!(read(8) as u32, adler::adler32_slice(&bytes[12..]));
  assert_eq!(&bytes[12..32], Sha1::digest(&bytes[32..]).as_slice());
  let map = read(52);
  assert_eq!(map % 4, 0);
  let mut last = None;
  for i in 0..read(map) {
    let at = map + 4 + i * 12;
    let offset = read(at + 8);
    let kind = u16::from_le_bytes(bytes[at..at + 2].try_into().unwrap());
    assert!(offset < bytes.len());
    assert!(last.is_none_or(|last| offset > last));
    assert!(read(at + 4) > 0);
    if !matches!(kind, 0x2000 | 0x2002) {
      assert_eq!(offset % 4, 0);
    }
    last = Some(offset);
  }
}

#[test]
fn invalid_registers_and_argument_words_are_refused() {
  let mut class = Class {
    name: "Linu/test/Bad;".into(),
    parent: "Ljava/lang/Object;".into(),
    interfaces: vec![],
    fields: vec![],
    methods: vec![Body {
      method: create_method("Linu/test/Bad;", "run", "V", &[]),
      flags: 9,
      registers: 1,
      ops: vec![Op::Const(1, 0), Op::ReturnVoid],
    }],
  };
  assert!(emit_dex(&class).unwrap_err().contains("out of bounds"));
  class.methods[0].ops = vec![
    Op::Invoke(0x71, vec![0], create_method("Ljava/lang/Long;", "valueOf", "Ljava/lang/Long;", &["J"])),
    Op::ReturnVoid,
  ];
  assert!(emit_dex(&class).unwrap_err().contains("word count mismatch"));
}

#[test]
fn strings_are_sorted_by_utf16_not_utf8() {
  let mut class = Class {
    name: "Linu/test/Unicode;".into(),
    parent: "Ljava/lang/Object;".into(),
    interfaces: vec![],
    fields: vec![],
    methods: vec![],
  };
  for name in ["\u{e000}", "\u{10000}"] {
    class.fields.push((
      Field {
        owner: class.name.clone(),
        name: name.into(),
        ty: "I".into(),
      },
      1,
    ));
  }
  let bytes = emit_dex(&class).unwrap();
  let count = u32::from_le_bytes(bytes[56..60].try_into().unwrap()) as usize;
  let mut supplementary = None;
  let mut bmp = None;
  for i in 0..count {
    let at = 112 + i * 4;
    let offset = u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap()) as usize;
    if bytes[offset..].starts_with(&[2, 0xed, 0xa0, 0x80, 0xed, 0xb0, 0x80, 0]) {
      supplementary = Some(i);
    }
    if bytes[offset..].starts_with(&[1, 0xee, 0x80, 0x80, 0]) {
      bmp = Some(i);
    }
  }
  assert!(supplementary.unwrap() < bmp.unwrap());
}

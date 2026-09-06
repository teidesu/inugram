use super::*;

fn strings(values: &[&str]) -> Vec<String> {
  values.iter().map(|value| (*value).to_string()).collect()
}

#[test]
fn descriptors_reject_invalid_types_before_emission() {
  for descriptor in ["", "Q", "L;", "[V", "Ljava.lang.String;", "Ljava//lang/String;", "Lbad\0name;"] {
    assert!(validate_type(descriptor, false).is_err(), "{descriptor}");
  }
  for descriptor in ["I", "J", "Z", "Ljava/lang/String;", "[I", "[[Ljava/lang/String;"] {
    assert!(validate_type(descriptor, false).is_ok(), "{descriptor}");
  }
  assert!(validate_type("V", true).is_ok());
  assert!(validate_type("V", false).is_err());
}

#[test]
fn malformed_counts_and_constructor_signatures_are_refused() {
  for method in [
    strings(&["<init>", "I", "0", "0", "0"]),
    strings(&["<init>", "V", "1", "0", "0"]),
    strings(&["test", "V", "0", "65", "0"]),
    strings(&["test", "V", "0", "1", "0"]),
    strings(&["test", "V", "0", "0", "1", "I"]),
    strings(&["test", "V", "0", "1", "V", "0"]),
  ] {
    assert!(validate_metadata(OBJECT, &[], &[], &[method.clone()]).is_err(), "{method:?}");
  }
}

#[test]
fn emitter_encodes_arrays_wide_values_interfaces_and_super_calls() {
  let bytes = build(
    "inu.test.Generated",
    OBJECT,
    &strings(&["Ljava/lang/Runnable;"]),
    &[strings(&["count", "I", "0"])],
    &[
      strings(&["<init>", "V", "0", "2", "J", "Ljava/lang/String;", "0"]),
      strings(&["run", "V", "0", "0", "0"]),
      strings(&["test", "J", "1", "5", "I", "D", "J", "Ljava/lang/String;", "[I", "0"]),
    ],
  )
  .unwrap();
  assert_eq!(bytes.as_slice(), include_bytes!("../../../../../test/assets/defined_class_generated.dex"));
  assert_eq!(u32::from_le_bytes(bytes[32..36].try_into().unwrap()) as usize, bytes.len());
  assert_eq!(u32::from_le_bytes(bytes[96..100].try_into().unwrap()), 1);
  let forwarding = build(
    "inu.test.Forwarding",
    "Linu/test/Parent;",
    &[],
    &[],
    &[strings(&["<init>", "V", "0", "2", "I", "Ljava/lang/String;", "4", "J", "Ljava/lang/String;", "D", "Z"])],
  )
  .unwrap();
  assert_eq!(forwarding.as_slice(), include_bytes!("../../../../../test/assets/defined_class_forwarding.dex"));
  if let Ok(directory) = std::env::var("INU_DEX_TEST_OUTPUT") {
    std::fs::write(std::path::Path::new(&directory).join("generated.dex"), bytes).unwrap();
    std::fs::write(std::path::Path::new(&directory).join("forwarding.dex"), forwarding).unwrap();
  }
}

#[test]
fn duplicates_reserved_names_and_oversized_metadata_are_refused() {
  for fields in [
    vec![strings(&["value", "I", "0"]), strings(&["value", "J", "1"])],
    vec![strings(&["inu$dispatch0", "I", "1"])],
    vec![strings(&["", "I", "1"])],
  ] {
    assert!(build("inu.test.Invalid", OBJECT, &[], &fields, &[]).is_err());
  }
  let method = strings(&["run", "V", "0", "0", "0"]);
  assert!(build("inu.test.Invalid", OBJECT, &[], &[], &[method.clone(), method]).is_err());
  assert!(build(&"a".repeat(super::super::VALUE_LIMIT_BYTES + 1), OBJECT, &[], &[], &[]).is_err());
}

#[test]
fn maximum_wide_parameter_and_super_ranges_fit_their_registers() {
  let mut metadata = strings(&["<init>", "V", "0", "64"]);
  metadata.extend(vec!["J".into(); 64]);
  metadata.push("64".into());
  metadata.extend(vec!["D".into(); 64]);
  let bytes = build("inu.test.Wide", OBJECT, &[], &[], &[metadata]).unwrap();
  let map = u32::from_le_bytes(bytes[52..56].try_into().unwrap()) as usize;
  let count = u32::from_le_bytes(bytes[map..map + 4].try_into().unwrap()) as usize;
  let entry = (0..count)
    .map(|i| map + 4 + i * 12)
    .find(|at| u16::from_le_bytes(bytes[*at..*at + 2].try_into().unwrap()) == 0x2001)
    .unwrap();
  let code = u32::from_le_bytes(bytes[entry + 8..entry + 12].try_into().unwrap()) as usize;
  assert_eq!(u16::from_le_bytes(bytes[code..code + 2].try_into().unwrap()), 264);
  assert_eq!(u16::from_le_bytes(bytes[code + 2..code + 4].try_into().unwrap()), 129);
  assert_eq!(u16::from_le_bytes(bytes[code + 4..code + 6].try_into().unwrap()), 129);
}

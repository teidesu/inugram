mod writer;
use writer::{create_method, get_word_count, Body, Class, Field, Op};
const TARGET: &str = "Ldesu/inugram/helpers/plugins/platform/PluginJvmClass$MethodTarget;";
const OBJECT: &str = "Ljava/lang/Object;";
const ARRAY: &str = "[Ljava/lang/Object;";
/// a primitive descriptor's box class and the method that unwraps one
fn get_box_pair(t: &str) -> Option<(&'static str, &'static str)> {
  Some(match t {
    "I" => ("Ljava/lang/Integer;", "intValue"),
    "J" => ("Ljava/lang/Long;", "longValue"),
    "D" => ("Ljava/lang/Double;", "doubleValue"),
    "F" => ("Ljava/lang/Float;", "floatValue"),
    "Z" => ("Ljava/lang/Boolean;", "booleanValue"),
    "B" => ("Ljava/lang/Byte;", "byteValue"),
    "S" => ("Ljava/lang/Short;", "shortValue"),
    "C" => ("Ljava/lang/Character;", "charValue"),
    _ => return None,
  })
}
fn get_box_type(t: &str) -> Option<&'static str> {
  get_box_pair(t).map(|(box_type, _)| box_type)
}
fn get_move_kind(t: &str) -> u8 {
  if get_word_count(t) == 2 {
    0x06
  } else if get_box_type(t).is_some() {
    0x03
  } else {
    0x09
  }
}
fn append_cast(ops: &mut Vec<Op>, t: &str) {
  ops.push(Op::Cast(0, get_box_type(t).unwrap_or(t).into()));
  if let Some((box_type, unbox)) = get_box_pair(t) {
    ops.push(Op::Invoke(0x6e, vec![0], create_method(box_type, unbox, t, &[])));
    ops.push(Op::Result(0, if get_word_count(t) == 2 { 0x0b } else { 0x0a }));
  }
}
fn add_body(class: &mut Class, name: &str, result: &str, params: &[&str], is_static: bool, super_params: &[&str]) {
  let constructor = name == "<init>";
  let locals = 6 + if constructor { 1 + super_params.iter().map(|t| get_word_count(t)).sum::<u16>() } else { 0 };
  let ins = u16::from(!is_static) + params.iter().map(|t| get_word_count(t)).sum::<u16>();
  let field = Field {
    owner: class.name.clone(),
    name: format!("inu$dispatch{}", class.methods.len()),
    ty: TARGET.into(),
  };
  class.fields.push((field.clone(), 9));
  let mut ops = vec![Op::Const(0, params.len() as i16), Op::NewArray(2, 0, ARRAY.into())];
  let mut reg = locals + u16::from(!is_static);
  for (i, t) in params.iter().enumerate() {
    ops.push(Op::Move(0, reg, get_move_kind(t)));
    reg += get_word_count(t);
    if let Some(b) = get_box_type(t) {
      ops.push(Op::Invoke(
        0x71,
        if get_word_count(t) == 2 { vec![0, 1] } else { vec![0] },
        create_method(b, "valueOf", b, &[t]),
      ));
      ops.push(Op::Result(0, 0x0c));
    }
    ops.extend([Op::Const(4, i as i16), Op::Aput(0, 2, 4)]);
  }
  ops.push(Op::GetStatic(3, field));
  if constructor {
    ops.push(Op::Move(6, locals, 0x09));
    let mut next = 7;
    for (i, t) in super_params.iter().enumerate() {
      ops.extend([
        Op::Const(4, i as i16),
        Op::Invoke(0x6e, vec![3, 4, 2], create_method(TARGET, "getSuperArgument", OBJECT, &["I", ARRAY])),
        Op::Result(0, 0x0c),
      ]);
      append_cast(&mut ops, t);
      ops.push(Op::Move(next, 0, get_move_kind(t)));
      next += get_word_count(t);
    }
    ops.push(Op::InvokeRange(0x76, 6, next - 6, create_method(&class.parent, "<init>", "V", super_params)));
  }
  ops.push(if is_static { Op::Const(5, 0) } else { Op::Move(5, locals, 0x09) });
  ops.extend([
    Op::Invoke(0x6e, vec![3, 5, 2], create_method(TARGET, "invoke", OBJECT, &[OBJECT, ARRAY])),
    Op::Result(0, 0x0c),
  ]);
  if result == "V" {
    ops.push(Op::ReturnVoid);
  } else {
    append_cast(&mut ops, result);
    ops.push(Op::Return(
      0,
      if get_word_count(result) == 2 {
        0x10
      } else if get_box_type(result).is_some() {
        0x0f
      } else {
        0x11
      },
    ));
  }
  class.methods.push(Body {
    method: create_method(&class.name, name, result, params),
    flags: 1
      | if is_static {
        8
      } else if constructor {
        0x10000
      } else {
        0
      },
    registers: locals + ins,
    ops,
  });
}

fn validate_type(descriptor: &str, allow_void: bool) -> Result<(), String> {
  let base = descriptor.trim_start_matches('[');
  let dimensions = descriptor.len() - base.len();
  let object = base.strip_prefix('L').and_then(|name| name.strip_suffix(';'));
  if dimensions > 255
    || !(get_box_type(base).is_some()
      || allow_void && dimensions == 0 && base == "V"
      || object.is_some_and(|name| {
        !name.is_empty()
          && name.split('/').all(|part| {
            !part.is_empty() && part.bytes().all(|ch| ch.is_ascii_alphanumeric() || matches!(ch, b'_' | b'$'))
          })
      }))
  {
    return Err(format!("invalid JVM type descriptor: {descriptor}"));
  }
  Ok(())
}

fn read_count(text: &str) -> Result<usize, String> {
  text
    .parse::<usize>()
    .ok()
    .filter(|count| *count <= 64)
    .ok_or_else(|| "at most 64 parameters".into())
}

fn validate_metadata(
  superclass: &str,
  interfaces: &[String],
  fields: &[Vec<String>],
  methods: &[Vec<String>],
) -> Result<(), String> {
  let mut field_names = std::collections::HashSet::new();
  let mut signatures = std::collections::HashSet::new();
  validate_type(superclass, false)?;
  if !superclass.starts_with('L') || interfaces.len() > 64 || fields.len() > 256 || methods.len() > 256 {
    return Err("invalid superclass or too many members".into());
  }
  for interface in interfaces {
    validate_type(interface, false)?;
    if !interface.starts_with('L') {
      return Err("interface must be a class descriptor".into());
    }
  }
  for field in fields {
    if field.len() != 3 || !matches!(field[2].as_str(), "0" | "1") {
      return Err("invalid field metadata".into());
    }
    validate_name(&field[0])?;
    if field[0].starts_with("inu$") {
      return Err("reserved field name".into());
    }
    if !field_names.insert(&field[0]) {
      return Err("duplicate field".into());
    }
    validate_type(&field[1], false)?;
  }
  for method in methods {
    if method.len() < 5 || !matches!(method[2].as_str(), "0" | "1") {
      return Err("invalid method metadata".into());
    }
    if method[0] != "<init>" {
      validate_name(&method[0])?;
      if method[0].starts_with("inu$") {
        return Err("reserved method name".into());
      }
    }
    validate_type(&method[1], true)?;
    let count = read_count(&method[3])?;
    let super_count = method.get(4 + count).ok_or("missing super parameters")?;
    let super_count = read_count(super_count)?;
    if method.len() != 5 + count + super_count {
      return Err("invalid parameter metadata".into());
    }
    if !signatures.insert((&method[0], &method[1], &method[4..4 + count])) {
      return Err("duplicate method signature".into());
    }
    if method[0] == "<init>" && (method[1] != "V" || method[2] != "0") {
      return Err("invalid constructor metadata".into());
    }
    if method[0] != "<init>" && super_count != 0 {
      return Err("only constructors have super parameters".into());
    }
    for param in method[4..4 + count].iter().chain(&method[5 + count..]) {
      validate_type(param, false)?;
    }
  }
  Ok(())
}

fn validate_name(name: &str) -> Result<(), String> {
  if name.is_empty()
    || !name
      .bytes()
      .enumerate()
      .all(|(i, ch)| ch.is_ascii_alphabetic() || matches!(ch, b'_' | b'$') || i > 0 && ch.is_ascii_digit())
  {
    return Err("invalid or reserved member name".into());
  }
  Ok(())
}

pub(crate) fn build(
  name: &str,
  superclass: &str,
  interfaces: &[String],
  fields: &[Vec<String>],
  methods: &[Vec<String>],
) -> Result<Vec<u8>, String> {
  let mut size = name.len().saturating_add(superclass.len());
  for text in interfaces.iter().chain(fields.iter().flatten()).chain(methods.iter().flatten()) {
    size = size.saturating_add(text.len());
  }
  if size > super::VALUE_LIMIT_BYTES {
    return Err("class metadata exceeds 1 MB".into());
  }
  for part in name.split('.') {
    validate_name(part)?;
  }
  validate_metadata(superclass, interfaces, fields, methods)?;
  let mut class = Class {
    name: format!("L{};", name.replace('.', "/")),
    parent: superclass.into(),
    interfaces: interfaces.to_vec(),
    fields: Vec::new(),
    methods: Vec::new(),
  };
  validate_type(&class.name, false)?;
  for field in fields {
    class.fields.push((
      Field {
        owner: class.name.clone(),
        name: field[0].clone(),
        ty: field[1].clone(),
      },
      if field[2] == "1" { 9 } else { 1 },
    ));
  }
  for data in methods {
    let count = read_count(&data[3])?;
    let params: Vec<_> = data[4..4 + count].iter().map(String::as_str).collect();
    let super_params: Vec<_> = data[5 + count..].iter().map(String::as_str).collect();
    add_body(&mut class, &data[0], &data[1], &params, data[2] == "1", &super_params);
  }
  writer::emit_dex(&class)
}
#[cfg(test)]
#[path = "dex_tests.rs"]
mod tests;

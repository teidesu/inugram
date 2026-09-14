use sha1::{Digest, Sha1};
use std::collections::{BTreeSet, HashMap};

#[derive(Clone, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub(super) struct Method {
  pub(super) owner: String,
  pub(super) name: String,
  pub(super) result: String,
  pub(super) params: Vec<String>,
}
#[derive(Clone, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub(super) struct Field {
  pub(super) owner: String,
  pub(super) name: String,
  pub(super) ty: String,
}
#[derive(Clone, Debug)]
pub(super) enum Op {
  Const(u16, i16),
  Move(u16, u16, u8),
  NewArray(u16, u16, String),
  Aput(u16, u16, u16),
  GetStatic(u16, Field),
  Invoke(u8, Vec<u16>, Method),
  InvokeRange(u8, u16, u16, Method),
  Result(u16, u8),
  Cast(u16, String),
  Return(u16, u8),
  ReturnVoid,
}
pub(super) struct Body {
  pub(super) method: Method,
  pub(super) flags: u32,
  pub(super) registers: u16,
  pub(super) ops: Vec<Op>,
}
pub(super) struct Class {
  pub(super) name: String,
  pub(super) parent: String,
  pub(super) interfaces: Vec<String>,
  pub(super) fields: Vec<(Field, u32)>,
  pub(super) methods: Vec<Body>,
}
pub(super) fn create_method(owner: &str, name: &str, result: &str, params: &[&str]) -> Method {
  Method {
    owner: owner.into(),
    name: name.into(),
    result: result.into(),
    params: params.iter().map(|s| (*s).into()).collect(),
  }
}
pub(super) fn get_word_count(ty: &str) -> u16 {
  if ty == "J" || ty == "D" {
    2
  } else {
    1
  }
}
fn get_shorty(result: &str, params: &[String]) -> String {
  std::iter::once(result)
    .chain(params.iter().map(String::as_str))
    .map(|s| if s.starts_with(['L', '[']) { 'L' } else { s.chars().next().unwrap() })
    .collect()
}
fn write_u16(out: &mut Vec<u8>, v: u16) {
  out.extend(v.to_le_bytes());
}
fn write_u32(out: &mut Vec<u8>, v: u32) {
  out.extend(v.to_le_bytes());
}
fn write_u32_at(out: &mut [u8], at: usize, value: u32) {
  out[at..at + 4].copy_from_slice(&value.to_le_bytes());
}
fn align_buffer(out: &mut Vec<u8>) {
  while !out.len().is_multiple_of(4) {
    out.push(0);
  }
}
fn write_uleb(out: &mut Vec<u8>, value: u32) {
  leb128::write::unsigned(out, value.into()).expect("writing to a Vec cannot fail");
}
fn write_mutf8(out: &mut Vec<u8>, text: &str) {
  write_uleb(out, text.encode_utf16().count() as u32);
  for u in text.encode_utf16() {
    if u != 0 && u < 0x80 {
      out.push(u as u8);
    } else if u < 0x800 {
      out.extend([0xc0 | (u >> 6) as u8, 0x80 | (u & 63) as u8]);
    } else {
      out.extend([0xe0 | (u >> 12) as u8, 0x80 | ((u >> 6) & 63) as u8, 0x80 | (u & 63) as u8]);
    }
  }
  out.push(0);
}
fn create_index<T: Eq + std::hash::Hash + Clone>(values: &[T]) -> HashMap<T, u32> {
  values.iter().cloned().enumerate().map(|(i, v)| (v, i as u32)).collect()
}
fn get_u16_index(value: u32) -> Result<u16, String> {
  u16::try_from(value).map_err(|_| "reference pool exceeds 16 bits".into())
}
pub(super) fn emit_dex(class: &Class) -> Result<Vec<u8>, String> {
  let mut types = BTreeSet::from([class.name.clone(), class.parent.clone()]);
  types.extend(class.interfaces.iter().cloned());
  let mut fields: BTreeSet<_> = class.fields.iter().map(|(f, _)| f.clone()).collect();
  let mut methods: BTreeSet<_> = class.methods.iter().map(|b| b.method.clone()).collect();
  for body in &class.methods {
    for op in &body.ops {
      match op {
        Op::NewArray(_, _, t) | Op::Cast(_, t) => {
          types.insert(t.clone());
        }
        Op::GetStatic(_, f) => {
          fields.insert(f.clone());
        }
        Op::Invoke(_, _, m) | Op::InvokeRange(_, _, _, m) => {
          methods.insert(m.clone());
        }
        _ => {}
      }
    }
  }
  for f in &fields {
    types.extend([f.owner.clone(), f.ty.clone()]);
  }
  for m in &methods {
    types.extend([m.owner.clone(), m.result.clone()]);
    types.extend(m.params.iter().cloned());
  }
  let mut strings = types.clone();
  for f in &fields {
    strings.insert(f.name.clone());
  }
  for m in &methods {
    strings.insert(m.name.clone());
    strings.insert(get_shorty(&m.result, &m.params));
  }
  let mut strings: Vec<_> = strings.into_iter().collect();
  strings.sort_by(|a, b| a.encode_utf16().cmp(b.encode_utf16()));
  let si = create_index(&strings);
  let mut types: Vec<_> = types.into_iter().collect();
  types.sort_by_key(|t| si[t]);
  let ti = create_index(&types);
  let mut protos: Vec<_> = methods
    .iter()
    .map(|m| (m.result.clone(), m.params.clone()))
    .collect::<BTreeSet<_>>()
    .into_iter()
    .collect();
  protos.sort_by_key(|(r, p)| (ti[r], p.iter().map(|t| ti[t]).collect::<Vec<_>>()));
  let pi = create_index(&protos);
  let mut fields: Vec<_> = fields.into_iter().collect();
  fields.sort_by_key(|f| (ti[&f.owner], si[&f.name], ti[&f.ty]));
  let fi = create_index(&fields);
  let mut methods: Vec<_> = methods.into_iter().collect();
  methods.sort_by_key(|m| (ti[&m.owner], si[&m.name], pi[&(m.result.clone(), m.params.clone())]));
  let mi = create_index(&methods);
  for size in [types.len(), protos.len(), fields.len(), methods.len()] {
    if size > 65536 {
      return Err("reference pool exceeds 16 bits".into());
    }
  }
  let string_off = 112;
  let type_off = string_off + strings.len() * 4;
  let proto_off = type_off + types.len() * 4;
  let field_off = proto_off + protos.len() * 12;
  let method_off = field_off + fields.len() * 8;
  let class_off = method_off + methods.len() * 8;
  let data_off = class_off + 32;
  let mut out = vec![0u8; data_off];
  let mut map = vec![
    (0u16, 1, 0usize),
    (1, strings.len(), string_off),
    (2, types.len(), type_off),
    (3, protos.len(), proto_off),
    (4, fields.len(), field_off),
    (5, methods.len(), method_off),
    (6, 1, class_off),
  ];
  map.push((0x2002, strings.len(), out.len()));
  for (i, s) in strings.iter().enumerate() {
    let off = out.len() as u32;
    write_u32_at(&mut out, string_off + i * 4, off);
    write_mutf8(&mut out, s);
  }
  for (i, t) in types.iter().enumerate() {
    write_u32_at(&mut out, type_off + i * 4, si[t]);
  }
  let mut lists = HashMap::<Vec<String>, u32>::new();
  let mut list_start = None;
  let mut write_list = |list: &[String], out: &mut Vec<u8>| -> Result<u32, String> {
    if list.is_empty() {
      return Ok(0);
    }
    if let Some(off) = lists.get(list) {
      return Ok(*off);
    }
    align_buffer(out);
    let off = out.len() as u32;
    list_start.get_or_insert(off as usize);
    write_u32(out, list.len() as u32);
    for t in list {
      write_u16(out, get_u16_index(ti[t])?);
    }
    lists.insert(list.to_vec(), off);
    Ok(off)
  };
  for (i, (r, p)) in protos.iter().enumerate() {
    let off = write_list(p, &mut out)?;
    write_u32_at(&mut out, proto_off + i * 12, si[&get_shorty(r, p)]);
    write_u32_at(&mut out, proto_off + i * 12 + 4, ti[r]);
    write_u32_at(&mut out, proto_off + i * 12 + 8, off);
  }
  let mut interfaces = class.interfaces.clone();
  interfaces.sort_by_key(|t| ti[t]);
  let interfaces_off = write_list(&interfaces, &mut out)?;
  if let Some(start) = list_start {
    map.push((0x1001, lists.len(), start));
  }
  for (i, f) in fields.iter().enumerate() {
    let at = field_off + i * 8;
    out[at..at + 2].copy_from_slice(&get_u16_index(ti[&f.owner])?.to_le_bytes());
    out[at + 2..at + 4].copy_from_slice(&get_u16_index(ti[&f.ty])?.to_le_bytes());
    write_u32_at(&mut out, at + 4, si[&f.name]);
  }
  for (i, m) in methods.iter().enumerate() {
    let at = method_off + i * 8;
    out[at..at + 2].copy_from_slice(&get_u16_index(ti[&m.owner])?.to_le_bytes());
    out[at + 2..at + 4].copy_from_slice(&get_u16_index(pi[&(m.result.clone(), m.params.clone())])?.to_le_bytes());
    write_u32_at(&mut out, at + 4, si[&m.name]);
  }
  let mut code_offsets = HashMap::new();
  align_buffer(&mut out);
  map.push((0x2001, class.methods.len(), out.len()));
  for body in &class.methods {
    let mut code = Vec::<u16>::new();
    let mut outs = 0u16;
    let check_reg = |r: u16| {
      if r < body.registers {
        Ok(())
      } else {
        Err(format!("register v{r} out of bounds"))
      }
    };
    for op in &body.ops {
      match op {
        Op::Const(r, v) => {
          check_reg(*r)?;
          if *r > 255 {
            return Err("const register exceeds 8 bits".into());
          }
          code.extend([0x13 | r << 8, *v as u16]);
        }
        Op::Move(d, s, k) => {
          check_reg(*d)?;
          check_reg(*s)?;
          if *k == 0x06 {
            check_reg(d + 1)?;
            check_reg(s + 1)?;
          }
          code.extend([u16::from(*k), *d, *s]);
        }
        Op::NewArray(d, s, t) => {
          check_reg(*d)?;
          check_reg(*s)?;
          if *d > 15 || *s > 15 {
            return Err("new-array register exceeds 4 bits".into());
          }
          code.extend([0x23 | d << 8 | s << 12, get_u16_index(ti[t])?]);
        }
        Op::Aput(v, a, i) => {
          for r in [v, a, i] {
            check_reg(*r)?;
            if *r > 255 {
              return Err("aput register exceeds 8 bits".into());
            }
          }
          code.extend([0x4d | v << 8, *a | i << 8]);
        }
        Op::GetStatic(r, f) => {
          check_reg(*r)?;
          if *r > 255 {
            return Err("sget register exceeds 8 bits".into());
          }
          code.extend([0x62 | r << 8, get_u16_index(fi[f])?]);
        }
        Op::Invoke(k, regs, m) => {
          if regs.len() > 5 || regs.iter().any(|r| *r > 15) {
            return Err("invoke requires range form".into());
          }
          let expected = m.params.iter().map(|t| get_word_count(t)).sum::<u16>() + u16::from(*k != 0x71);
          if regs.len() != usize::from(expected) {
            return Err("invoke argument word count mismatch".into());
          }
          for r in regs {
            check_reg(*r)?;
          }
          outs = outs.max(expected);
          let mut r = [0u16; 5];
          r[..regs.len()].copy_from_slice(regs);
          code.extend([
            u16::from(*k) | (regs.len() as u16) << 12 | r[4] << 8,
            get_u16_index(mi[m])?,
            r[0] | r[1] << 4 | r[2] << 8 | r[3] << 12,
          ]);
        }
        Op::InvokeRange(k, start, count, m) => {
          let expected = m.params.iter().map(|t| get_word_count(t)).sum::<u16>() + u16::from(*k != 0x77);
          if *count != expected || *count > 255 {
            return Err("range argument word count mismatch".into());
          }
          if u32::from(*start) + u32::from(*count) > u32::from(body.registers) {
            return Err("range out of bounds".into());
          }
          outs = outs.max(*count);
          code.extend([u16::from(*k) | count << 8, get_u16_index(mi[m])?, *start]);
        }
        Op::Result(r, k) | Op::Return(r, k) => {
          check_reg(*r)?;
          if *r > 255 {
            return Err("result register exceeds 8 bits".into());
          }
          if *k == 0x0b || *k == 0x10 {
            check_reg(r + 1)?;
          }
          code.push(u16::from(*k) | r << 8);
        }
        Op::Cast(r, t) => {
          check_reg(*r)?;
          if *r > 255 {
            return Err("cast register exceeds 8 bits".into());
          }
          code.extend([0x1f | r << 8, get_u16_index(ti[t])?]);
        }
        Op::ReturnVoid => code.push(0x0e),
      }
    }
    let ins = body.method.params.iter().map(|t| get_word_count(t)).sum::<u16>() + u16::from(body.flags & 8 == 0);
    if ins > body.registers {
      return Err("parameter registers exceed register count".into());
    }
    align_buffer(&mut out);
    code_offsets.insert(body.method.clone(), out.len() as u32);
    for n in [body.registers, ins, outs, 0] {
      write_u16(&mut out, n);
    }
    write_u32(&mut out, 0);
    write_u32(&mut out, code.len() as u32);
    for n in code {
      write_u16(&mut out, n);
    }
  }
  let class_data = out.len() as u32;
  map.push((0x2000, 1, out.len()));
  let mut statics: Vec<_> = class.fields.iter().filter(|(_, f)| f & 8 != 0).collect();
  statics.sort_by_key(|(f, _)| fi[f]);
  let mut instances: Vec<_> = class.fields.iter().filter(|(_, f)| f & 8 == 0).collect();
  instances.sort_by_key(|(f, _)| fi[f]);
  let mut direct: Vec<_> = class.methods.iter().filter(|m| m.flags & (8 | 2 | 0x10000) != 0).collect();
  direct.sort_by_key(|m| mi[&m.method]);
  let mut virtuals: Vec<_> = class.methods.iter().filter(|m| m.flags & (8 | 2 | 0x10000) == 0).collect();
  virtuals.sort_by_key(|m| mi[&m.method]);
  for n in [statics.len(), instances.len(), direct.len(), virtuals.len()] {
    write_uleb(&mut out, n as u32);
  }
  for group in [statics, instances] {
    let mut prev = 0;
    for (f, flags) in group {
      let n = fi[f];
      write_uleb(&mut out, n - prev);
      write_uleb(&mut out, *flags);
      prev = n;
    }
  }
  for group in [direct, virtuals] {
    let mut prev = 0;
    for b in group {
      let n = mi[&b.method];
      write_uleb(&mut out, n - prev);
      write_uleb(&mut out, b.flags);
      write_uleb(&mut out, code_offsets[&b.method]);
      prev = n;
    }
  }
  let vals = [ti[&class.name], 1, ti[&class.parent], interfaces_off, u32::MAX, 0, class_data, 0];
  for (i, v) in vals.iter().enumerate() {
    write_u32_at(&mut out, class_off + i * 4, *v);
  }
  align_buffer(&mut out);
  let map_off = out.len();
  map.push((0x1000, 1, map_off));
  map.retain(|(_, n, _)| *n != 0);
  map.sort_by_key(|(_, _, off)| *off);
  write_u32(&mut out, map.len() as u32);
  for (kind, count, offset) in map {
    write_u16(&mut out, kind);
    write_u16(&mut out, 0);
    write_u32(&mut out, count as u32);
    write_u32(&mut out, offset as u32);
  }
  out[..8].copy_from_slice(b"dex\n035\0");
  if out.len() > super::super::DEX_LIMIT_BYTES {
    return Err("DEX image exceeds 8 MB".into());
  }
  let size = out.len() as u32;
  for (at, v) in [
    (32, size),
    (36, 112),
    (40, 0x12345678),
    (52, map_off as u32),
    (104, size - data_off as u32),
    (108, data_off as u32),
  ] {
    write_u32_at(&mut out, at, v);
  }
  for (at, count, offset) in [
    (56, strings.len(), string_off),
    (64, types.len(), type_off),
    (72, protos.len(), proto_off),
    (80, fields.len(), field_off),
    (88, methods.len(), method_off),
    (96, 1, class_off),
  ] {
    write_u32_at(&mut out, at, count as u32);
    write_u32_at(&mut out, at + 4, if count == 0 { 0 } else { offset as u32 });
  }
  let signature = Sha1::digest(&out[32..]);
  out[12..32].copy_from_slice(&signature);
  let checksum = adler::adler32_slice(&out[12..]);
  write_u32_at(&mut out, 8, checksum);
  Ok(out)
}

#[cfg(test)]
#[path = "writer_tests.rs"]
mod tests;

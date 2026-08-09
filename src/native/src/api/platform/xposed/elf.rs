use std::collections::HashMap;
use std::ffi::{c_char, c_void};
#[cfg(target_os = "android")]
use std::ffi::{c_int, CStr};
use std::fs;
use std::ops::Range;

const ELF_MAGIC: [u8; 4] = [0x7f, b'E', b'L', b'F'];
const ELFCLASS64: u8 = 2;
const ELFDATA2LSB: u8 = 1;

const EHDR_SIZE: usize = 64;
const SHDR_SIZE: usize = 64;
const SYM_SIZE: usize = 24;

const SHT_SYMTAB: u32 = 2;
const SHT_DYNSYM: u32 = 11;

const SHN_UNDEF: u16 = 0;

fn read_u16(bytes: &[u8], at: usize) -> Option<u16> {
  Some(u16::from_le_bytes(bytes.get(at..at + 2)?.try_into().ok()?))
}

fn read_u32(bytes: &[u8], at: usize) -> Option<u32> {
  Some(u32::from_le_bytes(bytes.get(at..at + 4)?.try_into().ok()?))
}

fn read_u64(bytes: &[u8], at: usize) -> Option<u64> {
  Some(u64::from_le_bytes(bytes.get(at..at + 8)?.try_into().ok()?))
}

fn range_of(offset: u64, size: u64, len: usize) -> Option<Range<usize>> {
  let start = usize::try_from(offset).ok()?;
  let end = start.checked_add(usize::try_from(size).ok()?)?;
  (end <= len).then_some(start..end)
}

struct Section {
  name: u32,
  kind: u32,
  offset: u64,
  size: u64,
  link: u32,
  entsize: u64,
}

fn read_sections(image: &[u8]) -> Option<(Vec<Section>, usize)> {
  if image.get(..4)? != ELF_MAGIC || image.get(4)? != &ELFCLASS64 || image.get(5)? != &ELFDATA2LSB {
    return None;
  }

  let shoff = read_u64(image, 0x28)?;
  let shentsize = read_u16(image, 0x3a)? as usize;
  let shnum = read_u16(image, 0x3c)? as usize;
  let shstrndx = read_u16(image, 0x3e)? as usize;
  if shoff < EHDR_SIZE as u64 || shentsize < SHDR_SIZE || shnum == 0 {
    return None;
  }

  let table = range_of(shoff, (shentsize * shnum) as u64, image.len())?;
  let mut sections = Vec::with_capacity(shnum);
  for index in 0..shnum {
    let at = table.start + index * shentsize;
    sections.push(Section {
      name: read_u32(image, at)?,
      kind: read_u32(image, at + 4)?,
      offset: read_u64(image, at + 0x18)?,
      size: read_u64(image, at + 0x20)?,
      link: read_u32(image, at + 0x28)?,
      entsize: read_u64(image, at + 0x38)?,
    });
  }

  Some((sections, shstrndx))
}

fn section_name(strings: &[u8], at: u32) -> Option<&str> {
  let tail = strings.get(at as usize..)?;
  let end = tail.iter().position(|byte| *byte == 0)?;
  std::str::from_utf8(&tail[..end]).ok()
}

struct Table {
  debug: bool,
  symbols: Range<usize>,
  strings: Range<usize>,
}

pub struct Symbols {
  image: Vec<u8>,
  debug: Vec<u8>,
  tables: Vec<Table>,
}

impl Symbols {
  pub fn parse(image: Vec<u8>) -> Option<Symbols> {
    let mut symbols = Symbols {
      image,
      debug: Vec::new(),
      tables: Vec::new(),
    };

    let (sections, shstrndx) = read_sections(&symbols.image)?;
    let names = sections
      .get(shstrndx)
      .and_then(|section| range_of(section.offset, section.size, symbols.image.len()))
      .map(|range| symbols.image[range].to_vec())
      .unwrap_or_default();

    let debugdata = sections
      .iter()
      .find(|section| section_name(&names, section.name) == Some(".gnu_debugdata"))
      .and_then(|section| range_of(section.offset, section.size, symbols.image.len()));
    if let Some(range) = debugdata {
      symbols.debug = decompress_xz(&symbols.image[range]).unwrap_or_default();
    }

    symbols.tables = collect_tables(&symbols.image, false, &sections);
    if !symbols.debug.is_empty() {
      if let Some((debug_sections, _)) = read_sections(&symbols.debug) {
        let mut from_debug = collect_tables(&symbols.debug, true, &debug_sections);
        symbols.tables.append(&mut from_debug);
      }
    }

    (!symbols.tables.is_empty()).then_some(symbols)
  }

  fn buffer(&self, table: &Table) -> &[u8] {
    if table.debug {
      &self.debug
    } else {
      &self.image
    }
  }

  fn find(&self, matches: impl Fn(&str) -> bool) -> Option<u64> {
    for table in &self.tables {
      let buffer = self.buffer(table);
      let entries = &buffer[table.symbols.clone()];
      let strings = &buffer[table.strings.clone()];

      for entry in entries.chunks_exact(SYM_SIZE) {
        let value = read_u64(entry, 8)?;
        if value == 0 || read_u16(entry, 6)? == SHN_UNDEF {
          continue;
        }
        let Some(name) = section_name(strings, read_u32(entry, 0)?) else {
          continue;
        };
        if matches(name) {
          return Some(value);
        }
      }
    }
    None
  }

  pub fn exact(&self, name: &str) -> Option<u64> {
    self.find(|candidate| candidate == name)
  }

  pub fn prefix(&self, prefix: &str) -> Option<u64> {
    self.find(|candidate| candidate.starts_with(prefix))
  }
}

fn collect_tables(image: &[u8], debug: bool, sections: &[Section]) -> Vec<Table> {
  let mut tables = Vec::new();
  for section in sections {
    if section.kind != SHT_SYMTAB && section.kind != SHT_DYNSYM {
      continue;
    }
    if section.entsize as usize != SYM_SIZE {
      continue;
    }
    let Some(strtab) = sections.get(section.link as usize) else {
      continue;
    };
    let Some(symbols) = range_of(section.offset, section.size, image.len()) else {
      continue;
    };
    let Some(strings) = range_of(strtab.offset, strtab.size, image.len()) else {
      continue;
    };
    tables.push(Table { debug, symbols, strings });
  }
  tables
}

fn decompress_xz(compressed: &[u8]) -> Option<Vec<u8>> {
  let mut out = Vec::new();
  lzma_rs::xz_decompress(&mut std::io::Cursor::new(compressed), &mut out).ok()?;
  Some(out)
}

#[repr(C)]
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
struct DlPhdrInfo {
  addr: usize,
  name: *const c_char,
}

#[cfg(target_os = "android")]
extern "C" {
  fn dl_iterate_phdr(callback: extern "C" fn(*mut DlPhdrInfo, usize, *mut c_void) -> c_int, data: *mut c_void)
    -> c_int;
}

#[cfg(target_os = "android")]
struct Search {
  wanted: &'static str,
  found: Option<(usize, String)>,
}

#[cfg(target_os = "android")]
extern "C" fn visit(info: *mut DlPhdrInfo, _size: usize, data: *mut c_void) -> c_int {
  let search = unsafe { &mut *(data as *mut Search) };
  let info = unsafe { &*info };
  if info.name.is_null() {
    return 0;
  }
  let Ok(name) = (unsafe { CStr::from_ptr(info.name) }).to_str() else {
    return 0;
  };
  if !name.ends_with(search.wanted) {
    return 0;
  }
  search.found = Some((info.addr, name.to_string()));
  1
}

#[cfg(target_os = "android")]
fn find_loaded(name: &'static str) -> Option<(usize, String)> {
  let mut search = Search { wanted: name, found: None };
  unsafe { dl_iterate_phdr(visit, &mut search as *mut Search as *mut c_void) };
  search.found
}

#[cfg(not(target_os = "android"))]
fn find_loaded(_name: &'static str) -> Option<(usize, String)> {
  None
}

pub struct LoadedImage {
  bias: usize,
  symbols: Symbols,
}

impl LoadedImage {
  pub fn open(name: &'static str) -> Option<LoadedImage> {
    let (bias, path) = find_loaded(name)?;
    let image = fs::read(&path).ok()?;
    Some(LoadedImage { bias, symbols: Symbols::parse(image)? })
  }

  fn address(&self, value: Option<u64>) -> *mut c_void {
    match value.and_then(|value| usize::try_from(value).ok()) {
      Some(value) => self.bias.wrapping_add(value) as *mut c_void,
      None => std::ptr::null_mut(),
    }
  }

  pub fn exact(&self, name: &str) -> *mut c_void {
    self.address(self.symbols.exact(name))
  }

  pub fn prefix(&self, prefix: &str) -> *mut c_void {
    self.address(self.symbols.prefix(prefix))
  }
}

pub struct Resolver {
  image: Option<LoadedImage>,
  seen: HashMap<String, usize>,
}

impl Resolver {
  pub fn open(name: &'static str) -> Resolver {
    Resolver {
      image: LoadedImage::open(name),
      seen: HashMap::new(),
    }
  }

  fn cached(&mut self, key: String, lookup: impl FnOnce(&LoadedImage) -> *mut c_void) -> *mut c_void {
    if let Some(address) = self.seen.get(&key) {
      return *address as *mut c_void;
    }
    let address = self.image.as_ref().map(lookup).unwrap_or(std::ptr::null_mut());
    self.seen.insert(key, address as usize);
    address
  }

  pub fn exact(&mut self, name: &str) -> *mut c_void {
    self.cached(name.to_string(), |image| image.exact(name))
  }

  pub fn prefix(&mut self, prefix: &str) -> *mut c_void {
    self.cached(format!("{prefix} "), |image| image.prefix(prefix))
  }
}

#[cfg(test)]
#[path = "elf_tests.rs"]
mod tests;

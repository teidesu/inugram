use object::elf::{FileHeader64, SHT_DYNSYM, SHT_SYMTAB};
use object::read::elf::{FileHeader, SectionHeader, Sym, SymbolTable};
use object::LittleEndian;
use std::collections::HashMap;
use std::ffi::{c_char, c_void};
#[cfg(target_os = "android")]
use std::ffi::{c_int, CStr};
use std::fs;

type Header = FileHeader64<LittleEndian>;

fn read_tables(image: &[u8]) -> Vec<SymbolTable<'_, Header>> {
  let Ok(header) = Header::parse(image) else {
    return Vec::new();
  };
  let Ok(sections) = header.endian().and_then(|endian| header.sections(endian, image)) else {
    return Vec::new();
  };
  sections
    .enumerate()
    .filter(|(_, section)| matches!(section.sh_type(LittleEndian), SHT_SYMTAB | SHT_DYNSYM))
    .filter_map(|(index, section)| SymbolTable::parse(LittleEndian, image, &sections, index, section).ok())
    .collect()
}

fn read_debugdata(image: &[u8]) -> Option<Vec<u8>> {
  let header = Header::parse(image).ok()?;
  let sections = header.sections(header.endian().ok()?, image).ok()?;
  let (_, section) = sections.section_by_name(LittleEndian, b".gnu_debugdata")?;
  decompress_xz(section.data(LittleEndian, image).ok()?)
}

pub struct Symbols {
  image: Vec<u8>,
  debug: Vec<u8>,
}

impl Symbols {
  pub fn parse(image: Vec<u8>) -> Option<Symbols> {
    let debug = read_debugdata(&image).unwrap_or_default();
    let symbols = Symbols { image, debug };
    (!read_tables(&symbols.image).is_empty() || !read_tables(&symbols.debug).is_empty()).then_some(symbols)
  }

  fn find(&self, matches: impl Fn(&str) -> bool) -> Option<u64> {
    for image in [&self.image, &self.debug] {
      for table in read_tables(image) {
        for symbol in table.symbols() {
          let value = symbol.st_value(LittleEndian);
          if value == 0 || symbol.is_undefined(LittleEndian) {
            continue;
          }
          let Some(name) = symbol.name(LittleEndian, table.strings()).ok().and_then(|n| std::str::from_utf8(n).ok())
          else {
            continue;
          };
          if matches(name) {
            return Some(value);
          }
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

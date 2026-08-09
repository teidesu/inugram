/// a section name and the symbols in it: name, value, whether it is a function
type SyntheticTable = (&'static str, Vec<(&'static str, u64, bool)>);

use super::*;

/// Builds an ELF64 image with the symbol tables described, plus an optional `.gnu_debugdata`
/// section holding a second such image.
struct Builder {
  /// (section name, symbols as (name, value, defined))
  tables: Vec<SyntheticTable>,
  debugdata: Option<Vec<u8>>,
}

impl Builder {
  fn new() -> Builder {
    Builder { tables: Vec::new(), debugdata: None }
  }

  fn table(mut self, name: &'static str, symbols: &[(&'static str, u64, bool)]) -> Builder {
    self.tables.push((name, symbols.to_vec()));
    self
  }

  fn debugdata(mut self, inner: Vec<u8>) -> Builder {
    let mut compressed = Vec::new();
    lzma_rs::xz_compress(&mut std::io::Cursor::new(&inner), &mut compressed).unwrap();
    self.debugdata = Some(compressed);
    self
  }

  fn build(self) -> Vec<u8> {
    let mut shstrtab = vec![0u8];
    let name_of = |strings: &mut Vec<u8>, text: &str| {
      let at = strings.len() as u32;
      strings.extend_from_slice(text.as_bytes());
      strings.push(0);
      at
    };

    // section 0 is the mandatory null entry
    let mut sections: Vec<[u8; SHDR_SIZE]> = vec![[0u8; SHDR_SIZE]];
    let mut blobs: Vec<Vec<u8>> = vec![Vec::new()];

    for (name, symbols) in &self.tables {
      let mut strtab = vec![0u8];
      let mut entries = Vec::new();
      for (symbol, value, defined) in symbols {
        let at = name_of(&mut strtab, symbol);
        let mut entry = [0u8; SYM_SIZE];
        entry[0..4].copy_from_slice(&at.to_le_bytes());
        entry[6..8].copy_from_slice(&if *defined { 1u16 } else { SHN_UNDEF }.to_le_bytes());
        entry[8..16].copy_from_slice(&value.to_le_bytes());
        entries.extend_from_slice(&entry);
      }

      let strtab_index = sections.len() + 1;
      sections.push(header(
        name_of(&mut shstrtab, name),
        if *name == ".dynsym" { SHT_DYNSYM } else { SHT_SYMTAB },
        strtab_index as u32,
        SYM_SIZE as u64,
      ));
      blobs.push(entries);
      sections.push(header(name_of(&mut shstrtab, ".strtab"), 3, 0, 0));
      blobs.push(strtab);
    }

    if let Some(compressed) = &self.debugdata {
      sections.push(header(name_of(&mut shstrtab, ".gnu_debugdata"), 1, 0, 0));
      blobs.push(compressed.clone());
    }

    sections.push(header(name_of(&mut shstrtab, ".shstrtab"), 3, 0, 0));
    let shstrndx = sections.len() - 1;
    blobs.push(shstrtab);

    let mut image = vec![0u8; EHDR_SIZE];
    image[0..4].copy_from_slice(&ELF_MAGIC);
    image[4] = ELFCLASS64;
    image[5] = ELFDATA2LSB;

    for (index, blob) in blobs.iter().enumerate() {
      let offset = image.len() as u64;
      sections[index][0x18..0x20].copy_from_slice(&offset.to_le_bytes());
      sections[index][0x20..0x28].copy_from_slice(&(blob.len() as u64).to_le_bytes());
      image.extend_from_slice(blob);
    }
    // section 0 stays all-zero whatever offset the loop above wrote into it
    sections[0] = [0u8; SHDR_SIZE];

    let shoff = image.len() as u64;
    for section in &sections {
      image.extend_from_slice(section);
    }

    image[0x28..0x30].copy_from_slice(&shoff.to_le_bytes());
    image[0x3a..0x3c].copy_from_slice(&(SHDR_SIZE as u16).to_le_bytes());
    image[0x3c..0x3e].copy_from_slice(&(sections.len() as u16).to_le_bytes());
    image[0x3e..0x40].copy_from_slice(&(shstrndx as u16).to_le_bytes());
    image
  }
}

fn header(name: u32, kind: u32, link: u32, entsize: u64) -> [u8; SHDR_SIZE] {
  let mut section = [0u8; SHDR_SIZE];
  section[0..4].copy_from_slice(&name.to_le_bytes());
  section[4..8].copy_from_slice(&kind.to_le_bytes());
  section[0x28..0x2c].copy_from_slice(&link.to_le_bytes());
  section[0x38..0x40].copy_from_slice(&entsize.to_le_bytes());
  section
}

#[test]
fn a_symbol_is_found_in_dynsym() {
  let image = Builder::new().table(".dynsym", &[("_ZN3art9ArtMethod6InvokeEv", 0x1234, true)]).build();
  let symbols = Symbols::parse(image).expect("parses");
  assert_eq!(symbols.exact("_ZN3art9ArtMethod6InvokeEv"), Some(0x1234));
  assert_eq!(symbols.exact("_ZN3art9ArtMethod6InvokeE"), None);
}

#[test]
fn a_prefix_matches_the_first_defined_symbol_that_starts_with_it() {
  let image = Builder::new()
    .table(
      ".dynsym",
      &[("_ZN3art11ClassLinker5OtherEv", 0x10, true), ("_ZN3art11ClassLinker11SetEntryPointsEv", 0x20, true)],
    )
    .build();
  let symbols = Symbols::parse(image).expect("parses");
  assert_eq!(symbols.prefix("_ZN3art11ClassLinker11Set"), Some(0x20));
  assert_eq!(symbols.prefix("_ZN3art11ClassLinker"), Some(0x10));
  assert_eq!(symbols.prefix("_ZN3art9Something"), None);
}

#[test]
fn an_undefined_symbol_is_not_an_answer() {
  // libart's .dynsym carries plenty of these, and they name no address
  let image = Builder::new().table(".dynsym", &[("memcpy", 0x99, false), ("memcpy", 0x77, true)]).build();
  let symbols = Symbols::parse(image).expect("parses");
  assert_eq!(symbols.exact("memcpy"), Some(0x77));
}

#[test]
fn a_zero_valued_symbol_is_not_an_answer() {
  let image = Builder::new()
    .table(".symtab", &[("_ZN3art3Foo3BarEv", 0, true), ("_ZN3art3Foo3BarEv", 0x40, true)])
    .build();
  let symbols = Symbols::parse(image).expect("parses");
  assert_eq!(symbols.exact("_ZN3art3Foo3BarEv"), Some(0x40));
}

#[test]
fn symtab_is_searched_as_well_as_dynsym() {
  let image = Builder::new()
    .table(".dynsym", &[("exported", 0x10, true)])
    .table(".symtab", &[("local_only", 0x20, true)])
    .build();
  let symbols = Symbols::parse(image).expect("parses");
  assert_eq!(symbols.exact("local_only"), Some(0x20));
}

#[test]
fn the_symtab_inside_gnu_debugdata_is_searched() {
  // the platform's own libart.so keeps its real symtab here, xz-compressed
  let inner = Builder::new().table(".symtab", &[("_ZN3art6Hidden4ThingEv", 0x555, true)]).build();
  let image = Builder::new().table(".dynsym", &[("exported", 0x10, true)]).debugdata(inner).build();
  let symbols = Symbols::parse(image).expect("parses");
  assert_eq!(symbols.exact("_ZN3art6Hidden4ThingEv"), Some(0x555));
  assert_eq!(symbols.prefix("_ZN3art6Hidden"), Some(0x555));
  assert_eq!(symbols.exact("exported"), Some(0x10));
}

#[test]
fn a_corrupt_gnu_debugdata_leaves_the_outer_tables_usable() {
  let mut image = Builder::new()
    .table(".dynsym", &[("exported", 0x10, true)])
    .debugdata(Builder::new().table(".symtab", &[("inner", 0x20, true)]).build())
    .build();
  // the xz stream starts with a 6-byte magic; break it without moving anything
  let at = image.windows(6).position(|w| w == [0xfd, b'7', b'z', b'X', b'Z', 0x00]).expect("xz magic");
  image[at + 1] = b'8';

  let symbols = Symbols::parse(image).expect("parses");
  assert_eq!(symbols.exact("exported"), Some(0x10));
  assert_eq!(symbols.exact("inner"), None);
}

#[test]
fn a_truncated_image_is_refused_rather_than_read_past() {
  let full = Builder::new().table(".dynsym", &[("exported", 0x10, true)]).build();
  for length in [0, 4, 16, EHDR_SIZE, full.len() - 1] {
    assert!(Symbols::parse(full[..length].to_vec()).is_none(), "accepted {length} bytes");
  }
}

#[test]
fn a_32_bit_or_big_endian_image_is_refused() {
  // the app is arm64-v8a only, and reading an ELF32 with 64-bit offsets would answer garbage
  let mut image = Builder::new().table(".dynsym", &[("exported", 0x10, true)]).build();
  image[4] = 1;
  assert!(Symbols::parse(image.clone()).is_none());
  image[4] = ELFCLASS64;
  image[5] = 2;
  assert!(Symbols::parse(image).is_none());
}

#[test]
fn an_image_with_no_symbol_table_at_all_is_not_a_source() {
  let image = Builder::new().build();
  assert!(Symbols::parse(image).is_none());
}

#[test]
fn the_bias_is_added_to_every_answer() {
  let image = Builder::new().table(".dynsym", &[("exported", 0x10, true)]).build();
  let loaded = LoadedImage {
    bias: 0x7000,
    symbols: Symbols::parse(image).expect("parses"),
  };
  assert_eq!(loaded.exact("exported"), 0x7010 as *mut c_void);
  assert_eq!(loaded.prefix("expo"), 0x7010 as *mut c_void);
  assert_eq!(loaded.exact("missing"), std::ptr::null_mut());
}

#[test]
fn a_resolver_over_no_image_answers_null_rather_than_failing() {
  // a device whose libart.so cannot be read still has to init far enough to say so
  let mut resolver = Resolver { image: None, seen: HashMap::new() };
  assert_eq!(resolver.exact("anything"), std::ptr::null_mut());
  assert_eq!(resolver.prefix("any"), std::ptr::null_mut());
}

#[test]
fn exact_and_prefix_answers_do_not_share_a_cache_entry() {
  let image = Builder::new().table(".dynsym", &[("prefixed_and_more", 0x10, true)]).build();
  let loaded = LoadedImage {
    bias: 0,
    symbols: Symbols::parse(image).expect("parses"),
  };
  let mut resolver = Resolver {
    image: Some(loaded),
    seen: HashMap::new(),
  };

  assert_eq!(resolver.exact("prefixed"), std::ptr::null_mut());
  assert_eq!(resolver.prefix("prefixed"), 0x10 as *mut c_void);
  assert_eq!(resolver.exact("prefixed"), std::ptr::null_mut());
}

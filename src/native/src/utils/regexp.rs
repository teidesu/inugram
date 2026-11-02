use std::cell::Cell;
use std::ffi::{c_char, c_int, c_void, CStr};
use std::fmt;
use std::ops::Range;
use std::ptr::NonNull;
use std::sync::Arc;
use std::time::{Duration, Instant};

use rquickjs::qjs;

use crate::sandbox::limits::fit_runtime_stack_limit;

/// what one pattern may spend on one text before it counts as failed: matching backtracks
pub(crate) const BUDGET: Duration = Duration::from_millis(10);
/// mostly the backtracking stack, shared by every match on a thread but one match at a time
const MEMORY_LIMIT_BYTES: usize = 16 * 1024 * 1024;

// libregexp.h
const LRE_FLAG_IGNORECASE: c_int = 1 << 1;
const LRE_FLAG_MULTILINE: c_int = 1 << 2;
const LRE_FLAG_DOTALL: c_int = 1 << 3;
const LRE_FLAG_UNICODE: c_int = 1 << 4;
const LRE_FLAG_STICKY: c_int = 1 << 5;
const LRE_FLAG_UNICODE_SETS: c_int = 1 << 8;
const LRE_RET_TIMEOUT: c_int = -2;
const LRE_GROUP_NAME_TRAILER_LEN: usize = 2;
/// utf-16 input, read as code points under `u` or `v`
const CBUF_UTF16: c_int = 1;

unsafe extern "C" {
  fn lre_compile(
    plen: *mut c_int,
    error_msg: *mut c_char,
    error_msg_size: c_int,
    buf: *const c_char,
    buf_len: usize,
    re_flags: c_int,
    opaque: *mut c_void,
  ) -> *mut u8;
  fn lre_exec(
    capture: *mut *mut u8,
    bc_buf: *const u8,
    cbuf: *const u8,
    cindex: c_int,
    clen: c_int,
    cbuf_type: c_int,
    opaque: *mut c_void,
  ) -> c_int;
  fn lre_get_alloc_count(bc_buf: *const u8) -> c_int;
  fn lre_get_capture_count(bc_buf: *const u8) -> c_int;
  fn lre_get_groupnames(bc_buf: *const u8) -> *const c_char;
  fn lre_realloc(opaque: *mut c_void, ptr: *mut c_void, size: usize) -> *mut c_void;
}

/// quickjs implements libregexp's allocator, stack check and timeout poll on the `JSContext` passed
/// as `opaque`, so each thread that compiles or matches keeps a bare one, and needs no engine lease
struct Host {
  rt: NonNull<qjs::JSRuntime>,
  ctx: NonNull<qjs::JSContext>,
}

impl Host {
  fn new() -> Option<Host> {
    // SAFETY: a runtime and context only this thread touches, freed once in `drop`
    unsafe {
      let rt = NonNull::new(qjs::JS_NewRuntime())?;
      let Some(ctx) = NonNull::new(qjs::JS_NewContextRaw(rt.as_ptr())) else {
        qjs::JS_FreeRuntime(rt.as_ptr());
        return None;
      };
      qjs::JS_SetMemoryLimit(rt.as_ptr(), MEMORY_LIMIT_BYTES as _);
      qjs::JS_SetInterruptHandler(rt.as_ptr(), Some(interrupt_past_deadline), std::ptr::null_mut());
      Some(Host { rt, ctx })
    }
  }
}

impl Drop for Host {
  fn drop(&mut self) {
    // SAFETY: created in `new`, and nothing holds them past the thread
    unsafe {
      qjs::JS_FreeContext(self.ctx.as_ptr());
      qjs::JS_FreeRuntime(self.rt.as_ptr());
    }
  }
}

thread_local! {
    static HOST: Option<Host> = Host::new();
    static DEADLINE: Cell<Option<Instant>> = const { Cell::new(None) };
}

unsafe extern "C" fn interrupt_past_deadline(_rt: *mut qjs::JSRuntime, _opaque: *mut c_void) -> c_int {
  DEADLINE.get().is_some_and(|deadline| Instant::now() >= deadline) as c_int
}

fn with_host<R>(f: impl FnOnce(&Host) -> R) -> Option<R> {
  HOST.try_with(|host| host.as_ref().map(f)).ok().flatten()
}

#[derive(Debug)]
pub(crate) enum ExecError {
  Budget,
  Memory,
}

impl fmt::Display for ExecError {
  fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
    match self {
      ExecError::Budget => write!(f, "ran past its {}ms budget", BUDGET.as_millis()),
      ExecError::Memory => write!(f, "ran out of memory"),
    }
  }
}

/// A JS `RegExp`, compiled by the engine's own regexp library to match from any thread
#[derive(Clone)]
pub(crate) struct Regexp(Arc<Compiled>);

struct Compiled {
  bytecode: Box<[u8]>,
  /// per capture group after the whole match, `None` when unnamed
  names: Vec<Option<String>>,
  unicode: bool,
  /// `/source/flags`
  shown: String,
}

impl Regexp {
  /// `flags` as `RegExp.prototype.flags` spells them; `d` and `g` mean nothing here
  pub(crate) fn new(source: &str, flags: &str) -> Result<Regexp, String> {
    let mut re_flags = 0;
    for flag in flags.chars() {
      re_flags |= match flag {
        'd' | 'g' => 0,
        'i' => LRE_FLAG_IGNORECASE,
        'm' => LRE_FLAG_MULTILINE,
        's' => LRE_FLAG_DOTALL,
        'u' => LRE_FLAG_UNICODE,
        'v' => LRE_FLAG_UNICODE_SETS,
        'y' => LRE_FLAG_STICKY,
        _ => return Err(format!("unsupported flag '{flag}'")),
      };
    }
    let unicode = re_flags & (LRE_FLAG_UNICODE | LRE_FLAG_UNICODE_SETS) != 0;
    if re_flags & LRE_FLAG_UNICODE != 0 && re_flags & LRE_FLAG_UNICODE_SETS != 0 {
      return Err("'u' and 'v' do not combine".to_string());
    }
    // libregexp reads up to a terminating NUL
    let mut buf = Vec::with_capacity(source.len() + 1);
    buf.extend_from_slice(source.as_bytes());
    buf.push(0);
    let bytecode = with_host(|host| {
      let opaque = host.ctx.as_ptr().cast();
      let mut error = [0 as c_char; 64];
      let mut len: c_int = 0;
      // SAFETY: this thread's host; `buf` is NUL-terminated, and the bytecode is copied out and
      // freed through the same host before anything else runs on it
      unsafe {
        fit_runtime_stack_limit(host.rt.as_ptr());
        let compiled = lre_compile(
          &mut len,
          error.as_mut_ptr(),
          error.len() as c_int,
          buf.as_ptr().cast(),
          source.len(),
          re_flags,
          opaque,
        );
        if compiled.is_null() {
          return Err(CStr::from_ptr(error.as_ptr()).to_string_lossy().into_owned());
        }
        let bytecode = Box::<[u8]>::from(std::slice::from_raw_parts(compiled, len as usize));
        lre_realloc(opaque, compiled.cast(), 0);
        Ok(bytecode)
      }
    })
    .unwrap_or_else(|| Err("out of memory".to_string()))?;

    // SAFETY: bytecode lre_compile produced; the names are NUL-terminated, one per group after the
    // whole match, each followed by its trailer
    let names = unsafe {
      let count = lre_get_capture_count(bytecode.as_ptr()) as usize;
      let mut at = lre_get_groupnames(bytecode.as_ptr());
      (1..count)
        .map(|_| {
          if at.is_null() {
            return None;
          }
          let name = CStr::from_ptr(at);
          at = at.add(name.to_bytes().len() + LRE_GROUP_NAME_TRAILER_LEN);
          (!name.is_empty()).then(|| name.to_string_lossy().into_owned())
        })
        .collect()
    };
    Ok(Regexp(Arc::new(Compiled {
      bytecode,
      names,
      unicode,
      shown: format!("/{source}/{flags}"),
    })))
  }

  pub(crate) fn group_names(&self) -> impl Iterator<Item = &str> {
    self.0.names.iter().flatten().map(String::as_str)
  }

  pub(crate) fn is_match(&self, text: &str, deadline: Instant) -> Result<bool, ExecError> {
    let text: Vec<u16> = text.encode_utf16().collect();
    self.exec(&text, 0, deadline).map(|groups| groups.is_some())
  }

  /// `String.prototype.matchAll`, ending after the first error
  pub(crate) fn captures_iter<'a>(
    &'a self,
    text: &'a [u16],
    deadline: Instant,
  ) -> impl Iterator<Item = Result<Captures<'a>, ExecError>> + 'a {
    let mut next = Some(0);
    std::iter::from_fn(move || {
      let groups = match self.exec(text, next?, deadline) {
        Ok(Some(groups)) => groups,
        Ok(None) => {
          next = None;
          return None;
        }
        Err(e) => {
          next = None;
          return Some(Err(e));
        }
      };
      let whole = groups[0].clone().unwrap_or_default();
      next = Some(if whole.is_empty() { self.advance(text, whole.end) } else { whole.end });
      Some(Ok(Captures { regexp: self, text, groups }))
    })
  }

  /// `AdvanceStringIndex`: past a whole surrogate pair under `u` or `v`
  fn advance(&self, text: &[u16], at: usize) -> usize {
    let pair = self.0.unicode
      && text.get(at).is_some_and(|unit| (0xD800..0xDC00).contains(unit))
      && text.get(at + 1).is_some_and(|unit| (0xDC00..0xE000).contains(unit));
    at + if pair { 2 } else { 1 }
  }

  /// the groups of the first match from `at`, or at `at` under `y`, in utf-16 units
  fn exec(&self, text: &[u16], at: usize, deadline: Instant) -> Result<Option<Vec<Option<Range<usize>>>>, ExecError> {
    if at > text.len() {
      return Ok(None);
    }
    let bytecode = self.0.bytecode.as_ptr();
    // SAFETY: bytecode lre_compile produced
    let (alloc_count, count) = unsafe { (lre_get_alloc_count(bytecode), lre_get_capture_count(bytecode)) };
    let mut capture = vec![std::ptr::null_mut::<u8>(); alloc_count as usize];
    let base = text.as_ptr().cast::<u8>();
    DEADLINE.set(Some(deadline));
    // SAFETY: this thread's host; `capture` holds the slots the bytecode asks for, and libregexp
    // reads `text.len()` units from `base`. Java strings keep lengths within `c_int`
    let ret = with_host(|host| unsafe {
      lre_exec(
        capture.as_mut_ptr(),
        bytecode,
        base,
        at as c_int,
        text.len() as c_int,
        CBUF_UTF16,
        host.ctx.as_ptr().cast(),
      )
    });
    DEADLINE.set(None);
    match ret {
      Some(1) => {}
      Some(0) => return Ok(None),
      Some(LRE_RET_TIMEOUT) => return Err(ExecError::Budget),
      _ => return Err(ExecError::Memory),
    }
    let offset = |ptr: *mut u8| (ptr as usize - base as usize) / 2;
    Ok(Some(
      capture[..count as usize * 2]
        .chunks(2)
        .map(|pair| (!pair[0].is_null() && !pair[1].is_null()).then(|| offset(pair[0])..offset(pair[1])))
        .collect(),
    ))
  }
}

impl fmt::Display for Regexp {
  fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
    f.write_str(&self.0.shown)
  }
}

/// one match's groups; a group that took no part is `None`
pub(crate) struct Captures<'a> {
  regexp: &'a Regexp,
  text: &'a [u16],
  groups: Vec<Option<Range<usize>>>,
}

impl Captures<'_> {
  pub(crate) fn len(&self) -> usize {
    self.groups.len()
  }

  /// in utf-16 units
  pub(crate) fn range(&self, index: usize) -> Option<Range<usize>> {
    self.groups.get(index)?.clone()
  }

  pub(crate) fn get(&self, index: usize) -> Option<String> {
    self.range(index).map(|range| String::from_utf16_lossy(&self.text[range]))
  }

  /// of duplicate names, the group that took part
  pub(crate) fn name(&self, name: &str) -> Option<String> {
    let names = self.regexp.0.names.iter().enumerate();
    names.filter(|(_, candidate)| candidate.as_deref() == Some(name)).find_map(|(index, _)| self.get(index + 1))
  }
}

use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::ptr;
use std::sync::{Mutex, OnceLock};

use jni::objects::JObject;
use jni::strings::JNIString;
use jni::sys::{jclass, jobject, jobjectArray, JNIEnv as RawJNIEnv};
use jni::Env;

use crate::api::platform::xposed::elf::Resolver;
use crate::jni::env::clear_exception;

type ShadowhookInit = unsafe extern "C" fn(c_int, bool) -> c_int;
type ShadowhookDlopen = unsafe extern "C" fn(*const c_char) -> *mut c_void;
type ShadowhookDlsym = unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_void;
type ShadowhookHookAddr = unsafe extern "C" fn(*mut c_void, *mut c_void, *mut *mut c_void) -> *mut c_void;
type ShadowhookUnhook = unsafe extern "C" fn(*mut c_void) -> c_int;

type SymbolResolver = extern "C" fn(*const c_char, usize) -> *mut c_void;

#[repr(C)]
struct LSPlantInitInfoC {
  inline_hooker: extern "C" fn(*mut c_void, *mut c_void) -> *mut c_void,
  inline_unhooker: extern "C" fn(*mut c_void) -> bool,
  art_symbol_resolver: SymbolResolver,
  art_symbol_prefix_resolver: SymbolResolver,
  generated_class_name: *const c_char,
  generated_source_name: *const c_char,
  generated_field_name: *const c_char,
  generated_method_name: *const c_char,
}

type LSPlantInit = unsafe extern "C" fn(*mut RawJNIEnv, *const LSPlantInitInfoC) -> bool;
type LSPlantHook = unsafe extern "C" fn(*mut RawJNIEnv, jobject, jobject, jobject) -> jobject;
type LSPlantUnHook = unsafe extern "C" fn(*mut RawJNIEnv, jobject) -> bool;
type LSPlantOnObject = unsafe extern "C" fn(*mut RawJNIEnv, jobject) -> bool;
type SetHiddenApiExemptions = unsafe extern "C" fn(*mut RawJNIEnv, jclass, jobjectArray);

struct Shadowhook {
  dlopen: ShadowhookDlopen,
  dlsym: ShadowhookDlsym,
  hook_addr: ShadowhookHookAddr,
  unhook: ShadowhookUnhook,
}

struct LSPlant {
  init: LSPlantInit,
  hook: LSPlantHook,
  unhook: LSPlantUnHook,
  is_hooked: LSPlantOnObject,
}

struct Native {
  shadowhook: Shadowhook,
  lsplant: LSPlant,
  art: Mutex<Resolver>,
  art_handle: usize,
}

static NATIVE: OnceLock<Option<Native>> = OnceLock::new();

static INITIALIZED: OnceLock<bool> = OnceLock::new();
static PROFILE_SAVER_DISABLED: OnceLock<bool> = OnceLock::new();

fn native() -> Option<&'static Native> {
  NATIVE.get().and_then(|slot| slot.as_ref())
}

impl Native {
  fn find_art_export(&self, name: &str) -> *mut c_void {
    let Ok(name) = CString::new(name) else { return ptr::null_mut() };
    if self.art_handle == 0 {
      return ptr::null_mut();
    }
    // SAFETY: `art_handle` came from `shadowhook_dlopen` and is never closed
    unsafe { (self.shadowhook.dlsym)(self.art_handle as *mut c_void, name.as_ptr()) }
  }
}

fn dlopen(name: &CStr) -> Option<*mut c_void> {
  // SAFETY: a valid C string naming one of the app's own libraries, whose constructors need nothing
  let handle = unsafe { libc::dlopen(name.as_ptr(), libc::RTLD_NOW) };
  (!handle.is_null()).then_some(handle)
}

/// # Safety
/// `T` must be a function pointer type matching the C declaration of `name`.
unsafe fn dlsym<T: Copy>(handle: *mut c_void, name: &CStr) -> Option<T> {
  let symbol = libc::dlsym(handle, name.as_ptr());
  (!symbol.is_null()).then(|| std::mem::transmute_copy(&symbol))
}

#[cfg(target_os = "android")]
#[link(name = "log")]
extern "C" {
  fn __android_log_write(priority: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

#[allow(unused)]
fn log_init_failure(message: &str) {
  #[cfg(target_os = "android")]
  {
    let Ok(message) = CString::new(format!("[xposed] {message}")) else { return };
    // SAFETY: both arguments are valid C strings for the duration of the call
    unsafe { __android_log_write(6, c"InuPluginHost".as_ptr(), message.as_ptr()) };
  }
}

fn read_symbol_name(name: *const c_char, length: usize) -> &'static str {
  if name.is_null() {
    return "";
  }
  // SAFETY: lsplant passes a pointer to `length` bytes of a symbol name it keeps alive for the call
  let bytes = unsafe { std::slice::from_raw_parts(name as *const u8, length) };
  std::str::from_utf8(bytes).unwrap_or("")
}

extern "C" fn resolve_exact(name: *const c_char, length: usize) -> *mut c_void {
  let Some(native) = native() else {
    return ptr::null_mut();
  };
  let name = read_symbol_name(name, length);
  if name.is_empty() {
    return ptr::null_mut();
  }
  let found = native.find_art_export(name);
  if !found.is_null() {
    return found;
  }
  match native.art.lock() {
    Ok(mut art) => art.exact(name),
    Err(_) => ptr::null_mut(),
  }
}

extern "C" fn resolve_prefix(prefix: *const c_char, length: usize) -> *mut c_void {
  let Some(native) = native() else {
    return ptr::null_mut();
  };
  let prefix = read_symbol_name(prefix, length);
  if prefix.is_empty() {
    return ptr::null_mut();
  }
  match native.art.lock() {
    Ok(mut art) => art.prefix(prefix),
    Err(_) => ptr::null_mut(),
  }
}

extern "C" fn inline_hooker(target: *mut c_void, hooker: *mut c_void) -> *mut c_void {
  let Some(native) = native() else {
    return ptr::null_mut();
  };
  let mut original: *mut c_void = ptr::null_mut();
  // SAFETY: lsplant hands over a function entry in libart and its replacement
  let stub = unsafe { (native.shadowhook.hook_addr)(target, hooker, &mut original) };
  if stub.is_null() {
    return ptr::null_mut();
  }
  original
}

extern "C" fn inline_unhooker(func: *mut c_void) -> bool {
  let Some(native) = native() else { return false };
  // SAFETY: lsplant only unhooks stubs `inline_hooker` returned
  unsafe { (native.shadowhook.unhook)(func) == 0 }
}

const SHADOWHOOK_MODE_UNIQUE: c_int = 1;
const SET_HIDDEN_API_EXEMPTIONS: &str = "_ZN3artL32VMRuntime_setHiddenApiExemptionsEP7_JNIEnvP7_jclassP13_jobjectArray";

fn disable_hidden_api(env: &mut Env, native: &Native) -> bool {
  let mut address = native.find_art_export(SET_HIDDEN_API_EXEMPTIONS);
  if address.is_null() {
    address = match native.art.lock() {
      Ok(mut art) => art.prefix(SET_HIDDEN_API_EXEMPTIONS),
      Err(_) => ptr::null_mut(),
    };
  };
  if address.is_null() {
    log_init_failure("could not resolve VMRuntime_setHiddenApiExemptions");
    return true;
  }

  let Ok(string_class) = env.find_class(JNIString::from("java/lang/String")) else {
    log_init_failure("could not resolve java.lang.String for hidden API exemptions");
    clear_exception(env);
    return false;
  };
  let Ok(prefix) = env.new_string("L") else {
    log_init_failure("could not create hidden API exemption prefix");
    clear_exception(env);
    return false;
  };
  let Ok(exemptions) = env.new_object_array(1, &string_class, &prefix) else {
    log_init_failure("could not create hidden API exemption array");
    clear_exception(env);
    return false;
  };

  // SAFETY: the mangled name pins the C++ signature `SetHiddenApiExemptions` mirrors, and both
  // references are live locals of this env
  unsafe {
    let set_exemptions: SetHiddenApiExemptions = std::mem::transmute(address);
    set_exemptions(env.get_raw(), string_class.as_raw(), exemptions.as_raw());
  }
  if clear_exception(env) {
    log_init_failure("VMRuntime_setHiddenApiExemptions threw");
    return false;
  }
  true
}

fn load() -> Option<Native> {
  let shadowhook_lib = dlopen(c"libshadowhook.so")?;
  // SAFETY: each field's type alias mirrors the C declaration of the symbol read into it
  // (shadowhook.h, and the LSPlant*C wrappers in patches-native/lsplant-c-abi.patch)
  let (shadowhook, lsplant) = unsafe {
    let init: ShadowhookInit = dlsym(shadowhook_lib, c"shadowhook_init")?;
    if init(SHADOWHOOK_MODE_UNIQUE, false) != 0 {
      return None;
    }
    let shadowhook = Shadowhook {
      dlopen: dlsym(shadowhook_lib, c"shadowhook_dlopen")?,
      dlsym: dlsym(shadowhook_lib, c"shadowhook_dlsym")?,
      hook_addr: dlsym(shadowhook_lib, c"shadowhook_hook_func_addr")?,
      unhook: dlsym(shadowhook_lib, c"shadowhook_unhook")?,
    };
    let lsplant_lib = dlopen(c"liblsplant.so")?;
    let lsplant = LSPlant {
      init: dlsym(lsplant_lib, c"LSPlantInitC")?,
      hook: dlsym(lsplant_lib, c"LSPlantHookC")?,
      unhook: dlsym(lsplant_lib, c"LSPlantUnHookC")?,
      is_hooked: dlsym(lsplant_lib, c"LSPlantIsHookedC")?,
    };
    (shadowhook, lsplant)
  };

  // SAFETY: a valid C string; the handle is kept for the process lifetime
  let art_handle = unsafe { (shadowhook.dlopen)(c"libart.so".as_ptr()) } as usize;

  Some(Native {
    shadowhook,
    lsplant,
    art: Mutex::new(Resolver::open("libart.so")),
    art_handle,
  })
}

pub fn init(env: &mut Env) -> bool {
  let loaded = NATIVE.get_or_init(load);
  let Some(native) = loaded.as_ref() else {
    log_init_failure("could not load xposed native libraries");
    return false;
  };

  *INITIALIZED.get_or_init(|| {
    if !disable_hidden_api(env, native) {
      return false;
    }
    let info = LSPlantInitInfoC {
      inline_hooker,
      inline_unhooker,
      art_symbol_resolver: resolve_exact,
      art_symbol_prefix_resolver: resolve_prefix,
      generated_class_name: ptr::null(),
      generated_source_name: ptr::null(),
      generated_field_name: ptr::null(),
      generated_method_name: ptr::null(),
    };
    // SAFETY: `info` outlives the call, and every callback in it is valid for the process lifetime
    let initialized = unsafe { (native.lsplant.init)(env.get_raw(), &info) };
    if !initialized {
      log_init_failure("LSPlantInitC failed");
    }
    initialized
  })
}

// SAFETY (all three): lsplant takes local or global references the caller keeps alive for the call
pub fn hook(env: &mut Env, target: &JObject, hooker: &JObject, callback: &JObject) -> jobject {
  let Some(native) = native() else {
    return ptr::null_mut();
  };
  unsafe { (native.lsplant.hook)(env.get_raw(), target.as_raw(), hooker.as_raw(), callback.as_raw()) }
}

pub fn unhook(env: &mut Env, target: &JObject) -> bool {
  let Some(native) = native() else { return false };
  unsafe { (native.lsplant.unhook)(env.get_raw(), target.as_raw()) }
}

pub fn is_hooked(env: &mut Env, target: &JObject) -> bool {
  let Some(native) = native() else { return false };
  unsafe { (native.lsplant.is_hooked)(env.get_raw(), target.as_raw()) }
}

extern "C" fn ignore_profile_saver() -> bool {
  true
}

pub fn disable_profile_saver() -> bool {
  *PROFILE_SAVER_DISABLED.get_or_init(|| {
    let Some(native) = native() else { return false };
    let symbols = [
      "_ZN3art12ProfileSaver20ProcessProfilingInfoEbPtb",
      "_ZN3art12ProfileSaver20ProcessProfilingInfoEPt",
      "_ZN3art12ProfileSaver20ProcessProfilingInfoEbPt",
      "_ZN3art12ProfileSaver20ProcessProfilingInfoEbbPt",
    ];
    let address = match native.art.lock() {
      Ok(mut art) => symbols.into_iter().map(|symbol| art.exact(symbol)).find(|address| !address.is_null()),
      Err(_) => None,
    };
    let Some(address) = address else {
      log_init_failure("could not resolve ProfileSaver::ProcessProfilingInfo");
      return false;
    };
    let mut original = ptr::null_mut();
    // SAFETY: `address` is ProcessProfilingInfo's entry in libart, and the replacement takes no
    // arguments it would need to read
    let hook = unsafe {
      (native.shadowhook.hook_addr)(address, ignore_profile_saver as *const () as *mut c_void, &mut original)
    };
    if hook.is_null() {
      log_init_failure("could not disable ProfileSaver");
      return false;
    }
    true
  })
}

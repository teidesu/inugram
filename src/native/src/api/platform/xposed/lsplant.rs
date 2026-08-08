//! Loading lsplant and shadowhook, and standing up the four callbacks `LSPlantInitC` demands.
//!
//! Neither library is linked: both are `dlopen`ed by name and every entry point is a `dlsym`, which
//! is what keeps the engine a plain cargo cdylib built outside CMake while lsplant is a CMake
//! project built by AGP. The C entry points come from `patches-native/lsplant-c-abi.patch`, applied
//! to the submodule: lsplant's own interface is C++ linkage and `lsplant::Init` takes a struct of
//! `std::function`, which has no C representation.
//!
//! **Exact lookups go to shadowhook first.** `shadowhook_dlsym` is maintained against the platform
//! and reads `.dynsym` *and* `.symtab` past linker namespace restrictions, including inside an
//! APEX. It has no prefix search, which is why [`crate::api::platform::xposed::elf`] exists at all.

use std::ffi::{c_char, c_int, c_void, CString};
use std::ptr;
use std::sync::{Mutex, OnceLock};

use jni::sys::{jclass, jobject, JNIEnv as RawJNIEnv};
use jni::Env;

use crate::api::platform::xposed::elf::Resolver;

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
type LSPlantOnClass = unsafe extern "C" fn(*mut RawJNIEnv, jclass) -> bool;

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
    deoptimize: LSPlantOnObject,
    make_inheritable: LSPlantOnClass,
}

/// Every library handle and entry point this module resolved, plus the `libart.so` reader.
///
/// One process-wide slot rather than one per engine: an ART entry point is rewritten for the whole
/// process, and `lsplant::Init` installs hooks of its own that must run exactly once.
struct Native {
    shadowhook: Shadowhook,
    lsplant: LSPlant,
    art: Mutex<Resolver>,
    /// `shadowhook_dlopen("libart.so")`, or null where it could not be opened
    art_handle: usize,
}

// SAFETY: every member is either a function pointer into a library that is never unloaded, a
// `Mutex`, or an address. The `*mut c_void` handle is kept as a `usize` for exactly this reason.
unsafe impl Send for Native {}
unsafe impl Sync for Native {}

static NATIVE: OnceLock<Option<Native>> = OnceLock::new();

/// Whether `lsplant::Init` succeeded. Separate from [`NATIVE`] because the resolvers it calls read
/// that slot, so it cannot be published from inside that slot's own initializer.
static INITIALIZED: OnceLock<bool> = OnceLock::new();

fn native() -> Option<&'static Native> {
    NATIVE.get().and_then(|slot| slot.as_ref())
}

unsafe fn dlopen(name: &str) -> *mut c_void {
    let Ok(name) = CString::new(name) else {
        return ptr::null_mut();
    };
    libc_dlopen(name.as_ptr(), RTLD_NOW)
}

unsafe fn dlsym<T>(handle: *mut c_void, name: &str) -> Option<T> {
    let Ok(name) = CString::new(name) else {
        return None;
    };
    let symbol = libc_dlsym(handle, name.as_ptr());
    if symbol.is_null() {
        return None;
    }
    Some(std::mem::transmute_copy(&symbol))
}

const RTLD_NOW: c_int = 2;

extern "C" {
    #[link_name = "dlopen"]
    fn libc_dlopen(name: *const c_char, flags: c_int) -> *mut c_void;
    #[link_name = "dlsym"]
    fn libc_dlsym(handle: *mut c_void, name: *const c_char) -> *mut c_void;
    fn sysconf(name: c_int) -> i64;
    fn mprotect(addr: *mut c_void, len: usize, prot: c_int) -> c_int;
}

const SC_PAGESIZE: c_int = 29;
const PROT_READ: c_int = 1;
const PROT_WRITE: c_int = 2;
const PROT_EXEC: c_int = 4;

fn name_of(name: *const c_char, length: usize) -> &'static str {
    if name.is_null() {
        return "";
    }
    // lsplant hands over a `string_view`, which is not NUL-terminated: the length is authoritative
    let bytes = unsafe { std::slice::from_raw_parts(name as *const u8, length) };
    std::str::from_utf8(bytes).unwrap_or("")
}

extern "C" fn resolve_exact(name: *const c_char, length: usize) -> *mut c_void {
    let Some(native) = native() else {
        return ptr::null_mut();
    };
    let name = name_of(name, length);
    if name.is_empty() {
        return ptr::null_mut();
    }
    if native.art_handle != 0 {
        if let Ok(owned) = CString::new(name) {
            let found = unsafe { (native.shadowhook.dlsym)(native.art_handle as *mut c_void, owned.as_ptr()) };
            if !found.is_null() {
                return found;
            }
        }
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
    let prefix = name_of(prefix, length);
    if prefix.is_empty() {
        return ptr::null_mut();
    }
    match native.art.lock() {
        Ok(mut art) => art.prefix(prefix),
        Err(_) => ptr::null_mut(),
    }
}

/// Makes the page holding `address` writable, and the next one too when the write would straddle
/// the boundary.
///
/// The old protection is deliberately not restored: the region is ART's own text, what it carried
/// is not recorded anywhere cheap to read back, and shadowhook keeps its trampolines live for the
/// life of the process anyway.
fn unprotect(address: *mut c_void) -> bool {
    let page = unsafe { sysconf(SC_PAGESIZE) };
    if page <= 0 {
        return false;
    }
    let page = page as usize;
    let value = address as usize;
    let start = value & !(page - 1);
    let end = (value + page + page - 1) & !(page - 1);
    unsafe { mprotect(start as *mut c_void, end - start, PROT_READ | PROT_WRITE | PROT_EXEC) == 0 }
}

extern "C" fn inline_hooker(target: *mut c_void, hooker: *mut c_void) -> *mut c_void {
    let Some(native) = native() else {
        return ptr::null_mut();
    };
    if !unprotect(target) {
        return ptr::null_mut();
    }
    let mut original: *mut c_void = ptr::null_mut();
    let stub = unsafe { (native.shadowhook.hook_addr)(target, hooker, &mut original) };
    if stub.is_null() {
        return ptr::null_mut();
    }
    original
}

extern "C" fn inline_unhooker(func: *mut c_void) -> bool {
    let Some(native) = native() else { return false };
    unsafe { (native.shadowhook.unhook)(func) == 0 }
}

/// shadowhook's own `SHADOWHOOK_MODE_UNIQUE`: one hook per target, which is what lsplant installs,
/// and hooking an already-hooked target reports an error instead of stacking silently. The value is
/// 1 in every shadowhook release (0 is `SHADOWHOOK_MODE_SHARED`); it is not derived from a header,
/// since the library is dlopened rather than linked.
const SHADOWHOOK_MODE_UNIQUE: c_int = 1;

fn load() -> Option<Native> {
    let shadowhook_lib = unsafe { dlopen("libshadowhook.so") };
    if shadowhook_lib.is_null() {
        return None;
    }
    let init: ShadowhookInit = unsafe { dlsym(shadowhook_lib, "shadowhook_init")? };
    // false: shadowhook's own debug logging, which is not ours to turn on
    if unsafe { init(SHADOWHOOK_MODE_UNIQUE, false) } != 0 {
        return None;
    }
    let shadowhook = Shadowhook {
        dlopen: unsafe { dlsym(shadowhook_lib, "shadowhook_dlopen")? },
        dlsym: unsafe { dlsym(shadowhook_lib, "shadowhook_dlsym")? },
        hook_addr: unsafe { dlsym(shadowhook_lib, "shadowhook_hook_func_addr")? },
        unhook: unsafe { dlsym(shadowhook_lib, "shadowhook_unhook")? },
    };

    let lsplant_lib = unsafe { dlopen("liblsplant.so") };
    if lsplant_lib.is_null() {
        return None;
    }
    let lsplant = LSPlant {
        init: unsafe { dlsym(lsplant_lib, "LSPlantInitC")? },
        hook: unsafe { dlsym(lsplant_lib, "LSPlantHookC")? },
        unhook: unsafe { dlsym(lsplant_lib, "LSPlantUnHookC")? },
        is_hooked: unsafe { dlsym(lsplant_lib, "LSPlantIsHookedC")? },
        deoptimize: unsafe { dlsym(lsplant_lib, "LSPlantDeoptimizeC")? },
        make_inheritable: unsafe { dlsym(lsplant_lib, "LSPlantMakeClassInheritableC")? },
    };

    let art_name = CString::new("libart.so").ok()?;
    let art_handle = unsafe { (shadowhook.dlopen)(art_name.as_ptr()) } as usize;

    Some(Native {
        shadowhook,
        lsplant,
        // not fatal on its own: shadowhook may answer every exact lookup, and a prefix lookup that
        // finds nothing fails lsplant's own init with a clearer message than one from here
        art: Mutex::new(Resolver::open("libart.so")),
        art_handle,
    })
}

/// Loads both libraries and runs `lsplant::Init`. Idempotent; false means hooking is unavailable on
/// this device and every other entry point here will refuse.
///
/// `env` must have no hidden-api restrictions, which is what makes this the host's call to make
/// rather than something done lazily on the first hook.
pub fn init(env: &mut Env) -> bool {
    // published before `Init` runs, and deliberately not inside the closure: `Init` calls the two
    // resolvers, which read `NATIVE`, and a `OnceLock` has not published anything while its
    // initializer is still running
    let loaded = NATIVE.get_or_init(load);
    let Some(native) = loaded.as_ref() else {
        return false;
    };

    *INITIALIZED.get_or_init(|| {
        let info = LSPlantInitInfoC {
            inline_hooker,
            inline_unhooker,
            art_symbol_resolver: resolve_exact,
            art_symbol_prefix_resolver: resolve_prefix,
            // null keeps lsplant's own default for all four generated names
            generated_class_name: ptr::null(),
            generated_source_name: ptr::null(),
            generated_field_name: ptr::null(),
            generated_method_name: ptr::null(),
        };
        unsafe { (native.lsplant.init)(env.get_raw(), &info) }
    })
}

#[allow(dead_code)] // the only caller is `PluginXposed`, through the JNI export rather than this crate
pub fn is_available() -> bool {
    matches!(INITIALIZED.get(), Some(true))
}

/// Installs a hook, answering the backup method to invoke the original through, or null.
///
/// # Safety
/// `target` and `callback` must be a `java.lang.reflect.Method`/`Constructor`, and `hooker` the
/// object `callback` is declared on. lsplant's own contract, which nothing here can check.
pub unsafe fn hook(env: &mut Env, target: jobject, hooker: jobject, callback: jobject) -> jobject {
    let Some(native) = native() else {
        return ptr::null_mut();
    };
    (native.lsplant.hook)(env.get_raw(), target, hooker, callback)
}

/// # Safety
/// `target` must be a `java.lang.reflect.Method`/`Constructor` previously hooked.
pub unsafe fn unhook(env: &mut Env, target: jobject) -> bool {
    let Some(native) = native() else { return false };
    (native.lsplant.unhook)(env.get_raw(), target)
}

/// # Safety
/// `target` must be a `java.lang.reflect.Method`/`Constructor`.
pub unsafe fn is_hooked(env: &mut Env, target: jobject) -> bool {
    let Some(native) = native() else { return false };
    (native.lsplant.is_hooked)(env.get_raw(), target)
}

/// Stops callers of `method` inlining it, so a hook on a short callee actually fires.
///
/// # Safety
/// `method` must be a `java.lang.reflect.Method`/`Constructor`.
pub unsafe fn deoptimize(env: &mut Env, method: jobject) -> bool {
    let Some(native) = native() else { return false };
    (native.lsplant.deoptimize)(env.get_raw(), method)
}

/// Clears `final` on a class so it can be subclassed.
///
/// # Safety
/// `target` must be a `java.lang.Class`.
pub unsafe fn make_inheritable(env: &mut Env, target: jclass) -> bool {
    let Some(native) = native() else { return false };
    (native.lsplant.make_inheritable)(env.get_raw(), target)
}

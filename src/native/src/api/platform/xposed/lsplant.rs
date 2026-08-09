use std::ffi::{c_char, c_int, c_void, CString};
use std::ptr;
use std::sync::{Mutex, OnceLock};

use jni::sys::{jclass, jobject, jobjectArray, JNIEnv as RawJNIEnv};
use jni::strings::JNIString;
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
type LSPlantOnClass = unsafe extern "C" fn(*mut RawJNIEnv, jclass) -> bool;
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
    deoptimize: LSPlantOnObject,
    make_inheritable: LSPlantOnClass,
}

struct Native {
    shadowhook: Shadowhook,
    lsplant: LSPlant,
    art: Mutex<Resolver>,
    art_handle: usize,
}

unsafe impl Send for Native {}
unsafe impl Sync for Native {}

static NATIVE: OnceLock<Option<Native>> = OnceLock::new();

static INITIALIZED: OnceLock<bool> = OnceLock::new();
static PROFILE_SAVER_DISABLED: OnceLock<bool> = OnceLock::new();

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
}

#[cfg(target_os = "android")]
#[link(name = "log")]
extern "C" {
    fn __android_log_write(priority: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

const ANDROID_LOG_ERROR: c_int = 6;

fn log_init_failure(message: &str) {
    #[cfg(target_os = "android")]
    {
        let Ok(tag) = CString::new("InuPluginXposed") else { return };
        let Ok(message) = CString::new(message) else { return };
        unsafe { __android_log_write(ANDROID_LOG_ERROR, tag.as_ptr(), message.as_ptr()) };
    }
}

fn name_of(name: *const c_char, length: usize) -> &'static str {
    if name.is_null() {
        return "";
    }
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

extern "C" fn inline_hooker(target: *mut c_void, hooker: *mut c_void) -> *mut c_void {
    let Some(native) = native() else {
        return ptr::null_mut();
    };
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

const SHADOWHOOK_MODE_UNIQUE: c_int = 1;
const SET_HIDDEN_API_EXEMPTIONS: &str = "_ZN3artL32VMRuntime_setHiddenApiExemptionsEP7_JNIEnvP7_jclassP13_jobjectArray";

fn disable_hidden_api(env: &mut Env, native: &Native) -> bool {
    let mut address = ptr::null_mut();
    if native.art_handle != 0 {
        if let Ok(name) = CString::new(SET_HIDDEN_API_EXEMPTIONS) {
            address = unsafe { (native.shadowhook.dlsym)(native.art_handle as *mut c_void, name.as_ptr()) };
        }
    }
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

    let set_exemptions: SetHiddenApiExemptions = unsafe { std::mem::transmute(address) };
    unsafe { set_exemptions(env.get_raw(), string_class.as_raw(), exemptions.as_raw()) };
    if clear_exception(env) {
        log_init_failure("VMRuntime_setHiddenApiExemptions threw");
        return false;
    }
    true
}

fn load() -> Option<Native> {
    let shadowhook_lib = unsafe { dlopen("libshadowhook.so") };
    if shadowhook_lib.is_null() {
        return None;
    }
    let init: ShadowhookInit = unsafe { dlsym(shadowhook_lib, "shadowhook_init")? };
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

    Some(Native { shadowhook, lsplant, art: Mutex::new(Resolver::open("libart.so")), art_handle })
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
        let initialized = unsafe { (native.lsplant.init)(env.get_raw(), &info) };
        if !initialized {
            log_init_failure("LSPlantInitC failed");
        }
        initialized
    })
}

pub unsafe fn hook(env: &mut Env, target: jobject, hooker: jobject, callback: jobject) -> jobject {
    let Some(native) = native() else {
        return ptr::null_mut();
    };
    (native.lsplant.hook)(env.get_raw(), target, hooker, callback)
}

pub unsafe fn unhook(env: &mut Env, target: jobject) -> bool {
    let Some(native) = native() else { return false };
    (native.lsplant.unhook)(env.get_raw(), target)
}

pub unsafe fn is_hooked(env: &mut Env, target: jobject) -> bool {
    let Some(native) = native() else { return false };
    (native.lsplant.is_hooked)(env.get_raw(), target)
}

pub unsafe fn deoptimize(env: &mut Env, method: jobject) -> bool {
    let Some(native) = native() else { return false };
    (native.lsplant.deoptimize)(env.get_raw(), method)
}

pub unsafe fn make_inheritable(env: &mut Env, target: jclass) -> bool {
    let Some(native) = native() else { return false };
    (native.lsplant.make_inheritable)(env.get_raw(), target)
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

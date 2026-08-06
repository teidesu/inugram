//! Getting at an `Env` inside an export, and the string marshalling every one of them does.

use jni::objects::{JObjectArray, JString};
use jni::refs::IntoAuto;
use jni::{Env, EnvUnowned, JavaVM};

/// clears (and logcat-dumps, via ExceptionDescribe) any pending Java exception; true if one was
/// pending. MUST run after every JNI upcall - most JNI calls are UB while an exception is pending,
/// and an uncleared one would fire at an arbitrary later JNI call instead of its source.
pub(crate) fn clear_exception(env: &mut Env) -> bool {
    if env.exception_check() {
        env.exception_describe();
        env.exception_clear();
        true
    } else {
        false
    }
}

/// An `Env` for the current thread, for the length of one upcall.
///
/// Every caller is already attached - each is reached from inside a JNI upcall - so this is the
/// cheap path, and a thread that is somehow not attached answers `None` and the upcall fails
/// closed rather than crashing. `attach_current_thread` pushes a JNI stack frame of its own, which
/// is what bounds the local references the marshalling below allocates.
pub(crate) fn with_current_env<T>(f: impl FnOnce(&mut Env) -> T) -> Option<T> {
    let vm = JavaVM::singleton().ok()?;
    vm.attach_current_thread(|env| Ok::<T, jni::errors::Error>(f(env))).ok()
}

/// Runs one exported native method's body against a real `Env`.
///
/// jni 0.22 hands a native method an `EnvUnowned`, which carries no api of its own: the `Env` only
/// exists inside `with_env`. Nothing here throws back into java - an export is always reached from a
/// `globalQueue` runnable, where a pending exception would surface on whichever unrelated app work
/// ran next - so a failure answers `fallback`, which every caller reads as "the engine could not
/// answer". The closure's own `Err` arm is uninhabited for the same reason; `Outcome` is matched
/// rather than `resolve`d because a raw `jstring`/`jobject` return has no `Default`.
pub(crate) fn in_env<'local, T>(env: &mut EnvUnowned<'local>, fallback: T, f: impl FnOnce(&mut Env<'local>) -> T) -> T {
    match env.with_env(|env| Ok::<T, jni::errors::Error>(f(env))).into_outcome() {
        jni::Outcome::Ok(value) => value,
        _ => fallback,
    }
}

pub(crate) fn jstring_to_string(env: &mut Env, s: &JString) -> String {
    s.try_to_string(env).unwrap_or_default()
}

pub(crate) fn read_string_array(env: &mut Env, array: &JObjectArray<JString>) -> Vec<String> {
    let n = array.len(env).unwrap_or(0);
    let mut out = Vec::with_capacity(n);
    for i in 0..n {
        let Ok(item) = array.get_element(env, i) else {
            continue;
        };
        let item = item.auto();
        out.push(jstring_to_string(env, &item));
    }
    out
}

pub(crate) fn read_header(
    env: &mut Env,
    keys: &JObjectArray<JString>,
    values: &JObjectArray<JString>,
) -> Vec<(String, String)> {
    let n = keys.len(env).unwrap_or(0);
    let mut out = Vec::with_capacity(n);
    for i in 0..n {
        let Ok(k) = keys.get_element(env, i) else {
            continue;
        };
        let Ok(v) = values.get_element(env, i) else {
            continue;
        };
        let k = k.auto();
        let v = v.auto();
        let key = jstring_to_string(env, &k);
        let value = jstring_to_string(env, &v);
        out.push((key, value));
    }
    out
}

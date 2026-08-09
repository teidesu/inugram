use jni::objects::{JObjectArray, JString};
use jni::refs::IntoAuto;
use jni::{Env, EnvUnowned, JavaVM};

pub(crate) fn clear_exception(env: &mut Env) -> bool {
    if env.exception_check() {
        env.exception_describe();
        env.exception_clear();
        true
    } else {
        false
    }
}

pub(crate) fn with_current_env<T>(f: impl FnOnce(&mut Env) -> T) -> Option<T> {
    let vm = JavaVM::singleton().ok()?;
    vm.attach_current_thread(|env| Ok::<T, jni::errors::Error>(f(env))).ok()
}

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

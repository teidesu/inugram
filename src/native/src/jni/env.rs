use jni::objects::{JObjectArray, JString};
use jni::refs::IntoAuto;
use jni::sys::jstring;
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
  match s.try_to_string(env) {
    Ok(value) => value,
    Err(_) => {
      clear_exception(env);
      String::new()
    }
  }
}

pub(crate) fn new_jstring_raw(env: &mut Env, text: impl AsRef<str>) -> jstring {
  env.new_string(text).map(|value| value.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn array_len_or_clear(env: &mut Env, array: &JObjectArray<JString>) -> Option<usize> {
  match array.len(env) {
    Ok(length) => Some(length),
    Err(_) => {
      clear_exception(env);
      None
    }
  }
}

pub(crate) fn read_string_array(env: &mut Env, array: &JObjectArray<JString>) -> Vec<String> {
  let Some(n) = array_len_or_clear(env, array) else {
    return Vec::new();
  };
  let mut out = Vec::with_capacity(n);
  for i in 0..n {
    let Ok(item) = array.get_element(env, i) else {
      clear_exception(env);
      out.push(String::new());
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
  read_string_array(env, keys).into_iter().zip(read_string_array(env, values)).collect()
}

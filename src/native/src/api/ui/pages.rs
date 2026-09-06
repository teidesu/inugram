use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use rquickjs::{Array, Ctx, Exception, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::api::error::PluginErrorCode;
use crate::api::telegram::rpc::{format_exception, pump_jobs, PendingSettle};
use crate::api::ui::icons::{opt_icon, Icon, RETAINED_VALUE_TAG};
use crate::sandbox::registry::{make_disposer, noop_disposer, Lifecycle, Registry, RequestIds};
use crate::utils::arguments::{field, opt_bool, opt_fn, opt_num, opt_str, req_bool, req_fn, req_num, req_str};

const MAX_SLIDER_LABELS: usize = 501;

fn slider_steps(min: f64, max: f64, step: f64) -> usize {
  (((max - min) / step).round() as usize).saturating_add(1)
}

fn check_slider_steps<'js>(ctx: &Ctx<'js>, min: f64, max: f64, step: f64) -> JsResult<()> {
  let steps = slider_steps(min, max, step);
  if steps > MAX_SLIDER_LABELS {
    return PluginErrorCode::InvalidArgument.throw(
      ctx,
      &format!("slider: a 'label' is evaluated per step, and {steps} steps is past the {MAX_SLIDER_LABELS} allowed"),
    );
  }
  Ok(())
}

pub(crate) const ELEMENT_TAG: &str = "__inuUi";
const PAGE_ID_KEY: &str = "__inuPageId";
const BOTTOM_BUTTON_KEY: &str = "b";

pub trait UiHost {
  fn ui_prompt(&self, request_id: i64, options_json: &str) -> Option<String>;
  fn ui_open_page(&self, page_id: i64) -> Option<String>;
  fn ui_open_fragment(&self, handle: i64) -> Option<String>;
  fn ui_open_screen(&self, options_json: &str) -> Option<String>;
  fn ui_register_settings(&self, page_id: i64);
  fn ui_unregister_settings(&self, page_id: i64);
  fn ui_invalidate(&self, page_id: i64);
  fn ui_open_menu(&self, menu_id: i64, page_id: i64, anchor_key: &str, items_json: &str) -> Option<String>;
}

struct CallbackEntry {
  func: Persistent<Function<'static>>,
  row: Rc<str>,
}

struct UiPageDef {
  title: String,
  transient: bool,
  items_fn: Persistent<Function<'static>>,
  on_close: Option<Persistent<Function<'static>>>,
  bottom_text: Option<String>,
  bottom_on_click: Option<Persistent<Function<'static>>>,
  callbacks: RefCell<HashMap<u32, CallbackEntry>>,
  retained_icon_values: RefCell<Vec<Persistent<Value<'static>>>>,
  next_slot: Cell<u32>,
}

impl UiPageDef {
  fn release(self, ctx: &Ctx<'_>) {
    let _ = self.items_fn.restore(ctx);
    if let Some(p) = self.on_close {
      let _ = p.restore(ctx);
    }
    if let Some(p) = self.bottom_on_click {
      let _ = p.restore(ctx);
    }
    for (_, entry) in self.callbacks.into_inner() {
      let _ = entry.func.restore(ctx);
    }
    for value in self.retained_icon_values.into_inner() {
      let _ = value.restore(ctx);
    }
  }
}

impl UiState {
  fn dispose_page(&self, ctx: &Ctx<'_>, page_id: i64) {
    let state = self;
    if let Some(def) = state.pages.borrow_mut().remove(&page_id) {
      def.release(ctx);
    }
    for registered in state.settings.remove_matching(|id| *id == page_id) {
      state.host.ui_unregister_settings(registered);
    }
  }
}

pub struct UiState {
  host: Rc<dyn UiHost>,
  lifecycle: Rc<Lifecycle>,
  pub(crate) log: crate::Log,
  jvm: Option<Rc<crate::api::platform::jvm::JvmState>>,
  next_id: RequestIds,
  pages: RefCell<HashMap<i64, UiPageDef>>,
  menus: RefCell<HashMap<i64, Vec<Persistent<Function<'static>>>>>,
  pending_prompts: RefCell<HashMap<i64, PendingSettle>>,
  settings: Registry<i64>,
}

fn set_opt<'js, T: rquickjs::IntoJs<'js>>(out: &Object<'js>, key: &str, value: Option<T>) -> JsResult<()> {
  if let Some(v) = value {
    out.set(key, v)?;
  }
  Ok(())
}

fn new_element<'js>(ctx: &Ctx<'js>, ty: &str) -> JsResult<Object<'js>> {
  let obj = Object::new(ctx.clone())?;
  obj.set(ELEMENT_TAG, ty)?;
  Ok(obj)
}

fn make_check<'js>(ctx: &Ctx<'js>, opts: Object<'js>) -> JsResult<Object<'js>> {
  let out = new_element(ctx, "check")?;
  set_opt(&out, "id", opt_str(ctx, &opts, "check", "id")?)?;
  out.set("text", req_str(ctx, &opts, "check", "text")?)?;
  set_opt(&out, "subtitle", opt_str(ctx, &opts, "check", "subtitle")?)?;
  out.set("checked", req_bool(ctx, &opts, "check", "checked")?)?;
  out.set("onChange", req_fn(ctx, &opts, "check", "onChange")?)?;
  set_opt(&out, "onSecondaryClick", opt_fn(ctx, &opts, "check", "onSecondaryClick")?)?;
  Ok(out)
}

fn set_icon<'js>(out: &Object<'js>, icon: Option<Icon<'js>>) -> JsResult<()> {
  let Some(icon) = icon else { return Ok(()) };
  out.set("icon", icon.spec)?;
  if let Some(value) = icon.retained_value {
    out.set(RETAINED_VALUE_TAG, value)?;
  }
  Ok(())
}

fn make_button<'js>(
  ctx: &Ctx<'js>,
  opts: Object<'js>,
  jvm: Option<&Rc<crate::api::platform::jvm::JvmState>>,
) -> JsResult<Object<'js>> {
  let out = new_element(ctx, "button")?;
  set_opt(&out, "id", opt_str(ctx, &opts, "button", "id")?)?;
  out.set("text", req_str(ctx, &opts, "button", "text")?)?;
  set_icon(&out, opt_icon(ctx, &opts, "button", jvm)?)?;
  set_opt(&out, "subtitle", opt_str(ctx, &opts, "button", "subtitle")?)?;
  set_opt(&out, "value", opt_str(ctx, &opts, "button", "value")?)?;
  out.set("danger", opt_bool(ctx, &opts, "button", "danger")?)?;
  out.set("onClick", req_fn(ctx, &opts, "button", "onClick")?)?;
  set_opt(&out, "onSecondaryClick", opt_fn(ctx, &opts, "button", "onSecondaryClick")?)?;
  Ok(out)
}

fn make_select<'js>(
  ctx: &Ctx<'js>,
  opts: Object<'js>,
  jvm: Option<&Rc<crate::api::platform::jvm::JvmState>>,
) -> JsResult<Object<'js>> {
  let out = new_element(ctx, "select")?;
  set_opt(&out, "id", opt_str(ctx, &opts, "select", "id")?)?;
  out.set("text", req_str(ctx, &opts, "select", "text")?)?;
  set_icon(&out, opt_icon(ctx, &opts, "select", jvm)?)?;

  let raw: Value = field(ctx, &opts, "select", "items")?;
  let arr = raw.as_array().ok_or_else(|| Exception::throw_type(ctx, "select: 'items' must be an array"))?;
  let arr = crate::utils::arguments::array_values(ctx, arr, "select: 'items'")?;
  if arr.is_empty() {
    return Err(Exception::throw_type(ctx, "select: 'items' must not be empty"));
  }
  let items = Array::new(ctx.clone())?;
  for (i, item) in arr.into_iter().enumerate() {
    let entry = Object::new(ctx.clone())?;
    if let Some(s) = item.as_string() {
      entry.set("text", s.to_string()?)?;
    } else if let Some(obj) = item.as_object() {
      entry.set("text", req_str(ctx, obj, "select item", "text")?)?;
      set_opt(&entry, "subtitle", opt_str(ctx, obj, "select item", "subtitle")?)?;
    } else {
      return Err(Exception::throw_type(ctx, "select: items must be strings or { text, subtitle? } objects"));
    }
    items.set(i, entry)?;
  }
  let len = items.len();
  out.set("items", items)?;

  let selected = req_num(ctx, &opts, "select", "selected")? as i64;
  if selected < 0 || selected >= len as i64 {
    return Err(Exception::throw_type(ctx, "select: 'selected' out of range"));
  }
  out.set("selected", selected as i32)?;
  out.set("dialog", opt_bool(ctx, &opts, "select", "dialog")?)?;
  out.set("onChange", req_fn(ctx, &opts, "select", "onChange")?)?;
  set_opt(&out, "onSecondaryClick", opt_fn(ctx, &opts, "select", "onSecondaryClick")?)?;
  Ok(out)
}

fn make_slider<'js>(ctx: &Ctx<'js>, opts: Object<'js>) -> JsResult<Object<'js>> {
  let out = new_element(ctx, "slider")?;
  set_opt(&out, "id", opt_str(ctx, &opts, "slider", "id")?)?;
  set_opt(&out, "text", opt_str(ctx, &opts, "slider", "text")?)?;
  let min = req_num(ctx, &opts, "slider", "min")?;
  let max = req_num(ctx, &opts, "slider", "max")?;
  let step = req_num(ctx, &opts, "slider", "step")?;
  if !min.is_finite() || !max.is_finite() {
    return Err(Exception::throw_type(ctx, "slider: 'min'/'max' must be finite"));
  }
  if !step.is_finite() || step <= 0.0 {
    return Err(Exception::throw_type(ctx, "slider: 'step' must be > 0"));
  }
  if max <= min {
    return Err(Exception::throw_type(ctx, "slider: 'max' must be > 'min'"));
  }
  out.set("min", min)?;
  out.set("max", max)?;
  out.set("step", step)?;
  out.set("value", req_num(ctx, &opts, "slider", "value")?)?;
  set_opt(&out, "default", opt_num(ctx, &opts, "slider", "default")?)?;
  let label = opt_fn(ctx, &opts, "slider", "label")?;
  if label.is_some() {
    check_slider_steps(ctx, min, max, step)?;
  }
  set_opt(&out, "label", label)?;
  out.set("onChange", req_fn(ctx, &opts, "slider", "onChange")?)?;
  Ok(out)
}

pub fn install_ui<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn UiHost>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  jvm: Option<Rc<crate::api::platform::jvm::JvmState>>,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<UiState>> {
  let state = Rc::new(UiState {
    host,
    lifecycle,
    log,
    jvm,
    next_id: RequestIds::default(),
    pages: RefCell::new(HashMap::new()),
    menus: RefCell::new(HashMap::new()),
    pending_prompts: RefCell::new(HashMap::new()),
    settings: Registry::default(),
  });

  let ui: Object = match globals.inu.get::<_, Object>("ui") {
    Ok(o) => o,
    Err(_) => {
      let o = Object::new(ctx.clone())?;
      globals.inu.set("ui", o.clone())?;
      o
    }
  };

  ui.set(
    "header",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, text: rquickjs::Coerced<String>| {
      let out = new_element(&ctx, "header")?;
      out.set("text", text.0)?;
      Ok::<_, rquickjs::Error>(out)
    })?,
  )?;
  ui.set("check", Function::new(ctx.clone(), |ctx: Ctx<'js>, opts: Object<'js>| make_check(&ctx, opts))?)?;
  {
    let jvm = state.jvm.clone();
    ui.set(
      "button",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, opts: Object<'js>| make_button(&ctx, opts, jvm.as_ref()))?,
    )?;
  }
  {
    let jvm = state.jvm.clone();
    ui.set(
      "select",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, opts: Object<'js>| make_select(&ctx, opts, jvm.as_ref()))?,
    )?;
  }
  ui.set("slider", Function::new(ctx.clone(), |ctx: Ctx<'js>, opts: Object<'js>| make_slider(&ctx, opts))?)?;
  ui.set(
    "separator",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, text: rquickjs::function::Opt<rquickjs::Coerced<String>>| {
      let out = new_element(&ctx, "separator")?;
      set_opt(&out, "text", text.0.map(|c| c.0))?;
      Ok::<_, rquickjs::Error>(out)
    })?,
  )?;

  let state2 = state.clone();
  ui.set(
    "settingsPage",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, opts: Object<'js>| state2.js_settings_page(&ctx, opts))?,
  )?;

  let state2 = state.clone();
  ui.set(
    "openPage",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, page: Value<'js>| {
      if let Some(handle) = state2.java_handle(&ctx, &page)? {
        if let Some(err) = state2.host.ui_open_fragment(handle) {
          return Err(Exception::throw_message(&ctx, &err));
        }
        return Ok(());
      }
      if let Some(page) = page.as_object() {
        if page.contains_key("type")? {
          let kind: String = page.get("type")?;
          if !matches!(kind.as_str(), "chat" | "profile" | "dialogs" | "settings") {
            return PluginErrorCode::InvalidArgument.throw(&ctx, "openPage: unsupported screen type");
          }
          let out = Object::new(ctx.clone())?;
          out.set("type", kind.clone())?;
          if page.contains_key("account")? {
            out.set("accountId", page.get::<_, i32>("account")?)?;
          }
          if matches!(kind.as_str(), "chat" | "profile") {
            let dialog_id: f64 = page.get("dialogId")?;
            if !dialog_id.is_finite()
              || dialog_id.fract() != 0.0
              || dialog_id == 0.0
              || dialog_id.abs() > 9_007_199_254_740_991.0
            {
              return PluginErrorCode::InvalidArgument
                .throw(&ctx, "openPage: 'dialogId' must be a non-zero safe integer");
            }
            out.set("dialogId", dialog_id)?;
          }
          if page.contains_key("topicId")? {
            if kind != "chat" {
              return PluginErrorCode::InvalidArgument.throw(&ctx, "openPage: 'topicId' is only valid for a chat");
            }
            let topic_id: f64 = page.get("topicId")?;
            if !topic_id.is_finite() || topic_id.fract() != 0.0 || topic_id < 0.0 || topic_id > i32::MAX as f64 {
              return PluginErrorCode::InvalidArgument
                .throw(&ctx, "openPage: 'topicId' must be a non-negative signed 32-bit integer");
            }
            out.set("topicId", topic_id as i32)?;
          }
          let json = ctx
            .json_stringify(out.into_value())?
            .ok_or_else(|| Exception::throw_message(&ctx, "openPage: could not serialize the screen"))?
            .to_string()?;
          if let Some(err) = state2.host.ui_open_screen(&json) {
            return Err(Exception::throw_message(&ctx, &err));
          }
          return Ok(());
        }
      }
      let page_id = state2.page_id_of(&ctx, &page, "openPage")?;
      if let Some(err) = state2.host.ui_open_page(page_id) {
        return Err(Exception::throw_message(&ctx, &err));
      }
      Ok(())
    })?,
  )?;

  let state2 = state.clone();
  ui.set(
    "prompt",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, opts: Object<'js>| state2.js_prompt(&ctx, opts))?,
  )?;

  let state2 = state.clone();
  let android: Object = match globals.inu.get::<_, Object>("android") {
    Ok(o) => o,
    Err(_) => {
      let o = Object::new(ctx.clone())?;
      globals.inu.set("android", o.clone())?;
      o
    }
  };

  android.set(
    "nativeView",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, view: Value<'js>| {
      let Some(handle) = state2.java_handle(&ctx, &view)? else {
        return Err(Exception::throw_type(&ctx, "nativeView: expected a java object from inu.jvm"));
      };
      let out = new_element(&ctx, "native")?;
      out.set("handle", handle)?;
      Ok(out)
    })?,
  )?;

  let state2 = state.clone();
  globals.inu.set(
    "registerSettings",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, page: Value<'js>| state2.js_register_settings(&ctx, page))?,
  )?;

  Ok(state)
}

impl UiState {
  fn js_register_settings<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, page: Value<'js>) -> JsResult<Function<'js>> {
    let state = self;
    if state.lifecycle.is_unloading() {
      return noop_disposer(ctx);
    }
    let page_id = state.page_id_of(ctx, &page, "registerSettings")?;
    if !state.settings.is_empty() {
      return Err(Exception::throw_message(
        ctx,
        "registerSettings: a settings page is already registered; dispose that registration first",
      ));
    }
    let token = state.settings.alloc();
    state.settings.insert(token, None, page_id);
    state.host.ui_register_settings(page_id);

    let state = state.clone();
    make_disposer(ctx, move |_| {
      if let Some(page_id) = state.settings.remove(token) {
        state.host.ui_unregister_settings(page_id);
      }
    })
  }

  fn java_handle<'js>(&self, ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<Option<i64>> {
    let Some(jvm) = self.jvm.as_ref() else {
      return Ok(None);
    };
    let id = jvm.handle_id(ctx, value)?;
    Ok(if id < 0 { None } else { Some(id) })
  }

  fn page_id_of<'js>(&self, ctx: &Ctx<'js>, page: &Value<'js>, what: &str) -> JsResult<i64> {
    let id = page
      .as_object()
      .and_then(|o| o.get::<_, Option<f64>>(PAGE_ID_KEY).ok().flatten())
      .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: expected a settings page")))? as i64;
    if !self.pages.borrow().contains_key(&id) {
      return PluginErrorCode::HandleExpired.throw(ctx, &format!("{what}: this page has been disposed"));
    }
    Ok(id)
  }

  fn js_settings_page<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, opts: Object<'js>) -> JsResult<Object<'js>> {
    let state = self;
    let title = req_str(ctx, &opts, "settingsPage", "title")?;
    let transient = opt_bool(ctx, &opts, "settingsPage", "transient")?;
    let items_fn = req_fn(ctx, &opts, "settingsPage", "items")?;
    let on_close = opt_fn(ctx, &opts, "settingsPage", "onClose")?;

    let bottom: Value = field(ctx, &opts, "settingsPage", "bottomButton")?;
    let (bottom_text, bottom_on_click) = if bottom.is_undefined() || bottom.is_null() {
      (None, None)
    } else {
      let obj = bottom
        .as_object()
        .ok_or_else(|| Exception::throw_type(ctx, "settingsPage: 'bottomButton' must be an object"))?;
      (Some(req_str(ctx, obj, "bottomButton", "text")?), Some(req_fn(ctx, obj, "bottomButton", "onClick")?))
    };

    let page_id = state.next_id.alloc();
    if !state.lifecycle.is_unloading() {
      state.pages.borrow_mut().insert(
        page_id,
        UiPageDef {
          title,
          transient,
          items_fn: Persistent::save(ctx, items_fn),
          on_close: on_close.map(|f| Persistent::save(ctx, f)),
          bottom_text,
          bottom_on_click: bottom_on_click.map(|f| Persistent::save(ctx, f)),
          callbacks: RefCell::new(HashMap::new()),
          retained_icon_values: RefCell::new(Vec::new()),
          next_slot: Cell::new(1),
        },
      );
    }

    let page = Object::new(ctx.clone())?;
    page.set(PAGE_ID_KEY, page_id as f64)?;
    let state2 = state.clone();
    page.set(
      "invalidate",
      Function::new(ctx.clone(), move || {
        state2.host.ui_invalidate(page_id);
      })?,
    )?;
    let state2 = state.clone();
    page.set(
      "dispose",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>| {
        state2.dispose_page(&ctx, page_id);
      })?,
    )?;
    Ok(page)
  }
}

fn alloc_row_key(counts: &mut HashMap<String, u32>, ty: &str, id: Option<&str>, text: Option<&str>) -> Rc<str> {
  let base = match id {
    Some(id) => format!("i:{id}"),
    None => format!("t:{ty}:{}", text.unwrap_or("")),
  };
  let occurrence = *counts.entry(base.clone()).and_modify(|c| *c += 1).or_insert(1);
  Rc::from(format!("{base}#{occurrence}").as_str())
}

impl UiState {
  fn try_render<'js>(&self, ctx: &Ctx<'js>, page_id: i64) -> JsResult<String> {
    let state = self;
    let (items_fn, title, bottom_text, bottom_on_click, mut next_slot) = {
      let pages = state.pages.borrow();
      let def = pages.get(&page_id).ok_or_else(|| Exception::throw_message(ctx, "render: unknown page"))?;
      (
        def.items_fn.clone().restore(ctx)?,
        def.title.clone(),
        def.bottom_text.clone(),
        def.bottom_on_click.as_ref().map(|p| p.clone().restore(ctx)).transpose()?,
        def.next_slot.get(),
      )
    };

    let items_val: Value = items_fn.call(())?;
    let items_arr = items_val
      .as_array()
      .ok_or_else(|| Exception::throw_type(ctx, "settingsPage: items() must return an array"))?;

    let mut new_cbs: Vec<(u32, Rc<str>, Function<'js>)> = Vec::new();
    let mut retained_icon_values: Vec<Value<'js>> = Vec::new();
    let mut alloc_slot = |row: &Rc<str>, f: Function<'js>| -> u32 {
      let slot = next_slot;
      next_slot += 1;
      new_cbs.push((slot, row.clone(), f));
      slot
    };

    let out_items = Array::new(ctx.clone())?;
    let mut key_counts: HashMap<String, u32> = HashMap::new();
    for (i, element) in items_arr.iter::<Value>().enumerate() {
      let element = element?;
      let obj = element
        .as_object()
        .filter(|o| o.get::<_, Option<String>>(ELEMENT_TAG).ok().flatten().is_some())
        .ok_or_else(|| Exception::throw_type(ctx, "settingsPage: items() must return only inu.ui elements"))?;
      let ty: String = obj.get(ELEMENT_TAG)?;
      let row = alloc_row_key(
        &mut key_counts,
        &ty,
        obj.get::<_, Option<String>>("id")?.as_deref(),
        obj.get::<_, Option<String>>("text")?.as_deref(),
      );

      let out = Object::new(ctx.clone())?;
      out.set("type", ty.as_str())?;
      out.set("key", row.as_ref())?;
      match ty.as_str() {
        "native" => {
          out.set("handle", obj.get::<_, i64>("handle")?)?;
        }
        "header" | "separator" => {
          set_opt(&out, "text", obj.get::<_, Option<String>>("text")?)?;
        }
        "check" => {
          set_opt(&out, "id", obj.get::<_, Option<String>>("id")?)?;
          out.set("text", obj.get::<_, String>("text")?)?;
          set_opt(&out, "subtitle", obj.get::<_, Option<String>>("subtitle")?)?;
          out.set("checked", obj.get::<_, bool>("checked")?)?;
          out.set("onChange", alloc_slot(&row, obj.get::<_, Function>("onChange")?))?;
          if let Some(f) = obj.get::<_, Option<Function>>("onSecondaryClick")? {
            out.set("onSecondaryClick", alloc_slot(&row, f))?;
          }
        }
        "button" => {
          set_opt(&out, "id", obj.get::<_, Option<String>>("id")?)?;
          out.set("text", obj.get::<_, String>("text")?)?;
          set_opt(&out, "icon", obj.get::<_, Option<String>>("icon")?)?;
          if let Some(value) = obj.get::<_, Option<Value>>(RETAINED_VALUE_TAG)? {
            retained_icon_values.push(value);
          }
          set_opt(&out, "subtitle", obj.get::<_, Option<String>>("subtitle")?)?;
          set_opt(&out, "value", obj.get::<_, Option<String>>("value")?)?;
          out.set("danger", obj.get::<_, bool>("danger")?)?;
          out.set("onClick", alloc_slot(&row, obj.get::<_, Function>("onClick")?))?;
          if let Some(f) = obj.get::<_, Option<Function>>("onSecondaryClick")? {
            out.set("onSecondaryClick", alloc_slot(&row, f))?;
          }
        }
        "select" => {
          set_opt(&out, "id", obj.get::<_, Option<String>>("id")?)?;
          out.set("text", obj.get::<_, String>("text")?)?;
          set_opt(&out, "icon", obj.get::<_, Option<String>>("icon")?)?;
          if let Some(value) = obj.get::<_, Option<Value>>(RETAINED_VALUE_TAG)? {
            retained_icon_values.push(value);
          }
          out.set("items", obj.get::<_, Array>("items")?)?;
          out.set("selected", obj.get::<_, i32>("selected")?)?;
          out.set("dialog", obj.get::<_, bool>("dialog")?)?;
          out.set("onChange", alloc_slot(&row, obj.get::<_, Function>("onChange")?))?;
          if let Some(f) = obj.get::<_, Option<Function>>("onSecondaryClick")? {
            out.set("onSecondaryClick", alloc_slot(&row, f))?;
          }
        }
        "slider" => {
          set_opt(&out, "id", obj.get::<_, Option<String>>("id")?)?;
          set_opt(&out, "text", obj.get::<_, Option<String>>("text")?)?;
          let min: f64 = obj.get("min")?;
          let max: f64 = obj.get("max")?;
          let step: f64 = obj.get("step")?;
          out.set("min", min)?;
          out.set("max", max)?;
          out.set("step", step)?;
          out.set("value", obj.get::<_, f64>("value")?)?;
          set_opt(&out, "default", obj.get::<_, Option<f64>>("default")?)?;
          if let Some(label) = obj.get::<_, Option<Function>>("label")? {
            check_slider_steps(ctx, min, max, step)?;
            let labels = Array::new(ctx.clone())?;
            for s in 0..slider_steps(min, max, step) {
              let v = (min + s as f64 * step).min(max);
              let text: rquickjs::Coerced<String> = label.call((v,))?;
              labels.set(s, text.0)?;
            }
            out.set("labels", labels)?;
          }
          out.set("onChange", alloc_slot(&row, obj.get::<_, Function>("onChange")?))?;
        }
        other => {
          return Err(Exception::throw_type(ctx, &format!("settingsPage: unknown element type '{other}'")));
        }
      }
      out_items.set(i, out)?;
    }

    let root = Object::new(ctx.clone())?;
    root.set("title", title)?;
    root.set("items", out_items)?;
    if let (Some(text), Some(f)) = (bottom_text, bottom_on_click) {
      let bottom = Object::new(ctx.clone())?;
      bottom.set("key", BOTTOM_BUTTON_KEY)?;
      bottom.set("text", text)?;
      bottom.set("onClick", alloc_slot(&Rc::from(BOTTOM_BUTTON_KEY), f))?;
      root.set("bottomButton", bottom)?;
    }

    {
      let pages = state.pages.borrow();
      if let Some(def) = pages.get(&page_id) {
        let mut cbs = def.callbacks.borrow_mut();
        for (_, entry) in cbs.drain() {
          let _ = entry.func.restore(ctx);
        }
        for (slot, row, f) in new_cbs {
          cbs.insert(slot, CallbackEntry { func: Persistent::save(ctx, f), row });
        }
        for value in def
          .retained_icon_values
          .replace(retained_icon_values.into_iter().map(|value| Persistent::save(ctx, value)).collect())
        {
          let _ = value.restore(ctx);
        }
        def.next_slot.set(next_slot);
      }
    }

    ctx
      .json_stringify(root)?
      .map(|s| s.to_string())
      .transpose()?
      .ok_or_else(|| Exception::throw_message(ctx, "render: serialization produced no output"))
  }

  fn make_anchor<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, page_id: i64, row: Rc<str>) -> JsResult<Object<'js>> {
    let anchor = Object::new(ctx.clone())?;
    let state = self.clone();
    anchor.set(
      "openMenu",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, items: Value<'js>| {
        state.js_open_menu(&ctx, page_id, &row, items)
      })?,
    )?;
    Ok(anchor)
  }

  fn js_open_menu<'js>(&self, ctx: &Ctx<'js>, page_id: i64, row: &str, items: Value<'js>) -> JsResult<()> {
    let state = self;
    if !state.pages.borrow().contains_key(&page_id) {
      return PluginErrorCode::HandleExpired.throw(ctx, "openMenu: the page this anchor came from has been disposed");
    }
    let arr = items.as_array().ok_or_else(|| Exception::throw_type(ctx, "openMenu: expected an array of items"))?;
    let arr = crate::utils::arguments::array_values(ctx, arr, "openMenu")?;
    if arr.is_empty() {
      return Err(Exception::throw_type(ctx, "openMenu: items must not be empty"));
    }

    let out = Array::new(ctx.clone())?;
    let mut callbacks: Vec<Function<'js>> = Vec::with_capacity(arr.len());
    for (i, item) in arr.into_iter().enumerate() {
      let obj = item.as_object().ok_or_else(|| Exception::throw_type(ctx, "openMenu: items must be objects"))?;
      let entry = Object::new(ctx.clone())?;
      entry.set("text", req_str(ctx, obj, "openMenu item", "text")?)?;
      let checked: Value = field(ctx, obj, "openMenu item", "checked")?;
      if !checked.is_undefined() && !checked.is_null() {
        let checked = checked
          .as_bool()
          .ok_or_else(|| Exception::throw_type(ctx, "openMenu item: 'checked' must be a boolean"))?;
        entry.set("checked", checked)?;
      }
      entry.set("danger", opt_bool(ctx, obj, "openMenu item", "danger")?)?;
      callbacks.push(req_fn(ctx, obj, "openMenu item", "onClick")?);
      out.set(i, entry)?;
    }
    let json = ctx
      .json_stringify(out)?
      .map(|s| s.to_string())
      .transpose()?
      .ok_or_else(|| Exception::throw_message(ctx, "openMenu: serialization failed"))?;

    let menu_id = state.next_id.alloc();
    if let Some(err) = state.host.ui_open_menu(menu_id, page_id, row, &json) {
      return Err(Exception::throw_message(ctx, &err));
    }
    state
      .menus
      .borrow_mut()
      .insert(menu_id, callbacks.into_iter().map(|f| Persistent::save(ctx, f)).collect());
    Ok(())
  }

  fn js_prompt<'js>(&self, ctx: &Ctx<'js>, opts: Object<'js>) -> JsResult<Value<'js>> {
    let state = self;
    let out = Object::new(ctx.clone())?;
    out.set("title", req_str(ctx, &opts, "prompt", "title")?)?;
    set_opt(&out, "hint", opt_str(ctx, &opts, "prompt", "hint")?)?;
    set_opt(&out, "value", opt_str(ctx, &opts, "prompt", "value")?)?;
    out.set("selectAll", opt_bool(ctx, &opts, "prompt", "selectAll")?)?;
    let json = ctx
      .json_stringify(out)?
      .map(|s| s.to_string())
      .transpose()?
      .ok_or_else(|| Exception::throw_message(ctx, "prompt: serialization failed"))?;

    let request_id = state.next_id.alloc();
    let (promise, pending) = PendingSettle::new(ctx)?;
    state.pending_prompts.borrow_mut().insert(request_id, pending);

    if let Some(err) = state.host.ui_prompt(request_id, &json) {
      if let Some(pending) = state.pending_prompts.borrow_mut().remove(&request_id) {
        pending.reject_with(ctx, &err)?;
      }
    }
    Ok(promise.into_value())
  }
}

impl UiState {
  pub fn render(self: &Rc<Self>, rt: &Runtime, context: &rquickjs::Context, page_id: i64) -> Option<String> {
    let state = self;
    if !state.pages.borrow().contains_key(&page_id) {
      (state.log)(&format!("ui: render({page_id}): no such page (already disposed?)"));
      return None;
    }
    let out = context.with(|ctx| match state.try_render(&ctx, page_id) {
      Ok(json) => Some(json),
      Err(rquickjs::Error::Exception) => {
        (state.log)(&crate::fault(format_args!("ui: render failed: {}", format_exception(&ctx))));
        None
      }
      Err(e) => {
        (state.log)(&format!("ui: render failed: {e:?}"));
        None
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
    out
  }

  pub fn dispatch_event(
    self: &Rc<Self>,
    rt: &Runtime,
    context: &rquickjs::Context,
    page_id: i64,
    slot: u32,
    arg_json: &str,
  ) {
    let state = self;
    if state.lifecycle.is_unloading() {
      return;
    }
    context.with(|ctx| {
      let found = {
        let pages = state.pages.borrow();
        pages
          .get(&page_id)
          .and_then(|def| def.callbacks.borrow().get(&slot).map(|entry| (entry.func.clone(), entry.row.clone())))
      };
      let Some((cb, row)) = found else { return };
      let f = match cb.restore(&ctx) {
        Ok(f) => f,
        Err(e) => {
          (state.log)(&format!("ui: failed to restore callback: {e:?}"));
          return;
        }
      };
      let anchor = match state.make_anchor(&ctx, page_id, row) {
        Ok(a) => a,
        Err(e) => {
          (state.log)(&format!("ui: failed to build the anchor: {e:?}"));
          return;
        }
      };
      let result = if arg_json.is_empty() {
        f.call::<_, Value>((anchor,))
      } else {
        match ctx.json_parse(arg_json) {
          Ok(arg) => f.call::<_, Value>((arg, anchor)),
          Err(e) => {
            (state.log)(&format!("ui: bad event arg: {e:?}"));
            return;
          }
        }
      };
      match result {
        Ok(_) => {}
        Err(rquickjs::Error::Exception) => {
          (state.log)(&crate::fault(format_args!("ui callback threw: {}", format_exception(&ctx))));
        }
        Err(e) => (state.log)(&format!("ui callback failed: {e:?}")),
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  pub fn dispatch_menu_click(self: &Rc<Self>, rt: &Runtime, context: &rquickjs::Context, menu_id: i64, slot: i32) {
    let state = self;
    context.with(|ctx| {
      let Some(callbacks) = state.menus.borrow_mut().remove(&menu_id) else {
        (state.log)(&format!("menuClick({menu_id}, {slot}): no such menu (already settled?)"));
        return;
      };
      for (i, persistent) in callbacks.into_iter().enumerate() {
        let f = match persistent.restore(&ctx) {
          Ok(f) => f,
          Err(_) => continue,
        };
        if i as i32 == slot {
          match f.call::<_, Value>(()) {
            Ok(_) => {}
            Err(rquickjs::Error::Exception) => {
              (state.log)(&crate::fault(format_args!("menu item callback threw: {}", format_exception(&ctx))));
            }
            Err(e) => (state.log)(&format!("menu item callback failed: {e:?}")),
          }
        }
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  pub fn resolve_prompt(
    self: &Rc<Self>,
    rt: &Runtime,
    context: &rquickjs::Context,
    request_id: i64,
    text: Option<&str>,
  ) {
    let state = self;
    context.with(|ctx| {
      use rquickjs::IntoJs;
      if let Some(pending) = state.pending_prompts.borrow_mut().remove(&request_id) {
        let value = match text {
          Some(t) => t.into_js(&ctx),
          None => Ok(Value::new_null(ctx.clone())),
        };
        match value {
          Ok(v) => {
            if pending.resolve_with(&ctx, v).is_err() {
              (state.log)(&format!("prompt({request_id}) resolve failed: {}", format_exception(&ctx)));
            }
          }
          Err(e) => {
            pending.release(&ctx);
            (state.log)(&format!("prompt({request_id}) text conversion failed: {e:?}"));
          }
        }
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  pub fn close_page(self: &Rc<Self>, rt: &Runtime, context: &rquickjs::Context, page_id: i64) {
    let state = self;
    context.with(|ctx| {
      let (on_close, transient) = {
        let pages = state.pages.borrow();
        let Some(def) = pages.get(&page_id) else {
          return;
        };
        for (_, entry) in def.callbacks.borrow_mut().drain() {
          let _ = entry.func.restore(&ctx);
        }
        (def.on_close.as_ref().cloned(), def.transient)
      };
      if let Some(persistent) = on_close {
        match persistent.restore(&ctx) {
          Ok(f) => match f.call::<_, Value>(()) {
            Ok(_) => {}
            Err(rquickjs::Error::Exception) => {
              (state.log)(&crate::fault(format_args!("onClose callback threw: {}", format_exception(&ctx))));
            }
            Err(e) => (state.log)(&format!("onClose callback failed: {e:?}")),
          },
          Err(e) => (state.log)(&format!("onClose: failed to restore callback: {e:?}")),
        }
      }
      if transient {
        state.dispose_page(&ctx, page_id);
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  pub fn dispose(self: &Rc<Self>, context: &rquickjs::Context) {
    let state = self;
    context.with(|ctx| {
      for (_, def) in state.pages.borrow_mut().drain() {
        def.release(&ctx);
      }
      for (_, callbacks) in state.menus.borrow_mut().drain() {
        for p in callbacks {
          let _ = p.restore(&ctx);
        }
      }
      for (_, pending) in state.pending_prompts.borrow_mut().drain() {
        pending.release(&ctx);
      }
    });
  }
}

#[cfg(test)]
#[path = "pages_tests.rs"]
mod tests;

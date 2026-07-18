//! Pure JS-plumbing for the declarative settings-page api: `inu.ui.settingsPage`/`openPage`/
//! `openMenu`/`prompt` + the element factories (`header`/`check`/`button`/`select`/`slider`/
//! `separator`) and `inu.registerSettings`.
//!
//! Same design as [`crate::rpc`]/[`crate::api`]: JNI-free behind [`UiHost`], exercised directly
//! with rquickjs in cargo tests.
//!
//! Rendering is declarative: the host asks for a render ([`render_page`]) whenever the page
//! opens or needs refreshing; the page's `items()` runs and the element tree serializes into one
//! JSON string. Element callbacks become integer slots into a per-page table (slots grow
//! monotonically across renders so a stale event from a previous render is a safe no-op, never a
//! misdirected call); the host fires them back via [`dispatch_ui_event`] and re-renders after.
//! `openMenu` is only valid synchronously inside such an event dispatch — that's what lets the
//! host anchor the menu to the originating row.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use rquickjs::{Array, Ctx, Exception, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::api::{json_parse, json_stringify};
use crate::rpc::{format_exception, get_or_create_inu, pump_jobs, PendingSettle};

/// most steps a slider `label` callback will be evaluated for at render time; sliders with more
/// steps fall back to raw numeric labels
const MAX_SLIDER_LABELS: usize = 501;

const ELEMENT_TAG: &str = "__inuUi";
const PAGE_ID_KEY: &str = "__inuPageId";

/// stand-in for the ui half of the Kotlin `QuickJs.ApiListener`; `Some(msg)` == error
pub trait UiHost {
    fn ui_prompt(&self, request_id: i64, options_json: &str) -> Option<String>;
    fn ui_open_page(&self, page_id: i64) -> Option<String>;
    fn ui_register_settings(&self, page_id: i64);
    fn ui_invalidate(&self, page_id: i64);
    fn ui_open_menu(&self, menu_id: i64, items_json: &str) -> Option<String>;
}

struct UiPageDef {
    title: String,
    transient: bool,
    items_fn: Persistent<Function<'static>>,
    on_close: Option<Persistent<Function<'static>>>,
    bottom_text: Option<String>,
    bottom_on_click: Option<Persistent<Function<'static>>>,
    callbacks: RefCell<HashMap<u32, Persistent<Function<'static>>>>,
    next_slot: Cell<u32>,
}

fn release_page_def(ctx: &Ctx<'_>, def: UiPageDef) {
    let _ = def.items_fn.restore(ctx);
    if let Some(p) = def.on_close {
        let _ = p.restore(ctx);
    }
    if let Some(p) = def.bottom_on_click {
        let _ = p.restore(ctx);
    }
    for (_, p) in def.callbacks.into_inner() {
        let _ = p.restore(ctx);
    }
}

/// removes the page and releases every root it holds; missing page == already-disposed no-op
fn dispose_page(ctx: &Ctx<'_>, state: &UiState, page_id: i64) {
    if let Some(def) = state.pages.borrow_mut().remove(&page_id) {
        release_page_def(ctx, def);
    }
}

pub struct UiState {
    host: Rc<dyn UiHost>,
    pub(crate) log: Rc<dyn Fn(&str)>,
    next_id: Cell<i64>,
    pages: RefCell<HashMap<i64, UiPageDef>>,
    menus: RefCell<HashMap<i64, Vec<Persistent<Function<'static>>>>>,
    pending_prompts: RefCell<HashMap<i64, PendingSettle>>,
    in_event: Cell<bool>,
}

impl UiState {
    fn alloc_id(&self) -> i64 {
        let id = self.next_id.get();
        self.next_id.set(id + 1);
        id
    }
}

// -- option-object field readers (strict: wrong types throw at element creation, not at render) --

fn field<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Value<'js>> {
    obj.get(key)
        .map_err(|_| Exception::throw_type(ctx, &format!("{what}: cannot read '{key}'")))
}

fn req_str<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<String> {
    let v = field(ctx, obj, what, key)?;
    match v.as_string() {
        Some(s) => Ok(s.to_string()?),
        None => Err(Exception::throw_type(ctx, &format!("{what}: '{key}' must be a string"))),
    }
}

fn opt_str<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Option<String>> {
    let v = field(ctx, obj, what, key)?;
    if v.is_undefined() || v.is_null() {
        return Ok(None);
    }
    match v.as_string() {
        Some(s) => Ok(Some(s.to_string()?)),
        None => Err(Exception::throw_type(ctx, &format!("{what}: '{key}' must be a string"))),
    }
}

fn req_bool<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<bool> {
    let v = field(ctx, obj, what, key)?;
    v.as_bool()
        .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a boolean")))
}

fn opt_bool<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<bool> {
    let v = field(ctx, obj, what, key)?;
    if v.is_undefined() || v.is_null() {
        return Ok(false);
    }
    v.as_bool()
        .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a boolean")))
}

fn req_num<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<f64> {
    let v = field(ctx, obj, what, key)?;
    if let Some(i) = v.as_int() {
        return Ok(i as f64);
    }
    v.as_float()
        .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a number")))
}

fn opt_num<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Option<f64>> {
    let v = field(ctx, obj, what, key)?;
    if v.is_undefined() || v.is_null() {
        return Ok(None);
    }
    if let Some(i) = v.as_int() {
        return Ok(Some(i as f64));
    }
    v.as_float()
        .map(Some)
        .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a number")))
}

fn req_fn<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Function<'js>> {
    let v = field(ctx, obj, what, key)?;
    v.into_function()
        .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a function")))
}

fn opt_fn<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Option<Function<'js>>> {
    let v = field(ctx, obj, what, key)?;
    if v.is_undefined() || v.is_null() {
        return Ok(None);
    }
    v.into_function()
        .map(Some)
        .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a function")))
}

fn set_opt<'js, T: rquickjs::IntoJs<'js>>(out: &Object<'js>, key: &str, value: Option<T>) -> JsResult<()> {
    if let Some(v) = value {
        out.set(key, v)?;
    }
    Ok(())
}

// -- element factories: validate options eagerly, snapshot into a fresh tagged object --

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

fn make_button<'js>(ctx: &Ctx<'js>, opts: Object<'js>) -> JsResult<Object<'js>> {
    let out = new_element(ctx, "button")?;
    set_opt(&out, "id", opt_str(ctx, &opts, "button", "id")?)?;
    out.set("text", req_str(ctx, &opts, "button", "text")?)?;
    set_opt(&out, "subtitle", opt_str(ctx, &opts, "button", "subtitle")?)?;
    set_opt(&out, "value", opt_str(ctx, &opts, "button", "value")?)?;
    out.set("danger", opt_bool(ctx, &opts, "button", "danger")?)?;
    out.set("onClick", req_fn(ctx, &opts, "button", "onClick")?)?;
    set_opt(&out, "onSecondaryClick", opt_fn(ctx, &opts, "button", "onSecondaryClick")?)?;
    Ok(out)
}

fn make_select<'js>(ctx: &Ctx<'js>, opts: Object<'js>) -> JsResult<Object<'js>> {
    let out = new_element(ctx, "select")?;
    set_opt(&out, "id", opt_str(ctx, &opts, "select", "id")?)?;
    out.set("text", req_str(ctx, &opts, "select", "text")?)?;

    let raw: Value = field(ctx, &opts, "select", "items")?;
    let arr = raw
        .as_array()
        .ok_or_else(|| Exception::throw_type(ctx, "select: 'items' must be an array"))?;
    if arr.is_empty() {
        return Err(Exception::throw_type(ctx, "select: 'items' must not be empty"));
    }
    let items = Array::new(ctx.clone())?;
    for (i, item) in arr.iter::<Value>().enumerate() {
        let item = item?;
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
    set_opt(&out, "label", opt_fn(ctx, &opts, "slider", "label")?)?;
    out.set("onChange", req_fn(ctx, &opts, "slider", "onChange")?)?;
    Ok(out)
}

// -- install --

pub fn install_ui<'js>(ctx: &Ctx<'js>, host: Rc<dyn UiHost>, log: Rc<dyn Fn(&str)>) -> JsResult<Rc<UiState>> {
    let state = Rc::new(UiState {
        host,
        log,
        next_id: Cell::new(1),
        pages: RefCell::new(HashMap::new()),
        menus: RefCell::new(HashMap::new()),
        pending_prompts: RefCell::new(HashMap::new()),
        in_event: Cell::new(false),
    });

    let inu = get_or_create_inu(ctx)?;
    let ui: Object = match inu.get::<_, Object>("ui") {
        Ok(o) => o,
        Err(_) => {
            let o = Object::new(ctx.clone())?;
            inu.set("ui", o.clone())?;
            o
        }
    };

    ui.set("header", Function::new(ctx.clone(), |ctx: Ctx<'js>, text: rquickjs::Coerced<String>| {
        let out = new_element(&ctx, "header")?;
        out.set("text", text.0)?;
        Ok::<_, rquickjs::Error>(out)
    })?)?;
    ui.set("check", Function::new(ctx.clone(), |ctx: Ctx<'js>, opts: Object<'js>| make_check(&ctx, opts))?)?;
    ui.set("button", Function::new(ctx.clone(), |ctx: Ctx<'js>, opts: Object<'js>| make_button(&ctx, opts))?)?;
    ui.set("select", Function::new(ctx.clone(), |ctx: Ctx<'js>, opts: Object<'js>| make_select(&ctx, opts))?)?;
    ui.set("slider", Function::new(ctx.clone(), |ctx: Ctx<'js>, opts: Object<'js>| make_slider(&ctx, opts))?)?;
    ui.set("separator", Function::new(ctx.clone(), |ctx: Ctx<'js>, text: rquickjs::function::Opt<rquickjs::Coerced<String>>| {
        let out = new_element(&ctx, "separator")?;
        set_opt(&out, "text", text.0.map(|c| c.0))?;
        Ok::<_, rquickjs::Error>(out)
    })?)?;

    {
        let state2 = state.clone();
        ui.set("settingsPage", Function::new(ctx.clone(), move |ctx: Ctx<'js>, opts: Object<'js>| {
            js_settings_page(&ctx, &state2, opts)
        })?)?;
    }
    {
        let state2 = state.clone();
        ui.set("openPage", Function::new(ctx.clone(), move |ctx: Ctx<'js>, page: Value<'js>| {
            let page_id = page_id_of(&ctx, &state2, &page, "openPage")?;
            if let Some(err) = state2.host.ui_open_page(page_id) {
                return Err(Exception::throw_message(&ctx, &err));
            }
            Ok(())
        })?)?;
    }
    {
        let state2 = state.clone();
        ui.set("openMenu", Function::new(ctx.clone(), move |ctx: Ctx<'js>, items: Value<'js>| {
            js_open_menu(&ctx, &state2, items)
        })?)?;
    }
    {
        let state2 = state.clone();
        ui.set("prompt", Function::new(ctx.clone(), move |ctx: Ctx<'js>, opts: Object<'js>| {
            js_prompt(&ctx, &state2, opts)
        })?)?;
    }
    {
        let state2 = state.clone();
        inu.set("registerSettings", Function::new(ctx.clone(), move |ctx: Ctx<'js>, page: Value<'js>| {
            let page_id = page_id_of(&ctx, &state2, &page, "registerSettings")?;
            state2.host.ui_register_settings(page_id);
            Ok::<_, rquickjs::Error>(())
        })?)?;
    }

    Ok(state)
}

fn page_id_of<'js>(ctx: &Ctx<'js>, state: &Rc<UiState>, page: &Value<'js>, what: &str) -> JsResult<i64> {
    let id = page
        .as_object()
        .and_then(|o| o.get::<_, Option<f64>>(PAGE_ID_KEY).ok().flatten())
        .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: expected a settings page")))? as i64;
    if !state.pages.borrow().contains_key(&id) {
        return Err(Exception::throw_type(ctx, &format!("{what}: unknown page")));
    }
    Ok(id)
}

// -- inu.ui.settingsPage --

fn js_settings_page<'js>(ctx: &Ctx<'js>, state: &Rc<UiState>, opts: Object<'js>) -> JsResult<Object<'js>> {
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
        (
            Some(req_str(ctx, obj, "bottomButton", "text")?),
            Some(req_fn(ctx, obj, "bottomButton", "onClick")?),
        )
    };

    let page_id = state.alloc_id();
    state.pages.borrow_mut().insert(page_id, UiPageDef {
        title,
        transient,
        items_fn: Persistent::save(ctx, items_fn),
        on_close: on_close.map(|f| Persistent::save(ctx, f)),
        bottom_text,
        bottom_on_click: bottom_on_click.map(|f| Persistent::save(ctx, f)),
        callbacks: RefCell::new(HashMap::new()),
        next_slot: Cell::new(1),
    });

    let page = Object::new(ctx.clone())?;
    page.set(PAGE_ID_KEY, page_id as f64)?;
    let state2 = state.clone();
    page.set("invalidate", Function::new(ctx.clone(), move || {
        state2.host.ui_invalidate(page_id);
    })?)?;
    let state2 = state.clone();
    page.set("dispose", Function::new(ctx.clone(), move |ctx: Ctx<'js>| {
        dispose_page(&ctx, &state2, page_id);
    })?)?;
    Ok(page)
}

// -- render --

/// renders [`page_id`]'s full element tree into one JSON string; `None` (with a log) on failure
pub fn render_page(rt: &Runtime, context: &rquickjs::Context, state: &Rc<UiState>, page_id: i64) -> Option<String> {
    let out = context.with(|ctx| match try_render(&ctx, state, page_id) {
        Ok(json) => Some(json),
        Err(rquickjs::Error::Exception) => {
            (state.log)(&format!("ui: render failed: {}", format_exception(&ctx)));
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

fn try_render<'js>(ctx: &Ctx<'js>, state: &Rc<UiState>, page_id: i64) -> JsResult<String> {
    // snapshot everything needed, then release the map borrow: items() runs plugin code that may
    // itself create pages (which needs a mut borrow)
    let (items_fn, title, bottom_text, bottom_on_click, mut next_slot) = {
        let pages = state.pages.borrow();
        let def = pages
            .get(&page_id)
            .ok_or_else(|| Exception::throw_message(ctx, "render: unknown page"))?;
        for (_, p) in def.callbacks.borrow_mut().drain() {
            let _ = p.restore(ctx);
        }
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

    let mut new_cbs: Vec<(u32, Function<'js>)> = Vec::new();
    let mut alloc_slot = |f: Function<'js>| -> u32 {
        let slot = next_slot;
        next_slot += 1;
        new_cbs.push((slot, f));
        slot
    };

    let out_items = Array::new(ctx.clone())?;
    for (i, element) in items_arr.iter::<Value>().enumerate() {
        let element = element?;
        let obj = element
            .as_object()
            .filter(|o| o.get::<_, Option<String>>(ELEMENT_TAG).ok().flatten().is_some())
            .ok_or_else(|| Exception::throw_type(ctx, "settingsPage: items() must return only inu.ui elements"))?;
        let ty: String = obj.get(ELEMENT_TAG)?;

        let out = Object::new(ctx.clone())?;
        out.set("type", ty.as_str())?;
        match ty.as_str() {
            "header" | "separator" => {
                set_opt(&out, "text", obj.get::<_, Option<String>>("text")?)?;
            }
            "check" => {
                set_opt(&out, "id", obj.get::<_, Option<String>>("id")?)?;
                out.set("text", obj.get::<_, String>("text")?)?;
                set_opt(&out, "subtitle", obj.get::<_, Option<String>>("subtitle")?)?;
                out.set("checked", obj.get::<_, bool>("checked")?)?;
                out.set("onChange", alloc_slot(obj.get::<_, Function>("onChange")?))?;
                if let Some(f) = obj.get::<_, Option<Function>>("onSecondaryClick")? {
                    out.set("onSecondaryClick", alloc_slot(f))?;
                }
            }
            "button" => {
                set_opt(&out, "id", obj.get::<_, Option<String>>("id")?)?;
                out.set("text", obj.get::<_, String>("text")?)?;
                set_opt(&out, "subtitle", obj.get::<_, Option<String>>("subtitle")?)?;
                set_opt(&out, "value", obj.get::<_, Option<String>>("value")?)?;
                out.set("danger", obj.get::<_, bool>("danger")?)?;
                out.set("onClick", alloc_slot(obj.get::<_, Function>("onClick")?))?;
                if let Some(f) = obj.get::<_, Option<Function>>("onSecondaryClick")? {
                    out.set("onSecondaryClick", alloc_slot(f))?;
                }
            }
            "select" => {
                set_opt(&out, "id", obj.get::<_, Option<String>>("id")?)?;
                out.set("text", obj.get::<_, String>("text")?)?;
                out.set("items", obj.get::<_, Array>("items")?)?;
                out.set("selected", obj.get::<_, i32>("selected")?)?;
                out.set("dialog", obj.get::<_, bool>("dialog")?)?;
                out.set("onChange", alloc_slot(obj.get::<_, Function>("onChange")?))?;
                if let Some(f) = obj.get::<_, Option<Function>>("onSecondaryClick")? {
                    out.set("onSecondaryClick", alloc_slot(f))?;
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
                    let steps = (((max - min) / step).round() as usize).saturating_add(1);
                    if steps <= MAX_SLIDER_LABELS {
                        let labels = Array::new(ctx.clone())?;
                        for s in 0..steps {
                            let v = (min + s as f64 * step).min(max);
                            let text: rquickjs::Coerced<String> = label.call((v,))?;
                            labels.set(s, text.0)?;
                        }
                        out.set("labels", labels)?;
                    }
                }
                out.set("onChange", alloc_slot(obj.get::<_, Function>("onChange")?))?;
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
        bottom.set("text", text)?;
        bottom.set("onClick", alloc_slot(f))?;
        root.set("bottomButton", bottom)?;
    }

    {
        let pages = state.pages.borrow();
        if let Some(def) = pages.get(&page_id) {
            let mut cbs = def.callbacks.borrow_mut();
            for (slot, f) in new_cbs {
                cbs.insert(slot, Persistent::save(ctx, f));
            }
            def.next_slot.set(next_slot);
        }
    }

    json_stringify(ctx, root.into())?
        .ok_or_else(|| Exception::throw_message(ctx, "render: serialization produced no output"))
}

// -- events --

/// fires the callback behind [`slot`]. [`arg_json`]: "" for no-arg callbacks, else one JSON scalar.
/// a slot from a previous render is a safe no-op (slots are never reused within a page).
pub fn dispatch_ui_event(rt: &Runtime, context: &rquickjs::Context, state: &Rc<UiState>, page_id: i64, slot: u32, arg_json: &str) {
    context.with(|ctx| {
        let cb = {
            let pages = state.pages.borrow();
            pages.get(&page_id).and_then(|def| def.callbacks.borrow().get(&slot).cloned())
        };
        let Some(cb) = cb else { return };
        let f = match cb.restore(&ctx) {
            Ok(f) => f,
            Err(e) => {
                (state.log)(&format!("ui: failed to restore callback: {e:?}"));
                return;
            }
        };
        state.in_event.set(true);
        let result = if arg_json.is_empty() {
            f.call::<_, Value>(())
        } else {
            match json_parse(&ctx, arg_json) {
                Ok(arg) => f.call::<_, Value>((arg,)),
                Err(e) => {
                    state.in_event.set(false);
                    (state.log)(&format!("ui: bad event arg: {e:?}"));
                    return;
                }
            }
        };
        state.in_event.set(false);
        match result {
            Ok(_) => {}
            Err(rquickjs::Error::Exception) => {
                (state.log)(&format!("ui callback threw: {}", format_exception(&ctx)));
            }
            Err(e) => (state.log)(&format!("ui callback failed: {e:?}")),
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

// -- inu.ui.openMenu --

fn js_open_menu<'js>(ctx: &Ctx<'js>, state: &Rc<UiState>, items: Value<'js>) -> JsResult<()> {
    if !state.in_event.get() {
        return Err(Exception::throw_message(
            ctx,
            "openMenu: only valid synchronously inside a settings-page item callback",
        ));
    }
    let arr = items
        .as_array()
        .ok_or_else(|| Exception::throw_type(ctx, "openMenu: expected an array of items"))?;
    if arr.is_empty() {
        return Err(Exception::throw_type(ctx, "openMenu: items must not be empty"));
    }

    let out = Array::new(ctx.clone())?;
    let mut callbacks: Vec<Function<'js>> = Vec::with_capacity(arr.len());
    for (i, item) in arr.iter::<Value>().enumerate() {
        let item = item?;
        let obj = item
            .as_object()
            .ok_or_else(|| Exception::throw_type(ctx, "openMenu: items must be objects"))?;
        let entry = Object::new(ctx.clone())?;
        entry.set("text", req_str(ctx, obj, "openMenu item", "text")?)?;
        // "checked" present at all (even false) switches the host to radio-style rendering, so
        // only emit it when the plugin actually specified it
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
    let json = json_stringify(ctx, out.into())?
        .ok_or_else(|| Exception::throw_message(ctx, "openMenu: serialization failed"))?;

    let menu_id = state.alloc_id();
    if let Some(err) = state.host.ui_open_menu(menu_id, &json) {
        return Err(Exception::throw_message(ctx, &err));
    }
    state
        .menus
        .borrow_mut()
        .insert(menu_id, callbacks.into_iter().map(|f| Persistent::save(ctx, f)).collect());
    Ok(())
}

/// settles an open menu: [`slot`] is the clicked item's index, or -1 for dismissed-without-click.
/// either way the menu's callbacks are released.
pub fn dispatch_menu_click(rt: &Runtime, context: &rquickjs::Context, state: &Rc<UiState>, menu_id: i64, slot: i32) {
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
                        (state.log)(&format!("menu item callback threw: {}", format_exception(&ctx)));
                    }
                    Err(e) => (state.log)(&format!("menu item callback failed: {e:?}")),
                }
            }
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

// -- inu.ui.prompt --

fn js_prompt<'js>(ctx: &Ctx<'js>, state: &Rc<UiState>, opts: Object<'js>) -> JsResult<Value<'js>> {
    let out = Object::new(ctx.clone())?;
    out.set("title", req_str(ctx, &opts, "prompt", "title")?)?;
    set_opt(&out, "hint", opt_str(ctx, &opts, "prompt", "hint")?)?;
    set_opt(&out, "value", opt_str(ctx, &opts, "prompt", "value")?)?;
    out.set("selectAll", opt_bool(ctx, &opts, "prompt", "selectAll")?)?;
    let json = json_stringify(ctx, out.into())?
        .ok_or_else(|| Exception::throw_message(ctx, "prompt: serialization failed"))?;

    let request_id = state.alloc_id();
    let (promise, pending) = PendingSettle::new(ctx)?;
    state.pending_prompts.borrow_mut().insert(request_id, pending);

    if let Some(err) = state.host.ui_prompt(request_id, &json) {
        if let Some(pending) = state.pending_prompts.borrow_mut().remove(&request_id) {
            pending.reject_with(ctx, &err)?;
        }
    }
    Ok(promise.into_value())
}

/// settles a pending `inu.ui.prompt()`: the submitted text, or `None` for cancel/dismiss (-> null)
pub fn resolve_prompt(rt: &Runtime, context: &rquickjs::Context, state: &Rc<UiState>, request_id: i64, text: Option<&str>) {
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

// -- lifecycle --

/// the host closed the last view of [`page_id`]: fire `onClose`, release the render callbacks.
/// the page definition stays reopenable, unless it's `transient` — then it's disposed entirely.
pub fn page_closed(rt: &Runtime, context: &rquickjs::Context, state: &Rc<UiState>, page_id: i64) {
    context.with(|ctx| {
        let (on_close, transient) = {
            let pages = state.pages.borrow();
            let Some(def) = pages.get(&page_id) else { return };
            for (_, p) in def.callbacks.borrow_mut().drain() {
                let _ = p.restore(&ctx);
            }
            (def.on_close.as_ref().cloned(), def.transient)
        };
        if let Some(persistent) = on_close {
            match persistent.restore(&ctx) {
                Ok(f) => match f.call::<_, Value>(()) {
                    Ok(_) => {}
                    Err(rquickjs::Error::Exception) => {
                        (state.log)(&format!("onClose callback threw: {}", format_exception(&ctx)));
                    }
                    Err(e) => (state.log)(&format!("onClose callback failed: {e:?}")),
                },
                Err(e) => (state.log)(&format!("onClose: failed to restore callback: {e:?}")),
            }
        }
        if transient {
            dispose_page(&ctx, state, page_id);
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

/// releases every `Persistent` GC root this state still owns - same contract as [`crate::rpc::dispose`]
pub fn dispose(context: &rquickjs::Context, state: &Rc<UiState>) {
    context.with(|ctx| {
        for (_, def) in state.pages.borrow_mut().drain() {
            release_page_def(&ctx, def);
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

#[cfg(test)]
mod tests {
    use super::*;
    use rquickjs::Context;

    #[derive(Default)]
    struct TestUiHost {
        prompts: RefCell<Vec<(i64, String)>>,
        opened_pages: RefCell<Vec<i64>>,
        registered: RefCell<Vec<i64>>,
        invalidated: RefCell<Vec<i64>>,
        menus: RefCell<Vec<(i64, String)>>,
        fail_menu: RefCell<Option<String>>,
    }

    impl UiHost for TestUiHost {
        fn ui_prompt(&self, request_id: i64, options_json: &str) -> Option<String> {
            self.prompts.borrow_mut().push((request_id, options_json.to_string()));
            None
        }
        fn ui_open_page(&self, page_id: i64) -> Option<String> {
            self.opened_pages.borrow_mut().push(page_id);
            None
        }
        fn ui_register_settings(&self, page_id: i64) {
            self.registered.borrow_mut().push(page_id);
        }
        fn ui_invalidate(&self, page_id: i64) {
            self.invalidated.borrow_mut().push(page_id);
        }
        fn ui_open_menu(&self, menu_id: i64, items_json: &str) -> Option<String> {
            if let Some(err) = self.fail_menu.borrow().as_ref() {
                return Some(err.clone());
            }
            self.menus.borrow_mut().push((menu_id, items_json.to_string()));
            None
        }
    }

    fn setup() -> (Runtime, Context, Rc<TestUiHost>, Rc<UiState>, Rc<RefCell<Vec<String>>>) {
        let rt = Runtime::new().unwrap();
        let ctx = Context::full(&rt).unwrap();
        let host = Rc::new(TestUiHost::default());
        let host_dyn: Rc<dyn UiHost> = host.clone();
        let logs = Rc::new(RefCell::new(Vec::<String>::new()));
        let logs2 = logs.clone();
        let log: Rc<dyn Fn(&str)> = Rc::new(move |msg: &str| logs2.borrow_mut().push(msg.to_string()));
        let state = ctx.with(|ctx| install_ui(&ctx, host_dyn, log, ).unwrap());
        (rt, ctx, host, state, logs)
    }

    /// registers a page exercising every element type; returns its page id
    fn build_full_page(ctx: &Context, host: &Rc<TestUiHost>) -> i64 {
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                globalThis.__state = { on: false, sel: 0, speed: 1, log: [] };
                const s = globalThis.__state;
                const page = inu.ui.settingsPage({
                    title: 'Test page',
                    items: () => [
                        inu.ui.header('General'),
                        inu.ui.check({ text: 'Toggle', subtitle: 'sub', checked: s.on, onChange: v => { s.on = v; } }),
                        inu.ui.button({ text: 'Do it', value: 'now', danger: true, onClick: () => { s.log.push('click'); },
                            onSecondaryClick: () => { s.log.push('long'); } }),
                        inu.ui.select({ text: 'Mode', items: ['a', { text: 'b', subtitle: 'bee' }], selected: s.sel,
                            dialog: true, onChange: i => { s.sel = i; } }),
                        inu.ui.slider({ text: 'Speed', min: 0, max: 2, step: 1, value: s.speed, default: 1,
                            label: v => v + 'x', onChange: v => { s.speed = v; } }),
                        inu.ui.separator('the end'),
                    ],
                    bottomButton: { text: 'Save', onClick: () => { s.log.push('save'); } },
                    onClose: () => { s.log.push('close'); },
                });
                inu.registerSettings(page);
                globalThis.__page = page;
                "#,
            )
            .unwrap();
        });
        *host.registered.borrow().last().unwrap()
    }

    #[test]
    fn full_page_render_serializes_every_element() {
        let (rt, ctx, host, state, logs) = setup();
        let page_id = build_full_page(&ctx, &host);
        let json = render_page(&rt, &ctx, &state, page_id).expect("render failed");
        assert_eq!(
            json,
            r#"{"title":"Test page","items":[{"type":"header","text":"General"},{"type":"check","text":"Toggle","subtitle":"sub","checked":false,"onChange":1},{"type":"button","text":"Do it","value":"now","danger":true,"onClick":2,"onSecondaryClick":3},{"type":"select","text":"Mode","items":[{"text":"a"},{"text":"b","subtitle":"bee"}],"selected":0,"dialog":true,"onChange":4},{"type":"slider","text":"Speed","min":0,"max":2,"step":1,"value":1,"default":1,"labels":["0x","1x","2x"],"onChange":5},{"type":"separator","text":"the end"}],"bottomButton":{"text":"Save","onClick":6}}"#,
        );
        assert!(logs.borrow().is_empty(), "unexpected logs: {:?}", logs.borrow());
        dispose(&ctx, &state);
    }

    #[test]
    fn events_update_state_and_rerender_uses_fresh_slots() {
        let (rt, ctx, host, state, _logs) = setup();
        let page_id = build_full_page(&ctx, &host);
        render_page(&rt, &ctx, &state, page_id).unwrap();

        dispatch_ui_event(&rt, &ctx, &state, page_id, 1, "true");
        dispatch_ui_event(&rt, &ctx, &state, page_id, 4, "1");
        dispatch_ui_event(&rt, &ctx, &state, page_id, 2, "");
        dispatch_ui_event(&rt, &ctx, &state, page_id, 6, "");

        let snapshot: String = ctx.with(|ctx| {
            ctx.eval("JSON.stringify([__state.on, __state.sel, __state.log])").unwrap()
        });
        assert_eq!(snapshot, r#"[true,1,["click","save"]]"#);

        let json = render_page(&rt, &ctx, &state, page_id).unwrap();
        assert!(json.contains(r#""checked":true"#));
        assert!(json.contains(r#""selected":1"#));
        assert!(json.contains(r#""onChange":7"#), "slots must not restart: {json}");

        // slot from the first render is gone now - firing it must be a silent no-op
        dispatch_ui_event(&rt, &ctx, &state, page_id, 1, "false");
        let unchanged: bool = ctx.with(|ctx| ctx.eval("__state.on === true").unwrap());
        assert!(unchanged);
        dispose(&ctx, &state);
    }

    #[test]
    fn open_menu_only_works_inside_event_and_click_dispatches() {
        let (rt, ctx, host, state, _logs) = setup();
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                globalThis.__picked = null;
                globalThis.__menuErr = null;
                try {
                    inu.ui.openMenu([{ text: 'nope', onClick: () => {} }]);
                } catch (e) { globalThis.__menuErr = e.message; }
                const page = inu.ui.settingsPage({
                    title: 'menu test',
                    items: () => [
                        inu.ui.button({ text: 'row', onClick: () => {
                            inu.ui.openMenu([
                                { text: 'one', onClick: () => { globalThis.__picked = 'one'; } },
                                { text: 'two', checked: true, danger: true, onClick: () => { globalThis.__picked = 'two'; } },
                            ]);
                        } }),
                    ],
                });
                inu.registerSettings(page);
                "#,
            )
            .unwrap();
        });
        let outside_err: String = ctx.with(|ctx| ctx.eval("globalThis.__menuErr").unwrap());
        assert!(outside_err.contains("only valid synchronously"));

        let page_id = *host.registered.borrow().last().unwrap();
        render_page(&rt, &ctx, &state, page_id).unwrap();
        dispatch_ui_event(&rt, &ctx, &state, page_id, 1, "");

        let menus = host.menus.borrow();
        assert_eq!(menus.len(), 1);
        assert_eq!(
            menus[0].1,
            r#"[{"text":"one","danger":false},{"text":"two","checked":true,"danger":true}]"#,
        );
        let menu_id = menus[0].0;
        drop(menus);

        dispatch_menu_click(&rt, &ctx, &state, menu_id, 1);
        let picked: String = ctx.with(|ctx| ctx.eval("globalThis.__picked").unwrap());
        assert_eq!(picked, "two");
        assert!(state.menus.borrow().is_empty());
        dispose(&ctx, &state);
    }

    #[test]
    fn menu_dismissed_without_click_releases_callbacks() {
        let (rt, ctx, host, state, _logs) = setup();
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                globalThis.__picked = null;
                const page = inu.ui.settingsPage({
                    title: 't',
                    items: () => [inu.ui.button({ text: 'row', onClick: () => {
                        inu.ui.openMenu([{ text: 'x', onClick: () => { globalThis.__picked = 'x'; } }]);
                    } })],
                });
                inu.registerSettings(page);
                "#,
            )
            .unwrap();
        });
        let page_id = *host.registered.borrow().last().unwrap();
        render_page(&rt, &ctx, &state, page_id).unwrap();
        dispatch_ui_event(&rt, &ctx, &state, page_id, 1, "");
        let menu_id = host.menus.borrow()[0].0;

        dispatch_menu_click(&rt, &ctx, &state, menu_id, -1);
        let picked_is_null: bool = ctx.with(|ctx| ctx.eval("globalThis.__picked === null").unwrap());
        assert!(picked_is_null);
        assert!(state.menus.borrow().is_empty());
        dispose(&ctx, &state);
    }

    #[test]
    fn prompt_resolves_with_text_and_null() {
        let (rt, ctx, host, state, _logs) = setup();
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                globalThis.__results = [];
                inu.ui.prompt({ title: 'Name?', hint: 'h', value: 'v', selectAll: true })
                    .then(r => { globalThis.__results.push(r); });
                inu.ui.prompt({ title: 'Again?' }).then(r => { globalThis.__results.push(r); });
                "#,
            )
            .unwrap();
        });
        let prompts = host.prompts.borrow();
        assert_eq!(prompts.len(), 2);
        assert_eq!(prompts[0].1, r#"{"title":"Name?","hint":"h","value":"v","selectAll":true}"#);
        let (id1, id2) = (prompts[0].0, prompts[1].0);
        drop(prompts);

        resolve_prompt(&rt, &ctx, &state, id1, Some("alice"));
        resolve_prompt(&rt, &ctx, &state, id2, None);
        let results: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__results)").unwrap());
        assert_eq!(results, r#"["alice",null]"#);
        dispose(&ctx, &state);
    }

    #[test]
    fn open_page_and_invalidate_reach_host() {
        let (rt, ctx, host, state, _logs) = setup();
        let page_id = build_full_page(&ctx, &host);
        ctx.with(|ctx| {
            ctx.eval::<(), _>("inu.ui.openPage(globalThis.__page); globalThis.__page.invalidate();").unwrap();
        });
        assert_eq!(*host.opened_pages.borrow(), vec![page_id]);
        assert_eq!(*host.invalidated.borrow(), vec![page_id]);

        let threw = ctx.with(|ctx| ctx.eval::<(), _>("inu.ui.openPage({})").is_err());
        assert!(threw);
        let _ = (&rt, page_id);
        dispose(&ctx, &state);
    }

    #[test]
    fn page_closed_fires_on_close_and_page_stays_reopenable() {
        let (rt, ctx, host, state, _logs) = setup();
        let page_id = build_full_page(&ctx, &host);
        render_page(&rt, &ctx, &state, page_id).unwrap();

        page_closed(&rt, &ctx, &state, page_id);
        let closed: bool = ctx.with(|ctx| ctx.eval("__state.log.includes('close')").unwrap());
        assert!(closed);

        // render callbacks were released, but the page can be rendered again
        assert!(render_page(&rt, &ctx, &state, page_id).is_some());
        dispose(&ctx, &state);
    }

    #[test]
    fn manual_dispose_releases_page_and_is_idempotent() {
        let (rt, ctx, host, state, logs) = setup();
        let page_id = build_full_page(&ctx, &host);
        render_page(&rt, &ctx, &state, page_id).unwrap();

        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                globalThis.__page.dispose();
                globalThis.__page.dispose();
                globalThis.__openErr = null;
                try { inu.ui.openPage(globalThis.__page); } catch (e) { globalThis.__openErr = e.message; }
                "#,
            )
            .unwrap();
        });
        assert!(state.pages.borrow().is_empty());
        let open_err: String = ctx.with(|ctx| ctx.eval("globalThis.__openErr").unwrap());
        assert!(open_err.contains("unknown page"));
        assert!(render_page(&rt, &ctx, &state, page_id).is_none());
        logs.borrow_mut().clear();
        // rt/ctx drop after this without aborting == roots were released
    }

    #[test]
    fn transient_page_auto_disposes_on_close_after_on_close_fires() {
        let (rt, ctx, host, state, _logs) = setup();
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                globalThis.__closed = 0;
                inu.registerSettings(inu.ui.settingsPage({
                    title: 't',
                    transient: true,
                    items: () => [inu.ui.button({ text: 'r', onClick: () => {} })],
                    onClose: () => { globalThis.__closed++; },
                }));
                "#,
            )
            .unwrap();
        });
        let page_id = *host.registered.borrow().last().unwrap();
        render_page(&rt, &ctx, &state, page_id).unwrap();

        page_closed(&rt, &ctx, &state, page_id);
        let closed: i32 = ctx.with(|ctx| ctx.eval("globalThis.__closed").unwrap());
        assert_eq!(closed, 1);
        assert!(state.pages.borrow().is_empty());

        // a second close for the same id must be a silent no-op
        page_closed(&rt, &ctx, &state, page_id);
        let closed: i32 = ctx.with(|ctx| ctx.eval("globalThis.__closed").unwrap());
        assert_eq!(closed, 1);
    }

    #[test]
    fn throwing_items_fn_logs_and_returns_none() {
        let (rt, ctx, host, state, logs) = setup();
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                inu.registerSettings(inu.ui.settingsPage({
                    title: 'broken',
                    items: () => { throw new Error('render-boom'); },
                }));
                "#,
            )
            .unwrap();
        });
        let page_id = *host.registered.borrow().last().unwrap();
        assert!(render_page(&rt, &ctx, &state, page_id).is_none());
        assert!(
            logs.borrow().iter().any(|l| l.contains("render failed") && l.contains("render-boom")),
            "expected a logged diagnostic, got: {:?}",
            logs.borrow(),
        );
        dispose(&ctx, &state);
    }

    #[test]
    fn element_creation_validates_options_eagerly() {
        let (_rt, ctx, _host, state, _logs) = setup();
        let errors: String = ctx.with(|ctx| {
            ctx.eval::<String, _>(
                r#"
                const out = [];
                const tryIt = f => { try { f(); out.push('ok'); } catch (e) { out.push(e.message); } };
                tryIt(() => inu.ui.check({ text: 'x' }));
                tryIt(() => inu.ui.select({ text: 'x', items: [], selected: 0, onChange: () => {} }));
                tryIt(() => inu.ui.select({ text: 'x', items: ['a'], selected: 5, onChange: () => {} }));
                tryIt(() => inu.ui.slider({ min: 0, max: 10, step: 0, value: 1, onChange: () => {} }));
                JSON.stringify(out);
                "#,
            )
            .unwrap()
        });
        assert_eq!(
            errors,
            r#"["check: 'checked' must be a boolean","select: 'items' must not be empty","select: 'selected' out of range","slider: 'step' must be > 0"]"#,
        );
        dispose(&ctx, &state);
    }

    #[test]
    fn dispose_with_open_everything_releases_roots() {
        let (rt, ctx, host, state, _logs) = setup();
        let page_id = build_full_page(&ctx, &host);
        render_page(&rt, &ctx, &state, page_id).unwrap();
        ctx.with(|ctx| {
            ctx.eval::<(), _>("inu.ui.prompt({ title: 'stuck' });").unwrap();
        });
        // leave a menu open too
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                inu.registerSettings(inu.ui.settingsPage({ title: 'm', items: () => [
                    inu.ui.button({ text: 'r', onClick: () => inu.ui.openMenu([{ text: 'x', onClick: () => {} }]) }),
                ]}));
                "#,
            )
            .unwrap();
        });
        let page2 = *host.registered.borrow().last().unwrap();
        render_page(&rt, &ctx, &state, page2).unwrap();
        dispatch_ui_event(&rt, &ctx, &state, page2, 1, "");
        assert_eq!(state.menus.borrow().len(), 1);

        dispose(&ctx, &state);
        // rt/ctx drop after this without aborting == roots were released
    }
}

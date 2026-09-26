use rquickjs::atom::PredefinedAtom;

use crate::{api::error::PluginErrorCode, utils::arguments::array_values};

use super::*;

#[rquickjs::methods(rename_all = "camelCase")]
impl<'js> GradientHandle {
  fn add_color_stop(&self, ctx: Ctx<'js>, offset: Opt<Coerced<f64>>, color: Opt<Coerced<String>>) -> JsResult<()> {
    let offset = num(&offset);
    if !offset.is_finite() {
      return Err(Exception::throw_type(&ctx, "a colour stop's offset must be a finite number"));
    }
    if !(0.0..=1.0).contains(&offset) {
      return Err(Exception::throw_dom(&ctx, "IndexSizeError", "a colour stop's offset must be between 0 and 1"));
    }
    let Some(text) = color.0 else {
      return Err(Exception::throw_type(&ctx, "a colour stop needs a colour"));
    };
    let Some(color) = parse_color(&text.0) else {
      return Err(Exception::throw_dom(&ctx, "SyntaxError", &format!("'{}' is not a colour", text.0)));
    };
    let mut stops = self.gradient.stops.borrow_mut();
    if stops.len() >= MAX_GRADIENT_STOPS {
      return PluginErrorCode::QuotaExceeded(stops.len() as i64 + 1, MAX_GRADIENT_STOPS as i64)
        .throw(&ctx, &format!("a gradient may have at most {MAX_GRADIENT_STOPS} colour stops"));
    }
    let at = stops.partition_point(|(existing, _)| *existing <= offset);
    stops.insert(at, (offset, color));
    Ok(())
  }
}

#[rquickjs::methods(rename_all = "camelCase")]
impl<'js> PatternHandle {
  fn set_transform(&self, transform: Opt<Value<'js>>) -> JsResult<()> {
    let matrix = matrix_from_init(&transform)?;
    if matrix.is_finite() {
      self.pattern.transform.set(matrix);
    }
    Ok(())
  }
}

fn matrix_from_init(value: &Opt<Value<'_>>) -> JsResult<Affine> {
  let Some(object) = value.0.as_ref().and_then(|v| v.as_object()) else {
    return Ok(Affine::IDENTITY);
  };
  let read = |name: &str, default: f64| -> JsResult<f64> {
    Ok(object.get::<_, Option<Coerced<f64>>>(name)?.map(|v| v.0).unwrap_or(default))
  };
  Ok(Affine::new([
    read("a", 1.0)?,
    read("b", 0.0)?,
    read("c", 0.0)?,
    read("d", 1.0)?,
    read("e", 0.0)?,
    read("f", 0.0)?,
  ]))
}

#[rquickjs::methods(rename_all = "camelCase")]
impl<'js> ImageHandle {
  #[qjs(get, enumerable, configurable, rename = "width")]
  fn get_width(&self) -> i32 {
    self.image.width
  }

  #[qjs(get, enumerable, configurable, rename = "height")]
  fn get_height(&self) -> i32 {
    self.image.height
  }

  fn dispose(&self, ctx: Ctx<'js>) -> JsResult<()> {
    self.image.release(&ctx)
  }
}

#[rquickjs::methods(rename_all = "camelCase")]
impl<'js> CanvasHandle {
  #[qjs(get, enumerable, configurable, rename = "width")]
  fn get_width(&self) -> i32 {
    self.surface.width.get()
  }

  #[qjs(set, rename = "width")]
  fn set_width(&self, ctx: Ctx<'js>, value: Coerced<f64>) -> JsResult<()> {
    if !value.0.is_finite() {
      return Ok(());
    }
    self.surface.resize(&ctx, value.0.trunc() as i32, self.surface.height.get())
  }

  #[qjs(get, enumerable, configurable, rename = "height")]
  fn get_height(&self) -> i32 {
    self.surface.height.get()
  }

  #[qjs(set, rename = "height")]
  fn set_height(&self, ctx: Ctx<'js>, value: Coerced<f64>) -> JsResult<()> {
    if !value.0.is_finite() {
      return Ok(());
    }
    self.surface.resize(&ctx, self.surface.width.get(), value.0.trunc() as i32)
  }

  fn get_context(ctx: Ctx<'js>, this: This<Class<'js, Self>>, id: Opt<Coerced<String>>) -> JsResult<Value<'js>> {
    match id.0.as_ref().map(|v| v.0.as_str()) {
      Some("2d") => {}
      _ => return PluginErrorCode::InvalidArgument.throw(&ctx, "getContext: only '2d' is available"),
    }
    let key = rquickjs::Symbol::new_global(ctx.clone(), CONTEXT_KEY)?;
    let canvas = this.0.as_inner().clone();
    let cached: Value = canvas.get(key.as_atom())?;
    if Class::<Context2d>::from_value(&cached).is_ok() {
      return Ok(cached);
    }
    let surface = this.0.borrow().surface.clone();
    let context = Class::instance(
      ctx.clone(),
      Context2d {
        surface,
        state: RefCell::new(DrawState::default()),
        stack: RefCell::new(Vec::new()),
        path: RefCell::new(Path::default()),
      },
    )?;
    context.as_inner().prop("canvas", Property::from(canvas.clone()).enumerable())?;
    canvas.prop(key.as_atom(), Property::from(context.as_value().clone()))?;
    Ok(context.into_value())
  }

  fn convert_to_blob(&self, ctx: Ctx<'js>, options: Opt<Value<'js>>) -> JsResult<Value<'js>> {
    self.surface.state.convert_to_blob(&ctx, &self.surface, options)
  }

  fn dispose(&self) {
    self.surface.free();
  }
}

#[rquickjs::methods(rename_all = "camelCase")]
impl<'js> Context2d {
  fn save(&self, ctx: Ctx<'js>) -> JsResult<()> {
    self.live(&ctx)?;
    let state = self.state.borrow().clone();
    self.stack.borrow_mut().push(state);
    self.surface.record(&ctx, |out| out.u8(CMD_SAVE))
  }

  fn restore(&self, ctx: Ctx<'js>) -> JsResult<()> {
    self.live(&ctx)?;
    let Some(state) = self.stack.borrow_mut().pop() else {
      return Ok(());
    };
    *self.state.borrow_mut() = state;
    self.surface.record(&ctx, |out| out.u8(CMD_RESTORE))
  }

  fn reset(&self, ctx: Ctx<'js>) -> JsResult<()> {
    self.live(&ctx)?;
    *self.state.borrow_mut() = DrawState::default();
    self.stack.borrow_mut().clear();
    self.path.borrow_mut().0.truncate(0);
    self.surface.record(&ctx, |out| out.u8(CMD_RESET))
  }

  fn scale(&self, x: Opt<Coerced<f64>>, y: Opt<Coerced<f64>>) {
    let (x, y) = (num(&x), num(&y));
    if finite(&[x, y]) {
      let next = self.state.borrow().matrix.pre_scale_non_uniform(x, y);
      self.state.borrow_mut().matrix = next;
    }
  }

  fn rotate(&self, angle: Opt<Coerced<f64>>) {
    let angle = num(&angle);
    if angle.is_finite() {
      let next = self.state.borrow().matrix.pre_rotate(angle);
      self.state.borrow_mut().matrix = next;
    }
  }

  fn translate(&self, x: Opt<Coerced<f64>>, y: Opt<Coerced<f64>>) {
    let (x, y) = (num(&x), num(&y));
    if finite(&[x, y]) {
      let next = self.state.borrow().matrix.pre_translate(Vec2::new(x, y));
      self.state.borrow_mut().matrix = next;
    }
  }

  fn transform(&self, args: Rest<Value<'js>>) {
    let v = &args.0;
    let m = Affine::new([nth(v, 0), nth(v, 1), nth(v, 2), nth(v, 3), nth(v, 4), nth(v, 5)]);
    if m.is_finite() {
      let next = self.state.borrow().matrix * m;
      self.state.borrow_mut().matrix = next;
    }
  }

  fn set_transform(&self, args: Rest<Value<'js>>) -> JsResult<()> {
    let v = &args.0;
    let m = match v.first() {
      Some(first) if first.is_object() => matrix_from_init(&Opt(Some(first.clone())))?,
      None => Affine::IDENTITY,
      _ => Affine::new([nth(v, 0), nth(v, 1), nth(v, 2), nth(v, 3), nth(v, 4), nth(v, 5)]),
    };
    if m.is_finite() {
      self.state.borrow_mut().matrix = m;
    }
    Ok(())
  }

  fn reset_transform(&self) {
    self.state.borrow_mut().matrix = Affine::IDENTITY;
  }

  #[qjs(get, enumerable, configurable, rename = "globalAlpha")]
  fn get_global_alpha(&self) -> f64 {
    self.state.borrow().alpha
  }

  #[qjs(set, rename = "globalAlpha")]
  fn set_global_alpha(&self, value: Coerced<f64>) {
    if (0.0..=1.0).contains(&value.0) {
      self.state.borrow_mut().alpha = value.0;
    }
  }

  #[qjs(get, enumerable, configurable, rename = "globalCompositeOperation")]
  fn get_global_composite_operation(&self) -> &'static str {
    COMPOSITE_MODES[self.state.borrow().composite as usize]
  }

  #[qjs(set, rename = "globalCompositeOperation")]
  fn set_global_composite_operation(&self, value: Coerced<String>) {
    if let Some(index) = find_table_index(&COMPOSITE_MODES, &value.0) {
      self.state.borrow_mut().composite = index;
    }
  }

  #[qjs(get, enumerable, configurable, rename = "fillStyle")]
  fn get_fill_style(&self, ctx: Ctx<'js>) -> JsResult<Value<'js>> {
    style_to_value(&ctx, &self.state.borrow().fill)
  }

  #[qjs(set, rename = "fillStyle")]
  fn set_fill_style(&self, value: Value<'js>) -> JsResult<()> {
    if let Some(style) = style_from_value(&value)? {
      self.state.borrow_mut().fill = style;
    }
    Ok(())
  }

  #[qjs(get, enumerable, configurable, rename = "strokeStyle")]
  fn get_stroke_style(&self, ctx: Ctx<'js>) -> JsResult<Value<'js>> {
    style_to_value(&ctx, &self.state.borrow().stroke)
  }

  #[qjs(set, rename = "strokeStyle")]
  fn set_stroke_style(&self, value: Value<'js>) -> JsResult<()> {
    if let Some(style) = style_from_value(&value)? {
      self.state.borrow_mut().stroke = style;
    }
    Ok(())
  }

  #[qjs(get, enumerable, configurable, rename = "lineWidth")]
  fn get_line_width(&self) -> f64 {
    self.state.borrow().line_width
  }

  #[qjs(set, rename = "lineWidth")]
  fn set_line_width(&self, value: Coerced<f64>) {
    if value.0.is_finite() && value.0 > 0.0 {
      self.state.borrow_mut().line_width = value.0;
    }
  }

  #[qjs(get, enumerable, configurable, rename = "miterLimit")]
  fn get_miter_limit(&self) -> f64 {
    self.state.borrow().miter_limit
  }

  #[qjs(set, rename = "miterLimit")]
  fn set_miter_limit(&self, value: Coerced<f64>) {
    if value.0.is_finite() && value.0 > 0.0 {
      self.state.borrow_mut().miter_limit = value.0;
    }
  }

  #[qjs(get, enumerable, configurable, rename = "lineDashOffset")]
  fn get_line_dash_offset(&self) -> f64 {
    self.state.borrow().dash_offset
  }

  #[qjs(set, rename = "lineDashOffset")]
  fn set_line_dash_offset(&self, value: Coerced<f64>) {
    if value.0.is_finite() {
      self.state.borrow_mut().dash_offset = value.0;
    }
  }

  #[qjs(get, enumerable, configurable, rename = "lineCap")]
  fn get_line_cap(&self) -> &'static str {
    LINE_CAPS[self.state.borrow().line_cap as usize]
  }

  #[qjs(set, rename = "lineCap")]
  fn set_line_cap(&self, value: Coerced<String>) {
    if let Some(index) = find_table_index(&LINE_CAPS, &value.0) {
      self.state.borrow_mut().line_cap = index;
    }
  }

  #[qjs(get, enumerable, configurable, rename = "lineJoin")]
  fn get_line_join(&self) -> &'static str {
    LINE_JOINS[self.state.borrow().line_join as usize]
  }

  #[qjs(set, rename = "lineJoin")]
  fn set_line_join(&self, value: Coerced<String>) {
    if let Some(index) = find_table_index(&LINE_JOINS, &value.0) {
      self.state.borrow_mut().line_join = index;
    }
  }

  #[qjs(get, enumerable, configurable, rename = "shadowBlur")]
  fn get_shadow_blur(&self) -> f64 {
    self.state.borrow().shadow_blur
  }

  #[qjs(set, rename = "shadowBlur")]
  fn set_shadow_blur(&self, value: Coerced<f64>) {
    if value.0.is_finite() && value.0 >= 0.0 {
      self.state.borrow_mut().shadow_blur = value.0;
    }
  }

  #[qjs(get, enumerable, configurable, rename = "shadowColor")]
  fn get_shadow_color(&self) -> String {
    format_color(self.state.borrow().shadow_color)
  }

  #[qjs(set, rename = "shadowColor")]
  fn set_shadow_color(&self, value: Coerced<String>) {
    if let Some(color) = parse_color(&value.0) {
      self.state.borrow_mut().shadow_color = color;
    }
  }

  #[qjs(get, enumerable, configurable, rename = "shadowOffsetX")]
  fn get_shadow_offset_x(&self) -> f64 {
    self.state.borrow().shadow_offset.0
  }

  #[qjs(set, rename = "shadowOffsetX")]
  fn set_shadow_offset_x(&self, value: Coerced<f64>) {
    if value.0.is_finite() {
      self.state.borrow_mut().shadow_offset.0 = value.0;
    }
  }

  #[qjs(get, enumerable, configurable, rename = "shadowOffsetY")]
  fn get_shadow_offset_y(&self) -> f64 {
    self.state.borrow().shadow_offset.1
  }

  #[qjs(set, rename = "shadowOffsetY")]
  fn set_shadow_offset_y(&self, value: Coerced<f64>) {
    if value.0.is_finite() {
      self.state.borrow_mut().shadow_offset.1 = value.0;
    }
  }

  fn set_line_dash(&self, ctx: Ctx<'js>, segments: Opt<Value<'js>>) -> JsResult<()> {
    let Some(array) = segments.0.as_ref().and_then(|v| v.as_array()) else {
      return Err(Exception::throw_type(&ctx, "setLineDash: expected an array of lengths"));
    };
    let mut dash = Vec::new();
    for value in array_values(&ctx, array, "setLineDash")? {
      let value = Coerced::<f64>::from_js(&ctx, value)?.0;
      if !value.is_finite() || value < 0.0 {
        return Ok(());
      }
      dash.push(value);
    }
    if dash.len() % 2 == 1 {
      dash.extend_from_within(..);
    }
    self.state.borrow_mut().dash = dash;
    Ok(())
  }

  fn get_line_dash(&self) -> Vec<f64> {
    self.state.borrow().dash.clone()
  }

  fn create_linear_gradient(
    ctx: Ctx<'js>,
    x0: Opt<Coerced<f64>>,
    y0: Opt<Coerced<f64>>,
    x1: Opt<Coerced<f64>>,
    y1: Opt<Coerced<f64>>,
  ) -> JsResult<Value<'js>> {
    make_gradient(&ctx, STYLE_LINEAR, [num(&x0), num(&y0), num(&x1), num(&y1), 0.0, 0.0], 4)
  }

  fn create_radial_gradient(ctx: Ctx<'js>, args: Rest<Value<'js>>) -> JsResult<Value<'js>> {
    let a = &args.0;
    if nth(a, 2) < 0.0 || nth(a, 5) < 0.0 {
      return Err(Exception::throw_dom(&ctx, "IndexSizeError", "a radial gradient's radii must not be negative"));
    }
    make_gradient(&ctx, STYLE_RADIAL, [nth(a, 0), nth(a, 1), nth(a, 2), nth(a, 3), nth(a, 4), nth(a, 5)], 6)
  }

  fn create_conic_gradient(
    ctx: Ctx<'js>,
    angle: Opt<Coerced<f64>>,
    x: Opt<Coerced<f64>>,
    y: Opt<Coerced<f64>>,
  ) -> JsResult<Value<'js>> {
    make_gradient(&ctx, STYLE_CONIC, [num(&angle), num(&x), num(&y), 0.0, 0.0, 0.0], 3)
  }

  fn create_pattern(ctx: Ctx<'js>, image: Opt<Value<'js>>, repetition: Opt<Value<'js>>) -> JsResult<Value<'js>> {
    let repeat = match repetition.0.as_ref() {
      None => 0,
      Some(value) if value.is_null() || value.is_undefined() => 0,
      Some(value) => {
        let Some(text) = value.as_string() else {
          return Err(Exception::throw_dom(&ctx, "SyntaxError", "createPattern: that is not a repetition"));
        };
        let text = text.to_string()?;
        match find_table_index(&REPETITIONS, &text) {
          Some(index) => index,
          None => return Err(Exception::throw_dom(&ctx, "SyntaxError", &format!("'{text}' is not a repetition"))),
        }
      }
    };
    let Some(value) = image.0 else {
      return Err(Exception::throw_type(&ctx, "createPattern: expected an image"));
    };
    let source = image_source(&ctx, &value)?;
    if let ImageSource::Canvas(canvas) = &source {
      canvas.flush(&ctx)?;
    }
    let data = Rc::new(PatternData {
      source,
      repeat,
      transform: Cell::new(Affine::IDENTITY),
    });
    Ok(Class::instance(ctx.clone(), PatternHandle { pattern: data })?.into_value())
  }

  fn begin_path(&self) {
    self.path.borrow_mut().0.truncate(0);
  }

  fn close_path(&self) {
    self.path.borrow_mut().close();
  }

  fn move_to(&self, x: Opt<Coerced<f64>>, y: Opt<Coerced<f64>>) {
    let m = self.state.borrow().matrix;
    self.path.borrow_mut().move_to(m, num(&x), num(&y));
  }

  fn line_to(&self, x: Opt<Coerced<f64>>, y: Opt<Coerced<f64>>) {
    let m = self.state.borrow().matrix;
    self.path.borrow_mut().line_to(m, num(&x), num(&y));
  }

  fn bezier_curve_to(&self, args: Rest<Value<'js>>) {
    let a = &args.0;
    let m = self.state.borrow().matrix;
    self.path.borrow_mut().cubic_to(m, nth(a, 0), nth(a, 1), nth(a, 2), nth(a, 3), nth(a, 4), nth(a, 5));
  }

  fn quadratic_curve_to(
    &self,
    cx: Opt<Coerced<f64>>,
    cy: Opt<Coerced<f64>>,
    x: Opt<Coerced<f64>>,
    y: Opt<Coerced<f64>>,
  ) {
    let m = self.state.borrow().matrix;
    self.path.borrow_mut().quad_to(m, num(&cx), num(&cy), num(&x), num(&y));
  }

  fn arc(&self, ctx: Ctx<'js>, args: Rest<Value<'js>>) -> JsResult<()> {
    let a = &args.0;
    let m = self.state.borrow().matrix;
    let result = self
      .path
      .borrow_mut()
      .arc(m, nth(a, 0), nth(a, 1), nth(a, 2), nth(a, 3), nth(a, 4), truthy(a.get(5)));
    arc_result(&ctx, result)
  }

  fn ellipse(&self, ctx: Ctx<'js>, args: Rest<Value<'js>>) -> JsResult<()> {
    let a = &args.0;
    let m = self.state.borrow().matrix;
    let result = self.path.borrow_mut().ellipse(
      m,
      nth(a, 0),
      nth(a, 1),
      nth(a, 2),
      nth(a, 3),
      nth(a, 4),
      nth(a, 5),
      nth(a, 6),
      truthy(a.get(7)),
    );
    arc_result(&ctx, result)
  }

  fn arc_to(&self, ctx: Ctx<'js>, args: Rest<Value<'js>>) -> JsResult<()> {
    let a = &args.0;
    let m = self.state.borrow().matrix;
    let Some(inverse) = invert(m) else {
      return Ok(());
    };
    let result = self.path.borrow_mut().arc_to(m, inverse, nth(a, 0), nth(a, 1), nth(a, 2), nth(a, 3), nth(a, 4));
    arc_result(&ctx, result)
  }

  fn rect(&self, x: Opt<Coerced<f64>>, y: Opt<Coerced<f64>>, w: Opt<Coerced<f64>>, h: Opt<Coerced<f64>>) {
    let m = self.state.borrow().matrix;
    self.path.borrow_mut().rect(m, num(&x), num(&y), num(&w), num(&h));
  }

  fn round_rect(
    &self,
    ctx: Ctx<'js>,
    x: Opt<Coerced<f64>>,
    y: Opt<Coerced<f64>>,
    w: Opt<Coerced<f64>>,
    h: Opt<Coerced<f64>>,
    radii: Opt<Value<'js>>,
  ) -> JsResult<()> {
    let Some(corners) = read_radii(&ctx, &radii)? else {
      return Ok(());
    };
    let m = self.state.borrow().matrix;
    let (x, y, w, h) = (num(&x), num(&y), num(&w), num(&h));
    if !finite(&[x, y, w, h]) {
      return Ok(());
    }
    let (x, y, w, h, corners) = normalize_round_rect(x, y, w, h, corners);
    self.path.borrow_mut().round_rect(m, x, y, w, h, corners);
    Ok(())
  }

  fn fill(&self, ctx: Ctx<'js>, rule: Opt<Value<'js>>) -> JsResult<()> {
    let rule = parse_fill_rule(&ctx, rule)?;
    let path = self.path.borrow().clone();
    self.draw_path(&ctx, CMD_FILL, Some(PaintKind::Fill), rule, &path)
  }

  fn clip(&self, ctx: Ctx<'js>, rule: Opt<Value<'js>>) -> JsResult<()> {
    let rule = parse_fill_rule(&ctx, rule)?;
    let path = self.path.borrow().clone();
    self.draw_path(&ctx, CMD_CLIP, None, rule, &path)
  }

  fn stroke(&self, ctx: Ctx<'js>) -> JsResult<()> {
    let path = self.path.borrow().clone();
    self.draw_path(&ctx, CMD_STROKE, Some(PaintKind::Stroke), 0, &path)
  }

  fn clear_rect(
    &self,
    ctx: Ctx<'js>,
    x: Opt<Coerced<f64>>,
    y: Opt<Coerced<f64>>,
    w: Opt<Coerced<f64>>,
    h: Opt<Coerced<f64>>,
  ) -> JsResult<()> {
    self.draw_rect(&ctx, CMD_CLEAR, None, (num(&x), num(&y), num(&w), num(&h)))
  }

  fn fill_rect(
    &self,
    ctx: Ctx<'js>,
    x: Opt<Coerced<f64>>,
    y: Opt<Coerced<f64>>,
    w: Opt<Coerced<f64>>,
    h: Opt<Coerced<f64>>,
  ) -> JsResult<()> {
    self.draw_rect(&ctx, CMD_FILL, Some(PaintKind::Fill), (num(&x), num(&y), num(&w), num(&h)))
  }

  fn stroke_rect(
    &self,
    ctx: Ctx<'js>,
    x: Opt<Coerced<f64>>,
    y: Opt<Coerced<f64>>,
    w: Opt<Coerced<f64>>,
    h: Opt<Coerced<f64>>,
  ) -> JsResult<()> {
    self.draw_rect(&ctx, CMD_STROKE, Some(PaintKind::Stroke), (num(&x), num(&y), num(&w), num(&h)))
  }

  #[qjs(get, enumerable, configurable, rename = "font")]
  fn get_font(&self) -> String {
    self.state.borrow().font_source.clone()
  }

  #[qjs(set, rename = "font")]
  fn set_font(&self, value: Coerced<String>) {
    if let Some(font) = parse_font(&value.0) {
      let mut state = self.state.borrow_mut();
      state.font = font;
      state.font_source = value.0;
    }
  }

  #[qjs(get, enumerable, configurable, rename = "textAlign")]
  fn get_text_align(&self) -> &'static str {
    TEXT_ALIGNS[self.state.borrow().text_align as usize]
  }

  #[qjs(set, rename = "textAlign")]
  fn set_text_align(&self, value: Coerced<String>) {
    if let Some(index) = find_table_index(&TEXT_ALIGNS, &value.0) {
      self.state.borrow_mut().text_align = index;
    }
  }

  #[qjs(get, enumerable, configurable, rename = "textBaseline")]
  fn get_text_baseline(&self) -> &'static str {
    TEXT_BASELINES[self.state.borrow().text_baseline as usize]
  }

  #[qjs(set, rename = "textBaseline")]
  fn set_text_baseline(&self, value: Coerced<String>) {
    if let Some(index) = find_table_index(&TEXT_BASELINES, &value.0) {
      self.state.borrow_mut().text_baseline = index;
    }
  }

  fn fill_text(
    &self,
    ctx: Ctx<'js>,
    text: Opt<Coerced<String>>,
    x: Opt<Coerced<f64>>,
    y: Opt<Coerced<f64>>,
    max_width: Opt<Coerced<f64>>,
  ) -> JsResult<()> {
    self.draw_text(&ctx, PaintKind::Fill, text, (num(&x), num(&y)), max_width)
  }

  fn stroke_text(
    &self,
    ctx: Ctx<'js>,
    text: Opt<Coerced<String>>,
    x: Opt<Coerced<f64>>,
    y: Opt<Coerced<f64>>,
    max_width: Opt<Coerced<f64>>,
  ) -> JsResult<()> {
    self.draw_text(&ctx, PaintKind::Stroke, text, (num(&x), num(&y)), max_width)
  }

  fn measure_text(&self, ctx: Ctx<'js>, text: Opt<Coerced<String>>) -> JsResult<Value<'js>> {
    let text = text.0.map(|v| v.0).unwrap_or_default();
    self.live(&ctx)?;
    let (font, align) = {
      let state = self.state.borrow();
      (state.font.to_wire(), state.text_align)
    };
    let answer = ask(&*self.surface.state.host, OP_MEASURE, 0, |args| {
      args.text(&font);
      args.u8(align);
      args.text(&text);
    });
    parse_json_answer(&ctx, &answer, "measureText")
  }

  fn get_average_color(
    &self,
    ctx: Ctx<'js>,
    sx: Opt<Coerced<f64>>,
    sy: Opt<Coerced<f64>>,
    sw: Opt<Coerced<f64>>,
    sh: Opt<Coerced<f64>>,
  ) -> JsResult<Value<'js>> {
    self.live(&ctx)?;
    let region = if sx.0.is_none() && sy.0.is_none() && sw.0.is_none() && sh.0.is_none() {
      (0.0, 0.0, self.surface.width.get() as f64, self.surface.height.get() as f64)
    } else {
      let region = (num(&sx), num(&sy), num(&sw), num(&sh));
      if !finite(&[region.0, region.1, region.2, region.3]) {
        return PluginErrorCode::InvalidArgument.throw(&ctx, "getAverageColor: the region must be four finite numbers");
      }
      region
    };
    self.surface.flush(&ctx)?;
    let answer = ask(&*self.surface.state.host, OP_AVERAGE, self.surface.id, |args| {
      for v in [region.0, region.1, region.2, region.3] {
        args.f(v);
      }
    });
    parse_json_answer(&ctx, &answer, "getAverageColor")
  }

  fn draw_image(&self, ctx: Ctx<'js>, args: Rest<Value<'js>>) -> JsResult<()> {
    let a = &args.0;
    let Some(value) = a.first().cloned() else {
      return Err(Exception::throw_type(&ctx, "drawImage: expected an image"));
    };
    let source = image_source(&ctx, &value)?;
    let (iw, ih) = source.size();
    let (src, dst) = match a.len() - 1 {
      2 => ((0.0, 0.0, iw, ih), (nth(a, 1), nth(a, 2), iw, ih)),
      4 => ((0.0, 0.0, iw, ih), (nth(a, 1), nth(a, 2), nth(a, 3), nth(a, 4))),
      8 => ((nth(a, 1), nth(a, 2), nth(a, 3), nth(a, 4)), (nth(a, 5), nth(a, 6), nth(a, 7), nth(a, 8))),
      _ => return Err(Exception::throw_type(&ctx, "drawImage: expected 2, 4 or 8 coordinates")),
    };
    if !finite(&[src.0, src.1, src.2, src.3, dst.0, dst.1, dst.2, dst.3]) {
      return Ok(());
    }
    if src.2 == 0.0 || src.3 == 0.0 || dst.2 == 0.0 || dst.3 == 0.0 {
      return Ok(());
    }
    self.live(&ctx)?;
    if let ImageSource::Canvas(canvas) = &source {
      canvas.flush(&ctx)?;
    }
    let state = self.state.borrow();
    let Some((scratch, _)) = self.prepare_paint(&ctx, &state, Some((&Style::Color(0), false)))? else {
      return Ok(());
    };
    let matrix = state.matrix;
    drop(state);
    let (kind, id) = (source.kind(), source.id());
    self.surface.record(&ctx, move |out| {
      let mut scratch = scratch;
      out.sources.push(source);
      out.u8(CMD_IMAGE);
      out.matrix(matrix);
      out.paint(&mut scratch);
      out.u8(kind);
      out.i64(id);
      for value in [src.0, src.1, src.2, src.3, dst.0, dst.1, dst.2, dst.3] {
        out.f(value);
      }
    })
  }
}

impl Context2d {
  fn draw_rect(
    &self,
    ctx: &Ctx<'_>,
    command: u8,
    kind: Option<PaintKind>,
    (x, y, w, h): (f64, f64, f64, f64),
  ) -> JsResult<()> {
    if !finite(&[x, y, w, h]) || w == 0.0 || h == 0.0 {
      return Ok(());
    }
    let m = self.state.borrow().matrix;
    self.draw_path(ctx, command, kind, 0, &rect_path(m, x, y, w, h))
  }

  fn draw_text(
    &self,
    ctx: &Ctx<'_>,
    kind: PaintKind,
    text: Opt<Coerced<String>>,
    (x, y): (f64, f64),
    max_width: Opt<Coerced<f64>>,
  ) -> JsResult<()> {
    let Some(text) = text.0 else { return Ok(()) };
    if !finite(&[x, y]) {
      return Ok(());
    }
    let max_width = max_width.0.map(|v| v.0).filter(|v| v.is_finite() && *v > 0.0);
    self.live(ctx)?;
    let state = self.state.borrow();
    let Some((scratch, _)) = self.prepare_paint(ctx, &state, Some(state.select_paint_style(kind)))? else {
      return Ok(());
    };
    let matrix = state.matrix;
    let font = state.font.to_wire();
    let (align, baseline) = (state.text_align, state.text_baseline);
    drop(state);
    let text = text.0;
    self.surface.record(ctx, move |out| {
      let mut scratch = scratch;
      out.u8(CMD_TEXT);
      out.matrix(matrix);
      out.u8(u8::from(kind == PaintKind::Stroke));
      out.paint(&mut scratch);
      let font = out.string(&font);
      let text = out.string(&text);
      out.u32(font);
      out.u8(align);
      out.u8(baseline);
      out.f(x);
      out.f(y);
      out.f(max_width.unwrap_or(-1.0));
      out.u32(text);
    })
  }
}

fn make_gradient<'js>(ctx: &Ctx<'js>, kind: u8, coords: [f64; 6], used: usize) -> JsResult<Value<'js>> {
  if !finite(&coords[..used]) {
    return Err(Exception::throw_type(ctx, "a gradient needs finite coordinates"));
  }
  let data = Rc::new(GradientData {
    kind,
    coords,
    stops: RefCell::new(Vec::new()),
  });
  Ok(Class::instance(ctx.clone(), GradientHandle { gradient: data })?.into_value())
}

fn image_source<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<ImageSource> {
  if let Ok(image) = Class::<ImageHandle>::from_value(value) {
    let data = image.borrow().image.clone();
    if !data.alive.get() {
      return PluginErrorCode::HandleExpired.throw(ctx, "this image was disposed");
    }
    return Ok(ImageSource::Bitmap(data));
  }
  if let Ok(canvas) = Class::<CanvasHandle>::from_value(value) {
    let surface = canvas.borrow().surface.clone();
    if !surface.alive.get() {
      return PluginErrorCode::HandleExpired.throw(ctx, "this canvas is gone");
    }
    return Ok(ImageSource::Canvas(surface));
  }
  Err(Exception::throw_type(ctx, "expected an ImageBitmap or an OffscreenCanvas"))
}

fn truthy(value: Option<&Value<'_>>) -> bool {
  value.map(|v| v.as_bool().unwrap_or(!v.is_undefined() && !v.is_null())).unwrap_or(false)
}

fn arc_result(ctx: &Ctx<'_>, result: Result<(), ArcError>) -> JsResult<()> {
  match result {
    Ok(()) => Ok(()),
    Err(ArcError::NegativeRadius) => Err(Exception::throw_dom(ctx, "IndexSizeError", "a radius must not be negative")),
  }
}

fn read_radii<'js>(ctx: &Ctx<'js>, value: &Opt<Value<'js>>) -> JsResult<Option<[(f64, f64); 4]>> {
  let Some(value) = value.0.as_ref().filter(|v| !v.is_undefined() && !v.is_null()) else {
    return Ok(Some([(0.0, 0.0); 4]));
  };
  let values = match value.as_array() {
    Some(array) => {
      let mut values = Vec::new();
      for entry in array_values(ctx, array, "roundRect")? {
        values.push(Coerced::<f64>::from_js(ctx, entry)?.0);
      }
      values
    }
    None => vec![Coerced::<f64>::from_js(ctx, value.clone())?.0],
  };
  let [tl, tr, br, bl] = match values[..] {
    [all] => [all; 4],
    [a, b] => [a, b, a, b],
    [a, b, c] => [a, b, c, b],
    [a, b, c, d] => [a, b, c, d],
    _ => return Err(Exception::throw_range(ctx, "roundRect: expected 1 to 4 corner radii")),
  };
  for radius in &values {
    if !radius.is_finite() {
      return Ok(None);
    }
    if *radius < 0.0 {
      return Err(Exception::throw_range(ctx, "a corner radius must not be negative"));
    }
  }
  Ok(Some([(tl, tl), (tr, tr), (br, br), (bl, bl)]))
}

#[rquickjs::methods(rename_all = "camelCase")]
impl<'js> AnimationHandle {
  #[qjs(get, enumerable, configurable, rename = "width")]
  fn get_width(&self) -> i32 {
    self.animation.width
  }

  #[qjs(get, enumerable, configurable, rename = "height")]
  fn get_height(&self) -> i32 {
    self.animation.height
  }

  #[qjs(get, enumerable, configurable, rename = "frameCount")]
  fn get_frame_count(&self) -> i32 {
    self.animation.frame_count
  }

  #[qjs(get, enumerable, configurable, rename = "duration")]
  fn get_duration(&self) -> i32 {
    self.animation.duration
  }

  #[qjs(get, enumerable, configurable, rename = "fps")]
  fn get_fps(&self) -> i32 {
    self.animation.fps
  }

  fn frame(&self, ctx: Ctx<'js>, index: Opt<Coerced<f64>>) -> JsResult<Value<'js>> {
    let animation = &self.animation;
    if !animation.alive.get() {
      return PluginErrorCode::HandleExpired.throw(&ctx, "this animation is gone");
    }
    let index = num(&index);
    if !index.is_finite() || index < 0.0 || index.trunc() as i64 >= animation.frame_count as i64 {
      return PluginErrorCode::InvalidArgument
        .throw(&ctx, &format!("frame: this animation has frames 0 to {}", animation.frame_count.saturating_sub(1)));
    }
    let index = index.trunc() as i32;
    let image = animation.state.blank_image();
    let image_id = image.id;
    let describe = |args: &mut Encoder| {
      args.i64(image_id);
      args.i32(index);
    };
    let kind = PendingKind::Frame { image, sequential: false };
    animation.state.start_op(&ctx, kind, OP_ANIMATION_FRAME, animation.id, &describe, None)
  }

  fn next(&self, ctx: Ctx<'js>) -> JsResult<Value<'js>> {
    let animation = &self.animation;
    if !animation.alive.get() {
      return PluginErrorCode::HandleExpired.throw(&ctx, "this animation is gone");
    }
    let image = animation.state.blank_image();
    let image_id = image.id;
    let describe = |args: &mut Encoder| args.i64(image_id);
    let kind = PendingKind::Frame { image, sequential: true };
    animation.state.start_op(&ctx, kind, OP_ANIMATION_NEXT, animation.id, &describe, None)
  }

  #[qjs(rename = PredefinedAtom::SymbolAsyncIterator)]
  fn async_iterator(this: This<Value<'js>>) -> Value<'js> {
    this.0
  }

  fn dispose(&self) {
    self.animation.free();
  }
}

#[rquickjs::methods(rename_all = "camelCase")]
impl<'js> EncoderHandle {
  #[qjs(get, enumerable, configurable, rename = "width")]
  fn get_width(&self) -> i32 {
    self.encoder.width
  }

  #[qjs(get, enumerable, configurable, rename = "height")]
  fn get_height(&self) -> i32 {
    self.encoder.height
  }

  fn add_frame(&self, ctx: Ctx<'js>, source: Opt<Value<'js>>, duration: Opt<Coerced<f64>>) -> JsResult<Value<'js>> {
    let encoder = self.encoder.clone();
    encoder.writable(&ctx)?;
    let frames = encoder.frames.get();
    if frames >= MAX_ENCODER_FRAMES {
      return PluginErrorCode::QuotaExceeded(frames as i64 + 1, MAX_ENCODER_FRAMES as i64)
        .throw(&ctx, &format!("a video may have at most {MAX_ENCODER_FRAMES} frames"));
    }
    let Some(value) = source.0 else {
      return PluginErrorCode::InvalidArgument.throw(&ctx, "addFrame: expected an image");
    };
    let source = image_source(&ctx, &value)?;
    if let ImageSource::Canvas(canvas) = &source {
      canvas.flush(&ctx)?;
    }
    let millis = match duration.0.as_ref().map(|v| v.0) {
      None => 1000.0 / encoder.fps as f64,
      Some(value) if value.is_finite() && value > 0.0 => value,
      Some(_) => {
        return PluginErrorCode::InvalidArgument.throw(&ctx, "addFrame: a frame's duration must be a positive number")
      }
    };
    let (kind, id) = (source.kind(), source.id());
    let describe = |args: &mut Encoder| {
      args.u8(kind);
      args.i64(id);
      args.f(millis);
    };
    let frame = encoder.start_frame(&ctx)?;
    let answer = encoder.state.start_op(
      &ctx,
      PendingKind::EncoderFrame { _frame: frame },
      OP_ENCODER_FRAME,
      encoder.id,
      &describe,
      None,
    )?;
    encoder.frames.set(frames + 1);
    Ok(answer)
  }

  fn finish(&self, ctx: Ctx<'js>) -> JsResult<Value<'js>> {
    let encoder = self.encoder.clone();
    encoder.writable(&ctx)?;
    if encoder.frames.get() == 0 {
      return PluginErrorCode::InvalidArgument.throw(&ctx, "finish: this video has no frames");
    }
    encoder.finished.set(true);
    let state = encoder.state.clone();
    let id = encoder.id;
    state.start_op(&ctx, PendingKind::FinishEncoder { _encoder: encoder }, OP_ENCODER_FINISH, id, &|_| {}, None)
  }

  fn dispose(&self) {
    self.encoder.free();
  }
}

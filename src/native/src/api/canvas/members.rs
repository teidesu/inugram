use crate::{api::error::PluginErrorCode, utils::arguments::array_values};

use super::*;

pub(super) fn install_gradient_members<'js>(ctx: &Ctx<'js>) -> JsResult<()> {
  let proto = Class::<GradientHandle>::prototype(ctx)?
    .ok_or_else(|| Exception::throw_message(ctx, "CanvasGradient: the class has no prototype"))?;
  define_method(
    &proto,
    "addColorStop",
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>,
       this: This<Class<'js, GradientHandle>>,
       offset: Opt<Coerced<f64>>,
       color: Opt<Coerced<String>>|
       -> JsResult<()> {
        let offset = num(&offset);
        if !offset.is_finite() || !(0.0..=1.0).contains(&offset) {
          return invalid(&ctx, "a colour stop's offset must be between 0 and 1");
        }
        let Some(text) = color.0 else {
          return invalid(&ctx, "a colour stop needs a colour");
        };
        let Some(color) = parse_color(&text.0) else {
          return invalid(&ctx, &format!("'{}' is not a colour", text.0));
        };
        let data = this.0.borrow().0.clone();
        let mut stops = data.stops.borrow_mut();
        if stops.len() >= MAX_GRADIENT_STOPS {
          return PluginErrorCode::QuotaExceeded(stops.len() as i64 + 1, MAX_GRADIENT_STOPS as i64)
            .throw(&ctx, &format!("a gradient may have at most {MAX_GRADIENT_STOPS} colour stops"));
        }
        let at = stops.partition_point(|(existing, _)| *existing <= offset);
        stops.insert(at, (offset, color));
        Ok(())
      },
    )?,
  )?;
  Ok(())
}

pub(super) fn install_pattern_members<'js>(ctx: &Ctx<'js>) -> JsResult<()> {
  let proto = Class::<PatternHandle>::prototype(ctx)?
    .ok_or_else(|| Exception::throw_message(ctx, "CanvasPattern: the class has no prototype"))?;
  let f =
    Function::new(ctx.clone(), |this: This<Class<'js, PatternHandle>>, transform: Opt<Value<'js>>| -> JsResult<()> {
      let matrix = matrix_from_init(&transform)?;
      if matrix.is_finite() {
        this.0.borrow().0.transform.set(matrix);
      }
      Ok(())
    })?;
  define_method(&proto, "setTransform", f)?;
  Ok(())
}

fn matrix_from_init(value: &Opt<Value<'_>>) -> JsResult<Matrix> {
  let Some(object) = value.0.as_ref().and_then(|v| v.as_object()) else {
    return Ok(Matrix::IDENTITY);
  };
  let read = |name: &str, default: f64| -> JsResult<f64> {
    Ok(object.get::<_, Option<Coerced<f64>>>(name)?.map(|v| v.0).unwrap_or(default))
  };
  Ok(Matrix {
    a: read("a", 1.0)?,
    b: read("b", 0.0)?,
    c: read("c", 0.0)?,
    d: read("d", 1.0)?,
    e: read("e", 0.0)?,
    f: read("f", 0.0)?,
  })
}

pub(super) fn install_image_members<'js>(ctx: &Ctx<'js>) -> JsResult<()> {
  let proto = Class::<ImageHandle>::prototype(ctx)?
    .ok_or_else(|| Exception::throw_message(ctx, "ImageBitmap: the class has no prototype"))?;
  define_getter(&proto, "width", |this: This<Class<'js, ImageHandle>>| this.0.borrow().0.width)?;
  define_getter(&proto, "height", |this: This<Class<'js, ImageHandle>>| this.0.borrow().0.height)?;
  define_method(
    &proto,
    "dispose",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, this: This<Class<'js, ImageHandle>>| {
      let image = this.0.borrow().0.clone();
      image.release(&ctx)
    })?,
  )?;
  Ok(())
}

pub(super) fn install_context_members<'js>(ctx: &Ctx<'js>) -> JsResult<()> {
  let proto = Class::<Context2d>::prototype(ctx)?
    .ok_or_else(|| Exception::throw_message(ctx, "CanvasRenderingContext2D: the class has no prototype"))?;

  install_state_members(ctx, &proto)?;
  install_transform_members(ctx, &proto)?;
  install_style_members(ctx, &proto)?;
  install_path_members(ctx, &proto)?;
  install_rect_members(ctx, &proto)?;
  install_text_members(ctx, &proto)?;
  install_image_draw_members(ctx, &proto)?;
  Ok(())
}

pub(super) fn install_state_members<'js>(ctx: &Ctx<'js>, proto: &Object<'js>) -> JsResult<()> {
  define_method(
    proto,
    "save",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, this: This<Class<'js, Context2d>>| -> JsResult<()> {
      let this = this.0.borrow();
      this.live(&ctx)?;
      this.save();
      this.surface.record(&ctx, |out| out.u8(CMD_SAVE))
    })?,
  )?;
  define_method(
    proto,
    "restore",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, this: This<Class<'js, Context2d>>| -> JsResult<()> {
      let this = this.0.borrow();
      this.live(&ctx)?;
      if !this.restore() {
        return Ok(());
      }
      this.surface.record(&ctx, |out| out.u8(CMD_RESTORE))
    })?,
  )?;
  define_method(
    proto,
    "reset",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, this: This<Class<'js, Context2d>>| -> JsResult<()> {
      let this = this.0.borrow();
      this.live(&ctx)?;
      this.reset();
      this.surface.record(&ctx, |out| out.u8(CMD_RESET))
    })?,
  )?;
  Ok(())
}

pub(super) fn install_transform_members<'js>(ctx: &Ctx<'js>, proto: &Object<'js>) -> JsResult<()> {
  define_method(
    proto,
    "scale",
    Function::new(ctx.clone(), |this: This<Class<'js, Context2d>>, x: Opt<Coerced<f64>>, y: Opt<Coerced<f64>>| {
      let (x, y) = (num(&x), num(&y));
      if finite(&[x, y]) {
        let this = this.0.borrow();
        let next = this.state.borrow().matrix.scaled(x, y);
        this.state.borrow_mut().matrix = next;
      }
    })?,
  )?;
  define_method(
    proto,
    "rotate",
    Function::new(ctx.clone(), |this: This<Class<'js, Context2d>>, angle: Opt<Coerced<f64>>| {
      let angle = num(&angle);
      if angle.is_finite() {
        let this = this.0.borrow();
        let next = this.state.borrow().matrix.rotated(angle);
        this.state.borrow_mut().matrix = next;
      }
    })?,
  )?;
  define_method(
    proto,
    "translate",
    Function::new(ctx.clone(), |this: This<Class<'js, Context2d>>, x: Opt<Coerced<f64>>, y: Opt<Coerced<f64>>| {
      let (x, y) = (num(&x), num(&y));
      if finite(&[x, y]) {
        let this = this.0.borrow();
        let next = this.state.borrow().matrix.translated(x, y);
        this.state.borrow_mut().matrix = next;
      }
    })?,
  )?;
  define_method(
    proto,
    "transform",
    Function::new(ctx.clone(), |this: This<Class<'js, Context2d>>, args: Rest<Value<'js>>| {
      let v = &args.0;
      let m = Matrix {
        a: nth(v, 0),
        b: nth(v, 1),
        c: nth(v, 2),
        d: nth(v, 3),
        e: nth(v, 4),
        f: nth(v, 5),
      };
      if m.is_finite() {
        let this = this.0.borrow();
        let next = this.state.borrow().matrix.multiply(&m);
        this.state.borrow_mut().matrix = next;
      }
    })?,
  )?;
  define_method(
    proto,
    "setTransform",
    Function::new(ctx.clone(), |this: This<Class<'js, Context2d>>, args: Rest<Value<'js>>| -> JsResult<()> {
      let v = &args.0;
      let m = match v.first() {
        Some(first) if first.is_object() => matrix_from_init(&Opt(Some(first.clone())))?,
        None => Matrix::IDENTITY,
        _ => Matrix {
          a: nth(v, 0),
          b: nth(v, 1),
          c: nth(v, 2),
          d: nth(v, 3),
          e: nth(v, 4),
          f: nth(v, 5),
        },
      };
      if m.is_finite() {
        this.0.borrow().state.borrow_mut().matrix = m;
      }
      Ok(())
    })?,
  )?;
  define_method(
    proto,
    "resetTransform",
    Function::new(ctx.clone(), |this: This<Class<'js, Context2d>>| {
      this.0.borrow().state.borrow_mut().matrix = Matrix::IDENTITY;
    })?,
  )?;
  Ok(())
}

pub(super) fn install_style_members<'js>(ctx: &Ctx<'js>, proto: &Object<'js>) -> JsResult<()> {
  define_accessor(
    proto,
    "globalAlpha",
    |this: This<Class<'js, Context2d>>| this.0.borrow().state.borrow().alpha,
    |this: This<Class<'js, Context2d>>, value: Coerced<f64>| {
      if value.0.is_finite() && (0.0..=1.0).contains(&value.0) {
        this.0.borrow().state.borrow_mut().alpha = value.0;
      }
    },
  )?;
  define_accessor(
    proto,
    "globalCompositeOperation",
    |this: This<Class<'js, Context2d>>| COMPOSITE_MODES[this.0.borrow().state.borrow().composite as usize].to_string(),
    |this: This<Class<'js, Context2d>>, value: Coerced<String>| {
      if let Some(index) = index_of(&COMPOSITE_MODES, &value.0) {
        this.0.borrow().state.borrow_mut().composite = index;
      }
    },
  )?;

  for (name, is_stroke) in [("fillStyle", false), ("strokeStyle", true)] {
    define_accessor(
      proto,
      name,
      move |ctx: Ctx<'js>, this: This<Class<'js, Context2d>>| -> JsResult<Value<'js>> {
        let this = this.0.borrow();
        let state = this.state.borrow();
        style_to_value(&ctx, if is_stroke { &state.stroke } else { &state.fill })
      },
      move |ctx: Ctx<'js>, this: This<Class<'js, Context2d>>, value: Value<'js>| -> JsResult<()> {
        let Some(style) = style_from_value(&ctx, &value)? else {
          return Ok(());
        };
        let this = this.0.borrow();
        let mut state = this.state.borrow_mut();
        if is_stroke {
          state.stroke = style;
        } else {
          state.fill = style;
        }
        Ok(())
      },
    )?;
  }

  define_accessor(
    proto,
    "lineWidth",
    |this: This<Class<'js, Context2d>>| this.0.borrow().state.borrow().line_width,
    |this: This<Class<'js, Context2d>>, value: Coerced<f64>| {
      if value.0.is_finite() && value.0 > 0.0 {
        this.0.borrow().state.borrow_mut().line_width = value.0;
      }
    },
  )?;
  define_accessor(
    proto,
    "miterLimit",
    |this: This<Class<'js, Context2d>>| this.0.borrow().state.borrow().miter_limit,
    |this: This<Class<'js, Context2d>>, value: Coerced<f64>| {
      if value.0.is_finite() && value.0 > 0.0 {
        this.0.borrow().state.borrow_mut().miter_limit = value.0;
      }
    },
  )?;
  define_accessor(
    proto,
    "lineDashOffset",
    |this: This<Class<'js, Context2d>>| this.0.borrow().state.borrow().dash_offset,
    |this: This<Class<'js, Context2d>>, value: Coerced<f64>| {
      if value.0.is_finite() {
        this.0.borrow().state.borrow_mut().dash_offset = value.0;
      }
    },
  )?;
  define_accessor(
    proto,
    "lineCap",
    |this: This<Class<'js, Context2d>>| LINE_CAPS[this.0.borrow().state.borrow().line_cap as usize].to_string(),
    |this: This<Class<'js, Context2d>>, value: Coerced<String>| {
      if let Some(index) = index_of(&LINE_CAPS, &value.0) {
        this.0.borrow().state.borrow_mut().line_cap = index;
      }
    },
  )?;
  define_accessor(
    proto,
    "lineJoin",
    |this: This<Class<'js, Context2d>>| LINE_JOINS[this.0.borrow().state.borrow().line_join as usize].to_string(),
    |this: This<Class<'js, Context2d>>, value: Coerced<String>| {
      if let Some(index) = index_of(&LINE_JOINS, &value.0) {
        this.0.borrow().state.borrow_mut().line_join = index;
      }
    },
  )?;
  define_accessor(
    proto,
    "shadowBlur",
    |this: This<Class<'js, Context2d>>| this.0.borrow().state.borrow().shadow_blur,
    |this: This<Class<'js, Context2d>>, value: Coerced<f64>| {
      if value.0.is_finite() && value.0 >= 0.0 {
        this.0.borrow().state.borrow_mut().shadow_blur = value.0;
      }
    },
  )?;
  define_accessor(
    proto,
    "shadowColor",
    |this: This<Class<'js, Context2d>>| format_color(this.0.borrow().state.borrow().shadow_color),
    |this: This<Class<'js, Context2d>>, value: Coerced<String>| {
      if let Some(color) = parse_color(&value.0) {
        this.0.borrow().state.borrow_mut().shadow_color = color;
      }
    },
  )?;
  for (name, vertical) in [("shadowOffsetX", false), ("shadowOffsetY", true)] {
    define_accessor(
      proto,
      name,
      move |this: This<Class<'js, Context2d>>| {
        let this = this.0.borrow();
        let state = this.state.borrow();
        if vertical {
          state.shadow_offset.1
        } else {
          state.shadow_offset.0
        }
      },
      move |this: This<Class<'js, Context2d>>, value: Coerced<f64>| {
        if !value.0.is_finite() {
          return;
        }
        let this = this.0.borrow();
        let mut state = this.state.borrow_mut();
        if vertical {
          state.shadow_offset.1 = value.0;
        } else {
          state.shadow_offset.0 = value.0;
        }
      },
    )?;
  }

  define_method(
    proto,
    "setLineDash",
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>, this: This<Class<'js, Context2d>>, segments: Opt<Value<'js>>| -> JsResult<()> {
        let Some(array) = segments.0.as_ref().and_then(|v| v.as_array()) else {
          return invalid(&ctx, "setLineDash: expected an array of lengths");
        };
        let mut dash = Vec::new();
        for value in crate::utils::arguments::array_values(&ctx, array, "setLineDash")? {
          let value = Coerced::<f64>::from_js(&ctx, value)?.0;
          if !value.is_finite() || value < 0.0 {
            return Ok(());
          }
          dash.push(value);
        }
        if dash.len() % 2 == 1 {
          dash.extend_from_within(..);
        }
        this.0.borrow().state.borrow_mut().dash = dash;
        Ok(())
      },
    )?,
  )?;
  define_method(
    proto,
    "getLineDash",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, this: This<Class<'js, Context2d>>| -> JsResult<Value<'js>> {
      let this = this.0.borrow();
      let dash = this.state.borrow().dash.clone();
      let array = rquickjs::Array::new(ctx.clone())?;
      for (index, value) in dash.into_iter().enumerate() {
        array.set(index, value)?;
      }
      Ok(array.into_value())
    })?,
  )?;

  define_method(
    proto,
    "createLinearGradient",
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>,
       _this: This<Class<'js, Context2d>>,
       x0: Opt<Coerced<f64>>,
       y0: Opt<Coerced<f64>>,
       x1: Opt<Coerced<f64>>,
       y1: Opt<Coerced<f64>>|
       -> JsResult<Value<'js>> {
        let coords = [num(&x0), num(&y0), num(&x1), num(&y1), 0.0, 0.0];
        make_gradient(&ctx, STYLE_LINEAR, coords, 4)
      },
    )?,
  )?;
  define_method(
    proto,
    "createRadialGradient",
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>, _this: This<Class<'js, Context2d>>, args: Rest<Value<'js>>| -> JsResult<Value<'js>> {
        let a = &args.0;
        if nth(a, 2) < 0.0 || nth(a, 5) < 0.0 {
          return invalid(&ctx, "a radial gradient's radii must not be negative");
        }
        let coords = [nth(a, 0), nth(a, 1), nth(a, 2), nth(a, 3), nth(a, 4), nth(a, 5)];
        make_gradient(&ctx, STYLE_RADIAL, coords, 6)
      },
    )?,
  )?;
  define_method(
    proto,
    "createConicGradient",
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>,
       _this: This<Class<'js, Context2d>>,
       angle: Opt<Coerced<f64>>,
       x: Opt<Coerced<f64>>,
       y: Opt<Coerced<f64>>|
       -> JsResult<Value<'js>> {
        let coords = [num(&angle), num(&x), num(&y), 0.0, 0.0, 0.0];
        make_gradient(&ctx, STYLE_CONIC, coords, 3)
      },
    )?,
  )?;
  define_method(
    proto,
    "createPattern",
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>,
       this: This<Class<'js, Context2d>>,
       image: Opt<Value<'js>>,
       repetition: Opt<Value<'js>>|
       -> JsResult<Value<'js>> {
        let repeat = match repetition.0.as_ref() {
          None => 0,
          Some(value) if value.is_null() || value.is_undefined() => 0,
          Some(value) => {
            let Some(text) = value.as_string() else {
              return invalid(&ctx, "createPattern: that is not a repetition");
            };
            let text = text.to_string()?;
            match index_of(&REPETITIONS, &text) {
              Some(index) => index,
              None => return invalid(&ctx, &format!("'{text}' is not a repetition")),
            }
          }
        };
        let Some(value) = image.0 else {
          return invalid(&ctx, "createPattern: expected an image");
        };
        let source = image_source(&ctx, &value)?;
        if let ImageSource::Canvas(canvas) = &source {
          canvas.flush(&ctx)?;
        }
        let _ = this;
        let data = Rc::new(PatternData {
          source,
          repeat,
          transform: Cell::new(Matrix::IDENTITY),
        });
        Ok(Class::instance(ctx.clone(), PatternHandle(data))?.into_value())
      },
    )?,
  )?;
  Ok(())
}

fn make_gradient<'js>(ctx: &Ctx<'js>, kind: u8, coords: [f64; 6], used: usize) -> JsResult<Value<'js>> {
  if !finite(&coords[..used]) {
    return invalid(ctx, "a gradient needs finite coordinates");
  }
  let data = Rc::new(GradientData {
    kind,
    coords,
    stops: RefCell::new(Vec::new()),
  });
  Ok(Class::instance(ctx.clone(), GradientHandle(data))?.into_value())
}

fn image_source<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<ImageSource> {
  if let Ok(image) = Class::<ImageHandle>::from_value(value) {
    let data = image.borrow().0.clone();
    if !data.alive.get() {
      return expired(ctx, "this image was disposed");
    }
    return Ok(ImageSource::Bitmap(data));
  }
  if let Ok(canvas) = Class::<CanvasHandle>::from_value(value) {
    let surface = canvas.borrow().0.clone();
    if !surface.alive.get() {
      return expired(ctx, "this canvas is gone");
    }
    return Ok(ImageSource::Canvas(surface));
  }
  invalid(ctx, "expected an ImageBitmap or an OffscreenCanvas")
}

pub(super) fn install_path_members<'js>(ctx: &Ctx<'js>, proto: &Object<'js>) -> JsResult<()> {
  define_method(
    proto,
    "beginPath",
    Function::new(ctx.clone(), |this: This<Class<'js, Context2d>>| {
      this.0.borrow().path.borrow_mut().clear();
    })?,
  )?;
  define_method(
    proto,
    "closePath",
    Function::new(ctx.clone(), |this: This<Class<'js, Context2d>>| {
      this.0.borrow().path.borrow_mut().close();
    })?,
  )?;
  define_method(
    proto,
    "moveTo",
    Function::new(ctx.clone(), |this: This<Class<'js, Context2d>>, x: Opt<Coerced<f64>>, y: Opt<Coerced<f64>>| {
      let this = this.0.borrow();
      let m = this.state.borrow().matrix;
      this.path.borrow_mut().move_to(&m, num(&x), num(&y));
    })?,
  )?;
  define_method(
    proto,
    "lineTo",
    Function::new(ctx.clone(), |this: This<Class<'js, Context2d>>, x: Opt<Coerced<f64>>, y: Opt<Coerced<f64>>| {
      let this = this.0.borrow();
      let m = this.state.borrow().matrix;
      this.path.borrow_mut().line_to(&m, num(&x), num(&y));
    })?,
  )?;
  define_method(
    proto,
    "bezierCurveTo",
    Function::new(ctx.clone(), |this: This<Class<'js, Context2d>>, args: Rest<Value<'js>>| {
      let a = &args.0;
      let this = this.0.borrow();
      let m = this.state.borrow().matrix;
      this
        .path
        .borrow_mut()
        .cubic_to(&m, nth(a, 0), nth(a, 1), nth(a, 2), nth(a, 3), nth(a, 4), nth(a, 5));
    })?,
  )?;
  define_method(
    proto,
    "quadraticCurveTo",
    Function::new(
      ctx.clone(),
      |this: This<Class<'js, Context2d>>,
       cx: Opt<Coerced<f64>>,
       cy: Opt<Coerced<f64>>,
       x: Opt<Coerced<f64>>,
       y: Opt<Coerced<f64>>| {
        let this = this.0.borrow();
        let m = this.state.borrow().matrix;
        let inverse = m.invert();
        this.path.borrow_mut().quad_to(&m, num(&cx), num(&cy), num(&x), num(&y), inverse.as_ref());
      },
    )?,
  )?;
  define_method(
    proto,
    "arc",
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>, this: This<Class<'js, Context2d>>, args: Rest<Value<'js>>| -> JsResult<()> {
        let a = &args.0;
        let this = this.0.borrow();
        let m = this.state.borrow().matrix;
        let result =
          this
            .path
            .borrow_mut()
            .arc(&m, nth(a, 0), nth(a, 1), nth(a, 2), nth(a, 3), nth(a, 4), truthy(a.get(5)));
        arc_result(&ctx, result)
      },
    )?,
  )?;
  define_method(
    proto,
    "ellipse",
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>, this: This<Class<'js, Context2d>>, args: Rest<Value<'js>>| -> JsResult<()> {
        let a = &args.0;
        let this = this.0.borrow();
        let m = this.state.borrow().matrix;
        let result = this.path.borrow_mut().ellipse(
          &m,
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
      },
    )?,
  )?;
  define_method(
    proto,
    "arcTo",
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>, this: This<Class<'js, Context2d>>, args: Rest<Value<'js>>| -> JsResult<()> {
        let a = &args.0;
        let this = this.0.borrow();
        let m = this.state.borrow().matrix;
        let Some(inverse) = m.invert() else {
          return Ok(());
        };
        let result = this.path.borrow_mut().arc_to(&m, &inverse, nth(a, 0), nth(a, 1), nth(a, 2), nth(a, 3), nth(a, 4));
        arc_result(&ctx, result)
      },
    )?,
  )?;
  define_method(
    proto,
    "rect",
    Function::new(
      ctx.clone(),
      |this: This<Class<'js, Context2d>>,
       x: Opt<Coerced<f64>>,
       y: Opt<Coerced<f64>>,
       w: Opt<Coerced<f64>>,
       h: Opt<Coerced<f64>>| {
        let this = this.0.borrow();
        let m = this.state.borrow().matrix;
        this.path.borrow_mut().rect(&m, num(&x), num(&y), num(&w), num(&h));
      },
    )?,
  )?;
  define_method(
    proto,
    "roundRect",
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>,
       this: This<Class<'js, Context2d>>,
       x: Opt<Coerced<f64>>,
       y: Opt<Coerced<f64>>,
       w: Opt<Coerced<f64>>,
       h: Opt<Coerced<f64>>,
       radii: Opt<Value<'js>>|
       -> JsResult<()> {
        let corners = read_radii(&ctx, &radii)?;
        let this = this.0.borrow();
        let m = this.state.borrow().matrix;
        let (x, y, w, h) = (num(&x), num(&y), num(&w), num(&h));
        if !finite(&[x, y, w, h]) {
          return Ok(());
        }
        let (x, y, w, h, corners) = normalize_round_rect(x, y, w, h, corners);
        this.path.borrow_mut().round_rect(&m, x, y, w, h, corners);
        Ok(())
      },
    )?,
  )?;

  for (name, command, kind) in [("fill", CMD_FILL, Some(PaintKind::Fill)), ("clip", CMD_CLIP, None)] {
    define_method(
      proto,
      name,
      Function::new(
        ctx.clone(),
        move |ctx: Ctx<'js>, this: This<Class<'js, Context2d>>, rule: Opt<Value<'js>>| -> JsResult<()> {
          let rule = fill_rule_of(&ctx, rule)?;
          let this = this.0.borrow();
          let path = this.path.borrow().clone();
          this.draw_path(&ctx, command, kind, rule, &path)
        },
      )?,
    )?;
  }
  define_method(
    proto,
    "stroke",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, this: This<Class<'js, Context2d>>| -> JsResult<()> {
      let this = this.0.borrow();
      let path = this.path.borrow().clone();
      this.draw_path(&ctx, CMD_STROKE, Some(PaintKind::Stroke), 0, &path)
    })?,
  )?;
  Ok(())
}

fn truthy(value: Option<&Value<'_>>) -> bool {
  value.map(|v| v.as_bool().unwrap_or(!v.is_undefined() && !v.is_null())).unwrap_or(false)
}

fn arc_result(ctx: &Ctx<'_>, result: Result<(), ArcError>) -> JsResult<()> {
  match result {
    Ok(()) => Ok(()),
    Err(ArcError::NegativeRadius) => invalid(ctx, "a radius must not be negative"),
  }
}

fn read_radii<'js>(ctx: &Ctx<'js>, value: &Opt<Value<'js>>) -> JsResult<[(f64, f64); 4]> {
  let Some(value) = value.0.as_ref() else {
    return Ok([(0.0, 0.0); 4]);
  };
  if value.is_undefined() || value.is_null() {
    return Ok([(0.0, 0.0); 4]);
  }
  if let Some(array) = value.as_array() {
    let mut values = Vec::new();
    for entry in array_values(ctx, array, "roundRect")? {
      let entry = Coerced::<f64>::from_js(ctx, entry)?.0;
      if !entry.is_finite() || entry < 0.0 {
        return invalid(ctx, "a corner radius must be a non-negative number");
      }
      values.push(entry);
    }
    let [tl, tr, br, bl] = match values.len() {
      0 => [0.0; 4],
      1 => [values[0]; 4],
      2 => [values[0], values[1], values[0], values[1]],
      3 => [values[0], values[1], values[2], values[1]],
      _ => [values[0], values[1], values[2], values[3]],
    };
    return Ok([(tl, tl), (tr, tr), (br, br), (bl, bl)]);
  }
  let single = Coerced::<f64>::from_js(ctx, value.clone())?.0;
  if !single.is_finite() || single < 0.0 {
    return invalid(ctx, "a corner radius must be a non-negative number");
  }
  Ok([(single, single); 4])
}

pub(super) fn install_rect_members<'js>(ctx: &Ctx<'js>, proto: &Object<'js>) -> JsResult<()> {
  for (name, command, kind) in [
    ("clearRect", CMD_CLEAR, None),
    ("fillRect", CMD_FILL, Some(PaintKind::Fill)),
    ("strokeRect", CMD_STROKE, Some(PaintKind::Stroke)),
  ] {
    define_method(
      proto,
      name,
      Function::new(
        ctx.clone(),
        move |ctx: Ctx<'js>,
              this: This<Class<'js, Context2d>>,
              x: Opt<Coerced<f64>>,
              y: Opt<Coerced<f64>>,
              w: Opt<Coerced<f64>>,
              h: Opt<Coerced<f64>>|
              -> JsResult<()> {
          let (x, y, w, h) = (num(&x), num(&y), num(&w), num(&h));
          if !finite(&[x, y, w, h]) || w == 0.0 || h == 0.0 {
            return Ok(());
          }
          let this = this.0.borrow();
          let m = this.state.borrow().matrix;
          let path = rect_path(&m, x, y, w, h);
          this.draw_path(&ctx, command, kind, 0, &path)
        },
      )?,
    )?;
  }
  Ok(())
}

pub(super) fn install_text_members<'js>(ctx: &Ctx<'js>, proto: &Object<'js>) -> JsResult<()> {
  define_accessor(
    proto,
    "font",
    |this: This<Class<'js, Context2d>>| this.0.borrow().state.borrow().font_source.clone(),
    |this: This<Class<'js, Context2d>>, value: Coerced<String>| {
      if let Some(font) = parse_font(&value.0) {
        let this = this.0.borrow();
        let mut state = this.state.borrow_mut();
        state.font = font;
        state.font_source = value.0;
      }
    },
  )?;
  define_accessor(
    proto,
    "textAlign",
    |this: This<Class<'js, Context2d>>| TEXT_ALIGNS[this.0.borrow().state.borrow().text_align as usize].to_string(),
    |this: This<Class<'js, Context2d>>, value: Coerced<String>| {
      if let Some(index) = index_of(&TEXT_ALIGNS, &value.0) {
        this.0.borrow().state.borrow_mut().text_align = index;
      }
    },
  )?;
  define_accessor(
    proto,
    "textBaseline",
    |this: This<Class<'js, Context2d>>| {
      TEXT_BASELINES[this.0.borrow().state.borrow().text_baseline as usize].to_string()
    },
    |this: This<Class<'js, Context2d>>, value: Coerced<String>| {
      if let Some(index) = index_of(&TEXT_BASELINES, &value.0) {
        this.0.borrow().state.borrow_mut().text_baseline = index;
      }
    },
  )?;

  for (name, kind) in [("fillText", PaintKind::Fill), ("strokeText", PaintKind::Stroke)] {
    define_method(
      proto,
      name,
      Function::new(
        ctx.clone(),
        move |ctx: Ctx<'js>,
              this: This<Class<'js, Context2d>>,
              text: Opt<Coerced<String>>,
              x: Opt<Coerced<f64>>,
              y: Opt<Coerced<f64>>,
              max_width: Opt<Coerced<f64>>|
              -> JsResult<()> {
          let Some(text) = text.0 else { return Ok(()) };
          let (x, y) = (num(&x), num(&y));
          if !finite(&[x, y]) {
            return Ok(());
          }
          let max_width = max_width.0.map(|v| v.0).filter(|v| v.is_finite() && *v > 0.0);
          this.0.borrow().draw_text(&ctx, kind, &text.0, x, y, max_width)
        },
      )?,
    )?;
  }

  define_method(
    proto,
    "measureText",
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>, this: This<Class<'js, Context2d>>, text: Opt<Coerced<String>>| -> JsResult<Value<'js>> {
        let text = text.0.map(|v| v.0).unwrap_or_default();
        let this = this.0.borrow();
        this.live(&ctx)?;
        let (font, align) = {
          let state = this.state.borrow();
          (state.font.to_wire(), state.text_align)
        };
        let arg = format!("{font}{FIELD}{align}{FIELD}{text}");
        let answer = this.surface.state.host.canvas(OP_MEASURE, 0, &arg, None);
        let json = match answer.strip_prefix('J') {
          Some(json) => json,
          None => {
            throw_host_error(&ctx, &answer)?;
            return PluginErrorCode::Internal.throw(&ctx, "measureText: the host said nothing");
          }
        };
        ctx.json_parse(json)
      },
    )?,
  )?;

  define_method(
    proto,
    "getAverageColor",
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>,
       this: This<Class<'js, Context2d>>,
       sx: Opt<Coerced<f64>>,
       sy: Opt<Coerced<f64>>,
       sw: Opt<Coerced<f64>>,
       sh: Opt<Coerced<f64>>|
       -> JsResult<Value<'js>> {
        let this = this.0.borrow();
        this.live(&ctx)?;
        let region = if sx.0.is_none() && sy.0.is_none() && sw.0.is_none() && sh.0.is_none() {
          (0.0, 0.0, this.surface.width.get() as f64, this.surface.height.get() as f64)
        } else {
          let region = (num(&sx), num(&sy), num(&sw), num(&sh));
          if !finite(&[region.0, region.1, region.2, region.3]) {
            return invalid(&ctx, "getAverageColor: the region must be four finite numbers");
          }
          region
        };
        this.surface.flush(&ctx)?;
        let arg = format!("{},{},{},{}", region.0, region.1, region.2, region.3);
        let answer = this.surface.state.host.canvas(OP_AVERAGE, this.surface.id, &arg, None);
        let json = match answer.strip_prefix('J') {
          Some(json) => json,
          None => {
            throw_host_error(&ctx, &answer)?;
            return PluginErrorCode::Internal.throw(&ctx, "getAverageColor: the host said nothing");
          }
        };
        ctx.json_parse(json)
      },
    )?,
  )?;
  Ok(())
}

impl Context2d {
  fn draw_text(
    &self,
    ctx: &Ctx<'_>,
    kind: PaintKind,
    text: &str,
    x: f64,
    y: f64,
    max_width: Option<f64>,
  ) -> JsResult<()> {
    self.live(ctx)?;
    let state = self.state.borrow();
    let Some(inverse) = state.matrix.invert() else {
      return Ok(());
    };
    let blend_modes = self.surface.state.blend_modes.get();
    let style = if kind == PaintKind::Fill { &state.fill } else { &state.stroke };
    let mut scratch = Encoder::default();
    scratch.encode_paint(ctx, &state, style, &inverse, blend_modes)?;
    if kind == PaintKind::Stroke {
      scratch.encode_stroke(&state);
    }
    let matrix = state.matrix;
    let font = state.font.to_wire();
    let (align, baseline) = (state.text_align, state.text_baseline);
    drop(state);
    let text = text.to_string();
    self.surface.record(ctx, move |out| {
      let mut scratch = scratch;
      out.u8(CMD_TEXT);
      out.matrix(&matrix);
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

pub(super) fn install_image_draw_members<'js>(ctx: &Ctx<'js>, proto: &Object<'js>) -> JsResult<()> {
  define_method(
    proto,
    "drawImage",
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>, this: This<Class<'js, Context2d>>, args: Rest<Value<'js>>| -> JsResult<()> {
        let a = &args.0;
        let Some(value) = a.first().cloned() else {
          return invalid(&ctx, "drawImage: expected an image");
        };
        let source = image_source(&ctx, &value)?;
        let (iw, ih) = source.size();
        let (src, dst) = match a.len() - 1 {
          2 => ((0.0, 0.0, iw, ih), (nth(a, 1), nth(a, 2), iw, ih)),
          4 => ((0.0, 0.0, iw, ih), (nth(a, 1), nth(a, 2), nth(a, 3), nth(a, 4))),
          8 => ((nth(a, 1), nth(a, 2), nth(a, 3), nth(a, 4)), (nth(a, 5), nth(a, 6), nth(a, 7), nth(a, 8))),
          _ => return invalid(&ctx, "drawImage: expected 2, 4 or 8 coordinates"),
        };
        if !finite(&[src.0, src.1, src.2, src.3, dst.0, dst.1, dst.2, dst.3]) {
          return Ok(());
        }
        if src.2 == 0.0 || src.3 == 0.0 {
          return invalid(&ctx, "drawImage: the source rectangle is empty");
        }
        if dst.2 == 0.0 || dst.3 == 0.0 {
          return Ok(());
        }
        let this = this.0.borrow();
        this.live(&ctx)?;
        if let ImageSource::Canvas(canvas) = &source {
          canvas.flush(&ctx)?;
        }
        let state = this.state.borrow();
        let Some(inverse) = state.matrix.invert() else {
          return Ok(());
        };
        let blend_modes = this.surface.state.blend_modes.get();
        let mut scratch = Encoder::default();
        scratch.encode_paint(&ctx, &state, &Style::Color(0), &inverse, blend_modes)?;
        let matrix = state.matrix;
        drop(state);
        let (kind, id) = (source.kind(), source.id());
        this.surface.record(&ctx, move |out| {
          let mut scratch = scratch;
          out.sources.push(source);
          out.u8(CMD_IMAGE);
          out.matrix(&matrix);
          out.paint(&mut scratch);
          out.u8(kind);
          out.i64(id);
          for value in [src.0, src.1, src.2, src.3, dst.0, dst.1, dst.2, dst.3] {
            out.f(value);
          }
        })
      },
    )?,
  )?;
  Ok(())
}

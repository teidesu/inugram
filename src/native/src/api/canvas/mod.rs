pub(crate) mod css;
pub(crate) mod geometry;

use crate::runtime::Dispose;
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::path::PathBuf;
use std::rc::{Rc, Weak};

use kurbo::{Affine, PathEl, Point, Vec2};
use rquickjs::class::Trace;
use rquickjs::function::{Opt, Rest, This};
use rquickjs::object::Property;
use rquickjs::{Class, Coerced, Ctx, Exception, FromJs, Function, JsLifetime, Object, Result as JsResult, Value};

use crate::api::canvas::css::{parse_color, parse_font, Font};
use crate::api::canvas::geometry::{finite, invert, normalize_round_rect, ArcError, Path};
use crate::api::error::{throw_wire_error, PluginErrorCode};
use crate::api::io::blob::{mint_app_file_at, BUILD_LIMIT_BYTES};
use crate::api::io::fs::FsState;
use crate::api::io::staging::StagedFile;
use crate::api::io::staging::{SourceStager, StagedSource};
use crate::runtime::{enter_js, pump_jobs, Parked, PendingTable};
use crate::sandbox::limits::{ExternalCharge, ExternalMemory};
use crate::sandbox::registry::RequestIds;
use crate::utils::shape::alias_dispose;

pub const MAX_DIMENSION: i32 = 8192;

pub const MAX_GRADIENT_STOPS: usize = 256;

pub const MAX_SOURCE_BYTES: u64 = BUILD_LIMIT_BYTES;

const FLUSH_AT_BYTES: usize = 1024 * 1024;

pub const OP_CREATE: i32 = 0;
pub const OP_DESTROY: i32 = 1;
pub const OP_REPLAY: i32 = 2;
pub const OP_MEASURE: i32 = 3;
pub const OP_AVERAGE: i32 = 4;
pub const OP_ENCODE: i32 = 5;
pub const OP_DECODE: i32 = 6;
pub const OP_RELEASE_IMAGE: i32 = 7;
pub const OP_LOAD_FONT: i32 = 8;
pub const OP_CAPABILITIES: i32 = 9;
pub const OP_DECODE_ANIMATION: i32 = 10;
pub const OP_ANIMATION_FRAME: i32 = 11;
pub const OP_RELEASE_ANIMATION: i32 = 12;
pub const OP_ENCODER_CREATE: i32 = 13;
pub const OP_ENCODER_FRAME: i32 = 14;
pub const OP_ENCODER_FINISH: i32 = 15;
pub const OP_ENCODER_DESTROY: i32 = 16;
pub const OP_ANIMATION_NEXT: i32 = 17;
pub const OP_LIST_FONTS: i32 = 18;

pub const MAX_ANIMATIONS: usize = 4;
pub const MAX_ENCODERS: usize = 2;
pub const MAX_ENCODER_FRAMES: u32 = 3600;
pub const MAX_FPS: i32 = 120;
pub const MAX_ENCODER_BITRATE: i64 = 100_000_000;

const DEFAULT_ENCODER_FPS: i32 = 12;

// PluginVideoEncoder retains three pixel buffers and one bitmap for scaling.
const ENCODER_SPARE_BUFFERS: usize = 3;

const FIELD: char = '\u{1e}';
const ITEM: char = '\u{1f}';

fn encode_table(strings: &[String]) -> String {
  let mut out = String::new();
  for value in strings {
    out.push_str(&value.encode_utf16().count().to_string());
    out.push(ITEM);
    out.push_str(value);
  }
  out
}

pub trait CanvasHost {
  fn canvas(&self, op: i32, id: i64, arg: &str, bytes: Option<&[u8]>) -> String;
}

fn ask(host: &dyn CanvasHost, op: i32, id: i64, fill: impl FnOnce(&mut Encoder)) -> String {
  let mut args = Encoder::default();
  fill(&mut args);
  host.canvas(op, id, &encode_table(&args.strings), Some(&args.bytes))
}

const COMPOSITE_MODES: [&str; 26] = [
  "source-over",
  "source-in",
  "source-out",
  "source-atop",
  "destination-over",
  "destination-in",
  "destination-out",
  "destination-atop",
  "lighter",
  "copy",
  "xor",
  "multiply",
  "screen",
  "overlay",
  "darken",
  "lighten",
  "color-dodge",
  "color-burn",
  "hard-light",
  "soft-light",
  "difference",
  "exclusion",
  "hue",
  "saturation",
  "color",
  "luminosity",
];

const FIRST_BLEND_MODE: usize = 11;

const LINE_CAPS: [&str; 3] = ["butt", "round", "square"];
const LINE_JOINS: [&str; 3] = ["round", "bevel", "miter"];
const TEXT_ALIGNS: [&str; 5] = ["start", "end", "left", "right", "center"];
const TEXT_BASELINES: [&str; 6] = ["top", "hanging", "middle", "alphabetic", "ideographic", "bottom"];
const REPETITIONS: [&str; 4] = ["repeat", "repeat-x", "repeat-y", "no-repeat"];

fn find_table_index(table: &[&str], value: &str) -> Option<u8> {
  table.iter().position(|v| *v == value).map(|i| i as u8)
}

const CMD_SAVE: u8 = 0;
const CMD_RESTORE: u8 = 1;
const CMD_RESET: u8 = 2;
const CMD_CLIP: u8 = 3;
const CMD_FILL: u8 = 4;
const CMD_STROKE: u8 = 5;
const CMD_CLEAR: u8 = 6;
const CMD_TEXT: u8 = 7;
const CMD_IMAGE: u8 = 8;

const STYLE_COLOR: u8 = 0;
const STYLE_LINEAR: u8 = 1;
const STYLE_RADIAL: u8 = 2;
const STYLE_CONIC: u8 = 3;
const STYLE_PATTERN: u8 = 4;

const SOURCE_IMAGE: u8 = 0;
const SOURCE_CANVAS: u8 = 1;

#[derive(Default)]
struct Encoder {
  bytes: Vec<u8>,
  strings: Vec<String>,
  interned: HashMap<String, u32>,
  sources: Vec<ImageSource>,
}

impl Encoder {
  fn is_empty(&self) -> bool {
    self.bytes.is_empty()
  }

  fn clear(&mut self) {
    self.bytes.clear();
    self.strings.clear();
    self.interned.clear();
    self.sources.clear();
  }

  fn u8(&mut self, v: u8) {
    self.bytes.push(v);
  }

  fn u32(&mut self, v: u32) {
    self.bytes.extend_from_slice(&v.to_le_bytes());
  }

  fn i32(&mut self, v: i32) {
    self.bytes.extend_from_slice(&v.to_le_bytes());
  }

  fn i64(&mut self, v: i64) {
    self.bytes.extend_from_slice(&v.to_le_bytes());
  }

  fn f(&mut self, v: f64) {
    self.bytes.extend_from_slice(&(v as f32).to_le_bytes());
  }

  fn paint(&mut self, scratch: &mut Encoder) {
    self.bytes.extend_from_slice(&scratch.bytes);
    self.sources.append(&mut scratch.sources);
  }

  fn matrix(&mut self, m: Affine) {
    for v in m.as_coeffs() {
      self.f(v);
    }
  }

  fn text(&mut self, value: &str) {
    let index = self.string(value);
    self.u32(index);
  }

  fn string(&mut self, value: &str) -> u32 {
    if let Some(index) = self.interned.get(value) {
      return *index;
    }
    let index = self.strings.len() as u32;
    self.strings.push(value.to_string());
    self.interned.insert(value.to_string(), index);
    index
  }

  fn path(&mut self, path: &Path, inverse: Affine) {
    self.u32(path.0.elements().len() as u32);
    for &el in path.0.elements() {
      match inverse * el {
        PathEl::MoveTo(p) => {
          self.u8(0);
          self.f(p.x);
          self.f(p.y);
        }
        PathEl::LineTo(p) => {
          self.u8(1);
          self.f(p.x);
          self.f(p.y);
        }
        PathEl::CurveTo(a, b, c) => {
          self.u8(2);
          for p in [a, b, c] {
            self.f(p.x);
            self.f(p.y);
          }
        }
        PathEl::ClosePath => self.u8(3),
        PathEl::QuadTo(a, b) => {
          self.u8(4);
          for p in [a, b] {
            self.f(p.x);
            self.f(p.y);
          }
        }
      }
    }
  }
}

struct GradientData {
  kind: u8,
  coords: [f64; 6],
  stops: RefCell<Vec<(f64, i32)>>,
}

#[derive(Clone)]
enum ImageSource {
  Bitmap(Rc<ImageData>),
  Canvas(Rc<Surface>),
}

impl ImageSource {
  fn kind(&self) -> u8 {
    match self {
      ImageSource::Bitmap(_) => SOURCE_IMAGE,
      ImageSource::Canvas(_) => SOURCE_CANVAS,
    }
  }

  fn id(&self) -> i64 {
    match self {
      ImageSource::Bitmap(image) => image.id,
      ImageSource::Canvas(surface) => surface.id,
    }
  }

  fn alive(&self) -> bool {
    match self {
      ImageSource::Bitmap(image) => image.alive.get(),
      ImageSource::Canvas(surface) => surface.alive.get(),
    }
  }

  fn size(&self) -> (f64, f64) {
    match self {
      ImageSource::Bitmap(image) => (image.width as f64, image.height as f64),
      ImageSource::Canvas(surface) => (surface.width.get() as f64, surface.height.get() as f64),
    }
  }
}

struct PatternData {
  source: ImageSource,
  repeat: u8,
  transform: Cell<Affine>,
}

#[derive(Clone)]
enum Style {
  Color(i32),
  Gradient(Rc<GradientData>),
  Pattern(Rc<PatternData>),
}

pub struct Surface {
  id: i64,
  width: Cell<i32>,
  height: Cell<i32>,
  alive: Cell<bool>,
  charge: RefCell<Option<ExternalCharge>>,
  commands: RefCell<Encoder>,
  state: Rc<CanvasState>,
}

impl Drop for Surface {
  fn drop(&mut self) {
    if self.alive.replace(false) {
      self.state.host.canvas(OP_DESTROY, self.id, "", None);
    }
  }
}

impl Surface {
  /// Releases resources before [`Drop`]. Every operation checks `alive`, so contexts and patterns
  /// referencing a disposed canvas return `handle-expired` instead of using a stale host ID.
  fn free(&self) {
    if !self.alive.replace(false) {
      return;
    }
    self.commands.borrow_mut().clear();
    self.state.host.canvas(OP_DESTROY, self.id, "", None);
    self.charge.borrow_mut().take();
  }

  fn flush(&self, ctx: &Ctx<'_>) -> JsResult<()> {
    let (bytes, strings, _sources) = {
      let mut commands = self.commands.borrow_mut();
      if commands.is_empty() {
        return Ok(());
      }
      let bytes = std::mem::take(&mut commands.bytes);
      let strings = std::mem::take(&mut commands.strings);
      let sources = std::mem::take(&mut commands.sources);
      commands.interned.clear();
      (bytes, strings, sources)
    };
    let answer = self.state.host.canvas(OP_REPLAY, self.id, &encode_table(&strings), Some(&bytes));
    throw_host_error(ctx, &answer)
  }

  fn record(&self, ctx: &Ctx<'_>, fill: impl FnOnce(&mut Encoder)) -> JsResult<()> {
    {
      let mut commands = self.commands.borrow_mut();
      fill(&mut commands);
    }
    if self.commands.borrow().bytes.len() >= FLUSH_AT_BYTES {
      self.flush(ctx)?;
    }
    Ok(())
  }
}

#[derive(Trace, JsLifetime)]
#[rquickjs::class(rename = "OffscreenCanvas", frozen)]
pub struct CanvasHandle {
  #[qjs(skip_trace)]
  surface: Rc<Surface>,
}

pub struct ImageData {
  id: i64,
  width: i32,
  height: i32,
  alive: Cell<bool>,
  owns_bitmap: Cell<bool>,
  charge: RefCell<Option<ExternalCharge>>,
  state: Rc<CanvasState>,
}

impl Drop for ImageData {
  fn drop(&mut self) {
    self.free();
  }
}

impl ImageData {
  fn free(&self) {
    self.alive.set(false);
    if self.owns_bitmap.replace(false) {
      self.state.host.canvas(OP_RELEASE_IMAGE, self.id, "", None);
      self.charge.borrow_mut().take();
    }
  }

  fn release(&self, ctx: &Ctx<'_>) -> JsResult<()> {
    self.state.flush_surfaces(ctx)?;
    self.free();
    Ok(())
  }
}

#[derive(Trace, JsLifetime)]
#[rquickjs::class(rename = "ImageBitmap", frozen)]
pub struct ImageHandle {
  #[qjs(skip_trace)]
  image: Rc<ImageData>,
}
#[derive(Trace, JsLifetime)]
#[rquickjs::class(rename = "CanvasGradient", frozen)]
pub struct GradientHandle {
  #[qjs(skip_trace)]
  gradient: Rc<GradientData>,
}
#[derive(Trace, JsLifetime)]
#[rquickjs::class(rename = "CanvasPattern", frozen)]
pub struct PatternHandle {
  #[qjs(skip_trace)]
  pattern: Rc<PatternData>,
}

fn count_live<T>(list: &RefCell<Vec<Weak<T>>>, is_live: fn(&T) -> bool) -> usize {
  let mut list = list.borrow_mut();
  list.retain(|weak| weak.upgrade().is_some_and(|value| is_live(&value)));
  list.len()
}

pub struct AnimationData {
  id: i64,
  width: i32,
  height: i32,
  frame_count: i32,
  duration: i32,
  fps: i32,
  alive: Cell<bool>,
  charge: RefCell<Option<ExternalCharge>>,
  staged: RefCell<Option<StagedFile>>,
  state: Rc<CanvasState>,
}

impl Drop for AnimationData {
  fn drop(&mut self) {
    self.free();
  }
}

impl AnimationData {
  fn free(&self) {
    if !self.alive.replace(false) {
      return;
    }
    self.state.host.canvas(OP_RELEASE_ANIMATION, self.id, "", None);
    self.charge.borrow_mut().take();
    self.staged.borrow_mut().take();
  }
}

pub struct EncoderData {
  id: i64,
  width: i32,
  height: i32,
  fps: i32,
  frames: Cell<u32>,
  in_flight: Cell<usize>,
  finished: Cell<bool>,
  alive: Cell<bool>,
  charge: RefCell<Option<ExternalCharge>>,
  state: Rc<CanvasState>,
}

impl Drop for EncoderData {
  fn drop(&mut self) {
    self.free();
  }
}

impl EncoderData {
  fn free(&self) {
    if !self.alive.replace(false) {
      return;
    }
    self.state.host.canvas(OP_ENCODER_DESTROY, self.id, "", None);
    if self.in_flight.get() == 0 {
      self.charge.borrow_mut().take();
    }
  }

  fn start_frame(self: &Rc<Self>, ctx: &Ctx<'_>) -> JsResult<EncoderFrame> {
    let charge = self.state.external.charge(ctx, self.width as usize * self.height as usize * 4)?;
    self.in_flight.set(self.in_flight.get() + 1);
    Ok(EncoderFrame { encoder: self.clone(), _charge: charge })
  }

  fn writable(&self, ctx: &Ctx<'_>) -> JsResult<()> {
    if !self.alive.get() {
      return PluginErrorCode::HandleExpired.throw(ctx, "this encoder is gone");
    }
    if self.finished.get() {
      return PluginErrorCode::HandleExpired.throw(ctx, "this encoder has already been finished");
    }
    Ok(())
  }
}

struct EncoderFrame {
  encoder: Rc<EncoderData>,
  _charge: ExternalCharge,
}

impl Drop for EncoderFrame {
  fn drop(&mut self) {
    let encoder = &self.encoder;
    let in_flight = encoder.in_flight.get() - 1;
    encoder.in_flight.set(in_flight);
    if !encoder.alive.get() && in_flight == 0 {
      encoder.charge.borrow_mut().take();
    }
  }
}

#[derive(Trace, JsLifetime)]
#[rquickjs::class(rename = "AnimatedImage", frozen)]
pub struct AnimationHandle {
  #[qjs(skip_trace)]
  animation: Rc<AnimationData>,
}
#[derive(Trace, JsLifetime)]
#[rquickjs::class(rename = "VideoEncoder", frozen)]
pub struct EncoderHandle {
  #[qjs(skip_trace)]
  encoder: Rc<EncoderData>,
}

#[derive(Clone)]
struct DrawState {
  matrix: Affine,
  alpha: f64,
  composite: u8,
  fill: Style,
  stroke: Style,
  line_width: f64,
  line_cap: u8,
  line_join: u8,
  miter_limit: f64,
  dash: Vec<f64>,
  dash_offset: f64,
  shadow_blur: f64,
  shadow_color: i32,
  shadow_offset: (f64, f64),
  font: Font,
  font_source: String,
  text_align: u8,
  text_baseline: u8,
}

impl Default for DrawState {
  fn default() -> Self {
    DrawState {
      matrix: Affine::IDENTITY,
      alpha: 1.0,
      composite: 0,
      fill: Style::Color(0xff00_0000u32 as i32),
      stroke: Style::Color(0xff00_0000u32 as i32),
      line_width: 1.0,
      line_cap: 0,
      line_join: 2,
      miter_limit: 10.0,
      dash: Vec::new(),
      dash_offset: 0.0,
      shadow_blur: 0.0,
      shadow_color: 0,
      shadow_offset: (0.0, 0.0),
      font: Font::default_font(),
      font_source: "10px sans-serif".to_string(),
      text_align: 0,
      text_baseline: 3,
    }
  }
}

#[derive(Trace, JsLifetime)]
#[rquickjs::class(rename = "CanvasRenderingContext2D", frozen)]
pub struct Context2d {
  #[qjs(skip_trace)]
  surface: Rc<Surface>,
  #[qjs(skip_trace)]
  state: RefCell<DrawState>,
  #[qjs(skip_trace)]
  stack: RefCell<Vec<DrawState>>,
  #[qjs(skip_trace)]
  path: RefCell<Path>,
}

enum PendingKind {
  Encode,
  FinishEncoder {
    _encoder: Rc<EncoderData>,
  },
  EncoderFrame {
    _frame: EncoderFrame,
  },
  Decode(Rc<ImageData>),
  Frame {
    image: Rc<ImageData>,
    sequential: bool,
  },
  Ack,
  Json,
  Animation {
    id: i64,
    /// held here so a decode that never opens deletes the source it staged
    staged: Option<StagedFile>,
  },
  Encoder {
    id: i64,
    width: i32,
    height: i32,
    fps: i32,
    charge: Option<ExternalCharge>,
  },
}

struct CanvasRequest {
  kind: PendingKind,
  /// never read: it is here so that settling the request, however it settles, deletes the source
  _staged: Option<StagedFile>,
}

impl Parked for CanvasRequest {}

pub struct CanvasState {
  host: Rc<dyn CanvasHost>,
  sources: SourceStager,
  external: Rc<ExternalMemory>,
  log: crate::Log,
  blend_modes: Cell<bool>,
  next_id: RequestIds,
  pending: PendingTable<CanvasRequest>,
  surfaces: RefCell<Vec<Weak<Surface>>>,
  animations: RefCell<Vec<Weak<AnimationData>>>,
  encoders: RefCell<Vec<Weak<EncoderData>>>,
}

impl CanvasState {
  fn track_surface(&self, surface: &Rc<Surface>) {
    let mut surfaces = self.surfaces.borrow_mut();
    surfaces.retain(|weak| weak.strong_count() > 0);
    surfaces.push(Rc::downgrade(surface));
  }

  fn flush_surfaces(&self, ctx: &Ctx<'_>) -> JsResult<()> {
    let live: Vec<Rc<Surface>> = self.surfaces.borrow().iter().filter_map(Weak::upgrade).collect();
    for surface in live {
      surface.flush(ctx)?;
    }
    Ok(())
  }
}

fn throw_host_error(ctx: &Ctx<'_>, answer: &str) -> JsResult<()> {
  if answer.is_empty() {
    return Ok(());
  }
  throw_wire_error(ctx, answer)?;
  PluginErrorCode::Internal.throw(ctx, answer)
}

fn num(value: &Opt<Coerced<f64>>) -> f64 {
  value.0.as_ref().map(|v| v.0).unwrap_or(f64::NAN)
}

fn nth(args: &[Value<'_>], index: usize) -> f64 {
  let Some(value) = args.get(index) else {
    return f64::NAN;
  };
  Coerced::<f64>::from_js(value.ctx(), value.clone()).map(|v| v.0).unwrap_or(f64::NAN)
}

impl Context2d {
  fn live(&self, ctx: &Ctx<'_>) -> JsResult<()> {
    if !self.surface.alive.get() {
      return PluginErrorCode::HandleExpired.throw(ctx, "the canvas this context belongs to is gone");
    }
    Ok(())
  }
}

impl Encoder {
  fn encode_paint(
    &mut self,
    ctx: &Ctx<'_>,
    state: &DrawState,
    style: &Style,
    inverse: Affine,
    blend_modes: bool,
  ) -> JsResult<()> {
    if state.composite as usize >= FIRST_BLEND_MODE && !blend_modes {
      return PluginErrorCode::Unsupported.throw(
        ctx,
        &format!(
          "'{}' needs android 10 or newer; check inu.info().sdk before using the blend modes",
          COMPOSITE_MODES[state.composite as usize],
        ),
      );
    }
    self.f(state.alpha);
    self.u8(state.composite);
    let offset = inverse.with_translation(Vec2::ZERO) * Point::from(state.shadow_offset);
    let scale = inverse.determinant().abs().sqrt();
    self.f(state.shadow_blur * if scale.is_finite() && scale > 0.0 { scale } else { 1.0 });
    self.f(offset.x);
    self.f(offset.y);
    self.i32(state.shadow_color);
    self.encode_style(ctx, style)
  }

  fn encode_style(&mut self, ctx: &Ctx<'_>, style: &Style) -> JsResult<()> {
    match style {
      Style::Color(color) => {
        self.u8(STYLE_COLOR);
        self.i32(*color);
      }
      Style::Gradient(gradient) => {
        self.u8(gradient.kind);
        let count = match gradient.kind {
          STYLE_LINEAR => 4,
          STYLE_RADIAL => 6,
          _ => 3,
        };
        for value in &gradient.coords[..count] {
          self.f(*value);
        }
        let stops = gradient.stops.borrow();
        self.u32(stops.len() as u32);
        for (offset, color) in stops.iter() {
          self.f(*offset);
          self.i32(*color);
        }
      }
      Style::Pattern(pattern) => {
        if !pattern.source.alive() {
          return PluginErrorCode::HandleExpired.throw(ctx, "the image behind this pattern was disposed");
        }
        self.u8(STYLE_PATTERN);
        self.u8(pattern.source.kind());
        self.i64(pattern.source.id());
        self.sources.push(pattern.source.clone());
        self.u8(pattern.repeat);
        self.matrix(pattern.transform.get());
      }
    }
    Ok(())
  }

  fn encode_stroke(&mut self, state: &DrawState) {
    self.f(state.line_width);
    self.u8(state.line_cap);
    self.u8(state.line_join);
    self.f(state.miter_limit);
    self.f(state.dash_offset);
    self.u32(state.dash.len() as u32);
    for segment in &state.dash {
      self.f(*segment);
    }
  }
}

#[derive(Clone, Copy, PartialEq)]
enum PaintKind {
  Fill,
  Stroke,
}

impl DrawState {
  fn select_paint_style(&self, kind: PaintKind) -> (&Style, bool) {
    match kind {
      PaintKind::Fill => (&self.fill, false),
      PaintKind::Stroke => (&self.stroke, true),
    }
  }
}

impl Context2d {
  fn prepare_paint(
    &self,
    ctx: &Ctx<'_>,
    state: &DrawState,
    paint: Option<(&Style, bool)>,
  ) -> JsResult<Option<(Encoder, Affine)>> {
    let Some(inverse) = invert(state.matrix) else {
      return Ok(None);
    };
    let mut scratch = Encoder::default();
    if let Some((style, stroke)) = paint {
      scratch.encode_paint(ctx, state, style, inverse, self.surface.state.blend_modes.get())?;
      if stroke {
        scratch.encode_stroke(state);
      }
    }
    Ok(Some((scratch, inverse)))
  }

  fn draw_path(&self, ctx: &Ctx<'_>, command: u8, kind: Option<PaintKind>, fill_rule: u8, path: &Path) -> JsResult<()> {
    self.live(ctx)?;
    if path.0.is_empty() {
      return Ok(());
    }
    let state = self.state.borrow();
    let Some((mut scratch, inverse)) =
      self.prepare_paint(ctx, &state, kind.map(|kind| state.select_paint_style(kind)))?
    else {
      return Ok(());
    };
    let matrix = state.matrix;
    drop(state);
    self.surface.record(ctx, |out| {
      out.u8(command);
      out.matrix(matrix);
      out.paint(&mut scratch);
      if command == CMD_FILL || command == CMD_CLIP {
        out.u8(fill_rule);
      }
      out.path(path, inverse);
    })
  }
}

fn rect_path(m: Affine, x: f64, y: f64, w: f64, h: f64) -> Path {
  let mut path = Path::default();
  path.rect(m, x, y, w, h);
  path.0.pop();
  path
}

fn parse_fill_rule<'js>(ctx: &Ctx<'js>, rule: Opt<Value<'js>>) -> JsResult<u8> {
  let rule = match crate::utils::arguments::opt(rule) {
    Some(value) => Coerced::<String>::from_js(ctx, value)?.0,
    None => return Ok(0),
  };
  match rule.as_str() {
    "nonzero" => Ok(0),
    "evenodd" => Ok(1),
    other => Err(Exception::throw_type(ctx, &format!("'{other}' is not a fill rule"))),
  }
}

fn style_from_value<'js>(value: &Value<'js>) -> JsResult<Option<Style>> {
  if let Ok(gradient) = Class::<GradientHandle>::from_value(value) {
    let data = gradient.borrow().gradient.clone();
    return Ok(Some(Style::Gradient(data)));
  }
  if let Ok(pattern) = Class::<PatternHandle>::from_value(value) {
    let data = pattern.borrow().pattern.clone();
    return Ok(Some(Style::Pattern(data)));
  }
  let Some(text) = value.as_string() else {
    return Ok(None);
  };
  let text = text.to_string()?;
  Ok(parse_color(&text).map(Style::Color))
}

fn style_to_value<'js>(ctx: &Ctx<'js>, style: &Style) -> JsResult<Value<'js>> {
  match style {
    Style::Color(color) => {
      use rquickjs::IntoJs;
      format_color(*color).into_js(ctx)
    }
    Style::Gradient(data) => Ok(Class::instance(ctx.clone(), GradientHandle { gradient: data.clone() })?.into_value()),
    Style::Pattern(data) => Ok(Class::instance(ctx.clone(), PatternHandle { pattern: data.clone() })?.into_value()),
  }
}

fn format_color(color: i32) -> String {
  let value = color as u32;
  let (a, r, g, b) = (value >> 24, (value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff);
  if a == 0xff {
    return format!("#{r:02x}{g:02x}{b:02x}");
  }
  let alpha = (a as f64 / 255.0 * 1000.0).round() / 1000.0;
  format!("rgba({r}, {g}, {b}, {alpha})")
}

impl Font {
  fn to_wire(&self) -> String {
    format!(
      "{}{FIELD}{}{FIELD}{}{FIELD}{}{FIELD}{}",
      self.size,
      self.weight,
      u8::from(self.italic),
      u8::from(self.small_caps),
      self.families.join(&ITEM.to_string()),
    )
  }
}

impl CanvasState {
  /// Counts pending creations as well as live handles. Otherwise, plugins could exceed the limit by
  /// starting creations without awaiting them.
  fn count_starting(&self, matches: fn(&PendingKind) -> bool) -> usize {
    self.pending.count(|request| matches(&request.kind))
  }

  fn create_surface<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, width: i32, height: i32) -> JsResult<Rc<Surface>> {
    check_dimensions(ctx, width, height)?;
    let bytes = width as usize * height as usize * 4;
    let charge = self.external.charge(ctx, bytes)?;
    let id = self.next_id.alloc();
    let answer = ask(&*self.host, OP_CREATE, id, |args| {
      args.i32(width);
      args.i32(height);
    });
    throw_host_error(ctx, &answer)?;
    let surface = Rc::new(Surface {
      id,
      width: Cell::new(width),
      height: Cell::new(height),
      alive: Cell::new(true),
      charge: RefCell::new(Some(charge)),
      commands: RefCell::new(Encoder::default()),
      state: self.clone(),
    });
    self.track_surface(&surface);
    Ok(surface)
  }
}

fn read_dimensions(ctx: &Ctx<'_>, options: &Object<'_>, what: &str, required: bool) -> JsResult<[i32; 2]> {
  let mut size = [0; 2];
  for (name, slot) in ["width", "height"].into_iter().zip(&mut size) {
    match options.get::<_, Option<Coerced<f64>>>(name)? {
      None if required => return PluginErrorCode::InvalidArgument.throw(ctx, &format!("{what}: '{name}' is required")),
      None => {}
      Some(value) if !value.0.is_finite() => {
        return PluginErrorCode::InvalidArgument.throw(ctx, &format!("{what}: '{name}' must be a number"))
      }
      Some(value) => *slot = value.0.trunc() as i32,
    }
  }
  Ok(size)
}

fn check_dimensions(ctx: &Ctx<'_>, width: i32, height: i32) -> JsResult<()> {
  if width <= 0 || height <= 0 {
    return PluginErrorCode::InvalidArgument.throw(ctx, "a canvas needs a positive width and height");
  }
  if width > MAX_DIMENSION || height > MAX_DIMENSION {
    return PluginErrorCode::InvalidArgument
      .throw(ctx, &format!("a canvas may be at most {MAX_DIMENSION} pixels on a side"));
  }
  Ok(())
}

impl Surface {
  fn resize(&self, ctx: &Ctx<'_>, width: i32, height: i32) -> JsResult<()> {
    check_dimensions(ctx, width, height)?;
    let charge = if width == self.width.get() && height == self.height.get() {
      None
    } else {
      Some(self.state.external.charge(ctx, width as usize * height as usize * 4)?)
    };
    self.commands.borrow_mut().clear();
    let answer = ask(&*self.state.host, OP_CREATE, self.id, |args| {
      args.i32(width);
      args.i32(height);
    });
    throw_host_error(ctx, &answer)?;
    if let Some(charge) = charge {
      self.width.set(width);
      self.height.set(height);
      *self.charge.borrow_mut() = Some(charge);
    }
    Ok(())
  }
}

const CONTEXT_KEY: &str = "inu.canvas.context";

pub fn install_canvas<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn CanvasHost>,
  external: Rc<ExternalMemory>,
  stage_dir: PathBuf,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<CanvasState>> {
  let state = Rc::new(CanvasState {
    host,
    sources: SourceStager::new(stage_dir, "canvas", MAX_SOURCE_BYTES, "inu.canvas"),
    external,
    log,
    blend_modes: Cell::new(false),
    next_id: RequestIds::default(),
    pending: PendingTable::default(),
    surfaces: RefCell::new(Vec::new()),
    animations: RefCell::new(Vec::new()),
    encoders: RefCell::new(Vec::new()),
  });
  let capabilities = state.host.canvas(OP_CAPABILITIES, 0, "", None);
  state.blend_modes.set(capabilities.contains("\"blend\":true"));

  alias_dispose::<CanvasHandle>(ctx)?;
  alias_dispose::<ImageHandle>(ctx)?;
  alias_dispose::<AnimationHandle>(ctx)?;
  alias_dispose::<EncoderHandle>(ctx)?;
  state.install_namespace(ctx, globals)?;
  Ok(state)
}

impl CanvasState {
  fn install_namespace<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, globals: &crate::api::Globals<'js>) -> JsResult<()> {
    let canvas = Object::new(ctx.clone())?;

    let owned = self.clone();
    canvas.set(
      "create",
      Function::new(
        ctx.clone(),
        move |ctx: Ctx<'js>, width: Opt<Coerced<f64>>, height: Opt<Coerced<f64>>| -> JsResult<Value<'js>> {
          let (w, h) = (num(&width), num(&height));
          if !finite(&[w, h]) {
            return PluginErrorCode::InvalidArgument.throw(&ctx, "a canvas needs a width and a height");
          }
          let surface = owned.create_surface(&ctx, w.trunc() as i32, h.trunc() as i32)?;
          Ok(Class::instance(ctx.clone(), CanvasHandle { surface })?.into_value())
        },
      )?,
    )?;

    for (name, is_font) in [("decode", false), ("load", false), ("loadFont", true)] {
      let owned = self.clone();
      canvas.set(
        name,
        Function::new(
          ctx.clone(),
          move |ctx: Ctx<'js>, first: Opt<Value<'js>>, second: Opt<Value<'js>>| -> JsResult<Value<'js>> {
            let (family, source) = if is_font {
              let family = match first.0.as_ref().and_then(|v| v.as_string()) {
                Some(s) => s.to_string()?,
                None => {
                  return PluginErrorCode::InvalidArgument.throw(&ctx, "loadFont: the family name must be a string")
                }
              };
              (family, second.0.unwrap_or_else(|| Value::new_undefined(ctx.clone())))
            } else {
              (String::new(), first.0.unwrap_or_else(|| Value::new_undefined(ctx.clone())))
            };
            owned.start_async(&ctx, is_font, &family, &source)
          },
        )?,
      )?;
    }

    let owned = self.clone();
    canvas.set(
      "decodeAnimation",
      Function::new(
        ctx.clone(),
        move |ctx: Ctx<'js>, source: Opt<Value<'js>>, options: Opt<Value<'js>>| -> JsResult<Value<'js>> {
          let source = source.0.unwrap_or_else(|| Value::new_undefined(ctx.clone()));
          owned.decode_animation(&ctx, &source, options)
        },
      )?,
    )?;

    let owned = self.clone();
    canvas.set(
      "createEncoder",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, options: Opt<Value<'js>>| -> JsResult<Value<'js>> {
        owned.create_encoder(&ctx, options)
      })?,
    )?;

    let owned = self.clone();
    canvas.set(
      "listFonts",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<Value<'js>> {
        owned.start_op(&ctx, PendingKind::Json, OP_LIST_FONTS, 0, &|_| {}, None)
      })?,
    )?;

    globals.inu.set("canvas", canvas)?;
    Ok(())
  }

  fn decode_animation<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    source: &Value<'js>,
    options: Opt<Value<'js>>,
  ) -> JsResult<Value<'js>> {
    let open = count_live(&self.animations, |animation| animation.alive.get())
      + self.count_starting(|kind| matches!(kind, PendingKind::Animation { .. }));
    if open >= MAX_ANIMATIONS {
      return PluginErrorCode::QuotaExceeded(MAX_ANIMATIONS as i64 + 1, MAX_ANIMATIONS as i64)
        .throw(ctx, &format!("at most {MAX_ANIMATIONS} animations may be open at once"));
    }
    let [width, height] = match options.0.as_ref().and_then(|v| v.as_object()) {
      Some(options) => read_dimensions(ctx, options, "decodeAnimation", false)?,
      None => [0, 0],
    };
    if width != 0 || height != 0 {
      check_dimensions(ctx, width, height)?;
    }
    let StagedSource { path, owned } = self.sources.stage(ctx, source)?;
    let id = self.next_id.alloc();
    let describe = |args: &mut Encoder| {
      args.i32(width);
      args.i32(height);
      args.text(&path.to_string_lossy());
    };
    self.start_op(
      ctx,
      PendingKind::Animation {
        id,
        staged: owned.then(|| StagedFile(path.clone())),
      },
      OP_DECODE_ANIMATION,
      id,
      &describe,
      None,
    )
  }

  const ENCODINGS_VIDEO: [&str; 1] = ["video/mp4"];

  fn create_encoder<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, options: Opt<Value<'js>>) -> JsResult<Value<'js>> {
    let open = count_live(&self.encoders, |encoder| encoder.alive.get())
      + self.count_starting(|kind| matches!(kind, PendingKind::Encoder { .. }));
    if open >= MAX_ENCODERS {
      return PluginErrorCode::QuotaExceeded(MAX_ENCODERS as i64 + 1, MAX_ENCODERS as i64)
        .throw(ctx, &format!("at most {MAX_ENCODERS} encoders may be open at once"));
    }
    let Some(options) = options.0.as_ref().and_then(|v| v.as_object()) else {
      return PluginErrorCode::InvalidArgument.throw(ctx, "createEncoder: expected a width and a height");
    };
    let mut mime = "video/mp4".to_string();
    if let Some(value) = options.get::<_, Option<Coerced<String>>>("type")? {
      let value = value.0.to_ascii_lowercase();
      if !Self::ENCODINGS_VIDEO.contains(&value.as_str()) {
        return PluginErrorCode::InvalidArgument
          .throw(ctx, &format!("'{value}' is not an encoding this canvas writes"));
      }
      mime = value;
    }
    let [width, height] = read_dimensions(ctx, options, "createEncoder", true)?;
    check_dimensions(ctx, width, height)?;
    if width % 2 != 0 || height % 2 != 0 {
      return PluginErrorCode::InvalidArgument
        .throw(ctx, "createEncoder: a video's width and height must both be even");
    }
    let mut fps = DEFAULT_ENCODER_FPS;
    if let Some(value) = options.get::<_, Option<Coerced<f64>>>("fps")? {
      let rounded = if value.0.is_finite() { value.0.trunc() as i32 } else { 0 };
      if !(1..=MAX_FPS).contains(&rounded) {
        return PluginErrorCode::InvalidArgument
          .throw(ctx, &format!("createEncoder: 'fps' must be between 1 and {MAX_FPS}"));
      }
      fps = rounded;
    }
    let mut bitrate = 0i64;
    if let Some(value) = options.get::<_, Option<Coerced<f64>>>("bitrate")? {
      if !value.0.is_finite() || value.0 < 1.0 || value.0 > MAX_ENCODER_BITRATE as f64 {
        return PluginErrorCode::InvalidArgument
          .throw(ctx, &format!("createEncoder: 'bitrate' must be between 1 and {MAX_ENCODER_BITRATE}"));
      }
      bitrate = value.0.trunc() as i64;
    }
    let id = self.next_id.alloc();
    let describe = |args: &mut Encoder| {
      args.text(&mime);
      args.i32(width);
      args.i32(height);
      args.i32(fps);
      args.i64(bitrate);
    };
    self.start_op(
      ctx,
      PendingKind::Encoder {
        id,
        width,
        height,
        fps,
        charge: Some(self.external.charge(ctx, width as usize * height as usize * 4 * (ENCODER_SPARE_BUFFERS + 1))?),
      },
      OP_ENCODER_CREATE,
      id,
      &describe,
      None,
    )
  }

  fn start_async<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    is_font: bool,
    family: &str,
    source: &Value<'js>,
  ) -> JsResult<Value<'js>> {
    if is_font && family.is_empty() {
      return PluginErrorCode::InvalidArgument.throw(ctx, "loadFont: the family name is empty");
    }
    let StagedSource { path, owned } = self.sources.stage(ctx, source)?;
    let (kind, op, id) = if is_font {
      (PendingKind::Ack, OP_LOAD_FONT, 0)
    } else {
      let image = self.blank_image();
      let id = image.id;
      (PendingKind::Decode(image), OP_DECODE, id)
    };
    let describe = |args: &mut Encoder| {
      if is_font {
        args.text(family);
      }
      args.text(&path.to_string_lossy());
    };
    self.start_op(ctx, kind, op, id, &describe, owned.then(|| StagedFile(path.clone())))
  }

  /// the placeholder a decode's answer takes its id from; it owns no bitmap until the host answers
  fn blank_image(self: &Rc<Self>) -> Rc<ImageData> {
    Rc::new(ImageData {
      id: self.next_id.alloc(),
      width: 0,
      height: 0,
      alive: Cell::new(false),
      owns_bitmap: Cell::new(false),
      charge: RefCell::new(None),
      state: self.clone(),
    })
  }

  /// `staged` holds source files needed during the operation. Animations instead retain their
  /// [`StagedFile`] in the pending kind for the decoder's full lifetime and pass `None` here.
  fn start_op<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    kind: PendingKind,
    op: i32,
    id: i64,
    describe: &dyn Fn(&mut Encoder),
    staged: Option<StagedFile>,
  ) -> JsResult<Value<'js>> {
    let request = CanvasRequest { kind, _staged: staged };
    let promise = self.pending.park(ctx, request, |request_id| {
      let answer = ask(&*self.host, op, id, |args| {
        args.i64(request_id);
        describe(args);
      });
      (!answer.is_empty()).then_some(answer)
    })?;
    Ok(promise.into_value())
  }

  const ENCODINGS: [&str; 3] = ["image/png", "image/jpeg", "image/webp"];

  fn convert_to_blob<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    surface: &Rc<Surface>,
    options: Opt<Value<'js>>,
  ) -> JsResult<Value<'js>> {
    let mut mime = "image/png".to_string();
    let mut quality = 0.92;
    if let Some(options) = options.0.as_ref().and_then(|v| v.as_object()) {
      if let Some(value) = options.get::<_, Option<Coerced<String>>>("type")? {
        let value = value.0.to_ascii_lowercase();
        if !Self::ENCODINGS.contains(&value.as_str()) {
          return PluginErrorCode::InvalidArgument
            .throw(ctx, &format!("'{value}' is not an encoding this canvas writes"));
        }
        mime = value;
      }
      if let Some(value) = options.get::<_, Option<Coerced<f64>>>("quality")? {
        if value.0.is_finite() && (0.0..=1.0).contains(&value.0) {
          quality = value.0;
        }
      }
    }
    surface.flush(ctx)?;
    let describe = |args: &mut Encoder| {
      args.text(&mime);
      args.f(quality);
    };
    self.start_op(ctx, PendingKind::Encode, OP_ENCODE, surface.id, &describe, None)
  }
}

#[path = "members.rs"]
mod members;

impl CanvasState {
  fn build_answer<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, kind: &mut PendingKind, wire: &str) -> JsResult<Value<'js>> {
    match kind {
      PendingKind::Ack | PendingKind::EncoderFrame { .. } => Ok(Value::new_undefined(ctx.clone())),
      PendingKind::Json => {
        let json = wire
          .strip_prefix('J')
          .ok_or_else(|| Exception::throw_message(ctx, "canvas: malformed host answer"))?;
        ctx.json_parse(json)
      }
      PendingKind::Animation { id, staged } => {
        let object = parse_answer(ctx, wire)?;
        let width: i32 = object.get("width")?;
        let height: i32 = object.get("height")?;
        let bytes = width.max(0) as usize * height.max(0) as usize * 4;
        let charge = match self.external.charge(ctx, bytes) {
          Ok(charge) => charge,
          Err(e) => {
            self.host.canvas(OP_RELEASE_ANIMATION, *id, "", None);
            return Err(e);
          }
        };
        let animation = Rc::new(AnimationData {
          id: *id,
          width,
          height,
          frame_count: object.get("frameCount")?,
          duration: object.get("duration")?,
          fps: object.get("fps")?,
          alive: Cell::new(true),
          charge: RefCell::new(Some(charge)),
          staged: RefCell::new(staged.take()),
          state: self.clone(),
        });
        self.animations.borrow_mut().push(Rc::downgrade(&animation));
        Ok(Class::instance(ctx.clone(), AnimationHandle { animation })?.into_value())
      }
      PendingKind::Encoder { id, width, height, fps, charge } => {
        let encoder = Rc::new(EncoderData {
          id: *id,
          width: *width,
          height: *height,
          fps: *fps,
          frames: Cell::new(0),
          in_flight: Cell::new(0),
          finished: Cell::new(false),
          alive: Cell::new(true),
          charge: RefCell::new(charge.take()),
          state: self.clone(),
        });
        self.encoders.borrow_mut().push(Rc::downgrade(&encoder));
        Ok(Class::instance(ctx.clone(), EncoderHandle { encoder })?.into_value())
      }
      PendingKind::Encode | PendingKind::FinishEncoder { .. } => {
        let object = parse_answer(ctx, wire)?;
        let path: String = object.get("path")?;
        let mime: String = object.get("type").unwrap_or_default();
        mint_app_file_at(ctx, &PathBuf::from(path), &mime)
      }
      PendingKind::Decode(image) => {
        let object = parse_answer(ctx, wire)?;
        Ok(self.decoded_image(ctx, image, &object)?.into_value())
      }
      PendingKind::Frame { image, sequential } => {
        let object = parse_answer(ctx, wire)?;
        // only a sequential read ends this way: a frame asked for by index that is not there is
        // refused by the host with an error wire, so `end` on one is a malformed answer
        if object.get::<_, Option<bool>>("end")?.unwrap_or(false) {
          if !*sequential {
            return PluginErrorCode::InvalidArgument.throw(ctx, "frame: the host ended an indexed read");
          }
          return Ok(iteration(ctx, Value::new_undefined(ctx.clone()), true)?.into_value());
        }
        let handle = self.decoded_image(ctx, image, &object)?;
        handle.set("timestamp", object.get::<_, f64>("timestamp")?)?;
        if *sequential {
          return Ok(iteration(ctx, handle.into_value(), false)?.into_value());
        }
        Ok(handle.into_value())
      }
    }
  }

  fn decoded_image<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    image: &Rc<ImageData>,
    object: &Object<'js>,
  ) -> JsResult<Class<'js, ImageHandle>> {
    let width: i32 = object.get("width")?;
    let height: i32 = object.get("height")?;
    let bytes = width.max(0) as usize * height.max(0) as usize * 4;
    let charge = match self.external.charge(ctx, bytes) {
      Ok(charge) => charge,
      Err(e) => {
        self.host.canvas(OP_RELEASE_IMAGE, image.id, "", None);
        return Err(e);
      }
    };
    let handle = ImageData {
      id: image.id,
      width,
      height,
      alive: Cell::new(true),
      owns_bitmap: Cell::new(true),
      charge: RefCell::new(Some(charge)),
      state: self.clone(),
    };
    Class::instance(ctx.clone(), ImageHandle { image: Rc::new(handle) })
  }
}

fn iteration<'js>(ctx: &Ctx<'js>, value: Value<'js>, done: bool) -> JsResult<Object<'js>> {
  let result = Object::new(ctx.clone())?;
  result.set("value", value)?;
  result.set("done", done)?;
  Ok(result)
}

fn parse_json_answer<'js>(ctx: &Ctx<'js>, answer: &str, what: &str) -> JsResult<Value<'js>> {
  match answer.strip_prefix('J') {
    Some(json) => ctx.json_parse(json),
    None => {
      throw_host_error(ctx, answer)?;
      PluginErrorCode::Internal.throw(ctx, &format!("{what}: the host said nothing"))
    }
  }
}

fn parse_answer<'js>(ctx: &Ctx<'js>, wire: &str) -> JsResult<Object<'js>> {
  let json = wire
    .strip_prefix('J')
    .ok_or_else(|| Exception::throw_message(ctx, "canvas: malformed host answer"))?;
  ctx
    .json_parse(json)?
    .into_object()
    .ok_or_else(|| Exception::throw_message(ctx, "canvas: malformed host answer"))
}

impl CanvasState {
  pub fn attach_fs(self: &Rc<Self>, fs: Rc<FsState>) {
    self.sources.attach_fs(fs);
  }

  /// Encoder frames receive two responses: an `A`-prefixed queue-admission response settles the
  /// promise; the later ordinary response releases the consumed pixels.
  pub fn settle(self: &Rc<Self>, context: &rquickjs::Context, request_id: i64, result_wire: &str) {
    enter_js(context, |ctx| {
      let early = result_wire.starts_with('A')
        && self
          .pending
          .with_parked(request_id, |request| matches!(request.kind, PendingKind::EncoderFrame { .. }))
          .unwrap_or(false);
      let wire = if early { &result_wire[1..] } else { result_wire };
      let settled = self
        .pending
        .settle(&ctx, request_id, wire, early, |ctx, request, wire| self.build_answer(ctx, &mut request.kind, wire));
      if let Err(why) = settled {
        (self.log)(&format!("canvas({request_id}) settle failed: {why}"));
      }
    });
    pump_jobs(context, self.log.as_ref());
  }
}

impl Dispose for CanvasState {
  fn dispose(&self, context: &rquickjs::Context) {
    enter_js(context, |ctx| self.pending.dispose(&ctx));
  }
}

#[cfg(test)]
#[path = "tests.rs"]
mod tests;

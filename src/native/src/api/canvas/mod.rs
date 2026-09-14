pub(crate) mod css;
pub(crate) mod geometry;

use crate::runtime::Dispose;
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::rc::{Rc, Weak};

use rquickjs::class::{JsClass, Readable, Trace, Tracer};
use rquickjs::function::{Constructor, Opt, Rest, This};
use rquickjs::object::{Accessor, Property};
use rquickjs::{
  Class, Coerced, Ctx, Exception, FromJs, Function, JsLifetime, Object, Result as JsResult, Runtime, Value,
};

use crate::api::canvas::css::{parse_color, parse_font, Font};
use crate::api::canvas::geometry::{finite, normalize_round_rect, ArcError, Matrix, Path, Verb};
use crate::api::error::{wire_error_to_js, PluginErrorCode};
use crate::api::io::blob::{mint_app_file, BlobState, BUILD_LIMIT_BYTES};
use crate::api::io::fs::FsState;
use crate::api::io::staging::{SourceStager, StagedSource};
use crate::api::io::staging::StagedFile;
use crate::runtime::{pump_jobs, Parked, PendingTable};
use crate::sandbox::limits::{ExternalCharge, ExternalMemory};
use crate::sandbox::registry::RequestIds;
use crate::utils::shape::{define_disposable, define_getter, define_method};

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

/// a side request in the replay's own shape: the fields `fill` writes, strings through the table
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

fn index_of(table: &[&str], value: &str) -> Option<u8> {
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

  fn matrix(&mut self, m: &Matrix) {
    for v in [m.a, m.b, m.c, m.d, m.e, m.f] {
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

  fn path(&mut self, path: &Path, inverse: &Matrix) {
    self.u32(path.verbs.len() as u32);
    for verb in &path.verbs {
      match *verb {
        Verb::Move(x, y) => {
          self.u8(0);
          let p = inverse.apply(x, y);
          self.f(p.0);
          self.f(p.1);
        }
        Verb::Line(x, y) => {
          self.u8(1);
          let p = inverse.apply(x, y);
          self.f(p.0);
          self.f(p.1);
        }
        Verb::Cubic(ax, ay, bx, by, cx, cy) => {
          self.u8(2);
          for (x, y) in [(ax, ay), (bx, by), (cx, cy)] {
            let p = inverse.apply(x, y);
            self.f(p.0);
            self.f(p.1);
          }
        }
        Verb::Close => self.u8(3),
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
  transform: Cell<Matrix>,
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
  /// what [`Drop`] does, reached early. Every op guards on `alive`, so a context over a disposed
  /// canvas and a pattern made from one both answer `handle-expired` rather than a dead host id.
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

pub struct CanvasHandle(Rc<Surface>);

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

pub struct ImageHandle(Rc<ImageData>);
pub struct GradientHandle(Rc<GradientData>);
pub struct PatternHandle(Rc<PatternData>);

/// something the session counts while it is open, so a disposed handle stops counting before the
/// collector reaches it
trait Live {
  fn is_live(&self) -> bool;
}

fn count_live<T: Live>(list: &RefCell<Vec<Weak<T>>>) -> usize {
  let mut list = list.borrow_mut();
  list.retain(|weak| weak.upgrade().is_some_and(|value| value.is_live()));
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

impl Live for AnimationData {
  fn is_live(&self) -> bool {
    self.alive.get()
  }
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

impl Live for EncoderData {
  fn is_live(&self) -> bool {
    self.alive.get()
  }
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
      return expired(ctx, "this encoder is gone");
    }
    if self.finished.get() {
      return expired(ctx, "this encoder has already been finished");
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

pub struct AnimationHandle(Rc<AnimationData>);
pub struct EncoderHandle(Rc<EncoderData>);

#[derive(Clone)]
struct DrawState {
  matrix: Matrix,
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
      matrix: Matrix::IDENTITY,
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

pub struct Context2d {
  surface: Rc<Surface>,
  state: RefCell<DrawState>,
  stack: RefCell<Vec<DrawState>>,
  path: RefCell<Path>,
}

macro_rules! opaque_class {
  ($name:ident, $js:literal) => {
    impl<'js> Trace<'js> for $name {
      fn trace<'a>(&self, _tracer: Tracer<'a, 'js>) {}
    }
    // SAFETY: these opaque handles contain no values tied to the JavaScript lifetime.
    unsafe impl<'js> JsLifetime<'js> for $name {
      type Changed<'to> = $name;
    }
    impl<'js> JsClass<'js> for $name {
      const NAME: &'static str = $js;
      type Mutable = Readable;
      fn constructor(_ctx: &Ctx<'js>) -> JsResult<Option<Constructor<'js>>> {
        Ok(None)
      }
    }
  };
}

opaque_class!(CanvasHandle, "OffscreenCanvas");
opaque_class!(ImageHandle, "ImageBitmap");
opaque_class!(GradientHandle, "CanvasGradient");
opaque_class!(PatternHandle, "CanvasPattern");
opaque_class!(Context2d, "CanvasRenderingContext2D");
opaque_class!(AnimationHandle, "AnimatedImage");
opaque_class!(EncoderHandle, "VideoEncoder");

enum PendingKind {
  Encode,
  FinishEncoder {
    _encoder: Rc<EncoderData>,
  },
  EncoderFrame {
    _frame: EncoderFrame,
  },
  Decode(Rc<ImageData>),
  /// a decoder's frame: an image carrying its timestamp, or - asked sequentially - the end of the source
  Frame {
    image: Rc<ImageData>,
    sequential: bool,
  },
  /// the request answers nothing, and the promise resolves `undefined`
  Ack,
  /// the request answers with json, and the promise resolves whatever it parses to
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
  match wire_error_to_js(ctx, answer) {
    Some(Ok(value)) => Err(ctx.throw(value)),
    Some(Err(e)) => Err(e),
    None => PluginErrorCode::Internal.throw(ctx, answer),
  }
}

fn invalid<T>(ctx: &Ctx<'_>, message: &str) -> JsResult<T> {
  PluginErrorCode::InvalidArgument.throw(ctx, message)
}

fn expired<T>(ctx: &Ctx<'_>, message: &str) -> JsResult<T> {
  PluginErrorCode::HandleExpired.throw(ctx, message)
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
      return expired(ctx, "the canvas this context belongs to is gone");
    }
    Ok(())
  }

  fn save(&self) {
    let state = self.state.borrow().clone();
    self.stack.borrow_mut().push(state);
  }

  fn restore(&self) -> bool {
    match self.stack.borrow_mut().pop() {
      Some(state) => {
        *self.state.borrow_mut() = state;
        true
      }
      None => false,
    }
  }

  fn reset(&self) {
    *self.state.borrow_mut() = DrawState::default();
    self.stack.borrow_mut().clear();
    self.path.borrow_mut().clear();
  }
}

impl Encoder {
  fn encode_paint(
    &mut self,
    ctx: &Ctx<'_>,
    state: &DrawState,
    style: &Style,
    inverse: &Matrix,
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
    let offset = inverse.apply_vector(state.shadow_offset.0, state.shadow_offset.1);
    let scale = inverse.determinant().abs().sqrt();
    self.f(state.shadow_blur * if scale.is_finite() && scale > 0.0 { scale } else { 1.0 });
    self.f(offset.0);
    self.f(offset.1);
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
          return expired(ctx, "the image behind this pattern was disposed");
        }
        self.u8(STYLE_PATTERN);
        self.u8(pattern.source.kind());
        self.i64(pattern.source.id());
        self.sources.push(pattern.source.clone());
        self.u8(pattern.repeat);
        self.matrix(&pattern.transform.get());
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

impl Context2d {
  fn draw_path(&self, ctx: &Ctx<'_>, command: u8, kind: Option<PaintKind>, fill_rule: u8, path: &Path) -> JsResult<()> {
    self.live(ctx)?;
    let state = self.state.borrow();
    let Some(inverse) = state.matrix.invert() else {
      return Ok(());
    };
    if path.is_empty() {
      return Ok(());
    }
    let blend_modes = self.surface.state.blend_modes.get();
    let mut scratch = Encoder::default();
    match kind {
      Some(PaintKind::Fill) => scratch.encode_paint(ctx, &state, &state.fill, &inverse, blend_modes)?,
      Some(PaintKind::Stroke) => {
        scratch.encode_paint(ctx, &state, &state.stroke, &inverse, blend_modes)?;
        scratch.encode_stroke(&state);
      }
      None => {}
    }
    let matrix = state.matrix;
    drop(state);
    self.surface.record(ctx, |out| {
      out.u8(command);
      out.matrix(&matrix);
      out.paint(&mut scratch);
      if command == CMD_FILL || command == CMD_CLIP {
        out.u8(fill_rule);
      }
      out.path(path, &inverse);
    })
  }
}

fn rect_path(m: &Matrix, x: f64, y: f64, w: f64, h: f64) -> Path {
  let mut path = Path::default();
  path.rect(m, x, y, w, h);
  path.verbs.pop();
  path
}

fn fill_rule_of<'js>(ctx: &Ctx<'js>, rule: Opt<Value<'js>>) -> JsResult<u8> {
  let rule = match crate::utils::arguments::opt(rule) {
    Some(value) => Coerced::<String>::from_js(ctx, value)?.0,
    None => return Ok(0),
  };
  match rule.as_str() {
    "nonzero" => Ok(0),
    "evenodd" => Ok(1),
    other => invalid(ctx, &format!("'{other}' is not a fill rule")),
  }
}

fn style_from_value<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<Option<Style>> {
  if let Ok(gradient) = Class::<GradientHandle>::from_value(value) {
    let data = gradient.borrow().0.clone();
    return Ok(Some(Style::Gradient(data)));
  }
  if let Ok(pattern) = Class::<PatternHandle>::from_value(value) {
    let data = pattern.borrow().0.clone();
    return Ok(Some(Style::Pattern(data)));
  }
  let Some(text) = value.as_string() else {
    return Ok(None);
  };
  let text = text.to_string()?;
  let _ = ctx;
  Ok(parse_color(&text).map(Style::Color))
}

fn style_to_value<'js>(ctx: &Ctx<'js>, style: &Style) -> JsResult<Value<'js>> {
  match style {
    Style::Color(color) => {
      use rquickjs::IntoJs;
      format_color(*color).into_js(ctx)
    }
    Style::Gradient(data) => Ok(Class::instance(ctx.clone(), GradientHandle(data.clone()))?.into_value()),
    Style::Pattern(data) => Ok(Class::instance(ctx.clone(), PatternHandle(data.clone()))?.into_value()),
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
  /// What the host is already opening: a create only becomes live once it answers, so counting the
  /// live ones alone would let a plugin that never awaits start as many at once as it liked.
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

fn check_dimensions(ctx: &Ctx<'_>, width: i32, height: i32) -> JsResult<()> {
  if width <= 0 || height <= 0 {
    return invalid(ctx, "a canvas needs a positive width and height");
  }
  if width > MAX_DIMENSION || height > MAX_DIMENSION {
    return invalid(ctx, &format!("a canvas may be at most {MAX_DIMENSION} pixels on a side"));
  }
  Ok(())
}

impl Surface {
  fn resize(&self, ctx: &Ctx<'_>, width: i32, height: i32) -> JsResult<()> {
    check_dimensions(ctx, width, height)?;
    if width == self.width.get() && height == self.height.get() {
      self.commands.borrow_mut().clear();
      let answer = ask(&*self.state.host, OP_CREATE, self.id, |args| {
      args.i32(width);
      args.i32(height);
    });
      return throw_host_error(ctx, &answer);
    }
    let bytes = width as usize * height as usize * 4;
    let charge = self.state.external.charge(ctx, bytes)?;
    self.commands.borrow_mut().clear();
    let answer = ask(&*self.state.host, OP_CREATE, self.id, |args| {
      args.i32(width);
      args.i32(height);
    });
    throw_host_error(ctx, &answer)?;
    self.width.set(width);
    self.height.set(height);
    *self.charge.borrow_mut() = Some(charge);
    Ok(())
  }
}

fn define_accessor<'js, G, GP, S, SP>(target: &Object<'js>, name: &str, get: G, set: S) -> JsResult<()>
where
  G: rquickjs::function::IntoJsFunc<'js, GP> + 'js,
  S: rquickjs::function::IntoJsFunc<'js, SP> + 'js,
{
  target.prop(name, Accessor::new(get, set).enumerable().configurable())
}

const CONTEXT_KEY: &str = "inu.canvas.context";

pub fn install_canvas<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn CanvasHost>,
  blobs: Rc<BlobState>,
  external: Rc<ExternalMemory>,
  stage_dir: PathBuf,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<CanvasState>> {
  let state = Rc::new(CanvasState {
    host,
    sources: SourceStager::new(blobs, stage_dir, "canvas", MAX_SOURCE_BYTES, "inu.canvas"),
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

  state.install_canvas_members(ctx)?;
  install_context_members(ctx)?;
  install_gradient_members(ctx)?;
  install_pattern_members(ctx)?;
  install_image_members(ctx)?;
  install_animation_members(ctx)?;
  install_encoder_members(ctx)?;
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
            return invalid(&ctx, "a canvas needs a width and a height");
          }
          let surface = owned.create_surface(&ctx, w.trunc() as i32, h.trunc() as i32)?;
          Ok(Class::instance(ctx.clone(), CanvasHandle(surface))?.into_value())
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
                None => return invalid(&ctx, "loadFont: the family name must be a string"),
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
    let state = self;
    let open =
      count_live(&state.animations) + state.count_starting(|kind| matches!(kind, PendingKind::Animation { .. }));
    if open >= MAX_ANIMATIONS {
      return PluginErrorCode::QuotaExceeded(MAX_ANIMATIONS as i64 + 1, MAX_ANIMATIONS as i64)
        .throw(ctx, &format!("at most {MAX_ANIMATIONS} animations may be open at once"));
    }
    let mut width = 0;
    let mut height = 0;
    if let Some(options) = options.0.as_ref().and_then(|v| v.as_object()) {
      for (name, slot) in [("width", &mut width), ("height", &mut height)] {
        if let Some(value) = options.get::<_, Option<Coerced<f64>>>(name)? {
          if !value.0.is_finite() {
            return invalid(ctx, &format!("decodeAnimation: '{name}' must be a number"));
          }
          *slot = value.0.trunc() as i32;
        }
      }
      if width != 0 || height != 0 {
        check_dimensions(ctx, width, height)?;
      }
    }
    let StagedSource { path, owned } = state.sources.stage(ctx, source)?;
    let id = state.next_id.alloc();
    let describe = |args: &mut Encoder| {
      args.i32(width);
      args.i32(height);
      args.text(&path.to_string_lossy());
    };
    state.start_op(
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
    let state = self;
    let open = count_live(&state.encoders) + state.count_starting(|kind| matches!(kind, PendingKind::Encoder { .. }));
    if open >= MAX_ENCODERS {
      return PluginErrorCode::QuotaExceeded(MAX_ENCODERS as i64 + 1, MAX_ENCODERS as i64)
        .throw(ctx, &format!("at most {MAX_ENCODERS} encoders may be open at once"));
    }
    let Some(options) = options.0.as_ref().and_then(|v| v.as_object()) else {
      return invalid(ctx, "createEncoder: expected a width and a height");
    };
    let mut mime = "video/mp4".to_string();
    if let Some(value) = options.get::<_, Option<Coerced<String>>>("type")? {
      let value = value.0.to_ascii_lowercase();
      if !Self::ENCODINGS_VIDEO.contains(&value.as_str()) {
        return invalid(ctx, &format!("'{value}' is not an encoding this canvas writes"));
      }
      mime = value;
    }
    let mut size = [0i32; 2];
    for (name, slot) in [("width", 0), ("height", 1)] {
      let Some(value) = options.get::<_, Option<Coerced<f64>>>(name)? else {
        return invalid(ctx, &format!("createEncoder: '{name}' is required"));
      };
      if !value.0.is_finite() {
        return invalid(ctx, &format!("createEncoder: '{name}' must be a number"));
      }
      size[slot] = value.0.trunc() as i32;
    }
    let [width, height] = size;
    check_dimensions(ctx, width, height)?;
    if width % 2 != 0 || height % 2 != 0 {
      return invalid(ctx, "createEncoder: a video's width and height must both be even");
    }
    let mut fps = DEFAULT_ENCODER_FPS;
    if let Some(value) = options.get::<_, Option<Coerced<f64>>>("fps")? {
      let rounded = if value.0.is_finite() { value.0.trunc() as i32 } else { 0 };
      if !(1..=MAX_FPS).contains(&rounded) {
        return invalid(ctx, &format!("createEncoder: 'fps' must be between 1 and {MAX_FPS}"));
      }
      fps = rounded;
    }
    let mut bitrate = 0i64;
    if let Some(value) = options.get::<_, Option<Coerced<f64>>>("bitrate")? {
      if !value.0.is_finite() || value.0 < 1.0 || value.0 > MAX_ENCODER_BITRATE as f64 {
        return invalid(ctx, &format!("createEncoder: 'bitrate' must be between 1 and {MAX_ENCODER_BITRATE}"));
      }
      bitrate = value.0.trunc() as i64;
    }
    let id = state.next_id.alloc();
    let describe = |args: &mut Encoder| {
      args.text(&mime);
      args.i32(width);
      args.i32(height);
      args.i32(fps);
      args.i64(bitrate);
    };
    state.start_op(
      ctx,
      PendingKind::Encoder {
        id,
        width,
        height,
        fps,
        charge: Some(state.external.charge(ctx, width as usize * height as usize * 4 * (ENCODER_SPARE_BUFFERS + 1))?),
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
    let state = self;
    if is_font && family.is_empty() {
      return invalid(ctx, "loadFont: the family name is empty");
    }
    let StagedSource { path, owned } = state.sources.stage(ctx, source)?;
    let (kind, op, id) = if is_font {
      (PendingKind::Ack, OP_LOAD_FONT, 0)
    } else {
      let image = state.blank_image();
      let id = image.id;
      (PendingKind::Decode(image), OP_DECODE, id)
    };
    let describe = |args: &mut Encoder| {
      if is_font {
        args.text(family);
      }
      args.text(&path.to_string_lossy());
    };
    state.start_op(ctx, kind, op, id, &describe, owned.then(|| StagedFile(path.clone())))
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

  /// Every asynchronous canvas op, start to promise: the request is registered before the host is
  /// told about it, and an answer the host refuses on the spot rejects that same promise.
  ///
  /// `staged` is a source the host reads *during* the op, which is every case but an animation -
  /// that one holds its file for as long as its decoder does, so it passes `None` here and keeps
  /// the [`StagedFile`] on its own pending kind instead.
  fn start_op<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    kind: PendingKind,
    op: i32,
    id: i64,
    describe: &dyn Fn(&mut Encoder),
    staged: Option<StagedFile>,
  ) -> JsResult<Value<'js>> {
    let state = self;
    let request = CanvasRequest { kind, _staged: staged };
    let promise = state.pending.park(ctx, request, |request_id| {
      let answer = ask(&*state.host, op, id, |args| {
        args.i64(request_id);
        describe(args);
      });
      (!answer.is_empty()).then_some(answer)
    })?;
    Ok(promise.into_value())
  }

  fn install_canvas_members<'js>(self: &Rc<Self>, ctx: &Ctx<'js>) -> JsResult<()> {
    let proto = Class::<CanvasHandle>::prototype(ctx)?
      .ok_or_else(|| Exception::throw_message(ctx, "OffscreenCanvas: the class has no prototype"))?;

    {
      let f = Function::new(ctx.clone(), move |this: This<Class<'js, CanvasHandle>>| {
        let surface = this.0.borrow().0.clone();
        surface.free();
      })?;
      define_disposable(ctx, &proto, f)?;
    }

    for (name, vertical) in [("width", false), ("height", true)] {
      define_accessor(
        &proto,
        name,
        move |this: This<Class<'js, CanvasHandle>>| {
          let surface = this.0.borrow().0.clone();
          if vertical {
            surface.height.get()
          } else {
            surface.width.get()
          }
        },
        move |ctx: Ctx<'js>, this: This<Class<'js, CanvasHandle>>, value: Coerced<f64>| -> JsResult<()> {
          let surface = this.0.borrow().0.clone();
          if !value.0.is_finite() {
            return Ok(());
          }
          let value = value.0.trunc() as i32;
          let (w, h) = if vertical { (surface.width.get(), value) } else { (value, surface.height.get()) };
          surface.resize(&ctx, w, h)
        },
      )?;
    }

    define_method(
      &proto,
      "getContext",
      Function::new(
        ctx.clone(),
        |ctx: Ctx<'js>, this: This<Class<'js, CanvasHandle>>, id: Opt<Coerced<String>>| -> JsResult<Value<'js>> {
          match id.0.as_ref().map(|v| v.0.as_str()) {
            Some("2d") => {}
            _ => return invalid(&ctx, "getContext: only '2d' is available"),
          }
          let key = rquickjs::Symbol::new_global(ctx.clone(), CONTEXT_KEY)?;
          let canvas = this.0.as_inner().clone();
          let cached: Value = canvas.get(key.as_atom())?;
          if Class::<Context2d>::from_value(&cached).is_ok() {
            return Ok(cached);
          }
          let surface = this.0.borrow().0.clone();
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
        },
      )?,
    )?;

    let owned = self.clone();
    define_method(
      &proto,
      "convertToBlob",
      Function::new(
        ctx.clone(),
        move |ctx: Ctx<'js>, this: This<Class<'js, CanvasHandle>>, options: Opt<Value<'js>>| -> JsResult<Value<'js>> {
          let surface = this.0.borrow().0.clone();
          owned.convert_to_blob(&ctx, &surface, options)
        },
      )?,
    )?;
    Ok(())
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
          return invalid(ctx, &format!("'{value}' is not an encoding this canvas writes"));
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

use members::{
  install_animation_members, install_context_members, install_encoder_members, install_gradient_members,
  install_image_members, install_pattern_members,
};

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
        Ok(Class::instance(ctx.clone(), AnimationHandle(animation))?.into_value())
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
        Ok(Class::instance(ctx.clone(), EncoderHandle(encoder))?.into_value())
      }
      PendingKind::Encode | PendingKind::FinishEncoder { .. } => {
        let object = parse_answer(ctx, wire)?;
        let path: String = object.get("path")?;
        let mime: String = object.get("type").unwrap_or_default();
        let path = PathBuf::from(path);
        let (size, mtime) = match fs::metadata(&path) {
          Ok(meta) => (
            meta.len(),
            meta
              .modified()
              .ok()
              .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
              .map(|d| d.as_millis() as i64)
              .unwrap_or(0),
          ),
          Err(_) => (0, 0),
        };
        mint_app_file(ctx, &path, size, &mime, None, mtime)
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
            return invalid(ctx, "frame: the host ended an indexed read");
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

  /// the image a decode answered with, charged and registered as this session's
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
    Class::instance(ctx.clone(), ImageHandle(Rc::new(handle)))
  }
}

/// an iterator result, which is what an async iterator's `next` resolves with
fn iteration<'js>(ctx: &Ctx<'js>, value: Value<'js>, done: bool) -> JsResult<Object<'js>> {
  let result = Object::new(ctx.clone())?;
  result.set("value", value)?;
  result.set("done", done)?;
  Ok(result)
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
    let state = self;
    state.sources.attach_fs(fs);
  }

  /// an encoder frame answers twice: an `A`-prefixed answer once the host has admitted it to its
  /// queue, which settles the promise, and an ordinary one once the pixels are consumed
  pub fn settle(self: &Rc<Self>, rt: &Runtime, context: &rquickjs::Context, request_id: i64, result_wire: &str) {
    let state = self;
    context.with(|ctx| {
      let early = result_wire.starts_with('A')
        && state
          .pending
          .with_parked(request_id, |request| matches!(request.kind, PendingKind::EncoderFrame { .. }))
          .unwrap_or(false);
      let wire = if early { &result_wire[1..] } else { result_wire };
      let settled = state
        .pending
        .settle(&ctx, request_id, wire, early, |ctx, request, wire| state.build_answer(ctx, &mut request.kind, wire));
      if let Err(why) = settled {
        (state.log)(&format!("canvas({request_id}) settle failed: {why}"));
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

}

impl Dispose for CanvasState {
  fn dispose(&self, context: &rquickjs::Context) {
    context.with(|ctx| self.pending.dispose(&ctx));
  }
}

#[cfg(test)]
#[path = "tests.rs"]
mod tests;

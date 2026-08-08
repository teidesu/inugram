//! `inu.canvas`: `OffscreenCanvas`, `CanvasRenderingContext2D` and the handle types around them,
//! per `src/plugins/canvas.d.ts`.
//!
//! **The engine records, the host rasterizes.** A context appends to a command buffer, flushed only
//! where pixels are needed - `convertToBlob`, `getAverageColor`, and any op naming another canvas as
//! a source, which flushes that one first so the snapshot the spec promises is the one the plugin
//! can see. A command is self-contained rather than mutating a paint the host holds between
//! commands, which is what makes a `CanvasGradient` render with the stops it has *when it is drawn
//! with* (the spec's rule) without the host owning a state machine.
//!
//! **Everything is emitted under the current transform, in user space.** [`crate::draw::geom`] keeps the
//! path in device space because that is what a path is, and a draw hands over the transform plus the
//! path pulled back through its inverse: a stroke's pen, a gradient's coordinates and a pattern's
//! tiling are all defined in the user space of the draw. The two things the spec defines in *device*
//! space - the shadow's offset and its blur - are pushed the other way through the same inverse at
//! emit time. A transform with no inverse flattens the canvas onto a line, so those draws are
//! dropped rather than approximated.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::PathBuf;
use std::rc::{Rc, Weak};

use rquickjs::class::{JsClass, Readable, Trace, Tracer};
use rquickjs::function::{Constructor, Opt, Rest, This};
use rquickjs::object::{Accessor, Property};
use rquickjs::{
    Class, Coerced, Ctx, Exception, FromJs, Function, JsLifetime, Object, Result as JsResult, Runtime, TypedArray,
    Value,
};

use crate::draw::css::{parse_color, parse_font, Font};
use crate::draw::geom::{finite, normalize_round_rect, ArcError, Matrix, Path, Verb};
use crate::engine::deadline::{ExternalCharge, ExternalMemory};
use crate::engine::error::{get_or_create_inu, throw_plugin_error, wire_error_to_js};
use crate::engine::registry::RequestIds;
use crate::engine::shape::{define_getter, define_method};
use crate::io::blob::{mint_app_file, resolve_export, BlobState, BUILD_LIMIT_BYTES};
use crate::tg::rpc::{format_exception, pump_jobs, PendingSettle};

/// the largest canvas one plugin may ask for, per side. A canvas is one contiguous allocation of
/// `width * height * 4`, so the ceiling that matters is the native budget - this one exists on top
/// of it because a single side past it is a shape no rasterizer here is built for (the platform's
/// own hardware canvases stop around the same number), and because `create(1, 1e9)` should be an
/// `invalid-argument` rather than a `quota-exceeded` about a number nobody meant to ask for.
pub const MAX_DIMENSION: i32 = 8192;

/// how many colour stops one gradient may carry. Every stop is encoded into every draw the gradient
/// paints, so an unbounded list is a per-draw cost a plugin can grow without limit; 256 is more
/// than any real gradient and small enough that the encoding stays a rounding error.
pub const MAX_GRADIENT_STOPS: usize = 256;

/// how much content `decode`/`load`/`loadFont` may be handed in one call. Same bound
/// [`crate::io::blob`] puts on assembling a blob, for the same reason: the copy into a staged file is
/// native work no interpreter deadline can interrupt.
pub const MAX_SOURCE_BYTES: u64 = BUILD_LIMIT_BYTES;

/// how large a command buffer may grow before it is replayed without being asked. Without it a
/// plugin that draws a million shapes and never reads the result holds all of them in rust; with
/// it the buffer is bounded and the flush is work the drawing was going to cost anyway.
const FLUSH_AT_BYTES: usize = 1024 * 1024;

/// how much of a staged source is held in memory at once
const STAGE_CHUNK_BYTES: u64 = 256 * 1024;

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

/// separates the fields of an op's argument string. Both are control characters, so neither can
/// appear in a family name, a mime type or a path the filesystem gave us.
const FIELD: char = '\u{1e}';
const ITEM: char = '\u{1f}';

/// The replay wire's string table. Length-prefixed rather than separator-joined: an entry is
/// whatever a plugin passed to `fillText` or named as a font family, so there is no character it
/// cannot contain and no separator that would not eventually shift every index recorded after it -
/// silently, since the table only grows. Lengths are UTF-16 units, which is what the host counts a
/// string in. Read back by `PluginCanvas.decodeTable`.
fn encode_table(strings: &[String]) -> String {
    let mut out = String::new();
    for value in strings {
        out.push_str(&value.encode_utf16().count().to_string());
        out.push(ITEM);
        out.push_str(value);
    }
    out
}

/// stand-in for the Kotlin `QuickJs.CanvasListener`. Answers `""` for an op with nothing to say,
/// `J<json>` for a value, or a `P`/`R` error wire.
pub trait CanvasHost {
    fn canvas(&self, op: i32, id: i64, arg: &str, bytes: Option<&[u8]>) -> String;
}

/// in the order `GlobalCompositeOperation` declares them, which is the order the wire uses
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

/// the first of the separable blend modes, which need android 10 and are refused rather than
/// approximated below it
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

/// little-endian, because that is what every device this runs on is and what `ByteBuffer` is told
/// to expect on the other side. Geometry is `f32`: the largest canvas is 8192 px, so a float's 24
/// bits of mantissa put the quantization well below a thousandth of a pixel.
#[derive(Default)]
struct Encoder {
    bytes: Vec<u8>,
    strings: Vec<String>,
    interned: HashMap<String, u32>,
    /// the image sources commands in this buffer name, held for as long as they do. See
    /// [`ImageData::release`] for why the handle's own lifetime is not enough.
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

    /// appends a paint built in a scratch encoder. Not a plain `bytes` copy: whatever the paint
    /// named (a pattern's image) has to be retained by the buffer the bytes end up in.
    fn paint(&mut self, scratch: &mut Encoder) {
        self.bytes.extend_from_slice(&scratch.bytes);
        self.sources.append(&mut scratch.sources);
    }

    fn matrix(&mut self, m: &Matrix) {
        for v in [m.a, m.b, m.c, m.d, m.e, m.f] {
            self.f(v);
        }
    }

    /// interned, so a font or a run of identical text costs one entry however many commands name it
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

/// one canvas: an id the host keys its bitmap by, the pending commands for it, and what its bitmap
/// costs against the native budget
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
    /// hands whatever has been recorded to the host. Every read of the pixels goes through here,
    /// and so does every op that names this canvas as a source for another one.
    fn flush(&self, ctx: &Ctx<'_>) -> JsResult<()> {
        // `sources` is held across the host call and dropped after it: it is what keeps a bitmap
        // the plugin already disposed alive long enough for the id in the buffer to resolve
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

/// a decoded bitmap the host holds
pub struct ImageData {
    id: i64,
    width: i32,
    height: i32,
    /// the *handle*: `dispose()` ends it, and drawing with it after is `handle-expired`
    alive: Cell<bool>,
    /// whether the host holds a bitmap under [`ImageData::id`]. False for the placeholder a pending
    /// decode carries, which is an id reserved before there is anything to free.
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

    /// `dispose()`, which `canvas.d.ts` promises gives the memory back there and then.
    ///
    /// A recorded `drawImage` names an id the host only resolves at replay, so freeing the bitmap
    /// with commands still buffered would leave an id that resolves to nothing - and one of those
    /// fails the whole flush, silently taking every command recorded after it. Rather than defer
    /// the free (which would break the promise above), the buffers holding it are flushed first,
    /// which is the same work the draw was always going to cost.
    fn release(&self, ctx: &Ctx<'_>) -> JsResult<()> {
        self.state.flush_surfaces(ctx)?;
        self.free();
        Ok(())
    }
}

pub struct ImageHandle(Rc<ImageData>);
pub struct GradientHandle(Rc<GradientData>);
pub struct PatternHandle(Rc<PatternData>);

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

enum PendingKind {
    Encode,
    Decode(Rc<ImageData>),
    Font,
}

struct Pending {
    kind: PendingKind,
    settle: PendingSettle,
    staged: Option<PathBuf>,
}

pub struct CanvasState {
    host: Rc<dyn CanvasHost>,
    blobs: Rc<BlobState>,
    fs: RefCell<Option<Rc<crate::io::fs::FsState>>>,
    external: Rc<ExternalMemory>,
    log: crate::Log,
    stage_dir: PathBuf,
    /// whether the host can honour the separable blend modes at all, read once at install
    blend_modes: Cell<bool>,
    next_id: RequestIds,
    next_request: RequestIds,
    next_staged: RequestIds,
    pending: RefCell<HashMap<i64, Pending>>,
    /// every live surface, weakly. Only [`ImageData::release`] reads it, and only to make sure no
    /// buffer still names a bitmap it is about to give back.
    surfaces: RefCell<Vec<Weak<Surface>>>,
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

/// the host answered an op that has nothing to say; anything but an empty string is a failure it
/// described, and is raised as the plugin's own
fn throw_host_error(ctx: &Ctx<'_>, answer: &str) -> JsResult<()> {
    if answer.is_empty() {
        return Ok(());
    }
    match wire_error_to_js(ctx, answer) {
        Some(Ok(value)) => Err(ctx.throw(value)),
        Some(Err(e)) => Err(e),
        None => throw_plugin_error(ctx, "internal", answer, None, None, None),
    }
}

fn invalid<T>(ctx: &Ctx<'_>, message: &str) -> JsResult<T> {
    throw_plugin_error(ctx, "invalid-argument", message, None, None, None)
}

fn expired<T>(ctx: &Ctx<'_>, message: &str) -> JsResult<T> {
    throw_plugin_error(ctx, "handle-expired", message, None, None, None)
}

/// the spec's own coercion for a geometry argument: everything is a double, and a non-finite one
/// makes the whole call a no-op rather than an error
fn num(value: &Opt<Coerced<f64>>) -> f64 {
    value.0.as_ref().map(|v| v.0).unwrap_or(f64::NAN)
}

/// the same coercion off a `Rest`, which is how the members with more arguments than rquickjs can
/// name individually take theirs
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

/// the paint block every draw carries. Fails only where a style names something that has been
/// disposed, which the contract says is `handle-expired` at the moment it is drawn with.
fn encode_paint(
    ctx: &Ctx<'_>,
    out: &mut Encoder,
    state: &DrawState,
    style: &Style,
    inverse: &Matrix,
    blend_modes: bool,
) -> JsResult<()> {
    if state.composite as usize >= FIRST_BLEND_MODE && !blend_modes {
        return throw_plugin_error(
            ctx,
            "unsupported",
            &format!(
                "'{}' needs android 10 or newer; check inu.info().sdk before using the blend modes",
                COMPOSITE_MODES[state.composite as usize],
            ),
            None,
            None,
            None,
        );
    }
    out.f(state.alpha);
    out.u8(state.composite);
    // the spec puts the shadow in device space, and the host draws under the transform, so the
    // offset and the blur are pushed back through the inverse to survive it
    let offset = inverse.apply_vector(state.shadow_offset.0, state.shadow_offset.1);
    let scale = inverse.determinant().abs().sqrt();
    out.f(state.shadow_blur * if scale.is_finite() && scale > 0.0 { scale } else { 1.0 });
    out.f(offset.0);
    out.f(offset.1);
    out.i32(state.shadow_color);
    encode_style(ctx, out, style)
}

fn encode_style(ctx: &Ctx<'_>, out: &mut Encoder, style: &Style) -> JsResult<()> {
    match style {
        Style::Color(color) => {
            out.u8(STYLE_COLOR);
            out.i32(*color);
        }
        Style::Gradient(gradient) => {
            out.u8(gradient.kind);
            let count = match gradient.kind {
                STYLE_LINEAR => 4,
                STYLE_RADIAL => 6,
                _ => 3,
            };
            for value in &gradient.coords[..count] {
                out.f(*value);
            }
            let stops = gradient.stops.borrow();
            out.u32(stops.len() as u32);
            for (offset, color) in stops.iter() {
                out.f(*offset);
                out.i32(*color);
            }
        }
        Style::Pattern(pattern) => {
            if !pattern.source.alive() {
                return expired(ctx, "the image behind this pattern was disposed");
            }
            out.u8(STYLE_PATTERN);
            out.u8(pattern.source.kind());
            out.i64(pattern.source.id());
            out.sources.push(pattern.source.clone());
            out.u8(pattern.repeat);
            out.matrix(&pattern.transform.get());
        }
    }
    Ok(())
}

fn encode_stroke(out: &mut Encoder, state: &DrawState) {
    out.f(state.line_width);
    out.u8(state.line_cap);
    out.u8(state.line_join);
    out.f(state.miter_limit);
    out.f(state.dash_offset);
    out.u32(state.dash.len() as u32);
    for segment in &state.dash {
        out.f(*segment);
    }
}

#[derive(Clone, Copy, PartialEq)]
enum PaintKind {
    Fill,
    Stroke,
}

/// fill/stroke/clip/clear of an arbitrary path, which is every geometry op there is once the rects
/// and the text have built theirs
fn draw_path(
    ctx: &Ctx<'_>,
    this: &Context2d,
    command: u8,
    kind: Option<PaintKind>,
    fill_rule: u8,
    path: &Path,
) -> JsResult<()> {
    this.live(ctx)?;
    let state = this.state.borrow();
    let Some(inverse) = state.matrix.invert() else {
        return Ok(());
    };
    if path.is_empty() {
        return Ok(());
    }
    let blend_modes = this.surface.state.blend_modes.get();
    let mut scratch = Encoder::default();
    match kind {
        Some(PaintKind::Fill) => encode_paint(ctx, &mut scratch, &state, &state.fill, &inverse, blend_modes)?,
        Some(PaintKind::Stroke) => {
            encode_paint(ctx, &mut scratch, &state, &state.stroke, &inverse, blend_modes)?;
            encode_stroke(&mut scratch, &state);
        }
        None => {}
    }
    let matrix = state.matrix;
    drop(state);
    this.surface.record(ctx, |out| {
        out.u8(command);
        out.matrix(&matrix);
        out.paint(&mut scratch);
        if command == CMD_FILL || command == CMD_CLIP {
            out.u8(fill_rule);
        }
        out.path(path, &inverse);
    })
}

fn rect_path(m: &Matrix, x: f64, y: f64, w: f64, h: f64) -> Path {
    let mut path = Path::default();
    path.rect(m, x, y, w, h);
    // `rect` leaves a fresh subpath behind it, which would draw as a stray point under a round cap
    path.verbs.pop();
    path
}

fn fill_rule_of<'js>(ctx: &Ctx<'js>, rule: Opt<Value<'js>>) -> JsResult<u8> {
    let rule = match crate::engine::argv::opt(rule) {
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
    // the spec's rule for an unparseable colour: the assignment is ignored, so a typo leaves the
    // previous style in place rather than painting something arbitrary
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

/// the serialization the spec asks for when `fillStyle` is *read* back: `#rrggbb` when the colour
/// is opaque, and `rgba(r, g, b, a)` otherwise
fn format_color(color: i32) -> String {
    let value = color as u32;
    let (a, r, g, b) = (value >> 24, (value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff);
    if a == 0xff {
        return format!("#{r:02x}{g:02x}{b:02x}");
    }
    let alpha = (a as f64 / 255.0 * 1000.0).round() / 1000.0;
    format!("rgba({r}, {g}, {b}, {alpha})")
}

/// the font as the host reads it back: size, weight, italic, small caps, then the family list.
/// Both separators are control characters, which a css family name cannot contain.
fn font_wire(font: &Font) -> String {
    format!(
        "{}{FIELD}{}{FIELD}{}{FIELD}{}{FIELD}{}",
        font.size,
        font.weight,
        u8::from(font.italic),
        u8::from(font.small_caps),
        font.families.join(&ITEM.to_string()),
    )
}

/// copies whatever a `decode`/`loadFont` was handed into a file the host can open, because the
/// alternative is a byte array of the same size on the app-wide java heap - the lever the per-plugin
/// budgets exist to take away
fn stage_source<'js>(ctx: &Ctx<'js>, state: &Rc<CanvasState>, value: &Value<'js>) -> JsResult<(PathBuf, bool)> {
    if let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) {
        let Some(bytes) = typed.as_bytes() else {
            return invalid(ctx, "this Uint8Array is detached");
        };
        check_source_limit(ctx, bytes.len() as u64)?;
        return Ok((write_staged(ctx, state, |file| file.write_all(bytes))?, true));
    }
    if rquickjs::Class::<crate::io::blob::BlobHandle>::from_value(value).is_ok() {
        let Some(exported) = crate::io::blob::export_for_host(&state.blobs, value) else {
            return expired(ctx, "this blob has been disposed");
        };
        let Some(id) = exported.strip_prefix('B').and_then(|v| v.split(':').next()).and_then(|v| v.parse().ok()) else {
            return throw_plugin_error(ctx, "internal", "this blob could not be handed over", None, None, None);
        };
        let Some(export) = resolve_export(&state.blobs, id) else {
            return expired(ctx, "this blob has been disposed");
        };
        let len = export.len();
        check_source_limit(ctx, len)?;
        let path = write_staged(ctx, state, |file| {
            let mut at = 0u64;
            while at < len {
                let take = STAGE_CHUNK_BYTES.min(len - at);
                let chunk = export
                    .read(at, take)
                    .map_err(|_| std::io::Error::other("this blob's content is no longer readable"))?;
                file.write_all(&chunk)?;
                at += take;
            }
            Ok(())
        })?;
        return Ok((path, true));
    }
    if let Some(object) = value.as_object() {
        if let Some(path) = object.get::<_, Option<String>>("path")? {
            let Some(fs) = state.fs.borrow().clone() else {
                return throw_plugin_error(ctx, "not-granted", "naming a file needs @grant fs", Some("fs"), None, None);
            };
            return Ok((crate::io::fs::resolve_external(ctx, &fs, &path)?, false));
        }
    }
    invalid(ctx, "expected a Blob, a Uint8Array or { path }")
}

fn check_source_limit(ctx: &Ctx<'_>, len: u64) -> JsResult<()> {
    if len <= MAX_SOURCE_BYTES {
        return Ok(());
    }
    throw_plugin_error(
        ctx,
        "quota-exceeded",
        &format!("this source is {len} bytes; at most {} may be handed to inu.canvas in one call", MAX_SOURCE_BYTES,),
        None,
        Some(len as i64),
        Some(MAX_SOURCE_BYTES as i64),
    )
}

fn write_staged<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<CanvasState>,
    fill: impl FnOnce(&mut fs::File) -> std::io::Result<()>,
) -> JsResult<PathBuf> {
    if state.stage_dir.as_os_str().is_empty() {
        return throw_plugin_error(
            ctx,
            "internal",
            "this engine has no directory to stage a source in",
            None,
            None,
            None,
        );
    }
    let n = state.next_staged.alloc();
    let path = state.stage_dir.join(format!("canvas-{n}.bin"));
    let written = fs::create_dir_all(&state.stage_dir)
        .and_then(|_| fs::File::create(&path))
        .and_then(|mut file| fill(&mut file).and_then(|_| file.sync_all()));
    if let Err(e) = written {
        let _ = fs::remove_file(&path);
        return throw_plugin_error(ctx, "internal", &format!("staging this source failed: {e}"), None, None, None);
    }
    Ok(path)
}

fn take_pending(state: &Rc<CanvasState>, request_id: i64) -> Option<Pending> {
    let pending = state.pending.borrow_mut().remove(&request_id)?;
    if let Some(path) = pending.staged.as_ref() {
        let _ = fs::remove_file(path);
    }
    Some(pending)
}

fn create_surface<'js>(ctx: &Ctx<'js>, state: &Rc<CanvasState>, width: i32, height: i32) -> JsResult<Rc<Surface>> {
    check_dimensions(ctx, width, height)?;
    let bytes = width as usize * height as usize * 4;
    let charge = state.external.charge(ctx, bytes)?;
    let id = state.next_id.alloc();
    let answer = state.host.canvas(OP_CREATE, id, &format!("{width},{height}"), None);
    throw_host_error(ctx, &answer)?;
    let surface = Rc::new(Surface {
        id,
        width: Cell::new(width),
        height: Cell::new(height),
        alive: Cell::new(true),
        charge: RefCell::new(Some(charge)),
        commands: RefCell::new(Encoder::default()),
        state: state.clone(),
    });
    state.track_surface(&surface);
    Ok(surface)
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

/// the spec's rule for assigning `width`/`height`: the canvas is reallocated and everything on it,
/// including the context's own state, goes back to its initial value
fn resize_surface(ctx: &Ctx<'_>, surface: &Rc<Surface>, width: i32, height: i32) -> JsResult<()> {
    check_dimensions(ctx, width, height)?;
    if width == surface.width.get() && height == surface.height.get() {
        // still a reset: assigning the same size clears the canvas on the web too
        surface.commands.borrow_mut().clear();
        let answer = surface.state.host.canvas(OP_CREATE, surface.id, &format!("{width},{height}"), None);
        return throw_host_error(ctx, &answer);
    }
    let bytes = width as usize * height as usize * 4;
    // charged before the old one is given back, so a resize that cannot fit is refused with the
    // canvas it already had intact
    let charge = surface.state.external.charge(ctx, bytes)?;
    surface.commands.borrow_mut().clear();
    let answer = surface.state.host.canvas(OP_CREATE, surface.id, &format!("{width},{height}"), None);
    throw_host_error(ctx, &answer)?;
    surface.width.set(width);
    surface.height.set(height);
    *surface.charge.borrow_mut() = Some(charge);
    Ok(())
}

fn define_accessor<'js, G, GP, S, SP>(target: &Object<'js>, name: &str, get: G, set: S) -> JsResult<()>
where
    G: rquickjs::function::IntoJsFunc<'js, GP> + 'js,
    S: rquickjs::function::IntoJsFunc<'js, SP> + 'js,
{
    target.prop(name, Accessor::new(get, set).enumerable().configurable())
}

/// where a canvas caches its own 2d context, so `getContext('2d')` answers with the same object
/// every time. A *js* property rather than anything rust holds: the context refers back to its
/// canvas, and a cycle between two js objects is one quickjs can collect while a `Persistent` on
/// either side is a root that would leak both.
const CONTEXT_KEY: &str = "inu.canvas.context";

pub fn install_canvas<'js>(
    ctx: &Ctx<'js>,
    host: Rc<dyn CanvasHost>,
    blobs: Rc<BlobState>,
    external: Rc<ExternalMemory>,
    stage_dir: PathBuf,
    log: crate::Log,
) -> JsResult<Rc<CanvasState>> {
    let state = Rc::new(CanvasState {
        host,
        blobs,
        fs: RefCell::new(None),
        external,
        log,
        stage_dir,
        blend_modes: Cell::new(false),
        next_id: RequestIds::default(),
        next_request: RequestIds::default(),
        next_staged: RequestIds::default(),
        pending: RefCell::new(HashMap::new()),
        surfaces: RefCell::new(Vec::new()),
    });
    let capabilities = state.host.canvas(OP_CAPABILITIES, 0, "", None);
    state.blend_modes.set(capabilities.contains("\"blend\":true"));

    install_canvas_members(ctx, &state)?;
    install_context_members(ctx, &state)?;
    install_gradient_members(ctx)?;
    install_pattern_members(ctx)?;
    install_image_members(ctx)?;
    install_namespace(ctx, &state)?;
    Ok(state)
}

/// `inu.fs` installs after this one and the scoped root is its property, so `{ path }` is wired up
/// afterwards rather than duplicated here
pub fn attach_fs(state: &Rc<CanvasState>, fs: Rc<crate::io::fs::FsState>) {
    *state.fs.borrow_mut() = Some(fs);
}

fn install_namespace<'js>(ctx: &Ctx<'js>, state: &Rc<CanvasState>) -> JsResult<()> {
    let inu = get_or_create_inu(ctx)?;
    let canvas = Object::new(ctx.clone())?;

    let owned = state.clone();
    let f = Function::new(
        ctx.clone(),
        move |ctx: Ctx<'js>, width: Opt<Coerced<f64>>, height: Opt<Coerced<f64>>| -> JsResult<Value<'js>> {
            let (w, h) = (num(&width), num(&height));
            if !finite(&[w, h]) {
                return invalid(&ctx, "a canvas needs a width and a height");
            }
            let surface = create_surface(&ctx, &owned, w.trunc() as i32, h.trunc() as i32)?;
            Ok(Class::instance(ctx.clone(), CanvasHandle(surface))?.into_value())
        },
    )?;
    canvas.set("create", f)?;

    for (name, is_font) in [("decode", false), ("load", false), ("loadFont", true)] {
        let owned = state.clone();
        let f = Function::new(
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
                start_async(&ctx, &owned, is_font, &family, &source)
            },
        )?;
        canvas.set(name, f)?;
    }

    inu.set("canvas", canvas)?;
    Ok(())
}

/// `decode`/`load`/`loadFont`, which differ only in what the host is asked to do with the file
fn start_async<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<CanvasState>,
    is_font: bool,
    family: &str,
    source: &Value<'js>,
) -> JsResult<Value<'js>> {
    if is_font && family.is_empty() {
        return invalid(ctx, "loadFont: the family name is empty");
    }
    let (path, staged) = stage_source(ctx, state, source)?;
    let request_id = state.next_request.alloc();
    let (promise, settle) = PendingSettle::new(ctx)?;

    let (kind, op, id, arg) = if is_font {
        let arg = format!("{request_id}{FIELD}{family}{FIELD}{}", path.to_string_lossy());
        (PendingKind::Font, OP_LOAD_FONT, 0, arg)
    } else {
        let id = state.next_id.alloc();
        let image = Rc::new(ImageData {
            id,
            width: 0,
            height: 0,
            alive: Cell::new(false),
            // the host has nothing under this id yet; the value built once it answers is the one
            // that owns the bitmap
            owns_bitmap: Cell::new(false),
            charge: RefCell::new(None),
            state: state.clone(),
        });
        let arg = format!("{request_id}{FIELD}{}", path.to_string_lossy());
        (PendingKind::Decode(image), OP_DECODE, id, arg)
    };
    state.pending.borrow_mut().insert(request_id, Pending { kind, settle, staged: staged.then_some(path) });
    let answer = state.host.canvas(op, id, &arg, None);
    if !answer.is_empty() {
        if let Some(pending) = take_pending(state, request_id) {
            match wire_error_to_js(ctx, &answer) {
                Some(Ok(value)) => pending.settle.reject_with_value(ctx, value)?,
                _ => {
                    let value = crate::engine::error::make_plugin_error(ctx, "internal", &answer, None, None, None)?;
                    pending.settle.reject_with_value(ctx, value)?;
                }
            }
        }
    }
    Ok(promise.into_value())
}

fn install_canvas_members<'js>(ctx: &Ctx<'js>, state: &Rc<CanvasState>) -> JsResult<()> {
    let proto = Class::<CanvasHandle>::prototype(ctx)?
        .ok_or_else(|| Exception::throw_message(ctx, "OffscreenCanvas: the class has no prototype"))?;

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
                resize_surface(&ctx, &surface, w, h)
            },
        )?;
    }

    let f = Function::new(
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
            // the back-reference the spec's `ctx.canvas` promises, and the half of the cycle that
            // makes both objects collectable together
            context.as_inner().prop("canvas", Property::from(canvas.clone()).enumerable())?;
            canvas.prop(key.as_atom(), Property::from(context.as_value().clone()))?;
            Ok(context.into_value())
        },
    )?;
    define_method(&proto, "getContext", f)?;

    let owned = state.clone();
    let f = Function::new(
        ctx.clone(),
        move |ctx: Ctx<'js>, this: This<Class<'js, CanvasHandle>>, options: Opt<Value<'js>>| -> JsResult<Value<'js>> {
            let surface = this.0.borrow().0.clone();
            convert_to_blob(&ctx, &owned, &surface, options)
        },
    )?;
    define_method(&proto, "convertToBlob", f)?;
    Ok(())
}

const ENCODINGS: [&str; 3] = ["image/png", "image/jpeg", "image/webp"];

fn convert_to_blob<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<CanvasState>,
    surface: &Rc<Surface>,
    options: Opt<Value<'js>>,
) -> JsResult<Value<'js>> {
    let mut mime = "image/png".to_string();
    let mut quality = 0.92;
    if let Some(options) = options.0.as_ref().and_then(|v| v.as_object()) {
        if let Some(value) = options.get::<_, Option<Coerced<String>>>("type")? {
            let value = value.0.to_ascii_lowercase();
            if !ENCODINGS.contains(&value.as_str()) {
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
    let request_id = state.next_request.alloc();
    let (promise, settle) = PendingSettle::new(ctx)?;
    state.pending.borrow_mut().insert(request_id, Pending { kind: PendingKind::Encode, settle, staged: None });
    let arg = format!("{request_id}{FIELD}{mime}{FIELD}{quality}");
    let answer = state.host.canvas(OP_ENCODE, surface.id, &arg, None);
    if !answer.is_empty() {
        if let Some(pending) = take_pending(state, request_id) {
            let value = match wire_error_to_js(ctx, &answer) {
                Some(Ok(value)) => value,
                _ => crate::engine::error::make_plugin_error(ctx, "internal", &answer, None, None, None)?,
            };
            pending.settle.reject_with_value(ctx, value)?;
        }
    }
    Ok(promise.into_value())
}

use members::{install_context_members, install_gradient_members, install_image_members, install_pattern_members};

#[path = "canvas_members.rs"]
mod members;

fn build_answer<'js>(ctx: &Ctx<'js>, state: &Rc<CanvasState>, kind: &PendingKind, wire: &str) -> JsResult<Value<'js>> {
    match kind {
        PendingKind::Font => Ok(Value::new_undefined(ctx.clone())),
        PendingKind::Encode => {
            let object = parse_answer(ctx, wire)?;
            let path: String = object.get("path")?;
            let mime: String = object.get("type").unwrap_or_default();
            let path = PathBuf::from(path);
            let (size, mtime) = match fs::metadata(&path) {
                Ok(meta) => (
                    meta.len(),
                    meta.modified()
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
            let width: i32 = object.get("width")?;
            let height: i32 = object.get("height")?;
            let bytes = width.max(0) as usize * height.max(0) as usize * 4;
            // charged only now: until the host answered there was nothing allocated to charge for,
            // and a decode that failed must not leave a reservation behind
            let charge = match state.external.charge(ctx, bytes) {
                Ok(charge) => charge,
                Err(e) => {
                    state.host.canvas(OP_RELEASE_IMAGE, image.id, "", None);
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
                state: state.clone(),
            };
            Ok(Class::instance(ctx.clone(), ImageHandle(Rc::new(handle)))?.into_value())
        }
    }
}

fn parse_answer<'js>(ctx: &Ctx<'js>, wire: &str) -> JsResult<Object<'js>> {
    let json = wire.strip_prefix('J').ok_or_else(|| Exception::throw_message(ctx, "canvas: malformed host answer"))?;
    crate::api::json_parse(ctx, json)?
        .into_object()
        .ok_or_else(|| Exception::throw_message(ctx, "canvas: malformed host answer"))
}

/// releases every `Persistent` this state owns and deletes anything still staged - same contract as
/// [`crate::io::fetch::dispose`]
pub fn dispose(context: &rquickjs::Context, state: &Rc<CanvasState>) {
    context.with(|ctx| {
        for (_, pending) in state.pending.borrow_mut().drain() {
            if let Some(path) = pending.staged.as_ref() {
                let _ = fs::remove_file(path);
            }
            pending.settle.release(&ctx);
        }
    });
}

#[cfg(test)]
#[path = "canvas_tests.rs"]
mod tests;

/// settles one `convertToBlob`/`decode`/`load`/`loadFont`
pub fn canvas_result(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<CanvasState>,
    request_id: i64,
    result_wire: &str,
) {
    context.with(|ctx| {
        let Some(pending) = take_pending(state, request_id) else {
            return;
        };
        if let Some(built) = wire_error_to_js(&ctx, result_wire) {
            match built {
                Ok(value) => {
                    if pending.settle.reject_with_value(&ctx, value).is_err() {
                        (state.log)(&format!("canvas({request_id}) reject failed: {}", format_exception(&ctx)));
                    }
                }
                Err(e) => {
                    pending.settle.release(&ctx);
                    (state.log)(&format!("canvas({request_id}) error decode failed: {e:?}"));
                }
            }
            return;
        }
        let built = build_answer(&ctx, state, &pending.kind, result_wire);
        match built {
            Ok(value) => {
                if pending.settle.resolve_with(&ctx, value).is_err() {
                    (state.log)(&format!("canvas({request_id}) resolve failed: {}", format_exception(&ctx)));
                }
            }
            Err(_) => {
                pending.settle.release(&ctx);
                (state.log)(&format!("canvas({request_id}) bad result wire: {}", format_exception(&ctx)));
            }
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

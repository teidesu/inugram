use std::cell::RefCell;
use std::rc::Rc;

use rquickjs::{Context, Runtime};

use super::*;
use crate::api::error::install_plugin_error;
use crate::api::io::fs::tests::TestDir;
use crate::sandbox::limits::ExternalMemory;

/// mirrors `PluginCanvas.decodeTable`, deliberately over UTF-16 units the way the host reads it:
/// the point of the length prefix is that no entry can be mistaken for two, and a decoder that
/// agreed with a broken encoder is what let the separator collision ship
fn decode_table(arg: &str) -> Vec<String> {
  let units: Vec<u16> = arg.encode_utf16().collect();
  let mut out = Vec::new();
  let mut at = 0;
  while at < units.len() {
    let Some(offset) = units[at..].iter().position(|u| *u == ITEM as u16) else {
      break;
    };
    let separator = at + offset;
    let Ok(length) = String::from_utf16_lossy(&units[at..separator]).parse::<usize>() else {
      break;
    };
    let start = separator + 1;
    let end = (start + length).min(units.len());
    out.push(String::from_utf16_lossy(&units[start..end]));
    at = end;
  }
  out
}

#[derive(Default)]
struct Recorder {
  calls: Vec<(i32, i64, String)>,
  commands: Vec<Vec<u8>>,
  strings: Vec<Vec<String>>,
  canvases: Vec<i64>,
  released: Vec<i64>,
}

struct OracleHost {
  log: RefCell<Recorder>,
  blend_modes: bool,
  /// what the next asynchronous op should be answered with, or `None` to leave it pending
  reply: RefCell<Option<String>>,
  measure: RefCell<String>,
  average: RefCell<String>,
  fail: RefCell<Option<(i32, String)>>,
  pending: RefCell<Vec<i64>>,
}

impl OracleHost {
  fn new() -> Rc<OracleHost> {
    OracleHost::with_blend_modes(true)
  }

  fn with_blend_modes(blend_modes: bool) -> Rc<OracleHost> {
    Rc::new(OracleHost {
            log: RefCell::new(Recorder::default()),
            blend_modes,
            reply: RefCell::new(None),
            measure: RefCell::new(
                r#"J{"width":42,"actualBoundingBoxLeft":0,"actualBoundingBoxRight":42,"actualBoundingBoxAscent":8,"actualBoundingBoxDescent":2,"fontBoundingBoxAscent":9,"fontBoundingBoxDescent":3}"#
                    .to_string(),
            ),
            average: RefCell::new(r#"J{"r":10,"g":20,"b":30,"a":255}"#.to_string()),
            fail: RefCell::new(None),
            pending: RefCell::new(Vec::new()),
        })
  }
}

impl CanvasHost for OracleHost {
  fn canvas(&self, op: i32, id: i64, arg: &str, bytes: Option<&[u8]>) -> String {
    self.log.borrow_mut().calls.push((op, id, arg.to_string()));
    if let Some((failing, message)) = self.fail.borrow().as_ref() {
      if *failing == op {
        return message.clone();
      }
    }
    match op {
      OP_CAPABILITIES => {
        if self.blend_modes {
          r#"J{"blend":true}"#.to_string()
        } else {
          r#"J{"blend":false}"#.to_string()
        }
      }
      OP_CREATE => {
        self.log.borrow_mut().canvases.push(id);
        String::new()
      }
      OP_REPLAY => {
        let mut log = self.log.borrow_mut();
        log.commands.push(bytes.unwrap_or_default().to_vec());
        log.strings.push(decode_table(arg));
        String::new()
      }
      OP_MEASURE => self.measure.borrow().clone(),
      OP_AVERAGE => self.average.borrow().clone(),
      OP_RELEASE_IMAGE => {
        self.log.borrow_mut().released.push(id);
        String::new()
      }
      OP_ENCODE | OP_DECODE | OP_LOAD_FONT => {
        let request = arg.split(FIELD).next().and_then(|v| v.parse().ok()).unwrap_or(0);
        self.pending.borrow_mut().push(request);
        self.reply.borrow().clone().unwrap_or_default()
      }
      _ => String::new(),
    }
  }
}

struct Reader<'a> {
  bytes: &'a [u8],
  at: usize,
}

impl<'a> Reader<'a> {
  fn u8(&mut self) -> u8 {
    let v = self.bytes[self.at];
    self.at += 1;
    v
  }

  fn u32(&mut self) -> u32 {
    let v = u32::from_le_bytes(self.bytes[self.at..self.at + 4].try_into().unwrap());
    self.at += 4;
    v
  }

  fn i32(&mut self) -> i32 {
    self.u32() as i32
  }

  fn i64(&mut self) -> i64 {
    let v = i64::from_le_bytes(self.bytes[self.at..self.at + 8].try_into().unwrap());
    self.at += 8;
    v
  }

  fn f(&mut self) -> f32 {
    let v = f32::from_le_bytes(self.bytes[self.at..self.at + 4].try_into().unwrap());
    self.at += 4;
    v
  }

  fn matrix(&mut self) -> [f32; 6] {
    [self.f(), self.f(), self.f(), self.f(), self.f(), self.f()]
  }

  fn paint(&mut self) -> Paint {
    let alpha = self.f();
    let composite = self.u8();
    let shadow = [self.f(), self.f(), self.f()];
    let shadow_color = self.i32();
    let kind = self.u8();
    let mut coords = Vec::new();
    let mut stops = Vec::new();
    let mut color = 0;
    let mut pattern = None;
    match kind {
      STYLE_COLOR => color = self.i32(),
      STYLE_PATTERN => {
        let source = self.u8();
        let id = self.i64();
        let repeat = self.u8();
        let matrix = self.matrix();
        pattern = Some((source, id, repeat, matrix));
      }
      other => {
        let count = match other {
          STYLE_LINEAR => 4,
          STYLE_RADIAL => 6,
          _ => 3,
        };
        for _ in 0..count {
          coords.push(self.f());
        }
        let n = self.u32();
        for _ in 0..n {
          stops.push((self.f(), self.i32()));
        }
      }
    }
    Paint {
      alpha,
      composite,
      shadow,
      shadow_color,
      kind,
      color,
      coords,
      stops,
      pattern,
    }
  }

  fn stroke(&mut self) -> Stroke {
    let width = self.f();
    let cap = self.u8();
    let join = self.u8();
    let miter = self.f();
    let dash_offset = self.f();
    let n = self.u32();
    let mut dash = Vec::new();
    for _ in 0..n {
      dash.push(self.f());
    }
    Stroke {
      width,
      cap,
      join,
      miter,
      dash_offset,
      dash,
    }
  }

  fn path(&mut self) -> Vec<(u8, Vec<f32>)> {
    let n = self.u32();
    let mut out = Vec::new();
    for _ in 0..n {
      let verb = self.u8();
      let count = match verb {
        0 | 1 => 2,
        2 => 6,
        _ => 0,
      };
      let mut points = Vec::new();
      for _ in 0..count {
        points.push(self.f());
      }
      out.push((verb, points));
    }
    out
  }
}

#[derive(Debug)]
#[allow(dead_code)] // the decoder mirrors the wire; a field no test reads still documents it
struct Paint {
  alpha: f32,
  composite: u8,
  shadow: [f32; 3],
  shadow_color: i32,
  kind: u8,
  color: i32,
  coords: Vec<f32>,
  stops: Vec<(f32, i32)>,
  pattern: Option<(u8, i64, u8, [f32; 6])>,
}

#[derive(Debug)]
#[allow(dead_code)]
struct Stroke {
  width: f32,
  cap: u8,
  join: u8,
  miter: f32,
  dash_offset: f32,
  dash: Vec<f32>,
}

#[derive(Debug)]
#[allow(dead_code)]
enum Command {
  Save,
  Restore,
  Reset,
  Fill {
    matrix: [f32; 6],
    paint: Paint,
    rule: u8,
    path: Vec<(u8, Vec<f32>)>,
  },
  Stroke {
    matrix: [f32; 6],
    paint: Paint,
    stroke: Stroke,
    path: Vec<(u8, Vec<f32>)>,
  },
  Clip {
    matrix: [f32; 6],
    rule: u8,
    path: Vec<(u8, Vec<f32>)>,
  },
  Clear {
    matrix: [f32; 6],
    path: Vec<(u8, Vec<f32>)>,
  },
  Text {
    matrix: [f32; 6],
    stroked: bool,
    paint: Paint,
    font: u32,
    align: u8,
    baseline: u8,
    x: f32,
    y: f32,
    max_width: f32,
    text: u32,
  },
  Image {
    matrix: [f32; 6],
    paint: Paint,
    source: u8,
    id: i64,
    src: [f32; 4],
    dst: [f32; 4],
  },
}

fn decode(bytes: &[u8]) -> Vec<Command> {
  let mut reader = Reader { bytes, at: 0 };
  let mut out = Vec::new();
  while reader.at < bytes.len() {
    let command = reader.u8();
    out.push(match command {
      CMD_SAVE => Command::Save,
      CMD_RESTORE => Command::Restore,
      CMD_RESET => Command::Reset,
      CMD_FILL => {
        let matrix = reader.matrix();
        let paint = reader.paint();
        let rule = reader.u8();
        Command::Fill { matrix, paint, rule, path: reader.path() }
      }
      CMD_STROKE => {
        let matrix = reader.matrix();
        let paint = reader.paint();
        let stroke = reader.stroke();
        Command::Stroke {
          matrix,
          paint,
          stroke,
          path: reader.path(),
        }
      }
      CMD_CLIP => {
        let matrix = reader.matrix();
        let rule = reader.u8();
        Command::Clip { matrix, rule, path: reader.path() }
      }
      CMD_CLEAR => {
        let matrix = reader.matrix();
        Command::Clear { matrix, path: reader.path() }
      }
      CMD_TEXT => {
        let matrix = reader.matrix();
        let stroked = reader.u8() == 1;
        let paint = reader.paint();
        let stroke = if stroked { Some(reader.stroke()) } else { None };
        let _ = stroke;
        Command::Text {
          matrix,
          stroked,
          paint,
          font: reader.u32(),
          align: reader.u8(),
          baseline: reader.u8(),
          x: reader.f(),
          y: reader.f(),
          max_width: reader.f(),
          text: reader.u32(),
        }
      }
      CMD_IMAGE => {
        let matrix = reader.matrix();
        let paint = reader.paint();
        let source = reader.u8();
        let id = reader.i64();
        let src = [reader.f(), reader.f(), reader.f(), reader.f()];
        let dst = [reader.f(), reader.f(), reader.f(), reader.f()];
        Command::Image { matrix, paint, source, id, src, dst }
      }
      other => panic!("unknown command {other}"),
    });
  }
  out
}

/// releases the state's GC roots before the runtime goes, since a `Persistent` left behind aborts
/// `JS_FreeRuntime`. First field, because fields drop in declaration order.
struct DisposeOnDrop {
  ctx: Context,
  state: Rc<CanvasState>,
}

impl Drop for DisposeOnDrop {
  fn drop(&mut self) {
    self.state.dispose(&self.ctx);
  }
}

struct Fixture {
  _dispose: DisposeOnDrop,
  _rt: Runtime,
  ctx: Context,
  host: Rc<OracleHost>,
  state: Rc<CanvasState>,
  _dir: TestDir,
}

fn setup(name: &str) -> Fixture {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let dir = TestDir::new(name);
  let host = OracleHost::new();
  let external = ExternalMemory::new();
  let host_dyn: Rc<dyn CanvasHost> = host.clone();
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::inu_namespace(&ctx);
    install_plugin_error(&ctx, &inu).unwrap();
    let blobs = crate::api::io::blob::install(&ctx, dir.path(), external.clone()).unwrap();
    install_canvas(&ctx, host_dyn, blobs, external, dir.path().to_path_buf(), std::sync::Arc::new(|_: &str| {}), &inu)
      .unwrap()
  });
  Fixture {
    _dispose: DisposeOnDrop { ctx: ctx.clone(), state: state.clone() },
    _rt: rt,
    ctx,
    host,
    state,
    _dir: dir,
  }
}

fn run(f: &Fixture, code: &str) {
  crate::testing::harness::eval_unit(&f.ctx, code)
}

fn eval(f: &Fixture, code: &str) -> String {
  crate::testing::harness::eval_string(&f.ctx, code)
}

/// runs `code` and answers `code:message` for whatever `PluginError` it raised, so a refusal is
/// asserted on rather than merely observed to be a failure
fn refusal(f: &Fixture, code: &str) -> String {
  eval(
    f,
    &format!(
      r#"(() => {{ try {{ {code}; return 'no error' }} catch (e) {{ return `${{e.code}}:${{e.message}}` }} }})()"#,
    ),
  )
}

fn settle(f: &Fixture, expr: &str) -> String {
  run(
    f,
    &format!(
      r#"
            globalThis.__out = 'pending';
            Promise.resolve().then(() => {expr}).then(
                v => {{ globalThis.__out = 'ok:' + (v && v.constructor ? v.constructor.name : v) }},
                e => {{ globalThis.__out = `${{e.code}}:${{e.message}}` }},
            );
            "#,
    ),
  );
  while f._rt.is_job_pending() {
    f._rt.execute_pending_job().ok();
  }
  eval(f, "String(globalThis.__out)")
}

/// answers the asynchronous op the host is holding, the way `nativeCanvasResult` does
fn answer(f: &Fixture, wire: &str) {
  let request = f.host.pending.borrow_mut().pop().expect("nothing pending");
  f.state.resolve(&f._rt, &f.ctx, request, wire);
}

fn commands(f: &Fixture) -> Vec<Command> {
  let log = f.host.log.borrow();
  decode(log.commands.last().expect("nothing was replayed"))
}

fn draw(f: &Fixture, body: &str) -> Vec<Command> {
  run(
    f,
    &format!(
      r#"
            globalThis.c = globalThis.c ?? inu.canvas.create(100, 100);
            globalThis.x = globalThis.c.getContext('2d');
            {body}
            "#,
    ),
  );
  // nothing reads the pixels, so the buffer has to be pushed the way `convertToBlob` would
  f.ctx.with(|ctx| {
    let canvas: rquickjs::Value = ctx.globals().get("c").unwrap();
    let handle = Class::<CanvasHandle>::from_value(&canvas).unwrap();
    let surface = handle.borrow().0.clone();
    surface.flush(&ctx).unwrap();
  });
  commands(f)
}

#[test]
fn the_namespace_carries_exactly_what_the_contract_declares() {
  let f = setup("namespace");
  assert_eq!(eval(&f, "Object.keys(inu.canvas).sort().join(',')"), "create,decode,load,loadFont");
}

#[test]
fn a_canvas_answers_its_own_size_and_the_same_context_every_time() {
  let f = setup("basics");
  run(&f, "globalThis.c = inu.canvas.create(320, 240)");
  assert_eq!(eval(&f, "`${c.width}x${c.height}`"), "320x240");
  assert_eq!(eval(&f, "String(c.getContext('2d') === c.getContext('2d'))"), "true");
  assert_eq!(eval(&f, "String(c.getContext('2d').canvas === c)"), "true");
}

#[test]
fn only_the_2d_context_exists() {
  let f = setup("context-id");
  run(&f, "globalThis.c = inu.canvas.create(4, 4)");
  assert!(refusal(&f, "c.getContext('webgl')").starts_with("invalid-argument:"));
}

#[test]
fn a_canvas_is_refused_before_it_is_allocated_rather_than_after() {
  let f = setup("dimensions");
  for (call, what) in [
    ("inu.canvas.create(0, 10)", "positive"),
    ("inu.canvas.create(10, -1)", "positive"),
    ("inu.canvas.create(99999, 10)", "at most"),
  ] {
    let message = refusal(&f, call);
    assert!(message.starts_with("invalid-argument:"), "{call} answered {message}");
    assert!(message.contains(what), "{call} answered {message}");
  }
  assert!(f.host.log.borrow().canvases.is_empty(), "a refused canvas was still allocated");
}

#[test]
fn resizing_reallocates_and_throws_the_recorded_drawing_away() {
  let f = setup("resize");
  run(
    &f,
    r#"
        globalThis.c = inu.canvas.create(10, 10)
        const x = c.getContext('2d')
        x.fillRect(0, 0, 5, 5)
        c.width = 20
        "#,
  );
  let created = f.host.log.borrow().canvases.clone();
  assert_eq!(created.len(), 2, "the resize did not reallocate: {created:?}");
  assert_eq!(created[0], created[1], "the resize took a new id");
  assert_eq!(eval(&f, "`${c.width}x${c.height}`"), "20x10");
  assert!(f.host.log.borrow().commands.is_empty(), "the discarded drawing was replayed anyway");
}

#[test]
fn a_canvas_costs_its_pixels_against_the_native_budget() {
  let f = setup("budget");
  let before = f.ctx.with(|_| 0);
  let _ = before;
  run(&f, "globalThis.c = inu.canvas.create(1000, 1000)");
  // 1000 * 1000 * 4, and nothing else here charges
  assert!(refusal(&f, "inu.canvas.create(8192, 8192)").starts_with("quota-exceeded:"));
  run(&f, "globalThis.c = null");
  f.ctx.with(|ctx| ctx.run_gc());
  assert_eq!(refusal(&f, "inu.canvas.create(2000, 2000)"), "no error", "the freed canvas was not credited back");
}

#[test]
fn save_and_restore_cross_the_wire_because_the_clip_lives_on_the_other_side() {
  let f = setup("save");
  let commands = draw(&f, "x.save(); x.restore(); x.reset()");
  assert!(matches!(commands[0], Command::Save));
  assert!(matches!(commands[1], Command::Restore));
  assert!(matches!(commands[2], Command::Reset));
}

#[test]
fn restoring_an_empty_stack_says_nothing_to_the_host() {
  let f = setup("save-empty");
  let commands = draw(&f, "x.restore(); x.save(); x.restore(); x.restore()");
  assert_eq!(commands.len(), 2, "{commands:?}");
}

#[test]
fn restore_puts_back_every_piece_of_state() {
  let f = setup("restore-state");
  run(
    &f,
    r#"
        const x = inu.canvas.create(10, 10).getContext('2d')
        x.fillStyle = '#ff0000'
        x.lineWidth = 9
        x.font = 'italic bold 20px Roboto'
        x.save()
        x.fillStyle = '#00ff00'
        x.lineWidth = 1
        x.font = '10px monospace'
        x.restore()
        globalThis.out = `${x.fillStyle}|${x.lineWidth}|${x.font}`
        "#,
  );
  assert_eq!(eval(&f, "out"), "#ff0000|9|italic bold 20px Roboto");
}

#[test]
fn reset_returns_the_context_to_its_initial_state() {
  let f = setup("reset-state");
  run(
    &f,
    r#"
        const x = inu.canvas.create(10, 10).getContext('2d')
        x.fillStyle = 'red'
        x.translate(5, 5)
        x.reset()
        globalThis.out = x.fillStyle
        "#,
  );
  assert_eq!(eval(&f, "out"), "#000000");
}

#[test]
fn a_draw_carries_the_transform_and_the_path_pulled_back_through_it() {
  let f = setup("transform");
  let commands = draw(&f, "x.translate(10, 20); x.scale(2, 2); x.fillRect(0, 0, 5, 5)");
  let Command::Fill { matrix, path, .. } = &commands[0] else { panic!("{commands:?}") };
  assert_eq!(*matrix, [2.0, 0.0, 0.0, 2.0, 10.0, 20.0]);
  // the rect is handed over in the user space the matrix describes, not in device pixels
  assert_eq!(path[0].1, vec![0.0, 0.0]);
  assert_eq!(path[1].1, vec![5.0, 0.0]);
}

#[test]
fn a_path_built_across_two_transforms_keeps_each_point_where_it_was_put() {
  let f = setup("transform-mid-path");
  let commands = draw(
    &f,
    "x.beginPath(); x.moveTo(0, 0); x.translate(50, 0); x.lineTo(0, 0); x.resetTransform(); x.stroke()",
  );
  let Command::Stroke { path, matrix, .. } = &commands[0] else { panic!("{commands:?}") };
  assert_eq!(*matrix, [1.0, 0.0, 0.0, 1.0, 0.0, 0.0]);
  assert_eq!(path[0].1, vec![0.0, 0.0]);
  assert_eq!(path[1].1, vec![50.0, 0.0], "the second point moved with the transform");
}

#[test]
fn a_transform_with_no_inverse_draws_nothing_rather_than_dividing_by_it() {
  let f = setup("singular");
  let f2 = setup("singular-control");
  let commands = draw(&f2, "x.fillRect(0, 0, 5, 5)");
  assert_eq!(commands.len(), 1);
  run(
    &f,
    r#"
        globalThis.c = inu.canvas.create(10, 10)
        globalThis.x = c.getContext('2d')
        x.setTransform(1, 0, 2, 0, 0, 0)
        x.fillRect(0, 0, 5, 5)
        "#,
  );
  assert!(f.host.log.borrow().commands.is_empty(), "a flattened transform still drew");
}

#[test]
fn a_non_finite_transform_argument_leaves_the_transform_alone() {
  let f = setup("transform-nan");
  let commands = draw(&f, "x.translate(10, 0); x.scale(NaN, 2); x.rotate(Infinity); x.fillRect(0, 0, 1, 1)");
  let Command::Fill { matrix, .. } = &commands[0] else { panic!("{commands:?}") };
  assert_eq!(*matrix, [1.0, 0.0, 0.0, 1.0, 10.0, 0.0]);
}

#[test]
fn set_transform_takes_both_of_the_shapes_the_spec_gives_it() {
  let f = setup("set-transform");
  let commands = draw(&f, "x.setTransform(2, 0, 0, 2, 1, 1); x.fillRect(0, 0, 1, 1)");
  let Command::Fill { matrix, .. } = &commands[0] else { panic!("{commands:?}") };
  assert_eq!(*matrix, [2.0, 0.0, 0.0, 2.0, 1.0, 1.0]);

  let commands = draw(&f, "x.setTransform({ a: 3, f: 7 }); x.fillRect(0, 0, 1, 1)");
  let Command::Fill { matrix, .. } = &commands[0] else { panic!("{commands:?}") };
  assert_eq!(*matrix, [3.0, 0.0, 0.0, 1.0, 0.0, 7.0], "the omitted members took their identity values");
}

#[test]
fn a_colour_reads_back_in_the_form_the_spec_serializes() {
  let f = setup("colour-roundtrip");
  run(&f, "globalThis.x = inu.canvas.create(4, 4).getContext('2d')");
  for (set, back) in
    [("red", "#ff0000"), ("#0f0", "#00ff00"), ("rgb(1 2 3)", "#010203"), ("rgba(0, 0, 0, 0.5)", "rgba(0, 0, 0, 0.502)")]
  {
    run(&f, &format!("x.fillStyle = '{set}'"));
    assert_eq!(eval(&f, "x.fillStyle"), back, "for '{set}'");
  }
}

#[test]
fn an_unparseable_colour_leaves_the_previous_one_in_place() {
  let f = setup("colour-bad");
  run(
    &f,
    r#"
        globalThis.x = inu.canvas.create(4, 4).getContext('2d')
        x.fillStyle = 'red'
        x.fillStyle = 'not a colour'
        x.fillStyle = 'rgb(300)'
        "#,
  );
  assert_eq!(eval(&f, "x.fillStyle"), "#ff0000");
}

#[test]
fn a_gradient_is_encoded_with_the_stops_it_has_when_it_is_drawn_with() {
  let f = setup("gradient-late-stops");
  let commands = draw(
    &f,
    r#"
        const g = x.createLinearGradient(0, 0, 100, 0)
        x.fillStyle = g
        g.addColorStop(0, 'black')
        g.addColorStop(1, 'white')
        x.fillRect(0, 0, 10, 10)
        "#,
  );
  let Command::Fill { paint, .. } = &commands[0] else { panic!("{commands:?}") };
  assert_eq!(paint.kind, STYLE_LINEAR);
  assert_eq!(paint.coords, vec![0.0, 0.0, 100.0, 0.0]);
  assert_eq!(paint.stops.len(), 2, "the stops added after the assignment were lost");
}

#[test]
fn colour_stops_come_out_sorted_with_ties_in_the_order_they_were_added() {
  let f = setup("gradient-order");
  let commands = draw(
    &f,
    r#"
        const g = x.createLinearGradient(0, 0, 1, 0)
        g.addColorStop(1, '#ff0000')
        g.addColorStop(0, '#00ff00')
        g.addColorStop(0.5, '#0000ff')
        g.addColorStop(0.5, '#ffffff')
        x.fillStyle = g
        x.fillRect(0, 0, 1, 1)
        "#,
  );
  let Command::Fill { paint, .. } = &commands[0] else { panic!("{commands:?}") };
  let offsets: Vec<f32> = paint.stops.iter().map(|(o, _)| *o).collect();
  assert_eq!(offsets, vec![0.0, 0.5, 0.5, 1.0]);
  assert_eq!(paint.stops[1].1 as u32, 0xff00_00ff, "the equal offsets swapped");
}

#[test]
fn a_gradient_refuses_a_stop_outside_the_unit_range_and_a_colour_it_cannot_read() {
  let f = setup("gradient-bad");
  run(&f, "globalThis.g = inu.canvas.create(4,4).getContext('2d').createLinearGradient(0,0,1,0)");
  assert!(refusal(&f, "g.addColorStop(2, 'red')").starts_with("invalid-argument:"));
  assert!(refusal(&f, "g.addColorStop(NaN, 'red')").starts_with("invalid-argument:"));
  assert!(refusal(&f, "g.addColorStop(0, 'nope')").starts_with("invalid-argument:"));
}

#[test]
fn a_gradient_stops_taking_stops_at_the_stated_ceiling() {
  let f = setup("gradient-ceiling");
  run(&f, "globalThis.g = inu.canvas.create(4,4).getContext('2d').createLinearGradient(0,0,1,0)");
  let message =
    refusal(&f, &format!("for (let i = 0; i < {}; i++) g.addColorStop(i / 1000, 'red')", MAX_GRADIENT_STOPS + 1));
  assert!(message.starts_with("quota-exceeded:"), "{message}");
}

#[test]
fn a_radial_gradient_refuses_a_negative_radius() {
  let f = setup("gradient-radius");
  run(&f, "globalThis.x = inu.canvas.create(4,4).getContext('2d')");
  assert!(refusal(&f, "x.createRadialGradient(0, 0, -1, 0, 0, 5)").starts_with("invalid-argument:"));
}

#[test]
fn a_pattern_carries_its_source_its_repetition_and_its_own_transform() {
  let f = setup("pattern");
  let commands = draw(
    &f,
    r#"
        const other = inu.canvas.create(8, 8)
        const p = x.createPattern(other, 'repeat-x')
        p.setTransform({ a: 2, d: 2 })
        x.fillStyle = p
        x.fillRect(0, 0, 10, 10)
        "#,
  );
  let Command::Fill { paint, .. } = &commands[0] else { panic!("{commands:?}") };
  let (source, _, repeat, matrix) = paint.pattern.expect("no pattern");
  assert_eq!(source, SOURCE_CANVAS);
  assert_eq!(repeat, 1);
  assert_eq!(matrix, [2.0, 0.0, 0.0, 2.0, 0.0, 0.0]);
}

#[test]
fn a_pattern_refuses_a_repetition_it_does_not_know() {
  let f = setup("pattern-repeat");
  run(&f, "globalThis.x = inu.canvas.create(4,4).getContext('2d')");
  run(&f, "globalThis.o = inu.canvas.create(4,4)");
  assert!(refusal(&f, "x.createPattern(o, 'tile')").starts_with("invalid-argument:"));
  assert!(refusal(&f, "x.createPattern(42, 'repeat')").starts_with("invalid-argument:"));
}

#[test]
fn the_shadow_is_pushed_through_the_inverse_so_it_survives_the_transform() {
  let f = setup("shadow");
  let commands = draw(
    &f,
    r#"
        x.shadowColor = 'black'
        x.shadowOffsetX = 4
        x.shadowOffsetY = 8
        x.shadowBlur = 10
        x.scale(2, 2)
        x.fillRect(0, 0, 1, 1)
        "#,
  );
  let Command::Fill { paint, .. } = &commands[0] else { panic!("{commands:?}") };
  // the host draws under a 2x transform, so a 4px device offset has to be handed over as 2
  assert_eq!(paint.shadow, [5.0, 2.0, 4.0]);
  assert_eq!(paint.shadow_color as u32, 0xff00_0000);
}

#[test]
fn the_stroke_block_carries_the_pen_and_the_dash_pattern() {
  let f = setup("stroke-block");
  let commands = draw(
    &f,
    r#"
        x.lineWidth = 3
        x.lineCap = 'round'
        x.lineJoin = 'bevel'
        x.miterLimit = 4
        x.setLineDash([1, 2, 3])
        x.lineDashOffset = 5
        x.beginPath(); x.moveTo(0, 0); x.lineTo(10, 10); x.stroke()
        "#,
  );
  let Command::Stroke { stroke, .. } = &commands[0] else { panic!("{commands:?}") };
  assert_eq!(stroke.width, 3.0);
  assert_eq!((stroke.cap, stroke.join), (1, 1));
  assert_eq!(stroke.miter, 4.0);
  assert_eq!(stroke.dash_offset, 5.0);
  // an odd list is doubled, so the host never has to know that rule
  assert_eq!(stroke.dash, vec![1.0, 2.0, 3.0, 1.0, 2.0, 3.0]);
}

#[test]
fn get_line_dash_answers_the_doubled_list_the_spec_stores() {
  let f = setup("dash-getter");
  run(
    &f,
    r#"
        globalThis.x = inu.canvas.create(4,4).getContext('2d')
        x.setLineDash([4, 2])
        globalThis.a = x.getLineDash().join(',')
        x.setLineDash([5])
        globalThis.b = x.getLineDash().join(',')
        x.setLineDash([1, -1])
        globalThis.c2 = x.getLineDash().join(',')
        "#,
  );
  assert_eq!(eval(&f, "a"), "4,2");
  assert_eq!(eval(&f, "b"), "5,5");
  assert_eq!(eval(&f, "c2"), "5,5", "one bad entry has to leave the whole list alone");
}

#[test]
fn an_out_of_range_setter_is_ignored_rather_than_clamped() {
  let f = setup("setters");
  run(
    &f,
    r#"
        globalThis.x = inu.canvas.create(4,4).getContext('2d')
        x.globalAlpha = 0.5
        x.globalAlpha = 2
        x.globalAlpha = -1
        x.lineWidth = 4
        x.lineWidth = 0
        x.lineCap = 'squircle'
        x.globalCompositeOperation = 'nonsense'
        "#,
  );
  assert_eq!(
    eval(&f, "`${x.globalAlpha}|${x.lineWidth}|${x.lineCap}|${x.globalCompositeOperation}`"),
    "0.5|4|butt|source-over"
  );
}

#[test]
fn every_composite_mode_the_contract_declares_is_accepted() {
  let f = setup("composites");
  run(&f, "globalThis.x = inu.canvas.create(4,4).getContext('2d')");
  for (index, mode) in COMPOSITE_MODES.iter().enumerate() {
    run(&f, &format!("x.globalCompositeOperation = '{mode}'"));
    assert_eq!(eval(&f, "x.globalCompositeOperation"), *mode);
    let commands = draw(&f, &format!("x.globalCompositeOperation = '{mode}'; x.fillRect(0,0,1,1)"));
    let Command::Fill { paint, .. } = &commands[0] else { panic!("{commands:?}") };
    assert_eq!(paint.composite as usize, index);
  }
}

#[test]
fn a_blend_mode_the_host_cannot_honour_is_refused_rather_than_approximated() {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let dir = TestDir::new("no-blend");
  let host = OracleHost::with_blend_modes(false);
  let external = ExternalMemory::new();
  let host_dyn: Rc<dyn CanvasHost> = host.clone();
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::inu_namespace(&ctx);
    install_plugin_error(&ctx, &inu).unwrap();
    let blobs = crate::api::io::blob::install(&ctx, dir.path(), external.clone()).unwrap();
    install_canvas(&ctx, host_dyn, blobs, external, dir.path().to_path_buf(), std::sync::Arc::new(|_: &str| {}), &inu)
      .unwrap()
  });
  let f = Fixture {
    _dispose: DisposeOnDrop { ctx: ctx.clone(), state: state.clone() },
    _rt: rt,
    ctx,
    host,
    state,
    _dir: dir,
  };
  run(&f, "globalThis.x = inu.canvas.create(4,4).getContext('2d')");
  let message = refusal(&f, "x.globalCompositeOperation = 'multiply'; x.fillRect(0,0,1,1)");
  assert!(message.starts_with("unsupported:"), "{message}");
  // the porter-duff set is always available
  assert_eq!(refusal(&f, "x.globalCompositeOperation = 'xor'; x.fillRect(0,0,1,1)"), "no error");
}

#[test]
fn fill_and_clip_carry_the_rule_and_stroke_does_not_take_one() {
  let f = setup("fill-rule");
  let commands = draw(&f, "x.beginPath(); x.rect(0, 0, 4, 4); x.fill('evenodd'); x.clip('nonzero'); x.stroke()");
  let Command::Fill { rule, .. } = &commands[0] else { panic!("{commands:?}") };
  assert_eq!(*rule, 1);
  let Command::Clip { rule, .. } = &commands[1] else { panic!("{commands:?}") };
  assert_eq!(*rule, 0);
  assert!(matches!(commands[2], Command::Stroke { .. }));
}

#[test]
fn an_unknown_fill_rule_is_refused() {
  let f = setup("fill-rule-bad");
  run(&f, "globalThis.x = inu.canvas.create(4,4).getContext('2d')");
  assert!(refusal(&f, "x.fill('winding')").starts_with("invalid-argument:"));
}

#[test]
fn an_empty_path_says_nothing_to_the_host() {
  let f = setup("empty-path");
  run(
    &f,
    r#"
        globalThis.c = inu.canvas.create(10, 10)
        globalThis.x = c.getContext('2d')
        x.beginPath()
        x.fill()
        x.stroke()
        x.fillRect(0, 0, 0, 10)
        "#,
  );
  assert!(f.host.log.borrow().commands.is_empty());
}

#[test]
fn a_rect_draw_does_not_leave_the_stray_point_the_path_op_does() {
  let f = setup("rect-op");
  let commands = draw(&f, "x.fillRect(1, 2, 3, 4)");
  let Command::Fill { path, .. } = &commands[0] else { panic!("{commands:?}") };
  assert_eq!(path.len(), 5, "{path:?}");
  assert_eq!(path[4].0, 3, "the outline is not closed");
}

#[test]
fn round_rect_takes_every_radii_shape_the_spec_lists() {
  let f = setup("round-rect-radii");
  run(&f, "globalThis.x = inu.canvas.create(20, 20).getContext('2d')");
  for radii in ["4", "[4]", "[4, 2]", "[4, 2, 1]", "[4, 2, 1, 3]", "undefined"] {
    assert_eq!(
      refusal(&f, &format!("x.beginPath(); x.roundRect(0, 0, 10, 10, {radii})")),
      "no error",
      "for {radii}",
    );
  }
  assert!(refusal(&f, "x.roundRect(0, 0, 10, 10, -1)").starts_with("invalid-argument:"));
  assert!(refusal(&f, "x.roundRect(0, 0, 10, 10, [1, NaN])").starts_with("invalid-argument:"));
}

#[test]
fn a_negative_arc_radius_is_an_error_and_a_non_finite_one_is_not() {
  let f = setup("arc-radius");
  run(&f, "globalThis.x = inu.canvas.create(20, 20).getContext('2d')");
  assert!(refusal(&f, "x.arc(0, 0, -1, 0, 1)").starts_with("invalid-argument:"));
  assert!(refusal(&f, "x.ellipse(0, 0, 1, -1, 0, 0, 1)").starts_with("invalid-argument:"));
  assert!(refusal(&f, "x.arcTo(1, 1, 2, 2, -1)").starts_with("invalid-argument:"));
  assert_eq!(refusal(&f, "x.arc(0, 0, NaN, 0, 1)"), "no error");
}

#[test]
fn the_font_reads_back_as_it_was_written_and_a_bad_one_is_ignored() {
  let f = setup("font");
  run(
    &f,
    r#"
        globalThis.x = inu.canvas.create(4,4).getContext('2d')
        globalThis.initial = x.font
        x.font = 'italic small-caps bold 24px/30px "PT Sans", serif'
        globalThis.set = x.font
        x.font = 'not a font'
        globalThis.after = x.font
        "#,
  );
  assert_eq!(eval(&f, "initial"), "10px sans-serif");
  assert_eq!(eval(&f, "set"), eval(&f, "after"));
  assert!(eval(&f, "set").contains("24px"));
}

#[test]
fn text_crosses_as_an_interned_string_with_its_font_and_its_placement() {
  let f = setup("text");
  let commands = draw(
    &f,
    r#"
        x.font = 'bold 20px Roboto'
        x.textAlign = 'center'
        x.textBaseline = 'top'
        x.fillText('hi', 5, 6)
        x.fillText('hi', 7, 8, 40)
        x.strokeText('bye', 1, 2)
        "#,
  );
  let strings = f.host.log.borrow().strings.last().unwrap().clone();
  let Command::Text {
    font,
    text,
    align,
    baseline,
    x,
    y,
    max_width,
    stroked,
    ..
  } = &commands[0]
  else {
    panic!("{commands:?}")
  };
  assert_eq!(strings[*font as usize], "20\u{1e}700\u{1e}0\u{1e}0\u{1e}Roboto");
  assert_eq!(strings[*text as usize], "hi");
  assert_eq!((*align, *baseline), (4, 0));
  assert_eq!((*x, *y), (5.0, 6.0));
  assert_eq!(*max_width, -1.0, "no maxWidth has to be distinguishable from one of zero");
  assert!(!*stroked);

  let Command::Text { text: second, max_width, .. } = &commands[1] else { panic!("{commands:?}") };
  assert_eq!(second, text, "the same text was interned twice");
  assert_eq!(*max_width, 40.0);

  let Command::Text { stroked, .. } = &commands[2] else { panic!("{commands:?}") };
  assert!(*stroked);
  assert_eq!(strings.len(), 3, "the string table grew more than it had to: {strings:?}");
}

/// The font's family list and the table share [`ITEM`], so a joined table shifted every index
/// recorded after a multi-family font and painted a family name where the text should have been.
/// The text is asserted too, because a plugin may pass either separator inside one.
#[test]
fn a_multi_family_font_and_separator_bearing_text_survive_the_string_table() {
  let f = setup("table-separators");
  let commands = draw(
    &f,
    r#"
        x.font = 'italic small-caps bold 24px/30px "PT Sans", serif'
        x.fillText('a\u001fb\u001ec', 1, 2)
        "#,
  );
  let strings = f.host.log.borrow().strings.last().unwrap().clone();
  let Command::Text { font, text, .. } = &commands[0] else { panic!("{commands:?}") };
  assert_eq!(strings[*font as usize], "24\u{1e}700\u{1e}1\u{1e}1\u{1e}PT Sans\u{1f}serif");
  assert_eq!(strings[*text as usize], "a\u{1f}b\u{1e}c");
  assert_eq!(strings.len(), 2, "{strings:?}");
}

#[test]
fn measure_text_asks_the_host_with_the_font_and_alignment_it_would_draw_with() {
  let f = setup("measure");
  run(
    &f,
    r#"
        globalThis.x = inu.canvas.create(4,4).getContext('2d')
        x.font = '16px Roboto'
        x.textAlign = 'right'
        globalThis.m = x.measureText('hello')
        "#,
  );
  assert_eq!(eval(&f, "String(m.width)"), "42");
  assert_eq!(eval(&f, "String(m.fontBoundingBoxAscent)"), "9");
  let calls = f.host.log.borrow().calls.clone();
  let (_, _, arg) = calls.iter().rev().find(|(op, ..)| *op == OP_MEASURE).unwrap();
  assert_eq!(arg, "16\u{1e}400\u{1e}0\u{1e}0\u{1e}Roboto\u{1e}3\u{1e}hello");
}

#[test]
fn a_host_that_cannot_measure_raises_the_plugin_error_it_named() {
  let f = setup("measure-error");
  *f.host.fail.borrow_mut() = Some((OP_MEASURE, "Pinternal\n\n\n\nno typeface".to_string()));
  run(&f, "globalThis.x = inu.canvas.create(4,4).getContext('2d')");
  assert_eq!(refusal(&f, "x.measureText('hi')"), "internal:no typeface");
}

#[test]
fn draw_image_takes_all_three_argument_counts() {
  let f = setup("draw-image");
  run(&f, "globalThis.o = inu.canvas.create(8, 4)");
  let commands = draw(
    &f,
    r#"
        x.drawImage(o, 1, 2)
        x.drawImage(o, 1, 2, 30, 40)
        x.drawImage(o, 1, 2, 3, 4, 5, 6, 7, 8)
        "#,
  );
  let Command::Image { src, dst, source, .. } = &commands[0] else { panic!("{commands:?}") };
  assert_eq!(*source, SOURCE_CANVAS);
  assert_eq!(*src, [0.0, 0.0, 8.0, 4.0], "the whole source is the default");
  assert_eq!(*dst, [1.0, 2.0, 8.0, 4.0], "the natural size is the default");
  let Command::Image { dst, .. } = &commands[1] else { panic!("{commands:?}") };
  assert_eq!(*dst, [1.0, 2.0, 30.0, 40.0]);
  let Command::Image { src, dst, .. } = &commands[2] else { panic!("{commands:?}") };
  assert_eq!(*src, [1.0, 2.0, 3.0, 4.0]);
  assert_eq!(*dst, [5.0, 6.0, 7.0, 8.0]);
}

#[test]
fn draw_image_refuses_a_count_that_is_not_one_of_the_three() {
  let f = setup("draw-image-arity");
  run(&f, "globalThis.o = inu.canvas.create(8, 4)");
  run(&f, "globalThis.x = inu.canvas.create(8, 4).getContext('2d')");
  assert!(refusal(&f, "x.drawImage(o, 1, 2, 3)").starts_with("invalid-argument:"));
  assert!(refusal(&f, "x.drawImage(o)").starts_with("invalid-argument:"));
  assert!(refusal(&f, "x.drawImage({}, 1, 2)").starts_with("invalid-argument:"));
  assert!(refusal(&f, "x.drawImage(o, 1, 2, 3, 0, 5, 6, 7, 8)").starts_with("invalid-argument:"));
}

/// the snapshot the spec promises: whatever the source canvas has recorded has to be on its bitmap
/// before the copy, or the picture drawn is the one from before its last few ops
#[test]
fn naming_another_canvas_flushes_that_canvas_first() {
  let f = setup("cross-canvas-flush");
  run(
    &f,
    r#"
        globalThis.o = inu.canvas.create(8, 8)
        o.getContext('2d').fillRect(0, 0, 8, 8)
        globalThis.c = inu.canvas.create(8, 8)
        globalThis.x = c.getContext('2d')
        x.drawImage(o, 0, 0)
        "#,
  );
  let log = f.host.log.borrow();
  assert_eq!(log.commands.len(), 1, "the source canvas was not replayed");
  assert!(matches!(decode(&log.commands[0])[0], Command::Fill { .. }));
}

#[test]
fn a_pattern_over_a_canvas_flushes_it_too() {
  let f = setup("pattern-flush");
  run(
    &f,
    r#"
        globalThis.o = inu.canvas.create(8, 8)
        o.getContext('2d').fillRect(0, 0, 8, 8)
        globalThis.x = inu.canvas.create(8, 8).getContext('2d')
        x.createPattern(o, 'repeat')
        "#,
  );
  assert_eq!(f.host.log.borrow().commands.len(), 1);
}

#[test]
fn a_decoded_image_answers_its_size_and_frees_the_host_bitmap_when_disposed() {
  let f = setup("decode");
  run(&f, "globalThis.p = inu.canvas.decode(new Uint8Array([1,2,3]))");
  answer(&f, r#"J{"width":16,"height":9}"#);
  assert_eq!(settle(&f, "p.then(i => (globalThis.img = i, i.constructor.name))"), "ok:String");
  assert_eq!(eval(&f, "`${img.width}x${img.height}`"), "16x9");
  run(&f, "img.dispose()");
  assert_eq!(f.host.log.borrow().released.len(), 1);
  run(&f, "img.dispose()");
  assert_eq!(f.host.log.borrow().released.len(), 1, "a second dispose released it twice");
}

#[test]
fn drawing_a_disposed_image_is_handle_expired() {
  let f = setup("decode-expired");
  run(&f, "globalThis.p = inu.canvas.decode(new Uint8Array([1]))");
  answer(&f, r#"J{"width":4,"height":4}"#);
  settle(&f, "p.then(i => (globalThis.img = i, 1))");
  run(&f, "globalThis.x = inu.canvas.create(8,8).getContext('2d'); img.dispose()");
  assert!(refusal(&f, "x.drawImage(img, 0, 0)").starts_with("handle-expired:"));
}

/// A `drawImage` records an id the host resolves at replay, so a `dispose()` in between used to
/// free the bitmap out from under a command already recorded - and one id that resolves to nothing
/// fails the whole flush, silently discarding every command after it too. `dispose()` flushes
/// first, which keeps its own promise (the memory is back when it returns) without stranding
/// anything.
#[test]
fn disposing_an_image_flushes_the_buffers_that_named_it_rather_than_stranding_them() {
  let f = setup("dispose-flush");
  run(&f, "globalThis.p = inu.canvas.decode(new Uint8Array([1]))");
  answer(&f, r#"J{"width":4,"height":4}"#);
  settle(&f, "p.then(i => (globalThis.img = i, 1))");
  run(
    &f,
    r#"
        globalThis.x = inu.canvas.create(8, 8).getContext('2d')
        x.drawImage(img, 0, 0)
        "#,
  );
  assert!(f.host.log.borrow().commands.is_empty(), "the draw was performed rather than recorded");
  run(&f, "img.dispose()");
  assert_eq!(f.host.log.borrow().commands.len(), 1, "the buffer naming it was not flushed");
  assert_eq!(f.host.log.borrow().released.len(), 1, "the bitmap was not given back");
  // and what is drawn after it still reaches the host, on a buffer that no longer names it
  run(&f, "x.fillRect(0, 0, 1, 1)");
  f.ctx.with(|ctx| {
    let canvas: rquickjs::Value = ctx.globals().get("x").unwrap();
    let canvas: rquickjs::Value = canvas.as_object().unwrap().get("canvas").unwrap();
    let handle = Class::<CanvasHandle>::from_value(&canvas).unwrap();
    let surface = handle.borrow().0.clone();
    surface.flush(&ctx).unwrap();
  });
  assert_eq!(f.host.log.borrow().commands.len(), 2);
}

#[test]
fn a_pattern_outliving_its_image_fails_where_it_is_drawn_with() {
  let f = setup("pattern-expired");
  run(&f, "globalThis.p = inu.canvas.decode(new Uint8Array([1]))");
  answer(&f, r#"J{"width":4,"height":4}"#);
  settle(&f, "p.then(i => (globalThis.img = i, 1))");
  run(
    &f,
    r#"
        globalThis.x = inu.canvas.create(8,8).getContext('2d')
        x.fillStyle = x.createPattern(img, 'repeat')
        img.dispose()
        "#,
  );
  assert!(refusal(&f, "x.fillRect(0, 0, 1, 1)").starts_with("handle-expired:"));
}

#[test]
fn a_decode_the_host_refused_settles_as_a_rejection_and_leaves_nothing_charged() {
  let f = setup("decode-refused");
  *f.host.fail.borrow_mut() = Some((OP_DECODE, "Pinvalid-argument\n\n\n\nnot an image".to_string()));
  assert_eq!(settle(&f, "inu.canvas.decode(new Uint8Array([1,2,3]))"), "invalid-argument:not an image",);
  assert!(f.state.pending.borrow().is_empty());
}

#[test]
fn a_source_is_staged_to_a_file_and_the_file_goes_away_with_the_request() {
  let f = setup("stage");
  run(&f, "globalThis.p = inu.canvas.decode(new Uint8Array([1,2,3,4]))");
  let calls = f.host.log.borrow().calls.clone();
  let (_, _, arg) = calls.iter().rev().find(|(op, ..)| *op == OP_DECODE).unwrap();
  let path = arg.split(FIELD).nth(1).unwrap().to_string();
  assert!(std::fs::metadata(&path).is_ok(), "nothing was staged at {path}");
  answer(&f, r#"J{"width":1,"height":1}"#);
  assert!(std::fs::metadata(&path).is_err(), "the staged copy outlived the request");
}

#[test]
fn a_blob_source_is_staged_by_its_own_range() {
  let f = setup("stage-blob");
  run(
    &f,
    r#"
        const b = new Blob(['0123456789'])
        globalThis.p = inu.canvas.decode(b.slice(2, 5))
        "#,
  );
  let calls = f.host.log.borrow().calls.clone();
  let (_, _, arg) = calls.iter().rev().find(|(op, ..)| *op == OP_DECODE).unwrap();
  let path = arg.split(FIELD).nth(1).unwrap();
  assert_eq!(std::fs::read(path).unwrap(), b"234");
}

#[test]
fn a_disposed_blob_is_handle_expired_before_anything_is_staged() {
  let f = setup("stage-disposed");
  run(&f, "globalThis.b = new Blob(['x']); b.dispose()");
  assert!(refusal(&f, "inu.canvas.decode(b)").starts_with("handle-expired:"));
}

#[test]
fn naming_a_file_without_the_fs_grant_is_refused_by_name() {
  let f = setup("load-path");
  let message = refusal(&f, "inu.canvas.load({ path: 'a.png' })");
  assert!(message.starts_with("not-granted:"), "{message}");
  assert!(message.contains("fs"), "{message}");
}

#[test]
fn a_source_that_is_none_of_the_three_shapes_is_refused() {
  let f = setup("load-shape");
  assert!(refusal(&f, "inu.canvas.decode(42)").starts_with("invalid-argument:"));
  assert!(refusal(&f, "inu.canvas.loadFont('Fam', 42)").starts_with("invalid-argument:"));
  assert!(refusal(&f, "inu.canvas.loadFont(42, new Uint8Array([1]))").starts_with("invalid-argument:"));
  assert!(refusal(&f, "inu.canvas.loadFont('', new Uint8Array([1]))").starts_with("invalid-argument:"));
}

#[test]
fn loading_a_font_settles_with_nothing_and_names_the_family_to_the_host() {
  let f = setup("font-load");
  run(&f, "globalThis.p = inu.canvas.loadFont('My Face', new Uint8Array([1]))");
  let calls = f.host.log.borrow().calls.clone();
  let (_, _, arg) = calls.iter().rev().find(|(op, ..)| *op == OP_LOAD_FONT).unwrap();
  assert_eq!(arg.split(FIELD).nth(1).unwrap(), "My Face");
  answer(&f, "");
  assert_eq!(settle(&f, "p"), "ok:undefined");
}

#[test]
fn convert_to_blob_flushes_and_answers_a_blob_over_the_file_the_host_wrote() {
  let f = setup("encode");
  let dir = f._dir.path().join("out.png");
  std::fs::write(&dir, b"png bytes").unwrap();
  run(
    &f,
    r#"
        globalThis.c = inu.canvas.create(8, 8)
        c.getContext('2d').fillRect(0, 0, 8, 8)
        globalThis.p = c.convertToBlob()
        "#,
  );
  assert_eq!(f.host.log.borrow().commands.len(), 1, "the drawing was not flushed before the encode");
  answer(&f, &format!(r#"J{{"path":"{}","type":"image/png"}}"#, dir.to_string_lossy()));
  assert_eq!(settle(&f, "p.then(b => (globalThis.png = b, b.constructor.name))"), "ok:String");
  assert_eq!(eval(&f, "`${png.size}|${png.type}`"), "9|image/png");
}

#[test]
fn convert_to_blob_refuses_an_encoding_it_does_not_write() {
  let f = setup("encode-type");
  run(&f, "globalThis.c = inu.canvas.create(4, 4)");
  assert!(refusal(&f, "c.convertToBlob({ type: 'image/gif' })").starts_with("invalid-argument:"));
  let calls = f.host.log.borrow().calls.clone();
  assert!(!calls.iter().any(|(op, ..)| *op == OP_ENCODE), "the host was asked anyway");
}

#[test]
fn convert_to_blob_passes_the_type_and_quality_it_was_given() {
  let f = setup("encode-options");
  run(&f, "globalThis.c = inu.canvas.create(4, 4); c.convertToBlob({ type: 'image/jpeg', quality: 0.5 })");
  let calls = f.host.log.borrow().calls.clone();
  let (_, _, arg) = calls.iter().rev().find(|(op, ..)| *op == OP_ENCODE).unwrap();
  let fields: Vec<&str> = arg.split(FIELD).collect();
  assert_eq!(fields[1], "image/jpeg");
  assert_eq!(fields[2], "0.5");
}

#[test]
fn get_average_color_flushes_and_defaults_to_the_whole_canvas() {
  let f = setup("average");
  run(
    &f,
    r#"
        globalThis.x = inu.canvas.create(30, 20).getContext('2d')
        x.fillRect(0, 0, 5, 5)
        globalThis.avg = x.getAverageColor()
        "#,
  );
  assert_eq!(f.host.log.borrow().commands.len(), 1);
  assert_eq!(eval(&f, "`${avg.r},${avg.g},${avg.b},${avg.a}`"), "10,20,30,255");
  let calls = f.host.log.borrow().calls.clone();
  let (_, _, arg) = calls.iter().rev().find(|(op, ..)| *op == OP_AVERAGE).unwrap();
  assert_eq!(arg, "0,0,30,20");
}

#[test]
fn get_average_color_passes_the_region_it_was_given() {
  let f = setup("average-region");
  run(
    &f,
    r#"
        globalThis.x = inu.canvas.create(30, 20).getContext('2d')
        x.getAverageColor(1, 2, 3, 4)
        "#,
  );
  let calls = f.host.log.borrow().calls.clone();
  let (_, _, arg) = calls.iter().rev().find(|(op, ..)| *op == OP_AVERAGE).unwrap();
  assert_eq!(arg, "1,2,3,4");
  assert!(refusal(&f, "x.getAverageColor(NaN, 0, 1, 1)").starts_with("invalid-argument:"));
}

#[test]
fn the_buffer_replays_itself_before_it_can_grow_without_bound() {
  let f = setup("auto-flush");
  run(
    &f,
    r#"
        globalThis.x = inu.canvas.create(64, 64).getContext('2d')
        for (let i = 0; i < 40000; i++) x.fillRect(i, 0, 1, 1)
        "#,
  );
  assert!(!f.host.log.borrow().commands.is_empty(), "nothing was replayed");
  let total: usize = f.host.log.borrow().commands.iter().map(|c| c.len()).sum();
  assert!(total > FLUSH_AT_BYTES, "only {total} bytes crossed");
}

#[test]
fn a_replay_the_host_refused_raises_where_the_drawing_happened() {
  let f = setup("replay-error");
  run(&f, "globalThis.c = inu.canvas.create(8, 8); globalThis.x = c.getContext('2d')");
  *f.host.fail.borrow_mut() = Some((OP_REPLAY, "Pinternal\n\n\n\nno bitmap".to_string()));
  run(&f, "x.fillRect(0, 0, 1, 1)");
  assert_eq!(settle(&f, "c.convertToBlob()"), "internal:no bitmap");
}

#[test]
fn a_dropped_canvas_tells_the_host_to_let_its_bitmap_go() {
  let f = setup("destroy");
  run(&f, "globalThis.c = inu.canvas.create(8, 8); globalThis.c = null");
  f.ctx.with(|ctx| ctx.run_gc());
  let calls = f.host.log.borrow().calls.clone();
  assert!(calls.iter().any(|(op, ..)| *op == OP_DESTROY), "{calls:?}");
}

#[test]
fn disposal_releases_every_promise_the_engine_still_holds() {
  let f = setup("dispose");
  run(&f, "inu.canvas.decode(new Uint8Array([1])); inu.canvas.loadFont('a', new Uint8Array([1]))");
  assert_eq!(f.state.pending.borrow().len(), 2);
  f.state.dispose(&f.ctx);
  assert!(f.state.pending.borrow().is_empty());
}

/// Runs `canvas-test.js`, which is the only thing that can tell a member that behaves from one that
/// *vanished*: this suite is written out of `expectThrows`, and a missing member throws a
/// `TypeError` that reads as a refusal. It also asks for the real bundled file, so a contract change
/// that nobody carried into the shipped plugin fails here.
mod bundled_oracle {
  use super::*;

  const ORACLE: &str = include_str!("../../../../res/assets-debug/inu_plugins/canvas-test.js");

  /// the host the oracle runs against: it answers the three asynchronous ops, and refuses the one
  /// decode whose content says to - which is how the suite reaches its rejection case without a
  /// second host
  struct OracleCanvas {
    inner: Rc<OracleHost>,
  }

  impl CanvasHost for OracleCanvas {
    fn canvas(&self, op: i32, id: i64, arg: &str, bytes: Option<&[u8]>) -> String {
      self.inner.canvas(op, id, arg, bytes)
    }
  }

  #[test]
  fn the_bundled_canvas_test_plugin_passes() {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let dir = TestDir::new("oracle");
    let encoded = dir.path().join("out.png");
    std::fs::write(&encoded, b"encoded bytes").unwrap();
    let inner = OracleHost::new();
    let host = Rc::new(OracleCanvas { inner: inner.clone() });
    let lines = Rc::new(RefCell::new(Vec::<String>::new()));
    let external = ExternalMemory::new();
    let host_dyn: Rc<dyn CanvasHost> = host.clone();
    let state = ctx.with(|ctx| {
      let inu = crate::testing::harness::inu_namespace(&ctx);
      install_plugin_error(&ctx, &inu).unwrap();
      install_console(&ctx, lines.clone());
      let blobs = crate::api::io::blob::install(&ctx, dir.path(), external.clone()).unwrap();
      install_canvas(&ctx, host_dyn, blobs, external, dir.path().to_path_buf(), std::sync::Arc::new(|_: &str| {}), &inu)
        .unwrap()
    });
    let fixture = Fixture {
      _dispose: DisposeOnDrop { ctx: ctx.clone(), state: state.clone() },
      _rt: rt,
      ctx,
      host: inner,
      state,
      _dir: dir,
    };
    run(&fixture, ORACLE);
    drain(&fixture, &encoded);
    let lines = lines.borrow().clone();
    crate::testing::harness::assert_oracle_exact(&lines, "canvas test done", 64);
  }

  fn install_console(ctx: &Ctx<'_>, lines: Rc<RefCell<Vec<String>>>) {
    let console = rquickjs::Object::new(ctx.clone()).unwrap();
    for name in ["log", "error", "warn", "info", "debug"] {
      let lines = lines.clone();
      let f = rquickjs::Function::new(ctx.clone(), move |args: rquickjs::function::Rest<Coerced<String>>| {
        lines.borrow_mut().push(args.0.iter().map(|a| a.0.as_str()).collect::<Vec<_>>().join(" "));
      })
      .unwrap();
      console.set(name, f).unwrap();
    }
    ctx.globals().set("console", console).unwrap();
  }

  /// settles whatever the plugin is waiting on until it is waiting on nothing. Each answer can
  /// start the next op, so this is a loop rather than one pass.
  fn drain(f: &Fixture, encoded: &std::path::Path) {
    for _ in 0..64 {
      while f._rt.is_job_pending() {
        f._rt.execute_pending_job().ok();
      }
      let pending: Vec<(i32, String)> = {
        let calls = f.host.log.borrow().calls.clone();
        calls
          .into_iter()
          .filter(|(op, ..)| matches!(*op, OP_ENCODE | OP_DECODE | OP_LOAD_FONT))
          .map(|(op, _, arg)| (op, arg))
          .collect()
      };
      let outstanding: Vec<i64> = f.host.pending.borrow().clone();
      if outstanding.is_empty() {
        return;
      }
      f.host.pending.borrow_mut().clear();
      for request in outstanding {
        let (op, arg) = pending
          .iter()
          .find(|(_, arg)| arg.split(FIELD).next() == Some(&request.to_string()))
          .cloned()
          .expect("a pending request with no call behind it");
        let wire = match op {
          OP_ENCODE => {
            format!(r#"J{{"path":"{}","type":"image/png"}}"#, encoded.to_string_lossy())
          }
          OP_LOAD_FONT => String::new(),
          _ => {
            let path = arg.split(FIELD).nth(1).unwrap_or_default().to_string();
            // the suite asks for its rejection by handing over content that says so
            if std::fs::read(&path).unwrap_or_default() == b"\0" {
              "Pinvalid-argument\n\n\n\nnot an image".to_string()
            } else {
              r#"J{"width":16,"height":9}"#.to_string()
            }
          }
        };
        f.state.resolve(&f._rt, &f.ctx, request, &wire);
      }
    }
    panic!("the oracle never stopped waiting");
  }
}

pub const TAU: f64 = std::f64::consts::TAU;

const COLLINEAR_EPSILON: f64 = 1e-12;

const SINGULAR_EPSILON: f64 = 1e-12;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Matrix {
  pub a: f64,
  pub b: f64,
  pub c: f64,
  pub d: f64,
  pub e: f64,
  pub f: f64,
}

impl Default for Matrix {
  fn default() -> Self {
    Matrix::IDENTITY
  }
}

impl Matrix {
  pub const IDENTITY: Matrix = Matrix {
    a: 1.0,
    b: 0.0,
    c: 0.0,
    d: 1.0,
    e: 0.0,
    f: 0.0,
  };

  pub fn multiply(&self, other: &Matrix) -> Matrix {
    Matrix {
      a: self.a * other.a + self.c * other.b,
      b: self.b * other.a + self.d * other.b,
      c: self.a * other.c + self.c * other.d,
      d: self.b * other.c + self.d * other.d,
      e: self.a * other.e + self.c * other.f + self.e,
      f: self.b * other.e + self.d * other.f + self.f,
    }
  }

  pub fn apply(&self, x: f64, y: f64) -> (f64, f64) {
    (self.a * x + self.c * y + self.e, self.b * x + self.d * y + self.f)
  }

  pub fn apply_vector(&self, x: f64, y: f64) -> (f64, f64) {
    (self.a * x + self.c * y, self.b * x + self.d * y)
  }

  pub fn determinant(&self) -> f64 {
    self.a * self.d - self.b * self.c
  }

  pub fn invert(&self) -> Option<Matrix> {
    let det = self.determinant();
    if !det.is_finite() || det.abs() < SINGULAR_EPSILON {
      return None;
    }
    let inv = 1.0 / det;
    Some(Matrix {
      a: self.d * inv,
      b: -self.b * inv,
      c: -self.c * inv,
      d: self.a * inv,
      e: (self.c * self.f - self.d * self.e) * inv,
      f: (self.b * self.e - self.a * self.f) * inv,
    })
  }

  pub fn is_finite(&self) -> bool {
    [self.a, self.b, self.c, self.d, self.e, self.f].iter().all(|v| v.is_finite())
  }

  pub fn translated(&self, x: f64, y: f64) -> Matrix {
    self.multiply(&Matrix { e: x, f: y, ..Matrix::IDENTITY })
  }

  pub fn scaled(&self, x: f64, y: f64) -> Matrix {
    self.multiply(&Matrix { a: x, d: y, ..Matrix::IDENTITY })
  }

  pub fn rotated(&self, angle: f64) -> Matrix {
    let (sin, cos) = angle.sin_cos();
    self.multiply(&Matrix {
      a: cos,
      b: sin,
      c: -sin,
      d: cos,
      ..Matrix::IDENTITY
    })
  }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Verb {
  Move(f64, f64),
  Line(f64, f64),
  Cubic(f64, f64, f64, f64, f64, f64),
  Close,
}

#[derive(Clone, Debug, Default)]
pub struct Path {
  pub verbs: Vec<Verb>,
  current: Option<(f64, f64)>,
  subpath_start: Option<(f64, f64)>,
}

impl Path {
  pub fn is_empty(&self) -> bool {
    self.verbs.is_empty()
  }

  pub fn clear(&mut self) {
    self.verbs.clear();
    self.current = None;
    self.subpath_start = None;
  }

  pub fn current_in(&self, inverse: &Matrix) -> Option<(f64, f64)> {
    let (x, y) = self.current?;
    Some(inverse.apply(x, y))
  }

  fn push_move(&mut self, device: (f64, f64)) {
    self.verbs.push(Verb::Move(device.0, device.1));
    self.current = Some(device);
    self.subpath_start = Some(device);
  }

  fn push_line(&mut self, device: (f64, f64)) {
    match self.current {
      Some(_) => self.verbs.push(Verb::Line(device.0, device.1)),
      None => return self.push_move(device),
    }
    self.current = Some(device);
  }

  pub fn move_to(&mut self, m: &Matrix, x: f64, y: f64) {
    if !finite(&[x, y]) {
      return;
    }
    self.push_move(m.apply(x, y));
  }

  pub fn line_to(&mut self, m: &Matrix, x: f64, y: f64) {
    if !finite(&[x, y]) {
      return;
    }
    self.push_line(m.apply(x, y));
  }

  #[allow(clippy::too_many_arguments)]
  pub fn cubic_to(&mut self, m: &Matrix, c1x: f64, c1y: f64, c2x: f64, c2y: f64, x: f64, y: f64) {
    if !finite(&[c1x, c1y, c2x, c2y, x, y]) {
      return;
    }
    if self.current.is_none() {
      self.push_move(m.apply(c1x, c1y));
    }
    let c1 = m.apply(c1x, c1y);
    let c2 = m.apply(c2x, c2y);
    let to = m.apply(x, y);
    self.verbs.push(Verb::Cubic(c1.0, c1.1, c2.0, c2.1, to.0, to.1));
    self.current = Some(to);
  }

  pub fn quad_to(&mut self, m: &Matrix, cx: f64, cy: f64, x: f64, y: f64, inverse: Option<&Matrix>) {
    if !finite(&[cx, cy, x, y]) {
      return;
    }
    let from = match (self.current, inverse) {
      (Some(_), Some(inverse)) => match self.current_in(inverse) {
        Some(p) => p,
        None => (cx, cy),
      },
      _ => {
        self.push_move(m.apply(cx, cy));
        (cx, cy)
      }
    };
    let c1 = (from.0 + 2.0 / 3.0 * (cx - from.0), from.1 + 2.0 / 3.0 * (cy - from.1));
    let c2 = (x + 2.0 / 3.0 * (cx - x), y + 2.0 / 3.0 * (cy - y));
    self.cubic_to(m, c1.0, c1.1, c2.0, c2.1, x, y);
  }

  pub fn close(&mut self) {
    let Some(start) = self.subpath_start else {
      return;
    };
    self.verbs.push(Verb::Close);
    self.current = Some(start);
  }

  pub fn rect(&mut self, m: &Matrix, x: f64, y: f64, w: f64, h: f64) {
    if !finite(&[x, y, w, h]) {
      return;
    }
    self.push_move(m.apply(x, y));
    self.verbs.push(line_verb(m.apply(x + w, y)));
    self.verbs.push(line_verb(m.apply(x + w, y + h)));
    self.verbs.push(line_verb(m.apply(x, y + h)));
    self.verbs.push(Verb::Close);
    self.push_move(m.apply(x, y));
  }

  pub fn round_rect(&mut self, m: &Matrix, x: f64, y: f64, w: f64, h: f64, radii: [(f64, f64); 4]) {
    if !finite(&[x, y, w, h]) {
      return;
    }
    let (tl, tr, br, bl) = (radii[0], radii[1], radii[2], radii[3]);
    let (right, bottom) = (x + w, y + h);
    self.push_move(m.apply(x + tl.0, y));
    self.push_line(m.apply(right - tr.0, y));
    self.corner(m, (right - tr.0, y + tr.1), tr, -TAU / 4.0);
    self.push_line(m.apply(right, bottom - br.1));
    self.corner(m, (right - br.0, bottom - br.1), br, 0.0);
    self.push_line(m.apply(x + bl.0, bottom));
    self.corner(m, (x + bl.0, bottom - bl.1), bl, TAU / 4.0);
    self.push_line(m.apply(x, y + tl.1));
    self.corner(m, (x + tl.0, y + tl.1), tl, TAU / 2.0);
    self.verbs.push(Verb::Close);
    self.push_move(m.apply(x, y));
  }

  fn corner(&mut self, m: &Matrix, centre: (f64, f64), radii: (f64, f64), start: f64) {
    if radii.0 <= 0.0 || radii.1 <= 0.0 {
      return;
    }
    self.append_arc(m, centre, radii, 0.0, start, TAU / 4.0);
  }

  #[allow(clippy::too_many_arguments)]
  pub fn arc(
    &mut self,
    m: &Matrix,
    x: f64,
    y: f64,
    radius: f64,
    start: f64,
    end: f64,
    counterclockwise: bool,
  ) -> Result<(), ArcError> {
    self.ellipse(m, x, y, radius, radius, 0.0, start, end, counterclockwise)
  }

  #[allow(clippy::too_many_arguments)]
  pub fn ellipse(
    &mut self,
    m: &Matrix,
    x: f64,
    y: f64,
    radius_x: f64,
    radius_y: f64,
    rotation: f64,
    start: f64,
    end: f64,
    counterclockwise: bool,
  ) -> Result<(), ArcError> {
    if radius_x < 0.0 || radius_y < 0.0 {
      return Err(ArcError::NegativeRadius);
    }
    if !finite(&[x, y, radius_x, radius_y, rotation, start, end]) {
      return Ok(());
    }
    let sweep = sweep_of(start, end, counterclockwise);
    let first = ellipse_point((x, y), (radius_x, radius_y), rotation, start);
    self.push_line(m.apply(first.0, first.1));
    if sweep != 0.0 {
      self.append_arc(m, (x, y), (radius_x, radius_y), rotation, start, sweep);
    }
    Ok(())
  }

  #[allow(clippy::too_many_arguments)]
  pub fn arc_to(
    &mut self,
    m: &Matrix,
    inverse: &Matrix,
    x1: f64,
    y1: f64,
    x2: f64,
    y2: f64,
    radius: f64,
  ) -> Result<(), ArcError> {
    if radius < 0.0 {
      return Err(ArcError::NegativeRadius);
    }
    if !finite(&[x1, y1, x2, y2, radius]) {
      return Ok(());
    }
    let Some(p0) = self.current_in(inverse) else {
      self.push_move(m.apply(x1, y1));
      return Ok(());
    };
    let d0 = normalize((p0.0 - x1, p0.1 - y1));
    let d2 = normalize((x2 - x1, y2 - y1));
    let (Some(d0), Some(d2)) = (d0, d2) else {
      self.push_line(m.apply(x1, y1));
      return Ok(());
    };
    let cross = d0.0 * d2.1 - d0.1 * d2.0;
    if radius == 0.0 || cross.abs() < COLLINEAR_EPSILON {
      self.push_line(m.apply(x1, y1));
      return Ok(());
    }
    let dot = (d0.0 * d2.0 + d0.1 * d2.1).clamp(-1.0, 1.0);
    let half = dot.acos() / 2.0;
    let leg = radius / half.tan();
    let bisector = match normalize((d0.0 + d2.0, d0.1 + d2.1)) {
      Some(v) => v,
      None => {
        self.push_line(m.apply(x1, y1));
        return Ok(());
      }
    };
    let centre = (x1 + bisector.0 * (radius / half.sin()), y1 + bisector.1 * (radius / half.sin()));
    let touch_in = (x1 + d0.0 * leg, y1 + d0.1 * leg);
    let touch_out = (x1 + d2.0 * leg, y1 + d2.1 * leg);
    self.push_line(m.apply(touch_in.0, touch_in.1));

    let start = (touch_in.1 - centre.1).atan2(touch_in.0 - centre.0);
    let sweep = (std::f64::consts::PI - 2.0 * half) * if cross < 0.0 { 1.0 } else { -1.0 };
    self.append_arc(m, centre, (radius, radius), 0.0, start, sweep);
    let end = m.apply(touch_out.0, touch_out.1);
    self.current = Some(end);
    Ok(())
  }

  fn append_arc(&mut self, m: &Matrix, centre: (f64, f64), radii: (f64, f64), rotation: f64, start: f64, sweep: f64) {
    let segments = (sweep.abs() / (TAU / 4.0)).ceil().max(1.0) as usize;
    let delta = sweep / segments as f64;
    let alpha = 4.0 / 3.0 * (delta / 4.0).tan();
    let mut angle = start;
    for _ in 0..segments {
      let next = angle + delta;
      let p0 = ellipse_point(centre, radii, rotation, angle);
      let p1 = ellipse_point(centre, radii, rotation, next);
      let d0 = ellipse_tangent(radii, rotation, angle);
      let d1 = ellipse_tangent(radii, rotation, next);
      if self.current.is_none() {
        self.push_move(m.apply(p0.0, p0.1));
      }
      let c1 = m.apply(p0.0 + alpha * d0.0, p0.1 + alpha * d0.1);
      let c2 = m.apply(p1.0 - alpha * d1.0, p1.1 - alpha * d1.1);
      let to = m.apply(p1.0, p1.1);
      self.verbs.push(Verb::Cubic(c1.0, c1.1, c2.0, c2.1, to.0, to.1));
      self.current = Some(to);
      angle = next;
    }
  }
}

#[derive(Debug, PartialEq)]
pub enum ArcError {
  NegativeRadius,
}

fn line_verb(p: (f64, f64)) -> Verb {
  Verb::Line(p.0, p.1)
}

pub fn finite(values: &[f64]) -> bool {
  values.iter().all(|v| v.is_finite())
}

fn normalize(v: (f64, f64)) -> Option<(f64, f64)> {
  let len = (v.0 * v.0 + v.1 * v.1).sqrt();
  if !len.is_finite() || len < COLLINEAR_EPSILON {
    return None;
  }
  Some((v.0 / len, v.1 / len))
}

fn ellipse_point(centre: (f64, f64), radii: (f64, f64), rotation: f64, angle: f64) -> (f64, f64) {
  let (sin_r, cos_r) = rotation.sin_cos();
  let (sin_a, cos_a) = angle.sin_cos();
  let x = radii.0 * cos_a;
  let y = radii.1 * sin_a;
  (centre.0 + x * cos_r - y * sin_r, centre.1 + x * sin_r + y * cos_r)
}

fn ellipse_tangent(radii: (f64, f64), rotation: f64, angle: f64) -> (f64, f64) {
  let (sin_r, cos_r) = rotation.sin_cos();
  let (sin_a, cos_a) = angle.sin_cos();
  let dx = -radii.0 * sin_a;
  let dy = radii.1 * cos_a;
  (dx * cos_r - dy * sin_r, dx * sin_r + dy * cos_r)
}

fn sweep_of(start: f64, end: f64, counterclockwise: bool) -> f64 {
  let raw = end - start;
  if counterclockwise {
    if raw <= -TAU {
      return -TAU;
    }
    return -(-raw).rem_euclid(TAU);
  }
  if raw >= TAU {
    return TAU;
  }
  raw.rem_euclid(TAU)
}

pub fn normalize_round_rect(
  x: f64,
  y: f64,
  w: f64,
  h: f64,
  radii: [(f64, f64); 4],
) -> (f64, f64, f64, f64, [(f64, f64); 4]) {
  let mut radii = radii;
  let (x, w) = if w < 0.0 {
    radii.swap(0, 1);
    radii.swap(3, 2);
    (x + w, -w)
  } else {
    (x, w)
  };
  let (y, h) = if h < 0.0 {
    radii.swap(0, 3);
    radii.swap(1, 2);
    (y + h, -h)
  } else {
    (y, h)
  };
  let scale = [
    w / (radii[0].0 + radii[1].0),
    h / (radii[1].1 + radii[2].1),
    w / (radii[3].0 + radii[2].0),
    h / (radii[0].1 + radii[3].1),
  ]
  .into_iter()
  .filter(|v| v.is_finite())
  .fold(1.0f64, f64::min);
  if scale < 1.0 {
    for corner in radii.iter_mut() {
      corner.0 *= scale;
      corner.1 *= scale;
    }
  }
  (x, y, w, h, radii)
}

#[cfg(test)]
#[path = "geometry_tests.rs"]
mod tests;

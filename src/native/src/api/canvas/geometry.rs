use std::f64::consts::TAU;

use kurbo::{Affine, Arc, BezPath, ParamCurve, Point, Vec2};

const COLLINEAR_EPSILON: f64 = 1e-12;
const SINGULAR_EPSILON: f64 = 1e-12;
const ARC_TOLERANCE_PX: f64 = 0.1;

pub fn invert(m: Affine) -> Option<Affine> {
  let det = m.determinant();
  if !det.is_finite() || det.abs() < SINGULAR_EPSILON {
    return None;
  }
  Some(m.inverse())
}

#[derive(Clone, Debug, Default)]
pub struct Path(pub BezPath);

impl Path {
  fn push_line(&mut self, device: Point) {
    if self.0.elements().is_empty() {
      self.0.move_to(device);
    } else {
      self.0.line_to(device);
    }
  }

  pub fn move_to(&mut self, m: Affine, x: f64, y: f64) {
    if finite(&[x, y]) {
      self.0.move_to(m * Point::new(x, y));
    }
  }

  pub fn line_to(&mut self, m: Affine, x: f64, y: f64) {
    if finite(&[x, y]) {
      self.push_line(m * Point::new(x, y));
    }
  }

  #[allow(clippy::too_many_arguments)]
  pub fn cubic_to(&mut self, m: Affine, c1x: f64, c1y: f64, c2x: f64, c2y: f64, x: f64, y: f64) {
    if !finite(&[c1x, c1y, c2x, c2y, x, y]) {
      return;
    }
    let c1 = m * Point::new(c1x, c1y);
    if self.0.elements().is_empty() {
      self.0.move_to(c1);
    }
    self.0.curve_to(c1, m * Point::new(c2x, c2y), m * Point::new(x, y));
  }

  pub fn quad_to(&mut self, m: Affine, cx: f64, cy: f64, x: f64, y: f64) {
    if !finite(&[cx, cy, x, y]) {
      return;
    }
    let c = m * Point::new(cx, cy);
    if self.0.elements().is_empty() {
      self.0.move_to(c);
    }
    self.0.quad_to(c, m * Point::new(x, y));
  }

  pub fn close(&mut self) {
    if !self.0.elements().is_empty() {
      self.0.close_path();
    }
  }

  pub fn rect(&mut self, m: Affine, x: f64, y: f64, w: f64, h: f64) {
    if !finite(&[x, y, w, h]) {
      return;
    }
    self.0.move_to(m * Point::new(x, y));
    self.0.line_to(m * Point::new(x + w, y));
    self.0.line_to(m * Point::new(x + w, y + h));
    self.0.line_to(m * Point::new(x, y + h));
    self.0.close_path();
    self.0.move_to(m * Point::new(x, y));
  }

  pub fn round_rect(&mut self, m: Affine, x: f64, y: f64, w: f64, h: f64, radii: [(f64, f64); 4]) {
    if !finite(&[x, y, w, h]) {
      return;
    }
    let (tl, tr, br, bl) = (radii[0], radii[1], radii[2], radii[3]);
    let (right, bottom) = (x + w, y + h);
    self.0.move_to(m * Point::new(x + tl.0, y));
    self.0.line_to(m * Point::new(right - tr.0, y));
    self.corner(m, (right - tr.0, y + tr.1), tr, -TAU / 4.0);
    self.0.line_to(m * Point::new(right, bottom - br.1));
    self.corner(m, (right - br.0, bottom - br.1), br, 0.0);
    self.0.line_to(m * Point::new(x + bl.0, bottom));
    self.corner(m, (x + bl.0, bottom - bl.1), bl, TAU / 4.0);
    self.0.line_to(m * Point::new(x, y + tl.1));
    self.corner(m, (x + tl.0, y + tl.1), tl, TAU / 2.0);
    self.0.close_path();
    self.0.move_to(m * Point::new(x, y));
  }

  fn corner(&mut self, m: Affine, centre: (f64, f64), radii: (f64, f64), start: f64) {
    if radii.0 > 0.0 && radii.1 > 0.0 {
      self.append_arc(m, Arc::new(centre, radii, start, TAU / 4.0, 0.0));
    }
  }

  #[allow(clippy::too_many_arguments)]
  pub fn arc(
    &mut self,
    m: Affine,
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
    m: Affine,
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
    let arc = Arc::new((x, y), (radius_x, radius_y), start, compute_arc_sweep(start, end, counterclockwise), rotation);
    self.push_line(m * arc.start());
    self.append_arc(m, arc);
    Ok(())
  }

  #[allow(clippy::too_many_arguments)]
  pub fn arc_to(
    &mut self,
    m: Affine,
    inverse: Affine,
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
    let corner = Point::new(x1, y1);
    let Some(p0) = self.0.current_position().map(|p| inverse * p) else {
      self.0.move_to(m * corner);
      return Ok(());
    };
    let (Some(d0), Some(d2)) = (normalize(p0 - corner), normalize(Point::new(x2, y2) - corner)) else {
      self.push_line(m * corner);
      return Ok(());
    };
    let cross = d0.cross(d2);
    if radius == 0.0 || cross.abs() < COLLINEAR_EPSILON {
      self.push_line(m * corner);
      return Ok(());
    }
    let half = d0.dot(d2).clamp(-1.0, 1.0).acos() / 2.0;
    let Some(bisector) = normalize(d0 + d2) else {
      self.push_line(m * corner);
      return Ok(());
    };
    let centre = corner + bisector * (radius / half.sin());
    let touch_in = corner + d0 * (radius / half.tan());
    self.push_line(m * touch_in);
    let sweep = (std::f64::consts::PI - 2.0 * half) * if cross < 0.0 { 1.0 } else { -1.0 };
    self.append_arc(m, Arc::new(centre, (radius, radius), (touch_in - centre).atan2(), sweep, 0.0));
    Ok(())
  }

  fn append_arc(&mut self, m: Affine, arc: Arc) {
    for el in arc.append_iter(ARC_TOLERANCE_PX / m.spectral_norm()) {
      self.0.push(m * el);
    }
  }
}

#[derive(Debug, PartialEq)]
pub enum ArcError {
  NegativeRadius,
}

pub fn finite(values: &[f64]) -> bool {
  values.iter().all(|v| v.is_finite())
}

fn normalize(v: Vec2) -> Option<Vec2> {
  let len = v.hypot();
  if !len.is_finite() || len < COLLINEAR_EPSILON {
    return None;
  }
  Some(v / len)
}

fn compute_arc_sweep(start: f64, end: f64, counterclockwise: bool) -> f64 {
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

use super::*;
use kurbo::{PathEl, Shape};

fn close_to(a: f64, b: f64) -> bool {
  (a - b).abs() < 1e-9
}

fn assert_point(got: Point, want: (f64, f64)) {
  assert!((got.x - want.0).abs() < 1e-6 && (got.y - want.1).abs() < 1e-6, "{got:?} is not {want:?}",);
}

fn last_point(path: &Path) -> Point {
  path.0.elements().last().and_then(|el| el.end_point()).expect("the path ends with a close")
}

#[test]
fn a_line_with_no_subpath_starts_one() {
  let mut path = Path::default();
  path.line_to(Affine::IDENTITY, 4.0, 5.0);
  assert_eq!(path.0.elements(), [PathEl::MoveTo((4.0, 5.0).into())]);
}

#[test]
fn a_non_finite_coordinate_leaves_the_path_alone() {
  let mut path = Path::default();
  path.move_to(Affine::IDENTITY, 1.0, 1.0);
  path.line_to(Affine::IDENTITY, f64::NAN, 3.0);
  path.line_to(Affine::IDENTITY, 2.0, f64::INFINITY);
  assert_eq!(path.0.elements(), [PathEl::MoveTo((1.0, 1.0).into())]);
}

#[test]
fn closing_puts_the_pen_back_at_the_subpath_start() {
  let mut path = Path::default();
  path.move_to(Affine::IDENTITY, 3.0, 4.0);
  path.line_to(Affine::IDENTITY, 10.0, 10.0);
  path.close();
  assert_eq!(path.0.current_position(), Some(Point::new(3.0, 4.0)));
}

#[test]
fn closing_with_no_subpath_does_nothing() {
  let mut path = Path::default();
  path.close();
  assert!(path.0.elements().is_empty());
}

#[test]
fn rect_closes_and_then_opens_a_fresh_subpath() {
  let mut path = Path::default();
  path.rect(Affine::IDENTITY, 1.0, 2.0, 10.0, 20.0);
  assert_eq!(
    path.0.elements(),
    [
      PathEl::MoveTo((1.0, 2.0).into()),
      PathEl::LineTo((11.0, 2.0).into()),
      PathEl::LineTo((11.0, 22.0).into()),
      PathEl::LineTo((1.0, 22.0).into()),
      PathEl::ClosePath,
      PathEl::MoveTo((1.0, 2.0).into()),
    ],
  );
}

#[test]
fn a_full_circle_comes_back_to_where_it_started() {
  let mut path = Path::default();
  path.arc(Affine::IDENTITY, 0.0, 0.0, 10.0, 0.0, TAU, false).unwrap();
  assert_point(last_point(&path), (10.0, 0.0));
}

#[test]
fn the_cubics_stay_on_the_circle_within_a_device_pixel_tolerance() {
  for scale in [1.0, 10.0] {
    let mut path = Path::default();
    path.arc(Affine::scale(scale), 0.0, 0.0, 100.0, 0.0, TAU, false).unwrap();
    let radius = 100.0 * scale;
    let worst = path
      .0
      .segments()
      .flat_map(|seg| (1..20).map(move |step| seg.eval(step as f64 / 20.0)))
      .map(|p| (p.to_vec2().hypot() - radius).abs())
      .fold(0.0, f64::max);
    assert!(worst < ARC_TOLERANCE_PX, "the arc at scale {scale} is off the circle by {worst}px");
  }
}

#[test]
fn counterclockwise_goes_the_other_way_round() {
  let mut cw = Path::default();
  cw.arc(Affine::IDENTITY, 0.0, 0.0, 10.0, 0.0, TAU / 4.0, false).unwrap();
  let mut ccw = Path::default();
  ccw.arc(Affine::IDENTITY, 0.0, 0.0, 10.0, 0.0, TAU / 4.0, true).unwrap();
  assert_eq!(cw.0.elements()[0], PathEl::MoveTo((10.0, 0.0).into()));
  assert_point(last_point(&cw), (0.0, 10.0));
  assert_point(last_point(&ccw), (0.0, 10.0));
  assert!(cw.0.bounding_box().x0 > -1e-6, "the clockwise quarter strays left: {:?}", cw.0.bounding_box());
  assert!(ccw.0.bounding_box().x0 < -9.9, "the counterclockwise route skips the left side");
}

#[test]
fn angles_already_spanning_a_full_turn_give_exactly_one() {
  assert!(close_to(compute_arc_sweep(0.0, 10.0, false), TAU));
  assert!(close_to(compute_arc_sweep(0.0, -10.0, true), -TAU));
  assert!(close_to(compute_arc_sweep(0.0, TAU / 2.0, false), TAU / 2.0));
  assert!(close_to(compute_arc_sweep(0.0, TAU / 2.0, true), -TAU / 2.0));
}

#[test]
fn an_arc_after_a_subpath_is_joined_to_it_by_a_line() {
  let mut path = Path::default();
  path.move_to(Affine::IDENTITY, -50.0, -50.0);
  path.arc(Affine::IDENTITY, 0.0, 0.0, 10.0, 0.0, TAU / 4.0, false).unwrap();
  assert_eq!(path.0.elements()[0], PathEl::MoveTo((-50.0, -50.0).into()));
  assert_eq!(path.0.elements()[1], PathEl::LineTo((10.0, 0.0).into()));
}

#[test]
fn a_rotated_ellipse_is_not_an_axis_aligned_oval() {
  let mut path = Path::default();
  path.ellipse(Affine::IDENTITY, 0.0, 0.0, 20.0, 5.0, TAU / 8.0, 0.0, TAU, false).unwrap();
  // the point at angle 0 is (20, 0) rotated an eighth turn
  let r = (TAU / 8.0).cos() * 20.0;
  let PathEl::MoveTo(first) = path.0.elements()[0] else { panic!("{:?}", path.0) };
  assert_point(first, (r, r));
}

#[test]
fn arc_to_rounds_a_right_angle_at_the_tangent_points() {
  let mut path = Path::default();
  path.move_to(Affine::IDENTITY, 0.0, 0.0);
  path.arc_to(Affine::IDENTITY, Affine::IDENTITY, 100.0, 0.0, 100.0, 100.0, 50.0).unwrap();
  // the leg is r/tan(45) = r, so the arc starts at (50, 0) and ends at (100, 50)
  let PathEl::LineTo(join) = path.0.elements()[1] else { panic!("no join line: {:?}", path.0) };
  assert_point(join, (50.0, 0.0));
  assert_point(last_point(&path), (100.0, 50.0));
}

#[test]
fn arc_to_with_no_subpath_just_moves_to_the_corner() {
  let mut path = Path::default();
  path.arc_to(Affine::IDENTITY, Affine::IDENTITY, 7.0, 8.0, 9.0, 10.0, 4.0).unwrap();
  assert_eq!(path.0.elements(), [PathEl::MoveTo((7.0, 8.0).into())]);
}

#[test]
fn collinear_points_and_a_zero_radius_both_degenerate_to_a_line() {
  for (x2, y2, radius) in [(200.0, 0.0, 50.0), (100.0, 100.0, 0.0)] {
    let mut path = Path::default();
    path.move_to(Affine::IDENTITY, 0.0, 0.0);
    path.arc_to(Affine::IDENTITY, Affine::IDENTITY, 100.0, 0.0, x2, y2, radius).unwrap();
    assert_eq!(path.0.elements(), [PathEl::MoveTo((0.0, 0.0).into()), PathEl::LineTo((100.0, 0.0).into())]);
  }
}

#[test]
fn arc_to_turns_the_way_the_corner_does() {
  // mirrored corner: the same geometry reflected in y, so the arc has to sweep the other way
  let mut path = Path::default();
  path.move_to(Affine::IDENTITY, 0.0, 0.0);
  path.arc_to(Affine::IDENTITY, Affine::IDENTITY, 100.0, 0.0, 100.0, -100.0, 50.0).unwrap();
  assert_point(last_point(&path), (100.0, -50.0));
  // sweeping the other way would be three quarters of the circle and would reach x = 150
  let widest = path.0.bounding_box().x1;
  assert!(widest <= 100.0 + 1e-6, "the arc bulges the wrong way, out to x = {widest}");
}

#[test]
fn arc_to_reads_the_pen_in_the_current_user_space() {
  // the pen was placed under one transform and the corner is named under another; without
  // the inverse read-back the tangents would be computed against a device-space point
  let mut path = Path::default();
  path.move_to(Affine::scale(2.0), 0.0, 0.0);
  path.arc_to(Affine::IDENTITY, Affine::IDENTITY, 100.0, 0.0, 100.0, 100.0, 50.0).unwrap();
  let PathEl::LineTo(join) = path.0.elements()[1] else { panic!("no join line: {:?}", path.0) };
  assert_point(join, (50.0, 0.0));
}

#[test]
fn round_rect_corners_keep_their_proportions_under_that_scale() {
  let (_, _, _, _, radii) =
    normalize_round_rect(0.0, 0.0, 100.0, 100.0, [(30.0, 30.0), (90.0, 90.0), (0.0, 0.0), (0.0, 0.0)]);
  // 30 + 90 over a 100 wide box scales everything by 100/120
  assert!(close_to(radii[0].0, 25.0) && close_to(radii[1].0, 75.0), "{radii:?}");
}

#[test]
fn a_negative_width_flips_the_box_and_the_corners_with_it() {
  let (x, _, w, _, radii) =
    normalize_round_rect(100.0, 0.0, -100.0, 50.0, [(10.0, 10.0), (0.0, 0.0), (0.0, 0.0), (0.0, 0.0)]);
  assert!(close_to(x, 0.0) && close_to(w, 100.0));
  assert!(close_to(radii[1].0, 10.0), "the top-left corner became the top-right: {radii:?}");
}

#[test]
fn a_square_round_rect_is_a_closed_outline_that_leaves_a_fresh_subpath() {
  let mut path = Path::default();
  let (x, y, w, h, radii) = normalize_round_rect(0.0, 0.0, 100.0, 100.0, [(10.0, 10.0); 4]);
  path.round_rect(Affine::IDENTITY, x, y, w, h, radii);
  let elements = path.0.elements();
  assert_eq!(elements[0], PathEl::MoveTo((10.0, 0.0).into()));
  assert_eq!(elements[elements.len() - 2], PathEl::ClosePath);
  assert_eq!(elements[elements.len() - 1], PathEl::MoveTo((0.0, 0.0).into()));
}

#[test]
fn a_zero_radius_corner_is_a_plain_angle() {
  let mut path = Path::default();
  path.round_rect(Affine::IDENTITY, 0.0, 0.0, 10.0, 10.0, [(0.0, 0.0); 4]);
  assert!(!path.0.elements().iter().any(|el| matches!(el, PathEl::CurveTo(..))));
}

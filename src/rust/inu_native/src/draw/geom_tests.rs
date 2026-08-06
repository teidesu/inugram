use super::*;

fn close_to(a: f64, b: f64) -> bool {
    (a - b).abs() < 1e-9
}

fn assert_point(got: (f64, f64), want: (f64, f64)) {
    assert!((got.0 - want.0).abs() < 1e-6 && (got.1 - want.1).abs() < 1e-6, "{got:?} is not {want:?}",);
}

fn last_point(path: &Path) -> (f64, f64) {
    match path.verbs.last().unwrap() {
        Verb::Move(x, y) | Verb::Line(x, y) => (*x, *y),
        Verb::Cubic(_, _, _, _, x, y) => (*x, *y),
        Verb::Close => panic!("the path ends with a close"),
    }
}

#[test]
fn transform_post_multiplies_so_the_newest_applies_first() {
    // ctx.translate(10, 0); ctx.scale(2, 2) - a point at 1 lands at 12, not 22
    let m = Matrix::IDENTITY.translated(10.0, 0.0).scaled(2.0, 2.0);
    assert_point(m.apply(1.0, 0.0), (12.0, 0.0));
}

#[test]
fn inverting_round_trips_every_point() {
    let m = Matrix::IDENTITY.translated(30.0, -7.0).rotated(0.7).scaled(3.0, 0.5);
    let inverse = m.invert().unwrap();
    let (x, y) = m.apply(11.0, -4.0);
    assert_point(inverse.apply(x, y), (11.0, -4.0));
}

#[test]
fn a_flattened_transform_has_no_inverse() {
    assert!(Matrix { a: 1.0, b: 0.0, c: 2.0, d: 0.0, e: 0.0, f: 0.0 }.invert().is_none());
    assert!(Matrix { a: 0.0, ..Matrix::IDENTITY }.invert().is_none());
}

#[test]
fn a_vector_is_rotated_and_scaled_but_never_translated() {
    let m = Matrix::IDENTITY.translated(100.0, 100.0).scaled(2.0, 2.0);
    assert_point(m.apply_vector(1.0, 0.0), (2.0, 0.0));
    assert_point(m.apply(1.0, 0.0), (102.0, 100.0));
}

#[test]
fn a_point_is_baked_with_the_transform_that_was_current_when_it_was_added() {
    let mut path = Path::default();
    path.move_to(&Matrix::IDENTITY, 0.0, 0.0);
    let moved = Matrix::IDENTITY.translated(50.0, 0.0);
    path.line_to(&moved, 0.0, 0.0);
    assert_eq!(path.verbs, vec![Verb::Move(0.0, 0.0), Verb::Line(50.0, 0.0)]);
}

#[test]
fn a_line_with_no_subpath_starts_one() {
    let mut path = Path::default();
    path.line_to(&Matrix::IDENTITY, 4.0, 5.0);
    assert_eq!(path.verbs, vec![Verb::Move(4.0, 5.0)]);
}

#[test]
fn a_non_finite_coordinate_leaves_the_path_alone() {
    let mut path = Path::default();
    path.move_to(&Matrix::IDENTITY, 1.0, 1.0);
    path.line_to(&Matrix::IDENTITY, f64::NAN, 3.0);
    path.line_to(&Matrix::IDENTITY, 2.0, f64::INFINITY);
    assert_eq!(path.verbs, vec![Verb::Move(1.0, 1.0)]);
}

#[test]
fn closing_puts_the_pen_back_at_the_subpath_start() {
    let mut path = Path::default();
    path.move_to(&Matrix::IDENTITY, 3.0, 4.0);
    path.line_to(&Matrix::IDENTITY, 10.0, 10.0);
    path.close();
    assert_eq!(path.current_in(&Matrix::IDENTITY), Some((3.0, 4.0)));
}

#[test]
fn closing_with_no_subpath_does_nothing() {
    let mut path = Path::default();
    path.close();
    assert!(path.is_empty());
}

#[test]
fn rect_closes_and_then_opens_a_fresh_subpath() {
    let mut path = Path::default();
    path.rect(&Matrix::IDENTITY, 1.0, 2.0, 10.0, 20.0);
    assert_eq!(
        path.verbs,
        vec![
            Verb::Move(1.0, 2.0),
            Verb::Line(11.0, 2.0),
            Verb::Line(11.0, 22.0),
            Verb::Line(1.0, 22.0),
            Verb::Close,
            Verb::Move(1.0, 2.0),
        ],
    );
}

#[test]
fn the_current_point_reads_back_through_the_inverse() {
    let m = Matrix::IDENTITY.translated(100.0, 40.0).scaled(2.0, 2.0);
    let mut path = Path::default();
    path.move_to(&m, 5.0, 5.0);
    assert_point(path.current_in(&m.invert().unwrap()).unwrap(), (5.0, 5.0));
}

#[test]
fn a_quarter_arc_lands_on_its_endpoints() {
    let mut path = Path::default();
    path.arc(&Matrix::IDENTITY, 0.0, 0.0, 10.0, 0.0, TAU / 4.0, false).unwrap();
    assert_eq!(path.verbs[0], Verb::Move(10.0, 0.0));
    assert_point(last_point(&path), (0.0, 10.0));
}

#[test]
fn a_full_circle_is_four_cubics_and_comes_back_to_where_it_started() {
    let mut path = Path::default();
    path.arc(&Matrix::IDENTITY, 0.0, 0.0, 10.0, 0.0, TAU, false).unwrap();
    let cubics = path.verbs.iter().filter(|v| matches!(v, Verb::Cubic(..))).count();
    assert_eq!(cubics, 4);
    assert_point(last_point(&path), (10.0, 0.0));
}

/// the standard bound is ~2.7e-4 of the radius per quarter turn; anything much worse than this
/// means the control-point placement is wrong rather than merely approximate
#[test]
fn the_cubics_stay_on_the_circle() {
    let mut path = Path::default();
    path.arc(&Matrix::IDENTITY, 0.0, 0.0, 100.0, 0.0, TAU, false).unwrap();
    let mut worst: f64 = 0.0;
    let mut from = (100.0, 0.0);
    for verb in &path.verbs {
        let Verb::Cubic(c1x, c1y, c2x, c2y, x, y) = *verb else {
            continue;
        };
        for step in 1..20 {
            let t = step as f64 / 20.0;
            let u = 1.0 - t;
            let px = u * u * u * from.0 + 3.0 * u * u * t * c1x + 3.0 * u * t * t * c2x + t * t * t * x;
            let py = u * u * u * from.1 + 3.0 * u * u * t * c1y + 3.0 * u * t * t * c2y + t * t * t * y;
            worst = worst.max(((px * px + py * py).sqrt() - 100.0).abs());
        }
        from = (x, y);
    }
    assert!(worst < 0.03, "the arc is off the circle by {worst}");
}

#[test]
fn counterclockwise_goes_the_other_way_round() {
    let mut cw = Path::default();
    cw.arc(&Matrix::IDENTITY, 0.0, 0.0, 10.0, 0.0, TAU / 4.0, false).unwrap();
    let mut ccw = Path::default();
    ccw.arc(&Matrix::IDENTITY, 0.0, 0.0, 10.0, 0.0, TAU / 4.0, true).unwrap();
    assert_point(last_point(&cw), (0.0, 10.0));
    assert_point(last_point(&ccw), (0.0, 10.0));
    // same endpoint, opposite route: the clockwise one is a quarter turn and the other three
    assert_eq!(cw.verbs.iter().filter(|v| matches!(v, Verb::Cubic(..))).count(), 1);
    assert_eq!(ccw.verbs.iter().filter(|v| matches!(v, Verb::Cubic(..))).count(), 3);
}

#[test]
fn angles_already_spanning_a_full_turn_give_exactly_one() {
    assert!(close_to(sweep_of(0.0, 10.0, false), TAU));
    assert!(close_to(sweep_of(0.0, -10.0, true), -TAU));
    assert!(close_to(sweep_of(0.0, TAU / 2.0, false), TAU / 2.0));
    assert!(close_to(sweep_of(0.0, TAU / 2.0, true), -TAU / 2.0));
}

#[test]
fn an_arc_after_a_subpath_is_joined_to_it_by_a_line() {
    let mut path = Path::default();
    path.move_to(&Matrix::IDENTITY, -50.0, -50.0);
    path.arc(&Matrix::IDENTITY, 0.0, 0.0, 10.0, 0.0, TAU / 4.0, false).unwrap();
    assert_eq!(path.verbs[0], Verb::Move(-50.0, -50.0));
    assert_eq!(path.verbs[1], Verb::Line(10.0, 0.0));
}

#[test]
fn a_negative_radius_is_an_error_rather_than_a_flipped_arc() {
    let mut path = Path::default();
    assert_eq!(path.arc(&Matrix::IDENTITY, 0.0, 0.0, -1.0, 0.0, 1.0, false), Err(ArcError::NegativeRadius),);
    assert!(path.is_empty());
}

#[test]
fn a_rotated_ellipse_is_not_an_axis_aligned_oval() {
    let mut path = Path::default();
    path.ellipse(&Matrix::IDENTITY, 0.0, 0.0, 20.0, 5.0, TAU / 8.0, 0.0, TAU, false).unwrap();
    // the point at angle 0 is (20, 0) rotated an eighth turn
    let r = (TAU / 8.0).cos() * 20.0;
    assert_point(path_first_point(&path), (r, r));
}

fn path_first_point(path: &Path) -> (f64, f64) {
    match path.verbs[0] {
        Verb::Move(x, y) => (x, y),
        other => panic!("the path starts with {other:?}"),
    }
}

#[test]
fn arc_to_rounds_a_right_angle_at_the_tangent_points() {
    let mut path = Path::default();
    path.move_to(&Matrix::IDENTITY, 0.0, 0.0);
    path.arc_to(&Matrix::IDENTITY, &Matrix::IDENTITY, 100.0, 0.0, 100.0, 100.0, 50.0).unwrap();
    // the leg is r/tan(45) = r, so the arc starts at (50, 0) and ends at (100, 50)
    let Verb::Line(x, y) = path.verbs[1] else { panic!("no join line: {:?}", path.verbs) };
    assert_point((x, y), (50.0, 0.0));
    assert_point(last_point(&path), (100.0, 50.0));
}

#[test]
fn arc_to_with_no_subpath_just_moves_to_the_corner() {
    let mut path = Path::default();
    path.arc_to(&Matrix::IDENTITY, &Matrix::IDENTITY, 7.0, 8.0, 9.0, 10.0, 4.0).unwrap();
    assert_eq!(path.verbs, vec![Verb::Move(7.0, 8.0)]);
}

#[test]
fn collinear_points_and_a_zero_radius_both_degenerate_to_a_line() {
    for (x2, y2, radius) in [(200.0, 0.0, 50.0), (100.0, 100.0, 0.0)] {
        let mut path = Path::default();
        path.move_to(&Matrix::IDENTITY, 0.0, 0.0);
        path.arc_to(&Matrix::IDENTITY, &Matrix::IDENTITY, 100.0, 0.0, x2, y2, radius).unwrap();
        assert_eq!(path.verbs, vec![Verb::Move(0.0, 0.0), Verb::Line(100.0, 0.0)]);
    }
}

#[test]
fn arc_to_turns_the_way_the_corner_does() {
    // mirrored corner: the same geometry reflected in y, so the arc has to sweep the other way
    let mut path = Path::default();
    path.move_to(&Matrix::IDENTITY, 0.0, 0.0);
    path.arc_to(&Matrix::IDENTITY, &Matrix::IDENTITY, 100.0, 0.0, 100.0, -100.0, 50.0).unwrap();
    assert_point(last_point(&path), (100.0, -50.0));
    // sweeping the other way would be three quarters of the circle and would reach x = 150
    let widest = path
        .verbs
        .iter()
        .filter_map(|v| match v {
            Verb::Cubic(a, _, c, _, e, _) => Some(a.max(*c).max(*e)),
            _ => None,
        })
        .fold(f64::MIN, f64::max);
    assert!(widest <= 100.0 + 1e-6, "the arc bulges the wrong way, out to x = {widest}");
}

#[test]
fn arc_to_reads_the_pen_in_the_current_user_space() {
    // the pen was placed under one transform and the corner is named under another; without
    // the inverse read-back the tangents would be computed against a device-space point
    let placed = Matrix::IDENTITY.scaled(2.0, 2.0);
    let now = Matrix::IDENTITY;
    let mut path = Path::default();
    path.move_to(&placed, 0.0, 0.0);
    path.arc_to(&now, &now.invert().unwrap(), 100.0, 0.0, 100.0, 100.0, 50.0).unwrap();
    let Verb::Line(x, y) = path.verbs[1] else { panic!("no join line: {:?}", path.verbs) };
    assert_point((x, y), (50.0, 0.0));
}

#[test]
fn arc_to_refuses_a_negative_radius() {
    let mut path = Path::default();
    path.move_to(&Matrix::IDENTITY, 0.0, 0.0);
    assert_eq!(
        path.arc_to(&Matrix::IDENTITY, &Matrix::IDENTITY, 1.0, 1.0, 2.0, 2.0, -1.0),
        Err(ArcError::NegativeRadius),
    );
}

#[test]
fn round_rect_corners_are_scaled_as_a_set_when_they_do_not_fit() {
    let (_, _, _, _, radii) =
        normalize_round_rect(0.0, 0.0, 100.0, 100.0, [(80.0, 80.0), (80.0, 80.0), (0.0, 0.0), (0.0, 0.0)]);
    // 80 + 80 over a 100 wide box scales everything by 100/160
    assert!(close_to(radii[0].0, 50.0) && close_to(radii[1].0, 50.0));
}

#[test]
fn round_rect_corners_keep_their_proportions_under_that_scale() {
    let (_, _, _, _, radii) =
        normalize_round_rect(0.0, 0.0, 100.0, 100.0, [(30.0, 30.0), (90.0, 90.0), (0.0, 0.0), (0.0, 0.0)]);
    assert!(close_to(radii[1].0 / radii[0].0, 3.0), "{radii:?}");
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
    path.round_rect(&Matrix::IDENTITY, x, y, w, h, radii);
    assert_eq!(path.verbs[0], Verb::Move(10.0, 0.0));
    assert_eq!(path.verbs[path.verbs.len() - 2], Verb::Close);
    assert_eq!(path.verbs[path.verbs.len() - 1], Verb::Move(0.0, 0.0));
}

#[test]
fn a_zero_radius_corner_is_a_plain_angle() {
    let mut path = Path::default();
    path.round_rect(&Matrix::IDENTITY, 0.0, 0.0, 10.0, 10.0, [(0.0, 0.0); 4]);
    assert!(!path.verbs.iter().any(|v| matches!(v, Verb::Cubic(..))));
}

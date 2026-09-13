use super::*;

fn plane(data: &mut [u8], row_stride: usize, pixel_stride: usize) -> Plane {
  Plane {
    ptr: data.as_mut_ptr(),
    len: data.len(),
    row_stride,
    pixel_stride,
  }
}

fn convert(pixels: &[u8], width: usize, height: usize, y: Plane, u: Plane, v: Plane) -> Option<usize> {
  unsafe { convert_planes(pixels, width, height, &y, &u, &v) }
}

fn solid(rgba: [u8; 4], count: usize) -> Vec<u8> {
  rgba.iter().copied().cycle().take(count * 4).collect()
}

fn near(values: &[u8], expected: u8) -> bool {
  values.iter().all(|&value| (value as i32 - expected as i32).abs() <= 1)
}

#[test]
fn a_flat_colour_lands_on_its_bt601_values_in_every_plane() {
  let (width, height) = (4, 2);
  let pixels = solid([255, 0, 0, 255], width * height);
  let (mut y, mut u, mut v) = (vec![0u8; 8], vec![0u8; 2], vec![0u8; 2]);
  let v_end = v.as_ptr() as usize + 2;
  let end = convert(&pixels, width, height, plane(&mut y, 4, 1), plane(&mut u, 2, 1), plane(&mut v, 2, 1));
  // pure red: Y = 81, U = 90, V = 240 in limited-range bt.601, give or take integer rounding
  assert!(near(&y, 81), "{y:?}");
  assert!(near(&u, 90), "{u:?}");
  assert!(near(&v, 240), "{v:?}");
  assert!(end.is_some_and(|end| end >= v_end));
}

#[test]
fn an_interleaved_layout_writes_chroma_in_the_order_its_planes_start_in() {
  let (width, height) = (4, 2);
  let pixels = solid([0, 0, 255, 255], width * height);
  let mut y = vec![0u8; 8];

  let mut nv12 = vec![0u8; 4];
  let at = nv12.as_mut_ptr();
  // the second view starts one byte in and, as codecs hand it out, reaches one byte less far
  let u = Plane {
    ptr: at,
    len: 4,
    row_stride: 4,
    pixel_stride: 2,
  };
  let v = Plane {
    ptr: unsafe { at.add(1) },
    len: 2,
    row_stride: 4,
    pixel_stride: 2,
  };
  let y_end = y.as_ptr() as usize + y.len();
  let end = convert(&pixels, width, height, plane(&mut y, 4, 1), u, v);
  assert_eq!(end, Some(y_end.max(at as usize + 4)));
  // pure blue: U = 240, V = 110
  assert!(near(&[nv12[0], nv12[2]], 240), "{nv12:?}");
  assert!(near(&[nv12[1], nv12[3]], 110), "{nv12:?}");

  let mut nv21 = vec![0u8; 4];
  let at = nv21.as_mut_ptr();
  let v = Plane {
    ptr: at,
    len: 4,
    row_stride: 4,
    pixel_stride: 2,
  };
  let u = Plane {
    ptr: unsafe { at.add(1) },
    len: 3,
    row_stride: 4,
    pixel_stride: 2,
  };
  assert!(convert(&pixels, width, height, plane(&mut y, 4, 1), u, v).is_some());
  assert!(near(&[nv21[0], nv21[2]], 110), "{nv21:?}");
  assert!(near(&[nv21[1], nv21[3]], 240), "{nv21:?}");
}

#[test]
fn a_padded_row_stride_is_skipped_over_rather_than_written_into() {
  let (width, height) = (2, 2);
  let pixels = solid([255, 255, 255, 255], width * height);
  let mut y = vec![7u8; 8];
  let (mut u, mut v) = (vec![0u8; 1], vec![0u8; 1]);
  assert!(convert(&pixels, width, height, plane(&mut y, 4, 1), plane(&mut u, 1, 1), plane(&mut v, 1, 1)).is_some());
  assert_eq!(y, [235, 235, 7, 7, 235, 235, 7, 7]);
}

#[test]
fn a_last_row_that_stops_short_of_its_stride_is_still_written() {
  let (width, height) = (4, 4);
  let pixels = solid([255, 255, 255, 255], width * height);
  // a codec's planes end at the last row's pixels, not at its stride
  let mut y = vec![7u8; 6 * 3 + 4];
  let (mut u, mut v) = (vec![7u8; 3 + 2], vec![7u8; 3 + 2]);
  assert!(convert(&pixels, width, height, plane(&mut y, 6, 1), plane(&mut u, 3, 1), plane(&mut v, 3, 1)).is_some());
  for row in y.chunks(6) {
    assert_eq!(&row[..4], [235; 4], "{y:?}");
    assert!(row[4..].iter().all(|&byte| byte == 7), "{y:?}");
  }
  for chroma in [&u, &v] {
    assert_eq!([chroma[0], chroma[1], chroma[3], chroma[4]], [128; 4], "{chroma:?}");
    assert_eq!(chroma[2], 7, "{chroma:?}");
  }

  let mut y = vec![7u8; 6 * 3 + 4];
  let mut uv = vec![7u8; 6 + 4];
  let at = uv.as_mut_ptr();
  let u = Plane {
    ptr: at,
    len: 9,
    row_stride: 6,
    pixel_stride: 2,
  };
  let v = Plane {
    ptr: unsafe { at.add(1) },
    len: 9,
    row_stride: 6,
    pixel_stride: 2,
  };
  assert!(convert(&pixels, width, height, plane(&mut y, 6, 1), u, v).is_some());
  assert!(y.chunks(6).all(|row| row[..4] == [235; 4]), "{y:?}");
  assert_eq!(uv, [128, 128, 128, 128, 7, 7, 128, 128, 128, 128]);
}

#[test]
fn planes_that_cannot_take_the_frame_are_refused_without_a_write() {
  let (width, height) = (4, 4);
  let pixels = vec![0u8; width * height * 4];
  let (mut y, mut u, mut v) = (vec![9u8; 16], vec![9u8; 4], vec![9u8; 4]);

  assert!(
    convert(&pixels, width, height, plane(&mut y[..15], 4, 1), plane(&mut u, 2, 1), plane(&mut v, 2, 1)).is_none()
  );
  assert!(convert(&pixels, width, height, plane(&mut y, 3, 1), plane(&mut u, 2, 1), plane(&mut v, 2, 1)).is_none());
  assert!(
    convert(&pixels[..60], width, height, plane(&mut y, 4, 1), plane(&mut u, 2, 1), plane(&mut v, 2, 1)).is_none()
  );

  let shared = plane(&mut u, 2, 1);
  let aliased = Plane {
    ptr: shared.ptr,
    len: shared.len,
    row_stride: 2,
    pixel_stride: 1,
  };
  assert!(convert(&pixels, width, height, plane(&mut y, 4, 1), shared, aliased).is_none());

  let mut other = vec![9u8; 4];
  assert!(convert(&pixels, width, height, plane(&mut y, 4, 1), plane(&mut u, 4, 2), plane(&mut other, 4, 2)).is_none());

  assert!(y.iter().chain(&u).chain(&other).all(|&byte| byte == 9));
}

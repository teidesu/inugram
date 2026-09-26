//! Pixels are `copyPixelsToBuffer` ARGB_8888 (R, G, B, A in byte order, premultiplied); the output is
//! BT.601 limited range, what an h264 encoder takes unless told otherwise.

use jni::objects::{JByteBuffer, JClass};
use jni::sys::jint;
use jni::{Env, EnvUnowned};
use yuv::{BufferStoreMut, YuvBiPlanarImageMut, YuvConversionMode, YuvPlanarImageMut, YuvRange, YuvStandardMatrix};

use super::env::in_env;

pub(crate) struct Plane {
  pub ptr: *mut u8,
  pub len: usize,
  pub row_stride: usize,
  pub pixel_stride: usize,
}

fn reach(row_stride: usize, rows: usize, columns: usize) -> Option<usize> {
  if row_stride < columns {
    return None;
  }
  row_stride.checked_mul(rows - 1)?.checked_add(columns)
}

fn disjoint(a: (usize, usize), b: (usize, usize)) -> bool {
  a.0 + a.1 <= b.0 || b.0 + b.1 <= a.0
}

struct Rows {
  ptr: *mut u8,
  row_stride: usize,
  columns: usize,
}

impl Rows {
  /// # Safety
  /// `count` whole strides from row `from` must lie inside the plane.
  unsafe fn strides<'a>(&self, from: usize, count: usize) -> &'a mut [u8] {
    std::slice::from_raw_parts_mut(self.ptr.add(from * self.row_stride), count * self.row_stride)
  }

  /// # Safety
  /// Row `index` must lie inside the plane.
  unsafe fn write_rows(&self, from: usize, bytes: &[u8]) {
    for (index, row) in bytes.chunks_exact(self.columns).enumerate() {
      std::ptr::copy_nonoverlapping(row.as_ptr(), self.ptr.add((from + index) * self.row_stride), self.columns);
    }
  }
}

enum Chroma {
  Planar { u: Rows, v: Rows },
  Interleaved { uv: Rows, blue_first: bool },
}

enum ChromaBytes<'a> {
  Planar { u: &'a mut [u8], u_stride: usize, v: &'a mut [u8], v_stride: usize },
  Interleaved { uv: &'a mut [u8], stride: usize, blue_first: bool },
}

fn convert_rows(
  pixels: &[u8],
  width: usize,
  rows: usize,
  y: &mut [u8],
  y_stride: usize,
  chroma: ChromaBytes,
) -> Option<()> {
  let (image_width, image_height) = (u32::try_from(width).ok()?, u32::try_from(rows).ok()?);
  let rgba_stride = u32::try_from(width * 4).ok()?;
  let (range, matrix, mode) = (YuvRange::Limited, YuvStandardMatrix::Bt601, YuvConversionMode::Balanced);
  let y_plane = BufferStoreMut::Borrowed(y);
  let y_stride = u32::try_from(y_stride).ok()?;
  match chroma {
    ChromaBytes::Planar { u, u_stride, v, v_stride } => {
      let mut image = YuvPlanarImageMut {
        y_plane,
        y_stride,
        u_plane: BufferStoreMut::Borrowed(u),
        u_stride: u32::try_from(u_stride).ok()?,
        v_plane: BufferStoreMut::Borrowed(v),
        v_stride: u32::try_from(v_stride).ok()?,
        width: image_width,
        height: image_height,
      };
      yuv::rgba_to_yuv420(&mut image, pixels, rgba_stride, range, matrix, mode).ok()
    }
    ChromaBytes::Interleaved { uv, stride, blue_first } => {
      let mut image = YuvBiPlanarImageMut {
        y_plane,
        y_stride,
        uv_plane: BufferStoreMut::Borrowed(uv),
        uv_stride: u32::try_from(stride).ok()?,
        width: image_width,
        height: image_height,
      };
      if blue_first {
        yuv::rgba_to_yuv_nv12(&mut image, pixels, rgba_stride, range, matrix, mode).ok()
      } else {
        yuv::rgba_to_yuv_nv21(&mut image, pixels, rgba_stride, range, matrix, mode).ok()
      }
    }
  }
}

/// Codecs commonly hand out planes whose last row stops at its pixels rather than its stride, which
/// the crate cannot take. Every row pair but the last is converted in place; the last goes through
/// a scratch row pair at tight strides and is copied in. Chroma averages within a row pair, so the
/// split changes no value.
///
/// # Safety
/// Every plane's `ptr` must be valid for writes over `len` bytes for the duration of the call, and
/// nothing else may touch them meanwhile. The u and v planes of an interleaved layout start one
/// byte apart within one allocation.
pub(crate) unsafe fn convert_planes(
  pixels: &[u8],
  width: usize,
  height: usize,
  y: &Plane,
  u: &Plane,
  v: &Plane,
) -> Option<usize> {
  if width == 0 || height == 0 || pixels.len() < width.checked_mul(height)?.checked_mul(4)? {
    return None;
  }
  let (chroma_width, chroma_height) = (width.div_ceil(2), height.div_ceil(2));
  if y.pixel_stride != 1 {
    return None;
  }
  let y_len = reach(y.row_stride, height, width)?;
  if y.len < y_len {
    return None;
  }
  let y_range = (y.ptr as usize, y_len);

  let (chroma, chroma_end) = match (u.pixel_stride, v.pixel_stride) {
    (1, 1) => {
      let u_len = reach(u.row_stride, chroma_height, chroma_width)?;
      let v_len = reach(v.row_stride, chroma_height, chroma_width)?;
      if u.len < u_len || v.len < v_len {
        return None;
      }
      let (u_range, v_range) = ((u.ptr as usize, u_len), (v.ptr as usize, v_len));
      if !disjoint(y_range, u_range) || !disjoint(y_range, v_range) || !disjoint(u_range, v_range) {
        return None;
      }
      let chroma = Chroma::Planar {
        u: Rows {
          ptr: u.ptr,
          row_stride: u.row_stride,
          columns: chroma_width,
        },
        v: Rows {
          ptr: v.ptr,
          row_stride: v.row_stride,
          columns: chroma_width,
        },
      };
      (chroma, (u_range.0 + u_len).max(v_range.0 + v_len))
    }
    (2, 2) if u.row_stride == v.row_stride => {
      let (first, second, blue_first) = if v.ptr as usize == u.ptr as usize + 1 {
        (u, v, true)
      } else if u.ptr as usize == v.ptr as usize + 1 {
        (v, u, false)
      } else {
        return None;
      };
      let uv_len = reach(first.row_stride, chroma_height, chroma_width * 2)?;
      // a codec's second chroma view commonly stops where the first does, or one byte short of it
      if first.len.max(second.len + 1) < uv_len {
        return None;
      }
      let uv_range = (first.ptr as usize, uv_len);
      if !disjoint(y_range, uv_range) {
        return None;
      }
      let chroma = Chroma::Interleaved {
        uv: Rows {
          ptr: first.ptr,
          row_stride: first.row_stride,
          columns: chroma_width * 2,
        },
        blue_first,
      };
      (chroma, uv_range.0 + uv_len)
    }
    _ => return None,
  };
  let luma = Rows {
    ptr: y.ptr,
    row_stride: y.row_stride,
    columns: width,
  };

  let tail = (if height.is_multiple_of(2) { 2 } else { 3 }).min(height);
  let body = height - tail;
  let body_chroma = body / 2;
  if body > 0 {
    let bytes = match &chroma {
      Chroma::Planar { u, v } => ChromaBytes::Planar {
        u: u.strides(0, body_chroma),
        u_stride: u.row_stride,
        v: v.strides(0, body_chroma),
        v_stride: v.row_stride,
      },
      Chroma::Interleaved { uv, blue_first } => ChromaBytes::Interleaved {
        uv: uv.strides(0, body_chroma),
        stride: uv.row_stride,
        blue_first: *blue_first,
      },
    };
    convert_rows(&pixels[..body * width * 4], width, body, luma.strides(0, body), luma.row_stride, bytes)?;
  }

  let tail_chroma = chroma_height - body_chroma;
  let mut tail_luma = vec![0u8; width * tail];
  let tail_pixels = &pixels[body * width * 4..height * width * 4];
  match &chroma {
    Chroma::Planar { u, v } => {
      let (mut tail_u, mut tail_v) = (vec![0u8; chroma_width * tail_chroma], vec![0u8; chroma_width * tail_chroma]);
      let bytes = ChromaBytes::Planar {
        u: &mut tail_u,
        u_stride: chroma_width,
        v: &mut tail_v,
        v_stride: chroma_width,
      };
      convert_rows(tail_pixels, width, tail, &mut tail_luma, width, bytes)?;
      u.write_rows(body_chroma, &tail_u);
      v.write_rows(body_chroma, &tail_v);
    }
    Chroma::Interleaved { uv, blue_first } => {
      let mut tail_uv = vec![0u8; uv.columns * tail_chroma];
      let bytes = ChromaBytes::Interleaved {
        uv: &mut tail_uv,
        stride: uv.columns,
        blue_first: *blue_first,
      };
      convert_rows(tail_pixels, width, tail, &mut tail_luma, width, bytes)?;
      uv.write_rows(body_chroma, &tail_uv);
    }
  }
  luma.write_rows(body, &tail_luma);
  Some((y_range.0 + y_len).max(chroma_end))
}

fn plane(env: &Env, buffer: &JByteBuffer, offset: jint, row_stride: jint, pixel_stride: jint) -> Option<Plane> {
  if offset < 0 || row_stride <= 0 || pixel_stride <= 0 {
    return None;
  }
  let address = env.get_direct_buffer_address(buffer).ok()?;
  let capacity = env.get_direct_buffer_capacity(buffer).ok()?;
  let offset = offset as usize;
  if offset > capacity {
    return None;
  }
  Some(Plane {
    ptr: address.wrapping_add(offset),
    len: capacity - offset,
    row_stride: row_stride as usize,
    pixel_stride: pixel_stride as usize,
  })
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_desu_inugram_helpers_plugins_ui_NativePixels_rgbaToYuv420<'local>(
  mut env: EnvUnowned<'local>,
  _class: JClass<'local>,
  pixels: JByteBuffer<'local>,
  width: jint,
  height: jint,
  y_buffer: JByteBuffer<'local>,
  y_offset: jint,
  y_row_stride: jint,
  y_pixel_stride: jint,
  u_buffer: JByteBuffer<'local>,
  u_offset: jint,
  u_row_stride: jint,
  u_pixel_stride: jint,
  v_buffer: JByteBuffer<'local>,
  v_offset: jint,
  v_row_stride: jint,
  v_pixel_stride: jint,
) -> jint {
  in_env(&mut env, -1, |env| {
    if width <= 0 || height <= 0 {
      return -1;
    }
    let (Some(y), Some(u), Some(v)) = (
      plane(env, &y_buffer, y_offset, y_row_stride, y_pixel_stride),
      plane(env, &u_buffer, u_offset, u_row_stride, u_pixel_stride),
      plane(env, &v_buffer, v_offset, v_row_stride, v_pixel_stride),
    ) else {
      return -1;
    };
    let Some(source) = plane(env, &pixels, 0, 1, 1) else {
      return -1;
    };
    // SAFETY: the pixel buffer and the planes are direct memory the java side holds for the whole
    // call, and the codec's input buffer is not touched by anything else while it is dequeued
    let end = unsafe {
      let source = std::slice::from_raw_parts(source.ptr as *const u8, source.len);
      convert_planes(source, width as usize, height as usize, &y, &u, &v)
    };
    let Some(end) = end else {
      return -1;
    };
    // Image planes are slices: position() excludes their offset in the codec's common allocation.
    let base = (y.ptr as usize - y_offset as usize)
      .min(u.ptr as usize - u_offset as usize)
      .min(v.ptr as usize - v_offset as usize);
    jint::try_from(end - base).unwrap_or(-1)
  })
}

#[cfg(test)]
#[path = "pixels_tests.rs"]
mod pixels_tests;

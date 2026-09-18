use std::cell::RefCell;
use std::fs;
use std::io::Write;
use std::path::PathBuf;
use std::rc::Rc;

use rquickjs::{Ctx, Result as JsResult, TypedArray, Value};

use crate::api::error::PluginErrorCode;
use crate::api::io::blob::{BlobHandle, BlobState};
use crate::api::io::fs::FsState;
use crate::sandbox::registry::RequestIds;

const CHUNK_BYTES: u64 = 256 * 1024;

/// a file this engine wrote out for the host, deleted with whatever holds it
pub struct StagedFile(pub PathBuf);

impl Drop for StagedFile {
  fn drop(&mut self) {
    let _ = fs::remove_file(&self.0);
  }
}

/// a source the host is about to read, and whether this staged it: a file a plugin named is the
/// plugin's own and stays where it is, while a blob's content was copied out for the host to reach
pub struct StagedSource {
  pub path: PathBuf,
  pub owned: bool,
}

/// Turns the `Blob | Uint8Array | { path }` every api that takes a file takes into a path on disk,
/// which is the only thing the host side can read: blob content lives in rust, so handing it over
/// means writing it out first.
pub struct SourceStager {
  blobs: Rc<BlobState>,
  fs: RefCell<Option<Rc<FsState>>>,
  dir: PathBuf,
  prefix: &'static str,
  limit: u64,
  /// the api this stages for, named in the message a source too big for it is refused with
  what: &'static str,
  next: RequestIds,
}

impl SourceStager {
  pub fn new(blobs: Rc<BlobState>, dir: PathBuf, prefix: &'static str, limit: u64, what: &'static str) -> Self {
    SourceStager {
      blobs,
      fs: RefCell::new(None),
      dir,
      prefix,
      limit,
      what,
      next: RequestIds::default(),
    }
  }

  /// `inu.fs` installs after everything that names a file through it, so the resolver arrives late
  pub fn attach_fs(&self, fs: Rc<FsState>) {
    *self.fs.borrow_mut() = Some(fs);
  }

  pub fn stage<'js>(&self, ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<StagedSource> {
    if let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) {
      let Some(bytes) = typed.as_bytes() else {
        return PluginErrorCode::InvalidArgument.throw(ctx, "this Uint8Array is detached");
      };
      self.check_limit(ctx, bytes.len() as u64)?;
      return Ok(StagedSource {
        path: self.write(ctx, |file| file.write_all(bytes))?,
        owned: true,
      });
    }
    if rquickjs::Class::<BlobHandle>::from_value(value).is_ok() {
      return self.stage_blob(ctx, value);
    }
    if let Some(object) = value.as_object() {
      if let Some(path) = object.get::<_, Option<String>>("path")? {
        let Some(fs) = self.fs.borrow().clone() else {
          return PluginErrorCode::NotGranted("fs").throw(ctx, "naming a file needs @grant fs");
        };
        return Ok(StagedSource {
          path: fs.resolve_external(ctx, &path)?,
          owned: false,
        });
      }
    }
    PluginErrorCode::InvalidArgument.throw(ctx, "expected a Blob, a Uint8Array or { path }")
  }

  fn stage_blob<'js>(&self, ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<StagedSource> {
    let Some(exported) = self.blobs.export_for_host(value) else {
      return PluginErrorCode::HandleExpired.throw(ctx, "this blob has been disposed");
    };
    let Some(id) = crate::api::io::blob::export_id_of(&exported) else {
      return PluginErrorCode::Internal.throw(ctx, "this blob could not be handed over");
    };
    let Some(export) = self.blobs.resolve_export(id) else {
      return PluginErrorCode::HandleExpired.throw(ctx, "this blob has been disposed");
    };
    let len = export.len();
    self.check_limit(ctx, len)?;
    let path = self.write(ctx, |file| {
      let mut at = 0u64;
      while at < len {
        let take = CHUNK_BYTES.min(len - at);
        let chunk = export
          .read(at, take)
          .map_err(|_| std::io::Error::other("this blob's content is no longer readable"))?;
        file.write_all(&chunk)?;
        at += take;
      }
      Ok(())
    })?;
    Ok(StagedSource { path, owned: true })
  }

  fn write<'js>(&self, ctx: &Ctx<'js>, fill: impl FnOnce(&mut fs::File) -> std::io::Result<()>) -> JsResult<PathBuf> {
    if self.dir.as_os_str().is_empty() {
      return PluginErrorCode::Internal.throw(ctx, "this engine has no directory to stage a source in");
    }
    let n = self.next.alloc();
    let path = self.dir.join(format!("{}-{n}.bin", self.prefix));
    let written = fs::create_dir_all(&self.dir)
      .and_then(|_| fs::File::create(&path))
      .and_then(|mut file| fill(&mut file).and_then(|_| file.sync_all()));
    if let Err(e) = written {
      let _ = fs::remove_file(&path);
      return PluginErrorCode::Internal.throw(ctx, &format!("staging this source failed: {e}"));
    }
    Ok(path)
  }

  fn check_limit(&self, ctx: &Ctx<'_>, len: u64) -> JsResult<()> {
    if len <= self.limit {
      return Ok(());
    }
    PluginErrorCode::QuotaExceeded(len as i64, self.limit as i64).throw(
      ctx,
      &format!("this source is {len} bytes; at most {} may be handed to {} in one call", self.limit, self.what),
    )
  }
}

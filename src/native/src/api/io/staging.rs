use std::cell::RefCell;
use std::fs;
use std::io::Write;
use std::path::PathBuf;
use std::rc::Rc;

use rquickjs::{Ctx, Result as JsResult, TypedArray, Value};

use crate::api::error::PluginErrorCode;
use crate::api::io::blob::BlobHandle;
use crate::api::io::fs::FsState;
use crate::sandbox::registry::RequestIds;
use crate::utils::qjs::qjs_read_typed_bytes;

pub struct StagedFile(pub PathBuf);

impl Drop for StagedFile {
  fn drop(&mut self) {
    let _ = fs::remove_file(&self.0);
  }
}

pub struct StagedSource {
  pub path: PathBuf,
  pub owned: bool,
}

pub struct SourceStager {
  fs: RefCell<Option<Rc<FsState>>>,
  dir: PathBuf,
  prefix: &'static str,
  limit: u64,
  what: &'static str,
  next: RequestIds,
}

impl SourceStager {
  pub fn new(dir: PathBuf, prefix: &'static str, limit: u64, what: &'static str) -> Self {
    SourceStager {
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
      // an error thrown past the limit is built after the last use of the bytes
      let written = qjs_read_typed_bytes(&typed, |bytes| {
        self.check_limit(ctx, bytes.len() as u64)?;
        self.write(ctx, |file| file.write_all(bytes))
      });
      let Some(written) = written else {
        return PluginErrorCode::InvalidArgument.throw(ctx, "this Uint8Array is detached");
      };
      return Ok(StagedSource { path: written?, owned: true });
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
    let Some(export) = crate::api::io::blob::export_blob(value) else {
      return PluginErrorCode::HandleExpired.throw(ctx, "this blob has been disposed");
    };
    self.check_limit(ctx, export.len())?;
    let path = self.write(ctx, |file| {
      export.write_to(file).map_err(|fault| std::io::Error::other(fault.message().to_string()))
    })?;
    Ok(StagedSource { path, owned: true })
  }

  pub(crate) fn write<'js>(
    &self,
    ctx: &Ctx<'js>,
    fill: impl FnOnce(&mut fs::File) -> std::io::Result<()>,
  ) -> JsResult<PathBuf> {
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

  pub(crate) fn check_limit(&self, ctx: &Ctx<'_>, len: u64) -> JsResult<()> {
    if len <= self.limit {
      return Ok(());
    }
    PluginErrorCode::QuotaExceeded(len as i64, self.limit as i64).throw(
      ctx,
      &format!("this source is {len} bytes; at most {} may be handed to {} in one call", self.limit, self.what),
    )
  }
}

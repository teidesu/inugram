declare namespace inu {
  /**
   * Plugin storage is per-install, so it is **capped at 50 MB**. `quota()` returns the cap, in
   * bytes: 50 MB. Every path is relative to that private directory, and one that leaves it - an
   * absolute path, a `..` that climbs out, a symlink pointing away - is `not-granted`.
   *
   * `unsafe.fs` replaces that with the whole device as the app can see it: paths are absolute, the
   * cap is lifted, and nothing is scoped any more. Android still decides what the app may touch,
   * and it is not the same for every path. Shared storage (`/sdcard`, `/storage/emulated/0`) is
   * served through a filtered view unless the app holds all-files access, which it does not: media
   * the app has permission for and files it wrote itself are visible, everything else is not, and
   * **directories stay visible either way**, so a {@link fs.readdir} there can come back as
   * subdirectories alone with the plain files (a `.ttf`, a `.json`) missing even though they are
   * on disk. Paths outside shared storage - `/system/fonts`, the app's own directories from
   * {@link android} - are read normally.
   */
  /** @needs-grant fs */
  namespace fs {
    function read(path: string): Uint8Array

    function write(path: string, data: Blob | Uint8Array): void

    function append(path: string, data: Blob | Uint8Array): void

    function mkdir(path: string): void

    function rm(path: string, options?: { recursive?: boolean }): void

    function exists(path: string): boolean

    /**
     * The name of every entry, files and directories alike, sorted. Names only, so
     * {@link stat} is what tells a file from a directory.
     */
    function readdir(path: string): string[]

    function stat(path: string): {
      isFile: boolean
      isDirectory: boolean
      size: number
      mtime: number
      ctime: number
    }

    function copy(src: string, dest: string): void

    function move(src: string, dest: string): void

    function usage(): number

    function quota(): number
  }
}

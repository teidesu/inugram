declare namespace inu {
  /**
   * Access to filesystem.
   *
   * By default, paths are scoped to the plugin's private directory.
   * Absolute paths, `..` traversal, and symlinks that escape it throw `not-granted`.
   *
   * For full FS access, use `unsafe.fs` grant (its usage is discouraged, please contact us if you need it for some reason)
   *
   * With `unsafe.fs`, paths are absolute and the plugin storage limit does not apply.
   * Android permissions still apply. In shared storage (`/sdcard`, `/storage/emulated/0`),
   * only permitted media and files created by the app are visible.
   * {@link fs.readdir} may list directories while hiding files inside them.
   * Other paths, such as `/system/fonts` and the app directories from {@link android}, use normal filesystem access.
   *
   * **Limits: 50 MB per install (unless widened via a grant); no quota with `unsafe.fs`.**
   *
   * @needs-grant fs
   */
  namespace fs {
    function read(path: string): Uint8Array

    function write(path: string, data: Blob | Uint8Array): void

    function append(path: string, data: Blob | Uint8Array): void

    function mkdir(path: string): void

    function rm(path: string, options?: { recursive?: boolean }): void

    function exists(path: string): boolean

    /** Returns sorted file and directory names. Use {@link stat} to distinguish them. */
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

    /** Get the storage amount in bytes used by the plugin currently */
    function usage(): number

    /** Get the storage limit in bytes */
    function quota(): number
  }
}

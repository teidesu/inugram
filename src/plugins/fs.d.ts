declare namespace inu {
  /**
   * filesystem access, scoped to the plugin by default.
   *
   * with plain `@grant inu.fs` every path is **relative to this plugin's own private directory** —
   * `inu.fs.read('cache.json')` reads `<app data>/inu_plugins/scoped_<plugin hash>/cache.json`.
   * `..` cannot escape it, and absolute paths are rejected. that directory is created on first use
   * and wiped when the plugin is uninstalled, the same as its `inu.kv` store.
   *
   * `@grant fs(full)` drops the scoping: absolute paths work and the whole of the app's storage is
   * readable and writable. that is the dangerous one, and the only one that can reach another
   * plugin's data, the message cache, or the media a `getMessageFile` path points at.
   *
   * the scoped mode is deliberately cheap to grant, because the alternative was every plugin that
   * writes one temp file asking for the whole disk. cheap isn't free, though — it's storage the
   * user is paying for — so it is **capped at 50 MB**, and a write that would cross the cap throws.
   * ask for more with `@grant fs(200mb)`; the number is shown to the user next to the plugin, which
   * is the whole point of declaring it up front rather than growing quietly. `fs(full)` is
   * uncapped. the plugins list shows per-plugin usage with a way to clear it, and `usage()`/
   * `quota()` are the same numbers so a plugin can police itself first.
   *
   * for files that shouldn't count against any of that, see `createTempFile`.
   *
   * @needs-grant fs
   */
  namespace fs {
    /** the whole file. text is `inu.utils`' job — `new TextDecoder().decode(inu.fs.read(p))` */
    function read(path: string): Uint8Array
    function write(path: string, data: Uint8Array): void
    /** appends, creating the file if absent */
    function append(path: string, data: Uint8Array): void
    /** creates parent directories too */
    function mkdir(path: string): void
    function rm(path: string, options?: { recursive?: boolean }): void
    function exists(path: string): boolean
    /** names only, not paths; no `.`/`..` entries */
    function readdir(path: string): string[]
    function stat(path: string): {
      isFile: boolean
      isDirectory: boolean
      size: number
      /** unix millis. android can't answer creation time on most filesystems, so there's no `birthtime` */
      mtime: number
      ctime: number
    }
    function copy(src: string, dest: string): void
    function move(src: string, dest: string): void
    function glob(pattern: string): string[]

    /**
     * bytes currently stored in the plugin's scoped directory. the same number the plugins list
     * shows, so a plugin that caches things can police itself before the user has to.
     *
     * temp files aren't counted — they're somewhere else entirely, and clean themselves (see below).
     */
    function usage(): number
    /** the cap, in bytes: 50 MB, or whatever `@grant fs(...)` asked for. `Infinity` under `fs(full)` */
    function quota(): number

    // -- temp files --
    // everything above is durable: it survives restarts, sticks around until the plugin deletes it,
    // and counts against the quota. plenty of files aren't meant to — you draw an image, send it,
    // and never want it again — and leaving those to `rm` means one forgotten error path silently
    // eats the plugin's whole allowance.
    //
    // so paths under the reserved `.tmp/` prefix don't resolve inside the scoped directory at all:
    // they land in a *sibling* one (`scoped_<hash>.tmp/`) in the app's cache area. that's what buys
    // the lifetime — wiped when the plugin unloads, evictable by the os under storage pressure, and
    // outside the quota, none of which the storage directory can offer.
    //
    // every function above accepts a `.tmp/` path, including `rm` on an individual temp file if you
    // want it gone early. the directory itself is engine-managed and not addressable: `readdir` and
    // `glob` on the scoped root never see it (it isn't in there), and `rm` can't take it — that's
    // what `clearTempFiles` is for.

    /**
     * create an empty file in the temp area and return its path (`.tmp/<random><suffix>`). the
     * file is created here rather than merely named, so two calls can't race onto one path.
     *
     * the canonical use is handing something to a send without leaving it behind:
     *
     * ```ts
     * const path = inu.fs.createTempFile({ suffix: '.png' })
     * await canvas.toFile(path)
     * await inu.account().sendMedia('me', path)
     * ```
     *
     * `Account.downloadMedia`'s `to: 'temp'` puts a download here for the same reason.
     *
     * treat it as scratch, not storage: anything you need to keep, copy somewhere durable.
     */
    function createTempFile(options?: { suffix?: string }): string

    /** drop every temp file now, instead of waiting for unload */
    function clearTempFiles(): void
  }
}

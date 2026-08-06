declare namespace inu {
  /**
   * filesystem access, scoped to the plugin by default.
   *
   * with plain `@grant fs` every path is **relative to this plugin's own private directory** —
   * `inu.fs.read('cache.json')` reads `<app data>/inu_plugins/scoped_<install id>/cache.json`.
   * `..` cannot escape it, and absolute paths are rejected. that directory is created on first use
   * and wiped when the plugin is uninstalled, the same as its `inu.kv` store.
   *
   * the install id is assigned when the plugin is installed and is nothing the plugin file says,
   * so renaming a plugin keeps its data and no plugin can name its way into another's directory.
   *
   * `@grant unsafe.fs` is this same namespace with the scoping taken off: absolute paths work and
   * the whole of the app's storage is readable and writable. it's the one that reaches another
   * plugin's data, the message cache, the sqlite databases, and the media a `getMessageFile` path
   * points at — which is why it's named the way it is rather than as a scope on `fs`. it replaces
   * `fs` rather than adding to it; declaring both says nothing more than declaring the one.
   * relative paths still land in the plugin's own directory there, so growing the grant doesn't
   * move anything you already wrote.
   *
   * the scoped mode is deliberately cheap to grant, because the alternative was every plugin that
   * writes one file asking for the whole disk. cheap isn't free, though — it's storage the user is
   * paying for — so it is **capped at 50 MB**, and a write that would cross the cap throws. ask for
   * more with `@grant fs(200mb)`; the number is shown to the user next to the plugin, which is the
   * whole point of declaring it up front rather than growing quietly. `unsafe.fs` is uncapped. the
   * plugins list shows per-plugin usage with a way to clear it, and `usage()`/`quota()` are the
   * same numbers so a plugin can police itself first.
   *
   * every path is normalized before the scope check, so `..`, a doubled separator and a symlink all
   * resolve first and are then required to land inside the plugin's directory. that's also why
   * there's no `glob`: a pattern is a path the plugin doesn't fully spell, which makes it the one
   * shape where the check is easy to get subtly wrong for no capability the other calls don't give.
   *
   * a path that lands outside throws **`not-granted`** naming `unsafe.fs`, rather than `forbidden`:
   * there *is* a grant that would allow it, which is exactly what that code means. everything else
   * is `not-found` (nothing there), `invalid-argument` (a path that can't name anything, or the
   * wrong kind of thing at the end of it), `quota-exceeded` (carrying `usage`/`quota`) or
   * `internal`. every call here is synchronous and throws where you called it.
   *
   * everything here is *durable*: it survives restarts and sticks around until the plugin deletes
   * it. scratch — an image you drew only to send, a download you look at once — doesn't belong
   * here and doesn't need to be: `Blob` is content the app holds for you, needs no grant on
   * this namespace, and can't be left behind by a forgotten error path.
   *
   * @needs-grant fs
   */
  namespace fs {
    /**
     * the whole file. text is `inu.utils`' job — `new TextDecoder().decode(inu.fs.read(p))`
     *
     * **capped at 16 MB**, the same ceiling `blob.bytes()` has and for the same reason: the copy
     * has to fit in the js heap next to everything else you're holding. past it you get
     * `quota-exceeded` and *nothing is read*. for anything bigger, don't bring it into js —
     * `fs.write` and `fs.copy` move content without it ever crossing.
     */
    function read(path: string): Uint8Array
    /**
     * a `Blob` is written without ever crossing into js, so this is also how you keep something
     * you drew or downloaded — `fs.write('out.png', await canvas.convertToBlob())`. there is no
     * size ceiling on that path other than your quota: it's the way *out* of the 32 MB one
     * `new Blob([...])` has.
     *
     * replaces the file if it's there. the parent directory has to exist (`mkdir` first) —
     * creating one implicitly makes a typo in a path silently succeed.
     */
    function write(path: string, data: Blob | Uint8Array): void
    /** appends, creating the file if absent */
    function append(path: string, data: Blob | Uint8Array): void
    /** creates parent directories too, and is a no-op when the directory is already there */
    function mkdir(path: string): void
    /**
     * idempotent: removing what isn't there is what you asked for, not an error. a directory needs
     * `{ recursive: true }` — without it you get `invalid-argument`, so a stray path can't take a
     * subtree with it. the plugin's own directory can't be removed (`invalid-argument`); to empty
     * it, walk `readdir('.')`.
     */
    function rm(path: string, options?: { recursive?: boolean }): void
    /**
     * whether anything is at that path. a path that resolves *outside* the plugin's directory
     * throws rather than answering `false` — otherwise this alone would report on the rest of the
     * filesystem.
     */
    function exists(path: string): boolean
    /** names only, not paths; no `.`/`..` entries. sorted, so a listing is stable to compare */
    function readdir(path: string): string[]
    function stat(path: string): {
      isFile: boolean
      isDirectory: boolean
      /** 0 for a directory: what a directory entry itself costs is not a number filesystems agree on */
      size: number
      /** unix millis. android can't answer creation time on most filesystems, so there's no `birthtime` */
      mtime: number
      ctime: number
    }
    /** files only; a directory is `invalid-argument`. counts against the quota, like a write */
    function copy(src: string, dest: string): void
    /**
     * files and directories alike. never charged against the quota: inside the scope a move can't
     * add anything, and outside it (`unsafe.fs`) there is no cap to charge.
     */
    function move(src: string, dest: string): void

    /**
     * bytes currently stored in the plugin's scoped directory. the same number the plugins list
     * shows, so a plugin that caches things can police itself before the user has to.
     */
    function usage(): number
    /** the cap, in bytes: 50 MB, or whatever `@grant fs(...)` asked for. `Infinity` under `unsafe.fs` */
    function quota(): number
  }
}

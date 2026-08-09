declare namespace inu {
  /** Plugin storage is per-install, so it is **capped at 50 MB**. `quota()` returns the cap, in bytes: 50 MB. */
  /** @needs-grant fs */
  namespace fs {
    function read(path: string): Uint8Array

    function write(path: string, data: Blob | Uint8Array): void

    function append(path: string, data: Blob | Uint8Array): void

    function mkdir(path: string): void

    function rm(path: string, options?: { recursive?: boolean }): void

    function exists(path: string): boolean

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

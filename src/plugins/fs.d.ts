declare namespace inu {
  /**
   * access to the filesystem
   * @needs-grant inu.fs
   */
  namespace fs {
    function read(path: string): string
    function write(path: string, data: string): void
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
      birthtime: number
    }
    function copy(src: string, dest: string): void
    function move(src: string, dest: string): void
    function glob(pattern: string): string[]
  }
}

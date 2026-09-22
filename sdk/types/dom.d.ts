/**
 * Prints like Node's `util.inspect`: a string argument as-is, anything else inspected two levels deep.
 * Supported formatters: `%s %d %i %f %j %o %O %c %%`.
 */
declare const console: {
  log(...args: any[]): void
  info(...args: any[]): void
  warn(...args: any[]): void
  error(...args: any[]): void
  debug(...args: any[]): void
}

declare function atob(data: string): string
declare function btoa(data: string): string

declare const performance: {
  now(): number
  readonly timeOrigin: number
}

declare class DOMException extends Error {
  constructor(message?: string, name?: string)

  readonly code: number
}

/**
 * Set a timer to call `callback` after `ms` milliseconds.
 *
 * **Limits: 512 active timeouts and intervals combined per plugin.**
 *
 * @param callback function to call
 * @param ms timeout in milliseconds
 * @returns identifier of the timeout
 */
declare function setTimeout(callback: () => void, ms?: number): number
/** Cancel a timer previously set by {@link setTimeout} */
declare function clearTimeout(id?: number): void
/**
 * Set a timer to call `callback` every `ms` milliseconds.
 *
 * **Limits: 4 ms minimum interval; counts toward the shared timer limit (512).**
 */
declare function setInterval(callback: () => void, ms?: number): number
/** Cancel a timer previously set by {@link setInterval} */
declare function clearInterval(id?: number): void
/** Queue a `callback` to be invoked after the current job */
declare function queueMicrotask(callback: () => void): void

declare class TextEncoder {
  /** Encode the `input` as UTF-8 bytes */
  encode(input?: string): Uint8Array
  readonly encoding: 'utf-8'
}
declare class TextDecoder {
  constructor(label?: 'utf-8')
  /** Decode bytes from `input` as a UTF-8 string */
  decode(input?: Uint8Array | ArrayBufferLike): string
  readonly encoding: 'utf-8'
}

declare class URL {
  constructor(url: string, base?: string)
  static canParse(url: string, base?: string): boolean
  static parse(url: string, base?: string): URL | null

  href: string
  protocol: string
  username: string
  password: string
  host: string
  hostname: string
  port: string
  pathname: string
  search: string
  hash: string

  readonly searchParams: URLSearchParams
  readonly origin: string
  toString(): string
  toJSON(): string
}

declare class URLSearchParams {
  constructor(init?: string | string[][] | Record<string, string> | URLSearchParams)
  readonly size: number
  append(name: string, value: string): void

  delete(name: string, value?: string): void
  get(name: string): string | null
  getAll(name: string): string[]
  has(name: string, value?: string): boolean

  set(name: string, value: string): void

  sort(): void
  forEach(callback: (value: string, name: string, parent: URLSearchParams) => void, thisArg?: any): void
  keys(): IterableIterator<string>
  values(): IterableIterator<string>
  entries(): IterableIterator<[string, string]>
  [Symbol.iterator](): IterableIterator<[string, string]>
  toString(): string
}

declare const crypto: {
  /** Generate crypto-safe random values */
  getRandomValues: <T extends Uint8Array>(array: T) => T
  /** Generate a random UUIDv4 */
  randomUUID: () => string
}

declare class AbortController {
  readonly signal: AbortSignal
  abort(reason?: any): void
}

declare interface AbortSignal {
  readonly aborted: boolean
  readonly reason: any
  addEventListener(type: 'abort', listener: () => void): void
  removeEventListener(type: 'abort', listener: () => void): void
}

declare function structuredClone<T>(value: T): T

/** Web-like `Storage` interface. Its only instance is {@link localStorage}. */
declare class Storage {
  private constructor()
  /** Number of stored items */
  readonly length: number
  /** Key of the `index`-th item, in sorted key order, or `null` past the end */
  key(index: number): string | null
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  /**
   * **Inu-specific**
   *
   * Store several items in one write: either all of them are stored, or, past the quota, none.
   */
  setItems(items: Record<string, string>): void
  removeItem(key: string): void
  clear(): void
  /**
   * Items are properties too: `localStorage.foo = 'bar'` stores one, and `delete localStorage.foo`
   * removes it. A property of `Storage.prototype` (e.g. `getItem`, `length`) always reads as itself;
   * use {@link getItem} to read an item named like one.
   */
  [key: string]: any
}

/**
 * Persistent storage private to the plugin. It survives restarts, reloads and updates, and is wiped on uninstall.
 *
 * **Limits: 1 MB per plugin, counted as UTF-8 bytes**
 *
 * @throws `QuotaExceededError` {@link DOMException} when quota is exceeded
 */
declare const localStorage: Storage

/**
 * Web-like `Blob` interface, backed by memory or a file.
 *
 * **Limits: 32 MB per construction; 2 GB of blob data and 64 blobs stored on disk per plugin.**
 */
declare class Blob {
  constructor(parts?: (Blob | Uint8Array | ArrayBuffer | string)[], options?: { type?: string })
  /** Size of the blob in bytes */
  readonly size: number

  /** Note: MIME types are truncated at 1024 characters. */
  readonly type: string

  /** Create a `Blob` referencing a slice of the current one */
  slice(start?: number, end?: number, contentType?: string): Blob

  /**
   * Convert the blob into a `Uint8Array`
   *
   * **Limits: 16 MB per read.**
   */
  bytes(): Promise<Uint8Array>
  /**
   * Convert the blob into a `ArrayBuffer`
   *
   * **Limits: 16 MB per read.**
   */
  arrayBuffer(): Promise<ArrayBuffer>

  /**
   * Convert the blob into a string, decoding it as UTF-8
   *
   * **Limits: 8 MB per read.**
   */
  text(): Promise<string>

  /**
   * **Inu-specific**
   *
   * Dispose the blob and the backing memory right away. `Blob`-s are still garbage collected,
   * but manually disposing of them may lead to more efficient memory management.
   *
   * Also available as `[Symbol.dispose]`, for example: `using png = await canvas.convertToBlob()`.
   */
  dispose(): void
  [Symbol.dispose](): void
}

/** A {@link Blob} with extra fields specific to files. */
declare class File extends Blob {
  /**
   * @param parts Contents of the blob
   * @param name File name
   * @param options Extra options
   */
  constructor(
    parts: (Blob | Uint8Array | ArrayBuffer | string)[],
    name: string,
    options?: {
      /** MIME type of the file */
      type?: string
      /** Custom "last modified" date */
      lastModified?: number
    }
  )

  /** Note: file names are truncated at 1024 characters. */
  readonly name: string
  /** Modification date of the file */
  readonly lastModified: number
}

declare type HeadersInit = Record<string, string | string[]>

/** HTTP response, returned by {@link fetch} */
declare interface Response {
  /** Whether the request returned 200-ish response code */
  readonly ok: boolean
  /** Response status code */
  readonly status: number
  /** Response status text */
  readonly statusText: string

  /** Final URL of the request, after any redirects */
  readonly url: string
  /** Response headers */
  readonly headers: Record<string, string | string[]>

  /** Fetch the response body as a string */
  text(): Promise<string>
  /** Fetch the response body and parse it as JSON */
  json(): Promise<any>

  /** Fetch the response body as a `Uint8Array` */
  bytes(): Promise<Uint8Array>
  /** Fetch the response body as an `ArrayBuffer` */
  arrayBuffer(): Promise<ArrayBuffer>
  /** Fetch the response body as a {@link Blob} */
  blob(): Promise<Blob>
}

/**
 * `fetch`-like helper to make HTTP requests.
 *
 * While being *close* to WHATWG fetch, this is a simplified implementation lacking some of the features.
 *
 * **Limits: 32 MB per request or response, 256 MB of fetched content held per plugin.**
 *
 * @needs-grant fetch
 */
declare function fetch(url: string, init?: {
  /** Request HTTP method */
  method?: string
  /** Request headers */
  headers?: HeadersInit
  /** Request body */
  body?: string | Uint8Array | Blob

  /**
   * Redirect behavior:
   * - `follow`: automatically follow redirects, up to 20 hops
   * - `manual`: redirects are not followed, 3xx responses are returned
   * - `error`: when a redirect is returned, an error is thrown.
   */
  redirect?: 'follow' | 'manual' | 'error'

  /** Abort signal for the request */
  signal?: AbortSignal

  /** Timeout for the request */
  timeout?: number
}): Promise<Response>

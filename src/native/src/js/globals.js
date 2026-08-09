(native) => {
  const define = (name, value) => {
    Object.defineProperty(globalThis, name, { value, writable: true, configurable: true })
  }

  class TextEncoder {
    get encoding() {
      return 'utf-8'
    }

    encode(input) {
      return native.encodeUtf8(input === undefined ? '' : String(input))
    }
  }

  class TextDecoder {
    constructor(label) {
      const encoding = label === undefined ? 'utf-8' : String(label).trim().toLowerCase()
      if (encoding !== 'utf-8' && encoding !== 'utf8') {
        throw new RangeError(`TextDecoder: unsupported encoding '${label}'`)
      }
    }

    get encoding() {
      return 'utf-8'
    }

    decode(input) {
      return input === undefined ? '' : native.decodeUtf8(input)
    }
  }

  define('TextEncoder', TextEncoder)
  define('TextDecoder', TextDecoder)

  const hex = []
  for (let i = 0; i < 256; i++) hex.push((i | 0x100).toString(16).slice(1))

  define('crypto', Object.freeze({
    getRandomValues(array) {
      if (!(array instanceof Uint8Array)) {
        throw new TypeError('getRandomValues: expected a Uint8Array')
      }
      // the same ceiling the web api has, and the same error for crossing it
      if (array.length > 65536) {
        throw new DOMException('getRandomValues: at most 65536 bytes at a time', 'QuotaExceededError')
      }
      return native.randomFill(array)
    },

    randomUUID() {
      const b = native.randomFill(new Uint8Array(16))
      b[6] = (b[6] & 0x0F) | 0x40
      b[8] = (b[8] & 0x3F) | 0x80
      return `${hex[b[0]]}${hex[b[1]]}${hex[b[2]]}${hex[b[3]]}-${hex[b[4]]}${hex[b[5]]}-`
        + `${hex[b[6]]}${hex[b[7]]}-${hex[b[8]]}${hex[b[9]]}-`
        + `${hex[b[10]]}${hex[b[11]]}${hex[b[12]]}${hex[b[13]]}${hex[b[14]]}${hex[b[15]]}`
    },
  }))

  const signalBrand = Symbol('AbortSignal')
  const signalState = new WeakMap()

  const stateOf = (signal) => {
    const state = signalState.get(signal)
    if (state === undefined) throw new TypeError('not an AbortSignal')
    return state
  }

  class AbortSignal {
    constructor(brand) {
      if (brand !== signalBrand) throw new TypeError('Illegal constructor')
      signalState.set(this, { aborted: false, reason: undefined, listeners: [] })
    }

    get aborted() {
      return stateOf(this).aborted
    }

    get reason() {
      return stateOf(this).reason
    }

    addEventListener(type, listener) {
      const state = stateOf(this)
      if (type !== 'abort' || typeof listener !== 'function' || state.aborted) return
      if (!state.listeners.includes(listener)) state.listeners.push(listener)
    }

    removeEventListener(type, listener) {
      const state = stateOf(this)
      if (type !== 'abort') return
      const at = state.listeners.indexOf(listener)
      if (at !== -1) state.listeners.splice(at, 1)
    }
  }

  class AbortController {
    constructor() {
      Object.defineProperty(this, 'signal', { value: new AbortSignal(signalBrand), enumerable: true })
    }

    abort(reason) {
      const state = stateOf(this.signal)
      if (state.aborted) return
      state.aborted = true
      state.reason = reason !== undefined
        ? reason
        : new DOMException('signal is aborted without reason', 'AbortError')
      for (const listener of state.listeners.splice(0, state.listeners.length)) {
        try {
          listener()
        } catch (e) {
          console.error('AbortSignal listener threw:', (e && e.stack) || String(e))
        }
      }
    }
  }

  define('AbortController', AbortController)
  define('AbortSignal', AbortSignal)

  // the classes themselves come from `blob.rs`; what a native class does not get from quickjs is
  // the spec's brand, which `Object.prototype.toString` and everything built on it reads. `File`
  // needs its own, its prototype being a plain object sitting under `Blob.prototype`.
  const brand = (ctor, name) => {
    if (ctor === undefined) return
    Object.defineProperty(ctor.prototype, Symbol.toStringTag, { value: name, configurable: true })
  }
  brand(globalThis.Blob, 'Blob')
  brand(globalThis.File, 'File')

  const handleMarker = Symbol.for('inu.tl.handle')
  // null-prototype: the lookup is keyed on `error.name`, which a plugin picks - on a plain object
  // `errorTypes['constructor']` is `Object` (so the clone would be a String object rather than an
  // Error) and `errorTypes['__proto__']`/`['toString']` are not constructors at all, which throws
  // a TypeError out of structuredClone
  const errorTypes = Object.assign(Object.create(null), {
    Error,
    EvalError,
    RangeError,
    ReferenceError,
    SyntaxError,
    TypeError,
    URIError,
  })
  // captured here rather than read off globalThis per clone, so replacing the global later cannot
  // decide what does or doesn't take the blob path
  const BlobCtor = globalThis.Blob

  const uncloneable = what => new DOMException(`${what} could not be cloned`, 'DataCloneError')

  // every clone is recorded before it is returned, not only the ones that can contain themselves:
  // the web version preserves reference identity, so two fields holding the same object clone to
  // two fields holding one object, and two views over one buffer keep sharing a buffer
  const remember = (seen, value, clone) => {
    seen.set(value, clone)
    return clone
  }

  const cloneValue = (value, seen) => {
    const type = typeof value
    if (type === 'function') throw uncloneable('a function')
    if (type === 'symbol') throw uncloneable('a symbol')
    if (value === null || type !== 'object') return value
    if (seen.has(value)) return seen.get(value)
    if (value[handleMarker] !== undefined) throw uncloneable('a TL view (toJSON() detaches one)')

    // a blob clones by reference, as on the web: the clone is a second handle over the same
    // content, so disposing either one is the parent/slice relation the type already explains
    if (BlobCtor !== undefined && value instanceof BlobCtor) {
      const clone = native.cloneBlob(value)
      if (clone === undefined) throw uncloneable('an object pretending to be a Blob')
      return remember(seen, value, clone)
    }

    if (value instanceof Date) return remember(seen, value, new Date(value.getTime()))
    if (value instanceof RegExp) return remember(seen, value, new RegExp(value.source, value.flags))
    if (value instanceof ArrayBuffer) return remember(seen, value, value.slice(0))
    if (ArrayBuffer.isView(value)) {
      const buffer = cloneValue(value.buffer, seen)
      return remember(seen, value, value instanceof DataView
        ? new DataView(buffer, value.byteOffset, value.byteLength)
        : new value.constructor(buffer, value.byteOffset, value.length))
    }
    if (value instanceof Promise) throw uncloneable('a promise')
    if (value instanceof WeakMap || value instanceof WeakSet || value instanceof WeakRef) {
      throw uncloneable('a weak collection')
    }

    if (value instanceof Error) {
      const clone = new (errorTypes[value.name] || Error)(String(value.message))
      seen.set(value, clone)
      if (value.stack !== undefined) clone.stack = value.stack
      if ('cause' in value) clone.cause = cloneValue(value.cause, seen)
      return clone
    }
    if (value instanceof Map) {
      const clone = new Map()
      seen.set(value, clone)
      for (const [k, v] of value) clone.set(cloneValue(k, seen), cloneValue(v, seen))
      return clone
    }
    if (value instanceof Set) {
      const clone = new Set()
      seen.set(value, clone)
      for (const v of value) clone.add(cloneValue(v, seen))
      return clone
    }
    if (Array.isArray(value)) {
      const clone = Array.from({ length: value.length })
      seen.set(value, clone)
      for (const key of Object.keys(value)) clone[key] = cloneValue(value[key], seen)
      return clone
    }
    // eslint-disable-next-line unicorn/no-instanceof-builtins
    if (value instanceof Boolean || value instanceof Number || value instanceof String) {
      return remember(seen, value, new value.constructor(value.valueOf()))
    }

    const clone = {}
    seen.set(value, clone)
    for (const key of Object.keys(value)) clone[key] = cloneValue(value[key], seen)
    return clone
  }

  define('structuredClone', value => cloneValue(value, new Map()))
}

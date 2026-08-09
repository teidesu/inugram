(natives, PluginError, timers) => {
  const { setTimeout, clearTimeout } = timers

  const invalid = message => new PluginError('invalid-argument', message)

  // rfc7230's token, which is what a header name is allowed to be
  const TOKEN = /^[!#$%&'*+\-.^\w`|~]+$/

  // headers the transport owns: setting one of these from here either does nothing or makes the
  // request lie about its own framing
  const RESERVED = new Set([
    'host',
    'content-length',
    'connection',
    'transfer-encoding',
    'upgrade',
    'keep-alive',
    'te',
    'trailer',
  ])

  const REDIRECT_MODES = new Set(['follow', 'manual', 'error'])

  // null-prototype: `constructor` and `toString` are header names rfc7230 allows, and on a plain
  // object `name in out` answers for the whole prototype chain - so the first one of those would
  // read as a repeat and concat `Object.prototype.constructor`. `__proto__` is worse: assigning it
  // on a plain object sets the prototype instead of adding a header, and the header vanishes
  const headerMap = () => Object.create(null)

  const normalizeHeaders = (raw) => {
    if (raw === undefined || raw === null) return headerMap()
    if (typeof raw !== 'object') throw invalid('fetch: headers must be an object')
    const out = headerMap()
    for (const key of Object.keys(raw)) {
      if (!TOKEN.test(key)) throw invalid(`fetch: '${key}' is not a header name`)
      const name = key.toLowerCase()
      if (RESERVED.has(name)) throw invalid(`fetch: the '${key}' header belongs to the transport`)
      const value = raw[key]
      const values = Array.isArray(value) ? value : [value]
      for (const one of values) {
        if (typeof one !== 'string') throw invalid(`fetch: the '${key}' header must be a string`)
        // a newline in a value is a second header, and a request the plugin did not write
        if (/[\r\n\0]/.test(one)) throw invalid(`fetch: the '${key}' header has a line break in it`)
      }
      out[name] = name in out ? out[name].concat(values) : values.slice()
    }
    return out
  }

  // a header that appeared once is a string and one that repeated is an array, which is the whole
  // reason `HeadersInit` is a plain record rather than the spec's `Headers`
  const collapse = (raw) => {
    const out = headerMap()
    if (raw === undefined || raw === null) return out
    for (const key of Object.keys(raw)) {
      const values = raw[key]
      out[key] = Array.isArray(values) && values.length === 1 ? values[0] : values
    }
    return out
  }

  const bodies = new WeakMap()

  const bodyOf = (response) => {
    const blob = bodies.get(response)
    if (blob === undefined) throw new TypeError('not a Response')
    return blob
  }

  // the body is a `Blob` over the file the host wrote, so every read here inherits that type's
  // ceilings and its `handle-expired` rather than growing a second set of them
  class Response {
    constructor(raw) {
      bodies.set(this, raw.body)
      Object.defineProperties(this, {
        status: { value: raw.status, enumerable: true },
        statusText: { value: raw.statusText, enumerable: true },
        url: { value: raw.url, enumerable: true },
        ok: { value: raw.status >= 200 && raw.status < 300, enumerable: true },
        headers: { value: collapse(raw.headers), enumerable: true },
      })
    }

    blob() {
      return Promise.resolve(bodyOf(this))
    }

    bytes() {
      return bodyOf(this).bytes()
    }

    arrayBuffer() {
      return bodyOf(this).arrayBuffer()
    }

    text() {
      return bodyOf(this).text()
    }

    json() {
      return bodyOf(this).text().then(JSON.parse)
    }
  }

  const buildSpec = (init) => {
    if (init === undefined || init === null) return { method: 'GET', headers: {}, redirect: 'follow' }
    if (typeof init !== 'object') throw invalid('fetch: the second argument must be an options object')
    const redirect = init.redirect === undefined ? 'follow' : String(init.redirect)
    if (!REDIRECT_MODES.has(redirect)) throw invalid(`fetch: '${redirect}' is not a redirect mode`)
    const method = init.method === undefined ? 'GET' : String(init.method).toUpperCase()
    if (!TOKEN.test(method)) throw invalid(`fetch: '${init.method}' is not a method`)
    return { method, headers: normalizeHeaders(init.headers), redirect }
  }

  const readTimeout = (init) => {
    const raw = init === undefined || init === null ? undefined : init.timeout
    if (raw === undefined || raw === null) return undefined
    if (typeof raw !== 'number' || !Number.isFinite(raw) || raw <= 0) {
      throw invalid('fetch: timeout must be a positive number of milliseconds')
    }
    return raw
  }

  const readSignal = (init) => {
    const signal = init === undefined || init === null ? undefined : init.signal
    if (signal === undefined || signal === null) return undefined
    if (typeof signal.addEventListener !== 'function' || typeof signal.aborted !== 'boolean') {
      throw invalid('fetch: signal must be an AbortSignal')
    }
    return signal
  }

  const send = (url, init) => {
    const spec = buildSpec(init)
    const timeout = readTimeout(init)
    const signal = readSignal(init)
    const body = init === undefined || init === null ? undefined : init.body

    if (signal !== undefined && signal.aborted) {
      return Promise.reject(new PluginError('aborted', 'the request was aborted'))
    }

    // the spec crosses as an object: `JSON.stringify` is writable and shared with plugin code, so
    // serializing it here would let a plugin hand the host a spec none of the above ran on
    const started = natives.send(String(url), spec, body)

    return new Promise((resolve, reject) => {
      let settled = false
      let timer

      // whichever of the three gets here first owns the outcome; the other two become no-ops, so a
      // response that arrived just before an abort is not un-settled by it
      const finish = (run) => {
        if (settled) return false
        settled = true
        if (timer !== undefined) clearTimeout(timer)
        // eslint-disable-next-line no-use-before-define
        if (signal !== undefined) signal.removeEventListener('abort', onAbort)
        run()
        return true
      }

      const giveUp = (error) => {
        if (finish(() => reject(error))) natives.abort(started.id)
      }

      const onAbort = () => giveUp(new PluginError('aborted', 'the request was aborted'))

      started.promise.then(
        raw => finish(() => resolve(new Response(raw))),
        e => finish(() => reject(e)),
      )

      if (signal !== undefined) signal.addEventListener('abort', onAbort)
      if (timeout !== undefined) {
        timer = setTimeout(
          () => giveUp(new PluginError('timed-out', `the request timed out after ${timeout} ms`)),
          timeout,
        )
      }
    })
  }

  // every failure arrives in the `catch`, including the ones decided before anything is sent: a
  // missing grant throwing at the call site would make `fetch(...).catch(...)` the wrong shape
  const fetch = (url, init) => {
    try {
      return send(url, init)
    } catch (e) {
      return Promise.reject(e)
    }
  }

  // `Response` stays unexported: `common.d.ts` declares it as an interface, so there is no global
  // constructor to promise, and a plugin that could reach one would be reading surface nothing
  // agreed to
  Object.defineProperty(globalThis, 'fetch', { value: fetch, writable: true, configurable: true })
}

(natives, PluginError, timers) => {
  const { setTimeout, clearTimeout } = timers

  const invalid = message => new PluginError('invalid-argument', message)


  // Use a null-prototype header map. `constructor` and `toString` are valid RFC 7230 header names;
  // inherited properties would be mistaken for existing headers. Assigning `__proto__` to a plain
  // object would change its prototype instead of adding a header.
  const headerMap = () => Object.create(null)

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

  const readInit = (init) => {
    if (init === undefined || init === null) return {}
    if (typeof init !== 'object') throw invalid('fetch: the second argument must be an options object')
    return init
  }

  const readTimeout = (init) => {
    const raw = init.timeout
    if (raw === undefined || raw === null) return undefined
    if (typeof raw !== 'number' || !Number.isFinite(raw) || raw <= 0) {
      throw invalid('fetch: timeout must be a positive number of milliseconds')
    }
    return raw
  }

  const readSignal = (init) => {
    const signal = init.signal
    if (signal === undefined || signal === null) return undefined
    if (typeof signal.addEventListener !== 'function' || typeof signal.aborted !== 'boolean') {
      throw invalid('fetch: signal must be an AbortSignal')
    }
    return signal
  }

  const send = (url, raw) => {
    const init = readInit(raw)
    const timeout = readTimeout(init)
    const signal = readSignal(init)
    const body = init.body

    if (signal !== undefined && signal.aborted) {
      return Promise.reject(new PluginError('aborted', 'the request was aborted'))
    }

    // method, headers and redirect are checked natively: this prelude shares its realm with the
    // plugin, which can reassign `RegExp.prototype.test` or `Array.prototype.toJSON` under any check made here
    const started = natives.send(String(url), init.method, init.headers, init.redirect, body)

    return new Promise((resolve, reject) => {
      let settled = false
      let timer

      // The first completion wins. Later responses, errors, or aborts do nothing.
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

  // Reject all failures asynchronously, including grant checks, so callers can always use
  // `fetch(...).catch(...)`.
  const fetch = (url, init) => {
    try {
      return send(url, init)
    } catch (e) {
      return Promise.reject(e)
    }
  }

  // Keep Response private: `dom.d.ts` declares only an interface, not a global constructor.
  Object.defineProperty(globalThis, 'fetch', { value: fetch, writable: true, configurable: true })
}

(natives, PluginError, timers) => {
  const { setTimeout, clearTimeout } = timers



  let getHeaderList
  let freeze

  // names and values are checked natively, so `Headers` and the send path share one set of rules
  class Headers {
    #list = []
    #immutable = false

    static {
      getHeaderList = headers => headers.#list
      freeze = (headers) => {
        headers.#immutable = true
        return headers
      }
    }

    constructor(init) {
      if (init === undefined) return
      if (init === null || (typeof init !== 'object' && typeof init !== 'function')) {
        throw new TypeError('Headers: init must be an object')
      }
      if (#list in init) {
        for (const [name, value] of init.#list) this.#list.push([name, value])
        return
      }
      if (typeof init[Symbol.iterator] === 'function') {
        for (const pair of init) {
          const entry = [...pair]
          if (entry.length !== 2) throw new TypeError('Headers: each pair must be [name, value]')
          this.append(entry[0], entry[1])
        }
        return
      }
      for (const name of Object.keys(init)) {
        const value = init[name]
        if (Array.isArray(value)) {
          for (const one of value) this.append(name, one)
        } else {
          this.append(name, value)
        }
      }
    }

    #checkMutable() {
      if (this.#immutable) throw new TypeError('Headers: these headers are immutable')
    }

    #getValues(name) {
      const values = []
      for (const [key, value] of this.#list) {
        if (key === name) values.push(value)
      }
      return values
    }

    #getSorted() {
      const names = []
      for (const [name] of this.#list) {
        if (!names.includes(name)) names.push(name)
      }
      names.sort()
      const out = []
      for (const name of names) {
        const values = this.#getValues(name)
        if (name === 'set-cookie') {
          for (const value of values) out.push([name, value])
        } else {
          out.push([name, values.join(', ')])
        }
      }
      return out
    }

    append(name, value) {
      const entry = [natives.headerName(name), natives.headerValue(value)]
      this.#checkMutable()
      this.#list.push(entry)
    }

    delete(name) {
      const key = natives.headerName(name)
      this.#checkMutable()
      this.#list = this.#list.filter(([other]) => other !== key)
    }

    get(name) {
      const values = this.#getValues(natives.headerName(name))
      return values.length === 0 ? null : values.join(', ')
    }

    getSetCookie() {
      return this.#getValues('set-cookie')
    }

    has(name) {
      const key = natives.headerName(name)
      return this.#list.some(([other]) => other === key)
    }

    set(name, value) {
      const key = natives.headerName(name)
      const normalized = natives.headerValue(value)
      this.#checkMutable()
      const index = this.#list.findIndex(([other]) => other === key)
      if (index === -1) {
        this.#list.push([key, normalized])
        return
      }
      this.#list[index] = [key, normalized]
      this.#list = this.#list.filter(([other], at) => at <= index || other !== key)
    }

    forEach(callback, thisArg) {
      if (typeof callback !== 'function') throw new TypeError('Headers: forEach needs a function')
      for (const [name, value] of this.#getSorted()) callback.call(thisArg, value, name, this)
    }

    entries() {
      return this.#getSorted().values()
    }

    keys() {
      return this.#getSorted().map(([name]) => name).values()
    }

    values() {
      return this.#getSorted().map(([, value]) => value).values()
    }

    [Symbol.iterator]() {
      return this.entries()
    }
  }
  Object.defineProperty(Headers.prototype, Symbol.toStringTag, { value: 'Headers', configurable: true })

  // the host names each header once, lowercased, with every value it saw
  const createResponseHeaders = (raw) => {
    const headers = new Headers()
    const list = getHeaderList(headers)
    if (raw !== undefined && raw !== null) {
      for (const name of Object.keys(raw)) {
        for (const value of raw[name]) list.push([name, value])
      }
    }
    return freeze(headers)
  }

  const flattenHeaders = (init) => {
    const flat = []
    if (init === undefined || init === null) return flat
    for (const [name, value] of getHeaderList(new Headers(init))) flat.push(name, value)
    return flat
  }

  const bodies = new WeakMap()

  const getResponseBody = (response) => {
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
        headers: { value: createResponseHeaders(raw.headers), enumerable: true },
      })
    }

    blob() {
      return Promise.resolve(getResponseBody(this))
    }

    bytes() {
      return getResponseBody(this).bytes()
    }

    arrayBuffer() {
      return getResponseBody(this).arrayBuffer()
    }

    text() {
      return getResponseBody(this).text()
    }

    json() {
      return getResponseBody(this).text().then(JSON.parse)
    }
  }

  const readInit = (init) => {
    if (init === undefined || init === null) return {}
    if (typeof init !== 'object') throw new PluginError('invalid-argument', 'fetch: the second argument must be an options object')
    return init
  }

  const readTimeout = (init) => {
    const raw = init.timeout
    if (raw === undefined || raw === null) return undefined
    if (typeof raw !== 'number' || !Number.isFinite(raw) || raw <= 0) {
      throw new PluginError('invalid-argument', 'fetch: timeout must be a positive number of milliseconds')
    }
    return raw
  }

  const readSignal = (init) => {
    const signal = init.signal
    if (signal === undefined || signal === null) return undefined
    if (typeof signal.addEventListener !== 'function' || typeof signal.aborted !== 'boolean') {
      throw new PluginError('invalid-argument', 'fetch: signal must be an AbortSignal')
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
    // plugin, which can reassign `RegExp.prototype.test` or `Array.prototype.push` under any check made here
    const started = natives.send(String(url), init.method, flattenHeaders(init.headers), init.redirect, body)

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
  Object.defineProperty(globalThis, 'Headers', { value: Headers, writable: true, configurable: true })
}

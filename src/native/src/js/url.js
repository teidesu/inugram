((native) => {
  // module-private: nothing outside this closure holds them, and `Symbol()` (not `Symbol.for`)
  // keeps them out of the global registry a plugin can enumerate by name
  const BOUND = Symbol('inu.url.bound')
  const PAIRS = Symbol('inu.url.pairs')
  const MEMO = Symbol('inu.url.searchParams')

  const readPairs = (self) =>
    self[BOUND] === undefined ? self[PAIRS] : native.parseQuery(native.getQuery.call(self[BOUND]))

  const writePairs = (self, pairs) => {
    if (self[BOUND] === undefined) self[PAIRS] = pairs
    else native.setQuery.call(self[BOUND], native.serializeQuery(pairs))
  }

  const str = (value) => (typeof value === 'string' ? value : String(value))

  class URLSearchParams {
    constructor(init) {
      this[BOUND] = undefined
      this[PAIRS] = []
      if (init === undefined || init === null) return
      if (typeof init === 'string') {
        this[PAIRS] = native.parseQuery(init)
      } else if (init instanceof URLSearchParams) {
        this[PAIRS] = readPairs(init).map((pair) => [pair[0], pair[1]])
      } else if (typeof init[Symbol.iterator] === 'function') {
        for (const pair of init) {
          const entry = Array.from(pair)
          if (entry.length !== 2) {
            throw new TypeError('URLSearchParams: each entry must be a [name, value] pair')
          }
          this[PAIRS].push([str(entry[0]), str(entry[1])])
        }
      } else {
        for (const key of Object.keys(init)) this[PAIRS].push([key, str(init[key])])
      }
    }

    get size() {
      return readPairs(this).length
    }

    append(name, value) {
      const pairs = readPairs(this)
      pairs.push([str(name), str(value)])
      writePairs(this, pairs)
    }

    /** the two-argument form is the newer spec: it removes only the pairs matching both */
    delete(name, value) {
      const key = str(name)
      const wanted = value === undefined ? undefined : str(value)
      writePairs(
        this,
        readPairs(this).filter((pair) => pair[0] !== key || (wanted !== undefined && pair[1] !== wanted)),
      )
    }

    get(name) {
      const key = str(name)
      const found = readPairs(this).find((pair) => pair[0] === key)
      return found === undefined ? null : found[1]
    }

    getAll(name) {
      const key = str(name)
      return readPairs(this)
        .filter((pair) => pair[0] === key)
        .map((pair) => pair[1])
    }

    has(name, value) {
      const key = str(name)
      const wanted = value === undefined ? undefined : str(value)
      return readPairs(this).some((pair) => pair[0] === key && (wanted === undefined || pair[1] === wanted))
    }

    /** replaces the first match in place and drops the rest, which is what keeps ordering stable */
    set(name, value) {
      const key = str(name)
      const next = str(value)
      const pairs = readPairs(this)
      let seen = false
      const out = []
      for (const pair of pairs) {
        if (pair[0] !== key) {
          out.push(pair)
        } else if (!seen) {
          seen = true
          out.push([key, next])
        }
      }
      if (!seen) out.push([key, next])
      writePairs(this, out)
    }

    /** by name only, and stable, so pairs sharing a name keep the order they were appended in */
    sort() {
      const pairs = readPairs(this)
      const decorated = pairs.map((pair, index) => [pair, index])
      decorated.sort((a, b) => (a[0][0] < b[0][0] ? -1 : a[0][0] > b[0][0] ? 1 : a[1] - b[1]))
      writePairs(this, decorated.map((entry) => entry[0]))
    }

    forEach(callback, thisArg) {
      if (typeof callback !== 'function') throw new TypeError('URLSearchParams.forEach: callback is not a function')
      for (const pair of readPairs(this)) callback.call(thisArg, pair[1], pair[0], this)
    }

    entries() {
      return readPairs(this)
        .map((pair) => [pair[0], pair[1]])
        [Symbol.iterator]()
    }

    keys() {
      return readPairs(this)
        .map((pair) => pair[0])
        [Symbol.iterator]()
    }

    values() {
      return readPairs(this)
        .map((pair) => pair[1])
        [Symbol.iterator]()
    }

    [Symbol.iterator]() {
      return this.entries()
    }

    toString() {
      return native.serializeQuery(readPairs(this))
    }
  }

  // one object per URL, as the spec requires: `url.searchParams === url.searchParams`. It is memoed
  // on the instance rather than rebuilt, or a plugin holding `const p = url.searchParams` would be
  // writing into something the url had stopped listening to.
  Object.defineProperty(URL.prototype, 'searchParams', {
    get() {
      let params = this[MEMO]
      if (params === undefined) {
        params = new URLSearchParams()
        params[BOUND] = this
        Object.defineProperty(this, MEMO, { value: params })
      }
      return params
    },
    enumerable: true,
    configurable: true,
  })

  Object.defineProperty(globalThis, 'URLSearchParams', {
    value: URLSearchParams,
    writable: true,
    configurable: true,
  })
})

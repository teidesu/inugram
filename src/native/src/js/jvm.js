((natives, PluginError) => {
  const ids = new WeakMap()

  // the numbers themselves are rust's (`jvm::OP_*`, which Kotlin's `PluginJvm.OP_*` mirrors), so
  // there is one place to change them rather than one per language
  const OP = natives.ops

  // keep in sync with Kotlin `PluginJvm.KIND_*`
  const KIND_CLASS = 'C'
  const KIND_METHOD = 'M'
  const KIND_FIELD = 'F'

  // a handle whose js side became unreachable is one the host can forget. Nothing else ever
  // removes an entry before unload does, so without this a loop over a list would hold every row
  // it read for as long as the plugin runs.
  const registry = new FinalizationRegistry((id) => natives.release(id))

  const invalid = (message) => new PluginError('invalid-argument', message)

  const idOf = (value) => {
    if (value === null || (typeof value !== 'object' && typeof value !== 'function')) return -1
    const id = ids.get(value)
    return typeof id === 'number' ? id : -1
  }

  const handleOf = (value, what) => {
    const id = idOf(value)
    if (id < 0) throw invalid(`${what}: expected a java class, object, method or field`)
    return id
  }

  const named = (what, value) => {
    if (typeof value !== 'string' || value.length === 0) throw invalid(`${what}: expected a name`)
    return value
  }

  const objectMembers = {
    getField(field) {
      return natives.op(OP.get, handleOf(this, 'getField'), named('getField', field), [])
    },
    setField(field, value) {
      natives.op(OP.set, handleOf(this, 'setField'), named('setField', field), [value])
    },
    call(method, ...args) {
      return natives.op(OP.call, handleOf(this, 'call'), named('call', method), args)
    },
  }

  const classMembers = {
    getDeclaredMethod(method) {
      return natives.op(OP.method, handleOf(this, 'getDeclaredMethod'), named('getDeclaredMethod', method), [])
    },
    getDeclaredConstructor(descriptor) {
      if (typeof descriptor !== 'string' || !/^\([^)]*\)V$/.test(descriptor)) {
        throw invalid('getDeclaredConstructor: expected a JVM constructor descriptor')
      }
      return natives.op(OP.method, handleOf(this, 'getDeclaredConstructor'), `<init>${descriptor}`, [])
    },
    getDeclaredField(field) {
      return natives.op(OP.field, handleOf(this, 'getDeclaredField'), named('getDeclaredField', field), [])
    },
    getStaticField(field) {
      return natives.op(OP.get, handleOf(this, 'getStaticField'), named('getStaticField', field), [])
    },
    setStaticField(field, value) {
      natives.op(OP.set, handleOf(this, 'setStaticField'), named('setStaticField', field), [value])
    },
    callStatic(method, ...args) {
      return natives.op(OP.call, handleOf(this, 'callStatic'), named('callStatic', method), args)
    },
  }

  const methodMembers = {
    invoke(self, ...args) {
      return natives.op(OP.invoke, handleOf(this, 'invoke'), '', [self, ...args])
    },
  }

  const fieldMembers = {
    get(self) {
      return natives.op(OP.memberGet, handleOf(this, 'get'), '', [self])
    },
    set(self, value) {
      natives.op(OP.memberSet, handleOf(this, 'set'), '', [self, value])
    },
  }

  const objectProto = Object.freeze(objectMembers)
  const methodProto = Object.freeze(methodMembers)
  const fieldProto = Object.freeze(fieldMembers)

  const attach = (handle, id) => {
    ids.set(handle, id)
    registry.register(handle, id)
    return handle
  }

  // a class is callable because `common.d.ts` declares `new cls(...)`: a constructor returning an
  // object is what makes `new` answer with the host's handle rather than the empty `this` js built
  const mintClass = (id) => {
    const ctor = function (...args) {
      return natives.op(OP.construct, id, '', args)
    }
    Object.assign(ctor, classMembers)
    return attach(ctor, id)
  }

  const mint = (kind, id) => {
    if (kind === KIND_CLASS) return mintClass(id)
    const proto = kind === KIND_METHOD ? methodProto : kind === KIND_FIELD ? fieldProto : objectProto
    return attach(Object.create(proto), id)
  }

  const jvm = Object.freeze({
    cls(name) {
      return natives.cls(named('cls', name))
    },

    runnable(callback) {
      if (typeof callback !== 'function') throw invalid('runnable: expected a function')
      return natives.runnable(callback)
    },

    loadDex(source) {
      natives.loadDex(source)
    },

    defineClass() {
      throw new PluginError('unsupported', 'jvm.defineClass needs a js-to-dex compiler, which is not implemented')
    },

    callSuper() {
      throw new PluginError(
        'unsupported',
        'jvm.callSuper can only run inside a defineClass method body, and defineClass is not implemented',
      )
    },
  })

  return { jvm, mint, idOf }
})

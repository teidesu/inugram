(natives, PluginError, ops) => {
  const ids = new WeakMap()

  // keep in sync with Kotlin `PluginJvm.KIND_*`
  const KIND_CLASS = 'C'
  const KIND_METHOD = 'M'
  const KIND_FIELD = 'F'

  // a handle whose js side became unreachable is one the host can forget. Nothing else ever
  // removes an entry before unload does, so without this a loop over a list would hold every row
  // it read for as long as the plugin runs.
  const registry = new FinalizationRegistry(id => natives.release(id))

  const invalid = message => new PluginError('invalid-argument', message)

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
      return natives.op(ops.get, handleOf(this, 'getField'), named('getField', field), [])
    },
    setField(field, value) {
      natives.op(ops.set, handleOf(this, 'setField'), named('setField', field), [value])
    },
    call(method, ...args) {
      return natives.op(ops.call, handleOf(this, 'call'), named('call', method), args)
    },
  }

  const classMembers = {
    getDeclaredMethod(method) {
      return natives.op(ops.method, handleOf(this, 'getDeclaredMethod'), named('getDeclaredMethod', method), [])
    },
    getDeclaredConstructor(descriptor) {
      if (typeof descriptor !== 'string' || !/^\([^)]*\)V$/.test(descriptor)) {
        throw invalid('getDeclaredConstructor: expected a JVM constructor descriptor')
      }
      return natives.op(ops.method, handleOf(this, 'getDeclaredConstructor'), `<init>${descriptor}`, [])
    },
    getDeclaredField(field) {
      return natives.op(ops.field, handleOf(this, 'getDeclaredField'), named('getDeclaredField', field), [])
    },
    getStaticField(field) {
      return natives.op(ops.get, handleOf(this, 'getStaticField'), named('getStaticField', field), [])
    },
    setStaticField(field, value) {
      natives.op(ops.set, handleOf(this, 'setStaticField'), named('setStaticField', field), [value])
    },
    callStatic(method, ...args) {
      return natives.op(ops.call, handleOf(this, 'callStatic'), named('callStatic', method), args)
    },
  }

  const methodMembers = {
    invoke(self, ...args) {
      return natives.op(ops.invoke, handleOf(this, 'invoke'), '', [self, ...args])
    },
  }

  const fieldMembers = {
    get(self) {
      return natives.op(ops.memberGet, handleOf(this, 'get'), '', [self])
    },
    set(self, value) {
      natives.op(ops.memberSet, handleOf(this, 'set'), '', [self, value])
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
      return natives.op(ops.construct, id, '', args)
    }
    Object.assign(ctor, classMembers)
    return attach(ctor, id)
  }

  const mint = (kind, id) => {
    if (kind === KIND_CLASS) return mintClass(id)
    const proto = kind === KIND_METHOD ? methodProto : kind === KIND_FIELD ? fieldProto : objectProto
    return attach(Object.create(proto), id)
  }

  const buildRoutine = (build, hookMode = false) => {
    if (typeof build !== 'function') throw invalid('routine: expected a synchronous builder')
    const refs = new WeakMap()
    const nodes = []
    const values = []
    let open = true
    const ref = (value) => {
      const index = refs.get(value)
      if (index === undefined) throw invalid('routine: expected an operation from this builder')
      return index
    }
    const operand = (value) => {
      if (value !== null && (typeof value === 'object' || typeof value === 'function')) {
        const index = refs.get(value)
        if (index !== undefined) return [1, index]
        if (idOf(value) < 0 && !(value instanceof Uint8Array)) throw invalid('routine: invalid or foreign value')
      }
      values.push(value)
      return [0, values.length - 1]
    }
    const node = (kind, args) => {
      if (!open) throw invalid('routine: builder is closed')
      if (nodes.length >= 256) throw invalid('routine: at most 256 operations')
      const token = Object.freeze(Object.create(null))
      refs.set(token, nodes.length)
      nodes.push([kind, ...args()])
      return token
    }
    const builder = {
      getThisObject: () => node('methodThis', () => []),
      getArgument: index => node('methodArgument', () => [operand(index)]),
      setReturnValue: value => node('methodSetResult', () => [operand(value)]),
      get: name => node('getLocal', () => [named('get', name)]),
      set: (name, value) => node('setLocal', () => [named('set', name), operand(value)]),
      getField: (target, name) => node('get', () => [operand(target), named('getField', name)]),
      setField: (target, name, value) => node('set', () => [operand(target), named('setField', name), operand(value)]),
      call: (target, name, ...args) => node('call', () => [operand(target), named('call', name), args.map(operand)]),
      when: (condition, yes, no = []) => node('when', () => [operand(condition), yes.map(ref), no.map(ref)]),
      attempt: (body, fallback = []) => node('attempt', () => [body.map(ref), fallback.map(ref)]),
      compare: (op, left, right) => node('compare', () => {
        if (!['==', '!=', '<', '<=', '>', '>='].includes(op)) throw invalid('routine: unsupported comparison operator')
        return [op, operand(left), operand(right)]
      }),
      and: (left, right) => node('and', () => [operand(left), operand(right)]),
      or: (left, right) => node('or', () => [operand(left), operand(right)]),
      not: value => node('not', () => [operand(value)]),
      math: (op, left, right) => node('math', () => {
        if (!['+', '-', '*', '/', '%'].includes(op)) throw invalid('routine: unsupported math operator')
        return [op, operand(left), operand(right)]
      }),
    }
    if (hookMode) {
      Object.assign(builder, {
        getThisObject: () => node('hookThis', () => []),
        getMethod: () => node('hookMethod', () => []),
        getArgument: index => node('hookArgument', () => [operand(index)]),
        setArgument: (index, value) => node('hookSetArgument', () => [operand(index), operand(value)]),
        getReturnValue: () => node('hookResult', () => []),
        getThrowable: () => node('hookThrowable', () => []),
        setReturnValue: value => node('hookSetResult', () => [operand(value)]),
        setThrowable: value => node('hookSetThrowable', () => [operand(value)]),
      })
    }
    Object.freeze(builder)
    try {
      const roots = build(builder)
      if (!Array.isArray(roots)) throw invalid('routine: builder must return an operation array')
      return natives.op(hookMode ? ops.xposedRoutine : ops.routine, 0, JSON.stringify({ nodes, roots: roots.map(ref) }), values)
    } finally {
      open = false
    }
  }

  const jvm = Object.freeze({
    cls(name) {
      return natives.cls(named('cls', name))
    },

    runnable(callback) {
      if (typeof callback !== 'function') throw invalid('runnable: expected a function')
      return natives.runnable(callback)
    },

    routine: build => buildRoutine(build),

    loadDex(source) {
      natives.loadDex(source)
    },

    defineClass(name, spec) {
      named('defineClass', name)
      if (!spec || typeof spec !== 'object' || Array.isArray(spec)) throw invalid('defineClass: expected a class specification')
      for (const key of Object.keys(spec)) {
        if (!['superclass', 'interfaces', 'fields', 'staticFields', 'methods', 'staticMethods', 'constructors'].includes(key)) throw invalid(`defineClass: unknown option ${key}`)
      }
      const values = []
      const capture = value => { values.push(value); return values.length - 1 }
      const typeList = value => {
        if (value === undefined) return null
        if (!Array.isArray(value) || value.some(type => typeof type !== 'string' || !type)) throw invalid('defineClass: params must be type names')
        return value
      }
      const body = value => {
        if (typeof value === 'function') return ['js', capture(value)]
        if (idOf(value) >= 0) return ['routine', capture(value)]
        throw invalid('defineClass: expected a JS function or JVM routine body')
      }
      const definition = { name, superclass: null, interfaces: [], fields: [], methods: [] }
      if (spec.superclass !== undefined) {
        handleOf(spec.superclass, 'defineClass superclass')
        definition.superclass = capture(spec.superclass)
      }
      if (spec.interfaces !== undefined) {
        if (!Array.isArray(spec.interfaces)) throw invalid('defineClass: interfaces must be an array')
        definition.interfaces = spec.interfaces.map(value => { handleOf(value, 'defineClass interface'); return capture(value) })
      }
      for (const [key, isStatic] of [['fields', false], ['staticFields', true]]) {
        if (spec[key] === undefined) continue
        if (!spec[key] || typeof spec[key] !== 'object' || Array.isArray(spec[key])) throw invalid(`defineClass: invalid ${key}`)
        for (const [field, type] of Object.entries(spec[key])) definition.fields.push([field, named('defineClass field type', type), isStatic])
      }
      for (const [key, isStatic] of [['methods', false], ['staticMethods', true]]) {
        if (spec[key] === undefined) continue
        if (!spec[key] || typeof spec[key] !== 'object' || Array.isArray(spec[key])) throw invalid(`defineClass: invalid ${key}`)
        for (const [method, value] of Object.entries(spec[key])) {
          const item = typeof value === 'function' ? { body: value } : value
          if (!item || typeof item !== 'object' || Array.isArray(item)) throw invalid('defineClass: invalid method specification')
          for (const key of Object.keys(item)) if (!['params', 'returns', 'body'].includes(key)) throw invalid(`defineClass: unknown method option ${key}`)
          definition.methods.push({ name: method, params: typeList(item.params), returns: item.returns === undefined ? null : named('defineClass return type', item.returns), body: body(item.body), static: isStatic, constructor: false })
        }
      }
      const constructors = spec.constructors === undefined ? [{}] : spec.constructors
      if (!Array.isArray(constructors)) throw invalid('defineClass: constructors must be an array')
      for (const item of constructors) {
        if (!item || typeof item !== 'object' || Array.isArray(item)) throw invalid('defineClass: invalid constructor specification')
        for (const key of Object.keys(item)) if (!['params', 'super', 'init'].includes(key)) throw invalid(`defineClass: unknown constructor option ${key}`)
        const args = item.super === undefined ? [] : item.super
        if (!Array.isArray(args)) throw invalid('defineClass: super must be an array')
        const superArgs = args.map(value => {
          if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== 1) throw invalid('defineClass: invalid super argument')
          if (Object.hasOwn(value, 'arg') && Number.isInteger(value.arg) && value.arg >= 0) return { arg: value.arg }
          if (Object.hasOwn(value, 'value') && typeof value.value !== 'function') return { value: capture(value.value) }
          throw invalid('defineClass: expected super arg index or constant value')
        })
        definition.methods.push({ name: '<init>', params: typeList(item.params) ?? [], returns: 'void', body: item.init === undefined ? null : body(item.init), static: false, constructor: true, super: superArgs })
      }
      if (definition.methods.length > 256 || definition.fields.length > 256 || definition.interfaces.length > 64) throw invalid('defineClass: too many members or interfaces')
      return natives.defineClass(JSON.stringify(definition), values)
    },

    callSuper() {
      throw new PluginError(
        'unsupported',
        'jvm.callSuper is not implemented',
      )
    },
  })

  return { jvm, mint, idOf, xposedRoutine: build => buildRoutine(build, true) }
}

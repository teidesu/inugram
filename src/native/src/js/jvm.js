(natives, PluginError, ops) => {
  const invalid = message => new PluginError('invalid-argument', message)

  const named = (what, value) => {
    if (typeof value !== 'string' || value.length === 0) throw invalid(`${what}: expected a name`)
    return value
  }

  // a handle *is* the native `JvmRef` these sit on the prototype of: what it names is read off
  // the object on the far side, so nothing here keeps a table of its own
  const objectMembers = {
    getField(field) {
      return natives.get(this, named('getField', field))
    },
    setField(field, value) {
      natives.set(this, named('setField', field), value)
    },
    call(method, ...args) {
      return natives.call(this, named('call', method), ...args)
    },
  }

  const classMembers = {
    getDeclaredMethod(method) {
      return natives.method(this, named('getDeclaredMethod', method))
    },
    getDeclaredConstructor(descriptor) {
      if (typeof descriptor !== 'string' || !/^\([^)]*\)V$/.test(descriptor)) {
        throw invalid('getDeclaredConstructor: expected a JVM constructor descriptor')
      }
      return natives.method(this, `<init>${descriptor}`)
    },
    getDeclaredField(field) {
      return natives.field(this, named('getDeclaredField', field))
    },
    getStaticField(field) {
      return natives.get(this, named('getStaticField', field))
    },
    setStaticField(field, value) {
      natives.set(this, named('setStaticField', field), value)
    },
    callStatic(method, ...args) {
      return natives.call(this, named('callStatic', method), ...args)
    },
    isInstance(value) {
      return natives.isInstance(this, value)
    },
  }

  const methodMembers = {
    invoke(self, ...args) {
      return natives.invoke(this, self, ...args)
    },
  }

  // a constructor takes no receiver: `invoke` is the wrong shape for one, and java names this
  const constructorMembers = {
    newInstance(...args) {
      return natives.invoke(this, null, ...args)
    },
  }

  const fieldMembers = {
    get(self) {
      return natives.memberGet(this, self)
    },
    set(self, value) {
      natives.memberSet(this, self, value)
    },
  }

  const objectProto = Object.freeze(objectMembers)
  const methodProto = Object.freeze(methodMembers)
  const constructorProto = Object.freeze(constructorMembers)
  const fieldProto = Object.freeze(fieldMembers)

  // a class is callable because `common.d.ts` declares `new cls(...)`: a constructor returning an
  // object is what makes `new` answer with the host's handle rather than the empty `this` js built.
  // The native ref rides on the function, where the far side reads it back
  const mintClass = (ref) => {
    const ctor = function (...args) {
      return natives.construct(ref, ...args)
    }
    Object.assign(ctor, classMembers)
    Object.defineProperty(ctor, natives.hiddenRef, { value: ref })
    return ctor
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
        if (!natives.isRef(value) && !(value instanceof Uint8Array)) throw invalid('routine: invalid or foreign value')
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

    fromTl(value) {
      if (value === null || typeof value !== 'object') throw invalid('fromTl: expected a TL object')
      return natives.fromTl(value)
    },
    toTl(handle) {
      return natives.toTl(handle)
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
      const capture = (value) => { values.push(value); return values.length - 1 }
      const typeList = (value) => {
        if (value === undefined) return null
        if (!Array.isArray(value) || value.some(type => typeof type !== 'string' || !type)) throw invalid('defineClass: params must be type names')
        return value
      }

      const body = (value) => {
        if (typeof value === 'function') return ['js', capture(value)]
        if (natives.isRef(value)) return ['routine', capture(value)]
        throw invalid('defineClass: expected a JS function or JVM routine body')
      }
      const definition = { name, superclass: null, interfaces: [], fields: [], methods: [] }
      const handle = (value, what) => {
        if (!natives.isRef(value)) throw invalid(`${what}: expected a java class, object, method or field`)
        return value
      }
      if (spec.superclass !== undefined) {
        definition.superclass = capture(handle(spec.superclass, 'defineClass superclass'))
      }
      if (spec.interfaces !== undefined) {
        if (!Array.isArray(spec.interfaces)) throw invalid('defineClass: interfaces must be an array')
        definition.interfaces = spec.interfaces.map(value => capture(handle(value, 'defineClass interface')))
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
          for (const key of Object.keys(item)) {
            if (!['params', 'returns', 'body'].includes(key)) throw invalid(`defineClass: unknown method option ${key}`)
          }
          definition.methods.push({
            name: method,
            params: typeList(item.params),
            returns: item.returns === undefined ? null : named('defineClass return type', item.returns),
            body: body(item.body),
            static: isStatic,
            constructor: false,
          })
        }
      }

      const constructors = spec.constructors === undefined ? [{}] : spec.constructors
      if (!Array.isArray(constructors)) throw invalid('defineClass: constructors must be an array')
      for (const item of constructors) {
        if (!item || typeof item !== 'object' || Array.isArray(item)) throw invalid('defineClass: invalid constructor specification')
        for (const key of Object.keys(item)) {
          if (!['params', 'super', 'init'].includes(key)) throw invalid(`defineClass: unknown constructor option ${key}`)
        }
        const args = item.super === undefined ? [] : item.super
        if (!Array.isArray(args)) throw invalid('defineClass: super must be an array')
        const superArgs = args.map((value) => {
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

  return {
    jvm,
    mintClass,
    protos: { object: objectProto, method: methodProto, constructor: constructorProto, field: fieldProto },
    xposedRoutine: build => buildRoutine(build, true),
  }
}

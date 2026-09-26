(natives, PluginError, ops) => {

  const named = (what, value) => {
    if (typeof value !== 'string' || value.length === 0) throw new PluginError('invalid-argument', `${what}: expected a name`)
    return value
  }

  // These methods are on native `JvmRef` prototypes. Native reads the reference from the object
  // directly; JS keeps no separate table.
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
        throw new PluginError('invalid-argument', 'getDeclaredConstructor: expected a JVM constructor descriptor')
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

  const MAX_FLAT_CAPTURES = 4096

  /**
   * A capture crosses as one value wire, so an array capture is flattened into the same list and
   * the shape it had is sent alongside: `-1` for a plain value, a nested array for an array.
   */
  const flattenCapture = (value, flat, what) => {
    if (Array.isArray(value)) return value.map(item => flattenCapture(item, flat, what))
    if (flat.length >= MAX_FLAT_CAPTURES) throw new PluginError('invalid-argument', 'routine: too many captured values')
    const type = typeof value
    const scalar = value === null || value === undefined
      || type === 'boolean' || type === 'number' || type === 'bigint' || type === 'string'
    if (!scalar && !natives.isRef(value) && !(value instanceof Uint8Array)) {
      throw new PluginError('invalid-argument', `routine: ${what} must be a scalar, bytes, a java handle, or an array of those`)
    }
    flat.push(value)
    return -1
  }

  const buildRoutine = (program, captures, hookMode = false) => {
    if (typeof program === 'function') {
      throw new PluginError('invalid-argument', 'routine: a routine body is compiled by @inugram/cli, so build the plugin with it')
    }
    if (!program || typeof program !== 'object' || Array.isArray(program)) {
      throw new PluginError('invalid-argument', 'routine: expected a compiled routine')
    }
    if (program.v !== 1) throw new PluginError('invalid-argument', 'routine: unsupported routine version')
    if (!Array.isArray(program.code)) throw new PluginError('invalid-argument', 'routine: expected an instruction list')
    const names = Array.isArray(program.captures) ? program.captures : []
    const given = captures === undefined ? [] : captures
    if (!Array.isArray(given)) throw new PluginError('invalid-argument', 'routine: expected an array of captured values')
    if (given.length !== names.length) throw new PluginError('invalid-argument', 'routine: the captured values do not match the routine')

    const flat = []
    const layout = given.map((value, index) => flattenCapture(value, flat, `capture '${names[index]}'`))
    const definition = {
      v: 1,
      slots: program.slots === undefined ? 0 : program.slots,
      code: program.code,
      tries: program.tries === undefined ? [] : program.tries,
      layout,
    }
    return natives.op(hookMode ? ops.xposedRoutine : ops.routine, 0, JSON.stringify(definition), flat)
  }

  const jvm = Object.freeze({
    cls(name) {
      return natives.cls(named('cls', name))
    },

    fromTl(value) {
      if (value === null || typeof value !== 'object') throw new PluginError('invalid-argument', 'fromTl: expected a TL object')
      return natives.fromTl(value)
    },
    toTl(handle) {
      return natives.toTl(handle)
    },

    runnable(callback) {
      if (typeof callback !== 'function') throw new PluginError('invalid-argument', 'runnable: expected a function')
      return natives.runnable(callback)
    },

    routine: (program, captures) => buildRoutine(program, captures),

    loadDex(source) {
      natives.loadDex(source)
    },

    defineClass(name, spec) {
      if (spec === undefined && name !== null && typeof name === 'object' && !Array.isArray(name)) {
        spec = name
        name = null
      } else {
        named('defineClass', name)
      }
      if (!spec || typeof spec !== 'object' || Array.isArray(spec)) throw new PluginError('invalid-argument', 'defineClass: expected a class specification')
      for (const key of Object.keys(spec)) {
        if (!['superclass', 'interfaces', 'fields', 'staticFields', 'methods', 'staticMethods', 'constructors'].includes(key)) throw new PluginError('invalid-argument', `defineClass: unknown option ${key}`)
      }

      const values = []
      const capture = (value) => { values.push(value); return values.length - 1 }
      const typeList = (value) => {
        if (value === undefined) return null
        if (!Array.isArray(value) || value.some(type => typeof type !== 'string' || !type)) throw new PluginError('invalid-argument', 'defineClass: params must be type names')
        return value
      }

      const body = (value) => {
        if (typeof value === 'function') return ['js', capture(value)]
        if (natives.isRef(value)) return ['routine', capture(value)]
        throw new PluginError('invalid-argument', 'defineClass: expected a JS function or JVM routine body')
      }
      const definition = { name, superclass: null, interfaces: [], fields: [], methods: [] }
      const handle = (value, what) => {
        if (!natives.isRef(value)) throw new PluginError('invalid-argument', `${what}: expected a java class, object, method or field`)
        return value
      }
      if (spec.superclass !== undefined) {
        definition.superclass = capture(handle(spec.superclass, 'defineClass superclass'))
      }
      if (spec.interfaces !== undefined) {
        if (!Array.isArray(spec.interfaces)) throw new PluginError('invalid-argument', 'defineClass: interfaces must be an array')
        definition.interfaces = spec.interfaces.map(value => capture(handle(value, 'defineClass interface')))
      }

      for (const [key, isStatic] of [['fields', false], ['staticFields', true]]) {
        if (spec[key] === undefined) continue
        if (!spec[key] || typeof spec[key] !== 'object' || Array.isArray(spec[key])) throw new PluginError('invalid-argument', `defineClass: invalid ${key}`)
        for (const [field, type] of Object.entries(spec[key])) definition.fields.push([field, named('defineClass field type', type), isStatic])
      }

      for (const [key, isStatic] of [['methods', false], ['staticMethods', true]]) {
        if (spec[key] === undefined) continue
        if (!spec[key] || typeof spec[key] !== 'object' || Array.isArray(spec[key])) throw new PluginError('invalid-argument', `defineClass: invalid ${key}`)
        for (const [method, value] of Object.entries(spec[key])) {
          const items = Array.isArray(value) ? value : [typeof value === 'function' ? { body: value } : value]
          for (const item of items) {
            if (!item || typeof item !== 'object' || Array.isArray(item)) throw new PluginError('invalid-argument', 'defineClass: invalid method specification')
            for (const key of Object.keys(item)) {
              if (!['params', 'returns', 'body'].includes(key)) throw new PluginError('invalid-argument', `defineClass: unknown method option ${key}`)
            }
            if (Array.isArray(value) && item.params === undefined) throw new PluginError('invalid-argument', `defineClass: overloads of ${method} need explicit params`)
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
      }

      const constructors = spec.constructors === undefined ? [{}] : spec.constructors
      if (!Array.isArray(constructors)) throw new PluginError('invalid-argument', 'defineClass: constructors must be an array')
      for (const item of constructors) {
        if (!item || typeof item !== 'object' || Array.isArray(item)) throw new PluginError('invalid-argument', 'defineClass: invalid constructor specification')
        for (const key of Object.keys(item)) {
          if (!['params', 'super', 'superParams', 'init'].includes(key)) throw new PluginError('invalid-argument', `defineClass: unknown constructor option ${key}`)
        }
        const computed = typeof item.super === 'function' || natives.isRef(item.super)
        if (!computed && item.superParams !== undefined) throw new PluginError('invalid-argument', 'defineClass: superParams go with a super function')
        const args = item.super === undefined || computed ? [] : item.super
        if (!Array.isArray(args)) throw new PluginError('invalid-argument', 'defineClass: super must be an array, a function or a JVM routine')
        const superArgs = args.map((value) => {
          if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== 1) throw new PluginError('invalid-argument', 'defineClass: invalid super argument')
          if (Object.hasOwn(value, 'arg') && Number.isInteger(value.arg) && value.arg >= 0) return { arg: value.arg }
          if (Object.hasOwn(value, 'value') && typeof value.value !== 'function') return { value: capture(value.value) }
          throw new PluginError('invalid-argument', 'defineClass: expected super arg index or constant value')
        })
        const superFunction = item.super
        // a constructor's own `this` does not exist yet, so the super function takes the arguments alone
        const superBody = !computed ? null : body(typeof superFunction === 'function' ? (_, ...params) => superFunction(...params) : superFunction)
        definition.methods.push({
          name: '<init>',
          params: typeList(item.params) ?? [],
          returns: 'void',
          body: item.init === undefined ? null : body(item.init),
          static: false,
          constructor: true,
          super: superArgs,
          superBody,
          superParams: computed ? typeList(item.superParams) : null,
        })
      }

      const [type, fqn] = natives.defineClass(JSON.stringify(definition), values)
      Object.defineProperty(type, 'name', { value: fqn, configurable: true })
      return type
    },

    getSuper() {
      throw new PluginError('invalid-argument', 'inu.jvm.getSuper only works inside an inu.jvm.routine body, as inu.jvm.getSuper(this).method(...)')
    },

    callSuper(cls, self, method, ...args) {
      return natives.callSuper(cls, self, named('callSuper', method), ...args)
    },
  })

  return {
    jvm,
    mintClass,
    protos: { object: objectProto, method: methodProto, constructor: constructorProto, field: fieldProto },
    xposedRoutine: (program, captures) => buildRoutine(program, captures, true),
  }
}

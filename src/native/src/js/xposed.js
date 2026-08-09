((natives, PluginError) => {
  const OP = natives.ops

  const invalid = (message) => new PluginError('invalid-argument', message)

  const hookOf = (hook, what) => {
    if (hook === null || typeof hook !== 'object') throw invalid(`${what}: expected a hook object`)
    for (const phase of ['before', 'after']) {
      const callback = hook[phase]
      if (callback !== undefined && typeof callback !== 'function') {
        throw invalid(`${what}: ${phase} must be a function`)
      }
    }
    return hook
  }

  const named = (what, value) => {
    if (typeof value !== 'string' || value.length === 0) throw invalid(`${what}: expected a method name`)
    return value
  }

  return Object.freeze({
    hookMethod(method, hook) {
      return natives.hook(OP.hook, method, '', hookOf(hook, 'hookMethod'))
    },

    hookAllOverloads(cls, name, hook) {
      return natives.hook(
        OP.hookAll,
        cls,
        named('hookAllOverloads', name),
        hookOf(hook, 'hookAllOverloads'),
      )
    },

    hookAllConstructors(cls, hook) {
      // the empty name is what makes it the constructors rather than a method: a java method can
      // never be called `<init>` through reflection, so the two cannot collide
      return natives.hook(OP.hookAll, cls, '', hookOf(hook, 'hookAllConstructors'))
    },

    callOriginalMethod(method, thisObject, args) {
      if (args !== undefined && args !== null && !Array.isArray(args)) {
        throw invalid('callOriginalMethod: expected an array of arguments')
      }
      return natives.callOriginal(method, thisObject ?? null, args ?? [])
    },
  })
})

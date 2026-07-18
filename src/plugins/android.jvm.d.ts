declare type JavaObject = OpaqueType<'JVMObject'>
declare type JavaMethod = OpaqueType<'JVMMethod'> & {
  invoke: (obj: JavaObject, ...args: any[]) => any
}
declare type JavaField = OpaqueType<'JVMField'> & {
  get: (obj: JavaObject) => any
}
declare type JavaClass = OpaqueType<'JVMClass'> & {
  new (...args: any[]): JavaObject
  getDeclaredMethod: (name: string) => JavaMethod
  getDeclaredField: (name: string) => JavaField
}

/**
 * a cold, js-backed method body. called via a js jump on every invocation, so
 * `self` and args are live js handles and you can do anything js can. fine for
 * rarely-called overrides (click handlers, factories, comparators, lifecycle
 * callbacks). NOT for draw/measure/layout — see `hot` for those.
 */
declare type JvmColdMethod = (self: JavaObject, ...args: any[]) => any

declare interface JvmColdMethodSpec {
  /** required only to disambiguate overloads; otherwise inferred from super. */
  params?: string[]
  returns?: string
  body: JvmColdMethod
}

declare interface JvmHotMethodSpec {
  // required for hot: the source is compiled ahead of time, so the codegen needs
  // the exact signature (no runtime type inference like the cold path has).
  params: string[]
  returns: string
  /**
   * NOT executed as js. the engine extracts this function's *source* and compiles
   * it straight to a dex method body — zero js crossing at call time, native speed.
   *
   * only a restricted subset is allowed, and it's checked at defineClass time:
   * - params + `self` field access only. NO closures over the js heap — anything
   *   the body reads/writes must be a declared `field` (that's why fields are typed).
   * - calls are limited to a whitelist == the plugin's grants (so the fast path
   *   can't smuggle capabilities the cold path couldn't reach).
   * - no allocation beyond whitelisted ctors, no console, no async.
   *
   * because it's plain source in the js file, it stays auditable — the engine is
   * the only thing that ever produces dex; plugins never ship bytecode.
   */
  body: (self: JavaObject, ...args: any[]) => any
}

declare interface JvmConstructorSpec {
  params?: string[]
  /**
   * how to build the super() args. super must be invoked inside the dex ctor
   * before the object exists, so this is declarative, not arbitrary js: each entry
   * forwards one of this ctor's params ({ arg: n }) or passes a literal ({ value }).
   */
  super?: ({ arg: number } | { value: any })[]
  /** optional post-super init. cold (js), runs after super() returns. */
  init?: (self: JavaObject, ...args: any[]) => void
}

declare interface JvmClassSpec {
  /** defaults to java/lang/Object. */
  superclass?: JavaClass
  interfaces?: JavaClass[]

  /**
   * real dex instance fields. hot methods can't see the js heap, so any state a
   * hot method touches must live here. typed so codegen knows the descriptors.
   */
  fields?: Record<string, string>
  staticFields?: Record<string, string>

  /**
   * cold, js-backed overrides. key is the method name, or a name+descriptor
   * ('compare(Ljava/lang/Object;Ljava/lang/Object;)I') to pin an overload.
   * a bare function is shorthand for { body }.
   */
  methods?: Record<string, JvmColdMethod | JvmColdMethodSpec>
  staticMethods?: Record<string, JvmColdMethod | JvmColdMethodSpec>

  /**
   * hot overrides compiled to native dex bodies. opt-in per method — everything
   * else stays real js in `methods`. use ONLY where a js jump would tank perf
   * (things called inside onDraw/onMeasure/text layout).
   */
  hot?: Record<string, JvmHotMethodSpec>

  constructors?: JvmConstructorSpec[]
}

declare namespace inu {
  /** android-specific apis to access java classes */
  namespace jvm {
    /** create a Runnable from a callback */
    function runnable(callback: () => void): JavaObject
    /**
     * get a java class by its FQN
     * @needs-grant inu.jvm.cls
     */
    function cls(name: string): JavaClass

    /**
     * load a dex file
     * @needs-grant inu.jvm.loadDex
     */
    function loadDex(path: string | Uint8Array): void

    /**
     * define a new class at runtime, extending `spec.superclass` and/or
     * implementing `spec.interfaces`. the engine generates and loads the dex
     * itself from the spec — plugins never ship bytecode, so the class stays
     * auditable from its js source. returns the class, ready to `new`.
     *
     * `name` is the binary name of the class, slash form ('my/plugin/GradientSpan').
     *
     * cost model: defineClass itself (dex gen + link) is a one-off, cache the
     * returned class. per-call cost then depends on the method — `methods` pay a
     * js jump, `hot` methods run as native dex.
     *
     * `hot` methods require a js-to-dalvik compiler, which is not implemented yet.
     *
     * @needs-grant inu.jvm.defineClass
     */
    function defineClass(name: string, spec: JvmClassSpec): JavaClass

    /**
     * call the superclass implementation of an overridden method from inside a
     * cold method body. mirrors xposed.callOriginalMethod. `method` is a name or
     * name+descriptor.
     */
    function callSuper(self: JavaObject, method: string, ...args: any[]): any
  }
}

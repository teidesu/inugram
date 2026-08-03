declare type JavaObject = OpaqueType<'JVMObject'> & {
  /**
   * shorthands for the `getDeclaredField(name).get(obj)` dance, walking up the superclass chain
   * and handling `setAccessible` for you. reaching into private state is most of what reflection
   * gets used for, so it shouldn't cost three calls and a temporary.
   */
  getField: (name: string) => any
  setField: (name: string, value: any) => void
  /** same idea for methods; pass a name+descriptor to pin an overload */
  call: (method: string, ...args: any[]) => any
}
declare type JavaMethod = OpaqueType<'JVMMethod'> & {
  /** `null` for static methods */
  invoke: (obj: JavaObject | null, ...args: any[]) => any
}
declare type JavaField = OpaqueType<'JVMField'> & {
  /** `null` for static fields */
  get: (obj: JavaObject | null) => any
  set: (obj: JavaObject | null, value: any) => void
}
declare type JavaClass = OpaqueType<'JVMClass'> & {
  new (...args: any[]): JavaObject
  getDeclaredMethod: (name: string) => JavaMethod
  getDeclaredField: (name: string) => JavaField
  /** static counterparts of `JavaObject`'s shorthands */
  getStaticField: (name: string) => any
  setStaticField: (name: string, value: any) => void
  callStatic: (method: string, ...args: any[]) => any
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

// -- hot methods: designed, deliberately not shipped --
//
// a `hot` body would NOT be executed as js: the engine would extract the function's *source* and
// compile it straight to a dex method body — zero js crossing at call time, native speed — with a
// restricted subset checked at defineClass time:
// - params + `self` field access only. NO closures over the js heap — anything the body reads or
//   writes must be a declared `field` (that's why fields are typed).
// - calls limited to a whitelist == the plugin's grants, so the fast path can't smuggle
//   capabilities the cold path couldn't reach.
// - no allocation beyond whitelisted ctors, no console, no async.
// because it's plain source in the js file it stays auditable — the engine is the only thing that
// ever produces dex, plugins never ship bytecode.
//
// it's out of the public surface until something provably needs it: cold methods carry every real
// case so far (listeners, factories, comparators, spans), and this is a whole js->dalvik compiler
// to save a jni jump. keeping the spec here so the eventual implementation has a target.
//
// declare interface JvmHotMethodSpec {
//   // required for hot: the source is compiled ahead of time, so the codegen needs the exact
//   // signature (no runtime type inference like the cold path has).
//   params: string[]
//   returns: string
//   body: (self: JavaObject, ...args: any[]) => any
// }

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

  // hot?: Record<string, JvmHotMethodSpec>  // see the note above

  constructors?: JvmConstructorSpec[]
}

declare namespace inu {
  /**
   * android-specific apis to access java classes.
   *
   * **this is a game-over grant, and it is not offered with a scope list.** an earlier design let
   * you write `inu.jvm.cls(java.util.*)` to narrow it, which read like a capability and wasn't one:
   * the scope can only gate the *entry point*, and once any `JavaObject` is in hand `getField`
   * walks the whole heap — one hop from a reachable context to anything at all. rather than ship a
   * boundary that doesn't hold, `inu.jvm` is a single all-or-nothing permission, presented to the
   * user as such.
   *
   * that applies within the namespace too: `loadDex` and `defineClass` used to carry sub-grants of
   * their own, which were locks on an open door — `cls('dalvik.system.InMemoryDexClassLoader')`
   * loads dex and `java.lang.reflect.Proxy` defines classes, both reachable from bare `unsafe.jvm`.
   *
   * it is also the reason the api filtering (see the header in `common.d.ts`) is documented as a
   * property of the *other* apis and not of the app: reflection reads the message cache and the
   * connection layer directly, so a plugin holding this grant sees login codes and can invoke
   * `auth.*` without going anywhere near `invokeRpc`. that is not a gap to be plugged — it's the
   * same "game over" restated, and it's why `unsafe.disableApiFiltering` exists as an honest
   * separate grant rather than as a thing you'd reach this way anyway.
   *
   * @needs-grant unsafe.jvm
   */
  namespace jvm {
    /** create a Runnable from a callback */
    function runnable(callback: () => void): JavaObject
    /** get a java class by its FQN */
    function cls(name: string): JavaClass

    /** load a dex file */
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
     * @not-implemented
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

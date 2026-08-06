declare type JavaObject = OpaqueType<'JVMObject'> & {
  /**
   * shorthands for the `getDeclaredField(name).get(obj)` dance, walking up the superclass chain
   * and handling `setAccessible` for you. reaching into private state is most of what reflection
   * gets used for, so it shouldn't cost three calls and a temporary.
   *
   * throws `not-granted` if the field's *declaring* class is outside your scope list, and
   * `quota-exceeded` if its value is over the size bound. assigning a `final` field is `forbidden`.
   */
  getField: (name: string) => any
  setField: (name: string, value: any) => void
  /**
   * same idea for methods; pass a name+descriptor to pin an overload
   * ('width(J)Ljava/lang/String;').
   *
   * when several overloads accept the arguments, the narrowest numeric parameter wins and a more
   * derived reference type beats a less derived one. anything still tied is `invalid-argument`
   * rather than a guess: pin it with a descriptor. a java exception thrown by the method arrives
   * as a plain `Error` naming it, never as a `PluginError`.
   */
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
  /**
   * nothing is being called yet, so an overloaded name is `invalid-argument`: pass a
   * name+descriptor to say which one you meant.
   */
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
   * **this is the grant that hands over the app, and the scope list is the only boundary there
   * is.** `unsafe.jvm(java.util.*, android.widget.TextView)` narrows it to those classes: a scope
   * is a java binary name or a namespace ending in `.*` (`*` alone is everything), matched against
   * the class name and nothing else. the check is not just on `cls`, it runs again on the runtime
   * class of every reference handed back to you and on the declaring class of every member you
   * reach, so a scope you did not name cannot be reached by walking there from one you did.
   * `unsafe.jvm` with no list is every class, and reads that way in the permission sheet.
   *
   * **what the list buys is reach, not safety.** naming `java.lang.reflect.*`, `java.lang.Class`,
   * `java.lang.ClassLoader`, `java.lang.invoke.*` or `dalvik.system.*` hands back this gate's own
   * tools, and a scope list containing one of those is the same statement as the bare grant. so is
   * `loadDex`, which is why it needs the bare grant explicitly (below). a narrow list is a real
   * limit on an *honest* plugin and a speed bump for a dishonest one; the tier does not change.
   *
   * it is also why api filtering (see the header in `common.d.ts`) is documented as a property of
   * the *other* apis and not of the app: a plugin scoped to the right classes reads the message
   * cache and the connection layer directly, so it sees login codes and can invoke `auth.*` without
   * going anywhere near `invokeRpc`. that is not a gap to be plugged, it is the same "game over"
   * restated, and it is why `unsafe.disableApiFiltering` exists as an honest separate grant rather
   * than as a thing you would reach this way anyway.
   *
   * **what crosses.** `null`, booleans, numbers, strings and `Uint8Array` go over as themselves; a
   * java `char` arrives as a one-character string and goes back into a `char` from one. a `long`
   * too wide for a js number arrives as a `bigint`, and a `bigint` you pass is refused with
   * `invalid-argument` if it does not fit a java `long` rather than being truncated. a js number
   * is an integer or a double and nothing narrower, so the *parameter* decides what it becomes, and
   * one that does not fit exactly is `invalid-argument`; where the parameter is `Object`-shaped it
   * becomes the box a java literal would have been (`Integer` when it fits, else `Long`). anything
   * else (a plain object, an array, a function, a symbol) is `invalid-argument`, since guessing
   * what java type was meant is how a bridge silently does the wrong thing. one value may weigh
   * 1048576 bytes in either direction; past that it is `quota-exceeded`, because a string or a
   * `byte[]` costs several times its size on the *app's* heap, which no plugin ceiling covers.
   *
   * **handles.** a class, object, method or field you are handed is a handle into a table that is
   * this plugin's alone, and it carries nothing you can read off it or forge. one whose js side
   * becomes unreachable is released to the app; the rest go when the plugin unloads, so nothing it
   * held keeps an app object alive after it stops. using any of them afterwards is `handle-expired`.
   *
   * **the plugin engine's own package is `forbidden` whatever the scope list says.** reflection
   * that reaches `QuickJs`, `Plugin` or `PluginManager` re-enters the interpreter from inside its
   * own call, which is not an error but a crash. that is a guard against getting there in one hop,
   * not a boundary: `java.lang.reflect` walks around it, and nothing in this tier could stop that.
   *
   * @needs-grant unsafe.jvm
   */
  namespace jvm {
    /**
     * create a Runnable from a callback, to hand to something in the app that takes one.
     *
     * **it never runs inside the call you handed it to.** whatever java does with it, your callback
     * is queued on the engine's own thread and runs once that call has returned, so it cannot
     * observe the app mid-operation, and a `Runnable` java holds after you unload fires nothing.
     * for the same reason the object it returns is the one thing here you cannot reflect into:
     * every member of it is `forbidden`. call your own function directly instead.
     */
    function runnable(callback: () => void): JavaObject
    /**
     * get a java class by its FQN. `not-granted` if it is outside your scope list, asked before
     * the lookup, so a class you may not name reads the same whether or not it exists, and
     * `not-found` if nothing defines it.
     */
    function cls(name: string): JavaClass

    /**
     * load a dex file, by absolute path or as bytes we stage for you into a private directory that
     * is wiped when the plugin is uninstalled.
     *
     * **this needs `unsafe.jvm` with no scope list** (or a literal `unsafe.jvm(*)`), whatever else
     * the list says: the code it loads runs with the app's own permissions and never crosses this
     * bridge again, so no scope list describes anything once you can load one. classes it defines
     * are then visible to `cls`, ahead of the app's own.
     *
     * nothing here validates that the file *is* a dex: the platform's verifier owns that and
     * reports by failing to define a class, so a bad file is a `not-found` from a later `cls`
     * rather than an error here. what is checked before anything is loaded: the path is absolute
     * (`invalid-argument` otherwise), the file exists and is readable (`not-found`), it is not
     * empty (`invalid-argument`), and it is at most 8388608 bytes of dex (`quota-exceeded`).
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
     * the whole member throws `unsupported`, and there are two separate reasons rather than one.
     * `hot` methods want a js-to-dalvik compiler. the *cold* path wants a dex generator, and then
     * one thing this engine does not have: a way to answer java synchronously. a `Runnable` works
     * because it returns nothing, so `inu.jvm.runnable` posts its callback to the queue an engine
     * may be entered from; a method that returns a value cannot post, and entering the engine from
     * whichever thread java called on is the re-entrancy that aborts the process. so this is not a
     * missing feature behind a generator - it is a member whose contract this engine cannot keep.
     * implement listeners with `inu.jvm.runnable`, or with the api that owns the callback.
     *
     * @not-implemented
     */
    function defineClass(name: string, spec: JvmClassSpec): JavaClass

    /**
     * call the superclass implementation of an overridden method from inside a
     * cold method body. mirrors xposed.callOriginalMethod. `method` is a name or
     * name+descriptor.
     *
     * only means anything inside a body `defineClass` produced, so it goes wherever that goes. even
     * with one, there is no way to reach a superclass implementation non-virtually from outside its
     * own class: `Method.invoke` dispatches virtually, so an approximation of this would call the
     * override and recurse. it throws `unsupported` rather than doing that.
     *
     * @not-implemented
     */
    function callSuper(self: JavaObject, method: string, ...args: any[]): any
  }
}

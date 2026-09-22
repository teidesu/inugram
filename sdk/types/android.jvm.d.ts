/**
 * Reference to a real Java object
 */
declare type JavaObject = OpaqueType<'JVMObject'> & {
  /** Get a field value by its name */
  getField: (name: string) => any
  /** Set a field value */
  setField: (name: string, value: any) => void
  /** Call a member method of the object's class */
  call: (method: string, ...args: any[]) => any
}

/** Reference to a declared Java method */
declare type JavaMethod = OpaqueType<'JVMMethod'> & {
  /** Call the method on a specified object */
  invoke: (obj: JavaObject | null, ...args: any[]) => any
}

/** Reference to a declared Java constructor */
declare type JavaConstructor = OpaqueType<'JVMConstructor'> & {
  /** Call the constructor to create a new instance of the type */
  newInstance: (...args: any[]) => JavaObject
}

/** Reference to a Java field definition */
declare type JavaField = OpaqueType<'JVMField'> & {
  /** Get the field value on a specified object */
  get: (obj: JavaObject | null) => any
  /** Set the field value on a specified object */
  set: (obj: JavaObject | null, value: any) => void
}

/** Reference to a Java class definition */
declare type JavaClass = OpaqueType<'JVMClass'> & {
  /**
   * Create a new instance of the class
   *
   * A specific constructor is matched by argument count/types, but you can specify the one to use via {@link getDeclaredConstructor}
   */
  new (...args: any[]): JavaObject

  /** Get a method declaration */
  getDeclaredMethod: (name: string) => JavaMethod
  /**
   * Get a specific constructor by its ART descriptor
   *
   * @example `cls.getDeclaredConstructor('(Ljava/lang/Object;)V').newInstance(obj)`
   */
  getDeclaredConstructor: (descriptor: string) => JavaConstructor
  /** Get a member field declaration */
  getDeclaredField: (name: string) => JavaField

  /** Get a static field value */
  getStaticField: (name: string) => any
  /** Set a static field value */
  setStaticField: (name: string, value: any) => void
  /** Invoke a static method of the class */
  callStatic: (method: string, ...args: any[]) => any

  /**
   * Check whether `value` is an instance of this class.
   * @returns `true` if the value is an instance
   */
  isInstance: (value: unknown) => boolean
}

/** The class returned by {@link inu.jvm.defineClass}, including its generated or supplied name. */
declare type DefinedClass = JavaClass & {
  readonly name: string
}

/** Body of a "cold" (i.e. ran in JS) method */
declare type JvmMethodImpl = (self: JavaObject, ...args: any[]) => any
/** Body of a "cold" (i.e. ran in JS) static method */
declare type JvmStaticMethodImpl = (self: JavaClass, ...args: any[]) => any

/** Definition of a member method for {@link inu.jvm.defineClass} */
declare interface JvmMethodSpec {
  /** Params of the method, like you would write them in Java (e.g. `float`, `int[]`) */
  params?: string[]
  /** Return value of the method, like you would write it in Java (e.g. `float`, `int[]`) */
  returns?: string
  /** Body of the method */
  body: JvmMethodImpl | JvmRoutineRunnable
}

/** Definition of a static method for {@link inu.jvm.defineClass} */
declare interface JvmStaticMethodSpec {
  /** Params of the method, like you would write them in Java (e.g. `float`, `int[]`) */
  params?: string[]
  /** Return value of the method, like you would write it in Java (e.g. `float`, `int[]`) */
  returns?: string
  body: JvmStaticMethodImpl | JvmRoutineRunnable
}

/** Definition of a constructor for {@link inu.jvm.defineClass} */
declare interface JvmConstructorSpec {
  /** Params of the method, like you would write them in Java (e.g. `float`, `int[]`) */
  params?: string[]
  /** How `super` should be called */
  super?: ({ arg: number } | { value: any })[]
  /** Init method for the defined class */
  init?: ((self: JavaObject, ...args: any[]) => void) | JvmRoutineRunnable
}

/** Definition of a class for {@link inu.jvm.defineClass} */
declare interface JvmClassSpec {
  /** Super class of the newly created class */
  superclass?: JavaClass
  /** Interfaces the class implements */
  interfaces?: JavaClass[]
  /** Member fields and their types, like you would write them in Java (e.g. `float`, `int[]`) */
  fields?: Record<string, string>
  /** Static fields and their types, like you would write them in Java (e.g. `float`, `int[]`) */
  staticFields?: Record<string, string>
  /** Member methods for the class, keyed by their name */
  methods?: Record<string, JvmMethodImpl | JvmMethodSpec>
  /** Static methods for the class, keyed by their name */
  staticMethods?: Record<string, JvmStaticMethodImpl | JvmStaticMethodSpec>
  /** Constructors for the class */
  constructors?: JvmConstructorSpec[]
}

declare const __jvmRoutineRunnable__: unique symbol
/** `Runnable` that was compiled from {@link inu.jvm.routine} */
declare type JvmRoutineRunnable = JavaObject & { readonly [__jvmRoutineRunnable__]: true }

/**
 * A body compiled using the supported routine subset.
 *
 * `this` is the receiver and parameters are the call arguments.
 * Values and members are Java values and members (`s.length()`, not `s.length`).
 *
 * Use a function expression to access `this`; arrows cannot access it.
 */
declare type JvmRoutineBody = (this: JavaObject, ...args: any[]) => any

declare namespace inu {
  /**
   * Unscoped access to every class available to the app, except the engine's bridge package.
   *
   * **Limits: 1 MB per value in either direction, 8 MB per DEX input.**
   *
   * @needs-grant unsafe.jvm
   */
  namespace jvm {
    /**
     * Runs Java operations on the calling thread, without ever touching JS.
     * Can be used as a `defineClass` body, a `Runnable`, or an `inu.xposed` hook filter.
     *
     * Function body is compiled by `@inugram/cli`. Supported JS subset:
     * - `const`/`let` declarations
     * - `if`
     * - `while`/`do`/`for`/`for of` with `break`/`continue`
     * - `switch`
     * - `try`/`catch`/`finally`
     * - `throw`
     * - `return`
     * - math operators:
     *   - `+` concatenates if either operand is text
     *   - `/` does integer division if both args are integers
     *   - other math operators work as expected
     * - `===`/`!==` (coerced comparisons are NOT supported)
     * - {@link inu.jvm.callSuper}, the only `inu` member available
     * - expressions on Java values.
     *
     * Unsupported syntax is a build error with a source location.
     *
     * Captured values, including arrays, are **snapshots** taken when the routine is created.
     * Unload cancels running routines, but cannot interrupt a Java call already in progress.
     *
     * **Limits: 1024 instructions, 256 slots, 256 captures, 512 live routines, 1 MB of captures, 250 ms per run.**
     */
    function routine(body: JvmRoutineBody): JvmRoutineRunnable

    /**
     * Wraps a JS function in a `java.lang.Runnable`, preserving its closure.
     * Runs synchronously on the calling thread; promise continuations run later on the plugin thread.
     *
     * Recursive entry throws IllegalStateException. Busy or closed engines skip the callback.
     * Do not wait synchronously for another thread that may call into the same plugin.
     *
     * **Limits: 250 ms to acquire the engine.**
     */
    function runnable(callback: () => void): JavaObject

    /** Get a Java class by its FQN */
    function cls(name: string): JavaClass

    /**
     * Converts a TL value to a {@link JavaObject} for calling methods or passing to app code.
     * If the object is backed by Java, returns the existing object, otherwise creates a new `TLObject`
     */
    function fromTl(value: TLObject): JavaObject

    /**
     * Returns a writable TL view of a {@link JavaObject}.
     *
     * Throws `invalid-argument` if the handle is not a `TLObject`.
     */
    function toTl(value: JavaObject): TLObject

    /**
     * Load a DEX file from a path or Uint8Array
     *
     * **Note**: avoid using this API when possible. If you need to load a DEX in your plugin,
     * please talk to us about your use-case
     */
    function loadDex(path: string | Uint8Array): void

    /**
     * Defines a public JVM class, by default extending Object with a no-arg `super()` constructor.
     *
     * Types accept primitive or fully qualified class names, `[]` arrays, and JVM descriptors.
     * Omitted params/returns use an unambiguous inherited signature, or `()void`.
     * Fields are public with Java defaults. Static methods receive the JavaClass as `self`.
     *
     * Bodies and initializers accept sync JS functions or {@link inu.jvm.routine} objects.
     * JS errors become `IllegalStateException`; recursive JS entry is rejected. After unload,
     * void callbacks do nothing and other methods fail. Java calls cannot be interrupted.
     *
     * **Note**: Due to ART limitations, generated classes **cannot** be unloaded.
     * To work around that, by default classes get a randomly generated name, available in {@link DefinedClass.name}
     *
     * If you pass a specific class name, a full app restart will be required for the changes to take effect
     *
     * **Limits: 128 classes per engine, 256 fields and 256 methods per class (including constructors and covariant bridges), 64 interfaces, 64 parameters, 1 MB for definitions/captures, 64 nested calls sharing a 250 ms admission budget.**
     */
    function defineClass(spec: JvmClassSpec): DefinedClass
    function defineClass(name: string, spec: JvmClassSpec): DefinedClass

    /**
     * Calls `method` the way `super.method(...args)` written inside `cls` would: resolved on
     * the superclass of `cls`, and running that implementation even if `self` overrides it.
     * `self` must be an instance of `cls`. Overloads are picked like {@link JavaObject.call}.
     *
     * Available inside {@link inu.jvm.routine} and {@link inu.xposed.routine} bodies as well.
     *
     * Throws `invalid-argument` if `cls` has no superclass, or the picked method is abstract.
     *
     * @example `inu.jvm.callSuper(MySpan, self, 'updateDrawState', paint)`
     */
    function callSuper(cls: JavaClass, self: JavaObject, method: string, ...args: any[]): any
  }
}

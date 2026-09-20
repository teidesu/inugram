declare type JavaObject = OpaqueType<'JVMObject'> & {
  getField: (name: string) => any
  setField: (name: string, value: any) => void
  call: (method: string, ...args: any[]) => any
}

declare type JavaMethod = OpaqueType<'JVMMethod'> & {
  invoke: (obj: JavaObject | null, ...args: any[]) => any
}

declare type JavaConstructor = OpaqueType<'JVMConstructor'> & {
  newInstance: (...args: any[]) => JavaObject
}
declare type JavaField = OpaqueType<'JVMField'> & {
  get: (obj: JavaObject | null) => any
  set: (obj: JavaObject | null, value: any) => void
}

declare type JavaClass = OpaqueType<'JVMClass'> & {
  new (...args: any[]): JavaObject

  getDeclaredMethod: (name: string) => JavaMethod
  getDeclaredConstructor: (descriptor: string) => JavaConstructor
  getDeclaredField: (name: string) => JavaField

  getStaticField: (name: string) => any
  setStaticField: (name: string, value: any) => void
  callStatic: (method: string, ...args: any[]) => any

  /**
   * `Class.isInstance`. `null` and `undefined` are `false`, as in java. Only a handle names a java
   * object here, so a scalar is `invalid-argument` rather than a quiet `false`: a js number alone
   * does not say whether it is an `Integer` or a `Long`.
   */
  isInstance: (value: JavaObject | JavaClass | JavaMethod | JavaConstructor | JavaField | null | undefined) => boolean
}

declare type JvmColdMethod = (self: JavaObject, ...args: any[]) => any
declare type JvmStaticMethod = (self: JavaClass, ...args: any[]) => any

declare interface JvmColdMethodSpec {
  params?: string[]
  returns?: string
  body: JvmColdMethod | JvmRoutineRunnable
}

declare interface JvmStaticMethodSpec {
  params?: string[]
  returns?: string
  body: JvmStaticMethod | JvmRoutineRunnable
}

declare interface JvmConstructorSpec {
  params?: string[]
  super?: ({ arg: number } | { value: any })[]
  init?: ((self: JavaObject, ...args: any[]) => void) | JvmRoutineRunnable
}

declare interface JvmClassSpec {
  superclass?: JavaClass
  interfaces?: JavaClass[]
  fields?: Record<string, string>
  staticFields?: Record<string, string>
  methods?: Record<string, JvmColdMethod | JvmColdMethodSpec>
  staticMethods?: Record<string, JvmStaticMethod | JvmStaticMethodSpec>
  constructors?: JvmConstructorSpec[]
}

declare const __jvmRoutineRunnable__: unique symbol
declare type JvmRoutineRunnable = JavaObject & { readonly [__jvmRoutineRunnable__]: true }

declare type JvmRoutineValue = OpaqueType<'JVMRoutineValue'>
declare type JvmRoutineOperand
  = | null | undefined | boolean | number | bigint | string | Uint8Array
    | JavaObject | JavaClass | JavaMethod | JavaConstructor | JavaField | JvmRoutineValue

declare interface JvmRoutineOps {
  /**
   * Only while used as a defineClass body/init, or as an `inu.xposed` hook filter, where it is the
   * receiver of the hooked call. Static methods receive their JavaClass.
   */
  getThisObject(): JvmRoutineValue
  /**
   * Zero-based method/constructor argument, or the hooked call's argument in a hook filter; fails
   * outside either.
   */
  getArgument(index: number | JvmRoutineValue): JvmRoutineValue
  /**
   * Sets the method result, or the verdict in a hook filter; remaining roots still execute.
   * Ignored for void methods/constructors.
   */
  setReturnValue(value: JvmRoutineOperand): JvmRoutineValue
  /**
   * Reads an invocation-local variable; fails if no set has executed for this name.
   * Each read operation snapshots once.
   */
  get(name: string): JvmRoutineValue
  /** Initializes or updates an invocation-local variable and returns the assigned value. */
  set(name: string, value: JvmRoutineOperand): JvmRoutineValue
  getField(target: JavaObject | JavaClass | JvmRoutineValue, name: string): JvmRoutineValue
  setField(target: JavaObject | JavaClass | JvmRoutineValue, name: string, value: JvmRoutineOperand): JvmRoutineValue
  call(target: JavaObject | JavaClass | JvmRoutineValue, method: string, ...args: JvmRoutineOperand[]): JvmRoutineValue
  /** Run fallback if body fails; completed side effects are not rolled back. */
  attempt(body: JvmRoutineValue[], fallback?: JvmRoutineValue[]): JvmRoutineValue
  when(condition: JvmRoutineOperand, yes: JvmRoutineValue[], no?: JvmRoutineValue[]): JvmRoutineValue
  /**
   * No coercion: primitive numbers compare numerically, strings by value, other Java objects by identity.
   * null and undefined both become Java null. NaN compares unequal to everything.
   */
  compare(op: '==' | '!=', left: JvmRoutineOperand, right: JvmRoutineOperand): JvmRoutineValue
  /** Ordering requires two numbers or two strings; strings use UTF-16 lexicographic order. */
  compare(op: '<' | '<=' | '>' | '>=', left: number | bigint | string | JvmRoutineValue, right: number | bigint | string | JvmRoutineValue): JvmRoutineValue
  /** Short-circuiting boolean results, using when's truthiness. */
  and(left: JvmRoutineOperand, right: JvmRoutineOperand): JvmRoutineValue
  or(left: JvmRoutineOperand, right: JvmRoutineOperand): JvmRoutineValue
  not(value: JvmRoutineOperand): JvmRoutineValue
  /**
   * Integral operands use checked signed 64-bit arithmetic (division truncates); fractional operands use double.
   * Overflow, non-finite operands/results and zero divisors fail.
   */
  math(op: '+' | '-' | '*' | '/' | '%', left: number | bigint | JvmRoutineValue, right: number | bigint | JvmRoutineValue): JvmRoutineValue
}

declare namespace inu {
  /**
   * The grant takes no scope list: it reaches every class the app can, and the engine's own
   * bridge package is the one thing it never reaches.
   *
   * @needs-grant unsafe.jvm. Values are capped at 1048576 bytes in either direction; dex input has at most 8388608 bytes of dex.
   */
  namespace jvm {
    /**
     * Build now; run Java operations later on the caller's thread, without JS callbacks.
     * Operations run once per invocation, when needed. Locals reset each time.
     * Use ops.when/and/or for conditions; operation values are not ordinary JS values.
     * Errors are logged and stop execution; completed changes stay applied.
     * Limits: 256 ops, 512 live routines, 1 MB captures, 250 ms per run, checked before each java call: every other operation runs at most once, so only a call can outlast it.
     * Unload cancels remaining operations; running Java calls cannot be interrupted.
     */
    function routine(build: (ops: JvmRoutineOps) => JvmRoutineValue[]): JvmRoutineRunnable

    /**
     * Create a `java.lang.Runnable` wrapping a JS function
     *
     * Runs synchronously on the calling thread, preserving closures.
     * Runnables created inside async onUnload cleanup remain callable until cleanup ends.
     * Recursive JNI entry throws a Java IllegalStateException. Busy (250 ms) or closed engines skip the callback.
     * JVM/Xposed calls execute on that thread; promise jobs run later on globalQueue.
     * Do not synchronously wait for another queue that may need this engine.
     */
    function runnable(callback: () => void): JavaObject

    /** Get a Java class by its FQN */
    function cls(name: string): JavaClass

    /**
     * The app's own `TLObject` behind a TL value, as a {@link JavaObject} you can call methods on
     * and pass to app code. A TL view crosses as the object it already names; a plain object is
     * built into a new one first, which is the only way a plugin has of *making* a `TLObject`.
     *
     * The read-only rule the TL surface applies to app-owned values does not survive the crossing:
     * `unsafe.jvm` reaches every class the app can, and this is one of them.
     */
    function fromTl(value: TLObject): JavaObject

    /**
     * The other direction: a {@link JavaObject} that really is a `TLObject`, read back as a TL
     * view - the same shape a read would have answered with. Throws `invalid-argument` for a handle
     * that is not one.
     *
     * The view is writable: a plugin holding the java object can set its fields through
     * {@link set} anyway, so guarding the view would guard nothing.
     */
    function toTl(value: JavaObject): TLObject

    /** Load a DEX file from a path or Uint8Array */
    function loadDex(path: string | Uint8Array): void

    /**
     * Define a public JVM class.
     * Superclass defaults to Object; constructors default to one no-arg constructor calling super().
     * Types accept primitive names, fully qualified class names, [] suffixes, or JVM type descriptors.
     * Omitted method params/returns are inferred from an unambiguous inherited signature, otherwise ()void.
     * Fields are public, initially Java's default values. Static bodies receive the JavaClass as self.
     * Bodies/init accept synchronous JS functions or interpreted inu.jvm.routine objects.
     * JS errors become Java IllegalStateException; void callbacks become no-ops after unload, other
     * methods fail. Java calls cannot be interrupted; nested defined-method invocations share a
     * 250 ms admission budget and allow at most 64 levels. JS callbacks obey the engine's reentry rule.
     * Limits: 128 classes/engine, 256 fields and 256 methods/class (including constructors/covariant
     * bridges), 64 interfaces, 64 parameters, 1 MB definitions/captures. Duplicate class names fail.
     */
    function defineClass(name: string, spec: JvmClassSpec): JavaClass

    /** Not implemented yet; throws unsupported. Constructor super arguments are supported by defineClass. */
    function callSuper(self: JavaObject, method: string, ...args: any[]): any
  }
}

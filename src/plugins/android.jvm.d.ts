declare type JavaObject = OpaqueType<'JVMObject'> & {
  getField: (name: string) => any
  setField: (name: string, value: any) => void
  call: (method: string, ...args: any[]) => any
}

declare type JavaMethod = OpaqueType<'JVMMethod'> & {
  invoke: (obj: JavaObject | null, ...args: any[]) => any
}

declare type JavaConstructor = OpaqueType<'JVMConstructor'> & {}
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
}

declare type JvmColdMethod = (self: JavaObject, ...args: any[]) => any

declare interface JvmColdMethodSpec {
  params?: string[]
  returns?: string
  body: JvmColdMethod
}

declare interface JvmConstructorSpec {
  params?: string[]
  super?: ({ arg: number } | { value: any })[]
  init?: (self: JavaObject, ...args: any[]) => void
}

declare interface JvmClassSpec {
  superclass?: JavaClass
  interfaces?: JavaClass[]
  fields?: Record<string, string>
  staticFields?: Record<string, string>
  methods?: Record<string, JvmColdMethod | JvmColdMethodSpec>
  staticMethods?: Record<string, JvmColdMethod | JvmColdMethodSpec>
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
   * The scope list is matched against the *runtime* class of everything that crosses, return
   * values included - a method's declared type says nothing about what it hands back. So
   * `cls('java.lang.Thread').callStatic('currentThread')` answers with whatever subclass is
   * actually running, and a plugin scoped to `java.lang.*` is refused it.
   *
   * @needs-grant unsafe.jvm. Values are capped at 1048576 bytes in either direction; dex input has at most 8388608 bytes of dex.
   */
  namespace jvm {
    /**
     * Build now; run Java operations later on the caller's thread, without JS callbacks.
     * Operations run once per invocation, when needed. Locals reset each time.
     * Use ops.when/and/or for conditions; operation values are not ordinary JS values.
     * Errors are logged and stop execution; completed changes stay applied.
     * Limits: 256 ops, 512 live routines, 1 MB captures, 250 ms per run (checked between operations).
     * Unload cancels remaining operations; running Java calls cannot be interrupted.
     */
    function routine(build: (ops: JvmRoutineOps) => JvmRoutineValue[]): JvmRoutineRunnable

    /**
     * Create a `java.lang.Runnable` wrapping a JS function
     *
     * Runs synchronously on the calling thread, preserving closures.
     * Recursive JNI entry throws a Java IllegalStateException. Busy (250 ms) or closed engines skip the callback.
     * JVM/Xposed calls execute on that thread; promise jobs run later on globalQueue.
     * Do not synchronously wait for another queue that may need this engine.
     */
    function runnable(callback: () => void): JavaObject

    /** Get a Java class by its FQN */
    function cls(name: string): JavaClass

    /** Load a DEX file from a path or Uint8Array */
    function loadDex(path: string | Uint8Array): void

    /** Define a Java class by its name and spec */
    function defineClass(name: string, spec: JvmClassSpec): JavaClass

    /** Call a "super" method of a class instance */
    function callSuper(self: JavaObject, method: string, ...args: any[]): any
  }
}

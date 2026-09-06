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
    /** Create a java.lang.Runnable wrapping a JS function */
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

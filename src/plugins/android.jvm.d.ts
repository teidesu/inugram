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
  /** @needs-grant unsafe.jvm. Values are capped at 1048576 bytes in either direction; dex input has at most 8388608 bytes of dex. */
  namespace jvm {
    function runnable(callback: () => void): JavaObject

    function cls(name: string): JavaClass

    function loadDex(path: string | Uint8Array): void

    function defineClass(name: string, spec: JvmClassSpec): JavaClass

    function callSuper(self: JavaObject, method: string, ...args: any[]): any
  }
}

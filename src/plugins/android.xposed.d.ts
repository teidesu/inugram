
declare namespace inu {
  /** @needs-grant unsafe.xposed @needs-grant unsafe.jvm */
  namespace xposed {
    interface MethodHookContext {
      method: JavaMethod

      thisObject: JavaObject | null

      args: any[]


      returnValue: any

      throwable: JavaObject | null


      setReturnValue: (value: any) => void

      setThrowable: (throwable: JavaObject) => void
    }

    interface MethodHook {

      before?: (ctx: MethodHookContext) => void


      after?: (ctx: MethodHookContext) => void
    }


    function hookMethod(method: JavaMethod, hook: MethodHook): Disposer


    function hookAllOverloads(cls: JavaClass, name: string, hook: MethodHook): Disposer


    function hookAllConstructors(cls: JavaClass, hook: MethodHook): Disposer


    function callOriginalMethod(method: JavaMethod, thisObject: JavaObject | null, args: any[]): any
  }
}

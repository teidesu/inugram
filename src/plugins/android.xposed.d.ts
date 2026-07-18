// for impl see https://github.com/Aliucord/hook

declare namespace inu {
  /** @needs-grant inu.xposed */
  namespace xposed {
    interface MethodHookContext {
      method: JavaMethod
      thisObject: JavaObject
      args: any[]

      result: any
      throwable: JavaObject | null

      setResult: (result: any) => void
      setThrowable: (throwable: JavaObject) => void
    }

    interface MethodHook {
      /**
       * ran *before* the java method is called.
       *
       * use `setResult` or `setThrowable` to avoid the original method from being called
       */
      before?: (ctx: MethodHookContext) => void

      /** ran *after* the java method is called */
      after?: (ctx: MethodHookContext) => void
    }

    /** hook a java method with a given hook. returns a function to remove the hook */
    function hookMethod(method: JavaMethod, hook: MethodHook): VoidFunction

    /** hook all java methods of a class matching the name, with a given hook. returns a function to remove the hooks */
    function hookAllMethods(cls: JavaClass, method: JavaMethod, hook: MethodHook): VoidFunction

    /** hook all constructors of a given java class. returns a function to remove the hooks */
    function hookAllConstructors(cls: JavaClass, method: JavaMethod, hook: MethodHook): VoidFunction

    /** call a method as if it's not hooked */
    function callOriginalMethod(method: JavaMethod, thisObject: JavaObject, args: JavaObject[]): any
  }
}

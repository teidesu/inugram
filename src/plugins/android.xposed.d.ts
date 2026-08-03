// for impl see https://github.com/Aliucord/hook

declare namespace inu {
  /**
   * method hooking.
   *
   * **implies `jvm`**: every entry point here takes a `JavaMethod`/`JavaClass`, and `inu.jvm.cls` is
   * the only thing that mints one — so `@grant xposed` on its own would be a permission you can't
   * spend. it's granted alongside `jvm` rather than requiring both be listed.
   *
   * @needs-grant xposed
   */
  namespace xposed {
    interface MethodHookContext {
      method: JavaMethod
      /** `null` for static methods and for constructor hooks running in `before` */
      thisObject: JavaObject | null
      /** live: assigning an element changes what the original method is called with */
      args: any[]

      /** the original's return value. only meaningful in `after` — `null` in `before` */
      result: any
      /** what the original threw, or `null`. only meaningful in `after` */
      throwable: JavaObject | null

      /** in `before`, skips the original entirely; in `after`, replaces what it returned */
      setResult: (result: any) => void
      /** same, but makes the call throw. clears any result set by an earlier hook */
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
    function hookMethod(method: JavaMethod, hook: MethodHook): Disposer

    /**
     * hook every overload of `name` on `cls` — the point being that you don't have to resolve a
     * `JavaMethod` (and therefore a descriptor) per overload first
     */
    function hookAllMethods(cls: JavaClass, name: string, hook: MethodHook): Disposer

    /** hook all constructors of a given java class. returns a function to remove the hooks */
    function hookAllConstructors(cls: JavaClass, hook: MethodHook): Disposer

    /** call a method as if it's not hooked. `thisObject` is `null` for static methods */
    function callOriginalMethod(method: JavaMethod, thisObject: JavaObject | null, args: any[]): any
  }
}

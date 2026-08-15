declare namespace inu {
  /**
   * A hook gets at most **250 ms per phase**; at most 512 hooks live at once.
   *
   * The eight primitive box classes (`java.lang.Integer` & co.) cannot be hooked: the hook
   * machinery boxes its own arguments through them, so such a hook would recurse into itself.
   * Registration fails with `unsupported`.
   *
   * @needs-grant unsafe.xposed
   * @needs-grant unsafe.jvm
   */
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

    function callOriginalMethod(method: JavaMethod | JavaConstructor, thisObject: JavaObject | null, args: any[]): any

    function allocateInstance(cls: JavaClass): JavaObject

    function disableProfileSaver(): boolean
  }
}

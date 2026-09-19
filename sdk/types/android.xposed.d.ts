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
    /**
     * A view of the call in progress, not a copy of it: reading a member that the hook has not read
     * yet throws `handle-expired` once the phase has returned, so a hook that needs a value past an
     * `await` or a `setTimeout` reads it while it runs. Values already read stay usable.
     */
    interface MethodHookContext {
      readonly method: JavaMethod
      readonly thisObject: JavaObject | null
      readonly args: any[]

      readonly returnValue: any
      readonly throwable: JavaObject | null

      readonly setReturnValue: (value: any) => void
      readonly setThrowable: (throwable: JavaObject) => void
    }

    interface RoutineOps extends JvmRoutineOps {
      getThisObject(): JvmRoutineValue
      getMethod(): JvmRoutineValue
      getArgument(index: number | JvmRoutineValue): JvmRoutineValue
      setArgument(index: number | JvmRoutineValue, value: JvmRoutineOperand): JvmRoutineValue
      getReturnValue(): JvmRoutineValue
      getThrowable(): JvmRoutineValue
      setReturnValue(value: JvmRoutineOperand): JvmRoutineValue
      setThrowable(value: JavaObject | JvmRoutineValue): JvmRoutineValue
    }

    /**
     * Build a Java Consumer<PluginHookContext> that runs on the hooked thread without JS callbacks.
     * Includes JVM routine ops plus access to arguments, results and throwables.
     * Setting a result/throwable in before skips the original; after hooks still run.
     * Locals reset per phase. JVM routine limits and grant checks apply.
     */
    function routine(build: (ops: RoutineOps) => JvmRoutineValue[]): JavaObject

    /**
     * JS phases run on the hooked thread. Busy/reentrant engine entry bypasses the phase.
     * Promise jobs stay on globalQueue. Hosts handle void-call queueing; queue-bound reads are unavailable off it.
     */
    interface MethodHook {
      /**
       * Accepts JS callbacks, Java Runnables or Consumers. Consumers receive the hook context.
       * JS-backed Runnables execute synchronously; recursive engine entry is refused. Java exceptions are logged; changes stay applied.
       * A plugin cannot mix JS and Java hooks on the same method.
       */
      before?: ((ctx: MethodHookContext) => void) | JavaObject
      after?: ((ctx: MethodHookContext) => void) | JavaObject
    }

    /**
     * Plugins share one physical hook per method. Plugin layers nest in registration order:
     * arguments flow forward through before phases, results back through after phases.
     * A before answer skips subsequent plugins/the original. Contexts remain per-plugin.
     * Reentrant callback dispatch bypasses that plugin; other methods called by the original still run their hooks.
     * Disposing one plugin's site leaves the others installed; the last disposal unhooks ART.
     */
    function hookMethod(method: JavaMethod, hook: MethodHook): Disposer

    function hookAllOverloads(cls: JavaClass, name: string, hook: MethodHook): Disposer

    function hookAllConstructors(cls: JavaClass, hook: MethodHook): Disposer

    /** Bypasses every plugin's hook on this member, including hooks owned by other engines. */
    function callOriginalMethod(method: JavaMethod | JavaConstructor, thisObject: JavaObject | null, args: any[]): any

    function allocateInstance(cls: JavaClass): JavaObject

    function disableProfileSaver(): boolean
  }
}

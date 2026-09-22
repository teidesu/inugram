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

    /**
     * Like `MethodHookContext`, but used inside the routines.
     *
     * Every field is `any` because all the values are Java objects, and we do not currently
     * have a way to meaningfully type them.
     */
    interface RoutineContext {
      readonly args: any[]
      readonly thisObject: any
      readonly method: any
      readonly returnValue: any
      readonly throwable: any
      setReturnValue: (value: any) => void
      setThrowable: (value: any) => void
    }

    /**
     * A Java Consumer<PluginHookContext> that runs on the hooked thread without JS callbacks.
     * The body is the same compiled subset as `inu.jvm.routine`, reading the call through its
     * parameter instead of through `this` and its own arguments; `return` takes no value here,
     * since a hook answers through `setReturnValue`. An arrow works too, and nothing here reads
     * `this` anyway, so it means what a function expression means.
     *
     * Setting a result or a throwable in a before phase skips the original; after hooks still run.
     * The same limits and grant checks apply.
     */
    function routine(body: (ctx: RoutineContext) => void): JavaObject

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

      /**
       * A predicate the host runs on the hooked thread before anything reaches the engine: a call
       * it answers falsy skips this hook entirely, before and after both, as if the method were
       * not hooked. Written as an `inu.jvm.routine` reading the call through `getThisObject()`
       * and `getArgument(i)` and answering through `setReturnValue`, so a hook that only wants
       * some calls stops paying for the rest.
       *
       * A filter that fails answers yes: it decides what to skip, so a broken one may not silently
       * disable the hook it guards. Native hooks take no filter, being host-side already.
       */
      filter?: JvmRoutineRunnable
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

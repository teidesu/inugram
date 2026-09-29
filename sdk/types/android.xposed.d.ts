declare const __xposedRoutineRunnable__: unique symbol
/** `Consumer<PluginHookContext>` that was compiled from {@link inu.xposed.routine} */
declare type XposedRoutineRunnable = JavaObject & { readonly [__xposedRoutineRunnable__]: true }

declare namespace inu {
  /**
   * Xposed-style hooking for app methods
   * Hooking primitive wrapper classes, such as `java.lang.Integer`, throws `unsupported`.
   *
   * **Limits: 250 ms per hook phase, 512 live hooks.**
   *
   * @needs-grant unsafe.xposed
   * @needs-grant unsafe.jvm
   */
  namespace xposed {
    /**
     * A live view of the current call.
     *
     * Avoid retaining this value outside the hook closure, unread values will throw `handle-expired`.
     * Read values before `await` or `setTimeout` if you need them later.
     */
    interface MethodHookContext {
      /** Reference to the method being hooked */
      readonly method: JavaMethod
      /** `this` of the method */
      readonly thisObject: JavaObject | null
      /** Arguments passed to the method, can be modified in `before` phase */
      readonly args: any[]

      /** Return value of the method. In `before` phase, value is `null` */
      readonly returnValue: any
      /** Throwable thrown by the method. In `before` phase, value is `null`. */
      readonly throwable: JavaObject | null

      /** Set a return value. In `before` phase, skips the original implementation */
      readonly setReturnValue: (value: any) => void
      /** Set a throwable. In `before` phase, skips the original implementation */
      readonly setThrowable: (throwable: JavaObject) => void

      /**
       * Any additional data to pass between the `before` and `after` phases of one call.
       * Unset at the start of each call, and shared by every hook this plugin has on the method.
       */
      extra?: any
    }

    /**
     * The hook context used inside routines. Like {@link MethodHookContext}, but inside the compiled routines.
     * Fields contain Java values, and are not currently properly typed, thus `any`
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
     * Creates a hook that runs on the hooked thread without a JS callback.
     *
     * Uses the same compiled subset as {@link inu.jvm.routine}, with a context parameter.
     *
     * Set results through `setReturnValue`; `return` cannot take a value, and `this` is unavailable.
     * Both arrows and function expressions are supported.
     */
    function routine(body: (ctx: RoutineContext) => void): XposedRoutineRunnable

    /**
     * JS callbacks run on the hooked thread, which waits for this plugin's engine. The plugin thread
     * hands the engine over while it is inside a Java call, so the wait is at most one JS turn of it.
     * The 250 ms phase budget starts once the hook has the engine; Java calls the hook makes count toward it.
     *
     * A call skips this plugin's JS callbacks when:
     * - another app thread holds this plugin's engine for over 2 s, e.g. its own callback is stuck
     *   in a slow Java call;
     * - the hooked thread is inside one of this plugin's callbacks already, e.g. a runnable called a
     *   hooked method (a call made from the plugin thread through {@link inu.jvm} runs the hook);
     * - the call happened inside one of this plugin's hook phases, e.g. a `before` called a method
     *   this plugin hooks. This applies to routine hooks too;
     * - the plugin is stopping.
     *
     * A routine hook ({@link inu.xposed.routine}) or a `filter` does not take the engine, so only the
     * recursion case skips it.
     *
     * Promise continuations run later on the plugin thread.
     * APIs requiring the plugin thread are unavailable in these callbacks.
     *
     * A plugin cannot mix JS and Java (`inu.xposed.routine`) hooks on the same method
     */
    type MethodHook = {
      /** `before` phase of the hook, called before the original implementation */
      before?: XposedRoutineRunnable
      /** `after` phase of the hook, called after the original implementation */
      after?: XposedRoutineRunnable
    } | {
      /** `before` phase of the hook, called before the original implementation */
      before?: ((ctx: MethodHookContext) => void)
      /** `after` phase of the hook, called after the original implementation */
      after?: ((ctx: MethodHookContext) => void)

      /**
       * Runs an {@link inu.jvm.routine} on the hooked thread before entering the JS engine,
       * returning `false` skips the hook. Useful for complex hooks on hot paths, to avoid
       * entering JS unnecessarily.
       *
       * The call info can be read through:
       * - `getThisObject()` for `this`
       * - `getArgument(i)` for i-th parameter (0-indexed)
       * - `setReturnValue(...)` to set the result
       *
       * If the filter fails, the hook runs.
       */
      filter?: JvmRoutineRunnable
    }

    /**
     * Hook a Java method with an Xposed-style hook.
     *
     * `before` hooks run in registration order, `after` hooks are run in reverse order.
     *
     * Setting a result in a before hook skips later plugins and the original method.
     * Each plugin receives its own context.
     *
     * Recursive calls during a callback skip that plugin's hooks. Calls made by the original
     * method still run hooks normally. Disposing your hook leaves other plugins' hooks active.
     */
    function hookMethod(method: JavaMethod, hook: MethodHook): Disposer

    /** Hook all overloads of a class method by its name */
    function hookAllOverloads(cls: JavaClass, name: string, hook: MethodHook): Disposer

    /** Hook all constructors of a class */
    function hookAllConstructors(cls: JavaClass, hook: MethodHook): Disposer

    /** Calls the original member, bypassing all plugins' hooks. */
    function callOriginalMethod(method: JavaMethod | JavaConstructor, thisObject: JavaObject | null, args: any[]): any

    /** Create an instance of the class without running any constructor, via JNI `AllocObject`. */
    function allocateInstance(cls: JavaClass): JavaObject

    /** Disable Android Profile Saver for the process. */
    function disableProfileSaver(): boolean
  }
}

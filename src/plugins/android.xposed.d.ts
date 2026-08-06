// for impl see https://github.com/Aliucord/hook

declare namespace inu {
  /**
   * method hooking.
   *
   * **needs `unsafe.jvm` listed too, and does not imply it.** every entry point here takes a
   * `JavaMethod`/`JavaClass`, and `inu.jvm.cls` is the only thing that mints one, so this grant on
   * its own is a permission you can't spend. an earlier draft granted the pair implicitly, which
   * saved one token in a header at the cost of the only property this tier has: that what the
   * manifest lists is what the plugin got. a grant that quietly becomes two is a grant nobody read.
   *
   * one plugin may have at most 512 hooks live at once, and a `hookAll*` counts one per overload it
   * installed; past that every entry point here throws `quota-exceeded` until a `Disposer` runs.
   * unlike a registration, a hook is an ART method whose entry point was rewritten for the life of
   * the process, and it costs a dispatch on every call of a method the app may run in a loop.
   *
   * every entry point returns a `Disposer` with the semantics `common.d.ts` states for all of them.
   * the callbacks of one hook site run in registration order, `before` first to last and then
   * `after` first to last; a dispatch walks the list as it was when the call arrived, so a hook
   * added by a callback joins from the next call and one disposed by a callback still finishes the
   * run in flight. hooking the same method twice registers a second set of callbacks over the one
   * hook site rather than a second hook, and the site is uninstalled when the last of them goes.
   *
   * an engine being unloaded takes down every hook it installed, because an ART entry point stays
   * rewritten and a hook left behind would dispatch into an engine that is gone.
   *
   * **your callbacks do not run on the thread that called the hooked method.** they run where all
   * your other code runs, and the calling thread waits for them — once for `before`, and again for
   * `after` if you have one — for at most **250 ms per phase**, past which the app gets its own
   * method as if nothing had hooked it. the original itself is still called on the thread that
   * called it, so hooking something only the ui thread may run is safe. two consequences worth
   * planning around: a hook on a method the app calls in a tight loop pays a queue hop per call, so
   * keep the callbacks short and prefer hooking something coarse; and a `before` that answers with
   * `setReturnValue`/`setThrowable` costs *no* hop for its `after`, nothing having to run in
   * between. a hooked method reached from inside your own plugin code runs its original directly
   * with no callbacks at all — the wait there would be a wait on yourself.
   *
   * @needs-grant unsafe.xposed
   * @needs-grant unsafe.jvm
   */
  namespace xposed {
    interface MethodHookContext {
      method: JavaMethod
      /** `null` for static methods and for constructor hooks running in `before` */
      thisObject: JavaObject | null
      /** live: assigning an element changes what the original method is called with */
      args: any[]

      /** the original's return value. only meaningful in `after` — `null` in `before` */
      returnValue: any
      /** what the original threw, or `null`. only meaningful in `after` */
      throwable: JavaObject | null

      /** in `before`, skips the original entirely; in `after`, replaces what it returned */
      setReturnValue: (value: any) => void
      /** same, but makes the call throw. clears any return value set by an earlier hook */
      setThrowable: (throwable: JavaObject) => void
    }

    interface MethodHook {
      /**
       * ran *before* the java method is called.
       *
       * use `setReturnValue` or `setThrowable` to avoid the original method from being called
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
    function hookAllOverloads(cls: JavaClass, name: string, hook: MethodHook): Disposer

    /** hook all constructors of a given java class. returns a function to remove the hooks */
    function hookAllConstructors(cls: JavaClass, hook: MethodHook): Disposer

    /** call a method as if it's not hooked. `thisObject` is `null` for static methods */
    function callOriginalMethod(method: JavaMethod, thisObject: JavaObject | null, args: any[]): any
  }
}

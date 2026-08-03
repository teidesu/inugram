/// <reference path="./fs.d.ts" />
/// <reference path="./android.jvm.d.ts" />
/// <reference path="./android.notification-center.d.ts" />
/// <reference path="./android.xposed.d.ts" />

declare namespace inu {
  namespace ui {
    /**
     * android also accepts a fragment (`BaseFragment`) here, so pushing a plugin page and pushing
     * a stock screen are the same verb.
     */
    function openPage(fragment: JavaObject): void
  }

  /** android-specific apis */
  namespace android {
    function getPluginsDir(): string
    function getCacheDir(): string
    function getMediaDir(type: 'files' | 'images' | 'videos' | 'audios' | 'documents'): string

    /**
     * the fragment on top of the navigation stack — stock's `getLastFragment()`, which is the
     * single most-used thing in exteragram's sdk. the portable slice of it is
     * `inu.ui.getCurrentScreen`; reach for this when you need the real object (to walk the view
     * tree, anchor a popup, or hand it somewhere that wants a fragment).
     *
     * `null` when nothing is on screen.
     *
     * @needs-grant unsafe.jvm
     */
    function getCurrentFragment(): JavaObject | null
    /**
     * the hosting activity (`LaunchActivity`), for the places that want a `Context` rather than a
     * fragment.
     *
     * @needs-grant unsafe.jvm
     */
    function getCurrentActivity(): JavaObject | null

    /**
     * an icon from the app's own drawables, by resource name (`msg_settings`). native and free,
     * but it ties the plugin to android and to whatever the current app version happens to ship —
     * `inu.icons.common` travels better.
     */
    function resourceIcon(name: string): UIIcon

    /**
     * wrap a real `View` as a `UIElement`, so it can be a settings row or a dialog `body`. the
     * escape hatch for ui the declarative elements can't express.
     *
     * @needs-grant unsafe.jvm
     */
    function nativeView(view: JavaObject): UIElement
  }
}

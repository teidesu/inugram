/// <reference path="./fs.d.ts" />
/// <reference path="./android.jvm.d.ts" />
/// <reference path="./android.notification-center.d.ts" />
/// <reference path="./android.xposed.d.ts" />

declare namespace inu {
  namespace ui {
    /**
     * android also accepts a fragment (`BaseFragment`) here, so pushing a plugin page and pushing
     * a stock screen are the same verb.
     *
     * the object has to be one `inu.jvm` handed you, and it has to be a `BaseFragment`: anything
     * else throws `invalid-argument`, and a handle you have already let go of throws
     * `handle-expired`. with no ui on screen this is a no-op, exactly as the page form is.
     */
    function openPage(fragment: JavaObject): void
  }

  /** android-specific apis */
  namespace android {
    /**
     * the app's own directories, as absolute paths: where plugins are installed, the app cache, and
     * where the app keeps each kind of downloaded media.
     *
     * every one of them is outside `inu.fs`'s scoped root, so the grant is `unsafe.fs` rather than
     * `fs` - the path is only actionable through the api that can leave the sandbox, and asking for
     * the grant here rather than at the first read is the difference between a refusal and a
     * disclosure.
     *
     * a directory the app has not made throws `not-found`: `getMediaDir` names five kinds and the
     * app only creates one when it first downloads something of that kind. a `type` that is not one
     * of the five throws `invalid-argument`.
     *
     * @needs-grant unsafe.fs
     */
    function getPluginsDir(): string
    /** @needs-grant unsafe.fs */
    function getCacheDir(): string
    /** @needs-grant unsafe.fs */
    function getMediaDir(type: 'files' | 'images' | 'videos' | 'audios' | 'documents'): string

    /**
     * the fragment on top of the navigation stack — stock's `getLastFragment()`, which is the
     * single most-used thing in exteragram's sdk. the portable slice of it is
     * `inu.ui.getCurrentScreen`; reach for this when you need the real object (to walk the view
     * tree, anchor a popup, or hand it somewhere that wants a fragment).
     *
     * `null` when nothing is on screen. both of these are synchronous, so they answer from what the
     * app has at that moment or answer `null`: a process a push notification woke has no activity
     * and never will, and one that is finishing is the same as none.
     *
     * the handle is minted through the same path every other `inu.jvm` reference takes, so the
     * scope list still applies: a plugin scoped to one package is refused a fragment from another,
     * and asking for "whatever is on screen" buys no reach that naming the class would not.
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
     * but it ties the plugin to android and to whatever the current app version happens to ship.
     * `inu.icons.common` travels better.
     *
     * a name no drawable answers to throws `not-found`, and a name that is not a bare
     * `[A-Za-z0-9_]` identifier throws `invalid-argument`: the lookup also understands a qualified
     * `package:type/name`, and this only ever names a drawable of this app.
     * a name longer than 128 characters is `invalid-argument` too: no resource this app ships is
     * anywhere near that, and the name is copied out of js on every call.
     *
     * the resource id is resolved through the current screen's resources, so an icon named here
     * follows the user's icon pack exactly like the app's own rows do.
     */
    function resourceIcon(name: string): UIIcon

    /**
     * wrap a real `View` as a `UIElement`, so it can be a settings row or a dialog `body`. the
     * escape hatch for ui the declarative elements can't express.
     *
     * this is the **only** element that can be a dialog `body`: dialog options cross as JSON, and
     * a declarative element's callbacks would not survive that, so the rest are refused rather
     * than rendered into something that quietly does nothing.
     *
     * the view stays yours. the app never retains it past the row or dialog it was put in, so put
     * one view in one place - android gives a view a single parent, and the same handle in two
     * rows draws in neither reliably. a handle you have released, or one that never named a
     * `View`, drops the row rather than failing the whole render.
     *
     * @needs-grant unsafe.jvm
     */
    function nativeView(view: JavaObject): UIElement
  }
}

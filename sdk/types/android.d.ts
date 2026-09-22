/// <reference path="./fs.d.ts" />
/// <reference path="./android.jvm.d.ts" />
/// <reference path="./android.notification-center.d.ts" />
/// <reference path="./android.xposed.d.ts" />

declare namespace inu {
  namespace ui {
    /** Open a page from a manually created `Fragment` */
    function openPage(fragment: JavaObject): void
  }

  namespace android {
    /**
     * Create an Android `Bundle` from a JS object.
     * Supported value types:
     * - `number` (`putInt`, `putLong` or `putDouble`)
     * - `bigint` (`putLong`)
     * - `boolean`
     * - `string`
     * - `Uint8Array`
     * - Bundle-compatible Java objects such as `Parcelable`, `IBinder`, and `Serializable` values.
     *
     * @needs-grant unsafe.jvm
     */
    function bundle(values: Record<string, boolean | number | bigint | string | Uint8Array | JavaObject>): JavaObject

    /**
     * Get a full path to the plugins installation directory
     *
     * @needs-grant unsafe.fs
     */
    function getPluginsDir(): string
    /**
     * Get a full path to the app cache directory
     *
     * @needs-grant unsafe.fs
     */
    function getCacheDir(): string
    /**
     * Get a full path to the media directory, of the specified type
     *
     * @needs-grant unsafe.fs
     */
    function getMediaDir(type: 'files' | 'images' | 'videos' | 'audios' | 'documents'): string

    /**
     * Get a reference to the currently visible `Fragment`
     *
     * @needs-grant unsafe.jvm
     */
    function getCurrentFragment(): JavaObject | null
    /**
     * Get a reference to the currently visible `Activity`
     *
     * @needs-grant unsafe.jvm
     */
    function getCurrentActivity(): JavaObject | null

    /**
     * Get a {@link UIIcon} from `R.drawable.{name}`
     *
     * **Limits: 128 characters per name.**
     */
    function resourceIcon(name: string): UIIcon

    /**
     * Get a {@link UIIcon} from a Lottie JSON animation from `R.raw.{name}`
     */
    function rawAnimation(name: string, options?: icons.LottieOptions): UIIcon

    /**
     * Get a {@link UIIcon} from a manual Java `Drawable`
     *
     * @needs-grant unsafe.jvm
     */
    function drawableIcon(drawable: JavaObject): UIIcon

    /**
     * Get a {@link UIElement} from a manual Java `View`
     *
     * @needs-grant unsafe.jvm
     */
    function nativeView(view: JavaObject): UIElement
  }
}

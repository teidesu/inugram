/// <reference path="./fs.d.ts" />
/// <reference path="./android.jvm.d.ts" />
/// <reference path="./android.notification-center.d.ts" />
/// <reference path="./android.xposed.d.ts" />

declare namespace inu {
  namespace ui {
    function openPage(fragment: JavaObject): void
  }

  namespace android {
    /**
     * Creates an Android `Bundle`. Integer `number`s use `putInt`; use `bigint` for `putLong`.
     * Other supported values are finite numbers, booleans, strings, `Uint8Array`s, and
     * Bundle-compatible Java objects such as `Parcelable`, `IBinder`, and `Serializable` values.
     * @needs-grant unsafe.jvm(android.os.Bundle)
     */
    function bundle(values: Record<string, boolean | number | bigint | string | Uint8Array | JavaObject>): JavaObject

    /** @needs-grant unsafe.fs */
    function getPluginsDir(): string
    /** @needs-grant unsafe.fs */
    function getCacheDir(): string
    /** @needs-grant unsafe.fs */
    function getMediaDir(type: 'files' | 'images' | 'videos' | 'audios' | 'documents'): string

    /** @needs-grant unsafe.jvm */
    function getCurrentFragment(): JavaObject | null
    /** @needs-grant unsafe.jvm */
    function getCurrentActivity(): JavaObject | null

    /** Refuses a name longer than 128 characters. */
    function resourceIcon(name: string): UIIcon

    /** @needs-grant unsafe.jvm. Retains the drawable while its settings page is rendered. */
    function drawableIcon(drawable: JavaObject): UIIcon

    /** @needs-grant unsafe.jvm */
    function nativeView(view: JavaObject): UIElement
  }
}

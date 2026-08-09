/// <reference path="./fs.d.ts" />
/// <reference path="./android.jvm.d.ts" />
/// <reference path="./android.notification-center.d.ts" />
/// <reference path="./android.xposed.d.ts" />

declare namespace inu {
  namespace ui {

    function openPage(fragment: JavaObject): void
  }


  namespace android {
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


    function resourceIcon(name: string): UIIcon

    /** @needs-grant unsafe.jvm */
    function nativeView(view: JavaObject): UIElement
  }
}

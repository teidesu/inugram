/// <reference path="./fs.d.ts" />
/// <reference path="./android.jvm.d.ts" />
/// <reference path="./android.notification-center.d.ts" />
/// <reference path="./android.xposed.d.ts" />

declare namespace inu {
  /** android-specific apis */
  namespace android {
    function getPluginsDir(): string
    function getCacheDir(): string
    function getMediaDir(type: 'files' | 'images' | 'videos' | 'audios' | 'documents'): string

    /** force convert a tl object to a JavaObject representing a TLRPC object */
    function tlToJavaObject(tl: TLObject): JavaObject // todo: do we need this?
  }
}

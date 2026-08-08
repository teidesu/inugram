package desu.inugram.helpers.plugins

import android.content.Context
import android.content.pm.PackageInfo
import android.util.Log
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.core.plugins.PluginInstall
import desu.inugram.core.plugins.PluginInstalls
import desu.inugram.core.plugins.PluginManifestParser
import desu.inugram.helpers.plugins.io.PluginFs
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.BuildConfig

/**
 * The installed set as it exists on disk: one `.js` file per install, plus the record in
 * `PLUGINS_STATE` carrying its identity, its place in the order and its enabled bit.
 *
 * Split from [PluginManager], which owns the *engines*: nothing here starts, stops or talks to one,
 * and the two halves fail differently - a plugin that will not load is this side's problem and one
 * that throws is that side's.
 *
 * **The id is the whole point of the record.** It is minted at install ([PluginInstalls.mintId]) and
 * never derived from the file or the manifest, so a plugin that is renamed keeps its `kv` and `fs`
 * stores while a name-squatter gets an empty one. Losing a record therefore loses the user's data:
 * every failure below keeps the persisted state rather than reconciling against a set it could not
 * read.
 */
object PluginStore {
    private const val TAG = "InuPluginStore"
    private const val BUNDLED_DIR = "inu_plugins"
    private const val BUNDLED_STAMP_KEY = "plugins_bundled_apk"

    val dir: File by lazy { PluginFs.storeDir() }

    /** kept out of [load]'s answer: a file that failed to load this boot must still be persisted */
    private var unloaded: List<PluginInstall> = emptyList()

    /**
     * debug-only. Skipped unless the apk changed, because this runs from
     * `ApplicationLoader.onCreate` - the head of the notification path for a process a push woke,
     * and unpacking the set is a read and a write each. The assets live *in* the apk, so a reinstall
     * (what dev iteration is) bumps `lastUpdateTime`.
     */
    fun copyBundled(context: Context, packageInfo: PackageInfo?, appBuild: String) {
        if (!BuildConfig.DEBUG) return
        val stamp = packageInfo?.let { "$appBuild:${it.lastUpdateTime}" }
        if (stamp != null && InuConfig.prefs.getString(BUNDLED_STAMP_KEY, null) == stamp) return
        val names = try {
            context.assets.list(BUNDLED_DIR)
        } catch (e: Exception) {
            Log.e(TAG, "list bundled plugins failed", e)
            null
        } ?: return
        var complete = true
        for (name in names) {
            if (!name.endsWith(".js")) continue
            try {
                val text = context.assets.open("$BUNDLED_DIR/$name").use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                File(dir, name).writeText(text)
            } catch (e: Exception) {
                complete = false
                Log.e(TAG, "copy bundled plugin failed: $name", e)
            }
        }
        if (stamp != null && complete) InuConfig.prefs.edit { putString(BUNDLED_STAMP_KEY, stamp) }
    }

    /**
     * every install on disk that loads, in persisted order. A file nobody has a record for is a new
     * install and is assigned a fresh id here, so [persist] has to follow before anything uses one.
     */
    fun load(): List<Plugin> {
        // null is "could not list", which is not "there are no plugins": reconciling against an empty
        // set drops every record, and [persist] would then write that back, losing the ids the kv
        // stores are keyed on. leave the persisted state alone and run no plugins this boot
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".js") }
        if (files == null) {
            Log.e(TAG, "could not list $dir; keeping the persisted installs and skipping plugins")
            return emptyList()
        }
        val installs = PluginInstalls.reconcile(readPersisted(), files.map { it.name }.sorted())
        val loaded = ArrayList<Plugin>()
        for (install in installs) {
            val file = File(dir, install.file)
            val source = try {
                file.readText()
            } catch (e: Exception) {
                Log.e(TAG, "read failed: ${install.file}", e)
                continue
            }
            val manifest = PluginManifestParser.parseOrNull(source)
            if (manifest == null) {
                Log.w(TAG, "no valid manifest: ${install.file}")
                continue
            }
            loaded.add(Plugin(install.id, file, source, manifest).apply { enabled = install.enabled })
        }
        // a file we could not load this boot keeps its record, or fixing it later would land it on a
        // fresh id and an empty store
        unloaded = installs.filter { install -> loaded.none { it.file.name == install.file } }
        return loaded
    }

    fun persist(plugins: List<Plugin>) {
        val arr = JSONArray()
        for (p in plugins) {
            arr.put(JSONObject().put("id", p.id).put("file", p.file.name).put("enabled", p.enabled))
        }
        for (install in unloaded) {
            arr.put(JSONObject().put("id", install.id).put("file", install.file).put("enabled", install.enabled))
        }
        InuConfig.PLUGINS_STATE.value = arr.toString()
    }

    /** a free path under [dir] for [suggestedName]; the caller writes the source into it */
    fun fileFor(suggestedName: String): File {
        val safe = suggestedName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .removeSuffix(".js").ifBlank { "plugin" }
        var candidate = File(dir, "$safe.js")
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$safe-$i.js")
            i++
        }
        return candidate
    }

    private fun readPersisted(): List<PluginInstall> {
        val raw = InuConfig.PLUGINS_STATE.value
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val file = o.optString("file")
                if (file.isEmpty()) null
                else PluginInstall(o.optString("id"), file, o.optBoolean("enabled", true))
            }
        } catch (e: Exception) {
            Log.e(TAG, "bad plugins state", e)
            emptyList()
        }
    }
}

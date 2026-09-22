package desu.inugram.helpers.plugins

import android.util.Log
import desu.inugram.InuConfig
import desu.inugram.core.plugins.PluginInstall
import desu.inugram.core.plugins.PluginInstalls
import desu.inugram.core.plugins.PluginManifestParser
import desu.inugram.helpers.plugins.io.PluginFs
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns installed files: one `.js` per install, plus its plugin ID, order, and enabled state
 * in `PLUGINS_STATE`. [PluginManager] owns running engines and runtime failures; this class
 * handles file loading and persistence.
 *
 * [PluginInstalls.mintId] creates an install ID independently of the filename or manifest.
 * It keys `kv` and `fs` storage, preserving data across renames and preventing another plugin
 * from claiming it by name. Preserve records when reads fail; discarding them loses access
 * to the user's data.
 */
object PluginStore {
    private const val TAG = "InuPluginStore"

    val dir: File by lazy { PluginFs.storeDir() }

    /** kept out of [load]'s answer: a file that failed to load this boot must still be persisted */
    private var unloaded: List<PluginInstall> = emptyList()

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
            unloaded = readPersisted()
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
            loaded.add(
                Plugin(install.id, file, source, manifest).apply {
                    enabled = install.enabled
                    dev = install.dev
                }
            )
        }
        // a file we could not load this boot keeps its record, or fixing it later would land it on a
        // fresh id and an empty store
        unloaded = installs.filter { install -> loaded.none { it.file.name == install.file } }
        return loaded
    }

    fun persist(plugins: List<Plugin>) {
        val arr = JSONArray()
        for (p in plugins) {
            arr.put(record(p.id, p.file.name, p.enabled, p.manifest.id, p.dev))
        }
        for (install in unloaded) {
            arr.put(record(install.id, install.file, install.enabled, install.pluginId, install.dev))
        }
        InuConfig.PLUGINS_STATE.value = arr.toString()
    }

    private fun record(id: String, file: String, enabled: Boolean, pluginId: String?, dev: Boolean): JSONObject =
        JSONObject().put("id", id).put("file", file).put("enabled", enabled).putOpt("pluginId", pluginId)
            .apply { if (dev) put("dev", true) }

    /**
     * Finds a record that failed to load this boot but whose file claims [pluginId].
     * Re-importing a fixed file must reuse its install ID to recover its `kv`/`fs` storage.
     * Keep the record unloaded until the caller completes the import and calls [dropUnloaded],
     * so a failed import does not lose it.
     */
    fun findUnloaded(pluginId: String): PluginInstall? = unloaded.firstOrNull { it.pluginId == pluginId }

    /** hands a record found by [findUnloaded] over to the caller, so [persist] writes it only once */
    fun dropUnloaded(install: PluginInstall) {
        unloaded = unloaded - install
    }

    /**
     * Writes source through a temporary file. If [file] is an existing install, a failed write
     * must preserve its only on-disk copy.
     */
    fun writeSource(file: File, source: String): Boolean {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        return try {
            tmp.writeText(source)
            if (!tmp.renameTo(file)) throw IOException("rename to $file failed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "write failed: ${file.name}", e)
            tmp.delete()
            false
        }
    }

    /**
     * [plugin]'s source written out for sharing, under [into]. Named the way a plugin file is
     * written and read back: `.inu.js` is what the install flow and the dev server both expect.
     */
    fun exportTo(into: File, plugin: Plugin): File {
        val base = plugin.file.name.removeSuffix(".js").removeSuffix(".inu").ifBlank { "plugin" }
        val file = File(into, "$base.inu.js")
        into.mkdirs()
        file.writeText(plugin.source, Charsets.UTF_8)
        return file
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
                else PluginInstall(
                    o.optString("id"),
                    file,
                    o.optBoolean("enabled", true),
                    o.optString("pluginId").takeIf { it.isNotEmpty() },
                    o.optBoolean("dev", false),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "bad plugins state", e)
            emptyList()
        }
    }
}

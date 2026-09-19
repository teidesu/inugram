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
            arr.put(record(p.id, p.file.name, p.enabled, p.manifest.identity, p.dev))
        }
        for (install in unloaded) {
            arr.put(record(install.id, install.file, install.enabled, install.identity, install.dev))
        }
        InuConfig.PLUGINS_STATE.value = arr.toString()
    }

    private fun record(id: String, file: String, enabled: Boolean, identity: String?, dev: Boolean): JSONObject =
        JSONObject().put("id", id).put("file", file).put("enabled", enabled).putOpt("identity", identity)
            .apply { if (dev) put("dev", true) }

    /**
     * the record of an install that did not load this boot but whose file claims [identity].
     *
     * A plugin only lands here when its file stopped parsing, which is exactly when the user goes
     * and re-imports a fixed copy. Nothing lists it, so reusing its id is also the only way its
     * `kv`/`fs` stores are ever reachable again. It stays on the unloaded list until the caller has
     * actually taken it over ([dropUnloaded]), or a failed import would strand the id anyway.
     */
    fun findUnloaded(identity: String): PluginInstall? = unloaded.firstOrNull { it.identity == identity }

    /** hands a record found by [findUnloaded] over to the caller, so [persist] writes it only once */
    fun dropUnloaded(install: PluginInstall) {
        unloaded = unloaded - install
    }

    /**
     * writes plugin source through a temporary file, because [file] may be an install that works:
     * a write that dies halfway leaves the user with neither the old plugin nor the new one, and
     * the bytes on disk are the only copy either has.
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
                    o.optString("identity").takeIf { it.isNotEmpty() },
                    o.optBoolean("dev", false),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "bad plugins state", e)
            emptyList()
        }
    }
}

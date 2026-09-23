package desu.inugram.helpers.plugins

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
 * Owns installed files: one `<install id>.js` per install, plus its plugin ID, order, and enabled
 * state in `PLUGINS_STATE`. [PluginManager] owns running engines and runtime failures; this class
 * handles file loading and persistence.
 *
 * [PluginInstalls.mintId] creates an install ID independently of the manifest. It keys
 * `localStorage` and `fs` storage, preserving data across renames and preventing another plugin
 * from claiming it by name. The ID is the file name, so losing `PLUGINS_STATE` loses order and
 * flags, never the link between a plugin and its storage.
 */
object PluginStore {

    val dir: File by lazy { PluginFs.storeDir() }

    /** kept out of [load]'s answer: a file that failed to load this boot must still be persisted */
    private var unloaded: List<PluginInstall> = emptyList()

    /**
     * Every install on disk that loads, in persisted order, or null when the directory could not be
     * listed. Null is not "there are no plugins": the persisted state is kept as it is, and nothing
     * may treat the missing installs as gone.
     */
    fun load(): List<Plugin>? {
        val files = dir.list()
        if (files == null) {
            PluginLog.HOST.e("store", "could not list $dir; keeping the persisted installs and skipping plugins")
            unloaded = readPersisted()
            return null
        }
        val installs = PluginInstalls.reconcile(readPersisted(), files.asList())
        val loaded = ArrayList<Plugin>()
        for (install in installs) {
            val file = File(dir, install.file)
            val source = try {
                file.readText()
            } catch (e: Exception) {
                PluginLog.HOST.e("store", "read failed: ${install.file}", e)
                continue
            }
            val manifest = PluginManifestParser.parseOrNull(source)
            if (manifest == null) {
                PluginLog.HOST.w("store", "no valid manifest: ${install.file}")
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
        unloaded = installs.filter { install -> loaded.none { it.id == install.id } }
        return loaded
    }

    /** every install with a source file: the ones [plugins] holds and the ones that did not load */
    fun installIds(plugins: List<Plugin>): Set<String> =
        plugins.mapTo(HashSet()) { it.id }.apply { unloaded.mapTo(this) { it.id } }

    fun persist(plugins: List<Plugin>) {
        val arr = JSONArray()
        for (p in plugins) {
            arr.put(record(p.id, p.enabled, p.manifest.id, p.dev))
        }
        for (install in unloaded) {
            arr.put(record(install.id, install.enabled, install.pluginId, install.dev))
        }
        InuConfig.PLUGINS_STATE.value = arr.toString()
    }

    private fun record(id: String, enabled: Boolean, pluginId: String?, dev: Boolean): JSONObject =
        JSONObject().put("id", id).put("enabled", enabled).putOpt("pluginId", pluginId)
            .apply { if (dev) put("dev", true) }

    /**
     * Finds a record that failed to load this boot but whose file claims [pluginId].
     * Re-importing a fixed file must reuse its install ID to recover its `localStorage`/`fs` storage.
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
            PluginLog.HOST.e("store", "write failed: ${file.name}", e)
            tmp.delete()
            false
        }
    }

    /**
     * [plugin]'s source written out for sharing, under [into]. Named the way a plugin file is
     * written and read back: `.inu.js` is what the install flow and the dev server both expect.
     */
    fun exportTo(into: File, plugin: Plugin): File {
        val base = plugin.manifest.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "plugin" }
        val file = File(into, "$base.inu.js")
        into.mkdirs()
        file.writeText(plugin.source, Charsets.UTF_8)
        return file
    }

    fun fileFor(installId: String): File = File(dir, PluginInstalls.fileName(installId))

    /** a record that does not parse is skipped rather than failing the rest: the file keeps its id either way */
    private fun readPersisted(): List<PluginInstall> {
        val raw = InuConfig.PLUGINS_STATE.value
        if (raw.isBlank()) return emptyList()
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            PluginLog.HOST.e("store", "bad plugins state; installs keep their ids but lose order and flags", e)
            return emptyList()
        }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i)
            val id = o?.optString("id")?.takeIf(PluginInstalls::isValidId)
            if (o == null || id == null) {
                PluginLog.HOST.w("store", "dropping bad plugins state record #$i")
                return@mapNotNull null
            }
            PluginInstall(
                id,
                o.optBoolean("enabled", true),
                o.optString("pluginId").takeIf { it.isNotEmpty() },
                o.optBoolean("dev", false),
            )
        }
    }
}

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
 * The install id is the file name and keys `localStorage`/`fs`, so storage survives renames and cannot
 * be claimed by name. Losing `PLUGINS_STATE` loses order and flags, never the storage link.
 */
object PluginStore {

    val dir: File by lazy { PluginFs.storeDir() }

    /** a file that failed to load this boot must still be persisted */
    private var unloaded: List<PluginInstall> = emptyList()

    /** null is not "no plugins": persisted state is kept and missing installs must not be treated as gone */
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
        // fixing it later must not land it on a fresh id and empty store
        unloaded = installs.filter { install -> loaded.none { it.id == install.id } }
        return loaded
    }

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

    /** kept until the caller finishes the import and calls [dropUnloaded], so a failed import does not lose it */
    fun findUnloaded(pluginId: String): PluginInstall? = unloaded.firstOrNull { it.pluginId == pluginId }

    fun dropUnloaded(install: PluginInstall) {
        unloaded = unloaded - install
    }

    /** an existing install's only copy must survive a failed write */
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

    /** `.inu.js` is what the install flow and the dev server expect */
    fun exportTo(into: File, plugin: Plugin): File {
        val base = plugin.manifest.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "plugin" }
        val file = File(into, "$base.inu.js")
        into.mkdirs()
        file.writeText(plugin.source, Charsets.UTF_8)
        return file
    }

    /** the file keeps its id either way */
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

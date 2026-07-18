package desu.inugram.helpers.plugins

import android.content.Context
import android.content.SharedPreferences
import desu.inugram.core.plugins.TlWire
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.Utilities

/**
 * Per-plugin persistent key-value store backing `inu.kv` (rust: `api.rs`). One SharedPreferences
 * file per plugin (account-independent by design - the stock per-account DB is the wrong
 * granularity for plugin state), so reads are in-memory after first touch and writes persist
 * asynchronously. Values are strings only; total size is capped at [MAX_BYTES] per plugin.
 *
 * Results are TlWire-tagged strings (`S`/`N`/`J`/`E`) - see [QuickJs.ApiListener.kv].
 * Called only on [org.telegram.messenger.Utilities.globalQueue] (the engines' thread).
 */
object PluginKv {
    private const val MAX_BYTES = 1 shl 20

    const val OP_GET = 0
    const val OP_SET = 1
    const val OP_DEL = 2
    const val OP_KEYS = 3
    const val OP_CLEAR = 4
    const val OP_GET_ALL = 5
    const val OP_INSERT_ALL = 6

    // MD5 of the plugin id: ids are user-authored ("namespace/name") and must not leak unsafe
    // chars into a prefs filename
    private fun prefsName(pluginId: String): String =
        "inuplugin_kv_" + (Utilities.MD5(pluginId) ?: pluginId.hashCode().toString())

    private fun prefs(pluginId: String): SharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences(prefsName(pluginId), Context.MODE_PRIVATE)

    fun handleOp(pluginId: String, op: Int, key: String, value: String): String = try {
        val prefs = prefs(pluginId)
        when (op) {
            OP_GET -> prefs.getString(key, null)?.let { TlWire.encodeString(it) } ?: TlWire.encodeNull()
            OP_SET -> setEntries(prefs, mapOf(key to value))
            OP_DEL -> {
                prefs.edit().remove(key).apply()
                TlWire.encodeNull()
            }
            OP_KEYS -> {
                val arr = JSONArray()
                for (k in prefs.all.keys) arr.put(k)
                TlWire.encodeJson(arr.toString())
            }
            OP_CLEAR -> {
                prefs.edit().clear().apply()
                TlWire.encodeNull()
            }
            OP_GET_ALL -> {
                val obj = JSONObject()
                for ((k, v) in prefs.all) obj.put(k, v as? String ?: continue)
                TlWire.encodeJson(obj.toString())
            }
            OP_INSERT_ALL -> {
                val parsed = JSONObject(value)
                val entries = LinkedHashMap<String, String>()
                for (k in parsed.keys()) {
                    val v = parsed.get(k)
                    if (v !is String) throw IllegalArgumentException("insertAll: value for '$k' must be a string")
                    entries[k] = v
                }
                setEntries(prefs, entries)
            }
            else -> TlWire.encodeError("kv: unknown op $op")
        }
    } catch (e: Exception) {
        TlWire.encodeError("kv: ${e.message}")
    }

    private fun setEntries(prefs: SharedPreferences, entries: Map<String, String>): String {
        var total = usedBytes(prefs)
        for ((key, value) in entries) {
            (prefs.getString(key, null))?.let { total -= entrySize(key, it) }
            total += entrySize(key, value)
        }
        if (total > MAX_BYTES) {
            return TlWire.encodeError("kv: 1 MB per-plugin quota exceeded")
        }
        val editor = prefs.edit()
        for ((key, value) in entries) editor.putString(key, value)
        editor.apply()
        return TlWire.encodeNull()
    }

    private fun usedBytes(prefs: SharedPreferences): Int {
        var total = 0
        for ((key, value) in prefs.all) {
            total += entrySize(key, value as? String ?: continue)
        }
        return total
    }

    private fun entrySize(key: String, value: String): Int =
        key.toByteArray(Charsets.UTF_8).size + value.toByteArray(Charsets.UTF_8).size

    /** permanently deletes a plugin's store; call (on globalQueue, after its engine stopped) on uninstall */
    fun wipe(pluginId: String) {
        ApplicationLoader.applicationContext.deleteSharedPreferences(prefsName(pluginId))
    }
}

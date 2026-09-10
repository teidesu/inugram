package desu.inugram.helpers.plugins.api

import android.content.Context
import android.content.SharedPreferences
import desu.inugram.core.plugins.PluginInstalls
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.StorageListener
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader

/**
 * Per-plugin persistent key-value store backing `inu.kv` (rust: `api.rs`). One SharedPreferences
 * file per plugin (account-independent by design - the stock per-account DB is the wrong
 * granularity for plugin state), so reads are in-memory after first touch and writes persist
 * asynchronously. Values are strings only; total size is capped at [MAX_BYTES] per plugin.
 *
 * Results are PluginWire-tagged strings (`S`/`N`/`J`/`E`/`P`) - see
 * [desu.inugram.helpers.plugins.StorageListener.kv].
 *
 * Holds nothing of its own, and `SharedPreferences` is thread-safe, so this answers on whichever
 * thread asked. The quota check reads before it writes without a lock: a plugin racing itself can
 * overshoot [MAX_BYTES] by one write, which the next write then refuses.
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
    const val OP_HAS = 7
    const val OP_USAGE = 8

    fun listenerFor(plugin: Plugin): StorageListener = object : StorageListener {
        override fun kv(op: Int, key: String, value: String): String {
            if (!plugin.permissions.has("kv")) return PluginWire.encodeNotGranted("kv")
            return handleOp(plugin.id, op, key, value)
        }
    }

    // the id names a file, so it is re-checked where it becomes one: an id that reached here malformed would be a path fragment out of persisted state
    private fun prefsName(installId: String): String {
        require(PluginInstalls.isValidId(installId)) { "malformed install id" }
        return "inuplugin_kv_$installId"
    }

    private fun prefs(installId: String): SharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences(prefsName(installId), Context.MODE_PRIVATE)

    fun handleOp(installId: String, op: Int, key: String, value: String): String = try {
        val prefs = prefs(installId)
        when (op) {
            OP_GET -> prefs.getString(key, null)?.let { PluginWire.encodeString(it) } ?: PluginWire.encodeNull()
            OP_SET -> setEntries(prefs, mapOf(key to value))
            OP_DEL -> {
                prefs.edit().remove(key).apply()
                PluginWire.encodeNull()
            }
            OP_KEYS -> {
                val arr = JSONArray()
                for (k in prefs.all.keys) arr.put(k)
                PluginWire.encodeJson(arr.toString())
            }
            OP_CLEAR -> {
                prefs.edit().clear().apply()
                PluginWire.encodeNull()
            }
            OP_GET_ALL -> {
                val obj = JSONObject()
                for ((k, v) in prefs.all) obj.put(k, v as? String ?: continue)
                PluginWire.encodeJson(obj.toString())
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
            OP_HAS -> PluginWire.encodeJson(prefs.contains(key).toString())
            OP_USAGE -> PluginWire.encodeJson(usedBytes(prefs).toString())
            else -> PluginWire.encodeError("kv: unknown op $op")
        }
    } catch (e: Exception) {
        PluginWire.encodeError("kv: ${e.message}")
    }

    private fun setEntries(prefs: SharedPreferences, entries: Map<String, String>): String {
        var total = usedBytes(prefs)
        for ((key, value) in entries) {
            (prefs.getString(key, null))?.let { total -= entrySize(key, it) }
            total += entrySize(key, value)
        }
        if (total > MAX_BYTES) {
            return PluginWire.encodePluginError(
                "quota-exceeded",
                "kv: 1 MB per-plugin quota exceeded",
                usage = total.toLong(),
                quota = MAX_BYTES.toLong(),
            )
        }
        val editor = prefs.edit()
        for ((key, value) in entries) editor.putString(key, value)
        editor.apply()
        return PluginWire.encodeNull()
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

    fun wipe(installId: String) {
        if (!PluginInstalls.isValidId(installId)) return
        ApplicationLoader.applicationContext.deleteSharedPreferences(prefsName(installId))
    }
}

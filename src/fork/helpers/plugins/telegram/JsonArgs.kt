package desu.inugram.helpers.plugins.telegram

import desu.inugram.core.plugins.PluginWire.refuse
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.tl.TlHandles
import org.json.JSONObject
import org.telegram.messenger.MessagesController

internal open class JsonArgs(val json: JSONObject) {
    fun int(key: String): Int = if (json.isNull(key)) 0 else {
        json.optString(key).toIntOrNull() ?: refuse("invalid-argument", "$key: expected a 32-bit integer")
    }

    fun flag(key: String): Boolean = if (json.isNull(key)) false else {
        json.get(key) as? Boolean ?: refuse("invalid-argument", "$key: expected a boolean")
    }

    fun optedIn(key: String): Boolean = if (json.isNull(key)) true else flag(key)

    fun ints(key: String): List<Int> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return List(array.length()) { index ->
            array.optString(index).toIntOrNull() ?: refuse("invalid-argument", "$key: expected 32-bit integers")
        }
    }

    fun strings(key: String): List<String>? {
        val array = json.optJSONArray(key) ?: return null
        return List(array.length()) { index -> array.optString(index) }
    }
}

internal open class AccountCall(
    val session: PluginSession,
    val controller: MessagesController,
    val accountId: Int,
    val requestId: Long,
    json: JSONObject,
    private val settleApi: Int,
    private val what: String,
) : JsonArgs(json) {
    val handles: TlHandles get() = session.tl

    /** [release] runs on the stale path too: a reloaded plugin's obligation is still owed */
    fun answer(release: () -> Unit = {}, produce: () -> String) {
        EngineDispatch.settle(session, settleApi, requestId, what, release, produce)
    }
}

package desu.inugram.helpers

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_bots

// per-user key/value storage backed by the helper bot's webview cloud storage.
// bot-side must implement saveStorageValue / getStorageValues / deleteStorageValues / getStorageKeys.
object CloudStorageHelper {
    private val gson = Gson()
    private val mapType = object : TypeToken<HashMap<String, String>>() {}.type

    fun setItem(account: Int, key: String, value: String, callback: Utilities.Callback2<String, String>?) {
        invoke(account, "saveStorageValue", gson.toJson(mapOf("key" to key, "value" to value)), callback)
    }

    fun getItem(account: Int, key: String, callback: Utilities.Callback2<String, String>) {
        getItems(account, arrayOf(key)) { res, error ->
            callback.run(res?.get(key), error)
        }
    }

    fun getItems(account: Int, keys: Array<String>, callback: Utilities.Callback2<HashMap<String, String>, String>) {
        invoke(account, "getStorageValues", gson.toJson(mapOf("keys" to keys))) { res, error ->
            if (error != null) return@invoke callback.run(null, error)
            try {
                callback.run(gson.fromJson(res, mapType), null)
            } catch (e: Exception) {
                callback.run(null, e.message)
            }
        }
    }

    fun removeItems(account: Int, keys: Array<String>, callback: Utilities.Callback2<String, String>?) {
        invoke(account, "deleteStorageValues", gson.toJson(mapOf("keys" to keys)), callback)
    }

    fun getKeys(account: Int, callback: Utilities.Callback2<Array<String>, String>) {
        invoke(account, "getStorageKeys", "{}") { res, error ->
            if (error != null) return@invoke callback.run(null, error)
            try {
                callback.run(gson.fromJson(res, Array<String>::class.java), null)
            } catch (e: Exception) {
                callback.run(null, e.message)
            }
        }
    }

    private fun invoke(
        account: Int,
        method: String,
        data: String,
        callback: Utilities.Callback2<String, String>?,
    ) = invoke(account, method, data, callback, true)

    private fun invoke(
        account: Int,
        method: String,
        data: String,
        callback: Utilities.Callback2<String, String>?,
        allowResolve: Boolean,
    ) {
        val acc = AccountInstance.getInstance(account)
        val bot = acc.messagesController.getUser(WebAppHelper.HELPER_BOT_ID)
        if (bot == null) {
            if (allowResolve) {
                acc.messagesController.userNameResolver.resolve(WebAppHelper.HELPER_BOT_USERNAME) {
                    invoke(account, method, data, callback, false)
                }
            } else {
                callback?.run(null, "USER_NOT_FOUND")
            }
            return
        }
        val req = TL_bots.invokeWebViewCustomMethod()
        req.bot = acc.messagesController.getInputUser(bot)
        req.custom_method = method
        req.params = TLRPC.TL_dataJSON()
        req.params.data = data
        acc.connectionsManager.sendRequest(req) { res, error ->
            AndroidUtilities.runOnUIThread {
                if (callback == null) return@runOnUIThread
                when {
                    error != null -> callback.run(null, error.text)
                    res is TLRPC.TL_dataJSON -> callback.run(res.data, null)
                    else -> callback.run(null, null)
                }
            }
        }
    }
}

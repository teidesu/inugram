package desu.inugram.helpers.chat

import desu.inugram.InuConfig
import java.util.concurrent.ConcurrentHashMap

object WidgetAutoplayHelper {
    private val allowed = ConcurrentHashMap.newKeySet<String>()

    @JvmStatic
    fun mayRun(storageKey: String): Boolean = !InuConfig.DONT_AUTOPLAY_WIDGETS.value || storageKey in allowed

    @JvmStatic
    fun allow(storageKey: String) {
        allowed.add(storageKey)
    }
}

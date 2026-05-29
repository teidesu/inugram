package desu.inugram.helpers.dialogs

import desu.inugram.InuConfig
import org.telegram.messenger.UserConfig

object AccountOrderHelper {
    @JvmStatic
    fun sort(accounts: MutableList<Int>) {
        accounts.sortWith(compareBy { UserConfig.getInstance(it).loginTime })
        val csv = InuConfig.ACCOUNT_ORDER.value
        if (csv.isEmpty()) return
        val orderIndex = csv.split(',')
            .mapNotNull { it.toIntOrNull() }
            .withIndex()
            .associate { (i, v) -> v to i }
        accounts.sortWith(compareBy { orderIndex[it] ?: (Int.MAX_VALUE - 1) })
    }

    @JvmStatic
    fun setOrder(accounts: List<Int>) {
        InuConfig.ACCOUNT_ORDER.value = accounts.joinToString(",")
    }

    /**
     * Persist a reorder that only saw the visible accounts (the drawer hides
     * passcode-locked ones). Hidden accounts stay anchored right after the
     * visible account they previously followed, so locking an account doesn't
     * scramble its stored position. Idempotent.
     */
    fun setVisibleOrder(visibleOrder: List<Int>) {
        val all = mutableListOf<Int>()
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (UserConfig.getInstance(a).isClientActivated) all.add(a)
        }
        sort(all)

        val visible = visibleOrder.toHashSet()
        val hiddenByAnchor = LinkedHashMap<Int?, MutableList<Int>>()
        var lastVisible: Int? = null
        for (acc in all) {
            if (acc in visible) lastVisible = acc
            else hiddenByAnchor.getOrPut(lastVisible) { mutableListOf() }.add(acc)
        }

        val result = mutableListOf<Int>()
        hiddenByAnchor[null]?.let { result.addAll(it) }
        for (acc in visibleOrder) {
            result.add(acc)
            hiddenByAnchor[acc]?.let { result.addAll(it) }
        }
        setOrder(result)
    }
}

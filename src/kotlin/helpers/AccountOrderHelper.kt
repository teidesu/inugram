package desu.inugram.helpers

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
}

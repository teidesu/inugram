package desu.inugram.core.plugins

/**
 * A navigation-stack entry, compared by value in [ScreenStack.diff]. Activity recreation
 * creates new fragments for the same screens and must not emit navigation events.
 * Plain data also avoids retaining destroyed fragments.
 *
 * [dialogId] and [topicId] use 0 for absent values, avoiding unequal null/0 representations.
 */
data class ScreenRef(
    val type: String,
    val dialogId: Long = 0,
    val topicId: Int = 0,
    val accountId: Int = 0,
)

enum class ScreenChangeAction { PUSH, POP, REPLACE }

/**
 * Derives navigation changes because `INavigationLayout.setFragmentStackChangedListener`
 * provides no payload. An unchanged top emits nothing, including activity rebuilds and
 * removal of buried fragments.
 *
 * An old stack that prefixes the new one is a push, including the first screen. A new stack
 * that prefixes the old one is a pop. Equal depth means replace; other changes use the action
 * matching their depth change.
 */
object ScreenStack {
    fun diff(previous: List<ScreenRef>, next: List<ScreenRef>): ScreenChangeAction? {
        if (previous.lastOrNull() == next.lastOrNull()) return null
        if (isPrefix(previous, next)) return ScreenChangeAction.PUSH
        if (isPrefix(next, previous)) return ScreenChangeAction.POP
        if (previous.size == next.size) return ScreenChangeAction.REPLACE
        return if (next.size > previous.size) ScreenChangeAction.PUSH else ScreenChangeAction.POP
    }

    private fun isPrefix(candidate: List<ScreenRef>, of: List<ScreenRef>): Boolean {
        if (candidate.size > of.size) return false
        for (i in candidate.indices) {
            if (candidate[i] != of[i]) return false
        }
        return true
    }
}

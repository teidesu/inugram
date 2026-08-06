package desu.inugram.core.plugins

/**
 * One entry of the navigation stack, and the *identity* [ScreenStack.diff] runs on.
 *
 * Value identity rather than the fragment instance's: the stack lives on the activity, so one
 * destroyed and recreated comes back holding different instances describing the same screens, and
 * `common.d.ts` promises a rebuild onto the same screen fires nothing. It also keeps the previous
 * stack as plain data, so nothing here can pin a destroyed fragment alive.
 *
 * [dialogId]/[topicId] are 0 rather than null: a nullable would only let two equal screens
 * compare unequal.
 */
data class ScreenRef(
    val type: String,
    val dialogId: Long = 0,
    val topicId: Int = 0,
    val accountId: Int = 0,
)

enum class ScreenChangeAction { PUSH, POP, REPLACE }

/**
 * `INavigationLayout.setFragmentStackChangedListener` carries no payload, so this diff is the
 * only thing that knows what happened.
 *
 * An unchanged top is **nothing at all** - that is the dedup a rebuild and a
 * `removeFragmentFromStack` on something buried both land in. Otherwise: old a prefix of new is
 * a push (empty-to-nonempty included), new a prefix of old is a pop, equal depth is a replace,
 * and anything else takes the label its depth implies, `action` being a closed set.
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

package desu.inugram.helpers

import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.browser.Browser
import org.telegram.ui.ActionBar.BaseFragment


sealed class SearchTopAction {
    abstract val label: CharSequence
    abstract fun execute(fragment: BaseFragment)

    class Username(val name: String) : SearchTopAction() {
        override val label: CharSequence
            get() = LocaleController.formatString(R.string.InuSearchOpenUsername, "@$name")

        override fun execute(fragment: BaseFragment) {
            MessagesController.getInstance(fragment.currentAccount)
                .openByUserName(name, fragment, 1)
        }
    }

    class Link(private val display: String, private val url: String) : SearchTopAction() {
        override val label: CharSequence
            get() = LocaleController.formatString(R.string.InuSearchOpenLink, display)

        override fun execute(fragment: BaseFragment) {
            val activity = fragment.parentActivity ?: return
            Browser.openUrl(activity, url)
        }
    }

    companion object {
        const val VIEW_TYPE = 100;
        private val USERNAME = Regex("^@?([A-Za-z0-9_]{5,32})$")
        private val TG_LINK = Regex("^tg://\\S+$")
        private val TME_LINK =
            Regex("^(https?://)?(t\\.me|telegram\\.me|telegram\\.dog)/\\S+$", RegexOption.IGNORE_CASE)

        @JvmStatic
        fun parse(query: String?): SearchTopAction? {
            if (query.isNullOrEmpty()) return null
            USERNAME.matchEntire(query)?.let { return Username(it.groupValues[1]) }
            if (TG_LINK.matches(query)) return Link(query, query)
            TME_LINK.matchEntire(query)?.let {
                val scheme = it.groupValues[1]
                val url = if (scheme.isEmpty()) "https://$query" else query
                return Link(query, url)
            }
            return null
        }
    }
}
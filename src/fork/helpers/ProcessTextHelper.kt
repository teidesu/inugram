package desu.inugram.helpers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.view.Menu
import android.view.MenuItem
import desu.inugram.InuConfig
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.browser.Browser
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.web.SearchEngine

object ProcessTextHelper {
    private const val ORDER_START = 100
    private const val WEB_SEARCH_KEY = "web_search"

    class Action(val key: String, val label: CharSequence, val intent: Intent) {
        fun loadIcon(): Drawable? {
            val pm = ApplicationLoader.applicationContext.packageManager
            return try {
                intent.component?.let { pm.getActivityIcon(it) }
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    @JvmStatic
    fun addItems(menu: Menu) {
        if (!InuConfig.SELECTION_PROCESS_TEXT.value) return
        getOrderedActions().filter { it.second }.forEachIndexed { i, (action, _) ->
            menu.add(R.id.inu_process_text, Menu.NONE, ORDER_START + i, action.label).setIntent(action.intent)
        }
    }

    @JvmStatic
    fun launch(context: Context, item: MenuItem, text: CharSequence?) {
        if (item.groupId != R.id.inu_process_text) return
        val intent = item.intent ?: return
        if (text.isNullOrEmpty()) return
        if (intent.action == Intent.ACTION_WEB_SEARCH) {
            val url = SearchEngine.getCurrent().getSearchURL(text.toString())
            if (url != null) {
                Browser.openUrl(context, url)
                return
            }
        } else {
            try {
                context.startActivity(Intent(intent).putExtra(Intent.EXTRA_PROCESS_TEXT, text.toString()))
                return
            } catch (e: Exception) {
                FileLog.e(e)
            }
        }
        BulletinFactory.global().createErrorBulletin(LocaleController.getString(R.string.UnknownError)).show()
    }

    fun getOrderedActions(): List<Pair<Action, Boolean>> {
        val available = getAvailableActions()
        val saved = InuConfig.SELECTION_PROCESS_TEXT_ITEMS.value ?: return available.map { it to true }
        val byKey = available.associateBy { it.key }
        val savedKeys = saved.mapTo(HashSet()) { it.key }
        return saved.mapNotNull { e -> byKey[e.key]?.let { it to e.enabled } } +
            available.filter { it.key !in savedKeys }.map { it to false }
    }

    private fun getAvailableActions(): List<Action> {
        val ctx = ApplicationLoader.applicationContext
        val pm = ctx.packageManager
        val out = arrayListOf(
            Action(WEB_SEARCH_KEY, LocaleController.getString(R.string.InuWebSearch), Intent(Intent.ACTION_WEB_SEARCH)),
        )
        for (info in pm.queryIntentActivities(createIntent(), 0)) {
            if (!isSupported(ctx, info)) continue
            val activity = info.activityInfo
            out.add(
                Action(
                    "${activity.packageName}/${activity.name}",
                    info.loadLabel(pm),
                    createIntent().setClassName(activity.packageName, activity.name),
                )
            )
        }
        return out
    }

    private fun createIntent() = Intent(Intent.ACTION_PROCESS_TEXT)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)

    private fun isSupported(ctx: Context, info: ResolveInfo): Boolean {
        val activity = info.activityInfo
        if (activity.packageName == ctx.packageName) return true
        if (!activity.exported) return false
        return activity.permission == null ||
            ctx.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED
    }
}

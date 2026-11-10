package desu.inugram.ui.settings

import android.content.Context
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.update.UpdateHelper
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.RichMessageLayout
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class WhatsNewActivity : SettingsPageActivity() {
    private var releaseViews: List<View>? = null
    private var failed = false

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuWhatsNew)

    override fun onFragmentCreate(): Boolean {
        UpdateHelper.markWhatsNewSeen()
        return super.onFragmentCreate()
    }

    override fun createView(context: Context): View {
        releaseViews = null
        failed = false
        UpdateHelper.loadReleases(20) { releases ->
            failed = releases == null
            releaseViews = releases?.map {
                RichMessageLayout.PreviewView(context, currentAccount, null).apply {
                    set(it.changelog)
                    setPadding(dp(23f), dp(15f), dp(23f), dp(8f))
                }
            }
            listView?.adapter?.update(true)
        }
        return super.createView(context)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asCheck(
                TOGGLE_SHOW_ON_UPDATE,
                LocaleController.getString(R.string.InuWhatsNewOnUpdate),
            ).setChecked(InuConfig.WHATS_NEW_ON_UPDATE.value)
        )
        items.add(UItem.asShadow(null))

        val views = releaseViews
        if (views == null) {
            items.add(UItem.asShadow(LocaleController.getString(if (failed) R.string.ErrorOccurred else R.string.Loading)))
            return
        }
        for (view in views) {
            items.add(UItem.asCustom(view))
            items.add(UItem.asShadow(null))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == TOGGLE_SHOW_ON_UPDATE) {
            (view as? TextCheckCell)?.isChecked = InuConfig.WHATS_NEW_ON_UPDATE.toggle()
        }
    }

    companion object {
        private val TOGGLE_SHOW_ON_UPDATE = InuUtils.generateId()
    }
}

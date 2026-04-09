package desu.inugram.ui

import android.content.Context
import android.view.View
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalFragment
import kotlin.system.exitProcess

abstract class InuSettingsPageActivity : UniversalFragment() {
    override fun isSupportEdgeToEdge(): Boolean = true

    override fun createView(context: Context): View {
        return super.createView(context).also {
            listView.setSections()
            actionBar.setAdaptiveBackground(listView)
            listView.clipToPadding = false
        }
    }

    override fun onInsets(left: Int, top: Int, right: Int, bottom: Int) {
        val lv = listView ?: return
        lv.setPadding(lv.paddingLeft, lv.paddingTop, lv.paddingRight, bottom)
    }

    protected fun showRestartBulletin() {
        BulletinFactory.of(this)
            .createSimpleBulletin(
                R.raw.chats_infotip,
                LocaleController.getString(R.string.InuRestartRequired),
                LocaleController.getString(R.string.InuRestartNow)
            ) {
                val activity = parentActivity ?: return@createSimpleBulletin
                val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
                activity.finishAffinity()
                activity.startActivity(intent)
                exitProcess(0)
            }
            .show()
    }

    protected fun mkTwoLineCheckItem(id: Int, textRes: Int, infoRes: Int, checked: Boolean): UItem {
        val text = LocaleController.getString(textRes)
        val subtext = if (infoRes == 0) null else LocaleController.getString(infoRes)
        return UItem.asButtonCheck(id, text, subtext).also {
            it.checked = checked
            it.bind = Utilities.Callback { view ->
                (view as? NotificationsCheckCell)?.setTextAndValueAndCheck(
                    text,
                    subtext,
                    checked,
                    0,
                    subtext != null,
                    true
                )
                (view as? NotificationsCheckCell)?.setDrawLine(false)
            }
        }
    }

    protected fun mkSplitCheckItem(id: Int, textRes: Int, infoRes: Int, checked: Boolean): UItem {
        val text = LocaleController.getString(textRes)
        val subtext = if (infoRes == 0) null else LocaleController.getString(infoRes)
        return UItem.asButtonCheck(id, text, subtext).also {
            it.checked = checked
            it.bind = Utilities.Callback { view ->
                (view as? NotificationsCheckCell)?.setTextAndValueAndCheck(
                    text,
                    subtext,
                    checked,
                    0,
                    subtext != null,
                    true
                )
                (view as? NotificationsCheckCell)?.setDrawLine(true)
            }
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float) = false
}

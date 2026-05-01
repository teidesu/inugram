package desu.inugram.ui

import android.content.Context
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.View
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalFragment
import org.telegram.ui.FilterCreateActivity.NewSpan
import java.util.Optional
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

    protected fun mkTwoLineCheckItem(id: Int, textRes: Int, infoRes: Int, checked: Boolean, experimental: Boolean = false): UItem {
        val rawText = LocaleController.getString(textRes)
        val text = if (experimental) taggedExperimental(rawText) else rawText
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

    protected fun mkSplitCheckItem(id: Int, textRes: Int, infoRes: Int, checked: Boolean, experimental: Boolean = false): UItem {
        val rawText = LocaleController.getString(textRes)
        val text = if (experimental) taggedExperimental(rawText) else rawText
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

    protected fun taggedExperimental(string: CharSequence): CharSequence {
        val tag = LocaleController.getString(R.string.InuExperimental)

        val tagSpan = NewSpan(false)
        tagSpan.setColor(Theme.getColor(Theme.key_chat_serviceBackground))
        tagSpan.inu_setFgColor(Optional.of(Theme.getColor(Theme.key_chat_serviceText)))
        tagSpan.setText(tag)

        val tagText = SpannableString(tag)
        tagText.setSpan(tagSpan, 0, tagText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val text = SpannableStringBuilder(string)
        text.append("  ")
        text.append(tagText)
        return text
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float) = false
}

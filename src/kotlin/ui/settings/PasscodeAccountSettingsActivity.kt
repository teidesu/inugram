package desu.inugram.ui.settings

import android.app.Dialog
import android.text.TextPaint
import android.view.View
import android.widget.TextView
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.PasscodeHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ContactsController
import org.telegram.messenger.Emoji
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.PasscodeActivity

class PasscodeAccountSettingsActivity(private val account: Int) : SettingsPageActivity() {

    override fun getTitle(): CharSequence {
        val user = UserConfig.getInstance(account).currentUser ?: return ""
        val name = ContactsController.formatName(user.first_name, user.last_name)
        val fm = TextPaint().apply { textSize = AndroidUtilities.dp(20f).toFloat() }.fontMetricsInt
        return Emoji.replaceEmoji(name, fm, false)
    }

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val hasCode = PasscodeHelper.hasPasscodeForAccount(account)

        items.add(
            UItem.asButton(
                EDIT,
                LocaleController.getString(if (hasCode) R.string.InuPasscodeEdit else R.string.InuPasscodeSet)
            )
        )
        if (hasCode) {
            items.add(UItem.asButton(REMOVE, LocaleController.getString(R.string.InuPasscodeRemove)).red())
        }
        items.add(UItem.asShadow(null))

        if (hasCode) {
            items.add(
                UItem.asCheck(HIDE, LocaleController.getString(R.string.InuPasscodeHideAccount))
                    .setChecked(PasscodeHelper.isAccountHidden(account))
            )
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuPasscodeHideAccountInfo)))
        }

        items.add(
            UItem.asCheck(ALLOW_PANIC, LocaleController.getString(R.string.InuPasscodeAllowPanic))
                .setChecked(PasscodeHelper.isAccountAllowPanic(account))
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuPasscodeAllowPanicInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            EDIT -> {
                val frag = PasscodeActivity(PasscodeActivity.TYPE_SETUP_CODE)
                frag.inu_account = account
                presentFragment(frag)
            }
            REMOVE -> confirmRemove()
            HIDE -> {
                val hide = !PasscodeHelper.isAccountHidden(account)
                PasscodeHelper.setAccountHidden(account, hide)
                (view as? TextCheckCell)?.isChecked = hide
                postNotificationForAllAccounts(NotificationCenter.mainUserInfoChanged)
            }
            ALLOW_PANIC -> {
                val allow = !PasscodeHelper.isAccountAllowPanic(account)
                PasscodeHelper.setAccountAllowPanic(account, allow)
                (view as? TextCheckCell)?.isChecked = allow
            }
        }
    }

    private fun confirmRemove() {
        val ctx = parentActivity ?: return
        val dialog = AlertDialog.Builder(ctx, resourceProvider)
            .setTitle(LocaleController.getString(R.string.InuPasscodeRemove))
            .setMessage(LocaleController.getString(R.string.InuPasscodeRemoveConfirm))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.DisablePasscodeTurnOff)) { _, _ ->
                val wasHidden = PasscodeHelper.isAccountHidden(account)
                PasscodeHelper.removeForAccount(account)
                if (wasHidden) postNotificationForAllAccounts(NotificationCenter.mainUserInfoChanged)
                listView.adapter.update(true)
            }
            .create()
        showDialog(dialog)
        (dialog.getButton(Dialog.BUTTON_POSITIVE) as? TextView)
            ?.setTextColor(getThemedColor(Theme.key_text_RedBold))
    }

    companion object {
        private val EDIT = InuUtils.generateId()
        private val REMOVE = InuUtils.generateId()
        private val HIDE = InuUtils.generateId()
        private val ALLOW_PANIC = InuUtils.generateId()
    }
}

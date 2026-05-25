package desu.inugram.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.security.ParanoiaHelper
import desu.inugram.ui.showInputDialog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.GroupCreateActivity
import org.telegram.ui.Stories.recorder.ButtonWithCounterView

class ParanoiaActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuParanoiaMode)

    override fun createView(context: Context): View {
        val view = super.createView(context)
        attachStickyButton(view, buildEnableButton(context))
        return view
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuParanoiaModeInfo)))

        items.add(
            UItem.asButton(
                SET_CODE,
                R.drawable.msg_permissions,
                LocaleController.getString(
                    if (ParanoiaHelper.hasExitCode()) R.string.InuParanoiaExitCodeChange
                    else R.string.InuParanoiaExitCodeSet
                )
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuParanoiaExitCodeInfo)))

        val whitelist = ParanoiaHelper.isWhitelistMode
        val count = ParanoiaHelper.getActiveList(currentAccount).size
        items.add(
            UItem.asButton(
                FILTERING_MODE,
                R.drawable.inu_tabler_filter,
                LocaleController.getString(R.string.InuParanoiaFilteringMode),
                filteringModeLabel(whitelist),
            )
        )
        items.add(
            UItem.asButton(
                SELECT_CHATS,
                R.drawable.menu_hide_gift,
                LocaleController.getString(
                    if (whitelist) R.string.InuParanoiaSelectShow
                    else R.string.InuParanoiaSelect
                ),
                LocaleController.formatString(R.string.InuParanoiaCount, count)
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuParanoiaSelectInfo)))

        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_OTHER_ACCOUNTS,
                LocaleController.getString(R.string.InuParanoiaHideOtherAccounts)
            ).setChecked(ParanoiaHelper.hideOtherAccounts)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_NOTIFICATIONS,
                LocaleController.getString(R.string.InuParanoiaDisableNotifications)
            ).setChecked(ParanoiaHelper.disableNotifications)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_SETTINGS,
                LocaleController.getString(R.string.InuHideInugramSettings)
            ).setChecked(ParanoiaHelper.hideSettings)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISGUISE,
                LocaleController.getString(R.string.InuParanoiaDisguise)
            ).setChecked(ParanoiaHelper.disguiseIcon)
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuParanoiaDisguiseInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            SELECT_CHATS -> openPicker()
            SET_CODE -> showCodeDialog()
            FILTERING_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuParanoiaFilteringModeBlacklist),
                    LocaleController.getString(R.string.InuParanoiaFilteringModeWhitelist),
                ),
                if (ParanoiaHelper.isWhitelistMode) 1 else 0,
            ) { which ->
                ParanoiaHelper.isWhitelistMode = which == 1
                listView.adapter.update(true)
            }
            TOGGLE_DISGUISE -> toggleCheck(view, ParanoiaHelper.disguiseIcon) { ParanoiaHelper.disguiseIcon = it }
            TOGGLE_HIDE_OTHER_ACCOUNTS -> toggleCheck(view, ParanoiaHelper.hideOtherAccounts) { ParanoiaHelper.hideOtherAccounts = it }
            TOGGLE_DISABLE_NOTIFICATIONS -> toggleCheck(view, ParanoiaHelper.disableNotifications) { ParanoiaHelper.disableNotifications = it }
            TOGGLE_HIDE_SETTINGS -> toggleCheck(view, ParanoiaHelper.hideSettings) { ParanoiaHelper.hideSettings = it }
        }
    }

    private inline fun toggleCheck(view: View, current: Boolean, set: (Boolean) -> Unit) {
        val new = !current
        set(new)
        (view as? TextCheckCell)?.isChecked = new
    }

    private fun buildEnableButton(ctx: Context): View =
        ButtonWithCounterView(ctx, true, resourceProvider).setRound().apply {
            setText(LocaleController.getString(R.string.InuParanoiaEnable), false)
            setOnClickListener { tryEnableParanoia() }
        }

    private fun openPicker() {
        val args = Bundle().apply {
            if (ParanoiaHelper.isWhitelistMode) {
                putBoolean("isAlwaysShare", true)
            } else {
                putBoolean("isNeverShare", true)
            }
            putInt("chatAddType", 2)
        }
        val fragment = GroupCreateActivity(args)
        fragment.select(ArrayList(ParanoiaHelper.getActiveList(currentAccount)), false, false)
        fragment.setDelegate { _, _, ids ->
            ParanoiaHelper.setActiveList(currentAccount, ids)
            listView.adapter.update(true)
        }
        presentFragment(fragment)
    }

    private fun showCodeDialog() {
        showInputDialog(
            fragment = this,
            title = LocaleController.getString(R.string.InuParanoiaExitCode),
            hint = LocaleController.getString(R.string.InuParanoiaExitCodeHint),
        ) { code ->
            if (code.isEmpty()) {
                BulletinFactory.of(this)
                    .createErrorBulletin(LocaleController.getString(R.string.InuParanoiaExitCodeEmpty))
                    .show()
                return@showInputDialog false
            }
            ParanoiaHelper.setExitCode(code)
            listView.adapter.update(true)
            BulletinFactory.of(this)
                .createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.InuParanoiaExitCodeSaved))
                .show()
            true
        }
    }

    private fun tryEnableParanoia() {
        if (!ParanoiaHelper.hasExitCode() || ParanoiaHelper.getActiveList(currentAccount).isEmpty()) {
            BulletinFactory.of(this)
                .createErrorBulletin(LocaleController.getString(R.string.InuParanoiaNeedSetup))
                .show()
            return
        }
        val ctx = parentActivity ?: return
        val dialog = AlertDialog.Builder(ctx, resourceProvider)
            .setTitle(LocaleController.getString(R.string.InuParanoiaEnable))
            .setMessage(LocaleController.getString(R.string.InuParanoiaEnableConfirm))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.InuParanoiaEnable)) { _, _ ->
                ParanoiaHelper.enableParanoia(this)
            }
            .create()
        showDialog(dialog)
    }

    companion object {
        private val SELECT_CHATS = InuUtils.generateId()
        private val SET_CODE = InuUtils.generateId()
        private val FILTERING_MODE = InuUtils.generateId()
        private val TOGGLE_DISGUISE = InuUtils.generateId()
        private val TOGGLE_HIDE_OTHER_ACCOUNTS = InuUtils.generateId()
        private val TOGGLE_DISABLE_NOTIFICATIONS = InuUtils.generateId()
        private val TOGGLE_HIDE_SETTINGS = InuUtils.generateId()

        private fun filteringModeLabel(whitelist: Boolean): String = LocaleController.getString(
            if (whitelist) R.string.InuParanoiaFilteringModeWhitelist
            else R.string.InuParanoiaFilteringModeBlacklist
        )
    }
}

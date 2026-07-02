package desu.inugram.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ShortcutHelper
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

        val count = ParanoiaHelper.getHidden(currentAccount).size
        val whitelist = ParanoiaHelper.whitelist
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

        items.add(
            UItem.asCheck(
                TOGGLE_WHITELIST,
                LocaleController.getString(R.string.InuParanoiaWhitelist)
            ).setChecked(whitelist)
        )
        items.add(
            UItem.asButton(
                SELECT_CHATS,
                R.drawable.menu_hide_gift,
                LocaleController.getString(
                    if (whitelist) R.string.InuParanoiaSelectWhitelist
                    else R.string.InuParanoiaSelect
                ),
                LocaleController.formatString(R.string.InuParanoiaCount, count)
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuParanoiaSelectInfo)))

        items.add(
            UItem.asCheck(
                TOGGLE_LAUNCHER_SHORTCUT,
                LocaleController.getString(R.string.InuParanoiaLauncherShortcut)
            ).setChecked(ParanoiaHelper.launcherShortcut)
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuParanoiaLauncherShortcutInfo)))

        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_OTHER_ACCOUNTS,
                LocaleController.getString(R.string.InuParanoiaHideOtherAccounts)
            ).setChecked(ParanoiaHelper.hideOtherAccounts)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_FOLDERS,
                LocaleController.getString(R.string.InuParanoiaHideFolders)
            ).setChecked(ParanoiaHelper.hideFolders)
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
            TOGGLE_WHITELIST -> {
                toggleCheck(view, ParanoiaHelper.whitelist) { ParanoiaHelper.whitelist = it }
                listView.adapter.update(true)
            }

            TOGGLE_LAUNCHER_SHORTCUT -> {
                if (!ParanoiaHelper.launcherShortcut && !ParanoiaHelper.canUseLauncherShortcut()) {
                    BulletinFactory.of(this)
                        .createErrorBulletin(LocaleController.getString(R.string.InuParanoiaNeedSetup))
                        .show()
                    return
                }
                toggleCheck(view, ParanoiaHelper.launcherShortcut) { ParanoiaHelper.launcherShortcut = it }
                syncShortcut()
            }

            TOGGLE_DISGUISE -> toggleCheck(view, ParanoiaHelper.disguiseIcon) { ParanoiaHelper.disguiseIcon = it }
            TOGGLE_HIDE_OTHER_ACCOUNTS -> toggleCheck(view, ParanoiaHelper.hideOtherAccounts) { ParanoiaHelper.hideOtherAccounts = it }
            TOGGLE_HIDE_FOLDERS -> toggleCheck(view, ParanoiaHelper.hideFolders) { ParanoiaHelper.hideFolders = it }
            TOGGLE_DISABLE_NOTIFICATIONS -> toggleCheck(view, ParanoiaHelper.disableNotifications) { ParanoiaHelper.disableNotifications = it }
            TOGGLE_HIDE_SETTINGS -> toggleCheck(view, ParanoiaHelper.hideSettings) { ParanoiaHelper.hideSettings = it }
        }
    }

    private fun syncShortcut() {
        parentActivity?.let { ShortcutHelper.sync(it) }
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
            putBoolean("isNeverShare", true)
            putInt("chatAddType", 2)
            putBoolean("inu_allowChannels", true)
        }
        val fragment = GroupCreateActivity(args)
        fragment.select(ArrayList(ParanoiaHelper.getHidden(currentAccount)), false, false)
        fragment.setDelegate { _, _, ids ->
            ParanoiaHelper.setHidden(currentAccount, ids)
            listView.adapter.update(true)
            syncShortcut()
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
            syncShortcut()
            BulletinFactory.of(this)
                .createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.InuParanoiaExitCodeSaved))
                .show()
            true
        }
    }

    private fun tryEnableParanoia() {
        if (!ParanoiaHelper.hasExitCode() || ParanoiaHelper.getHidden(currentAccount).isEmpty()) {
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
        private val TOGGLE_WHITELIST = InuUtils.generateId()
        private val TOGGLE_LAUNCHER_SHORTCUT = InuUtils.generateId()
        private val TOGGLE_DISGUISE = InuUtils.generateId()
        private val TOGGLE_HIDE_OTHER_ACCOUNTS = InuUtils.generateId()
        private val TOGGLE_HIDE_FOLDERS = InuUtils.generateId()
        private val TOGGLE_DISABLE_NOTIFICATIONS = InuUtils.generateId()
        private val TOGGLE_HIDE_SETTINGS = InuUtils.generateId()
    }
}

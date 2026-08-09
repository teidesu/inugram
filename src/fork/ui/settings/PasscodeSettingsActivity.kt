package desu.inugram.ui.settings

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.security.PasscodeHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ContactsController
import org.telegram.messenger.Emoji
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Cells.UserCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RLottieImageView
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.URLSpanNoUnderline
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.PasscodeActivity
import org.telegram.ui.Stories.recorder.ButtonWithCounterView

class PasscodeSettingsActivity : SettingsPageActivity() {

    private var passcodeSet = false

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuPasscode)

    override fun onResume() {
        super.onResume()
        passcodeSet = SharedConfig.passcodeHash.isNotEmpty()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        if (!passcodeSet) {
            val view = emptyState ?: buildEmptyState(parentActivity ?: return).also { emptyState = it }
            items.add(UItem.asFullscreenCustom(view, AndroidUtilities.navigationBarHeight))
            return
        }
        items.add(
            UItem.asButton(
                OPEN_STOCK,
                R.drawable.msg_permissions,
                LocaleController.getString(R.string.InuPasscodeOpenStockSettings)
            )
        )
        items.add(UItem.asShadow(null))
        items.add(
            UItem.asCheck(SHOW_IN_SETTINGS, LocaleController.getString(R.string.InuPasscodeShowInSettings))
                .setChecked(!PasscodeHelper.isSettingsHidden())
        )
        items.add(UItem.asShadow(buildSettingsLinkInfo()))

        items.add(UItem.asHeader(LocaleController.getString(R.string.Account)))
        val ctx = parentActivity ?: return
        val accounts = (0 until UserConfig.MAX_ACCOUNT_COUNT).mapNotNull { a ->
            UserConfig.getInstance(a).currentUser?.let { a to it }
        }
        accounts.forEachIndexed { idx, (a, _) ->
            items.add(UItem.asCustom(ACCOUNT_ROW_BASE + a, buildAccountCell(ctx, a, idx < accounts.lastIndex)))
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuPasscodeAccountsInfo)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuPasscodePanic)))
        items.add(
            mkSubPageButton(
                SET_PANIC,
                LocaleController.getString(
                    if (PasscodeHelper.hasPanicCode()) R.string.InuPasscodePanicEdit
                    else R.string.InuPasscodePanicSet
                )
            )
        )
        if (PasscodeHelper.hasPanicCode()) {
            items.add(
                UItem.asButton(REMOVE_PANIC, LocaleController.getString(R.string.InuPasscodePanicRemove)).red()
            )
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuPasscodePanicInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val id = item.id
        if (id >= ACCOUNT_ROW_BASE && id < ACCOUNT_ROW_BASE + UserConfig.MAX_ACCOUNT_COUNT) {
            presentFragment(PasscodeAccountSettingsActivity(id - ACCOUNT_ROW_BASE))
            return
        }
        when (id) {
            OPEN_STOCK -> presentFragment(PasscodeActivity.determineOpenFragment())
            SHOW_IN_SETTINGS -> {
                PasscodeHelper.setSettingsHidden(!PasscodeHelper.isSettingsHidden())
                (view as? TextCheckCell)?.isChecked = !PasscodeHelper.isSettingsHidden()
            }

            SET_PANIC -> {
                val frag = PasscodeActivity(PasscodeActivity.TYPE_SETUP_CODE)
                frag.inu_account = PasscodeHelper.PANIC_ACCOUNT
                presentFragment(frag)
            }

            REMOVE_PANIC -> {
                val ctx = parentActivity ?: return
                val dialog = AlertDialog.Builder(ctx, resourceProvider)
                    .setTitle(LocaleController.getString(R.string.InuPasscodePanicRemove))
                    .setMessage(LocaleController.getString(R.string.InuPasscodePanicRemoveConfirm))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .setPositiveButton(LocaleController.getString(R.string.DisablePasscodeTurnOff)) { _, _ ->
                        PasscodeHelper.removeForAccount(PasscodeHelper.PANIC_ACCOUNT)
                        listView.adapter.update(true)
                    }
                    .create()
                showDialog(dialog)
                (dialog.getButton(Dialog.BUTTON_POSITIVE) as? TextView)
                    ?.setTextColor(getThemedColor(Theme.key_text_RedBold))
            }
        }
    }

    private fun buildSettingsLinkInfo(): CharSequence {
        val link = "https://t.me/inusettings/${PasscodeHelper.getSettingsKey()}"
        val info = SpannableStringBuilder(
            AndroidUtilities.replaceTags(LocaleController.getString(R.string.InuPasscodeShowInSettingsInfo))
        )
        info.append("\n").append(link)
        info.setSpan(object : URLSpanNoUnderline(null) {
            override fun onClick(view: View) {
                val cm = ApplicationLoader.applicationContext
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("label", link))
                BulletinFactory.of(this@PasscodeSettingsActivity).createCopyLinkBulletin().show()
            }
        }, info.length - link.length, info.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return info
    }

    private val nameFm by lazy {
        TextPaint().apply { textSize = AndroidUtilities.dp(16f).toFloat() }.fontMetricsInt
    }
    private val accountCells = HashMap<Int, UserCell>()
    private var emptyState: View? = null

    private fun buildAccountCell(ctx: Context, account: Int, divider: Boolean): UserCell {
        val cell = accountCells.getOrPut(account) { UserCell(ctx, 6, 0, false, false, resourceProvider) }
        val user = UserConfig.getInstance(account).currentUser
        val rawName = ContactsController.formatName(user?.first_name, user?.last_name)
        val name = Emoji.replaceEmoji(rawName, nameFm, false)
        val status = LocaleController.getString(
            when {
                PasscodeHelper.isAccountHiddenByPasscode(account) -> R.string.InuPasscodeStateHidden
                PasscodeHelper.hasPasscodeForAccount(account) -> R.string.InuPasscodeStateSet
                else -> R.string.InuPasscodeStateNone
            }
        )
        cell.setData(user, name, status, 0, divider)
        return cell
    }

    private fun buildEmptyState(ctx: Context): View {
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val lottie = RLottieImageView(ctx).apply {
            setAutoRepeat(false)
            setAnimation(R.raw.utyan_passcode, 120, 120)
            playAnimation()
        }
        val title = TextView(ctx).apply {
            text = LocaleController.getString(R.string.InuPasscodeNeeded)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText))
        }
        val button = ButtonWithCounterView(ctx, resourceProvider).apply {
            setText(LocaleController.getString(R.string.NotificationsPermissionSettings), false)
            setRound()
            setOnClickListener { presentFragment(PasscodeActivity.determineOpenFragment()) }
        }
        layout.addView(lottie, LayoutHelper.createLinear(120, 120, Gravity.CENTER_HORIZONTAL))
        layout.addView(
            title,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 8, 24, 24)
        )
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_HORIZONTAL, 24, 0, 24, 0))
        return layout
    }

    companion object {
        private val OPEN_STOCK = InuUtils.generateId()
        private val SHOW_IN_SETTINGS = InuUtils.generateId()
        private val SET_PANIC = InuUtils.generateId()
        private val REMOVE_PANIC = InuUtils.generateId()
        private const val ACCOUNT_ROW_BASE = 10_000
    }
}

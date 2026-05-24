package desu.inugram.ui.settings

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.UpdateHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.messenger.browser.Browser
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.IUpdateLayout
import org.telegram.ui.UpdateLayoutWrapper

class AboutActivity : SettingsPageActivity(), NotificationCenter.NotificationCenterDelegate {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAbout)

    private var updateLayout: IUpdateLayout? = null
    private var updateWrapper: UpdateLayoutWrapper? = null
    private var bottomInset: Int = 0

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asCustomShadow(getOrCreateLogoHeader()))
        items.add(
            UItem.asButton(
                BUTTON_GITHUB,
                LocaleController.getString(R.string.InuAboutGitHub),
                "teidesu/inugram",
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_CHANNEL_LINK,
                LocaleController.getString(R.string.InuAboutChannel),
                "@" + UpdateHelper.USERNAME,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuUpdates)))
        items.add(
            UItem.asCheck(
                TOGGLE_UPDATES_ENABLED,
                LocaleController.getString(R.string.InuUpdatesEnabled),
            ).setChecked(InuConfig.UPDATES_ENABLED.value)
        )
        items.add(
            UItem.asButton(
                BUTTON_CHECK_NOW,
                LocaleController.getString(R.string.InuUpdateCheckNow),
            )
        )
        items.add(UItem.asShadow(lastCheckLabel()))
    }

    override fun createView(context: Context): View {
        val root = super.createView(context) as FrameLayout

        val wrapper = UpdateLayoutWrapper(context)
        root.addView(
            wrapper,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        updateWrapper = wrapper

        val ul = ApplicationLoader.applicationLoaderInstance?.takeUpdateLayout(parentActivity, wrapper)
        updateLayout = ul
        ul?.updateAppUpdateViews(UserConfig.selectedAccount, false)
        applyListPadding()

        return root
    }

    override fun onInsets(left: Int, top: Int, right: Int, bottom: Int) {
        bottomInset = bottom
        updateWrapper?.setPadding(0, 0, 0, bottom)
        applyListPadding()
    }

    private fun applyListPadding() {
        val lv = listView ?: return
        val barHeight = if (SharedConfig.isAppUpdateAvailable()) dp(44f) else 0
        lv.setPadding(lv.paddingLeft, lv.paddingTop, lv.paddingRight, bottomInset + barHeight)
    }

    private var isChecking = false
    private fun lastCheckLabel(): String {
        val ms = InuConfig.UPDATE_LAST_CHECK_MS.value
        val text = when {
            isChecking -> LocaleController.getString(R.string.Checking)
            ms == 0L -> LocaleController.getString(R.string.MessageScheduledRepeatOptionNever)
            else -> LocaleController.formatDateTime(ms / 1000, true)
        }
        return LocaleController.formatString(R.string.InuUpdateLastChecked, text)
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val ctx = context ?: return
        when (item.id) {
            BUTTON_GITHUB -> Browser.openUrl(ctx, "https://github.com/teidesu/inugram")
            BUTTON_CHANNEL_LINK -> Browser.openUrl(ctx, "https://t.me/" + UpdateHelper.USERNAME)
            TOGGLE_UPDATES_ENABLED -> {
                val new = InuConfig.UPDATES_ENABLED.toggle()
                (view as? TextCheckCell)?.isChecked = new
                if (!new) UpdateHelper.clearPending()
            }
            BUTTON_CHECK_NOW -> runManualCheck()
        }
    }

    private fun runManualCheck() {
        isChecking = true
        listView.adapter.update(true)
        UpdateHelper.check { result ->
            AndroidUtilities.runOnUIThread {
                isChecking = false
                listView?.adapter?.update(true)
                val msg: CharSequence = when (result) {
                    UpdateHelper.CheckResult.InFlight ->
                        LocaleController.getString(R.string.Checking)

                    UpdateHelper.CheckResult.UpToDate ->
                        LocaleController.getString(R.string.InuUpdateUpToDate)

                    is UpdateHelper.CheckResult.Updated -> {
                        val ctx = context ?: return@runOnUIThread
                        ApplicationLoader.applicationLoaderInstance?.showUpdateAppPopup(
                            ctx, result.update, UserConfig.selectedAccount,
                        )
                        return@runOnUIThread
                    }

                    is UpdateHelper.CheckResult.Error ->
                        LocaleController.formatString(R.string.InuUpdateError, result.message)
                }
                BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, msg).show()
            }
        }
    }

    override fun onFragmentCreate(): Boolean {
        val ok = super.onFragmentCreate()
        val global = NotificationCenter.getGlobalInstance()
        global.addObserver(this, NotificationCenter.appUpdateAvailable)
        global.addObserver(this, NotificationCenter.appUpdateLoading)
        val acct = NotificationCenter.getInstance(UserConfig.selectedAccount)
        acct.addObserver(this, NotificationCenter.fileLoadProgressChanged)
        acct.addObserver(this, NotificationCenter.fileLoaded)
        acct.addObserver(this, NotificationCenter.fileLoadFailed)
        return ok
    }

    override fun onFragmentDestroy() {
        val global = NotificationCenter.getGlobalInstance()
        global.removeObserver(this, NotificationCenter.appUpdateAvailable)
        global.removeObserver(this, NotificationCenter.appUpdateLoading)
        val acct = NotificationCenter.getInstance(UserConfig.selectedAccount)
        acct.removeObserver(this, NotificationCenter.fileLoadProgressChanged)
        acct.removeObserver(this, NotificationCenter.fileLoaded)
        acct.removeObserver(this, NotificationCenter.fileLoadFailed)
        super.onFragmentDestroy()
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        val ul = updateLayout ?: return
        val acct = UserConfig.selectedAccount
        when (id) {
            NotificationCenter.appUpdateAvailable -> {
                val animated = args.getOrNull(0) as? Boolean ?: true
                ul.updateAppUpdateViews(acct, animated)
                applyListPadding()
            }
            NotificationCenter.appUpdateLoading -> {
                ul.updateFileProgress(null)
                ul.updateAppUpdateViews(acct, true)
            }
            NotificationCenter.fileLoadProgressChanged -> {
                ul.updateFileProgress(args)
            }
            NotificationCenter.fileLoaded, NotificationCenter.fileLoadFailed -> {
                val name = args.getOrNull(0) as? String ?: return
                val doc = SharedConfig.pendingAppUpdate?.document ?: return
                if (name == FileLoader.getAttachFileName(doc)) {
                    ul.updateAppUpdateViews(acct, true)
                }
            }
        }
    }

    private var logoHeader: View? = null
    private fun getOrCreateLogoHeader(): View {
        logoHeader?.let { return it }
        val ctx = context!!
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(20f), 0, dp(16f))
        }

        // Layer the background + foreground manually to bypass the AdaptiveIconDrawable
        // system mask, which would otherwise force a circle/squircle shape regardless
        // of our outline clip. Negative inset scales the foreground glyph up beyond the
        // adaptive-icon safe zone; the outline clip trims the overflow.
        val bg = ctx.getDrawable(R.drawable.icon_background_inu)
        val fg = ctx.getDrawable(R.drawable.icon_foreground_inu)
        val layered = LayerDrawable(arrayOf(bg, fg))
        val fgOverscan = -dp(18f)
        layered.setLayerInset(1, fgOverscan, fgOverscan, fgOverscan, fgOverscan)
        val icon = ImageView(ctx).apply {
            setImageDrawable(layered)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(28f).toFloat())
                }
            }
        }
        container.addView(icon, LinearLayout.LayoutParams(dp(120f), dp(120f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14f)
        })
        val version = TextView(ctx).apply {
            text = UpdateHelper.getVersionInfoString()
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            gravity = Gravity.CENTER
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4))
        }
        container.addView(version, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dp(48f)
            rightMargin = dp(48f)
        })
        logoHeader = container
        return container
    }

    companion object {
        private val BUTTON_GITHUB = InuUtils.generateId()
        private val BUTTON_CHANNEL_LINK = InuUtils.generateId()
        private val TOGGLE_UPDATES_ENABLED = InuUtils.generateId()
        private val BUTTON_CHECK_NOW = InuUtils.generateId()
    }
}

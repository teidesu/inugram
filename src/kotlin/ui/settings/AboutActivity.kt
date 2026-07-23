package desu.inugram.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.InuConfig
import desu.inugram.helpers.CrashReporter
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.LogsHelper
import desu.inugram.helpers.SystemInfo
import desu.inugram.helpers.update.UpdateHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.messenger.browser.Browser
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.IUpdateLayout
import org.telegram.ui.LaunchActivity
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

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuLogs)))
        items.add(
            UItem.asCheck(
                TOGGLE_LOGS_ENABLED,
                LocaleController.getString(R.string.InuLogsEnabled),
            ).setChecked(LogsHelper.isEnabled())
        )
        if (LogsHelper.isEnabled()) {
            items.add(UItem.asCustom(getOrCreateLogsRow()))
            items.add(UItem.asCustom(getOrCreateHeapRow()))
        }
        items.add(UItem.asButton(BUTTON_COPY_SYSINFO, LocaleController.getString(R.string.InuLogsCopySystemInfo)))
        items.add(UItem.asShadow(null))
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
            TOGGLE_LOGS_ENABLED -> {
                val new = !LogsHelper.isEnabled()
                LogsHelper.setEnabled(new)
                (view as? TextCheckCell)?.isChecked = new
                if (new) refreshLogsSize()
                listView?.adapter?.update(true)
            }

            BUTTON_COPY_SYSINFO -> {
                AndroidUtilities.addToClipboard(SystemInfo.build())
                BulletinFactory.of(this).createCopyBulletin(
                    LocaleController.getString(R.string.InuLogsSystemInfoCopied)
                ).show()
            }
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

    private var logsRow: View? = null
    private var logsSizeText: TextView? = null
    private var logsSize: Long = -1L

    private fun getOrCreateLogsRow(): View {
        logsRow?.let { return it }
        val ctx = context!!
        val row = object : LinearLayout(ctx) {
            override fun dispatchDraw(canvas: Canvas) {
                super.dispatchDraw(canvas)
                canvas.drawLine(
                    dp(20f).toFloat(),
                    height - 1f,
                    width.toFloat(),
                    height.toFloat(),
                    Theme.dividerPaint,
                )
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(50f)
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            setPadding(dp(21f), 0, dp(8f), 0)
        }
        val size = TextView(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            text = logsSizeLabel(logsSize)
        }
        logsSizeText = size
        row.addView(size, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(buildLogsIconButton(ctx, R.drawable.msg_clear, R.string.InuLogsClear) {
            FileLog.cleanupLogs()
            refreshLogsSize()
            BulletinFactory.of(this).createSimpleBulletin(
                R.raw.chats_infotip,
                LocaleController.getString(R.string.InuLogsCleared),
            ).show()
        })
        row.addView(buildLogsIconButton(ctx, R.drawable.msg_shareout, R.string.InuLogsShare) { anchor ->
            showShareMenu(anchor)
        })
        logsRow = row
        refreshLogsSize()
        return row
    }

    private fun buildLogsIconButton(
        ctx: Context, iconRes: Int, contentDescRes: Int, onClick: (anchor: View) -> Unit,
    ): ImageView = ImageView(ctx).apply {
        setImageResource(iconRes)
        setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY)
        background = Theme.createSelectorDrawable(
            Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP,
        )
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        contentDescription = LocaleController.getString(contentDescRes)
        layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f))
        setOnClickListener { onClick(this) }
    }

    private fun showShareMenu(anchor: View) {
        val opts = ItemOptions.makeOptions(this, anchor)
        opts.add(R.drawable.msg_archive, LocaleController.getString(R.string.InuLogsShareZip)) {
            val activity = parentActivity as? LaunchActivity ?: return@add
            LogsHelper.shareZip(activity, ::onShareDone)
        }
        opts.add(R.drawable.msg_log, LocaleController.getString(R.string.InuLogsShareCurrent)) {
            val activity = parentActivity as? LaunchActivity ?: return@add
            LogsHelper.shareCurrent(activity, ::onShareDone)
        }
        opts.setGravity(Gravity.END).show()
    }

    private fun onShareDone(ok: Boolean) {
        if (!ok) BulletinFactory.of(this).createErrorBulletin(
            LocaleController.getString(R.string.InuLogsShareError)
        ).show()
    }

    private var heapRow: View? = null
    private var heapUsageText: TextView? = null

    private fun getOrCreateHeapRow(): View {
        heapRow?.let { refreshHeapUsage(); return it }
        val ctx = context!!
        val row = object : LinearLayout(ctx) {
            override fun dispatchDraw(canvas: Canvas) {
                super.dispatchDraw(canvas)
                canvas.drawLine(
                    dp(20f).toFloat(),
                    height - 1f,
                    width.toFloat(),
                    height.toFloat(),
                    Theme.dividerPaint,
                )
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(50f)
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            setPadding(dp(21f), 0, dp(8f), 0)
        }
        val usage = TextView(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        }
        heapUsageText = usage
        row.addView(usage, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(buildLogsIconButton(ctx, R.drawable.msg_calls_minimize, R.string.InuLogsMakeHeapDump) {
            val rt = Runtime.getRuntime()
            rt.gc()
            BulletinFactory.of(this).createErrorBulletin("Runtime GC finished").show()
            refreshHeapUsage()
        })
        row.addView(buildLogsIconButton(ctx, R.drawable.msg_download, R.string.InuLogsMakeHeapDump) {
            confirmAndMakeHeapDump()
        })
        heapRow = row
        refreshHeapUsage()
        return row
    }

    private fun confirmAndMakeHeapDump() {
        val activity = parentActivity as? LaunchActivity ?: return
        AlertDialog.Builder(activity)
            .setTitle(LocaleController.getString(R.string.InuLogsMakeHeapDump))
            .setMessage(AndroidUtilities.replaceTags(LocaleController.getString(R.string.InuLogsHeapDumpWarning)))
            .setPositiveButton(LocaleController.getString(R.string.Continue)) { _, _ -> makeHeapDump(activity) }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
    }

    private fun makeHeapDump(activity: LaunchActivity) {
        val progress = AlertDialog(activity, AlertDialog.ALERT_TYPE_MESSAGE).apply {
            setMessage(LocaleController.getString(R.string.InuLogsMakingHeapDump))
            setCanCancel(false)
        }
        progress.show()
        // let the dialog draw a frame before the (blocking) dump freezes the UI thread
        AndroidUtilities.runOnUIThread({
            var ok = true
            try {
                CrashReporter.dumpAndSaveHeap(activity)
            } catch (e: Throwable) {
                ok = false
                FileLog.e(e)
            } finally {
                progress.dismiss()
                refreshHeapUsage()
            }
            if (!ok) BulletinFactory.of(this).createErrorBulletin(
                LocaleController.getString(R.string.InuLogsShareError)
            ).show()
        }, 150)
    }

    private fun refreshHeapUsage() {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        heapUsageText?.text = LocaleController.formatString(
            R.string.InuLogsHeapUsage,
            AndroidUtilities.formatFileSize(used),
            AndroidUtilities.formatFileSize(rt.maxMemory()),
        )
    }

    private fun logsSizeLabel(size: Long): String = LocaleController.formatString(
        R.string.InuLogsSize,
        if (size < 0) "…" else AndroidUtilities.formatFileSize(size),
    )

    private fun refreshLogsSize() {
        logsSize = -1L
        logsSizeText?.text = logsSizeLabel(-1L)
        Utilities.globalQueue.postRunnable {
            val size = LogsHelper.computeSize()
            AndroidUtilities.runOnUIThread {
                logsSize = size
                logsSizeText?.text = logsSizeLabel(size)
            }
        }
    }

    private val ripplePos = IntArray(2)
    private var lastRippleTime = 0L
    private var rippleStreak = 0

    private fun rippleFrom(view: View, x: Float, y: Float) {
        val now = System.currentTimeMillis()
        rippleStreak = if (now - lastRippleTime < 800L) rippleStreak + 1 else 0
        lastRippleTime = now

        view.getLocationInWindow(ripplePos)
        LaunchActivity.makeRipple(
            ripplePos[0] + x,
            ripplePos[1] + y,
            1f + minOf(rippleStreak, 9),
        )
        try {
            view.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        } catch (ignore: Exception) {
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
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) rippleFrom(v, event.x, event.y)
                false
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
        container.addView(
            version, LinearLayout.LayoutParams(
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
        private val TOGGLE_LOGS_ENABLED = InuUtils.generateId()
        private val BUTTON_COPY_SYSINFO = InuUtils.generateId()
    }
}

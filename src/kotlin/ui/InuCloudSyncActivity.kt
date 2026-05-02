package desu.inugram.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.InuConfig
import desu.inugram.helpers.CloudSettingsHelper
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.SettingsBackupHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.Emoji
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.Bulletin
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.LinkSpanDrawable
import org.telegram.ui.Components.RLottieImageView
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Stories.recorder.ButtonWithCounterView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InuCloudSyncActivity : InuSettingsPageActivity() {
    private var syncAccount: Int = -1
    private var cloudTs: Long = 0L
    private var loading: Boolean = false
    private var hasBackup: Boolean = false

    private var syncButtonContainer: FrameLayout? = null
    private var syncButton: ButtonWithCounterView? = null
    private var headerView: View? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuCloudSync)

    override fun createView(context: Context): View {
        var stored = InuConfig.CLOUD_SYNC_ACCOUNT_ID.value
        if (stored == 0L) {
            stored = UserConfig.getInstance(UserConfig.selectedAccount).clientUserId
            InuConfig.CLOUD_SYNC_ACCOUNT_ID.value = stored
        }
        syncAccount = resolveAccount(stored)
        val view = super.createView(context) as FrameLayout
        view.addView(
            buildSyncButton(context),
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM)
        )
        listView.setPadding(0, 0, 0, dp(STICKY_HEIGHT_DP))
        Bulletin.addDelegate(this, object : Bulletin.Delegate {
            override fun getBottomOffset(tag: Int): Int = syncButtonContainer?.height ?: 0
        })
        reloadFromCloud()
        return view
    }

    override fun onFragmentDestroy() {
        Bulletin.removeDelegate(this)
        super.onFragmentDestroy()
    }

    override fun onInsets(left: Int, top: Int, right: Int, bottom: Int) {
        listView?.setPadding(0, 0, 0, bottom + dp(STICKY_HEIGHT_DP))
        syncButtonContainer?.setPadding(dp(16), dp(8), dp(16), dp(8) + bottom)
    }

    private fun resolveAccount(userId: Long): Int {
        if (userId == 0L) return -1
        return activeAccountIndices().firstOrNull {
            UserConfig.getInstance(it).clientUserId == userId
        } ?: -1
    }

    private fun activeAccountIndices(): List<Int> =
        (0 until UserConfig.MAX_ACCOUNT_COUNT).filter { UserConfig.getInstance(it).isClientActivated }

    private fun accountLabel(account: Int): String {
        val user = UserConfig.getInstance(account).currentUser ?: return ""
        return UserObject.getUserName(user)
    }

    private fun formatDate(ts: Long): String =
        if (ts > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
        else LocaleController.getString(R.string.InuCloudSyncDateNever)

    private fun accountAvatarRow(account: Int, selected: Boolean): View {
        val ctx = context
        val rp = resourceProvider
        val user = UserConfig.getInstance(account).currentUser
        val avatarDrawable = AvatarDrawable().apply { setInfo(user) }

        val avatarContainer = object : FrameLayout(ctx) {
            private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun dispatchDraw(canvas: Canvas) {
                if (selected) {
                    ringPaint.style = Paint.Style.STROKE
                    ringPaint.strokeWidth = AndroidUtilities.dp(1.33f).toFloat()
                    ringPaint.color = Theme.getColor(Theme.key_featuredStickers_addButton, rp)
                    canvas.drawCircle(width / 2f, height / 2f, AndroidUtilities.dp(16f).toFloat(), ringPaint)
                }
                super.dispatchDraw(canvas)
            }
        }
        val avatarView = BackupImageView(ctx).apply {
            setRoundRadius(AndroidUtilities.dp(16f))
            imageReceiver.currentAccount = account
            setForUserOrChat(user, avatarDrawable)
            if (selected) {
                scaleX = 0.833f
                scaleY = 0.833f
            }
        }
        avatarContainer.addView(avatarView, LayoutHelper.createFrame(32, 32f, Gravity.CENTER, 1f, 1f, 1f, 1f))

        val nameView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp))
            text = Emoji.replaceEmoji(UserObject.getUserName(user), paint.fontMetricsInt, false)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, rp), 0, 0)
            addView(avatarContainer, LayoutHelper.createLinear(34, 34, Gravity.CENTER_VERTICAL, 12, 0, 0, 0))
            addView(
                nameView,
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 13, 0, 14, 0)
            )
        }
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asCustomShadow(getOrCreateHeader(), LayoutHelper.WRAP_CONTENT))
        items.add(
            UItem.asButton(
                BUTTON_SYNC_ACCOUNT,
                LocaleController.getString(R.string.InuCloudSyncAccount),
                accountRowSubtitle(),
            )
        )

        items.add(
            UItem.asCheck(TOGGLE_AUTO, LocaleController.getString(R.string.InuCloudSyncAuto))
                .setChecked(InuConfig.CLOUD_SYNC_AUTO.value)
        )
        items.add(UItem.asShadow(null))

        val opsEnabled = syncAccount >= 0 && hasBackup && !loading
        items.add(
            UItem.asButton(
                BUTTON_RESTORE,
                R.drawable.msg_download,
                LocaleController.getString(R.string.InuCloudRestore),
            ).setEnabled(opsEnabled)
        )
        items.add(
            UItem.asButton(
                BUTTON_DELETE,
                R.drawable.msg_delete,
                LocaleController.getString(R.string.InuCloudDelete),
            ).red().setEnabled(opsEnabled)
        )
        items.add(UItem.asShadow(statusLabel()))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_SYNC_ACCOUNT -> showAccountPicker(view)
            BUTTON_RESTORE -> if (syncAccount >= 0 && !loading) onRestoreClick()
            BUTTON_DELETE -> if (syncAccount >= 0 && !loading) onDeleteClick()
            TOGGLE_AUTO -> {
                InuConfig.CLOUD_SYNC_AUTO_USER_SET.value = true
                (view as? TextCheckCell)?.isChecked = InuConfig.CLOUD_SYNC_AUTO.toggle()
            }
        }
    }

    private fun accountRowSubtitle(): String =
        if (syncAccount >= 0) accountLabel(syncAccount)
        else LocaleController.getString(R.string.InuCloudSyncAccountInactive)

    private fun refreshList() {
        listView?.adapter?.update(true)
        listView?.post { updateOpsAlpha() }
    }

    private fun updateOpsAlpha() {
        val lv = listView ?: return
        val adapter = lv.adapter ?: return
        for (i in 0 until lv.childCount) {
            val child = lv.getChildAt(i)
            val pos = lv.getChildAdapterPosition(child)
            val item = adapter.getItem(pos) ?: continue
            if (item.id == BUTTON_RESTORE || item.id == BUTTON_DELETE) {
                child.alpha = if (item.enabled) 1f else 0.5f
            }
        }
    }

    private fun statusLabel(): CharSequence = when {
        syncAccount < 0 -> LocaleController.getString(R.string.InuCloudSyncAccountInactive)
        loading -> LocaleController.getString(R.string.InuCloudSyncing)
        else -> LocaleController.formatString(R.string.InuCloudSyncDate, formatDate(cloudTs))
    }

    private fun getOrCreateHeader(): View {
        headerView?.let { return it }
        val ctx = context
        val rp = resourceProvider
        val animation = RLottieImageView(ctx).apply {
            setAutoRepeat(true)
            setAnimation(R.raw.utyan_saved_messages, 120, 120)
            scaleType = ImageView.ScaleType.CENTER
            playAnimation()
        }
        val text = LinkSpanDrawable.LinksTextView(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4, rp))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            gravity = Gravity.CENTER
            text = AndroidUtilities.replaceLinks(
                LocaleController.getString(R.string.InuCloudSyncDesc), resourceProvider
            )
        }
        val frame = FrameLayout(ctx)
        frame.addView(
            animation,
            LayoutHelper.createFrame(120, 120f, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0f, 14f, 0f, 0f),
        )
        frame.addView(
            text,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL, 32f, 150f, 32f, 20f,
            ),
        )
        headerView = frame
        return frame
    }

    private fun buildSyncButton(ctx: Context): FrameLayout {
        val btn = ButtonWithCounterView(ctx, true, resourceProvider).setRound().apply {
            setText(LocaleController.getString(R.string.InuCloudSyncNow), false)
            setOnClickListener { onSyncClick() }
        }
        val container = FrameLayout(ctx).apply {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(btn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48f))
        }
        syncButton = btn
        syncButtonContainer = container
        updateSyncButtonEnabled()
        return container
    }

    private fun updateSyncButtonEnabled() {
        val btn = syncButton ?: return
        val enabled = syncAccount >= 0 && !loading
        btn.isEnabled = enabled
        btn.alpha = if (enabled) 1f else 0.5f
    }

    private fun showAccountPicker(anchor: View) {
        val accounts = activeAccountIndices()
        if (accounts.isEmpty()) return
        val o = ItemOptions.makeOptions(this, anchor)
        o.setDimAlpha(0)
        for (acc in accounts) {
            val view = accountAvatarRow(acc, syncAccount == acc)
            view.setOnClickListener {
                o.dismiss()
                if (syncAccount == acc) return@setOnClickListener
                syncAccount = acc
                InuConfig.CLOUD_SYNC_ACCOUNT_ID.value = UserConfig.getInstance(acc).clientUserId
                reloadFromCloud()
            }
            o.addView(view, LayoutHelper.createLinear(230, 48))
        }
        o.show()
    }

    private fun reloadFromCloud() {
        cloudTs = 0L
        hasBackup = false
        loading = false
        updateSyncButtonEnabled()
        refreshList()
        if (syncAccount < 0) return
        val acc = syncAccount
        CloudSettingsHelper.fetchCloudTimestamp(acc) { ts ->
            if (acc != syncAccount) return@fetchCloudTimestamp
            cloudTs = ts
            hasBackup = ts > 0
            updateSyncButtonEnabled()
            refreshList()
        }
    }

    private fun onSyncClick() {
        if (syncAccount < 0 || loading) return
        loading = true
        updateSyncButtonEnabled()
        refreshList()
        val acc = syncAccount
        CloudSettingsHelper.syncToCloud(acc) { ok, error ->
            if (acc != syncAccount) return@syncToCloud
            loading = false
            if (ok) {
                cloudTs = System.currentTimeMillis()
                hasBackup = true
                if (!InuConfig.CLOUD_SYNC_AUTO_USER_SET.value && !InuConfig.CLOUD_SYNC_AUTO.value) {
                    InuConfig.CLOUD_SYNC_AUTO.value = true
                }
            }
            updateSyncButtonEnabled()
            refreshList()
            if (!ok) showError(R.string.InuCloudSyncFailed, error)
        }
    }

    private fun onRestoreClick() {
        loading = true
        updateSyncButtonEnabled()
        refreshList()
        val acc = syncAccount
        CloudSettingsHelper.restoreFromCloud(acc) { parsed, error ->
            if (acc != syncAccount) return@restoreFromCloud
            loading = false
            updateSyncButtonEnabled()
            refreshList()
            if (parsed == null) {
                showError(R.string.InuCloudRestoreFailed, error)
                return@restoreFromCloud
            }
            if (parsed.changed == 0) {
                bulletin().createSimpleBulletin(
                    R.raw.chats_infotip,
                    LocaleController.getString(R.string.InuBackupImportNoChanges),
                ).show()
                return@restoreFromCloud
            }
            SettingsBackupHelper.applyAndPromptRestart(this, parsed)
        }
    }

    private fun onDeleteClick() {
        loading = true
        updateSyncButtonEnabled()
        refreshList()
        val acc = syncAccount
        CloudSettingsHelper.deleteCloudBackup(acc) { ok, error ->
            if (acc != syncAccount) return@deleteCloudBackup
            loading = false
            if (ok) {
                cloudTs = 0L
                hasBackup = false
            }
            updateSyncButtonEnabled()
            refreshList()
            when {
                ok -> bulletin().createSimpleBulletin(
                    R.raw.done,
                    LocaleController.getString(R.string.InuCloudDeleteSuccess),
                ).show()

                error == null -> bulletin().createSimpleBulletin(
                    R.raw.chats_infotip,
                    LocaleController.getString(R.string.InuCloudNoBackup),
                ).show()

                else -> showError(R.string.InuCloudDeleteFailed, error)
            }
        }
    }

    private fun bulletin() = BulletinFactory.of(this)

    private fun showError(titleRes: Int, error: String?) {
        val title = LocaleController.getString(titleRes)
        val b = if (error.isNullOrEmpty()) bulletin().createSimpleBulletin(R.raw.error, title)
        else bulletin().createSimpleBulletin(R.raw.error, title, error)
        b.show()
    }

    private fun dp(v: Int) = AndroidUtilities.dp(v.toFloat())

    companion object {
        private val BUTTON_SYNC_ACCOUNT = InuUtils.generateId()
        private val BUTTON_RESTORE = InuUtils.generateId()
        private val BUTTON_DELETE = InuUtils.generateId()
        private val TOGGLE_AUTO = InuUtils.generateId()

        private const val STICKY_HEIGHT_DP = 64
    }
}

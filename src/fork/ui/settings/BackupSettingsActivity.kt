package desu.inugram.ui.settings


import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.collection.LongSparseArray
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.cloud.CloudSettingsHelper
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.cloud.SettingsBackupHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.Emoji
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ShareAlert
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupSettingsActivity : SettingsPageActivity() {
    private var syncAccount: Int = -1
    private var cloudTs: Long = 0L
    private var loading: Boolean = false
    private var hasBackup: Boolean = false

    private var cloudCard: CloudSyncCell? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuBackupSettings)

    override fun createView(context: Context): View {
        var stored = InuConfig.CLOUD_SYNC_ACCOUNT_ID.value
        if (stored == 0L) {
            stored = UserConfig.getInstance(UserConfig.selectedAccount).clientUserId
            InuConfig.CLOUD_SYNC_ACCOUNT_ID.value = stored
        }
        syncAccount = resolveAccount(stored)
        val view = super.createView(context)
        reloadFromCloud()
        return view
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
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

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
        items.add(UItem.asCustom(CARD_CLOUD, getOrCreateCloudCard()))

        val opsEnabled = syncAccount >= 0 && hasBackup && !loading
        items.add(
            UItem.asButton(
                BUTTON_SYNC_NOW,
                R.drawable.msg_retry,
                LocaleController.getString(R.string.InuCloudSyncNow),
            ).setEnabled(syncAccount >= 0 && !loading)
        )
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
        items.add(
            UItem.asShadow(
                AndroidUtilities.replaceLinks(
                    LocaleController.getString(R.string.InuCloudSyncDesc), resourceProvider
                )
            )
        )

        items.add(
            UItem.asButton(
                BUTTON_EXPORT,
                R.drawable.msg_shareout,
                LocaleController.getString(R.string.InuBackupExport)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_IMPORT,
                R.drawable.msg_download,
                LocaleController.getString(R.string.InuBackupImport)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_RESET,
                R.drawable.msg_reset_solar,
                LocaleController.getString(R.string.InuBackupReset)
            ).red()
        )
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_EXPORT -> launchExport()
            BUTTON_IMPORT -> launchImport()
            BUTTON_RESET -> confirmReset()
            BUTTON_SYNC_NOW -> onSyncClick()
            BUTTON_RESTORE -> if (syncAccount >= 0 && !loading) onRestoreClick()
            BUTTON_DELETE -> if (syncAccount >= 0 && !loading) onDeleteClick()
        }
    }

    private fun toggleAutoSync() {
        InuConfig.CLOUD_SYNC_AUTO_USER_SET.value = true
        InuConfig.CLOUD_SYNC_AUTO.toggle()
        updateCloudCard(animated = true)
    }

    private fun refreshList() {
        updateCloudCard(animated = false)
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
            if (item.id == BUTTON_SYNC_NOW || item.id == BUTTON_RESTORE || item.id == BUTTON_DELETE) {
                child.alpha = if (item.enabled) 1f else 0.5f
            }
        }
    }

    private fun getCloudStatus(): CharSequence = when {
        syncAccount < 0 -> LocaleController.getString(R.string.InuCloudSyncAccountInactive)
        loading -> LocaleController.getString(R.string.InuCloudSyncing)
        cloudTs <= 0L -> "${accountLabel(syncAccount)} · ${LocaleController.getString(R.string.InuCloudSyncNever)}"
        else -> "${accountLabel(syncAccount)} · ${
            LocaleController.formatString(R.string.InuCloudSyncedAt, formatDate(cloudTs))
        }"
    }

    private fun getOrCreateCloudCard(): CloudSyncCell {
        cloudCard?.let { return it }
        val card = CloudSyncCell(
            context,
            resourceProvider,
            onPickAccount = { anchor -> showAccountPicker(anchor) },
            onToggleAuto = { toggleAutoSync() },
        )
        cloudCard = card
        updateCloudCard(animated = false)
        return card
    }

    private fun updateCloudCard(animated: Boolean) {
        cloudCard?.setState(syncAccount, getCloudStatus(), InuConfig.CLOUD_SYNC_AUTO.value, animated)
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
        refreshList()
        if (syncAccount < 0) return
        val acc = syncAccount
        CloudSettingsHelper.fetchCloudTimestamp(acc) { ts ->
            if (acc != syncAccount) return@fetchCloudTimestamp
            cloudTs = ts
            hasBackup = ts > 0
            refreshList()
        }
    }

    private fun onSyncClick() {
        if (syncAccount < 0 || loading) return
        loading = true
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
            refreshList()
            if (!ok) showError(R.string.InuCloudSyncFailed, error)
        }
    }

    private fun onRestoreClick() {
        loading = true
        refreshList()
        val acc = syncAccount
        CloudSettingsHelper.restoreFromCloud(acc) { parsed, error ->
            if (acc != syncAccount) return@restoreFromCloud
            loading = false
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
        refreshList()
        val acc = syncAccount
        CloudSettingsHelper.deleteCloudBackup(acc) { ok, error ->
            if (acc != syncAccount) return@deleteCloudBackup
            loading = false
            if (ok) {
                cloudTs = 0L
                hasBackup = false
            }
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

    private fun launchExport() {
        Utilities.globalQueue.postRunnable {
            val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(AndroidUtilities.getCacheDir(), "$date${SettingsBackupHelper.FILENAME_SUFFIX}")
            val err: String? = try {
                file.parentFile?.mkdirs()
                file.writeText(SettingsBackupHelper.export(), Charsets.UTF_8)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            AndroidUtilities.runOnUIThread {
                if (err != null) {
                    bulletin().createErrorBulletin(
                        LocaleController.formatString(R.string.InuBackupExportError, err)
                    ).show()
                    return@runOnUIThread
                }
                openSharePicker(file)
            }
        }
    }

    private fun openSharePicker(file: File) {
        val ctx = parentActivity ?: return
        val account = accountInstance
        val sheet = object : ShareAlert(ctx, null, null, false, null, false) {
            override fun onSend(
                dids: LongSparseArray<TLRPC.Dialog>,
                count: Int,
                topic: TLRPC.TL_forumTopic?,
                showToast: Boolean
            ) {
                for (i in 0 until dids.size()) {
                    val did = dids.keyAt(i)
                    SendMessagesHelper.prepareSendingDocument(
                        account, file.absolutePath, file.absolutePath, null, null,
                        "application/json", did,
                        null, null, null, null, null,
                        true, 0, null, null, false,
                    )
                }
                if (dids.size() == 1) openChat(dids.keyAt(0))
            }
        }
        showDialog(sheet)
    }

    private fun openChat(did: Long) {
        val args = Bundle().apply {
            putBoolean("scrollToTopOnResume", true)
            when {
                DialogObject.isEncryptedDialog(did) -> putInt("enc_id", DialogObject.getEncryptedChatId(did))
                DialogObject.isUserDialog(did) -> putLong("user_id", did)
                else -> putLong("chat_id", -did)
            }
        }
        if (messagesController.checkCanOpenChat(args, this)) {
            presentFragment(ChatActivity(args))
        }
    }

    private fun launchImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "*/*"))
        }
        try {
            startActivityForResult(intent, REQ_IMPORT)
        } catch (e: Exception) {
            bulletin().createErrorBulletin(e.message ?: "").show()
        }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val ctx = context ?: parentActivity ?: return
        if (requestCode == REQ_IMPORT) {
            Utilities.globalQueue.postRunnable {
                val text = try {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (_: Exception) {
                    null
                }
                AndroidUtilities.runOnUIThread {
                    if (text == null) {
                        bulletin().createErrorBulletin(
                            LocaleController.getString(R.string.InuBackupImportBadFormat)
                        ).show()
                        return@runOnUIThread
                    }
                    SettingsBackupHelper.showImportConfirm(this, text)
                }
            }
        }
    }

    private fun confirmReset() {
        val ctx = parentActivity ?: return
        val dialog = AlertDialog.Builder(ctx, resourceProvider)
            .setTitle(LocaleController.getString(R.string.InuBackupReset))
            .setMessage(LocaleController.getString(R.string.InuBackupResetConfirm))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.InuBackupReset)) { _, _ ->
                SettingsBackupHelper.resetAndPromptRestart(this)
            }
            .create()
        showDialog(dialog)
        (dialog.getButton(Dialog.BUTTON_POSITIVE) as? TextView)
            ?.setTextColor(getThemedColor(Theme.key_text_RedBold))
    }

    companion object {
        private val CARD_CLOUD = InuUtils.generateId()
        private val BUTTON_SYNC_NOW = InuUtils.generateId()
        private val BUTTON_RESTORE = InuUtils.generateId()
        private val BUTTON_DELETE = InuUtils.generateId()
        private val BUTTON_EXPORT = InuUtils.generateId()
        private val BUTTON_IMPORT = InuUtils.generateId()
        private val BUTTON_RESET = InuUtils.generateId()

        private const val REQ_IMPORT = 31002

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "backup",
            titleRes = R.string.InuBackupSettings,
            iconRes = R.drawable.inu_tabler_cloud,
            factory = ::BackupSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("backup-export", R.string.InuBackupExport, BUTTON_EXPORT),
                SearchRegistry.Entry("backup-import", R.string.InuBackupImport, BUTTON_IMPORT),
                SearchRegistry.Entry("backup-reset", R.string.InuBackupReset, BUTTON_RESET),
                SearchRegistry.Entry("cloud-sync", R.string.InuCloudSync, CARD_CLOUD),
            ),
        )
    }
}

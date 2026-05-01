package desu.inugram.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.os.Bundle
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.SettingsBackupHelper
import androidx.collection.LongSparseArray
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ShareAlert
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InuSettingsActivity : InuSettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuSettings)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAppearance)))
        items.add(
            UItem.asButton(
                BUTTON_GENERAL,
                R.drawable.msg_settings_old,
                LocaleController.getString(R.string.InuGeneral)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_CHATS,
                R.drawable.msg_discussion,
                LocaleController.getString(R.string.Chats)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_DIALOGS,
                R.drawable.msg_folders,
                LocaleController.getString(R.string.InuMainPage)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_ANNOYANCES,
                R.drawable.menu_hide_gift,
                LocaleController.getString(R.string.InuAnnoyances)
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuOther)))
        items.add(
            UItem.asButton(
                BUTTON_BEHAVIOR,
                R.drawable.avd_speed,
                LocaleController.getString(R.string.InuBehavior)
            )
        )
        items.add(UItem.asShadow(null))

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
                BUTTON_ABOUT,
                R.drawable.msg_info,
                LocaleController.getString(R.string.InuAbout)
            )
        )
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_GENERAL -> presentFragment(InuAppearanceSettingsActivity())
            BUTTON_CHATS -> presentFragment(InuChatsSettingsActivity())
            BUTTON_DIALOGS -> presentFragment(InuDialogsSettingsActivity())
            BUTTON_ANNOYANCES -> presentFragment(InuAnnoyancesSettingsActivity())
            BUTTON_BEHAVIOR -> presentFragment(InuBehaviorSettingsActivity())
            BUTTON_ABOUT -> presentFragment(InuAboutActivity())
            BUTTON_EXPORT -> launchExport()
            BUTTON_IMPORT -> launchImport()
        }
    }

    private fun launchExport() {
        Utilities.globalQueue.postRunnable {
            val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(AndroidUtilities.getCacheDir(), "$date.inu-settings.json")
            val err: String? = try {
                file.parentFile?.mkdirs()
                file.writeText(SettingsBackupHelper.export(), Charsets.UTF_8)
                null
            } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            AndroidUtilities.runOnUIThread {
                if (err != null) {
                    BulletinFactory.of(this).createErrorBulletin(
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
            override fun onSend(dids: LongSparseArray<TLRPC.Dialog>, count: Int, topic: TLRPC.TL_forumTopic?, showToast: Boolean) {
                for (i in 0 until dids.size()) {
                    val did = dids.keyAt(i)
                    SendMessagesHelper.prepareSendingDocument(
                        account, file.absolutePath, file.absolutePath, null, null,
                        "application/json", did,
                        null, null, null, null, null,
                        true, 0, null, null, 0, false,
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
            BulletinFactory.of(this).createErrorBulletin(e.message ?: "").show()
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
                } catch (_: Exception) { null }
                AndroidUtilities.runOnUIThread {
                    if (text == null) {
                        BulletinFactory.of(this).createErrorBulletin(
                            LocaleController.getString(R.string.InuBackupImportBadFormat)
                        ).show()
                        return@runOnUIThread
                    }
                    SettingsBackupHelper.showImportConfirm(this, text)
                }
            }
        }
    }

    companion object {
        private val BUTTON_GENERAL = InuUtils.generateId()
        private val BUTTON_CHATS = InuUtils.generateId()
        private val BUTTON_DIALOGS = InuUtils.generateId()
        private val BUTTON_ANNOYANCES = InuUtils.generateId()
        private val BUTTON_BEHAVIOR = InuUtils.generateId()
        private val BUTTON_ABOUT = InuUtils.generateId()
        private val BUTTON_EXPORT = InuUtils.generateId()
        private val BUTTON_IMPORT = InuUtils.generateId()

        private const val REQ_IMPORT = 31002
    }
}

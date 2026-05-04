package desu.inugram.helpers

import android.content.pm.PackageInfo
import desu.inugram.InuConfig
import desu.inugram.InuConfig.UpdateChannelItem
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BetaUpdate
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import kotlin.math.max
import kotlin.math.min

object UpdateHelper {
    const val USERNAME = "InugramCI"
    private const val CHECK_INTERVAL_MS = 4L * 60 * 60 * 1000
    private const val INFLIGHT_TIMEOUT_MS = 60L * 1000

    private var pInfo: PackageInfo? = null;

    @JvmStatic
    fun getVersionInfoString(): String {
        if (pInfo == null) {
            pInfo = ApplicationLoader.applicationContext.packageManager.getPackageInfo(
                ApplicationLoader.applicationContext.packageName,
                0
            )
        }

        return LocaleController.formatString(
            if (BuildConfig.INU_BUILD_TYPE === "canary") R.string.InuVersionCanary else R.string.InuVersion,
            pInfo!!.versionCode,
            pInfo!!.versionName?.replace(Regex("-[0-9a-f]{7}$"), "") ?: "",
            BuildConfig.STOCK_VERSION_CODE
        )
    }

    private val APK_RE = Regex("^inugram-(.+)-(\\d+)\\.apk$")
    private val SHORT_SHA_RE = Regex("-([0-9a-f]{7,40})$")

    @Volatile
    private var inflight = false

    @Volatile
    private var inflightSince = 0L

    @Volatile
    var pendingBetaUpdate: BetaUpdate? = null
        private set

    fun checkForCustomUpdate(force: Boolean, whenDone: Runnable?) {
        if (!force && System.currentTimeMillis() - InuConfig.UPDATE_LAST_CHECK_MS.value < CHECK_INTERVAL_MS) {
            whenDone?.run()
            return
        }
        check { whenDone?.run() }
    }

    fun clearPending() {
        pendingBetaUpdate = null
        SharedConfig.pendingAppUpdate = null
        SharedConfig.saveConfig()
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable)
    }

    @JvmStatic
    fun clearPendingIfInstalled() {
        val pending = SharedConfig.pendingAppUpdate ?: return
        val current = currentBuild()
        if (pending.version == current.versionCode.toString()) {
            clearPending()
        }
    }

    fun check(callback: ((CheckResult) -> Unit)?) {
        val account = UserConfig.selectedAccount
        if (!UserConfig.getInstance(account).isClientActivated) {
            callback?.invoke(CheckResult.Error("Not logged in"))
            return
        }
        if (BuildConfig.INU_BUILD_TYPE == "debug" || InuConfig.UPDATE_CHANNEL.value == UpdateChannelItem.DISABLED) {
            callback?.invoke(CheckResult.UpToDate)
            return
        }
        val now = System.currentTimeMillis()
        if (inflight && now - inflightSince < INFLIGHT_TIMEOUT_MS) {
            callback?.invoke(CheckResult.InFlight)
            return
        }
        inflight = true
        inflightSince = now
        MessagesController.getInstance(account).userNameResolver.resolve(USERNAME) { id ->
            if (id == null || id == 0L || id == Long.MAX_VALUE) {
                finish(callback, CheckResult.Error("resolve failed"))
                return@resolve
            }
            performSearch(account, id, callback)
        }
    }

    private fun performSearch(account: Int, peerId: Long, callback: ((CheckResult) -> Unit)?) {
        val mc = MessagesController.getInstance(account)
        val isCanary = InuConfig.UPDATE_CHANNEL.value == UpdateChannelItem.CANARY
        val req = TLRPC.TL_messages_search().apply {
            peer = mc.getInputPeer(peerId)
            q = if (isCanary) "#canary" else "#release"
            filter = TLRPC.TL_inputMessagesFilterDocument()
            limit = 10
        }
        ConnectionsManager.getInstance(account).sendRequest(req) { resp, err ->
            AndroidUtilities.runOnUIThread {
                if (err != null || resp !is TLRPC.messages_Messages) {
                    finish(callback, CheckResult.Error(err?.text ?: "no response"))
                    return@runOnUIThread
                }
                val match = resp.messages.firstNotNullOfOrNull { msg ->
                    extractApkInfo(msg)?.let { msg to it }
                }
                val current = currentBuild()
                if (match == null || !isNewer(match.second, current)) {
                    clearPending()
                    finish(callback, CheckResult.UpToDate)
                    return@runOnUIThread
                }
                val (msg, info) = match
                val updateObj = TLRPC.TL_help_appUpdate().apply {
                    flags = flags or 2
                    version = info.verCode.toString()
                    text = msg.message ?: ""
                    entities = msg.entities
                    document = info.document
                }

                val blockquote = updateObj.entities.firstOrNull {
                    it is TLRPC.TL_messageEntityBlockquote
                }
                if (blockquote != null) {
                    val start = blockquote.offset
                    val end = blockquote.offset + blockquote.length
                    val newEntities = arrayListOf<TLRPC.MessageEntity>()
                    for (entity in updateObj.entities) {
                        if (entity === blockquote) continue
                        if (entity.offset + entity.length <= start) continue
                        if (entity.offset >= end) continue
                        val clippedStart = max(entity.offset, start)
                        val clippedEnd = min(entity.offset + entity.length, end)
                        entity.offset = clippedStart - start
                        entity.length = clippedEnd - clippedStart
                        newEntities.add(entity)
                    }
                    updateObj.text = updateObj.text.substring(start, end)
                    updateObj.entities = newEntities
                }

                SharedConfig.pendingAppUpdate = updateObj
                SharedConfig.pendingAppUpdateBuildVersion = current.versionCode
                SharedConfig.saveConfig()
                pendingBetaUpdate = BetaUpdate(info.appVerName, info.verCode, updateObj.text)
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable)
                finish(callback, CheckResult.Updated(updateObj))
            }
        }
    }

    private fun finish(callback: ((CheckResult) -> Unit)?, result: CheckResult) {
        inflight = false
        InuConfig.UPDATE_LAST_CHECK_MS.value = System.currentTimeMillis()
        callback?.invoke(result)
    }

    private fun currentBuild(): CurrentBuild {
        val ctx = ApplicationLoader.applicationContext
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)

        @Suppress("DEPRECATION")
        return CurrentBuild(pkg.versionCode)
    }

    private fun extractApkInfo(msg: TLRPC.Message): ApkInfo? {
        val media = msg.media as? TLRPC.TL_messageMediaDocument ?: return null
        val doc = media.document ?: return null
        val nameAttr = doc.attributes.filterIsInstance<TLRPC.TL_documentAttributeFilename>().firstOrNull()
            ?: return null
        val match = APK_RE.matchEntire(nameAttr.file_name) ?: return null
        val verName = match.groupValues[1]
        val verCode = match.groupValues[2].toIntOrNull() ?: return null
        val appVerName = verName.replace(SHORT_SHA_RE, "")
        return ApkInfo(verCode, appVerName, doc)
    }

    private fun isNewer(remote: ApkInfo, current: CurrentBuild): Boolean {
        return remote.verCode > current.versionCode
    }

    sealed class CheckResult {
        object InFlight : CheckResult()
        object UpToDate : CheckResult()
        data class Updated(val update: TLRPC.TL_help_appUpdate) : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    private data class ApkInfo(
        val verCode: Int,
        val appVerName: String,
        val document: TLRPC.Document,
    )

    private data class CurrentBuild(
        val versionCode: Int,
    )
}

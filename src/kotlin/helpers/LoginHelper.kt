package desu.inugram.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.PasskeysController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.SerializedData
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_account
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AlertsCreator
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.LaunchActivity
import org.telegram.ui.LoginActivity
import org.telegram.ui.ProxyListActivity
import org.telegram.ui.TwoStepVerificationActivity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.function.BooleanSupplier

object LoginHelper {

    private const val REFRESH_BUFFER_SEC = 5
    private var activeQrLogin: State? = null

    fun onUpdate(update: TLObject?, account: Int) {
        if (update !is TLRPC.TL_updateLoginToken) return
        AndroidUtilities.runOnUIThread {
            activeQrLogin?.takeIf { it.currentAccount == account }?.onPushUpdate()
        }
    }

    @JvmStatic
    fun makeClientDataJSON(get: Boolean, challenge: String, origin: String): String =
        JSONObject().apply {
            put("type", if (get) "webauthn.get" else "webauthn.create")
            put("challenge", challenge)
            put("origin", origin)
        }.toString()

    @JvmStatic
    fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8))

    @JvmStatic
    fun addMenuButton(parent: FrameLayout, loginActivity: LoginActivity, currentAccount: Int, showLoginItems: BooleanSupplier): ImageView {
        val padding = dp(4f)
        val button = ImageView(parent.context).apply {
            setImageResource(R.drawable.ic_ab_other)
            setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setPadding(padding, padding, padding, padding)
            setOnClickListener { v -> showOptionsMenu(loginActivity, v, currentAccount, showLoginItems.asBoolean) }
        }
        parent.addView(button, LayoutHelper.createFrame(32, 32f, Gravity.RIGHT or Gravity.TOP, 0f, 16f, 16f, 0f))
        return button
    }

    private fun showOptionsMenu(loginActivity: LoginActivity, anchor: View, currentAccount: Int, showLoginItems: Boolean) {
        val opts = ItemOptions.makeOptions(loginActivity, anchor)
        if (showLoginItems) {
            opts.add(R.drawable.msg_qrcode, getString(R.string.InuLoginViaQr)) {
                showQrLoginDialog(loginActivity, currentAccount)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                opts.add(R.drawable.filled_access_fingerprint, getString(R.string.InuLoginViaPasskey)) {
                    passkeyLogin(loginActivity, currentAccount)
                }
            }
        }
        opts.add(R.drawable.outline_shield_check, getString(R.string.Proxy)) {
            loginActivity.presentFragment(ProxyListActivity())
        }
        opts.add(R.drawable.msg_delete, getString(R.string.InuResetStorage)) {
            val activity = loginActivity.parentActivity ?: return@add
            AlertDialog.Builder(activity)
                .setTitle(getString(R.string.InuResetStorage))
                .setMessage(getString(R.string.InuResetStorageConfirm))
                .setPositiveButton(getString(R.string.OK)) { _, _ ->
                    MessagesController.getInstance(currentAccount).performLogout(2)
                    loginActivity.presentFragment(LoginActivity(currentAccount), true)
                }
                .setNegativeButton(getString(R.string.Cancel), null)
                .show()
        }
        opts.show()
    }

    private fun fetchAndShowPasswordPage(loginActivity: LoginActivity, currentAccount: Int) {
        val req = TL_account.getPassword()
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, { res, err ->
            AndroidUtilities.runOnUIThread {
                if (err != null || res !is TL_account.Password) return@runOnUIThread
                if (!TwoStepVerificationActivity.canHandleCurrentPassword(res, true)) {
                    AlertsCreator.showUpdateAppAlert(loginActivity.parentActivity, getString(R.string.UpdateAppAlert), true)
                    return@runOnUIThread
                }
                val bundle = Bundle()
                val data = SerializedData(res.objectSize)
                res.serializeToStream(data)
                bundle.putString("password", Utilities.bytesToHex(data.toByteArray()))
                loginActivity.setPage(LoginActivity.VIEW_PASSWORD, true, bundle, false)
            }
        }, ConnectionsManager.RequestFlagFailOnServerErrors or ConnectionsManager.RequestFlagWithoutLogin)
    }

    private fun passkeyLogin(loginActivity: LoginActivity, currentAccount: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val context = loginActivity.context ?: return
        val testBackend = ConnectionsManager.getInstance(currentAccount).isTestBackend
        PasskeysController.login(context, currentAccount, true) { userId, authObject, err ->
            if ("EMPTY" == err || "CANCELLED" == err) return@login
            if (userId != 0L && loginActivity.parentActivity is LaunchActivity) {
                for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                    val cfg = UserConfig.getInstance(a)
                    if (!cfg.isClientActivated) continue
                    if (cfg.clientUserId == userId && ConnectionsManager.getInstance(a).isTestBackend == testBackend) {
                        if (UserConfig.selectedAccount != a) {
                            (loginActivity.parentActivity as LaunchActivity).switchToAccount(a, true)
                        }
                        loginActivity.finishFragment()
                        return@login
                    }
                }
            }
            when {
                err?.contains("SESSION_PASSWORD_NEEDED") == true -> fetchAndShowPasswordPage(loginActivity, currentAccount)
                err != null -> if (BuildVars.DEBUG_VERSION) BulletinFactory.of(loginActivity).showForError(err)
                authObject is TLRPC.TL_auth_authorization -> loginActivity.onAuthSuccess(authObject)
            }
        }
    }

    private fun showQrLoginDialog(loginActivity: LoginActivity, currentAccount: Int) {
        val context = loginActivity.parentActivity ?: return
        activeQrLogin?.finish()
        val state = State(loginActivity, currentAccount)
        activeQrLogin = state
        state.buildAndShowDialog(context)
        ConnectionsManager.getInstance(currentAccount).cleanup(false)
        state.export()
    }

    private class State(val loginActivity: LoginActivity, val currentAccount: Int) {
        private var dialog: AlertDialog? = null
        private var imageView: ImageView? = null
        private var lastBitmap: Bitmap? = null
        private var pendingPoll: Runnable? = null
        private var pendingRequestId = 0
        private var done = false

        fun buildAndShowDialog(context: Context) {
            val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

            val title = TextView(context).apply {
                typeface = AndroidUtilities.bold()
                gravity = Gravity.CENTER_HORIZONTAL
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
                setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
                text = getString(R.string.InuLoginViaQr)
            }
            layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8f, 24f, 8f, 0f))

            val description = TextView(context).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
                text = getString(R.string.InuQrLoginMessage)
                setPadding(0, dp(8f), 0, 0)
            }
            layout.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8f, 0f, 8f, 0f))

            val image = object : ImageView(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val size = MeasureSpec.getSize(widthMeasureSpec)
                    super.onMeasure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
                }
            }.apply {
                scaleType = ImageView.ScaleType.FIT_XY
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.measuredWidth, view.measuredHeight, dp(12f).toFloat())
                    }
                }
                clipToOutline = true
            }
            imageView = image
            layout.addView(image, LayoutHelper.createLinear(240, 240, Gravity.CENTER_HORIZONTAL or Gravity.TOP, 24, 24, 24, 24))

            dialog = AlertDialog.Builder(context)
                .setView(layout)
                .setNegativeButton(getString(R.string.Cancel)) { dlg, _ -> dlg.dismiss() }
                .create().also {
                    it.setOnDismissListener { finish() }
                    loginActivity.showDialog(it)
                }
        }

        fun export() {
            if (done) return
            val req = TLRPC.TL_auth_exportLoginToken().apply {
                api_id = BuildVars.APP_ID
                api_hash = BuildVars.APP_HASH
                for (i in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                    val cfg = UserConfig.getInstance(i)
                    if (cfg.isClientActivated) except_ids.add(cfg.clientUserId)
                }
            }
            sendRequest(req)
        }

        private fun sendRequest(req: TLObject) {
            pendingRequestId = ConnectionsManager.getInstance(currentAccount).sendRequest(
                req,
                { response, error -> AndroidUtilities.runOnUIThread { onResponse(response, error) } },
                ConnectionsManager.RequestFlagFailOnServerErrors or
                    ConnectionsManager.RequestFlagWithoutLogin or
                    ConnectionsManager.RequestFlagTryDifferentDc or
                    ConnectionsManager.RequestFlagEnableUnauthorized,
            )
        }

        private fun onResponse(response: TLObject?, error: TLRPC.TL_error?) {
            if (done) return
            pendingRequestId = 0
            if (error != null) {
                handleError(error.text ?: "UNKNOWN_ERROR")
                return
            }
            when (response) {
                is TLRPC.TL_auth_loginToken -> {
                    renderQr(response.token)
                    val remaining = response.expires - ConnectionsManager.getInstance(currentAccount).currentTime - REFRESH_BUFFER_SEC
                    val refreshIn = remaining.coerceAtLeast(5)
                    val poll = Runnable {
                        pendingPoll = null
                        export()
                    }
                    pendingPoll = poll
                    AndroidUtilities.runOnUIThread(poll, refreshIn * 1000L)
                }
                is TLRPC.TL_auth_loginTokenSuccess -> {
                    finish()
                    (response.authorization as? TLRPC.TL_auth_authorization)?.let { loginActivity.onAuthSuccess(it) }
                }
                is TLRPC.TL_auth_loginTokenMigrateTo -> {
                    ConnectionsManager.getInstance(currentAccount).setDefaultDatacenterId(response.dc_id)
                    sendRequest(TLRPC.TL_auth_importLoginToken().apply { token = response.token })
                }
                else -> handleError("UNEXPECTED_RESPONSE")
            }
        }

        private fun renderQr(token: ByteArray) {
            val link = "tg://login?token=" + Base64.encodeToString(
                token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            try {
                val hints = mapOf<EncodeHintType, Any>(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to 0,
                )
                lastBitmap = QRCodeWriter().encode(link, 768, 768, hints, lastBitmap)
                imageView?.setImageBitmap(lastBitmap)
            } catch (e: Exception) {
                FileLog.e(e)
            }
        }

        private fun handleError(text: String) {
            finish()
            if (text.contains("SESSION_PASSWORD_NEEDED")) {
                fetchAndShowPasswordPage(loginActivity, currentAccount)
            } else if (BuildVars.DEBUG_VERSION) {
                BulletinFactory.of(loginActivity).showForError(text)
            }
        }

        fun onPushUpdate() {
            if (done) return
            cancelPending()
            export()
        }

        fun finish() {
            if (done) return
            done = true
            if (activeQrLogin === this) activeQrLogin = null
            cancelPending()
            dialog?.dismiss()
            dialog = null
        }

        private fun cancelPending() {
            pendingPoll?.let { AndroidUtilities.cancelRunOnUIThread(it) }
            pendingPoll = null
            if (pendingRequestId != 0) {
                ConnectionsManager.getInstance(currentAccount).cancelRequest(pendingRequestId, true)
                pendingRequestId = 0
            }
        }
    }
}

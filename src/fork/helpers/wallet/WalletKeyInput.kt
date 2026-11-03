package desu.inugram.helpers.wallet

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.ui.ExpandableFrameLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Wallet.GramWallet.SecretPhrase
import org.telegram.ui.Wallet.WalletEngine2
import org.telegram.ui.Wallet.WalletImportFragment

class WalletKeyInput(
    context: Context,
    private val currentAccount: Int,
    private val resourcesProvider: Theme.ResourcesProvider?,
    private val onChanged: Runnable,
) {
    val input = EditTextBoldCursor(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        typeface = AndroidUtilities.getTypeface("fonts/rmono.ttf")
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider))
        setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider))
        setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider))
        hint = LocaleController.getString(R.string.InuWalletPrivateKeyHint)
        background = Theme.createRoundRectDrawable(dp(16f), Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider), .50f))
        setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        minLines = 4
    }

    private val addressView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        typeface = AndroidUtilities.getTypeface("fonts/rmono.ttf")
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider))
        background = Theme.createRoundRectDrawable(dp(10f), Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider))
        setPadding(dp(10f), dp(12f), dp(10f), dp(12f))
        gravity = Gravity.CENTER
    }
    private val statusView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
    }
    private val addressFrame = ExpandableFrameLayout(context).apply {
        addView(addressView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP, 16f, 12f, 16f, 0f))
        setExpanded(false, false)
    }
    private val statusFrame = ExpandableFrameLayout(context).apply {
        addView(statusView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP, 16f, 8f, 16f, 0f))
        setExpanded(false, false)
    }

    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        addView(statusFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        addView(addressFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
    }

    private var secret: SecretPhrase? = null
    private var generation = 0
    private var pending: Runnable? = null
    private var recovery: Runnable? = null

    val hasAddress get() = addressFrame.isExpanded
    val isResolved get() = secret != null

    init {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = resolve(s?.toString().orEmpty())
        })
    }

    fun copySecret(): SecretPhrase? = secret?.copy()

    fun destroy() {
        generation++
        cancelPending()
        setSecret(null)
    }

    private fun cancelPending() {
        pending?.let { Utilities.globalQueue.cancelRunnable(it) }
        recovery?.run()
        recovery = null
    }

    private fun setSecret(value: SecretPhrase?) {
        secret?.close()
        secret = value
    }

    private fun resolve(text: String) {
        val current = ++generation
        cancelPending()
        setSecret(null)
        if (text.isBlank()) {
            show(null, null, false)
            return
        }
        addressFrame.setExpanded(false, true)
        onChanged.run()
        pending = Runnable {
            val tokens = text.trim().split(Regex("\\s+"))
            if (tokens.size == 13 && WalletEngine2.isValidAddress(tokens[0])) {
                AndroidUtilities.runOnUIThread {
                    if (current == generation) show(null, LocaleController.getString(R.string.InuWalletRecoverSearching), false)
                }
                val cancel = SecretPhrase.fromWords(tokens.drop(1).map { it.lowercase() }).use { words ->
                    WalletEngine2.inu_recoverWalletSecret(currentAccount, tokens[0], words) { recovered, error ->
                        if (current != generation) return@inu_recoverWalletSecret
                        recovery = null
                        if (recovered == null) {
                            show(null, LocaleController.getString(getRecoverErrorString(error)), true)
                        } else {
                            setSecret(recovered.copy())
                            show(tokens[0], null, false)
                        }
                    }
                }
                AndroidUtilities.runOnUIThread {
                    if (current == generation) recovery = cancel else cancel?.run()
                }
                return@Runnable
            }
            val entered = runCatching { WalletKeyHelper.readSecretInput(text) }.getOrNull()
            val address = entered?.let { runCatching { WalletEngine2.secretPhraseToAddress(it) }.getOrNull() }
            AndroidUtilities.runOnUIThread {
                if (current != generation || address == null) {
                    entered?.close()
                    if (current == generation) show(null, LocaleController.getString(R.string.InuWalletPrivateKeyInvalid), true)
                    return@runOnUIThread
                }
                setSecret(entered)
                show(address, null, false)
            }
        }.also { Utilities.globalQueue.postRunnable(it, 300) }
    }

    private fun getRecoverErrorString(error: String?) = when (error) {
        "INU_RECOVER_INVALID" -> R.string.InuWalletRecoverInvalid
        "INU_RECOVER_NOT_FOUND" -> R.string.InuWalletRecoverNotFound
        "INU_RECOVER_REPLACED" -> R.string.InuWalletRecoverReplaced
        else -> R.string.InuWalletRecoverFailed
    }

    private fun show(address: String?, status: String?, isError: Boolean) {
        if (address != null) addressView.text = WalletImportFragment.formatAddressTwoLines(address)
        if (status != null) {
            statusView.text = status
            statusView.setTextColor(Theme.getColor(if (isError) Theme.key_text_RedRegular else Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider))
        }
        addressFrame.setExpanded(address != null, true)
        statusFrame.setExpanded(status != null, true)
        onChanged.run()
    }
}

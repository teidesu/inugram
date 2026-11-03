package desu.inugram.helpers.wallet

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.SpannableString
import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.Bulletin
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.IconBackgroundColors
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.LoadingSpan
import org.telegram.ui.Components.TableView
import org.telegram.ui.LaunchActivity
import org.telegram.ui.Wallet.GramWallet
import org.telegram.ui.Wallet.GramWallet.SecretPhrase
import org.telegram.ui.Wallet.NewBottomSheet
import org.telegram.ui.Wallet.WalletEngine2
import org.telegram.ui.Stories.recorder.ButtonWithCounterView
import org.telegram.ui.Wallet.WalletActivity
import org.telegram.ui.Wallet.WalletKeysBackupFragment
import org.telegram.ui.Wallet.WalletSecretPhraseFragment
import org.telegram.ui.Wallet.WalletSendSelectSender
import org.telegram.ui.Wallet.WalletTestPhraseFragment

object WalletKeyHelper {
    const val BACKUP_UNAVAILABLE = "INU_PRIVATE_KEY_BACKUP"

    private val prefs by lazy {
        ApplicationLoader.applicationContext.getSharedPreferences("inugram_wallet", Context.MODE_PRIVATE)
    }

    @JvmStatic
    private external fun nativeIsPrivateKey(secret: ByteArray): Boolean

    @JvmStatic
    private external fun nativeNormalizePrivateKey(secret: ByteArray): ByteArray?

    @JvmStatic
    fun isPrivateKey(phrase: SecretPhrase): Boolean {
        val bytes = phrase.copyBytes()
        try {
            return nativeIsPrivateKey(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    @JvmStatic
    fun isPrivateKey(words: List<String>): Boolean = SecretPhrase.fromWords(words).use { isPrivateKey(it) }

    @JvmStatic
    fun extractKey(words: List<String>): String? = words.firstOrNull()?.takeIf { isPrivateKey(words) }

    @JvmStatic
    fun readSecretInput(text: CharSequence): SecretPhrase {
        val tokens = text.trim().split(Regex("\\s+"))
        // a base64 key is case-sensitive, the words after it are not
        SecretPhrase.fromWords(listOf(tokens[0]) + tokens.drop(1).map { it.lowercase() }).use { entered ->
            val bytes = entered.copyBytes()
            try {
                val normalized = nativeNormalizePrivateKey(bytes) ?: return SecretPhrase.fromWords(tokens.map { it.lowercase() })
                try {
                    return SecretPhrase(normalized)
                } finally {
                    normalized.fill(0)
                }
            } finally {
                bytes.fill(0)
            }
        }
    }

    @JvmStatic
    fun createKeyView(context: Context, resourcesProvider: Theme.ResourcesProvider?, key: String): TextView {
        return TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            typeface = AndroidUtilities.getTypeface("fonts/rmono.ttf")
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider))
            background = Theme.createRoundRectDrawable(dp(10f), Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider))
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
            text = key
        }
    }

    @JvmStatic
    fun isUnrotatedKey(phrase: SecretPhrase): Boolean = phrase.wordCount() == 1 && isPrivateKey(phrase)

    @JvmStatic
    fun rememberKeyWallet(address: String?, anchorPublicKey: ByteArray?) {
        if (address == null || anchorPublicKey == null) return
        val key = Utilities.bytesToHex(anchorPublicKey)
        if (prefs.getString(address, null) != key) prefs.edit { putString(address, key) }
    }

    @JvmStatic
    fun rememberKeyWallet(address: String?, phrase: SecretPhrase) {
        if (isPrivateKey(phrase)) rememberKeyWallet(address, WalletEngine2.secretPhraseToAnchorPublicKey(phrase))
    }

    @JvmStatic
    fun isKeyWallet(wallet: GramWallet): Boolean = wallet.address?.let { prefs.contains(it) } == true

    @JvmStatic
    fun isUnrotatedKeyWallet(wallet: GramWallet): Boolean {
        val address = wallet.address ?: return false
        val publicKey = wallet.publicKey ?: return false
        return prefs.getString(address, null) == Utilities.bytesToHex(publicKey)
    }

    @JvmStatic
    fun onKeyReplaced() {
        AndroidUtilities.runOnUIThread({
            val fragment = LaunchActivity.getSafeLastFragment() ?: return@runOnUIThread
            BulletinFactory.of(fragment)
                .createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.InuWalletKeyReplaced), LocaleController.getString(R.string.InuWalletKeyReplacedInfo))
                .setDuration(Bulletin.DURATION_PROLONG)
                .show()
        }, 1000)
    }

    @JvmStatic
    fun offerKeyReplacement() {
        AndroidUtilities.runOnUIThread({
            LaunchActivity.getSafeLastFragment()?.let { startKeyReplacement(it) }
        }, 600)
    }

    @JvmStatic
    fun startKeyReplacement(fragment: BaseFragment) {
        val context = fragment.context ?: return
        val resourcesProvider = fragment.resourceProvider
        val wallet = GramWallet.getInstance(fragment.currentAccount)
        val textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider)
        val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val closeButton = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.ic_ab_close)
            colorFilter = PorterDuffColorFilter(textColor, PorterDuff.Mode.SRC_IN)
            background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_CIRCLE_20DP)
            contentDescription = LocaleController.getString(R.string.Close)
        }
        layout.addView(FrameLayout(context).apply {
            addView(closeButton, LayoutHelper.createFrame(48, 48f, Gravity.LEFT or Gravity.CENTER_VERTICAL, 4f, -2f, 0f, 0f))
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56))

        val keyIcon = FrameLayout(context).apply {
            background = Theme.createCircleDrawable(dp(76f), IconBackgroundColors.GRAY.top, IconBackgroundColors.GRAY.bottom)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.wallet_transaction_key)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LayoutHelper.createFrame(44, 44, Gravity.CENTER))
        }
        layout.addView(keyIcon, LayoutHelper.createLinear(76, 76, Gravity.CENTER_HORIZONTAL, 0, -20, 0, 0))
        layout.addView(TextView(context).apply {
            text = LocaleController.getString(R.string.InuWalletReplaceKeyTitle)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 26f, 12f, 26f, 0f))
        layout.addView(TextView(context).apply {
            text = LocaleController.getString(R.string.InuWalletReplaceKeyInfo)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            gravity = Gravity.CENTER
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 26f, 8f, 26f, 24f))

        val sheet = arrayOfNulls<NewBottomSheet>(1)
        val feeView = TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(textColor)
        }
        feeView.text = SpannableString(LocaleController.getString(R.string.Loading)).apply {
            setSpan(LoadingSpan(feeView, dp(150f)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val table = TableView(context, resourcesProvider)
        wallet.address?.let { address ->
            table.addRowMonospaced(LocaleController.getString(R.string.WalletAddress), WalletActivity.formatTransactionAddress(address) {
                BulletinFactory.of(sheet[0]!!.topBulletinContainer, resourcesProvider)
                    .createSimpleBulletin(R.raw.copy, LocaleController.getString(R.string.WalletAddressCopiedBulletin)).show(false)
            }, 14, null)
        }
        table.addRow(LocaleController.getString(R.string.WalletFee), feeView)
        layout.addView(table, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14f, 0f, 14f, 0f))

        val button = ButtonWithCounterView(context, resourcesProvider).setRound()
        button.setText(LocaleController.getString(R.string.InuWalletReplaceKeyButton))
        button.isEnabled = false
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 14f, 14f, 14f, 14f))

        sheet[0] = NewBottomSheet(context, layout, resourcesProvider).apply {
            useBackgroundTopPadding = false
        }
        closeButton.setOnClickListener { sheet[0]?.dismiss() }

        var fee = -1L
        button.setOnClickListener {
            if (fee < 0 || button.isLoading) return@setOnClickListener
            if (wallet.balance < fee) {
                sheet[0]?.dismiss()
                showTopUpAlert(fragment, wallet, fee)
                return@setOnClickListener
            }
            button.setLoading(true)
            wallet.prepareDisableBackup { prepared, error ->
                button.setLoading(false)
                if (prepared == null) {
                    BulletinFactory.of(sheet[0]!!.topBulletinContainer, resourcesProvider).showForError(error ?: "NULL_ERROR")
                    return@prepareDisableBackup
                }
                if (sheet[0]?.isShowing != true || fragment.isFinished) {
                    prepared.close()
                    return@prepareDisableBackup
                }
                prepared.inu_rotateOnly = true
                sheet[0]?.dismiss()
                if (!fragment.presentFragment(createReplacementPhraseFragment(wallet, prepared))) prepared.close()
            }
        }
        sheet[0]!!.show()

        wallet.emulateDisableBackup { estimated, error ->
            if (sheet[0]?.isShowing != true) return@emulateDisableBackup
            if (estimated == null) {
                sheet[0]?.dismiss()
                BulletinFactory.of(fragment).showForError(error ?: "NULL_ERROR")
                return@emulateDisableBackup
            }
            fee = estimated
            feeView.text = WalletActivity.formatTransactionFee(fragment.currentAccount, estimated, resourcesProvider)
            button.isEnabled = true
        }
    }

    private fun showTopUpAlert(fragment: BaseFragment, wallet: GramWallet, fee: Long) {
        val context = fragment.context ?: return
        AlertDialog.Builder(context, fragment.resourceProvider)
            .setTitle(LocaleController.getString(R.string.WalletNotEnoughGram))
            .setMessage(LocaleController.formatSpannable(R.string.WalletUpdateSecretPhraseInsufficientFunds, GramWallet.formatGramCapital(fee), wallet.formatCurrency(fee, true)))
            .setNegativeButton(LocaleController.getString(R.string.WalletNotNow), null)
            .setPositiveButton(LocaleController.getString(R.string.WalletTopUp)) { di, which ->
                val loading = (di as AlertDialog).makeButtonLoading(which)
                loading.init()
                WalletActivity.openOnramp(fragment.currentAccount, 0) { error ->
                    loading.end()
                    if (!error.isNullOrEmpty()) BulletinFactory.of(fragment).showForError(error)
                }
            }
            .show()
    }

    private fun createReplacementPhraseFragment(wallet: GramWallet, prepared: GramWallet.PreparedDisableBackup): WalletSecretPhraseFragment {
        var handedOff = false
        val fragment = object : WalletSecretPhraseFragment() {
            override fun onFragmentDestroy() {
                super.onFragmentDestroy()
                AndroidUtilities.runOnUIThread { if (!handedOff) prepared.close() }
            }
        }
        return fragment.setPhrase(prepared.newSecretPhrase).onTested {
            handedOff = true
            wallet.disableBackup(prepared) { error ->
                prepared.close()
                val last = LaunchActivity.getSafeLastFragment() ?: return@disableBackup
                if (error != null) {
                    BulletinFactory.of(last).showForError(error)
                    return@disableBackup
                }
                returnToWallet(last)
            }
        }
    }

    private fun returnToWallet(last: BaseFragment) {
        val layout = last.parentLayout ?: return
        for (fragment in ArrayList(layout.fragmentStack)) {
            if (fragment !== last && (fragment is WalletSendSelectSender || fragment is WalletSecretPhraseFragment || fragment is WalletTestPhraseFragment || fragment is WalletKeysBackupFragment)) {
                layout.removeFragmentFromStack(fragment)
            }
        }
        last.finishFragment()
    }
}

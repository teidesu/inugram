package desu.inugram.helpers

import desu.inugram.InuConfig
import org.telegram.tgnet.TLRPC

object SuggestionFilter {
    private val hiddenByFlag: Map<String, InuConfig.BoolItem> = mapOf(
        "BIRTHDAY_SETUP" to InuConfig.HIDE_SUGGESTION_BIRTHDAY_SETUP,
        "BIRTHDAY_CONTACTS_TODAY" to InuConfig.HIDE_SUGGESTION_BIRTHDAY_CONTACTS,
        "VALIDATE_PASSWORD" to InuConfig.HIDE_SUGGESTION_PASSWORD,
        "SETUP_PASSWORD" to InuConfig.HIDE_SUGGESTION_PASSWORD,
        "VALIDATE_PHONE_NUMBER" to InuConfig.HIDE_SUGGESTION_PHONE,
        "PREMIUM_ANNUAL" to InuConfig.HIDE_SUGGESTION_PREMIUM,
        "PREMIUM_UPGRADE" to InuConfig.HIDE_SUGGESTION_PREMIUM,
        "PREMIUM_RESTORE" to InuConfig.HIDE_SUGGESTION_PREMIUM,
        "PREMIUM_CHRISTMAS" to InuConfig.HIDE_SUGGESTION_PREMIUM,
        "PREMIUM_GRACE" to InuConfig.HIDE_SUGGESTION_PREMIUM,
    )

    @JvmStatic
    fun filterPromoData(res: TLRPC.TL_help_promoData) {
        res.pending_suggestions.removeAll { hiddenByFlag[it]?.value == true }
        if (InuConfig.HIDE_SUGGESTION_CUSTOM.value) {
            res.custom_pending_suggestion = null
        }
    }
}

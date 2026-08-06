package desu.inugram.core.plugins

/**
 * rpc methods refused in `invokeRpc`/`interceptRpc` under every grant but
 * `unsafe.disableApiFiltering`, because holding them is a complete account takeover. see
 * `src/plugins/common.d.ts` "account-takeover surfaces are filtered".
 */
object TakeoverMethods {
    val ACCOUNT_METHODS: Set<String> = setOf(
        "account.getPasskeys",
        "account.deletePasskey",
        "account.registerPasskey",
        "account.initPasskeyRegistration",
        "account.registerDevice",
        "account.unregisterDevice",
        "account.deleteAccount",
        "account.changePhone",
        "account.getAuthorizations",
        "account.resetAuthorization",
        "account.acceptAuthorization",
        "account.verifyPhone",
        "account.verifyEmail",
        "account.resetPassword",
    )

    fun isBlocked(name: String): Boolean = name.startsWith("auth.") || name in ACCOUNT_METHODS
}

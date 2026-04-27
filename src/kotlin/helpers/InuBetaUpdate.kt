package desu.inugram.helpers

import org.telegram.messenger.BetaUpdate

class InuBetaUpdate(
    version: String,
    versionCode: Int,
    changelog: String?,
    val shortSha: String?,
) : BetaUpdate(version, versionCode, changelog) {
    override fun higherThan(other: BetaUpdate?): Boolean {
        if (other == null) return true
        if (other is InuBetaUpdate) {
            if (versionCode != other.versionCode) return versionCode > other.versionCode
            return shortSha != null && shortSha != other.shortSha
        }
        return super.higherThan(other)
    }
}

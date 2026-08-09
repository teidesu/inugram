package desu.inugram.core.plugins

object FsQuota {
    /** Keep in sync with Rust `fs::DEFAULT_QUOTA_BYTES`. */
    const val DEFAULT_BYTES = 50L * 1024 * 1024

    const val UNCAPPED = -1L

    private val SIZE = Regex("""(\d+)(kb|mb|gb)""", RegexOption.IGNORE_CASE)

    fun parseSize(scope: String): Long? {
        val match = SIZE.matchEntire(scope.trim()) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val unit = when (match.groupValues[2].lowercase()) {
            "kb" -> 1024L
            "mb" -> 1024L * 1024
            else -> 1024L * 1024 * 1024
        }
        val ceiling = Long.MAX_VALUE / 2
        if (amount > ceiling / unit) return ceiling
        return amount * unit
    }

    fun forGrants(grants: List<String>): Long? {
        var quota: Long? = null
        var sawFs = false
        for (token in grants) {
            val grant = PluginPermissions.parseGrant(token) ?: continue
            if (grant.name == "unsafe.fs") return UNCAPPED
            if (grant.name != "fs") continue
            sawFs = true
            for (scope in grant.scopes) {
                val size = parseSize(scope) ?: continue
                if (quota == null || size > quota) quota = size
            }
        }
        if (!sawFs) return null
        return quota ?: DEFAULT_BYTES
    }
}

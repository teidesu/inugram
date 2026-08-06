package desu.inugram.core.plugins

/**
 * The `@grant fs(200mb)` size grammar, and the only place it is written.
 *
 * [GrantValidator] refuses an install over a scope this cannot read and the host turns the same
 * scope into a cap, so a second copy of the shape means a grant that validates and then parses to
 * nothing - which falls back to the default cap and gives a plugin less disk than it asked for with
 * no diagnostic anywhere.
 */
object FsQuota {
    /** the cap `@grant fs` alone buys, per `fs.d.ts`. Keep in sync with rust `fs::DEFAULT_QUOTA_BYTES`. */
    const val DEFAULT_BYTES = 50L * 1024 * 1024

    /** what `installFs` is passed for `unsafe.fs`; rust turns it into `quota()` answering `Infinity` */
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
        // a manifest asking for exabytes is a typo, not a request; clamping keeps the arithmetic in
        // rust away from an overflow it would have to defend against, and stays clear of the
        // sentinel [UNCAPPED] turns into
        val ceiling = Long.MAX_VALUE / 2
        if (amount > ceiling / unit) return ceiling
        return amount * unit
    }

    /**
     * the cap the manifest asked for, or null when it declared no `fs` at all - which is a
     * different answer from "asked for the default", and is what stops an engine being handed a
     * scoped directory it may never use.
     *
     * A plugin that declared `fs` more than once gets the largest number it named, the only reading
     * under which adding a grant line cannot take storage away from it.
     */
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

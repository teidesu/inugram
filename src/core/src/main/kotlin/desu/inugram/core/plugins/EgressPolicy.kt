package desu.inugram.core.plugins

import java.net.URI

/** Decides which fetch hosts are allowed. */
object EgressPolicy {
    /**
     * Returns the URL's connection host, or null for unsupported URLs.
     * Checks the original string to reject spellings that different parsers interpret differently.
     */
    fun parseHost(url: String): String? {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        // `http://allowed.com@127.0.0.1/` reads as one host and connects to another
        if (uri.rawUserInfo != null) return null
        val host = uri.host ?: return null
        return host.removeSurrounding("[", "]").trimEnd('.').lowercase().ifEmpty { null }
    }

    private fun refuse(code: String, message: String, grant: String? = null) =
        PluginWire.encodePluginError(code, message, grant = grant)

    fun screenHop(permissions: PluginPermissions, url: String): String? {
        val host = parseHost(url)
            ?: return refuse("invalid-argument", "fetch: '$url' is not an http(s) url this api will follow")
        if (!permissions.allows("fetch", host, ScopeMatch.DOMAIN)) {
            return refuse("not-granted", "missing grant: fetch($host)", grant = "fetch($host)")
        }
        return null
    }
}

package desu.inugram.core.plugins

import java.net.URI

/**
 * Decides which fetch hosts and resolved addresses are allowed. Kept separate from transport
 * so tests can pass fixed resolver results to [screenHop] without sockets or live DNS.
 */
object EgressPolicy {
    /**
     * Returns the URL's connection host, or null for unsupported URLs.
     * Checks the original string to reject spellings that different parsers interpret differently.
     */
    fun hostOf(url: String): String? {
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

    /**
     * every range a plugin has no business reaching from a grant reading "arbitrary http": loopback
     * and link-local (the device's own services), the private ones (the user's lan), and the
     * shared/benchmark/multicast ones.
     *
     * Fail-closed on an address length this does not recognise.
     */
    fun isBlockedAddress(raw: ByteArray): Boolean {
        when (raw.size) {
            4 -> {
                val a = raw[0].toInt() and 0xff
                val b = raw[1].toInt() and 0xff
                return when {
                    a == 0 -> true // "this network"
                    a == 10 -> true
                    a == 127 -> true // loopback
                    a == 100 && b in 64..127 -> true // carrier-grade nat
                    a == 169 && b == 254 -> true // link-local, and the metadata services on it
                    a == 172 && b in 16..31 -> true
                    a == 192 && b == 0 -> true // ietf protocol assignments, incl. 192.0.0.0/24
                    a == 192 && b == 168 -> true
                    a == 198 && b in 18..19 -> true // benchmarking
                    a >= 224 -> true // multicast, reserved, broadcast
                    else -> false
                }
            }
            16 -> {
                val v4 = embeddedIpv4(raw)
                if (v4 != null) return isBlockedAddress(v4)
                val first = raw[0].toInt() and 0xff
                if (first == 0xff) return true // multicast
                if (first and 0xfe == 0xfc) return true // unique local, fc00::/7
                if (first == 0xfe && (raw[1].toInt() and 0xc0) == 0x80) return true // link-local, fe80::/10
                // ::1 and :: - both are this device
                if (raw.take(15).all { it.toInt() == 0 }) return true
                return false
            }
            else -> return true
        }
    }

    /** the three v6 shapes carrying a v4 address - `::ffff:a.b.c.d`, `::a.b.c.d`, `64:ff9b::/96` - each of which reaches the v4 address it embeds */
    private fun embeddedIpv4(raw: ByteArray): ByteArray? {
        val tail = raw.copyOfRange(12, 16)
        val mapped = raw.take(10).all { it.toInt() == 0 } &&
            (raw[10].toInt() and 0xff) == 0xff && (raw[11].toInt() and 0xff) == 0xff
        if (mapped) return tail
        val nat64 = (raw[0].toInt() and 0xff) == 0x00 && (raw[1].toInt() and 0xff) == 0x64 &&
            (raw[2].toInt() and 0xff) == 0xff && (raw[3].toInt() and 0xff) == 0x9b &&
            raw.copyOfRange(4, 12).all { it.toInt() == 0 }
        if (nat64) return tail
        val compatible = raw.take(12).all { it.toInt() == 0 } && tail.any { it.toInt() != 0 }
        if (compatible) return tail
        return null
    }

    private fun refuse(code: String, message: String, grant: String? = null) =
        PluginWire.encodePluginError(code, message, grant = grant)

    /** [resolve] is a parameter so the rule can be tested against addresses rather than against whatever dns says today */
    fun screenHop(
        permissions: PluginPermissions,
        url: String,
        resolve: (String) -> List<ByteArray>,
    ): String? {
        val host = hostOf(url)
            ?: return refuse("invalid-argument", "fetch: '$url' is not an http(s) url this api will follow")
        if (!permissions.allows("fetch", host, ScopeMatch.DOMAIN)) {
            return refuse("not-granted", "missing grant: fetch($host)", grant = "fetch($host)")
        }
        val addresses = try {
            resolve(host)
        } catch (e: Exception) {
            return refuse("network", "fetch: '$host' does not resolve")
        }
        if (addresses.isEmpty()) return refuse("network", "fetch: '$host' does not resolve")
        for (address in addresses) {
            if (isBlockedAddress(address)) {
                return refuse(
                    "forbidden",
                    "fetch: '$host' resolves onto a loopback, link-local or private address, which this api does not reach",
                )
            }
        }
        return null
    }
}

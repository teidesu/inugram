package desu.inugram.helpers

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import desu.inugram.InuConfig
import desu.inugram.core.urlcleaner.UrlCleaner
import org.telegram.messenger.ApplicationLoader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UrlCleanerHelper {
    private const val FILE = "adguard_url_tracking.txt"
    private const val FILTER_URL = "https://filters.adtidy.org/extension/ublock/filters/17.txt"
    private val TIME_UPDATED_RE = Regex("""^!\s*TimeUpdated:\s*(\S+)""", RegexOption.MULTILINE)

    @Volatile private var cleaner: UrlCleaner? = null
    @Volatile private var loaded = false

    /** Date prefix of `! TimeUpdated:` from the active filter source, or null. */
    @Volatile var lastUpdated: String? = null
        private set

    val isUsingOverride: Boolean get() = overrideFile().exists()

    private fun overrideFile() = File(ApplicationLoader.applicationContext.filesDir, FILE)

    private fun readSource(): String =
        overrideFile().takeIf { it.exists() }?.readText()
            ?: ApplicationLoader.applicationContext.assets.open(FILE).bufferedReader().use { it.readText() }

    private fun parseTimeUpdated(text: String): String? =
        TIME_UPDATED_RE.find(text)?.groupValues?.get(1)?.substringBefore('T')

    @Synchronized
    private fun getCleaner(): UrlCleaner? {
        if (loaded) return cleaner
        cleaner = try {
            val text = readSource()
            lastUpdated = parseTimeUpdated(text)
            UrlCleaner.fromAdGuardFilter(text)
        } catch (e: Throwable) {
            Log.e("UrlCleanerHelper", "failed to load filter", e)
            null
        }
        loaded = true
        return cleaner
    }

    @Synchronized
    fun invalidate() {
        cleaner = null
        loaded = false
        lastUpdated = null
    }

    fun preload() { getCleaner() }

    /**
     * Download latest filter. Blocks — call from a background queue.
     * Returns true if the downloaded copy was written; false if it matches the active source.
     */
    @Throws(java.io.IOException::class)
    fun fetchLatest(): Boolean {
        getCleaner()
        val currentTime = lastUpdated

        val conn = (URL(FILTER_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        val downloaded = try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }

        if (parseTimeUpdated(downloaded)?.let { it == currentTime } == true) return false

        val target = overrideFile()
        val tmp = File(target.parentFile, "$FILE.tmp")
        tmp.writeText(downloaded)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("rename failed")
        }
        invalidate()
        return true
    }

    fun resetToBundled() {
        overrideFile().delete()
        invalidate()
    }

    @JvmStatic
    fun clean(uri: Uri): Uri {
        if (!InuConfig.STRIP_TRACKING_PARAMS.value) return uri
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return uri
        val c = getCleaner() ?: return uri
        val original = uri.toString()
        val cleaned = c.clean(original)
        return if (cleaned === original) uri else cleaned.toUri()
    }
}

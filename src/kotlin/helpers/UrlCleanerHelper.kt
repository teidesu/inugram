package desu.inugram.helpers

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.URLSpan
import android.util.Log
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.core.net.toUri
import desu.inugram.InuConfig
import desu.inugram.core.urlcleaner.UrlCleaner
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.ui.Components.URLSpanReplacement
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UrlCleanerHelper {
    private const val FILE = "adguard_url_tracking.txt"
    private const val FILTER_URL = "https://filters.adtidy.org/extension/ublock/filters/17.txt"
    private val TIME_UPDATED_RE = Regex("""^!\s*TimeUpdated:\s*(\S+)""", RegexOption.MULTILINE)

    @Volatile
    private var cleaner: UrlCleaner? = null

    @Volatile
    private var loaded = false

    /** Date prefix of `! TimeUpdated:` from the active filter source, or null. */
    @Volatile
    var lastUpdated: String? = null
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

    fun preload() {
        getCleaner()
    }

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
        if (!InuConfig.STRIP_TRACKING_PARAMS_ON_OPEN.value) return uri
        val original = uri.toString()
        val cleaned = cleanUrl(original)
        return if (cleaned === original) uri else cleaned.toUri()
    }

    private fun cleanUrl(url: String): String {
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") return url
        return getCleaner()?.clean(url) ?: url
    }

    @JvmStatic
    fun cleanUrlString(url: CharSequence): CharSequence {
        if (!InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.value) return url
        val s = url.toString()
        val cleaned = cleanUrl(s)
        return if (cleaned === s) url else cleaned
    }

    /** Scans URLs in [text]; returns same ref if nothing matched/changed. */
    @JvmStatic
    fun cleanText(text: CharSequence?): CharSequence? {
        if (text.isNullOrEmpty() || !InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.value) return text
        val matcher = AndroidUtilities.WEB_URL?.matcher(text) ?: return text
        var out: SpannableStringBuilder? = null
        var shift = 0
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            val original = text.subSequence(start, end).toString()
            val cleaned = cleanUrl(original)
            if (cleaned === original) continue
            if (out == null) out = SpannableStringBuilder(text)
            out.replace(start + shift, end + shift, cleaned)
            shift += cleaned.length - (end - start)
        }
        return out ?: text
    }

    /** Returns true if the paste was consumed (text inserted). */
    @JvmStatic
    fun handleContextMenuPaste(editText: EditText): Boolean {
        if (!InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.value) return false
        val ctx = editText.context ?: return false
        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val clip = cb.primaryClip?.takeIf { it.itemCount >= 1 } ?: return false
        try {
            val raw = clip.getItemAt(0).coerceToText(ctx) ?: return false
            val cleaned = cleanText(raw) ?: return false
            if (cleaned === raw) return false
            val text = editText.text ?: return false
            val start = maxOf(0, editText.selectionStart)
            val end = minOf(text.length, editText.selectionEnd)
            text.replace(start, end, cleaned)
            editText.setSelection(start + cleaned.length)
            return true
        } catch (e: Throwable) {
            FileLog.e(e)
            return false
        }
    }

    /** Intercepts IME commits (Gboard clipboard chips etc.). */
    @JvmStatic
    fun wrapInputConnection(ic: InputConnection?): InputConnection? {
        if (ic == null) return null
        return object : InputConnectionWrapper(ic, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val cleaned = if (text != null && InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.value
                    && text.toString().contains("https://")
                ) cleanText(text) else text
                return super.commitText(cleaned, newCursorPosition)
            }
        }
    }

    @JvmStatic
    fun cleanSpannedUrls(text: Spannable) {
        if (!InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.value) return
        for (span in text.getSpans(0, text.length, URLSpan::class.java)) {
            val url = span.url ?: continue
            val cleaned = cleanUrl(url)
            if (cleaned === url) continue
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            val flags = text.getSpanFlags(span)
            text.removeSpan(span)
            val replacement = if (span is URLSpanReplacement) URLSpanReplacement(cleaned, span.textStyleRun)
            else URLSpan(cleaned)
            text.setSpan(replacement, start, end, flags)
        }
    }
}

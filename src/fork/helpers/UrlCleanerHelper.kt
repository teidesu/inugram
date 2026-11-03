package desu.inugram.helpers

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.SuggestionSpan
import android.text.style.URLSpan
import android.util.Log
import android.util.LruCache
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.core.net.toUri
import desu.inugram.InuConfig
import desu.inugram.core.urlcleaner.UrlCleaner
import desu.inugram.helpers.chat.WebPreviewHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.Utilities
import org.telegram.messenger.browser.Browser
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.EditTextCaption
import org.telegram.ui.Components.LinkPath
import org.telegram.ui.Components.LoadingDrawable
import org.telegram.ui.Components.URLSpanReplacement
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

object UrlCleanerHelper {
    private const val FILE = "adguard_url_tracking.txt"
    private const val FILTER_URL = "https://filters.adtidy.org/android/filters/17.txt"
    private val TIME_UPDATED_RE = Regex("""^!\s*TimeUpdated:\s*(\S+)""", RegexOption.MULTILINE)
    private val EXTRA_RULES = listOf(
        "||soundcloud.com^\$removeparam=ref",
        "||soundcloud.com^\$removeparam=p",
        "||soundcloud.com^\$removeparam=c",
        "||soundcloud.com^\$removeparam=si",
        "||instagram.com^\$removeparam=/^[a-z]{4}=[A-Za-z0-9]+(=|%3D){0,2}\$/",
        "||pinterest.*^\$removeparam=/^(invite_code|sender|sfo)=/",
        "||yandex.*/maps/\$removeparam=si",
        "||maps.yango.com/\$removeparam=si",
        "||google.*/maps/\$removeparam=entry",
        "||google.*/maps/\$removeparam=skid",
        "||x.com/*/status/\$removeparam=a",
        "||twitter.com/*/status/\$removeparam=a",
        "||ozon.ru^\$removeparam=/^(from|perehod|sh|short)=/",
    )
    private val REDIRECT_HOSTS = setOf("vm.tiktok.com", "vt.tiktok.com", "on.soundcloud.com", "share.google", "pin.it", "spotify.link", "maps.app.goo.gl")
    private val REDDIT_SHARE_PATH = Regex("^/r/[^/]+/s/[^/]+/?$")
    private val PINTEREST_HOST = Regex("^(www\\.)?pinterest\\.[a-z.]+$")
    private val YANDEX_HOST = Regex("^(www\\.)?yandex\\.[a-z.]+$")
    private val YANDEX_MAPS_SHARE_PATH = Regex("^/maps/-/[^/]+/?$")
    private val OZON_SHARE_PATH = Regex("^/t/[^/]+/?$")
    private class RedirectWrapper(val host: Regex, val path: String, val params: List<String>)
    private val REDIRECT_WRAPPERS = listOf(
        RedirectWrapper(Regex("^(www\\.)?google\\.[a-z.]+$"), "/url", listOf("url", "q")),
        RedirectWrapper(Regex("^(www\\.|m\\.)?youtube\\.com$"), "/redirect", listOf("q")),
        RedirectWrapper(Regex("^lm?\\.facebook\\.com$"), "/l.php", listOf("u")),
        RedirectWrapper(Regex("^l\\.instagram\\.com$"), "", listOf("u")),
        RedirectWrapper(Regex("^(m\\.)?vk\\.(com|ru)$"), "/away.php", listOf("to")),
        RedirectWrapper(Regex("^steamcommunity\\.com$"), "/linkfilter", listOf("url", "u")),
    )
    private const val MAX_UNWRAP_DEPTH = 3
    /** stock persists `Message.params` while a message is unsent, so a send retried after a restart still resolves */
    private const val REDIRECTS_PARAM = "inu_redirects"
    private val resolvedRedirects = LruCache<String, String>(128)

    /** callbacks of the redirects being resolved, mutated on the ui thread */
    private val resolving = ConcurrentHashMap<String, MutableList<(String?) -> Unit>>()

    /** sends held until their redirects resolve, and the later sends of their chat; ui thread */
    private val held = ArrayList<HeldSend>()
    @Volatile private var hasHeld = false
    private var releasing = false

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

    private fun readSource(): String {
        val bundled = ApplicationLoader.applicationContext.assets.open(FILE).bufferedReader().use { it.readText() }
        val file = overrideFile()
        val override = file.takeIf { it.exists() }?.readText() ?: return bundled
        val bundledTime = parseTimestamp(bundled) ?: return override
        val overrideTime = parseTimestamp(override)
        if (overrideTime != null && !bundledTime.isAfter(overrideTime)) return override
        file.delete()
        return bundled
    }

    private fun parseTimeUpdated(text: String): String? =
        TIME_UPDATED_RE.find(text)?.groupValues?.get(1)?.substringBefore('T')

    private fun parseTimestamp(text: String): OffsetDateTime? =
        TIME_UPDATED_RE.find(text)?.groupValues?.get(1)?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

    @Synchronized
    private fun getCleaner(): UrlCleaner? {
        if (loaded) return cleaner
        cleaner = try {
            val text = readSource()
            lastUpdated = parseTimeUpdated(text)
            UrlCleaner.fromAdGuardFilter(text + "\n" + EXTRA_RULES.joinToString("\n"))
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
        val target = unwrapRedirect(url, 0)
        return getCleaner()?.clean(target) ?: target
    }

    private tailrec fun unwrapRedirect(url: String, depth: Int): String {
        if (depth == MAX_UNWRAP_DEPTH) return url
        val uri = url.toUri()
        val host = uri.host?.lowercase() ?: return url
        val path = uri.path.orEmpty().trimEnd('/')
        val wrapper = REDIRECT_WRAPPERS.firstOrNull { it.host.matches(host) && it.path == path } ?: return url
        val target = wrapper.params.firstNotNullOfOrNull { uri.getQueryParameter(it)?.trim() } ?: return url
        val scheme = target.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") return url
        return unwrapRedirect(target, depth + 1)
    }

    private fun isPasteTransformEnabled(): Boolean =
        InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.value ||
            InuConfig.WEB_PREVIEW_REPLACE_ON_PASTE.value && InuConfig.WEB_PREVIEW_REPLACEMENTS_ENABLED.value

    private fun transformPastedUrl(url: String): String {
        val cleaned = if (InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.value) cleanUrl(url) else url
        return if (InuConfig.WEB_PREVIEW_REPLACE_ON_PASTE.value) WebPreviewHelper.applyReplacements(cleaned) else cleaned
    }

    @JvmStatic
    fun cleanUrlString(url: CharSequence): CharSequence {
        if (!isPasteTransformEnabled()) return url
        val s = url.toString()
        val cleaned = transformPastedUrl(s)
        return if (cleaned === s) url else cleaned
    }

    /** Scans URLs in [text]; returns same ref if nothing matched/changed. */
    @JvmStatic
    fun cleanText(text: CharSequence?): CharSequence? {
        if (text.isNullOrEmpty() || !isPasteTransformEnabled()) return text
        return replaceUrls(text, ::transformPastedUrl)
    }

    private fun replaceUrls(
        text: CharSequence,
        transform: (String) -> String,
        onUrl: ((url: String, start: Int, end: Int) -> Unit)? = null,
    ): CharSequence {
        val matcher = AndroidUtilities.WEB_URL?.matcher(text) ?: return text
        var out: SpannableStringBuilder? = null
        var shift = 0
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            val original = text.subSequence(start, end).toString()
            val cleaned = transform(original)
            onUrl?.invoke(original, start + shift, start + shift + cleaned.length)
            if (cleaned === original) continue
            if (out == null) out = SpannableStringBuilder(text)
            out.replace(start + shift, end + shift, cleaned)
            shift += cleaned.length - (end - start)
        }
        if (out != null && out.length != text.length) {
            BaseInputConnection.removeComposingSpans(out)
            for (span in out.getSpans(0, out.length, SuggestionSpan::class.java)) {
                out.removeSpan(span)
            }
        }
        return out ?: text
    }

    /** Returns true if the paste was consumed (text inserted). */
    @JvmStatic
    fun handleContextMenuPaste(editText: EditText): Boolean {
        if (!isPasteTransformEnabled() && !InuConfig.RESOLVE_TRACKABLE_REDIRECTS.value) return false
        val ctx = editText.context ?: return false
        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val clip = cb.primaryClip?.takeIf { it.itemCount >= 1 } ?: return false
        try {
            val raw = clip.getItemAt(0).coerceToText(ctx) ?: return false
            val text = editText.text ?: return false
            val start = maxOf(0, editText.selectionStart)
            val end = minOf(text.length, editText.selectionEnd)
            if (insertResolvingRedirects(editText, raw, start, end)) return true
            val cleaned = cleanText(raw) ?: return false
            if (cleaned === raw) return false
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
    fun wrapInputConnection(ic: InputConnection?, editText: EditText): InputConnection? {
        if (ic == null) return null
        return object : InputConnectionWrapper(ic, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val targets = if (text != null && text.toString().contains("https://")) findRedirectTargets(text) else emptyList()
                if (text != null && targets.isNotEmpty()) {
                    val committed = super.commitText("", newCursorPosition)
                    val position = maxOf(0, editText.selectionStart)
                    resolveAndInsert(editText, text, targets, position, position)
                    return committed
                }
                val cleaned = if (text != null && text.toString().contains("https://")) cleanText(text) else text
                return super.commitText(cleaned, newCursorPosition)
            }
        }
    }

    @JvmStatic
    fun cleanSpannedUrls(text: Spannable) {
        if (!isPasteTransformEnabled()) return
        replaceSpanUrls(text, ::transformPastedUrl)
    }

    private fun replaceSpanUrls(
        text: Spannable,
        transform: (String) -> String,
        onUrl: ((url: String, start: Int, end: Int) -> Unit)? = null,
    ) {
        for (span in text.getSpans(0, text.length, URLSpan::class.java)) {
            val url = span.url ?: continue
            val cleaned = transform(url)
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            onUrl?.invoke(url, start, end)
            if (cleaned === url) continue
            val flags = text.getSpanFlags(span)
            text.removeSpan(span)
            val replacement = if (span is URLSpanReplacement) URLSpanReplacement(cleaned, span.textStyleRun)
            else URLSpan(cleaned)
            text.setSpan(replacement, start, end, flags)
        }
    }

    private fun isTrackableRedirect(url: String): Boolean {
        val uri = url.toUri()
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase()
        val path = uri.path.orEmpty()
        return host in REDIRECT_HOSTS && path.length > 1
            || (host == "www.reddit.com" || host == "reddit.com") && REDDIT_SHARE_PATH.matches(path)
            || YANDEX_HOST.matches(host.orEmpty()) && YANDEX_MAPS_SHARE_PATH.matches(path)
            || host == "ozon.ru" && OZON_SHARE_PATH.matches(path)
    }

    private fun findRedirectTargets(text: CharSequence): List<String> {
        if (!InuConfig.RESOLVE_TRACKABLE_REDIRECTS.value) return emptyList()
        val urls = mutableListOf<String>()
        AndroidUtilities.WEB_URL?.matcher(text)?.let { matcher ->
            while (matcher.find()) urls.add(matcher.group())
        }
        if (text is Spanned) {
            text.getSpans(0, text.length, URLSpan::class.java).mapNotNullTo(urls) { it.url }
        }
        return urls.filter(::isTrackableRedirect).distinct()
    }

    /** Blocks. */
    private fun resolveTrackableRedirect(url: String): String? = when (val host = url.toUri().host?.lowercase().orEmpty()) {
        "vm.tiktok.com", "vt.tiktok.com" -> fetchRedirectLocation(url)
            ?.takeIf { it.host == "www.tiktok.com" && it.path.orEmpty().length > 1 }

        "on.soundcloud.com" -> fetchRedirectLocation(url)
            ?.takeIf { it.host == "soundcloud.com" }

        "share.google" -> fetchRedirectLocation(url)
            ?.takeIf { it.host == "www.google.com" && it.path == "/share.google" }
            ?.let { fetchRedirectLocation(it.toString()) }
            ?.takeIf { it.scheme == "https" || it.scheme == "http" }

        "www.reddit.com", "reddit.com" -> fetchRedirectLocation(url)
            ?.takeIf { it.host == "www.reddit.com" && it.path.orEmpty().contains("/comments/") }

        "pin.it" -> fetchRedirectLocation(url)
            ?.takeIf { it.host == "api.pinterest.com" && it.path.orEmpty().startsWith("/url_shortener/") }
            ?.let { fetchRedirectLocation(it.toString()) }
            ?.takeIf { PINTEREST_HOST.matches(it.host.orEmpty()) && it.path.orEmpty().startsWith("/pin/") }
            ?.let { it.buildUpon().path(it.path.orEmpty().removeSuffix("sent/")).build() }

        "spotify.link" -> fetchRedirectLocation(url)
            ?.takeIf { it.host == "spotify.app.link" }
            ?.let { fetchRedirectLocation(it.toString()) }
            ?.takeIf { it.host == "open.spotify.com" && it.path.orEmpty().length > 1 }

        "maps.app.goo.gl" -> fetchRedirectLocation(url)
            ?.takeIf { it.host == "www.google.com" && it.path.orEmpty().startsWith("/maps/") }

        "ozon.ru" -> fetchRedirectLocation(url)
            ?.takeIf { it.host == "www.ozon.ru" && it.path.orEmpty().length > 1 }

        else -> if (YANDEX_HOST.matches(host)) {
            fetchRedirectLocation(url)?.takeIf { it.host == host && it.path.orEmpty().startsWith("/maps/") }
        } else null
    }?.toString()

    /** Blocks. */
    private fun fetchRedirectLocation(url: String): Uri? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        try {
            if (conn.responseCode !in 300..399) return null
            val location = conn.getHeaderField("Location") ?: return null
            return URL(URL(url), location).toString().toUri()
        } finally {
            conn.disconnect()
        }
    }

    /** Returns true if insertion of [text] at [start, end) was taken over to resolve redirects in the background. */
    @JvmStatic
    fun insertResolvingRedirects(editText: EditText, text: CharSequence, start: Int, end: Int): Boolean {
        val targets = findRedirectTargets(text)
        if (targets.isEmpty()) return false
        resolveAndInsert(editText, text, targets, start, end)
        return true
    }

    private fun resolveAndInsert(editText: EditText, text: CharSequence, targets: List<String>, start: Int, end: Int) {
        val cached = targets.mapNotNull { url -> resolvedRedirects.get(url)?.let { url to it } }.toMap()
        val pending = targets.filter { it !in cached }
        if (pending.isEmpty() || (editText as? EditTextCaption)?.inu_resolveRedirectsInBackground == true) {
            val markers = insertTransformed(editText, text, start, end, cached, pending)
            if (markers.isEmpty()) return
            resolveInBackground(pending) { resolved -> applyResolvedRedirects(editText, markers, resolved) }
            return
        }
        val finish = showLoader(editText.context, null, 300) { insertTransformed(editText, text, start, end, cached, emptyList()) }
        resolveInBackground(pending) { resolved ->
            finish { insertTransformed(editText, text, start, end, cached + resolved, emptyList()) }
        }
    }

    private fun insertTransformed(
        editText: EditText,
        text: CharSequence,
        start: Int,
        end: Int,
        resolved: Map<String, String>,
        pending: List<String>,
    ): List<PendingRedirectSpan> {
        val transform = { url: String -> transformPastedUrl(resolved[url] ?: url) }
        val ranges = mutableListOf<Triple<String, Int, Int>>()
        val collectPending = { url: String, s: Int, e: Int -> if (url in pending) ranges.add(Triple(url, s, e)) }
        val result = SpannableStringBuilder(replaceUrls(text, transform, collectPending))
        replaceSpanUrls(result, transform, collectPending)
        val markers = ranges.map { (url, s, e) ->
            PendingRedirectSpan(url, transformPastedUrl(url)).also { result.setSpan(it, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        val editable = editText.text ?: return emptyList()
        val s = start.coerceIn(0, editable.length)
        val e = end.coerceIn(s, editable.length)
        editable.replace(s, e, result)
        editText.setSelection(s + result.length)
        return markers
    }

    private fun applyResolvedRedirects(editText: EditText, markers: List<PendingRedirectSpan>, resolved: Map<String, String>) {
        val editable = editText.text
        for (marker in markers) {
            marker.resolved = true
            if (editable == null) continue
            val s = editable.getSpanStart(marker)
            val e = editable.getSpanEnd(marker)
            editable.removeSpan(marker)
            val target = resolved[marker.url]?.let(::transformPastedUrl) ?: continue
            if (s < 0) continue
            replaceSpanUrls(editable, { if (it == marker.shown) target else it })
            if (!TextUtils.equals(editable.subSequence(s, e), marker.shown)) continue
            val selStart = Selection.getSelectionStart(editable)
            val selEnd = Selection.getSelectionEnd(editable)
            editable.replace(s, e, target)
            val shift = { p: Int -> if (p >= e) p + target.length - (e - s) else if (p > s) s + target.length else p }
            if (selStart >= 0) Selection.setSelection(editable, shift(selStart), shift(selEnd))
        }
        (editText as? EditTextCaption)?.notifySpansChanged()
        for (marker in markers) marker.onResolved.forEach { it() }
    }

    private fun getPendingRedirects(text: CharSequence?): List<PendingRedirectSpan> =
        (text as? Spanned)?.getSpans(0, text.length, PendingRedirectSpan::class.java)?.filterNot { it.resolved }.orEmpty()

    @JvmStatic
    fun hasPendingRedirects(text: CharSequence?): Boolean = getPendingRedirects(text).isNotEmpty()

    /** Returns true if [send] was deferred until the redirects pending in [editText] resolve. */
    @JvmStatic
    fun sendAfterRedirectsResolved(editText: EditText?, send: Runnable): Boolean {
        val markers = getPendingRedirects(editText?.text)
        if (editText == null || markers.isEmpty()) return false
        val finish = showLoader(editText.context, null, 0) {
            editText.text?.let { text -> getPendingRedirects(text).forEach(text::removeSpan) }
            send.run()
        }
        var remaining = markers.size
        for (marker in markers) {
            marker.onResolved.add { if (--remaining == 0) finish(send::run) }
        }
        return true
    }

    /** marks the redirects [params] carries that are being resolved, or were, for [holdSend] to replace */
    @JvmStatic
    fun markPendingRedirects(params: SendMessagesHelper.SendMessageParams) {
        if (params.retryMessageObject != null || DialogObject.isEncryptedDialog(params.peer)) return
        val text = params.message?.takeIf { it.isNotEmpty() } ?: params.caption ?: return
        val urls = HashSet<String>()
        AndroidUtilities.WEB_URL?.matcher(text)?.let { matcher ->
            while (matcher.find()) urls.add(matcher.group())
        }
        params.entities?.forEach { if (it is TLRPC.TL_messageEntityTextUrl) urls.add(it.url) }
        if (urls.isEmpty()) return
        val candidates = resolving.keys + resolvedRedirects.snapshot().keys
        val pending = candidates.mapNotNull { url -> transformPastedUrl(url).takeIf { it in urls }?.let { it to url } }
        if (pending.isEmpty()) return
        val encoded = pending.joinToString(" ") { (shown, url) -> Uri.encode(shown) + " " + Uri.encode(url) }
        params.params = HashMap(params.params.orEmpty()).apply { put(REDIRECTS_PARAM, encoded) }
    }

    private fun readMarkedRedirects(message: TLRPC.Message): Map<String, String>? =
        message.params?.get(REDIRECTS_PARAM)?.split(' ')?.chunked(2)?.filter { it.size == 2 }?.associate { (shown, url) -> Uri.decode(shown) to Uri.decode(url) }

    private class HeldSend(val account: Int, val dialogId: Long, val scheduled: Boolean, val messages: List<MessageObject>, val send: Runnable) {
        val id = messages.maxOf { it.id }
        var ready = false

        fun isSameChat(other: HeldSend) = account == other.account && dialogId == other.dialogId && scheduled == other.scheduled
    }

    /**
     * Holds the final request of [messages] until the redirects marked in them resolve and replace their urls, and
     * holds the later sends of their chat behind them. Returns true if [send] was deferred
     */
    @JvmStatic
    fun holdSend(account: Int, req: TLObject, messages: List<MessageObject>, scheduled: Boolean, send: Runnable): Boolean {
        if (messages.isEmpty() || messages.any { it.id > 0 }) return false
        if (!hasHeld && messages.none { readMarkedRedirects(it.messageOwner) != null }) return false
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AndroidUtilities.runOnUIThread { if (!holdSend(account, req, messages, scheduled, send)) releaseHeld(send) }
            return true
        }
        if (releasing) return false
        val entry = HeldSend(account, messages[0].dialogId, scheduled, messages, send)
        val marked = messages.mapNotNull { message -> readMarkedRedirects(message.messageOwner)?.let { message to it } }.toMap()
        if (marked.isEmpty() && held.none { it.isSameChat(entry) && it.id > entry.id }) return false
        held.add(entry)
        hasHeld = true
        if (marked.isEmpty()) {
            entry.ready = true
            return true
        }
        resolveInBackground(marked.values.flatMapTo(HashSet()) { it.values }) { resolved ->
            replaceRedirects(account, req, marked, resolved)
            entry.ready = true
            releaseReadySends()
        }
        return true
    }

    private fun releaseHeld(send: Runnable) {
        releasing = true
        try {
            send.run()
        } finally {
            releasing = false
        }
    }

    /** older messages have greater local ids */
    private fun releaseReadySends() {
        held.sortByDescending { it.id }
        val ready = ArrayList<HeldSend>()
        for (entry in held) {
            if (!entry.ready) continue
            if (held.any { !it.ready && it.isSameChat(entry) && it.id > entry.id }) continue
            ready.add(entry)
        }
        held.removeAll(ready.toSet())
        hasHeld = held.isNotEmpty()
        for (entry in ready) {
            val helper = SendMessagesHelper.getInstance(entry.account)
            if (entry.messages.any { helper.isSendingMessage(it.id) }) releaseHeld(entry.send)
        }
    }

    private fun replaceRedirects(account: Int, req: TLObject, marked: Map<MessageObject, Map<String, String>>, resolved: Map<String, String>) {
        val controller = MessagesController.getInstance(account)
        val changed = ArrayList<MessageObject>()
        for ((message, pending) in marked) {
            val owner = message.messageOwner
            owner.params?.remove(REDIRECTS_PARAM)
            val targets = pending.mapNotNull { (shown, url) -> resolved[url]?.let { shown to transformPastedUrl(it) } }.toMap()
            if (targets.isEmpty()) continue
            val replace = { text: String?, entities: List<TLRPC.MessageEntity>?, limit: Int ->
                text?.let { replaceRedirectsInText(it, entities.orEmpty() + owner.entities.orEmpty(), targets, limit) }
                    ?.also { owner.message = it }
            }
            val replaced = when (req) {
                is TLRPC.TL_messages_sendMessage -> replace(req.message, req.entities, controller.getMaxMessageLength())?.also { req.message = it }
                is TLRPC.TL_messages_sendMedia -> replace(req.message, req.entities, controller.captionMaxLengthLimit)?.also { req.message = it }
                is TLRPC.TL_messages_sendMultiMedia -> req.multi_media.find { it.random_id == owner.random_id }?.let { single ->
                    replace(single.message, single.entities, controller.captionMaxLengthLimit)?.also { single.message = it }
                }
                else -> null
            }
            if (replaced != null) changed.add(message)
        }
        if (changed.isEmpty()) return
        for (message in changed) {
            val owner = message.messageOwner
            val mode = when {
                message.scheduled -> ChatActivity.MODE_SCHEDULED
                MessageObject.isWelcomeMessage(owner) -> ChatActivity.MODE_WELCOME_MESSAGES
                owner.quick_reply_shortcut_id != 0 || owner.quick_reply_shortcut != null -> ChatActivity.MODE_QUICK_REPLIES
                else -> 0
            }
            MessagesStorage.getInstance(account).putMessages(arrayListOf(owner), false, true, false, 0, mode, owner.quick_reply_shortcut_id.toLong())
        }
        val notifications = NotificationCenter.getInstance(account)
        for ((dialogId, messages) in changed.groupBy { it.dialogId }) {
            for (message in messages) {
                val shown = controller.dialogMessage.get(dialogId).orEmpty().filter { it.id == message.id && it !== message }
                for (copy in shown) {
                    copy.messageOwner.message = message.messageOwner.message
                    copy.messageOwner.entities = message.messageOwner.entities
                }
                for (instance in shown + message) {
                    if (instance.type == MessageObject.TYPE_TEXT) {
                        instance.applyNewText()
                    } else {
                        instance.caption = null
                        instance.generateCaption()
                    }
                    instance.forceUpdate = true
                }
            }
            notifications.postNotificationName(NotificationCenter.replaceMessagesObjects, dialogId, ArrayList(messages))
        }
        notifications.postNotificationName(NotificationCenter.dialogsNeedReload)
    }

    /**
     * Replaces the urls of [targets] in [text] and in the text urls of [entities], shifting the entities in place.
     * Null if nothing changed or the text would outgrow [limit]
     */
    private fun replaceRedirectsInText(text: String, entities: List<TLRPC.MessageEntity>, targets: Map<String, String>, limit: Int): String? {
        val ranges = ArrayList<Triple<Int, Int, String>>()
        AndroidUtilities.WEB_URL?.matcher(text)?.let { matcher ->
            while (matcher.find()) targets[matcher.group()]?.let { ranges.add(Triple(matcher.start(), matcher.end(), it)) }
        }
        val distinct = entities.toCollection(Collections.newSetFromMap(IdentityHashMap()))
        val linked = distinct.filter { it is TLRPC.TL_messageEntityTextUrl && it.url in targets }
        if (ranges.isEmpty() && linked.isEmpty()) return null
        if (text.length + ranges.sumOf { (start, end, target) -> target.length - (end - start) } > limit) return null
        val result = StringBuilder(text)
        for ((start, end, target) in ranges.asReversed()) {
            result.replace(start, end, target)
            val delta = target.length - (end - start)
            for (entity in distinct) {
                val entityStart = entity.offset
                val entityEnd = entity.offset + entity.length
                entity.offset = if (entityStart >= end) entityStart + delta else minOf(entityStart, start)
                entity.length = (if (entityEnd >= end) entityEnd + delta else if (entityEnd > start) start + target.length else entityEnd) - entity.offset
            }
        }
        for (entity in linked) entity.url = targets.getValue(entity.url)
        return result.toString()
    }

    @JvmStatic
    fun drawPendingRedirects(editText: EditText, canvas: Canvas) {
        val text = editText.text ?: return
        val layout = editText.layout ?: return
        val markers = getPendingRedirects(text)
        if (markers.isEmpty()) return
        val color = editText.currentTextColor
        canvas.save()
        canvas.translate(editText.totalPaddingLeft.toFloat(), editText.totalPaddingTop.toFloat())
        for (marker in markers) {
            val start = text.getSpanStart(marker)
            marker.path.rewind()
            marker.path.setCurrentLayout(layout, start, 0f)
            layout.getSelectionPath(start, text.getSpanEnd(marker), marker.path)
            marker.path.closeRects()
            marker.loading.setColors(Theme.multAlpha(color, .1f), Theme.multAlpha(color, .25f))
            marker.loading.updateBounds()
            marker.loading.draw(canvas)
        }
        canvas.restore()
        editText.invalidate()
    }

    private class PendingRedirectSpan(val url: String, val shown: String) {
        var resolved = false
        val onResolved = mutableListOf<() -> Unit>()
        val path = LinkPath(true).apply { setUseCornerPathImplementation(true) }
        val loading = LoadingDrawable().apply {
            usePath(path)
            setRadiiDp(5f)
            setAppearByGradient(true)
        }
    }

    /** Returns true if opening [uri] was taken over to resolve a trackable redirect first. */
    @JvmStatic
    fun openResolvingRedirects(context: Context, uri: Uri, progress: Browser.Progress?, open: Utilities.Callback<Uri>): Boolean {
        if (!InuConfig.STRIP_TRACKING_PARAMS_ON_OPEN.value || !InuConfig.RESOLVE_TRACKABLE_REDIRECTS.value) return false
        val url = uri.toString()
        if (!isTrackableRedirect(url)) return false
        resolvedRedirects.get(url)?.let {
            open.run(it.toUri())
            return true
        }
        val finish = showLoader(context, progress, 300) {}
        resolveInBackground(listOf(url)) { resolved ->
            finish { open.run(resolved[url]?.toUri() ?: uri) }
        }
        return true
    }

    /** Returns a function that runs its block unless the loader was already cancelled or finished. */
    private fun showLoader(context: Context, progress: Browser.Progress?, delay: Long, onCancel: () -> Unit): (() -> Unit) -> Unit {
        val dialog = if (progress == null && AndroidUtilities.findActivity(context) != null) {
            AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER)
        } else null
        var done = false
        val finish = { block: () -> Unit ->
            if (!done) {
                done = true
                dialog?.dismiss()
                progress?.end()
                block()
            }
        }
        dialog?.setOnCancelListener { finish(onCancel) }
        if (delay > 0) dialog?.showDelayed(delay) else dialog?.show()
        progress?.onCancel { done = true }
        progress?.init()
        return finish
    }

    /** ui thread */
    private fun resolveInBackground(urls: Collection<String>, onResolved: (Map<String, String>) -> Unit) {
        val resolved = HashMap<String, String>()
        var left = urls.size
        if (left == 0) return onResolved(resolved)
        for (url in urls) {
            val done = { target: String? ->
                target?.let { resolved[url] = it }
                if (--left == 0) onResolved(resolved)
            }
            val waiting = resolving[url]
            if (waiting != null) {
                waiting.add(done)
                continue
            }
            resolving[url] = mutableListOf(done)
            Utilities.externalNetworkQueue.postRunnable {
                val target = try {
                    resolveTrackableRedirect(url)
                } catch (e: Exception) {
                    Log.e("UrlCleanerHelper", "failed to resolve $url", e)
                    null
                }
                target?.let { resolvedRedirects.put(url, it) }
                AndroidUtilities.runOnUIThread { resolving.remove(url)?.forEach { it(target) } }
            }
        }
    }
}

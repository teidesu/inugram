package desu.inugram.helpers.plugins

import desu.inugram.InuConfig
import desu.inugram.core.plugins.PluginManifest
import desu.inugram.core.plugins.PluginManifestParser
import desu.inugram.core.plugins.PluginOrigin
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ChatObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.RequestDelegateTimestamp
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_iv

class PluginRepo(val channelId: Long, val username: String, val title: String)

/** [document] carries the file reference of the request that found it */
class PluginRepoPost(val repo: PluginRepo, val messageId: Int, val document: TLRPC.Document) {
    val origin: PluginOrigin get() = PluginOrigin(repo.channelId, messageId)
}

/**
 * A repo is a channel, a release is a `.inu.js` post in it. Manifests come from the first few KB of each
 * file and are cached by document id, which never changes content. Ui thread only, callbacks included
 */
object PluginRepos {
    class Page(val posts: List<PluginRepoPost>, val nextOffsetId: Int)

    class Update(val plugin: Plugin, val post: PluginRepoPost, val manifest: PluginManifest)

    class UpdateCheck(val updates: List<Update>, val failedChannelIds: Set<Long>)

    private const val PAGE_SIZE = 50
    private const val HEADER_CHUNK = 4096
    private const val HEADER_MAX = 64 * 1024
    private const val SOURCE_CHUNK = 512 * 1024
    private const val HEADER_END = "==/InuPlugin=="
    private val USERNAME = Regex("""^(?:(?:https?://)?(?:t|telegram)\.me/|tg://resolve\?domain=|@)?([A-Za-z0-9_]{4,32})/?$""")

    private val headerDir by lazy { File(ApplicationLoader.applicationContext.cacheDir, "plugin_repo_headers") }

    /** null value: the document is not a plugin */
    private val manifests = HashMap<Long, PluginManifest?>()
    private val pendingManifests = HashMap<Long, MutableList<(Boolean, PluginManifest?) -> Unit>>()

    fun getRepos(): List<PluginRepo> {
        val raw = InuConfig.PLUGIN_REPOS.value
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrElse {
            PluginLog.HOST.e("repos", "bad repos state", it)
            return emptyList()
        }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            PluginRepo(o.optLong("channel"), o.optString("username"), o.optString("title"))
                .takeIf { it.channelId != 0L && it.username.isNotEmpty() }
        }
    }

    private fun saveRepos(repos: List<PluginRepo>) {
        val arr = JSONArray()
        for (repo in repos) {
            arr.put(JSONObject().put("channel", repo.channelId).put("username", repo.username).put("title", repo.title))
        }
        InuConfig.PLUGIN_REPOS.value = arr.toString()
    }

    fun parseUsername(input: String): String? = USERNAME.matchEntire(input.trim())?.groupValues?.get(1)

    /** [callback] gets an error message, or null once the repo is saved */
    fun addRepo(account: Int, input: String, callback: (String?) -> Unit) {
        val username = parseUsername(input) ?: return callback(getString(R.string.InuPluginReposErrorLink))
        val mc = MessagesController.getInstance(account)
        mc.userNameResolver.resolve(username) { peerId ->
            val chat = if (peerId != null && peerId < 0) mc.getChat(-peerId) else null
            if (chat == null || !ChatObject.isChannel(chat)) return@resolve callback(getString(R.string.InuPluginReposErrorNotChannel))
            val repos = getRepos()
            if (repos.any { it.channelId == chat.id }) return@resolve callback(getString(R.string.InuPluginReposErrorExists))
            saveRepos(repos + PluginRepo(chat.id, ChatObject.getPublicUsername(chat) ?: username, chat.title.orEmpty()))
            callback(null)
        }
    }

    fun removeRepo(repo: PluginRepo) {
        saveRepos(getRepos().filter { it.channelId != repo.channelId })
    }

    /** access hashes are per account, so a repo added on another one is resolved again; the id guards against a resold username */
    fun loadChat(account: Int, repo: PluginRepo, callback: (TLRPC.Chat?) -> Unit) {
        val mc = MessagesController.getInstance(account)
        mc.getChat(repo.channelId)?.takeIf { !it.min }?.let { return callback(it) }
        mc.userNameResolver.resolve(repo.username) { peerId ->
            callback(if (peerId == -repo.channelId) mc.getChat(repo.channelId) else null)
        }
    }

    private fun resolvePeer(account: Int, repo: PluginRepo, callback: (TLRPC.InputPeer?) -> Unit) {
        loadChat(account, repo) { chat ->
            callback(chat?.let { MessagesController.getInstance(account).getInputPeer(-it.id) })
        }
    }

    /** newest first. [Page.nextOffsetId] is 0 once the channel has no older messages */
    fun loadPage(account: Int, repo: PluginRepo, offsetId: Int, minId: Int = 0, callback: (Page?) -> Unit) {
        resolvePeer(account, repo) { peer ->
            if (peer == null) return@resolvePeer callback(null)
            // The document search filter omits files embedded in rich messages.
            val req = TLRPC.TL_messages_getHistory().apply {
                this.peer = peer
                offset_id = offsetId
                min_id = minId
                limit = PAGE_SIZE
            }
            ConnectionsManager.getInstance(account).sendRequest(req) { response, _ ->
                AndroidUtilities.runOnUIThread {
                    val res = response as? TLRPC.messages_Messages ?: return@runOnUIThread callback(null)
                    val mc = MessagesController.getInstance(account)
                    mc.putUsers(res.users, false)
                    mc.putChats(res.chats, false)
                    val more = res.messages.size >= PAGE_SIZE
                    val nextOffset = if (more) res.messages.last().id else 0
                    loadPosts(account, repo, peer, res.messages) { posts ->
                        callback(posts?.let { Page(it, nextOffset) })
                    }
                }
            }
        }
    }

    private fun pluginDocuments(message: TLRPC.Message): List<TLRPC.Document> =
        (listOfNotNull(message.media?.document) + message.rich_message?.documents.orEmpty())
            .filter { FileLoader.getDocumentFileName(it)?.endsWith(".inu.js", ignoreCase = true) == true }
            .distinctBy { it.id }

    /** A partial rich message may omit the installer even when it contains other documents. */
    private fun loadFullMessage(account: Int, peer: TLRPC.InputPeer, message: TLRPC.Message, callback: (TLRPC.Message?) -> Unit) {
        if (message.rich_message?.part != true) return callback(message)
        val req = TL_iv.getRichMessage().apply {
            this.peer = peer
            id = message.id
        }
        ConnectionsManager.getInstance(account).sendRequest(req) { response, _ ->
            AndroidUtilities.runOnUIThread {
                callback((response as? TLRPC.messages_Messages)?.messages?.firstOrNull {
                    it.id == message.id && it.rich_message?.part == false
                })
            }
        }
    }

    private fun loadPosts(
        account: Int,
        repo: PluginRepo,
        peer: TLRPC.InputPeer,
        messages: List<TLRPC.Message>,
        callback: (List<PluginRepoPost>?) -> Unit,
    ) {
        val posts = ArrayList<PluginRepoPost>()
        // Resolve partial messages one at a time, keeping channel order and bounding requests in flight.
        fun next(index: Int) {
            if (index == messages.size) return callback(posts)
            loadFullMessage(account, peer, messages[index]) { full ->
                if (full == null) return@loadFullMessage callback(null)
                posts.addAll(pluginDocuments(full).map { PluginRepoPost(repo, full.id, it) })
                next(index + 1)
            }
        }
        next(0)
    }

    /** in [posts] order, without the posts that are not plugins; null when any header could not be read */
    fun loadManifests(account: Int, posts: List<PluginRepoPost>, callback: (List<Pair<PluginRepoPost, PluginManifest>>?) -> Unit) {
        if (posts.isEmpty()) return callback(emptyList())
        val results = arrayOfNulls<PluginManifest>(posts.size)
        var remaining = posts.size
        var failed = false
        posts.forEachIndexed { i, post ->
            loadManifest(account, post) { ok, manifest ->
                results[i] = manifest
                failed = failed || !ok
                if (--remaining == 0) {
                    callback(if (failed) null else posts.indices.mapNotNull { j -> results[j]?.let { posts[j] to it } })
                }
            }
        }
    }

    private fun loadManifest(account: Int, post: PluginRepoPost, callback: (Boolean, PluginManifest?) -> Unit) {
        val id = post.document.id
        if (manifests.containsKey(id)) return callback(true, manifests[id])
        pendingManifests[id]?.let {
            it.add(callback)
            return
        }
        pendingManifests[id] = mutableListOf(callback)
        val file = File(headerDir, id.toString())
        Utilities.globalQueue.postRunnable {
            val cached = runCatching { file.readText() }.getOrNull()
            if (cached != null) return@postRunnable deliverManifest(id, cached)
            fetch(account, post, post.document, HEADER_CHUNK, ByteArrayOutputStream(), { it.size() >= HEADER_MAX || it.toString(Charsets.UTF_8.name()).contains(HEADER_END) }) { bytes ->
                if (bytes == null) return@fetch deliverManifest(id, null)
                val header = bytes.toString(Charsets.UTF_8)
                runCatching {
                    headerDir.mkdirs()
                    file.writeText(header)
                }.onFailure { PluginLog.HOST.w("repos", "header cache write failed: ${it.message}") }
                deliverManifest(id, header)
            }
        }
    }

    /** null [header]: the fetch failed, so nothing is remembered */
    private fun deliverManifest(documentId: Long, header: String?) {
        val manifest = header?.let { PluginManifestParser.parseOrNull(it) }
        AndroidUtilities.runOnUIThread {
            if (header != null) manifests[documentId] = manifest
            pendingManifests.remove(documentId)?.forEach { it(header != null, manifest) }
        }
    }

    fun downloadSource(account: Int, post: PluginRepoPost, callback: (String?) -> Unit) {
        Utilities.globalQueue.postRunnable {
            fetch(account, post, post.document, SOURCE_CHUNK, ByteArrayOutputStream(), { false }) { bytes ->
                AndroidUtilities.runOnUIThread { callback(bytes?.toString(Charsets.UTF_8)) }
            }
        }
    }

    /** continues and calls [onDone] on the global queue */
    private fun fetch(
        account: Int,
        post: PluginRepoPost,
        document: TLRPC.Document,
        chunk: Int,
        out: ByteArrayOutputStream,
        isDone: (ByteArrayOutputStream) -> Boolean,
        refreshed: Boolean = false,
        onDone: (ByteArray?) -> Unit,
    ) {
        val req = TLRPC.TL_upload_getFile().apply {
            location = TLRPC.TL_inputDocumentFileLocation().apply {
                id = document.id
                access_hash = document.access_hash
                file_reference = document.file_reference ?: ByteArray(0)
                thumb_size = ""
            }
            offset = out.size().toLong()
            limit = chunk
        }
        ConnectionsManager.getInstance(account).sendRequest(req, RequestDelegateTimestamp { response, error, _ ->
            // the response buffer goes back to a pool once this returns
            val bytes = (response as? TLRPC.TL_upload_file)?.bytes?.let { buffer ->
                buffer.position(0)
                ByteArray(buffer.limit()).also { buffer.readBytes(it, true) }
            }
            Utilities.globalQueue.postRunnable {
                when {
                    bytes != null -> {
                        out.write(bytes)
                        if (bytes.size < chunk || out.size() >= document.size || isDone(out)) onDone(out.toByteArray())
                        else fetch(account, post, document, chunk, out, isDone, refreshed, onDone)
                    }
                    !refreshed && error?.text?.startsWith("FILE_REFERENCE_") == true -> refreshDocument(account, post) { fresh ->
                        // an edit swapped the file, and the bytes so far belong to the old one
                        if (fresh == null || fresh.id != document.id) onDone(null)
                        else fetch(account, post, fresh, chunk, out, isDone, true, onDone)
                    }
                    else -> {
                        PluginLog.HOST.w("repos", "getFile failed: ${error?.text}")
                        onDone(null)
                    }
                }
            }
        }, 0, ConnectionsManager.ConnectionTypeDownload, document.dc_id)
    }

    private fun refreshDocument(account: Int, post: PluginRepoPost, callback: (TLRPC.Document?) -> Unit) {
        val req = TLRPC.TL_channels_getMessages().apply {
            channel = MessagesController.getInstance(account).getInputChannel(post.repo.channelId)
            id.add(post.messageId)
        }
        ConnectionsManager.getInstance(account).sendRequest(req) { response, _ ->
            AndroidUtilities.runOnUIThread {
                val message = (response as? TLRPC.messages_Messages)?.messages?.firstOrNull { it.id == post.messageId }
                if (message == null) {
                    Utilities.globalQueue.postRunnable { callback(null) }
                } else {
                    val peer = MessagesController.getInstance(account).getInputPeer(-post.repo.channelId)
                    loadFullMessage(account, peer, message) { full ->
                        val document = full?.let(::pluginDocuments)?.firstOrNull { it.id == post.document.id }
                        Utilities.globalQueue.postRunnable { callback(document) }
                    }
                }
            }
        }
    }

    /** the newest runnable post of each plugin; [posts] are newest first */
    fun pickLatest(posts: List<Pair<PluginRepoPost, PluginManifest>>): List<Pair<PluginRepoPost, PluginManifest>> =
        posts.filter { PluginManager.incompatibility(it.second) == null }.distinctBy { it.second.id ?: it.first.messageId }

    /** only the repo [plugin] was installed from may update it */
    fun isUpdate(plugin: Plugin, post: PluginRepoPost, manifest: PluginManifest): Boolean {
        val origin = plugin.origin ?: return false
        return origin.channelId == post.repo.channelId &&
            post.messageId > origin.messageId &&
            manifest.version != plugin.manifest.version
    }

    /** reads each repo only down to the oldest post something was installed from */
    fun findUpdates(account: Int, callback: (UpdateCheck) -> Unit) {
        val tracked = PluginManager.plugins().filter { it.origin != null && it.manifest.id != null }
        val repos = getRepos().filter { repo -> tracked.any { it.origin?.channelId == repo.channelId } }
        if (repos.isEmpty()) return callback(UpdateCheck(emptyList(), emptySet()))
        val found = ArrayList<Update>()
        val failed = HashSet<Long>()
        var remaining = repos.size
        for (repo in repos) {
            val installed = tracked.filter { it.origin?.channelId == repo.channelId }
            val minId = installed.minOf { it.origin?.messageId ?: 0 }
            loadPostsSince(account, repo, minId, 0, ArrayList()) { posts ->
                loadManifests(account, posts.orEmpty()) { loaded ->
                    if (posts == null || loaded == null) failed.add(repo.channelId)
                    for ((post, manifest) in pickLatest(loaded.orEmpty())) {
                        val plugin = installed.firstOrNull { it.manifest.id == manifest.id } ?: continue
                        if (isUpdate(plugin, post, manifest)) found.add(Update(plugin, post, manifest))
                    }
                    if (--remaining == 0) callback(UpdateCheck(found, failed))
                }
            }
        }
    }

    private fun loadPostsSince(
        account: Int,
        repo: PluginRepo,
        minId: Int,
        offsetId: Int,
        into: ArrayList<PluginRepoPost>,
        callback: (List<PluginRepoPost>?) -> Unit,
    ) {
        loadPage(account, repo, offsetId, minId) { page ->
            if (page == null) return@loadPage callback(null)
            into.addAll(page.posts)
            if (page.nextOffsetId == 0) callback(into)
            else loadPostsSince(account, repo, minId, page.nextOffsetId, into, callback)
        }
    }
}

package desu.inugram.helpers.plugins.ui

import desu.inugram.core.plugins.OwnerRegistry
import desu.inugram.helpers.plugins.SessionResource
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.PluginSession
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.io.PluginBlobs
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.Utilities
import org.telegram.ui.LaunchActivity

/**
 * `inu.ui.pickFile` and `inu.ui.saveFile` (rust: `api/ui/files.rs`): the device's own document
 * picker, which is the only thing that hands a plugin a file it did not make. The gesture is the
 * permission - one file, chosen once - so nothing here asks for a grant and nothing reports where
 * the file came from.
 *
 * The content is copied rather than read through the uri it arrived as: that uri's permission lasts
 * as long as the picker's own result, while the `File` the plugin holds outlives it.
 */
internal object PluginFilePicker : SessionResource {
    private const val TAG = "InuPluginFiles"

    /** what the app's cache can reasonably take a copy of, and what a plugin may be handed at once */
    private const val MAX_PICK_BYTES = 256L * 1024 * 1024

    private const val COPY_CHUNK_BYTES = 256 * 1024

    /** stock passes the code straight through [NotificationCenter.onActivityResultReceived], and it must fit 16 bits */
    private const val REQUEST_BASE = 0x7100
    private const val REQUEST_SPAN = 0x80

    private var nextRequest = 0

    /** the result observers a plugin is still waiting on */
    private val waiting = OwnerRegistry<PluginSession, NotificationCenter.NotificationCenterDelegate>()

    /** a copy made for a plugin nobody is waiting for any more is deleted rather than left in its spill directory */
    private class Picked(val wire: String, val copies: List<File> = emptyList())

    override fun detach(session: PluginSession) {
        val observers = waiting.take(session)
        if (observers.isEmpty()) return
        AndroidUtilities.runOnUIThread {
            val center = NotificationCenter.getGlobalInstance()
            observers.forEach { center.removeObserver(it, NotificationCenter.onActivityResultReceived) }
        }
    }

    fun pick(session: PluginSession, requestId: Long, optionsJson: String): String? {
        val options = try {
            JSONObject(optionsJson)
        } catch (e: Exception) {
            return PluginWire.encodePluginError("invalid-argument", "pickFile: ${e.message}")
        }
        val multiple = options.optBoolean("multiple")
        val types = options.optJSONArray("accept")?.let { array ->
            List(array.length()) { array.optString(it) }.filter { it.isNotEmpty() }
        }.orEmpty()
        return launch(session, requestId, "pickFile", {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = types.singleOrNull() ?: "*/*"
                if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }) { data -> copyIn(session, urisOf(data), multiple) }
    }

    fun save(session: PluginSession, requestId: Long, optionsJson: String): String? {
        val options = try {
            JSONObject(optionsJson)
        } catch (e: Exception) {
            return PluginWire.encodePluginError("invalid-argument", "saveFile: ${e.message}")
        }
        val source = File(options.optString("path"))
        val name = options.optString("fileName").ifEmpty { "file" }
        val mime = options.optString("type").ifEmpty { "application/octet-stream" }
        if (!source.isFile) return PluginWire.encodePluginError("not-found", "saveFile: there is nothing to save")
        return launch(session, requestId, "saveFile", {
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                putExtra(Intent.EXTRA_TITLE, name)
            }
        }) { data ->
            val target = data?.data ?: return@launch Picked(PluginWire.encodeBool(false))
            Picked(copyOut(source, target))
        }
    }

    /**
     * The one shape both take: hand the device an intent from the ui thread, wait for the result on
     * [NotificationCenter.onActivityResultReceived] - which is how stock reports one to anything
     * that is not a fragment - and turn it into the plugin's answer off that thread, copying a file
     * being disk work. A plugin torn down in between answers nothing, its promises being gone.
     */
    private fun launch(
        session: PluginSession,
        requestId: Long,
        name: String,
        intent: () -> Intent,
        answer: (Intent?) -> Picked,
    ): String? {
        AndroidUtilities.runOnUIThread {
            val activity = LaunchActivity.instance
            if (activity == null || activity.isFinishing) {
                settle(session, requestId, name, Picked(PluginWire.encodePluginError("unsupported", "$name: there is no screen to open a picker over")))
                return@runOnUIThread
            }
            nextRequest = (nextRequest + 1) % REQUEST_SPAN
            val code = REQUEST_BASE + nextRequest
            val center = NotificationCenter.getGlobalInstance()
            val observer = object : NotificationCenter.NotificationCenterDelegate {
                override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                    if (args.getOrNull(0) != code) return
                    center.removeObserver(this, NotificationCenter.onActivityResultReceived)
                    waiting.remove(session) { it === this }
                    val ok = args.getOrNull(1) == Activity.RESULT_OK
                    val data = args.getOrNull(2) as? Intent
                    Utilities.globalQueue.postRunnable {
                        val result = if (!ok) {
                            Picked(if (name == "saveFile") PluginWire.encodeBool(false) else PluginWire.encodeJson("[]"))
                        } else try {
                            answer(data)
                        } catch (e: Throwable) {
                            Log.e(TAG, "$name failed", e)
                            Picked(PluginWire.encodePluginError("internal", "$name: ${e.message ?: e.toString()}"))
                        }
                        settle(session, requestId, name, result)
                    }
                }
            }
            center.addObserver(observer, NotificationCenter.onActivityResultReceived)
            waiting.add(session, observer)
            try {
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent(), code)
            } catch (e: Throwable) {
                center.removeObserver(observer, NotificationCenter.onActivityResultReceived)
                waiting.remove(session) { it === observer }
                Log.e(TAG, "$name could not be opened", e)
                settle(session, requestId, name, Picked(PluginWire.encodePluginError("unsupported", "$name: this device has no file picker")))
            }
        }
        return null
    }

    private fun settle(session: PluginSession, requestId: Long, name: String, picked: Picked) {
        EngineDispatch.onEngine(session, onDropped = { picked.copies.forEach(File::delete) }) {
            session.engine.settle(QuickJs.SETTLE_FILES, requestId, picked.wire)
        }
    }

    private fun urisOf(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        val clip = data.clipData
        if (clip != null) return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        return listOfNotNull(data.data)
    }

    /**
     * The copies the plugin is handed, or nothing at all: a pick that fails halfway leaves no file
     * behind, and neither does a picker that answered with more files than were asked for.
     */
    private fun copyIn(session: PluginSession, uris: List<Uri>, multiple: Boolean): Picked {
        val wanted = if (multiple) uris else uris.take(1)
        if (wanted.isEmpty()) return Picked(PluginWire.encodeJson("[]"))
        val root = PluginBlobs.dirFor(session.plugin.id)
        if (root.isEmpty()) {
            return Picked(PluginWire.encodePluginError("internal", "pickFile: there is nowhere to copy the file to"))
        }
        val dir = File(root, "picked")
        if (!dir.isDirectory && !dir.mkdirs()) {
            return Picked(PluginWire.encodePluginError("internal", "pickFile: there is nowhere to copy the file to"))
        }
        val copies = ArrayList<File>()
        val out = JSONArray()
        for (uri in wanted) {
            val target = File(dir, "picked-${System.nanoTime()}-${out.length()}")
            copies.add(target)
            val described = try {
                describe(uri)
            } catch (e: Throwable) {
                Log.e(TAG, "could not read what $uri is", e)
                null
            }
            val failure = if (described == null) {
                PluginWire.encodePluginError("internal", "pickFile: this file could not be read")
            } else {
                copy(uri, target, described)
            }
            if (failure != null) {
                for (copy in copies) copy.delete()
                return Picked(failure)
            }
            out.put(
                JSONObject()
                    .put("path", target.absolutePath)
                    .put("name", described!!.name)
                    .put("type", described.mime),
            )
        }
        return Picked(PluginWire.encodeJson(out.toString()), copies)
    }

    /**
     * The cap is counted as the bytes arrive rather than taken off the provider, which is allowed to
     * report no size at all - and does, for plenty of cloud providers. Answers the refusal, or null.
     */
    private fun copy(uri: Uri, target: File, described: Described): String? {
        val resolver = ApplicationLoader.applicationContext.contentResolver
        if (described.size > MAX_PICK_BYTES) return tooBig(described.name, described.size)
        var written = 0L
        try {
            val input = resolver.openInputStream(uri) ?: return PluginWire.encodePluginError(
                "internal",
                "pickFile: this file could not be read",
            )
            input.use { source ->
                target.outputStream().use { sink ->
                    val buffer = ByteArray(COPY_CHUNK_BYTES)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > MAX_PICK_BYTES) return tooBig(described.name, written)
                        sink.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "could not copy $uri", e)
            return PluginWire.encodePluginError("internal", "pickFile: this file could not be read")
        }
        return null
    }

    private fun tooBig(name: String, size: Long): String = PluginWire.encodePluginError(
        "quota-exceeded",
        "pickFile: '$name' is over $MAX_PICK_BYTES bytes ($size so far), which is more than may be picked at once",
    )

    private fun copyOut(source: File, target: Uri): String {
        val resolver = ApplicationLoader.applicationContext.contentResolver
        val stream = resolver.openOutputStream(target)
            ?: return PluginWire.encodePluginError("internal", "saveFile: the file could not be written")
        stream.use { out -> source.inputStream().use { it.copyTo(out) } }
        return PluginWire.encodeBool(true)
    }

    private class Described(val name: String, val mime: String, val size: Long)

    /** what the provider says about the file, the uri itself saying nothing a name can be read off */
    private fun describe(uri: Uri): Described {
        val resolver = ApplicationLoader.applicationContext.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameAt = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeAt = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameAt >= 0 && !cursor.isNull(nameAt)) cursor.getString(nameAt) else null
                val size = if (sizeAt >= 0 && !cursor.isNull(sizeAt)) cursor.getLong(sizeAt) else 0L
                return Described(name ?: uri.lastPathSegment ?: "file", mime, size)
            }
        }
        return Described(uri.lastPathSegment ?: "file", mime, 0L)
    }
}

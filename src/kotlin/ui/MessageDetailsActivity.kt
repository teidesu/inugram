package desu.inugram.ui

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.WebAppHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ChatObject
import org.telegram.messenger.ContactsController
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextDetailSettingsCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalFragment
import org.telegram.ui.Components.UniversalRecyclerView
import org.telegram.ui.ProfileActivity
import java.io.File
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

class MessageDetailsActivity(
    private val messageObject: MessageObject,
    private val messageGroup: MessageObject.GroupedMessages?
) : UniversalFragment(), NotificationCenter.NotificationCenterDelegate {

    @Volatile
    private var fragmentDestroyed = false

    private val fromChat: TLRPC.Chat?
    private val fromUser: TLRPC.User?
    private var fileName: String? = null
    private var filePath: String? = null
    private var width = 0
    private var height = 0
    private var videoCodec: String? = null
    private var frameRate = 0f
    private var bitRate = 0L
    private var isBitRateEstimated = false
    private var audioBitRate = 0L
    private var isAudioBitRateEstimated = false
    private var hasMultipleTracks = false
    private var sampleRate = 0
    private var dc = 0

    init {
        val peerId = messageObject.messageOwner.peer_id
        fromChat = when {
            peerId?.channel_id != 0L -> messagesController.getChat(peerId.channel_id)
            peerId.chat_id != 0L -> messagesController.getChat(peerId.chat_id)
            else -> null
        }
        fromUser = messageObject.messageOwner.from_id?.user_id?.takeIf { it != 0L }
            ?.let { messagesController.getUser(it) }

        val media = MessageObject.getMedia(messageObject.messageOwner)
        if (media != null) {
            val file = FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner)
            if (file != null && file.exists()) {
                filePath = file.absolutePath
            }

            val photo = media.webpage?.photo ?: media.photo
            if (photo != null) {
                dc = photo.dc_id
                val photoSize = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, Int.MAX_VALUE)
                if (photoSize != null) {
                    width = photoSize.w
                    height = photoSize.h
                }
            }

            val document = media.webpage?.document ?: media.document
            if (document != null) {
                dc = document.dc_id
                for (attr in document.attributes) {
                    if (attr is TLRPC.TL_documentAttributeFilename) {
                        fileName = attr.file_name
                    }
                    if (attr is TLRPC.TL_documentAttributeImageSize || attr is TLRPC.TL_documentAttributeVideo) {
                        width = attr.w
                        height = attr.h
                        videoCodec = attr.video_codec
                    }
                }
                if (!filePath.isNullOrEmpty()) {
                    val path = filePath!!
                    Utilities.globalQueue.postRunnable {
                        if (fragmentDestroyed) return@postRunnable
                        extractMediaMetadata(path)
                        AndroidUtilities.runOnUIThread({
                            if (fragmentDestroyed || isFinishing || parentActivity == null) return@runOnUIThread
                            listView?.adapter?.update(true)
                        }, 300)
                    }
                }
            }
        }
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.emojiLoaded)
        return true
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        fragmentDestroyed = true
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.emojiLoaded)
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.emojiLoaded) {
            listView?.invalidateViews()
        }
    }

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuMessageDetails)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            detailItem(
                ROW_ID, "ID", if (messageObject.currentEvent != null) {
                    messageObject.currentEvent.id.toString()
                } else {
                    messageObject.messageOwner.id.toString()
                }
            )
        )
        if (messageObject.scheduled) {
            items.add(detailItem(ROW_SCHEDULED, R.string.InuMsgDetailScheduled, "Yes"))
        }
        if (!messageObject.messageText.isNullOrEmpty()) {
            items.add(detailItem(ROW_MESSAGE, R.string.InuMsgDetailMessage, messageObject.messageText.toString()))
        }
        val caption = captionText()
        if (caption != null) {
            items.add(detailItem(ROW_CAPTION, R.string.InuMsgDetailCaption, caption))
        }
        if (fromChat != null && !fromChat.broadcast) {
            items.add(
                detailItem(
                    ROW_GROUP,
                    R.string.InuMsgDetailGroup,
                    formatEntityInfo(fromChat.title, fromChat.username, fromChat.id)
                )
            )
        }
        if (fromChat != null && fromChat.broadcast) {
            items.add(
                detailItem(
                    ROW_CHANNEL,
                    R.string.InuMsgDetailChannel,
                    formatEntityInfo(fromChat.title, fromChat.username, fromChat.id)
                )
            )
        }
        if (fromUser != null) {
            items.add(
                detailItem(
                    ROW_FROM, R.string.InuMsgDetailFrom, formatEntityInfo(
                        ContactsController.formatName(fromUser.first_name, fromUser.last_name),
                        fromUser.username, fromUser.id
                    )
                )
            )
        } else if (messageObject.messageOwner.post_author != null) {
            items.add(detailItem(ROW_FROM, R.string.InuMsgDetailFrom, messageObject.messageOwner.post_author))
        }
        if (fromUser != null && fromUser.bot) {
            items.add(detailItem(ROW_BOT, "Bot", "Yes"))
        }
        if (messageObject.messageOwner.date != 0) {
            val label = if (messageObject.scheduled) R.string.InuMsgDetailScheduledDate else R.string.InuMsgDetailDate
            val value = if (messageObject.messageOwner.date == 0x7ffffffe) {
                LocaleController.getString(R.string.InuMsgDetailWhenOnline)
            } else {
                formatDate(messageObject.messageOwner.date)
            }
            items.add(detailItem(ROW_DATE, label, value))
        }
        if (messageObject.messageOwner.edit_date != 0) {
            items.add(
                detailItem(
                    ROW_EDITED,
                    R.string.InuMsgDetailEdited,
                    formatDate(messageObject.messageOwner.edit_date)
                )
            )
        }
        if (messageObject.isForwarded) {
            items.add(detailItem(ROW_FORWARD, R.string.InuMsgDetailForwardFrom, buildForwardInfo()))
        }
        if (!messageObject.messageOwner.restriction_reason.isNullOrEmpty()) {
            val value =
                messageObject.messageOwner.restriction_reason.joinToString(", ") { "${it.reason}-${it.platform}" }
            items.add(detailItem(ROW_RESTRICTION, R.string.InuMsgDetailRestrictionReason, value))
        }
        if (!fileName.isNullOrEmpty()) {
            items.add(detailItem(ROW_FILE_NAME, R.string.InuMsgDetailFileName, fileName!!))
        }
        if (!filePath.isNullOrEmpty()) {
            items.add(detailItem(ROW_FILE_PATH, R.string.InuMsgDetailFilePath, filePath!!))
        }
        if (messageObject.size != 0L) {
            items.add(
                detailItem(
                    ROW_FILE_SIZE,
                    R.string.InuMsgDetailFileSize,
                    AndroidUtilities.formatFileSize(messageObject.size)
                )
            )
        }
        if (!messageObject.mimeType.isNullOrEmpty()) {
            items.add(detailItem(ROW_MIME_TYPE, R.string.InuMsgDetailMimeType, messageObject.mimeType))
        }
        val mediaInfo = buildMediaInfo()
        if (mediaInfo.isNotEmpty()) {
            items.add(detailItem(ROW_MEDIA, R.string.InuMsgDetailMedia, mediaInfo))
        }
        if (dc != 0) {
            items.add(detailItem(ROW_DC, "DC", "DC$dc"))
        }
        items.add(UItem.asShadow(null))
        items.add(
            UItem.asButton(
                ROW_SHOW_JSON,
                R.drawable.inu_tabler_code,
                LocaleController.getString(R.string.InuShowJson)
            )
        )
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == ROW_SHOW_JSON) {
            WebAppHelper.openTlViewer(
                this,
                messageObject.currentEvent ?: messageObject.messageOwner
            )
            return;
        }

        if (view !is TextDetailSettingsCell) return
        val text = view.valueTextView.text
        if (text.isNullOrEmpty()) return
        try {
            AndroidUtilities.addToClipboard(text)
            BulletinFactory.of(this).createCopyBulletin(LocaleController.formatString(R.string.TextCopied)).show()
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        when (item.id) {
            ROW_ID -> {
                handleIdLongClick(); return true
            }

            ROW_FILE_PATH -> {
                handleFilePathLongClick(); return true
            }

            ROW_CHANNEL, ROW_GROUP -> {
                openChatProfile(); return true
            }

            ROW_FROM -> {
                openUserProfile(); return true
            }
        }
        return false
    }

    // region long-click actions

    private fun handleIdLongClick() {
        val chat = fromChat ?: return
        if (!ChatObject.isChannel(chat)) return
        val req = TLRPC.TL_channels_exportMessageLink().apply {
            id = messageObject.id
            channel = MessagesController.getInputChannel(chat)
            thread = false
        }
        connectionsManager.sendRequest(req) { response, error ->
            AndroidUtilities.runOnUIThread {
                if (response is TLRPC.TL_exportedMessageLink) {
                    try {
                        AndroidUtilities.addToClipboard(response.link)
                        BulletinFactory.of(this).createCopyLinkBulletin(response.link.contains("/c/")).show()
                    } catch (e: Exception) {
                        FileLog.e(e)
                    }
                } else if (error != null) {
                    BulletinFactory.of(this).createErrorBulletin(error.text).show()
                }
            }
        }
    }

    private fun handleFilePathLongClick() {
        val path = filePath ?: return
        val activity = parentActivity ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            try {
                putExtra(
                    Intent.EXTRA_STREAM,
                    FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", File(path))
                )
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            } catch (_: Exception) {
                putExtra(Intent.EXTRA_STREAM, Uri.fromFile(File(path)))
            }
        }
        startActivityForResult(Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)), 500)
    }

    private fun openChatProfile() {
        val chat = fromChat ?: return
        presentFragment(ProfileActivity(Bundle().apply { putLong("chat_id", chat.id) }))
    }

    private fun openUserProfile() {
        val user = fromUser ?: return
        presentFragment(ProfileActivity(Bundle().apply { putLong("user_id", user.id) }))
    }

    // endregion

    // region data formatting

    private fun captionText(): String? {
        if (!messageObject.caption.isNullOrEmpty()) return messageObject.caption.toString()
        val groupCaption = messageGroup?.findCaptionMessageObject()
        if (groupCaption != null && !groupCaption.caption.isNullOrEmpty()) return groupCaption.caption.toString()
        return null
    }

    private fun formatDate(timestamp: Int): String {
        val date = Date(timestamp.toLong() * 1000)
        val locale = LocaleController.getInstance()
        return LocaleController.formatString(
            R.string.formatDateAtTime,
            locale.formatterYear.format(date),
            locale.formatterDay.format(date)
        )
    }

    private fun formatEntityInfo(name: String?, username: String?, id: Long): String = buildString {
        if (name != null) appendLine(name)
        if (!username.isNullOrEmpty()) appendLine("@$username")
        append(id)
    }

    private fun buildForwardInfo(): String = buildString {
        val fwd = messageObject.messageOwner.fwd_from ?: return@buildString
        if (fwd.from_id == null) {
            append(fwd.from_name)
        } else {
            when {
                fwd.from_id.channel_id != 0L -> {
                    val chat = messagesController.getChat(fwd.from_id.channel_id)
                    if (chat != null) append(formatEntityInfo(chat.title, chat.username, chat.id))
                }

                fwd.from_id.user_id != 0L -> {
                    val user = messagesController.getUser(fwd.from_id.user_id)
                    if (user != null) append(
                        formatEntityInfo(
                            ContactsController.formatName(user.first_name, user.last_name),
                            user.username, user.id
                        )
                    )
                }

                !fwd.from_name.isNullOrEmpty() -> append(fwd.from_name)
            }
        }
        if (fwd.date != 0) {
            if (isNotEmpty()) append('\n')
            append(formatDate(fwd.date))
        }
    }

    private fun buildMediaInfo(): String = buildString {
        if (width > 0 && height > 0) {
            append(String.format(Locale.US, "%dx%d", width, height))
        }
        if (!videoCodec.isNullOrEmpty()) {
            if (isNotEmpty()) append(", ")
            append(videoCodec)
        }
        if (frameRate > 0) {
            if (isNotEmpty()) append(", ")
            append(String.format(Locale.US, "%.2f fps", frameRate))
        }
        if (bitRate > 0) {
            if (isNotEmpty()) append(", ")
            val trackPrefix = if (hasMultipleTracks) "V: " else ""
            val estimated = if (isBitRateEstimated) "~" else ""
            append(String.format(Locale.US, "%s%s%.0f Kbps", trackPrefix, estimated, bitRate / 1000.0))
        }
        if (audioBitRate > 0) {
            if (isNotEmpty()) append(", ")
            val trackPrefix = if (hasMultipleTracks) "A: " else ""
            val estimated = if (isAudioBitRateEstimated) "~" else ""
            append(String.format(Locale.US, "%s%s%.0f Kbps", trackPrefix, estimated, audioBitRate / 1000.0))
        }
        if (sampleRate > 0) {
            if (isNotEmpty()) append(", ")
            append(String.format(Locale.US, "%d Hz", sampleRate))
        }
    }

    // endregion

    // region media metadata extraction

    private fun extractMediaMetadata(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) return

        frameRate = 0f
        bitRate = 0
        audioBitRate = 0
        sampleRate = 0
        isBitRateEstimated = false
        isAudioBitRateEstimated = false
        hasMultipleTracks = false

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(filePath)
            var isVideo = false
            var isAudio = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("video/")) {
                    isVideo = true
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        frameRate = try {
                            format.getFloat(MediaFormat.KEY_FRAME_RATE)
                        } catch (_: ClassCastException) {
                            format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                        }
                    }
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        bitRate = format.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                    } else if (format.containsKey("max-bitrate")) {
                        bitRate = format.getInteger("max-bitrate").toLong()
                    }
                } else if (mime != null && mime.startsWith("audio/")) {
                    isAudio = true
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        audioBitRate = format.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                    } else if (format.containsKey("max-bitrate")) {
                        audioBitRate = format.getInteger("max-bitrate").toLong()
                    }
                }
            }
            hasMultipleTracks = isVideo && isAudio
            if (isVideo) {
                val extractorFrameRate = frameRate
                frameRate = 0f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        MediaMetadataRetriever().use { retriever ->
                            retriever.setDataSource(filePath)
                            val frameCountStr =
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            if (frameCountStr != null && durationStr != null) {
                                val frameCount = frameCountStr.toLong()
                                val durationMs = durationStr.toLong()
                                if (frameCount > 0 && durationMs > 0) {
                                    frameRate = (frameCount / (durationMs / 1000.0)).toFloat()
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                if (frameRate == 0f) {
                    frameRate = extractorFrameRate
                }
            }
        } catch (e: Exception) {
            FileLog.e(e)
        } finally {
            extractor.release()
        }

        if (bitRate == 0L || audioBitRate == 0L || sampleRate == 0) {
            try {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(filePath)
                    if (bitRate == 0L || audioBitRate == 0L) {
                        val bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        if (bitrateStr != null) {
                            val hasVideo =
                                "yes" == retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                            val hasAudioTrack =
                                "yes" == retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                            if (hasVideo && hasAudioTrack) hasMultipleTracks = true
                            val totalBitrate = Utilities.parseLong(bitrateStr).let {
                                if (it !in 1..1_000_000L) 0L else it
                            }
                            if (totalBitrate > 0) {
                                when {
                                    hasVideo && !hasAudioTrack && bitRate == 0L -> bitRate = totalBitrate
                                    !hasVideo && hasAudioTrack && audioBitRate == 0L -> audioBitRate = totalBitrate
                                    hasVideo && hasAudioTrack -> {
                                        if (bitRate == 0L && audioBitRate > 0) {
                                            bitRate = (totalBitrate - audioBitRate).coerceAtLeast(0)
                                            if (bitRate == 0L) bitRate = totalBitrate
                                        } else if (audioBitRate == 0L && bitRate > 0) {
                                            audioBitRate = (totalBitrate - bitRate).coerceAtLeast(0)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (sampleRate == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val sampleRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                        if (sampleRateStr != null) {
                            sampleRate = sampleRateStr.toInt()
                        }
                    }
                }
            } catch (e: Exception) {
                FileLog.e(e)
            }
        }

        if (bitRate == 0L || (audioBitRate == 0L && sampleRate > 0)) {
            try {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(filePath)
                    val durationStr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val isVideoFile = "yes" == r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                    var durationMs = Utilities.parseLong(durationStr)
                    if (durationMs == Long.MAX_VALUE) durationMs = 0
                    if (durationMs <= 0) {
                        val messageDurationSeconds = messageObject.duration
                        if (messageDurationSeconds > 0) {
                            durationMs = (messageDurationSeconds * 1000.0).roundToLong()
                        }
                    }
                    if (durationMs > 0) {
                        val fileSizeBytes = file.length()
                        val durationSeconds = durationMs / 1000.0
                        val estimatedTotalBitrate = (fileSizeBytes * 8 / durationSeconds).toLong()
                        if (bitRate == 0L && isVideoFile) {
                            bitRate = estimatedTotalBitrate
                            isBitRateEstimated = true
                        } else if (audioBitRate == 0L && sampleRate > 0 && !isVideoFile) {
                            audioBitRate = estimatedTotalBitrate
                            isAudioBitRateEstimated = true
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    // endregion

    class DetailCellFactory : UItem.UItemFactory<TextDetailSettingsCell>() {
        companion object {
            init {
                setup(DetailCellFactory())
            }

            @JvmStatic
            fun of(id: Int, label: CharSequence, value: CharSequence): UItem {
                return UItem.ofFactory(DetailCellFactory::class.java).apply {
                    this.id = id
                    this.text = label
                    this.subtext = value
                }
            }
        }

        override fun createView(
            context: Context,
            listView: RecyclerListView?,
            currentAccount: Int,
            classGuid: Int,
            resourcesProvider: Theme.ResourcesProvider?
        ): TextDetailSettingsCell {
            return TextDetailSettingsCell(context).apply {
                setMultilineDetail(true)
            }
        }

        override fun bindView(
            view: View,
            item: UItem,
            divider: Boolean,
            adapter: UniversalAdapter,
            listView: UniversalRecyclerView?
        ) {
            (view as TextDetailSettingsCell).setTextAndValue(item.text, item.subtext, divider)
        }

        override fun isClickable() = true
    }

    override fun createView(context: Context): View {
        return super.createView(context).also {
            listView.setSections()
            actionBar.setAdaptiveBackground(listView)
            listView.clipToPadding = false
            ViewCompat.setOnApplyWindowInsetsListener(listView) { v, insets ->
                val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
                insets
            }
        }
    }

    companion object {
        private val ROW_ID = InuUtils.generateId()
        private val ROW_SCHEDULED = InuUtils.generateId()
        private val ROW_MESSAGE = InuUtils.generateId()
        private val ROW_CAPTION = InuUtils.generateId()
        private val ROW_GROUP = InuUtils.generateId()
        private val ROW_CHANNEL = InuUtils.generateId()
        private val ROW_FROM = InuUtils.generateId()
        private val ROW_BOT = InuUtils.generateId()
        private val ROW_DATE = InuUtils.generateId()
        private val ROW_EDITED = InuUtils.generateId()
        private val ROW_FORWARD = InuUtils.generateId()
        private val ROW_RESTRICTION = InuUtils.generateId()
        private val ROW_FILE_NAME = InuUtils.generateId()
        private val ROW_FILE_PATH = InuUtils.generateId()
        private val ROW_FILE_SIZE = InuUtils.generateId()
        private val ROW_MIME_TYPE = InuUtils.generateId()
        private val ROW_MEDIA = InuUtils.generateId()
        private val ROW_DC = InuUtils.generateId()
        private val ROW_SHOW_JSON = InuUtils.generateId()

        private fun detailItem(id: Int, label: CharSequence, value: CharSequence): UItem {
            return DetailCellFactory.of(id, label, value)
        }

        private fun detailItem(id: Int, labelRes: Int, value: CharSequence): UItem {
            return DetailCellFactory.of(id, LocaleController.getString(labelRes), value)
        }
    }
}

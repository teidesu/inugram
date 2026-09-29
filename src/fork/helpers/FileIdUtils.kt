package desu.inugram.helpers

import desu.inugram.core.fileid.FileIdException
import desu.inugram.core.fileid.FileType
import desu.inugram.core.fileid.FullRemoteFileLocation
import desu.inugram.core.fileid.PhotoSizeSource
import desu.inugram.core.fileid.RemoteFileLocation
import desu.inugram.core.fileid.serializeFileId
import desu.inugram.core.fileid.serializeUniqueFileId
import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC

data class FileIds(val type: FileType, val fileId: String, val uniqueFileId: String)

object FileIdUtils {
    fun getFileIdsForMessage(messageObject: MessageObject): FileIds? {
        if (DialogObject.isEncryptedDialog(messageObject.dialogId)) return null
        val media = MessageObject.getMedia(messageObject.messageOwner) ?: return null
        val document = media.webpage?.document ?: media.document
        if (document != null) return getFileIdsForDocument(document)
        return getFileIdsForPhoto(media.webpage?.photo ?: media.photo)
    }

    fun getFileIdsForDocument(document: TLRPC.Document?): FileIds? {
        if (document !is TLRPC.TL_document || document is TLRPC.TL_documentEncrypted_old) return null
        return buildFileIds(
            FullRemoteFileLocation(
                dcId = document.dc_id,
                type = getDocumentFileType(document),
                fileReference = document.file_reference?.takeIf { it.isNotEmpty() },
                location = RemoteFileLocation.Common(document.id, document.access_hash),
            )
        )
    }

    fun getFileIdsForPhoto(photo: TLRPC.Photo?): FileIds? {
        if (photo !is TLRPC.TL_photo) return null
        val size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, Int.MAX_VALUE, false, null, true)
        val sizeType = size?.type?.firstOrNull() ?: return null
        return buildFileIds(
            FullRemoteFileLocation(
                dcId = photo.dc_id,
                type = FileType.PHOTO,
                fileReference = photo.file_reference?.takeIf { it.isNotEmpty() },
                location = RemoteFileLocation.Photo(
                    photo.id,
                    photo.access_hash,
                    PhotoSizeSource.Thumbnail(FileType.PHOTO, sizeType),
                ),
            )
        )
    }

    private fun buildFileIds(location: FullRemoteFileLocation): FileIds? {
        return try {
            FileIds(location.type, serializeFileId(location), serializeUniqueFileId(location))
        } catch (e: FileIdException) {
            FileLog.e(e)
            null
        }
    }

    private fun getDocumentFileType(document: TLRPC.Document): FileType {
        var animated = false
        var video: TLRPC.DocumentAttribute? = null
        var audio: TLRPC.DocumentAttribute? = null
        for (attr in document.attributes) {
            when (attr) {
                is TLRPC.TL_documentAttributeSticker,
                is TLRPC.TL_documentAttributeCustomEmoji,
                -> return FileType.STICKER

                is TLRPC.TL_documentAttributeAnimated -> animated = true
                is TLRPC.TL_documentAttributeVideo -> video = attr
                is TLRPC.TL_documentAttributeAudio -> audio = attr
            }
        }
        return when {
            video != null && video.round_message -> FileType.VIDEO_NOTE
            video != null && animated -> FileType.ANIMATION
            video != null -> FileType.VIDEO
            animated -> FileType.ANIMATION
            audio != null && audio.voice -> FileType.VOICE_NOTE
            audio != null -> FileType.AUDIO
            else -> FileType.DOCUMENT
        }
    }
}

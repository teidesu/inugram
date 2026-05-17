package desu.inugram.helpers

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.TextUtils
import com.google.android.exoplayer2.extractor.jpeg.MotionPhotoDescription
import com.google.android.exoplayer2.extractor.jpeg.XmpMotionPhotoDescriptionParser
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.MediaController
import org.telegram.messenger.NotificationCenter
import java.util.concurrent.ConcurrentHashMap

object GalleryHelper {
    private class Entry(val dateModified: Long, val description: MotionPhotoDescription?)

    private val cache = ConcurrentHashMap<Int, Entry>()

    // ASCII byte signatures present in motion-photo / micro-video XMP only.
    // generic XMP (lightroom edits, copyright, etc.) doesn't contain these.
    // quick byte scan to skip the expensive xml parsing for photos that are definitely not "live"
    private val MOTION_SIGNATURES = arrayOf(
        "MotionPhoto".toByteArray(Charsets.US_ASCII),
        "MicroVideo".toByteArray(Charsets.US_ASCII),
    )

    @JvmStatic
    fun lookupXmp(imageId: Int, dateModified: Long, xmpBlob: ByteArray): MotionPhotoDescription? {
        val cached = cache[imageId]
        if (cached != null && cached.dateModified == dateModified) {
            return cached.description
        }
        if (!containsSignature(xmpBlob)) {
            cache[imageId] = Entry(dateModified, null)
            return null
        }
        val description = try {
            XmpMotionPhotoDescriptionParser.parse(String(xmpBlob))
        } catch (_: Exception) {
            null
        }
        cache[imageId] = Entry(dateModified, description)
        return description
    }

    private fun containsSignature(blob: ByteArray): Boolean {
        for (sig in MOTION_SIGNATURES) {
            if (indexOf(blob, sig) >= 0) return true
        }
        return false
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        val end = haystack.size - needle.size
        outer@ for (i in 0..end) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    // ---------------- incremental updates ----------------

    private val pendingInserts = HashSet<Long>()
    private val pendingLock = Any()
    @Volatile
    private var forceFullRescan = false
    @Volatile
    private var incrementalEligible = false
    @Volatile
    private var cachedCameraAlbumId: Int? = null

    private val IMAGE_URI_RE = Regex("""^content://media/[^/]+/images/media/(\d+)$""")
    private val VIDEO_URI_RE = Regex("""^content://media/[^/]+/video/media/(\d+)$""")

    @JvmStatic
    fun recordEvent(uri: Uri?, flags: Int) {
        if (uri == null) {
            forceFullRescan = true; return
        }
        val str = uri.toString()
        val imageMatch = IMAGE_URI_RE.matchEntire(str)
        val videoMatch = if (imageMatch == null) VIDEO_URI_RE.matchEntire(str) else null
        val id = (imageMatch ?: videoMatch)?.groupValues?.get(1)?.toLongOrNull()
        if (id == null) {
            forceFullRescan = true
            return
        }
        val isImage = imageMatch != null
        when {
            (flags and ContentResolver.NOTIFY_DELETE) != 0 -> forceFullRescan = true
            (flags and ContentResolver.NOTIFY_INSERT) != 0 -> {
                if (isImage) {
                    synchronized(pendingLock) { pendingInserts.add(id) }
                } else {
                    forceFullRescan = true
                }
            }

            (flags and ContentResolver.NOTIFY_UPDATE) != 0 -> {
                if (!isImage) {
                    forceFullRescan = true
                } else {
                    // IS_PENDING finalization: row appears in default queries only after the
                    // writer flips IS_PENDING=0, which fires as UPDATE. If we don't have this id
                    // yet, treat it as a fresh insert.
                    val allPhotos = MediaController.allPhotosAlbumEntry
                    if (allPhotos == null || allPhotos.photosByIds.indexOfKey(id.toInt()) < 0) {
                        synchronized(pendingLock) { pendingInserts.add(id) }
                    }
                }
            }

            else -> forceFullRescan = true
        }
    }

    @JvmStatic
    fun markFullScanComplete(cameraAlbumId: Int?) {
        // do NOT clear pendingInserts — events that arrived during the scan stay queued
        // so the next debounce picks them up incrementally; merge dedupes via photosByIds.
        forceFullRescan = false
        cachedCameraAlbumId = cameraAlbumId
        incrementalEligible = true
    }

    /**
     * @return true if caller should skip full rescan (incremental handled or no-op).
     */
    @JvmStatic
    fun tryConsumeIncremental(guid: Int): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        if (!incrementalEligible || forceFullRescan) {
            synchronized(pendingLock) { pendingInserts.clear() }
            forceFullRescan = false
            return false
        }
        val ids = synchronized(pendingLock) {
            val snapshot = pendingInserts.toLongArray()
            pendingInserts.clear()
            snapshot
        }
        if (ids.isEmpty()) return true
        val allMediaEntry = MediaController.allMediaAlbumEntry
        val allPhotosEntry = MediaController.allPhotosAlbumEntry
        if (allMediaEntry == null || allPhotosEntry == null) return false

        Thread {
            try {
                val rows = queryRowsByIds(ids)
                if (rows.isEmpty()) return@Thread
                val knownBuckets = HashSet<Int>()
                for (a in MediaController.allMediaAlbums) knownBuckets.add(a.bucketId)
                if (rows.any { it.bucketId !in knownBuckets }) {
                    AndroidUtilities.runOnUIThread { MediaController.loadGalleryPhotosAlbums(guid) }
                    return@Thread
                }
                AndroidUtilities.runOnUIThread {
                    mergeIntoAlbums(rows)
                    NotificationCenter.getGlobalInstance().postNotificationName(
                        NotificationCenter.albumsDidLoad,
                        guid,
                        MediaController.allMediaAlbums,
                        MediaController.allPhotoAlbums,
                        cachedCameraAlbumId,
                    )
                }
            } catch (e: Throwable) {
                FileLog.e(e)
                AndroidUtilities.runOnUIThread { MediaController.loadGalleryPhotosAlbums(guid) }
            }
        }.apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
        return true
    }

    private fun queryRowsByIds(ids: LongArray): List<MediaController.PhotoEntry> {
        val context = ApplicationLoader.applicationContext
        val selection = MediaStore.Images.Media._ID + " IN (" + ids.joinToString(",") + ")"
        val sortColumn = if (Build.VERSION.SDK_INT > 28) MediaStore.Images.Media.DATE_MODIFIED else MediaStore.Images.Media.DATE_TAKEN
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            sortColumn,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.ImageColumns.XMP,
        )
        val cursor: Cursor = MediaStore.Images.Media.query(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "$sortColumn DESC",
        ) ?: return emptyList()

        val result = ArrayList<MediaController.PhotoEntry>(ids.size)
        cursor.use { c ->
            val idCol = c.getColumnIndex(MediaStore.Images.Media._ID)
            val bucketIdCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
            val dataCol = c.getColumnIndex(MediaStore.Images.Media.DATA)
            val dateCol = c.getColumnIndex(sortColumn)
            val orientationCol = c.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
            val widthCol = c.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val heightCol = c.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            val sizeCol = c.getColumnIndex(MediaStore.Images.Media.SIZE)
            val xmpCol = try {
                c.getColumnIndex(MediaStore.Images.Media.XMP)
            } catch (_: Exception) {
                -1
            }

            while (c.moveToNext()) {
                val path = c.getString(dataCol)
                if (TextUtils.isEmpty(path)) continue
                val imageId = c.getInt(idCol)
                val bucketId = c.getInt(bucketIdCol)
                val dateTaken = c.getLong(dateCol)
                val orientation = c.getInt(orientationCol)
                val width = c.getInt(widthCol)
                val height = c.getInt(heightCol)
                val size = c.getLong(sizeCol)

                var motionPhoto: MotionPhotoDescription? = null
                if (xmpCol >= 0) {
                    try {
                        val blob = c.getBlob(xmpCol)
                        if (blob != null && blob.isNotEmpty()) {
                            motionPhoto = lookupXmp(imageId, dateTaken, blob)
                        }
                    } catch (e: Exception) {
                        FileLog.e(e)
                    }
                }

                val entry = MediaController.PhotoEntry(bucketId, imageId, dateTaken, path, orientation, 0, false, width, height, size)
                if (motionPhoto != null) {
                    var photoItem: MotionPhotoDescription.ContainerItem? = null
                    var videoItem: MotionPhotoDescription.ContainerItem? = null
                    for (i in motionPhoto.items.indices) {
                        val item = motionPhoto.items[i]
                        if ("Primary".equals(item.semantic, ignoreCase = true)) photoItem = item
                        else if ("MotionPhoto".equals(item.semantic, ignoreCase = true)) videoItem = item
                    }
                    if (photoItem != null && videoItem != null && videoItem.length > 0) {
                        try {
                            val wholeFile = java.io.File(path)
                            val videoStart = wholeFile.length() - videoItem.length
                            entry.isVideo = true
                            entry.isLivePhoto = true
                            entry.discardLivePhoto = InuConfig.OPT_IN_MOTION_PHOTOS.value
                            entry.livePhotoVideoOffset = videoStart
                            entry.livePhotoTimestampUs = motionPhoto.photoPresentationTimestampUs
                        } catch (e: Exception) {
                            FileLog.e(e)
                        }
                    }
                }
                result.add(entry)
            }
        }
        return result
    }

    private fun mergeIntoAlbums(newPhotos: List<MediaController.PhotoEntry>) {
        val allMediaEntry = MediaController.allMediaAlbumEntry ?: return
        val allPhotosEntry = MediaController.allPhotosAlbumEntry ?: return
        // bucketId=0 is reserved for the synthetic "All X" albums — exclude from per-bucket lookup
        val bucketMap = HashMap<Int, MediaController.AlbumEntry>()
        for (a in MediaController.allMediaAlbums) if (a.bucketId != 0) bucketMap[a.bucketId] = a
        val photoBucketMap = HashMap<Int, MediaController.AlbumEntry>()
        for (a in MediaController.allPhotoAlbums) if (a.bucketId != 0) photoBucketMap[a.bucketId] = a

        for (entry in newPhotos) {
            if (allPhotosEntry.photosByIds.indexOfKey(entry.imageId) >= 0) continue
            insertSortedByDate(allMediaEntry, entry)
            insertSortedByDate(allPhotosEntry, entry)
            bucketMap[entry.bucketId]?.let { insertSortedByDate(it, entry) }
            photoBucketMap[entry.bucketId]?.let { insertSortedByDate(it, entry) }
        }
    }

    private fun insertSortedByDate(album: MediaController.AlbumEntry, entry: MediaController.PhotoEntry) {
        val photos = album.photos
        var idx = 0
        while (idx < photos.size && photos[idx].dateTaken > entry.dateTaken) idx++
        photos.add(idx, entry)
        album.photosByIds.put(entry.imageId, entry)
        if (idx == 0) album.coverPhoto = entry
    }
}

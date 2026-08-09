package desu.inugram.helpers.media

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
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.ImageReceiver
import org.telegram.messenger.MediaController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.Utilities
import org.telegram.ui.PhotoViewer
import java.io.File
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
    private val pendingUpdates = HashSet<Long>()
    private val pendingLock = Any()
    @Volatile
    private var forceFullRescan = false
    @Volatile
    private var incrementalEligible = false
    private var incrementalRetryScheduled = false
    private var missingRowRetryAttempts = 0
    private val invalidatedThumbIds = HashSet<Int>()
    @Volatile
    private var cachedCameraAlbumId: Int? = null

    private val IMAGE_URI_RE = Regex("""^content://media/[^/]+/images/media/(\d+)$""")
    private val VIDEO_URI_RE = Regex("""^content://media/[^/]+/video/media/(\d+)$""")

    @JvmStatic
    fun invalidateThumbnailIfChanged(
        receiver: ImageReceiver,
        previous: MediaController.PhotoEntry?,
        current: MediaController.PhotoEntry,
    ) {
        val invalidated = if (previous === current) {
            invalidatedThumbIds.remove(current.imageId)
        } else {
            hasMediaChanged(previous, current)
        }
        if (!invalidated) return
        receiver.imageKey?.let { ImageLoader.getInstance().removeImage(it) }
        receiver.clearImage()
    }

    @JvmStatic
    fun refreshPhotoViewer(album: MediaController.AlbumEntry?) {
        if (album == null || !PhotoViewer.hasInstance()) return
        val viewer = PhotoViewer.getInstance()
        if (!viewer.isVisible) return
        val index = viewer.currentIndex
        val photos = viewer.imagesArrLocals
        if (index !in photos.indices) return
        val previous = photos[index] as? MediaController.PhotoEntry ?: return
        val current = album.photosByIds[previous.imageId] ?: return
        if (previous === current) return
        android.util.Log.d("inu-gallery", "refreshPhotoViewer id=${previous.imageId} changed=${hasMediaChanged(previous, current)} editing=${viewer.inu_isInEditMode()}")
        viewer.inu_reloadCurrentPhoto(current, hasMediaChanged(previous, current))
    }

    @JvmStatic
    fun isPhotoViewerEditing(): Boolean {
        return PhotoViewer.hasInstance() && PhotoViewer.getInstance().inu_isInEditMode()
    }

    @JvmStatic
    fun reconcileSendEntry(index: Int, resolved: MediaController.PhotoEntry?): MediaController.PhotoEntry? {
        if (!PhotoViewer.hasInstance()) return resolved
        val viewer = PhotoViewer.getInstance()
        if (!viewer.isVisible) return resolved
        val photos = viewer.imagesArrLocals
        if (index !in photos.indices) return resolved
        val shown = photos[index] as? MediaController.PhotoEntry ?: return resolved
        if (shown === resolved) return resolved
        android.util.Log.e("inu-gallery", "reconcileSendEntry: mismatch at index=$index shown=${shown.imageId} resolved=${resolved?.imageId}")
        if (resolved != null && resolved.imageId == shown.imageId) {
            resolved.copyFrom(shown)
            return resolved
        }
        return shown
    }

    private fun hasMediaChanged(
        previous: MediaController.PhotoEntry?,
        current: MediaController.PhotoEntry,
    ): Boolean {
        if (previous == null || previous.imageId != current.imageId) return false
        return previous.dateTaken != current.dateTaken ||
            previous.size != current.size ||
            previous.width != current.width ||
            previous.height != current.height ||
            previous.orientation != current.orientation ||
            previous.path != current.path
    }

    @JvmStatic
    fun recordEvent(uri: Uri?, flags: Int) {
        android.util.Log.d("inu-gallery", "recordEvent uri=$uri flags=$flags")
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
                    val existingPhoto = MediaController.allPhotosAlbumEntry?.photosByIds?.get(id.toInt())
                    if (existingPhoto == null) {
                        synchronized(pendingLock) { pendingInserts.add(id) }
                    } else {
                        synchronized(pendingLock) { pendingUpdates.add(id) }
                    }
                }
            }

            else -> forceFullRescan = true
        }
    }

    private fun isPhotoViewerBlockingGalleryUpdate(): Boolean {
        if (!PhotoViewer.hasInstance()) return false
        return PhotoViewer.getInstance().isVisible
    }

    @JvmStatic
    fun markFullScanComplete(cameraAlbumId: Int?) {
        // do NOT clear pendingInserts/pendingUpdates: events that arrived during the scan stay
        // queued so the next debounce picks them up incrementally; apply dedupes via photosByIds.
        cachedCameraAlbumId = cameraAlbumId
        incrementalEligible = true
        invalidatedThumbIds.clear()
        missingRowRetryAttempts = 0
        android.util.Log.d("inu-gallery", "markFullScanComplete")
    }

    private fun scheduleIncrementalRetry(guid: Int, delay: Int) {
        AndroidUtilities.runOnUIThread {
            if (incrementalRetryScheduled) return@runOnUIThread
            incrementalRetryScheduled = true
            AndroidUtilities.runOnUIThread({
                incrementalRetryScheduled = false
                if (!tryConsumeIncremental(guid)) {
                    MediaController.loadGalleryPhotosAlbums(guid)
                }
            }, delay.toLong())
        }
    }

    /**
     * @return true if caller should skip full rescan (incremental handled or no-op).
     */
    @JvmStatic
    fun tryConsumeIncremental(guid: Int): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        if (!incrementalEligible || forceFullRescan) {
            android.util.Log.d("inu-gallery", "tryConsumeIncremental: full rescan (eligible=$incrementalEligible force=$forceFullRescan)")
            synchronized(pendingLock) {
                pendingInserts.clear()
                pendingUpdates.clear()
            }
            forceFullRescan = false
            return false
        }
        // inserting into album photo lists shifts positions that an open PhotoViewer's
        // placeProvider maps back via getPhotoEntryAtPosition(index); merging now would make it
        // resolve a different photo than the one on screen. Defer inserts until it closes.
        // In-place updates keep object identity and positions, so they are always safe to apply.
        val blocked = isPhotoViewerBlockingGalleryUpdate()
        val inserts: LongArray
        val updates: LongArray
        synchronized(pendingLock) {
            inserts = if (blocked) LongArray(0) else pendingInserts.toLongArray().also { pendingInserts.clear() }
            updates = pendingUpdates.toLongArray().also { pendingUpdates.clear() }
        }
        if (blocked && synchronized(pendingLock) { pendingInserts.isNotEmpty() }) {
            android.util.Log.d("inu-gallery", "tryConsumeIncremental: inserts deferred, viewer visible")
            scheduleIncrementalRetry(guid, 200)
        }
        if (inserts.isEmpty() && updates.isEmpty()) return true
        if (MediaController.allMediaAlbumEntry == null || MediaController.allPhotosAlbumEntry == null) return false

        Thread {
            try {
                val rows = queryRowsByIds(inserts + updates)
                AndroidUtilities.runOnUIThread { applyIncrementalRows(guid, inserts, updates, rows) }
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

    private fun applyIncrementalRows(guid: Int, inserts: LongArray, updates: LongArray, rows: List<MediaController.PhotoEntry>) {
        val allMediaEntry = MediaController.allMediaAlbumEntry
        val allPhotosEntry = MediaController.allPhotosAlbumEntry
        if (allMediaEntry == null || allPhotosEntry == null) {
            MediaController.loadGalleryPhotosAlbums(guid)
            return
        }

        val foundIds = rows.mapTo(HashSet()) { it.imageId.toLong() }
        val missing = (inserts + updates).filter { it !in foundIds }
        if (missing.isEmpty()) {
            missingRowRetryAttempts = 0
        } else if (missingRowRetryAttempts < 5) {
            // IS_PENDING rows are hidden from default queries, and events can arrive before the
            // row becomes visible to other processes. Requeue instead of silently dropping,
            // otherwise a fresh screenshot may never show up until an unrelated full rescan.
            missingRowRetryAttempts++
            android.util.Log.d("inu-gallery", "applyIncrementalRows: ${missing.size} rows not visible yet, retrying (attempt $missingRowRetryAttempts)")
            val updateIds = updates.toHashSet()
            synchronized(pendingLock) {
                for (id in missing) {
                    if (id in updateIds) pendingUpdates.add(id) else pendingInserts.add(id)
                }
            }
            scheduleIncrementalRetry(guid, 1000)
        } else {
            android.util.Log.d("inu-gallery", "applyIncrementalRows: giving up on ${missing.size} missing rows")
            missingRowRetryAttempts = 0
        }
        if (rows.isEmpty()) return

        val toInsert = ArrayList<MediaController.PhotoEntry>()
        var changed = false
        for (row in rows) {
            val existing = allPhotosEntry.photosByIds[row.imageId] ?: allMediaEntry.photosByIds[row.imageId]
            if (existing == null) {
                toInsert.add(row)
            } else if (row.bucketId != existing.bucketId) {
                android.util.Log.d("inu-gallery", "applyIncrementalRows: id=${row.imageId} moved buckets, full rescan")
                MediaController.loadGalleryPhotosAlbums(guid)
                return
            } else if (hasMediaChanged(existing, row)) {
                android.util.Log.d("inu-gallery", "applyIncrementalRows: updating id=${row.imageId} in place")
                applyUpdateInPlace(existing, row)
                changed = true
            }
        }

        if (toInsert.isNotEmpty()) {
            if (isPhotoViewerBlockingGalleryUpdate()) {
                synchronized(pendingLock) { toInsert.forEach { pendingInserts.add(it.imageId.toLong()) } }
                scheduleIncrementalRetry(guid, 200)
            } else {
                val knownBuckets = HashSet<Int>()
                for (a in MediaController.allMediaAlbums) knownBuckets.add(a.bucketId)
                if (toInsert.any { it.bucketId !in knownBuckets }) {
                    MediaController.loadGalleryPhotosAlbums(guid)
                    return
                }
                android.util.Log.d("inu-gallery", "applyIncrementalRows: inserting ${toInsert.size} rows")
                mergeIntoAlbums(toInsert)
                changed = true
            }
        }

        if (changed) {
            NotificationCenter.getGlobalInstance().postNotificationName(
                NotificationCenter.albumsDidLoad,
                guid,
                MediaController.allMediaAlbums,
                MediaController.allPhotoAlbums,
                cachedCameraAlbumId,
            )
        }
    }

    private fun applyUpdateInPlace(existing: MediaController.PhotoEntry, fresh: MediaController.PhotoEntry) {
        cache.remove(existing.imageId)
        ImageLoader.getInstance().removeImage(Utilities.MD5("thumb://${existing.imageId}:${existing.path}"))
        existing.path = fresh.path
        existing.dateTaken = fresh.dateTaken
        existing.size = fresh.size
        existing.width = fresh.width
        existing.height = fresh.height
        existing.orientation = fresh.orientation
        existing.invert = fresh.invert
        existing.thumb = null
        if (existing.isLivePhoto != fresh.isLivePhoto) {
            existing.discardLivePhoto = fresh.discardLivePhoto
        }
        existing.isVideo = fresh.isVideo
        existing.isLivePhoto = fresh.isLivePhoto
        existing.livePhotoVideoOffset = fresh.livePhotoVideoOffset
        existing.livePhotoTimestampUs = fresh.livePhotoTimestampUs
        invalidatedThumbIds.add(existing.imageId)
        refreshViewerIfShowing(existing)
    }

    private fun refreshViewerIfShowing(entry: MediaController.PhotoEntry) {
        if (!PhotoViewer.hasInstance()) return
        val viewer = PhotoViewer.getInstance()
        if (!viewer.isVisible || viewer.inu_isInEditMode()) return
        val index = viewer.currentIndex
        val photos = viewer.imagesArrLocals
        if (index in photos.indices && photos[index] === entry) {
            viewer.inu_reloadCurrentPhoto(entry, true)
        }
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
                if (xmpCol >= 0 && !InuConfig.DISABLE_MOTION_PHOTOS.value) {
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
                            val wholeFile = File(path)
                            val videoStart = wholeFile.length() - videoItem.length
                            entry.isVideo = true
                            entry.isLivePhoto = true
                            entry.discardLivePhoto = true
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

package desu.inugram.helpers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.TreeMap

/**
 * inBitmap pool. Stock telegram never sets opts.inBitmap on the main decode path
 * (CacheOutTask.run), so every decode allocates fresh — fuels GC pressure.
 *
 * Bitmaps evicted from ImageLoader's LRU are offered here instead of recycled
 * immediately. Decodes try to acquire a compatible buffer first.
 */
object BitmapPool {
    private const val MAX_POOL_BYTES = 8 * 1024 * 1024

    private val pools = HashMap<Bitmap.Config, TreeMap<Int, ArrayDeque<Bitmap>>>()
    private var totalBytes = 0

    @Synchronized
    @JvmStatic
    fun tryAcquire(opts: BitmapFactory.Options, config: Bitmap.Config?): Boolean {
        if (config == null) return false
        val needed = computeByteCount(opts, config)
        if (needed <= 0) return false
        val tree = pools[config] ?: return false
        val entry = tree.ceilingEntry(needed) ?: return false
        val deque = entry.value
        val bm = deque.removeFirstOrNull() ?: return false
        if (deque.isEmpty()) tree.remove(entry.key)
        if (bm.isRecycled || !bm.isMutable) {
            totalBytes -= entry.key
            return tryAcquire(opts, config)
        }
        totalBytes -= entry.key
        opts.inBitmap = bm
        opts.inMutable = true
        return true
    }

    @Synchronized
    @JvmStatic
    fun offer(bm: Bitmap?): Boolean {
        if (bm == null || bm.isRecycled || !bm.isMutable) return false
        val config = bm.config ?: return false
        val size = bm.allocationByteCount
        if (size <= 0 || size > MAX_POOL_BYTES) return false
        while (totalBytes + size > MAX_POOL_BYTES) {
            if (!evictOne()) return false
        }
        val tree = pools.getOrPut(config) { TreeMap() }
        tree.getOrPut(size) { ArrayDeque() }.addLast(bm)
        totalBytes += size
        return true
    }

    @Synchronized
    @JvmStatic
    fun clear() {
        for (tree in pools.values) {
            for (deque in tree.values) {
                for (bm in deque) {
                    if (!bm.isRecycled) bm.recycle()
                }
            }
        }
        pools.clear()
        totalBytes = 0
    }

    private fun evictOne(): Boolean {
        for (tree in pools.values) {
            if (tree.isEmpty()) continue
            val firstKey = tree.firstKey()
            val deque = tree[firstKey] ?: continue
            val bm = deque.removeFirstOrNull() ?: continue
            totalBytes -= firstKey
            if (deque.isEmpty()) tree.remove(firstKey)
            if (!bm.isRecycled) bm.recycle()
            return true
        }
        return false
    }

    private fun computeByteCount(opts: BitmapFactory.Options, config: Bitmap.Config): Int {
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return 0
        val sample = if (opts.inSampleSize > 0) opts.inSampleSize else 1
        val sw = (w + sample - 1) / sample
        val sh = (h + sample - 1) / sample
        val bpp = when (config) {
            Bitmap.Config.ARGB_8888 -> 4
            Bitmap.Config.RGB_565 -> 2
            Bitmap.Config.ALPHA_8 -> 1
            else -> 4
        }
        return sw * sh * bpp
    }
}

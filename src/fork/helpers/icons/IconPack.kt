package desu.inugram.helpers.icons

import android.util.SparseIntArray

abstract class IconPack {
    private val icons by lazy { buildIcons() }

    protected abstract fun buildIcons(): SparseIntArray

    fun map(original: Int): Int = icons.get(original, original)
}

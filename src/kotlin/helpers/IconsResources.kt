package desu.inugram.helpers

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.drawable.Drawable
import desu.inugram.InuConfig
import desu.inugram.helpers.icons.SolarIconPack

class IconsResources(resources: Resources) : Resources(resources.assets, resources.displayMetrics, resources.configuration) {

    @SuppressLint("UseCompatLoadingForDrawables")
    @Deprecated("Deprecated in Java")
    @Throws(NotFoundException::class)
    override fun getDrawable(id: Int): Drawable {
        return super.getDrawable(getConversion(id), null);
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    @Throws(NotFoundException::class)
    override fun getDrawable(id: Int, theme: Theme?): Drawable {
        return super.getDrawable(getConversion(id), theme);
    }

    @Throws(NotFoundException::class)
    override fun getDrawableForDensity(id: Int, density: Int, theme: Theme?): Drawable? {
        return super.getDrawableForDensity(getConversion(id), density, theme);
    }

    @Deprecated("Deprecated in Java")
    @Throws(NotFoundException::class)
    override fun getDrawableForDensity(id: Int, density: Int): Drawable? {
        return super.getDrawableForDensity(getConversion(id), density, null);
    }

    private fun getConversion(icon: Int): Int {
        return when (InuConfig.ICON_REPLACEMENT.value) {
            InuConfig.IconReplacementItem.SOLAR -> SolarIconPack.map(icon)
            else -> icon
        }
    }
}
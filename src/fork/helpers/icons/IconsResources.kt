@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package desu.inugram.helpers.icons

import android.annotation.SuppressLint
import android.content.res.AssetFileDescriptor
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.TypedArray
import android.content.res.XmlResourceParser
import android.graphics.Movie
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.TypedValue
import desu.inugram.InuConfig
import java.io.InputStream

class IconsResources(private val resources: Resources) : Resources(resources.assets, resources.displayMetrics, resources.configuration) {
    fun wrapsResources(resources: Resources): Boolean {
        return this.resources === resources
    }

    override fun getAnimation(id: Int): XmlResourceParser = resources.getAnimation(id)
    override fun getBoolean(id: Int): Boolean = resources.getBoolean(id)
    override fun getColor(id: Int): Int = resources.getColor(id)
    override fun getColor(id: Int, theme: Theme?): Int = resources.getColor(id, theme)
    override fun getColorStateList(id: Int): ColorStateList = resources.getColorStateList(id)
    override fun getColorStateList(id: Int, theme: Theme?): ColorStateList = resources.getColorStateList(id, theme)
    override fun getConfiguration(): Configuration = resources.configuration
    override fun getDimension(id: Int): Float = resources.getDimension(id)
    override fun getDimensionPixelOffset(id: Int): Int = resources.getDimensionPixelOffset(id)
    override fun getDimensionPixelSize(id: Int): Int = resources.getDimensionPixelSize(id)
    override fun getDisplayMetrics(): DisplayMetrics = resources.displayMetrics

    @SuppressLint("UseCompatLoadingForDrawables")
    override fun getDrawable(id: Int): Drawable = resources.getDrawable(getConversion(id))

    @SuppressLint("UseCompatLoadingForDrawables")
    override fun getDrawable(id: Int, theme: Theme?): Drawable = resources.getDrawable(getConversion(id), theme)

    override fun getDrawableForDensity(id: Int, density: Int): Drawable? = resources.getDrawableForDensity(getConversion(id), density)
    override fun getDrawableForDensity(id: Int, density: Int, theme: Theme?): Drawable? = resources.getDrawableForDensity(getConversion(id), density, theme)
    override fun getFloat(id: Int): Float = resources.getFloat(id)
    override fun getFont(id: Int): Typeface = resources.getFont(id)
    override fun getFraction(id: Int, base: Int, pbase: Int): Float = resources.getFraction(id, base, pbase)
    override fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int = resources.getIdentifier(name, defType, defPackage)
    override fun getIntArray(id: Int): IntArray = resources.getIntArray(id)
    override fun getInteger(id: Int): Int = resources.getInteger(id)
    override fun getLayout(id: Int): XmlResourceParser = resources.getLayout(id)
    override fun getMovie(id: Int): Movie? = resources.getMovie(id)
    override fun getQuantityString(id: Int, quantity: Int): String = resources.getQuantityString(id, quantity)
    override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any): String = resources.getQuantityString(id, quantity, *formatArgs)
    override fun getQuantityText(id: Int, quantity: Int): CharSequence = resources.getQuantityText(id, quantity)
    override fun getResourceEntryName(resid: Int): String = resources.getResourceEntryName(resid)
    override fun getResourceName(resid: Int): String = resources.getResourceName(resid)
    override fun getResourcePackageName(resid: Int): String = resources.getResourcePackageName(resid)
    override fun getResourceTypeName(resid: Int): String = resources.getResourceTypeName(resid)
    override fun getString(id: Int): String = resources.getString(id)
    override fun getString(id: Int, vararg formatArgs: Any): String = resources.getString(id, *formatArgs)
    override fun getStringArray(id: Int): Array<String> = resources.getStringArray(id)
    override fun getText(id: Int): CharSequence = resources.getText(id)
    override fun getText(id: Int, def: CharSequence?): CharSequence = resources.getText(id, def)
    override fun getTextArray(id: Int): Array<CharSequence> = resources.getTextArray(id)
    override fun getValue(id: Int, outValue: TypedValue, resolveRefs: Boolean) = resources.getValue(id, outValue, resolveRefs)
    override fun getValue(name: String?, outValue: TypedValue, resolveRefs: Boolean) = resources.getValue(name, outValue, resolveRefs)
    override fun getValueForDensity(id: Int, density: Int, outValue: TypedValue, resolveRefs: Boolean) = resources.getValueForDensity(id, density, outValue, resolveRefs)
    override fun getXml(id: Int): XmlResourceParser = resources.getXml(id)
    override fun obtainAttributes(set: AttributeSet, attrs: IntArray): TypedArray = resources.obtainAttributes(set, attrs)
    override fun obtainTypedArray(id: Int): TypedArray = resources.obtainTypedArray(id)
    override fun openRawResource(id: Int): InputStream = resources.openRawResource(id)
    override fun openRawResource(id: Int, value: TypedValue): InputStream = resources.openRawResource(id, value)
    override fun openRawResourceFd(id: Int): AssetFileDescriptor? = resources.openRawResourceFd(id)
    override fun parseBundleExtra(tagName: String, attrs: AttributeSet, outBundle: Bundle) = resources.parseBundleExtra(tagName, attrs, outBundle)
    override fun parseBundleExtras(parser: XmlResourceParser, outBundle: Bundle) = resources.parseBundleExtras(parser, outBundle)
    override fun updateConfiguration(config: Configuration?, metrics: DisplayMetrics?) = resources.updateConfiguration(config, metrics)

    private fun getConversion(icon: Int): Int {
        return when (InuConfig.ICON_REPLACEMENT.value) {
            InuConfig.IconReplacementItem.SOLAR -> SolarIconPack.map(icon)
            InuConfig.IconReplacementItem.VKUI -> VkIconPack.map(icon)
            else -> icon
        }
    }
}

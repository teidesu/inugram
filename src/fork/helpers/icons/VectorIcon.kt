package desu.inugram.helpers.icons

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.Xml
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import kotlin.math.min
import kotlin.math.roundToInt

/** the subset of svg that VectorDrawable can express, drawn natively instead of through androidsvg */
class VectorIcon private constructor(
    private val viewBox: FloatArray,
    private val stretch: Boolean,
    private val root: Group,
) {
    private class Group(val clip: Path?, val children: List<Any>)

    private class Shape(
        val path: Path,
        val clipEvenOdd: Boolean,
        val fillColor: Int,
        val strokeColor: Int,
        val strokeWidth: Float,
        val cap: Paint.Cap,
        val join: Paint.Join,
        val miter: Float,
    )

    private data class Style(
        val fill: Int = Color.BLACK,
        val fillOpacity: Float = 1f,
        val evenOdd: Boolean = false,
        val clipEvenOdd: Boolean = false,
        val stroke: Int = Color.TRANSPARENT,
        val strokeOpacity: Float = 1f,
        val strokeWidth: Float = 1f,
        val cap: Paint.Cap = Paint.Cap.BUTT,
        val join: Paint.Join = Paint.Join.MITER,
        val miter: Float = 4f,
    )

    private class Unsupported : Exception()

    class Drawable(private val icon: VectorIcon, width: Int, height: Int) : IconPackDrawable(width, height) {
        private val matrix = Matrix()
        private val transformed = Path()

        override fun copy() = Drawable(icon, intrinsicWidth, intrinsicHeight)

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val viewBox = icon.viewBox
            var scaleX = bounds.width() / viewBox[2]
            var scaleY = bounds.height() / viewBox[3]
            var dx = 0f
            var dy = 0f
            if (!icon.stretch) {
                val scale = min(scaleX, scaleY)
                dx = (bounds.width() - viewBox[2] * scale) / 2
                dy = (bounds.height() - viewBox[3] * scale) / 2
                scaleX = scale
                scaleY = scale
            }
            paint.colorFilter = activeColorFilter
            val save = canvas.save()
            canvas.translate(bounds.left + dx, bounds.top + dy)
            if (scaleX == scaleY) {
                canvas.scale(scaleX, scaleX)
                canvas.translate(-viewBox[0], -viewBox[1])
                drawGroup(canvas, icon.root, null, 1f)
            } else {
                // VectorDrawable transforms the paths and scales strokes by the smaller axis
                matrix.setTranslate(-viewBox[0], -viewBox[1])
                matrix.postScale(scaleX, scaleY)
                drawGroup(canvas, icon.root, matrix, min(scaleX, scaleY))
            }
            canvas.restoreToCount(save)
        }

        private fun drawGroup(canvas: Canvas, group: Group, matrix: Matrix?, strokeScale: Float) {
            val save = group.clip?.let {
                val count = canvas.save()
                canvas.clipPath(transform(it, matrix))
                count
            }
            for (child in group.children) {
                if (child is Group) drawGroup(canvas, child, matrix, strokeScale) else drawShape(canvas, child as Shape, matrix, strokeScale)
            }
            save?.let(canvas::restoreToCount)
        }

        private fun drawShape(canvas: Canvas, shape: Shape, matrix: Matrix?, strokeScale: Float) {
            val path = transform(shape.path, matrix)
            val alpha = paint.alpha
            if (shape.fillColor != Color.TRANSPARENT) {
                paint.style = Paint.Style.FILL
                paint.color = shape.fillColor
                paint.alpha = Color.alpha(shape.fillColor) * alpha / 255
                canvas.drawPath(path, paint)
            }
            if (shape.strokeColor != Color.TRANSPARENT && shape.strokeWidth > 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = shape.strokeWidth * strokeScale
                paint.strokeCap = shape.cap
                paint.strokeJoin = shape.join
                paint.strokeMiter = shape.miter
                paint.color = shape.strokeColor
                paint.alpha = Color.alpha(shape.strokeColor) * alpha / 255
                canvas.drawPath(path, paint)
            }
            paint.alpha = alpha
        }

        private fun transform(path: Path, matrix: Matrix?): Path {
            if (matrix == null) return path
            path.transform(matrix, transformed)
            transformed.fillType = path.fillType
            return transformed
        }
    }

    companion object {
        private val IGNORED_ATTRS = setOf("xmlns", "xmlns:xlink", "version", "id", "xml:space")
        private val SKIPPED_ELEMENTS = setOf("title", "desc", "metadata")
        private val CLIP_URL = Regex("url\\(#([^)]+)\\)")
        private val RECT_ATTRS = listOf("x", "y", "width", "height", "rx", "ry")

        /** null when the svg uses anything outside the supported subset */
        fun parse(bytes: ByteArray): VectorIcon? = try {
            val parser = Xml.newPullParser()
            parser.setInput(ByteArrayInputStream(bytes), null)
            while (parser.next() != XmlPullParser.START_TAG) {
                if (parser.eventType == XmlPullParser.END_DOCUMENT) throw Unsupported()
            }
            if (parser.name != "svg") throw Unsupported()
            parseRoot(parser)
        } catch (_: Exception) {
            null
        }

        private fun parseRoot(parser: XmlPullParser): VectorIcon {
            var width = -1f
            var height = -1f
            var viewBox: FloatArray? = null
            var stretch = false
            var style = Style()
            forEachAttribute(parser) { name, value ->
                when (name) {
                    "width" -> width = parseLength(value)
                    "height" -> height = parseLength(value)
                    "viewBox" -> viewBox = parseNumbers(value).takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 } ?: throw Unsupported()
                    "preserveAspectRatio" -> stretch = when (value.trim()) {
                        "none" -> true
                        "xMidYMid", "xMidYMid meet" -> false
                        else -> throw Unsupported()
                    }
                    else -> style = applyStyle(style, name, value)
                }
            }
            val box = viewBox ?: if (width > 0 && height > 0) floatArrayOf(0f, 0f, width, height) else throw Unsupported()
            val clips = HashMap<String, Path>()
            return VectorIcon(box, stretch, Group(null, parseChildren(parser, clips, style)))
        }

        private fun parseChildren(parser: XmlPullParser, clips: MutableMap<String, Path>, style: Style): List<Any> {
            val children = ArrayList<Any>()
            while (parser.next() != XmlPullParser.END_TAG) {
                when (parser.eventType) {
                    XmlPullParser.END_DOCUMENT -> throw Unsupported()
                    XmlPullParser.TEXT -> if (!parser.isWhitespace) throw Unsupported()
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "path", "rect" -> {
                            children.add(parseShape(parser, style))
                            if (parser.next() != XmlPullParser.END_TAG) throw Unsupported()
                        }
                        "g" -> {
                            var clip: Path? = null
                            var groupStyle = style
                            forEachAttribute(parser) { name, value ->
                                if (name == "clip-path") {
                                    clip = clips[CLIP_URL.matchEntire(value.trim())?.groupValues?.get(1)] ?: throw Unsupported()
                                } else {
                                    groupStyle = applyStyle(groupStyle, name, value)
                                }
                            }
                            children.add(Group(clip, parseChildren(parser, clips, groupStyle)))
                        }
                        "defs" -> {
                            forEachAttribute(parser) { _, _ -> throw Unsupported() }
                            parseDefs(parser, clips)
                        }
                        in SKIPPED_ELEMENTS -> skipElement(parser)
                        else -> throw Unsupported()
                    }
                }
            }
            return children
        }

        private fun parseDefs(parser: XmlPullParser, clips: MutableMap<String, Path>) {
            while (parser.next() != XmlPullParser.END_TAG) {
                when (parser.eventType) {
                    XmlPullParser.END_DOCUMENT -> throw Unsupported()
                    XmlPullParser.TEXT -> if (!parser.isWhitespace) throw Unsupported()
                    XmlPullParser.START_TAG -> {
                        if (parser.name != "clipPath") throw Unsupported()
                        val id = parser.getAttributeValue(null, "id") ?: throw Unsupported()
                        forEachAttribute(parser) { _, _ -> throw Unsupported() }
                        val clip = Path()
                        for (child in parseChildren(parser, clips, Style())) {
                            if (child !is Shape) throw Unsupported()
                            val shapeClip = Path(child.path)
                            shapeClip.fillType = if (child.clipEvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                            clip.op(shapeClip, Path.Op.UNION)
                        }
                        clips[id] = clip
                    }
                }
            }
        }

        private fun parseShape(parser: XmlPullParser, inherited: Style): Shape {
            val isRect = parser.name == "rect"
            var path: Path? = null
            val rect = FloatArray(6) { -1f }.also { it[0] = 0f; it[1] = 0f }
            var opacity = 1f
            var style = inherited
            forEachAttribute(parser) { name, value ->
                when {
                    !isRect && name == "d" -> path = PathParser.createPathFromPathData(value)
                    isRect && name in RECT_ATTRS -> rect[RECT_ATTRS.indexOf(name)] = value.trim().removeSuffix("px").toFloat()
                    name == "opacity" -> opacity = value.trim().toFloat()
                    else -> style = applyStyle(style, name, value)
                }
            }
            val shapePath = if (isRect) createRect(rect) else path ?: throw Unsupported()
            shapePath.fillType = if (style.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            val fill = applyOpacity(style.fill, style.fillOpacity * opacity)
            val stroke = applyOpacity(style.stroke, style.strokeOpacity * opacity)
            // element opacity composites fill and stroke together, which per-paint alpha only matches when one of them is drawn
            if (opacity < 1f && fill != Color.TRANSPARENT && stroke != Color.TRANSPARENT && style.strokeWidth > 0) throw Unsupported()
            return Shape(shapePath, style.clipEvenOdd, fill, stroke, style.strokeWidth, style.cap, style.join, style.miter)
        }

        private fun createRect(rect: FloatArray): Path {
            val (x, y, width, height) = rect
            if (width < 0 || height < 0) throw Unsupported()
            var rx = rect[4]
            var ry = rect[5]
            if (rx < 0) rx = ry
            if (ry < 0) ry = rx
            return Path().apply {
                if (rx > 0) {
                    addRoundRect(x, y, x + width, y + height, min(rx, width / 2), min(ry, height / 2), Path.Direction.CW)
                } else {
                    addRect(x, y, x + width, y + height, Path.Direction.CW)
                }
            }
        }

        private fun applyStyle(style: Style, name: String, value: String): Style = when (name) {
            "fill" -> style.copy(fill = parseColor(value))
            "fill-opacity" -> style.copy(fillOpacity = value.trim().toFloat())
            "fill-rule" -> style.copy(evenOdd = parseEvenOdd(value))
            "clip-rule" -> style.copy(clipEvenOdd = parseEvenOdd(value))
            "stroke" -> style.copy(stroke = parseColor(value))
            "stroke-opacity" -> style.copy(strokeOpacity = value.trim().toFloat())
            "stroke-width" -> style.copy(strokeWidth = parseLength(value).takeIf { it >= 0 } ?: throw Unsupported())
            "stroke-linecap" -> style.copy(cap = when (value.trim()) {
                "butt" -> Paint.Cap.BUTT
                "round" -> Paint.Cap.ROUND
                "square" -> Paint.Cap.SQUARE
                else -> throw Unsupported()
            })
            "stroke-linejoin" -> style.copy(join = when (value.trim()) {
                "miter" -> Paint.Join.MITER
                "round" -> Paint.Join.ROUND
                "bevel" -> Paint.Join.BEVEL
                else -> throw Unsupported()
            })
            "stroke-miterlimit" -> style.copy(miter = value.trim().toFloat())
            else -> throw Unsupported()
        }

        private fun parseEvenOdd(value: String) = when (value.trim()) {
            "evenodd" -> true
            "nonzero" -> false
            else -> throw Unsupported()
        }

        private inline fun forEachAttribute(parser: XmlPullParser, block: (String, String) -> Unit) {
            for (i in 0 until parser.attributeCount) {
                val name = parser.getAttributeName(i)
                if (name in IGNORED_ATTRS || name.startsWith("xmlns:")) continue
                block(name, parser.getAttributeValue(i))
            }
        }

        private fun skipElement(parser: XmlPullParser) {
            var depth = 1
            while (depth > 0) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> depth++
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.END_DOCUMENT -> throw Unsupported()
                }
            }
        }

        private fun parseLength(value: String): Float {
            val trimmed = value.trim()
            if (trimmed.endsWith("%")) return -1f
            return trimmed.removeSuffix("px").toFloat()
        }

        private fun parseNumbers(value: String) =
            value.trim().split(Regex("[\\s,]+")).map { it.toFloat() }.toFloatArray()

        private fun parseColor(value: String): Int {
            val trimmed = value.trim()
            return when {
                trimmed == "none" -> Color.TRANSPARENT
                trimmed == "black" -> Color.BLACK
                trimmed == "white" -> Color.WHITE
                !trimmed.startsWith("#") -> throw Unsupported()
                else -> {
                    val hex = trimmed.substring(1)
                    when (hex.length) {
                        3 -> Color.parseColor("#" + hex.map { "$it$it" }.joinToString(""))
                        6 -> Color.parseColor(trimmed)
                        // svg is RRGGBBAA, android is AARRGGBB
                        8 -> Color.parseColor("#" + hex.substring(6) + hex.substring(0, 6))
                        else -> throw Unsupported()
                    }
                }
            }
        }

        private fun applyOpacity(color: Int, opacity: Float): Int {
            if (color == Color.TRANSPARENT) return color
            val alpha = (Color.alpha(color) * opacity.coerceIn(0f, 1f)).roundToInt()
            return (color and 0x00FFFFFF) or (alpha shl 24)
        }
    }
}

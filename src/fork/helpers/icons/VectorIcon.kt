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
        val fillColor: Int,
        val strokeColor: Int,
        val strokeWidth: Float,
        val cap: Paint.Cap,
        val join: Paint.Join,
        val miter: Float,
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
                    else -> throw Unsupported()
                }
            }
            val box = viewBox ?: if (width > 0 && height > 0) floatArrayOf(0f, 0f, width, height) else throw Unsupported()
            val clips = HashMap<String, Path>()
            return VectorIcon(box, stretch, Group(null, parseChildren(parser, clips)))
        }

        private fun parseChildren(parser: XmlPullParser, clips: MutableMap<String, Path>): List<Any> {
            val children = ArrayList<Any>()
            while (parser.next() != XmlPullParser.END_TAG) {
                when (parser.eventType) {
                    XmlPullParser.END_DOCUMENT -> throw Unsupported()
                    XmlPullParser.TEXT -> if (!parser.isWhitespace) throw Unsupported()
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "path" -> {
                            children.add(parseShape(parser))
                            if (parser.next() != XmlPullParser.END_TAG) throw Unsupported()
                        }
                        "g" -> {
                            var clip: Path? = null
                            forEachAttribute(parser) { name, value ->
                                if (name != "clip-path") throw Unsupported()
                                clip = clips[CLIP_URL.matchEntire(value.trim())?.groupValues?.get(1)] ?: throw Unsupported()
                            }
                            children.add(Group(clip, parseChildren(parser, clips)))
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
                        for (child in parseChildren(parser, clips)) {
                            if (child !is Shape) throw Unsupported()
                            clip.addPath(child.path)
                        }
                        clips[id] = clip
                    }
                }
            }
        }

        private fun parseShape(parser: XmlPullParser): Shape {
            var path: Path? = null
            var fill = Color.BLACK
            var fillOpacity = 1f
            var evenOdd = false
            var stroke = Color.TRANSPARENT
            var strokeOpacity = 1f
            var strokeWidth = 1f
            var cap = Paint.Cap.BUTT
            var join = Paint.Join.MITER
            var miter = 4f
            forEachAttribute(parser) { name, value ->
                when (name) {
                    "d" -> path = PathParser.createPathFromPathData(value)
                    "fill" -> fill = parseColor(value)
                    "fill-opacity" -> fillOpacity = value.trim().toFloat()
                    "fill-rule" -> evenOdd = when (value.trim()) {
                        "evenodd" -> true
                        "nonzero" -> false
                        else -> throw Unsupported()
                    }
                    "stroke" -> stroke = parseColor(value)
                    "stroke-opacity" -> strokeOpacity = value.trim().toFloat()
                    "stroke-width" -> strokeWidth = parseLength(value).takeIf { it >= 0 } ?: throw Unsupported()
                    "stroke-linecap" -> cap = when (value.trim()) {
                        "butt" -> Paint.Cap.BUTT
                        "round" -> Paint.Cap.ROUND
                        "square" -> Paint.Cap.SQUARE
                        else -> throw Unsupported()
                    }
                    "stroke-linejoin" -> join = when (value.trim()) {
                        "miter" -> Paint.Join.MITER
                        "round" -> Paint.Join.ROUND
                        "bevel" -> Paint.Join.BEVEL
                        else -> throw Unsupported()
                    }
                    "stroke-miterlimit" -> miter = value.trim().toFloat()
                    else -> throw Unsupported()
                }
            }
            val shapePath = path ?: throw Unsupported()
            shapePath.fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            return Shape(shapePath, applyOpacity(fill, fillOpacity), applyOpacity(stroke, strokeOpacity), strokeWidth, cap, join, miter)
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

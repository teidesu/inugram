package desu.inugram.helpers.plugins.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Build
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.CanvasListener
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginDispatch
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.io.PluginBlobs
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import org.json.JSONObject

/**
 * The rasterizer behind `inu.canvas` (rust: `canvas.rs`), per `src/plugins/canvas.d.ts`.
 *
 * **The engine decides, this paints.** Everything with a rule in the spec is settled in rust before
 * a byte crosses; what arrives is a command buffer of move/line/cubic/close, each command carrying
 * its own transform and its own fully-described paint. So there is no state machine between
 * commands. Every draw runs under the command's own transform, in the user space the plugin was
 * drawing in: a stroke's pen, a gradient's coordinates and a pattern's tiling are all defined there.
 *
 * **A composite mode other than `source-over` draws into a `saveLayer`**, being defined against the
 * whole destination rather than the shape. Three things the platform cannot do are refused rather
 * than approximated: the separable blend modes below api 29, a non-concentric radial gradient (no
 * two-point conical shader exists), and a pattern that does not tile in both directions below api
 * 31, where `TileMode.DECAL` arrives and `CLAMP` in its place smears the edge pixel.
 *
 * The listener is a JNI upcall, so it runs on the queue the engine lives on. The three slow ops
 * (encoding a bitmap, decoding one, reading a font file) hop to [work] and come back through
 * [QuickJs.canvasResult] on globalQueue.
 */
object PluginCanvas {
    // keep in sync with rust `canvas::OP_*`
    const val OP_CREATE = 0
    const val OP_DESTROY = 1
    const val OP_REPLAY = 2
    const val OP_MEASURE = 3
    const val OP_AVERAGE = 4
    const val OP_ENCODE = 5
    const val OP_DECODE = 6
    const val OP_RELEASE_IMAGE = 7
    const val OP_LOAD_FONT = 8
    const val OP_CAPABILITIES = 9

    private const val FIELD = '\u001e'
    private const val ITEM = '\u001f'

    private const val CMD_SAVE = 0
    private const val CMD_RESTORE = 1
    private const val CMD_RESET = 2
    private const val CMD_CLIP = 3
    private const val CMD_FILL = 4
    private const val CMD_STROKE = 5
    private const val CMD_CLEAR = 6
    private const val CMD_TEXT = 7
    private const val CMD_IMAGE = 8

    private const val STYLE_COLOR = 0
    private const val STYLE_LINEAR = 1
    private const val STYLE_RADIAL = 2
    private const val STYLE_CONIC = 3
    private const val STYLE_PATTERN = 4

    private const val SOURCE_IMAGE = 0
    private const val SOURCE_CANVAS = 1

    private const val ENCODED_DIR = "canvas"

    /**
     * The platform's shadow radius is a blur-mask radius and the spec's `shadowBlur` is twice the
     * gaussian sigma, so one is not the other. `BlurMaskFilter` uses `sigma = radius * 0.57735 +
     * 0.5`, which inverts to this.
     */
    private const val BLUR_SIGMA_PER_RADIUS = 0.57735f

    private val work by lazy {
        Executors.newFixedThreadPool(2, ThreadFactory { r ->
            Thread(r, "inuPluginCanvas").apply { isDaemon = true }
        })
    }

    fun listenerFor(plugin: Plugin, engine: QuickJs): CanvasListener = Session(plugin, engine)

    /** call on globalQueue as the engine stops: every bitmap it holds is native memory */
    fun detach(engine: QuickJs) {
        (engine.listener?.canvas as? Session)?.close()
    }

    /** deletes whatever `convertToBlob` wrote for a plugin being uninstalled */
    fun wipe(installId: String) {
        val root = PluginBlobs.dirFor(installId)
        if (root.isEmpty()) return
        File(root, ENCODED_DIR).deleteRecursively()
    }

    private class Refusal(val wire: String) : RuntimeException(null, null, false, false)

    private fun refuse(code: String, message: String): Nothing =
        throw Refusal(PluginWire.encodePluginError(code, message))

    private class Session(private val plugin: Plugin, private val engine: QuickJs) : CanvasListener {
        private val canvases = HashMap<Long, Surface>()
        private val images = HashMap<Long, Bitmap>()
        private val fonts = HashMap<String, Typeface>()
        private var nextFile = 0L

        private class Surface(val bitmap: Bitmap) {
            val canvas = Canvas(bitmap)
        }

        override fun canvas(op: Int, id: Long, arg: String, bytes: ByteArray?): String = try {
            run(op, id, arg, bytes)
        } catch (e: Refusal) {
            e.wire
        } catch (e: OutOfMemoryError) {
            PluginWire.encodePluginError("quota-exceeded", "canvas: out of memory")
        } catch (e: Throwable) {
            PluginWire.encodePluginError("internal", "canvas: ${e.javaClass.simpleName}: ${e.message}")
        }

        private fun run(op: Int, id: Long, arg: String, bytes: ByteArray?): String = when (op) {
            OP_CAPABILITIES -> """J{"blend":${Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q}}"""
            OP_CREATE -> create(id, arg)
            OP_DESTROY -> destroy(id)
            OP_REPLAY -> replay(id, arg, bytes)
            OP_MEASURE -> measure(arg)
            OP_AVERAGE -> average(id, arg)
            OP_ENCODE -> encode(id, arg)
            OP_DECODE -> decode(id, arg)
            OP_RELEASE_IMAGE -> releaseImage(id)
            OP_LOAD_FONT -> loadFont(arg)
            else -> PluginWire.encodePluginError("invalid-argument", "canvas: unknown op $op")
        }

        private fun create(id: Long, arg: String): String {
            val parts = arg.split(',')
            val width = parts.getOrNull(0)?.toIntOrNull() ?: refuse("internal", "canvas: malformed size")
            val height = parts.getOrNull(1)?.toIntOrNull() ?: refuse("internal", "canvas: malformed size")
            canvases.remove(id)?.bitmap?.recycle()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0)
            canvases[id] = Surface(bitmap)
            return ""
        }

        private fun destroy(id: Long): String {
            canvases.remove(id)?.bitmap?.recycle()
            return ""
        }

        private fun surfaceOf(id: Long): Surface =
            canvases[id] ?: refuse("handle-expired", "canvas: that canvas is gone")

        private fun releaseImage(id: Long): String {
            images.remove(id)?.recycle()
            return ""
        }

        /**
         * The replay wire's string table, `<length>ITEM<content>` per entry. Length-prefixed
         * because an entry is text a plugin chose - a `fillText` argument, a css family name - so
         * there is no separator it cannot contain.
         */
        internal fun decodeTable(strings: String): List<String> {
            val out = ArrayList<String>()
            var at = 0
            while (at < strings.length) {
                val separator = strings.indexOf(ITEM, at)
                if (separator < 0) break
                val length = strings.substring(at, separator).toIntOrNull() ?: break
                val start = separator + 1
                val end = (start + length).coerceIn(start, strings.length)
                out.add(strings.substring(start, end))
                at = end
            }
            return out
        }

        private fun replay(id: Long, strings: String, bytes: ByteArray?): String {
            val surface = surfaceOf(id)
            val table = decodeTable(strings)
            val reader = Reader(bytes ?: ByteArray(0))
            while (reader.has()) {
                when (val command = reader.u8()) {
                    CMD_SAVE -> surface.canvas.save()
                    // a buffer is replayed in pieces, so a restore may belong to a save from an
                    // earlier one; `restoreToCount(1)` is the floor the reset re-establishes
                    CMD_RESTORE -> if (surface.canvas.saveCount > 1) surface.canvas.restore()
                    CMD_RESET -> {
                        surface.canvas.restoreToCount(1)
                        surface.bitmap.eraseColor(0)
                    }
                    CMD_CLIP -> clip(surface.canvas, reader)
                    CMD_FILL -> fillOrStroke(surface.canvas, reader, stroked = false)
                    CMD_STROKE -> fillOrStroke(surface.canvas, reader, stroked = true)
                    CMD_CLEAR -> clear(surface.canvas, reader)
                    CMD_TEXT -> text(surface.canvas, reader, table)
                    CMD_IMAGE -> image(surface.canvas, reader)
                    else -> refuse("internal", "canvas: unknown command $command")
                }
            }
            return ""
        }

        private fun clip(canvas: Canvas, reader: Reader) {
            val matrix = reader.matrix()
            val rule = reader.u8()
            val path = reader.path(rule)
            // no save/restore around it: a clip belongs to whichever `save()` the plugin itself
            // opened, and one wrapped in a save of ours would be discarded a line later. The matrix
            // is identity between commands (every draw restores its own), so putting it back is
            // setting it rather than unwinding it.
            canvas.concat(matrix)
            canvas.clipPath(path)
            canvas.setMatrix(null)
        }

        private fun fillOrStroke(canvas: Canvas, reader: Reader, stroked: Boolean) {
            val matrix = reader.matrix()
            val paint = reader.paint()
            val stroke = if (stroked) reader.stroke() else null
            val rule = if (stroked) 0 else reader.u8()
            val path = reader.path(rule)
            val brush = buildPaint(paint, stroke)
            drawComposited(canvas, matrix, paint.composite) { it.drawPath(path, brush) }
        }

        private fun clear(canvas: Canvas, reader: Reader) {
            val matrix = reader.matrix()
            val path = reader.path(0)
            val brush = Paint(Paint.ANTI_ALIAS_FLAG)
            brush.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            canvas.save()
            canvas.concat(matrix)
            canvas.drawPath(path, brush)
            canvas.restore()
        }

        private fun text(canvas: Canvas, reader: Reader, table: List<String>) {
            val matrix = reader.matrix()
            val stroked = reader.u8() == 1
            val paint = reader.paint()
            val stroke = if (stroked) reader.stroke() else null
            val font = table.getOrNull(reader.u32()) ?: refuse("internal", "canvas: no font in the table")
            val align = reader.u8()
            val baseline = reader.u8()
            val x = reader.f()
            val y = reader.f()
            val maxWidth = reader.f()
            val body = table.getOrNull(reader.u32()) ?: refuse("internal", "canvas: no text in the table")

            val brush = buildPaint(paint, stroke)
            applyFont(brush, font)
            brush.textAlign = when (align) {
                2, 0 -> Paint.Align.LEFT
                3, 1 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            val dy = baselineOffset(brush, baseline)
            val width = brush.measureText(body)
            drawComposited(canvas, matrix, paint.composite) {
                if (maxWidth > 0f && width > maxWidth) {
                    // the spec condenses rather than clipping, about the alignment point
                    it.save()
                    it.scale(maxWidth / width, 1f, x, y)
                    it.drawText(body, x, y + dy, brush)
                    it.restore()
                } else {
                    it.drawText(body, x, y + dy, brush)
                }
            }
        }

        private fun image(canvas: Canvas, reader: Reader) {
            val matrix = reader.matrix()
            val paint = reader.paint()
            val kind = reader.u8()
            val id = reader.i64()
            val src = RectF(reader.f(), reader.f(), 0f, 0f)
            src.right = src.left + reader.f()
            src.bottom = src.top + reader.f()
            val dst = RectF(reader.f(), reader.f(), 0f, 0f)
            dst.right = dst.left + reader.f()
            dst.bottom = dst.top + reader.f()

            val bitmap = when (kind) {
                SOURCE_IMAGE -> images[id] ?: refuse("handle-expired", "canvas: that image was disposed")
                SOURCE_CANVAS -> surfaceOf(id).bitmap
                else -> refuse("internal", "canvas: unknown image source")
            }
            // drawing a canvas onto itself reads and writes the same pixels; the copy is what makes
            // the snapshot the spec promises
            val source = if (bitmap === canvasBitmapOf(canvas)) bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false) else bitmap
            val brush = Paint(Paint.FILTER_BITMAP_FLAG)
            brush.alpha = (paint.alpha * 255f).toInt().coerceIn(0, 255)
            applyShadow(brush, paint)
            val srcRect = Rect(
                src.left.toInt().coerceIn(0, source.width),
                src.top.toInt().coerceIn(0, source.height),
                src.right.toInt().coerceIn(0, source.width),
                src.bottom.toInt().coerceIn(0, source.height),
            )
            drawComposited(canvas, matrix, paint.composite) { it.drawBitmap(source, srcRect, dst, brush) }
            if (source !== bitmap) source.recycle()
        }

        private fun canvasBitmapOf(canvas: Canvas): Bitmap? =
            canvases.values.firstOrNull { it.canvas === canvas }?.bitmap

        /**
         * The layer dance the module doc describes. `source-over` is the platform's own default and
         * needs none of it, which is the case every drawing is mostly made of.
         */
        private fun drawComposited(canvas: Canvas, matrix: Matrix, composite: Int, draw: (Canvas) -> Unit) {
            canvas.save()
            if (composite != 0) {
                val layer = Paint()
                applyBlend(layer, composite)
                canvas.saveLayer(null, layer)
                canvas.concat(matrix)
                draw(canvas)
                canvas.restore()
            } else {
                canvas.concat(matrix)
                draw(canvas)
            }
            canvas.restore()
        }

        private fun buildPaint(spec: PaintSpec, stroke: StrokeSpec?): Paint {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            when (spec.style) {
                STYLE_COLOR -> paint.color = spec.color
                else -> paint.shader = buildShader(spec)
            }
            paint.alpha = ((if (spec.style == STYLE_COLOR) (spec.color ushr 24) and 0xff else 255) * spec.alpha)
                .toInt()
                .coerceIn(0, 255)
            applyShadow(paint, spec)
            if (stroke != null) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = stroke.width
                paint.strokeCap = when (stroke.cap) {
                    1 -> Paint.Cap.ROUND
                    2 -> Paint.Cap.SQUARE
                    else -> Paint.Cap.BUTT
                }
                paint.strokeJoin = when (stroke.join) {
                    0 -> Paint.Join.ROUND
                    1 -> Paint.Join.BEVEL
                    else -> Paint.Join.MITER
                }
                paint.strokeMiter = stroke.miter
                if (stroke.dash.isNotEmpty() && stroke.dash.any { it > 0f }) {
                    paint.pathEffect = android.graphics.DashPathEffect(stroke.dash, stroke.dashOffset)
                }
            } else {
                paint.style = Paint.Style.FILL
            }
            return paint
        }

        private fun applyShadow(paint: Paint, spec: PaintSpec) {
            if (spec.shadowColor ushr 24 == 0) return
            if (spec.shadowBlur <= 0f && spec.shadowDx == 0f && spec.shadowDy == 0f) return
            val radius = (spec.shadowBlur / 2f) / BLUR_SIGMA_PER_RADIUS
            paint.setShadowLayer(radius.coerceAtLeast(0.01f), spec.shadowDx, spec.shadowDy, spec.shadowColor)
        }

        private fun applyBlend(paint: Paint, composite: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                paint.blendMode = BLEND_MODES[composite]
                return
            }
            val mode = PORTER_DUFF[composite]
                ?: refuse("unsupported", "canvas: this blend mode needs android 10 or newer")
            paint.xfermode = PorterDuffXfermode(mode)
        }

        private fun buildShader(spec: PaintSpec): Shader = when (spec.style) {
            STYLE_LINEAR -> {
                val (colors, stops) = expandStops(spec)
                LinearGradient(spec.coords[0], spec.coords[1], spec.coords[2], spec.coords[3], colors, stops, Shader.TileMode.CLAMP)
            }
            STYLE_RADIAL -> radialShader(spec)
            STYLE_CONIC -> {
                val (colors, stops) = expandStops(spec)
                val shader = SweepGradient(spec.coords[1], spec.coords[2], colors, stops)
                val rotation = Matrix()
                rotation.setRotate(Math.toDegrees(spec.coords[0].toDouble()).toFloat(), spec.coords[1], spec.coords[2])
                shader.setLocalMatrix(rotation)
                shader
            }
            else -> patternShader(spec)
        }

        /**
         * The concentric case is exact: the inner radius is folded into the stop positions, so
         * `createRadialGradient(x, y, r0, x, y, r1)` renders as the spec draws it. The focal case
         * needs a two-point conical shader, which the platform does not have at all - and is refused
         * rather than drawn as something else.
         */
        private fun radialShader(spec: PaintSpec): Shader {
            val (x0, y0, r0) = Triple(spec.coords[0], spec.coords[1], spec.coords[2])
            val (x1, y1, r1) = Triple(spec.coords[3], spec.coords[4], spec.coords[5])
            if (x0 != x1 || y0 != y1) {
                refuse("unsupported", "canvas: a radial gradient whose circles are not concentric has no equivalent here")
            }
            if (r1 <= 0f) refuse("invalid-argument", "canvas: a radial gradient needs an outer radius")
            val (colors, stops) = expandStops(spec)
            val mapped = FloatArray(stops.size) { (r0 + stops[it] * (r1 - r0)) / r1 }
            return RadialGradient(x1, y1, r1, colors, mapped, Shader.TileMode.CLAMP)
        }

        private fun patternShader(spec: PaintSpec): Shader {
            val bitmap = when (spec.patternSource) {
                SOURCE_IMAGE -> images[spec.patternId] ?: refuse("handle-expired", "canvas: that image was disposed")
                else -> surfaceOf(spec.patternId).bitmap
            }
            val decal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Shader.TileMode.DECAL
            } else if (spec.repeat == 0) {
                Shader.TileMode.CLAMP
            } else {
                refuse("unsupported", "canvas: a pattern that does not repeat in both directions needs android 12 or newer")
            }
            val tileX = if (spec.repeat == 0 || spec.repeat == 1) Shader.TileMode.REPEAT else decal
            val tileY = if (spec.repeat == 0 || spec.repeat == 2) Shader.TileMode.REPEAT else decal
            val shader = android.graphics.BitmapShader(bitmap, tileX, tileY)
            shader.setLocalMatrix(spec.patternMatrix)
            return shader
        }

        /**
         * The platform needs at least two stops and this may be handed none: a gradient with no
         * stops paints nothing, and one with a single stop paints that colour flat.
         */
        private fun expandStops(spec: PaintSpec): Pair<IntArray, FloatArray> = when (spec.stops.size) {
            0 -> Pair(intArrayOf(0, 0), floatArrayOf(0f, 1f))
            1 -> Pair(intArrayOf(spec.stops[0].second, spec.stops[0].second), floatArrayOf(0f, 1f))
            else -> Pair(
                IntArray(spec.stops.size) { spec.stops[it].second },
                FloatArray(spec.stops.size) { spec.stops[it].first },
            )
        }

        private fun applyFont(paint: Paint, wire: String) {
            val fields = wire.split(FIELD)
            paint.textSize = fields.getOrNull(0)?.toFloatOrNull() ?: 10f
            val weight = fields.getOrNull(1)?.toIntOrNull() ?: 400
            val italic = fields.getOrNull(2) == "1"
            paint.isFakeBoldText = false
            val families = fields.getOrNull(4)?.split(ITEM).orEmpty().filter { it.isNotEmpty() }
            paint.typeface = typefaceFor(families, weight, italic)
        }

        private fun typefaceFor(families: List<String>, weight: Int, italic: Boolean): Typeface {
            val base = families.firstNotNullOfOrNull { fonts[it] }
                ?: families.firstNotNullOfOrNull { family ->
                    // `Typeface.create` never fails, so a family the device does not have comes back
                    // as the default and there is nothing to tell it from a real match; taking the
                    // first name that is not the default is the only way to honour the list
                    val candidate = Typeface.create(family, Typeface.NORMAL)
                    candidate.takeIf { it != Typeface.DEFAULT }
                }
                ?: Typeface.DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return Typeface.create(base, weight.coerceIn(1, 1000), italic)
            }
            val style = when {
                weight >= 600 && italic -> Typeface.BOLD_ITALIC
                weight >= 600 -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            return Typeface.create(base, style)
        }

        private fun baselineOffset(paint: Paint, baseline: Int): Float {
            val metrics = paint.fontMetrics
            return when (baseline) {
                0 -> -metrics.top
                1 -> -metrics.ascent * 0.8f
                2 -> -(metrics.ascent + metrics.descent) / 2f
                4, 5 -> -metrics.bottom
                else -> 0f
            }
        }

        private fun measure(arg: String): String {
            val fields = arg.split(FIELD)
            // the font wire is five fields of its own, then the alignment, then the text - which may
            // contain anything, including a field separator, so it is taken as the tail
            val font = fields.take(5).joinToString(FIELD.toString())
            val align = fields.getOrNull(5)?.toIntOrNull() ?: 0
            val text = fields.drop(6).joinToString(FIELD.toString())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            applyFont(paint, font)
            val width = paint.measureText(text)
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            val anchor = when (align) {
                3, 1 -> width
                4 -> width / 2f
                else -> 0f
            }
            val metrics = paint.fontMetrics
            return "J" + JSONObject()
                .put("width", width.toDouble())
                .put("actualBoundingBoxLeft", (anchor - bounds.left).toDouble())
                .put("actualBoundingBoxRight", (bounds.right - anchor).toDouble())
                .put("actualBoundingBoxAscent", (-bounds.top).toDouble())
                .put("actualBoundingBoxDescent", bounds.bottom.toDouble())
                .put("fontBoundingBoxAscent", (-metrics.ascent).toDouble())
                .put("fontBoundingBoxDescent", metrics.descent.toDouble())
                .toString()
        }

        private fun average(id: Long, arg: String): String {
            val surface = surfaceOf(id)
            val parts = arg.split(',').map { it.toFloatOrNull() ?: 0f }
            if (parts.size < 4) refuse("internal", "canvas: malformed region")
            var left = parts[0]
            var top = parts[1]
            var right = parts[0] + parts[2]
            var bottom = parts[1] + parts[3]
            if (right < left) { val t = left; left = right; right = t }
            if (bottom < top) { val t = top; top = bottom; bottom = t }
            val x = left.toInt().coerceIn(0, surface.bitmap.width)
            val y = top.toInt().coerceIn(0, surface.bitmap.height)
            val w = right.toInt().coerceIn(0, surface.bitmap.width) - x
            val h = bottom.toInt().coerceIn(0, surface.bitmap.height) - y
            if (w <= 0 || h <= 0) {
                refuse("invalid-argument", "canvas: that region is empty once clipped to the canvas")
            }
            val pixels = IntArray(w * h)
            surface.bitmap.getPixels(pixels, 0, w, x, y, w, h)
            var alpha = 0L
            var red = 0L
            var green = 0L
            var blue = 0L
            for (pixel in pixels) {
                val a = (pixel ushr 24) and 0xff
                alpha += a
                // alpha-weighted, so a transparent area does not drag the colour toward black
                red += ((pixel ushr 16) and 0xff).toLong() * a
                green += ((pixel ushr 8) and 0xff).toLong() * a
                blue += (pixel and 0xff).toLong() * a
            }
            val json = JSONObject()
            if (alpha == 0L) {
                json.put("r", 0).put("g", 0).put("b", 0).put("a", 0)
            } else {
                json.put("r", (red / alpha).toInt())
                    .put("g", (green / alpha).toInt())
                    .put("b", (blue / alpha).toInt())
                    .put("a", (alpha / pixels.size).toInt())
            }
            return "J$json"
        }

        private fun encode(id: Long, arg: String): String {
            val surface = surfaceOf(id)
            val fields = arg.split(FIELD)
            val requestId = fields.getOrNull(0)?.toLongOrNull() ?: refuse("internal", "canvas: malformed request")
            val mime = fields.getOrNull(1).orEmpty()
            val quality = fields.getOrNull(2)?.toFloatOrNull() ?: 0.92f
            val dir = encodedDir() ?: refuse("internal", "canvas: there is nowhere to write the result")
            val format = when (mime) {
                "image/jpeg" -> Bitmap.CompressFormat.JPEG
                "image/webp" -> webpFormat()
                else -> Bitmap.CompressFormat.PNG
            }
            // a copy, because the plugin keeps drawing on the original while this runs
            val snapshot = surface.bitmap.copy(surface.bitmap.config ?: Bitmap.Config.ARGB_8888, false)
            val file = File(dir, "out-${++nextFile}.bin")
            submit(requestId) {
                try {
                    file.outputStream().use { out ->
                        if (!snapshot.compress(format, (quality * 100f).toInt().coerceIn(0, 100), out)) {
                            throw IllegalStateException("the encoder refused this bitmap")
                        }
                    }
                    "J" + JSONObject().put("path", file.absolutePath).put("type", mime).toString()
                } finally {
                    snapshot.recycle()
                }
            }
            return ""
        }

        @Suppress("DEPRECATION")
        private fun webpFormat(): Bitmap.CompressFormat =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }

        private fun decode(id: Long, arg: String): String {
            val fields = arg.split(FIELD)
            val requestId = fields.getOrNull(0)?.toLongOrNull() ?: refuse("internal", "canvas: malformed request")
            val path = fields.drop(1).joinToString(FIELD.toString())
            submitBitmap(requestId, id) {
                BitmapFactory.decodeFile(path)
                    ?: throw IllegalArgumentException("this is not an image the device can decode")
            }
            return ""
        }

        private fun loadFont(arg: String): String {
            val fields = arg.split(FIELD)
            val requestId = fields.getOrNull(0)?.toLongOrNull() ?: refuse("internal", "canvas: malformed request")
            val family = fields.getOrNull(1).orEmpty()
            val path = fields.drop(2).joinToString(FIELD.toString())
            work.execute {
                val loaded = runCatching { Typeface.createFromFile(path) }
                PluginDispatch.onEngine(plugin, engine) {
                    val wire = loaded.fold(
                        onSuccess = { fonts[family] = it; "" },
                        onFailure = { PluginWire.encodePluginError("invalid-argument", "canvas: this is not a font file") },
                    )
                    engine.canvasResult(requestId, wire)
                }
            }
            return ""
        }

        /** the decode half of [submit]: the bitmap has to land in this session's table, not in js */
        private fun submitBitmap(requestId: Long, imageId: Long, produce: () -> Bitmap) {
            work.execute {
                val result = runCatching(produce)
                val bitmap = result.getOrNull()
                PluginDispatch.onEngine(plugin, engine, onDropped = { bitmap?.recycle() }) {
                    if (bitmap == null) {
                        val message = result.exceptionOrNull()?.message ?: "the decode failed"
                        engine.canvasResult(requestId, PluginWire.encodePluginError("invalid-argument", "canvas: $message"))
                        return@onEngine
                    }
                    images[imageId] = bitmap
                    engine.canvasResult(
                        requestId,
                        "J" + JSONObject().put("width", bitmap.width).put("height", bitmap.height).toString(),
                    )
                }
            }
        }

        private fun submit(requestId: Long, produce: () -> String) {
            work.execute {
                val wire = try {
                    produce()
                } catch (e: OutOfMemoryError) {
                    PluginWire.encodePluginError("quota-exceeded", "canvas: out of memory")
                } catch (e: Throwable) {
                    PluginWire.encodePluginError("internal", "canvas: ${e.message ?: e.toString()}")
                }
                PluginDispatch.onEngine(plugin, engine) { engine.canvasResult(requestId, wire) }
            }
        }

        private fun encodedDir(): File? {
            val root = PluginBlobs.dirFor(plugin.id)
            if (root.isEmpty()) return null
            val dir = File(root, ENCODED_DIR)
            if (!dir.isDirectory && !dir.mkdirs()) return null
            return dir
        }

        fun close() {
            for (surface in canvases.values) surface.bitmap.recycle()
            canvases.clear()
            for (image in images.values) image.recycle()
            images.clear()
            fonts.clear()
        }
    }

    private class PaintSpec {
        var alpha = 1f
        var composite = 0
        var shadowBlur = 0f
        var shadowDx = 0f
        var shadowDy = 0f
        var shadowColor = 0
        var style = STYLE_COLOR
        var color = 0
        var coords = FloatArray(6)
        var stops: List<Pair<Float, Int>> = emptyList()
        var patternSource = 0
        var patternId = 0L
        var repeat = 0
        var patternMatrix: Matrix = Matrix()
    }

    private class StrokeSpec(
        val width: Float,
        val cap: Int,
        val join: Int,
        val miter: Float,
        val dashOffset: Float,
        val dash: FloatArray,
    )

    private class Reader(bytes: ByteArray) {
        private val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        fun has(): Boolean = buffer.hasRemaining()
        fun u8(): Int = buffer.get().toInt() and 0xff
        fun i32(): Int = buffer.int
        fun u32(): Int = buffer.int
        fun i64(): Long = buffer.long
        fun f(): Float = buffer.float

        fun matrix(): Matrix {
            val a = f(); val b = f(); val c = f(); val d = f(); val e = f(); val g = f()
            val matrix = Matrix()
            matrix.setValues(floatArrayOf(a, c, e, b, d, g, 0f, 0f, 1f))
            return matrix
        }

        fun paint(): PaintSpec {
            val spec = PaintSpec()
            spec.alpha = f()
            spec.composite = u8()
            spec.shadowBlur = f()
            spec.shadowDx = f()
            spec.shadowDy = f()
            spec.shadowColor = i32()
            spec.style = u8()
            when (spec.style) {
                STYLE_COLOR -> spec.color = i32()
                STYLE_PATTERN -> {
                    spec.patternSource = u8()
                    spec.patternId = i64()
                    spec.repeat = u8()
                    spec.patternMatrix = matrix()
                }
                else -> {
                    val count = when (spec.style) {
                        STYLE_LINEAR -> 4
                        STYLE_RADIAL -> 6
                        else -> 3
                    }
                    for (i in 0 until count) spec.coords[i] = f()
                    val n = u32()
                    val stops = ArrayList<Pair<Float, Int>>(n)
                    for (i in 0 until n) stops.add(Pair(f(), i32()))
                    spec.stops = stops
                }
            }
            return spec
        }

        fun stroke(): StrokeSpec {
            val width = f()
            val cap = u8()
            val join = u8()
            val miter = f()
            val dashOffset = f()
            val n = u32()
            val dash = FloatArray(n)
            for (i in 0 until n) dash[i] = f()
            return StrokeSpec(width, cap, join, miter, dashOffset, dash)
        }

        fun path(rule: Int): Path {
            val path = Path()
            path.fillType = if (rule == 1) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            val n = u32()
            for (i in 0 until n) {
                when (u8()) {
                    0 -> path.moveTo(f(), f())
                    1 -> path.lineTo(f(), f())
                    2 -> path.cubicTo(f(), f(), f(), f(), f(), f())
                    else -> path.close()
                }
            }
            return path
        }
    }

    /** indexed by the engine's composite code, which is `GlobalCompositeOperation`'s own order */
    private val BLEND_MODES: Array<BlendMode> by lazy {
        arrayOf(
            BlendMode.SRC_OVER, BlendMode.SRC_IN, BlendMode.SRC_OUT, BlendMode.SRC_ATOP,
            BlendMode.DST_OVER, BlendMode.DST_IN, BlendMode.DST_OUT, BlendMode.DST_ATOP,
            BlendMode.PLUS, BlendMode.SRC, BlendMode.XOR,
            BlendMode.MULTIPLY, BlendMode.SCREEN, BlendMode.OVERLAY, BlendMode.DARKEN, BlendMode.LIGHTEN,
            BlendMode.COLOR_DODGE, BlendMode.COLOR_BURN, BlendMode.HARD_LIGHT, BlendMode.SOFT_LIGHT,
            BlendMode.DIFFERENCE, BlendMode.EXCLUSION,
            BlendMode.HUE, BlendMode.SATURATION, BlendMode.COLOR, BlendMode.LUMINOSITY,
        )
    }

    /** the porter-duff set only; the separable blend modes have no equivalent below api 29 */
    private val PORTER_DUFF: Array<PorterDuff.Mode?> = arrayOf(
        PorterDuff.Mode.SRC_OVER, PorterDuff.Mode.SRC_IN, PorterDuff.Mode.SRC_OUT, PorterDuff.Mode.SRC_ATOP,
        PorterDuff.Mode.DST_OVER, PorterDuff.Mode.DST_IN, PorterDuff.Mode.DST_OUT, PorterDuff.Mode.DST_ATOP,
        PorterDuff.Mode.ADD, PorterDuff.Mode.SRC, PorterDuff.Mode.XOR,
    )
}

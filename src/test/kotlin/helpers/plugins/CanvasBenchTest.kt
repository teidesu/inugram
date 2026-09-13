package desu.inugram.helpers.plugins

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import desu.inugram.helpers.plugins.ui.PluginCanvas
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Not a test of behaviour: a benchmark of what a canvas costs a plugin, on the real rasterizer, so
 * the split between the crossing, the platform's paint and the encoder is measured rather than
 * guessed at. It prints to logcat under `InuBench` and asserts only that the work happened.
 *
 * The js half is timed with `performance.now()` from inside the engine, so a number covers the JNI
 * crossing and the kotlin side the way a plugin sees it; the kotlin half runs the same platform
 * calls with no engine at all, so a slow op says which side it is.
 */
class CanvasBenchTest {
    private val engines = ArrayList<QuickJs>()
    private val text = "Когда ты понял что это всё"

    @Before
    fun setUp() {
        resetBridge()
    }

    @After
    fun tearDown() {
        for (engine in engines) {
            engine.stopCallbacks()
            PluginCanvas.detach(engine)
            engine.close()
        }
        engines.clear()
    }

    private fun engineFor(): Plugin =
        canvasEngine("canvas-bench") { Log.d("InuBench", it) }.also { engines.add(it.engine!!) }

    private fun us(timings: JSONObject, key: String, count: Int) =
        "%.1fus".format(timings.getDouble(key) / count * 1000)

    /**
     * every op a text-heavy render makes, one at a time. `measureText` is the one a wrap loop calls
     * per candidate line, so its per-call cost is what a paragraph multiplies.
     */
    @Test
    fun bench_canvas_crossings() {
        val plugin = engineFor()
        val count = 200
        val timings = JSONObject(
            plugin.js(
                """
                (() => {
                  const t = {}
                  const text = ${JSONObject.quote(text)}
                  for (const round of [0, 1, 2]) {
                    let start = performance.now()
                    for (let i = 0; i < $count; i++) { const c = inu.canvas.create(64, 64); c.dispose() }
                    t.createDispose = performance.now() - start

                    const canvas = inu.canvas.create(640, 460)
                    const ctx = canvas.getContext('2d')

                    start = performance.now()
                    for (let i = 0; i < $count; i++) ctx.font = (10 + (i % 40)) + 'px serif'
                    t.fontSetter = performance.now() - start

                    ctx.font = '48px serif'
                    start = performance.now()
                    for (let i = 0; i < $count; i++) ctx.measureText(text)
                    t.measureText = performance.now() - start

                    start = performance.now()
                    for (let i = 0; i < $count; i++) ctx.measureText(text + i)
                    t.measureTextUncached = performance.now() - start

                    start = performance.now()
                    for (let i = 0; i < $count; i++) ctx.fillText(text, 10, 10 + (i % 400))
                    t.fillTextRecord = performance.now() - start
                    start = performance.now()
                    ctx.getAverageColor(0, 0, 1, 1)
                    t.fillTextReplay = performance.now() - start

                    start = performance.now()
                    for (let i = 0; i < $count; i++) ctx.fillRect(i % 600, 10, 20, 20)
                    t.fillRectRecord = performance.now() - start
                    start = performance.now()
                    ctx.getAverageColor(0, 0, 1, 1)
                    t.fillRectReplay = performance.now() - start

                    start = performance.now()
                    for (let i = 0; i < 20; i++) canvas.height = 400 + i
                    t.resize = performance.now() - start

                    canvas.dispose()
                  }
                  return JSON.stringify(t)
                })()
                """,
            ),
        )
        Log.i(
            "InuBench",
            "canvas crossings, $count each:" +
                " create+dispose=${us(timings, "createDispose", count)}" +
                " fontSetter=${us(timings, "fontSetter", count)}" +
                " measureText=${us(timings, "measureText", count)}" +
                " measureTextNewString=${us(timings, "measureTextUncached", count)}" +
                " fillTextRecord=${us(timings, "fillTextRecord", count)}" +
                " fillTextReplay=${"%.2f".format(timings.getDouble("fillTextReplay"))}ms" +
                " fillRectRecord=${us(timings, "fillRectRecord", count)}" +
                " fillRectReplay=${"%.2f".format(timings.getDouble("fillRectReplay"))}ms" +
                " resize=${us(timings, "resize", 20)}",
        )
        assertTrue(timings.getDouble("measureText") > 0.0)
    }

    /** the shape of one demotivator: measure a paragraph, fill, draw, encode */
    @Test
    fun bench_render_pipeline() {
        val plugin = engineFor()
        plugin.js("globalThis.photo = new Uint8Array([${jpegBytes().joinToString(",")}])")
        plugin.await(
            """
            (async () => {
              globalThis.t = {}
              const text = ${JSONObject.quote(text)}
              for (const round of [0, 1, 2]) {
                let start = performance.now()
                using image = await inu.canvas.decode(photo)
                t.decode = performance.now() - start

                start = performance.now()
                using canvas = inu.canvas.create(640, 1)
                const ctx = canvas.getContext('2d')
                ctx.font = '48px serif'
                let lines = 0
                for (let i = 0; i < 8; i++) { ctx.measureText(text + ' ' + i); lines++ }
                t.measurePass = performance.now() - start

                start = performance.now()
                canvas.height = 460
                ctx.fillStyle = '#000000'
                ctx.fillRect(0, 0, 640, 460)
                ctx.fillStyle = '#ffffff'
                ctx.fillRect(20, 20, 600, 340)
                ctx.drawImage(image, 25, 25, 590, 330)
                ctx.textAlign = 'center'
                ctx.textBaseline = 'alphabetic'
                for (let i = 0; i < 3; i++) ctx.fillText(text, 320, 390 + i * 40)
                t.drawPass = performance.now() - start

                start = performance.now()
                using png = await canvas.convertToBlob({ type: 'image/png' })
                t.encodePng = performance.now() - start
                t.pngBytes = png.size

                start = performance.now()
                using jpeg = await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.9 })
                t.encodeJpeg = performance.now() - start
                t.jpegBytes = jpeg.size
                t.lines = lines
              }
              globalThis.done = true
            })()
            """,
        )
        val timings = JSONObject(plugin.js("JSON.stringify(t)"))
        Log.i(
            "InuBench",
            "render pipeline, 640x460:" +
                " decode=${"%.2f".format(timings.getDouble("decode"))}ms" +
                " measure8Lines=${"%.2f".format(timings.getDouble("measurePass"))}ms" +
                " draw=${"%.2f".format(timings.getDouble("drawPass"))}ms" +
                " encodePng=${"%.2f".format(timings.getDouble("encodePng"))}ms (${timings.getInt("pngBytes")}b)" +
                " encodeJpeg=${"%.2f".format(timings.getDouble("encodeJpeg"))}ms (${timings.getInt("jpegBytes")}b)",
        )
        assertTrue(timings.getInt("pngBytes") > 0)
    }

    /**
     * the shape of one animated demotivator: a 720x720 source at 12fps, decoded and drawn into a
     * 616x800 frame that is then encoded. Three variants say where the time goes: decoding at the
     * target size against decoding whole and drawing down, and awaiting each frame against
     * letting the encoder queue run ahead. The kotlin side of each is dumped under `InuCanvasStats`.
     */
    @Test
    fun bench_animation_pipeline() {
        val plugin = engineFor()
        plugin.await(
            """
            (async () => {
              globalThis.t = {}
              const frames = 120
              const sourceSide = 720
              // the source: a square moving across a filled background, at the size a gif might be
              let start = performance.now()
              {
                using canvas = inu.canvas.create(sourceSide, sourceSide)
                const ctx = canvas.getContext('2d')
                using encoder = await inu.canvas.createEncoder({ width: sourceSide, height: sourceSide, fps: 12 })
                for (let i = 0; i < frames; i++) {
                  ctx.fillStyle = `hsl(${'$'}{i * 3}, 60%, 40%)`
                  ctx.fillRect(0, 0, sourceSide, sourceSide)
                  ctx.fillStyle = '#ffffff'
                  ctx.fillRect((i * 5) % sourceSide, 200, 120, 120)
                  await encoder.addFrame(canvas)
                }
                globalThis.source = await encoder.finish()
              }
              t.makeSource = performance.now() - start
              t.sourceBytes = source.size

              const side = 360
              const border = 20
              const width = 616
              const height = 800
              const run = async (label, decodeSize, awaitEach) => {
                const r = { frames: 0, decode: 0, draw: 0, encode: 0 }
                const total = performance.now()
                using animation = await inu.canvas.decodeAnimation(source, decodeSize)
                using canvas = inu.canvas.create(width, height)
                const ctx = canvas.getContext('2d')
                ctx.fillStyle = '#000000'
                ctx.fillRect(0, 0, width, height)
                ctx.fillStyle = '#ffffff'
                ctx.fillRect(border, border, side + 10, side + 10)
                ctx.font = '48px serif'
                ctx.textAlign = 'center'
                ctx.fillText(${JSONObject.quote(text)}, width / 2, height - 60)
                using encoder = await inu.canvas.createEncoder({ width, height, fps: 12 })
                const pending = []
                let s = performance.now()
                for await (using frame of animation) {
                  r.decode += performance.now() - s
                  s = performance.now()
                  ctx.drawImage(frame, border + 5, border + 5, side, side)
                  r.draw += performance.now() - s
                  s = performance.now()
                  const p = encoder.addFrame(canvas)
                  if (awaitEach) await p; else pending.push(p)
                  r.encode += performance.now() - s
                  r.frames++
                  s = performance.now()
                }
                s = performance.now()
                await Promise.all(pending)
                using out = await encoder.finish()
                r.finish = performance.now() - s
                r.bytes = out.size
                r.total = performance.now() - total
                t[label] = r
              }
              await run('warm', { width: side, height: side }, true)
              await run('scaledAwait', { width: side, height: side }, true)
              await run('wholeAwait', undefined, true)
              await run('scaledQueued', { width: side, height: side }, false)
            })()
            """,
            timeoutMillis = 180_000,
            // the harness drains the plugin queue from here, so a slow poll is a hop the app never has
            pollMillis = 1,
        )
        val t = JSONObject(plugin.js("JSON.stringify(t)"))
        Log.i("InuBench", "animation source: ${"%.0f".format(t.getDouble("makeSource"))}ms for 120 frames of 720x720 (${t.getInt("sourceBytes")}b)")
        for (label in listOf("scaledAwait", "wholeAwait", "scaledQueued")) {
            val r = t.getJSONObject(label)
            val n = r.getInt("frames").coerceAtLeast(1)
            Log.i(
                "InuBench",
                "animation $label: ${r.getInt("frames")} frames total=${"%.0f".format(r.getDouble("total"))}ms" +
                    " perFrame=${"%.2f".format(r.getDouble("total") / n)}ms" +
                    " decodeAwait=${"%.2f".format(r.getDouble("decode") / n)}ms" +
                    " draw=${"%.2f".format(r.getDouble("draw") / n)}ms" +
                    " encodeAwait=${"%.2f".format(r.getDouble("encode") / n)}ms" +
                    " finish=${"%.0f".format(r.getDouble("finish"))}ms (${r.getInt("bytes")}b)",
            )
            assertTrue(r.getInt("frames") > 100, "$label read ${r.getInt("frames")} frames")
        }
    }

    /** the same platform calls with no engine: whatever is left over is what the bridge costs */
    @Test
    fun bench_platform_floor() {
        val bitmap = Bitmap.createBitmap(640, 460, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 48f }
        val bounds = Rect()
        val count = 200

        fun rounds(round: () -> Unit): Double = (1..3).map {
            val start = System.nanoTime()
            repeat(count) { round() }
            (System.nanoTime() - start) / 1_000_000.0
        }.last()

        val measure = rounds { paint.measureText(text) }
        val measureAndBounds = rounds {
            paint.measureText(text)
            paint.getTextBounds(text, 0, text.length, bounds)
        }
        val freshPaint = rounds {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.textSize = 48f
            p.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 400, false)
            p.measureText(text)
            p.getTextBounds(text, 0, text.length, bounds)
        }
        val json = rounds {
            JSONObject().put("width", 1.0).put("actualBoundingBoxLeft", 1.0)
                .put("actualBoundingBoxRight", 1.0).put("actualBoundingBoxAscent", 1.0)
                .put("actualBoundingBoxDescent", 1.0).put("fontBoundingBoxAscent", 1.0)
                .put("fontBoundingBoxDescent", 1.0).toString()
        }
        val draw = rounds { canvas.drawText(text, 10f, 100f, paint) }
        val bitmaps = rounds {
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { it.eraseColor(0); it.recycle() }
        }

        canvas.drawColor(android.graphics.Color.rgb(40, 40, 40))
        val png = (1..3).map {
            val start = System.nanoTime()
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            Pair((System.nanoTime() - start) / 1_000_000.0, out.size())
        }.last()
        val copy = (1..3).map {
            val start = System.nanoTime()
            bitmap.copy(Bitmap.Config.ARGB_8888, false).recycle()
            (System.nanoTime() - start) / 1_000_000.0
        }.last()

        Log.i(
            "InuBench",
            "platform floor, $count each:" +
                " measureText=${"%.1f".format(measure / count * 1000)}us" +
                " +getTextBounds=${"%.1f".format(measureAndBounds / count * 1000)}us" +
                " +freshPaintAndTypeface=${"%.1f".format(freshPaint / count * 1000)}us" +
                " jsonObject=${"%.1f".format(json / count * 1000)}us" +
                " drawText=${"%.1f".format(draw / count * 1000)}us" +
                " createBitmap64=${"%.1f".format(bitmaps / count * 1000)}us" +
                " | 640x460 pngCompress=${"%.2f".format(png.first)}ms (${png.second}b)" +
                " bitmapCopy=${"%.2f".format(copy)}ms",
        )
        bitmap.recycle()
        assertTrue(png.second > 0)
    }

    /**
     * the kotlin half of a `measureText` on its own, with no engine and no crossing: what is left
     * between this and the js number is the bridge.
     */
    @Test
    fun bench_host_listener() {
        val plugin = engineFor()
        val listener = PluginCanvas.listenerFor(plugin.session!!)
        val field = '\u001e'
        val arg = "48.0${field}400${field}0${field}0${field}serif${field}0${field}$text"
        val count = 200

        fun rounds(round: () -> Unit): Double = (1..3).map {
            val start = System.nanoTime()
            repeat(count) { round() }
            (System.nanoTime() - start) / 1_000_000.0
        }.last()

        val measure = rounds { listener.canvas(PluginCanvas.OP_MEASURE, 0, arg, null) }
        val split = rounds {
            val fields = arg.split(field)
            fields.take(5).joinToString(field.toString())
            fields.drop(6).joinToString(field.toString())
        }
        val library = rounds { desu.inugram.helpers.font.FontLibrary.getTypefaceByName("serif", 400, false) }
        val byName = rounds { android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL) }
        val styled = rounds {
            android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 400, false)
        }
        var id = 0L
        val create = rounds {
            id++
            listener.canvas(PluginCanvas.OP_CREATE, id, "64,64", null)
            listener.canvas(PluginCanvas.OP_DESTROY, id, "", null)
        }
        Log.i(
            "InuBench",
            "host listener, $count each:" +
                " measure=${"%.1f".format(measure / count * 1000)}us" +
                " argSplit=${"%.1f".format(split / count * 1000)}us" +
                " fontLibraryByName=${"%.1f".format(library / count * 1000)}us" +
                " typefaceCreateByName=${"%.1f".format(byName / count * 1000)}us" +
                " typefaceCreateStyled=${"%.1f".format(styled / count * 1000)}us" +
                " create+destroy=${"%.1f".format(create / count * 1000)}us",
        )
        assertTrue(measure > 0.0)
    }

    /** a small jpeg to decode, built here so the bench carries no asset */
    private fun jpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(android.graphics.Color.rgb(30, 120, 200))
            drawCircle(256f, 192f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.YELLOW })
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}

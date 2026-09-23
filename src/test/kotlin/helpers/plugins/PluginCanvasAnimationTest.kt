package desu.inugram.helpers.plugins

import android.util.Log
import desu.inugram.helpers.plugins.io.PluginBlobs
import desu.inugram.helpers.plugins.telegram.PluginMedia
import desu.inugram.helpers.plugins.ui.NativePixels
import java.io.File
import java.nio.ByteBuffer
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.TLRPC
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginCanvasAnimationTest {
    @Before
    fun setUp() {
        resetBridge()
    }

    private fun engineFor(): Plugin =
        startEngine("canvas-animation", canvas = true) { Log.d(TAG, it) }

    private fun encodedFiles(plugin: Plugin): List<File> =
        File(PluginBlobs.dirFor(plugin.id), "canvas").listFiles()?.filter { it.name.endsWith(".mp4") } ?: emptyList()

    /** rlottie reads plain json as well as gzipped `.tgs` */
    private fun lottieJson(frames: Int, fps: Int): String =
        """
        {"v":"5.5.7","fr":$fps,"ip":0,"op":$frames,"w":64,"h":64,"nm":"t","ddd":0,"assets":[],"layers":[
          {"ddd":0,"ind":1,"ty":4,"nm":"s","sr":1,"ks":{"o":{"a":0,"k":100},"r":{"a":0,"k":0},
           "p":{"a":1,"k":[
             {"t":0,"s":[0,32],"e":[64,32],"i":{"x":[1],"y":[1]},"o":{"x":[0],"y":[0]}},
             {"t":$frames,"s":[64,32]}]},
           "a":{"a":0,"k":[0,0]},"s":{"a":0,"k":[100,100]}},
           "shapes":[{"ty":"gr","it":[
             {"ty":"rc","d":1,"s":{"a":0,"k":[40,40]},"p":{"a":0,"k":[0,0]},"r":{"a":0,"k":0}},
             {"ty":"fl","c":{"a":0,"k":[1,0.4,0.2,1]},"o":{"a":0,"k":100}},
             {"ty":"tr","p":{"a":0,"k":[0,0]},"a":{"a":0,"k":[0,0]},"s":{"a":0,"k":[100,100]},
              "r":{"a":0,"k":0},"o":{"a":0,"k":100}}]}],
           "ip":0,"op":$frames,"st":0}]}
        """.trimIndent()

    @Test
    fun an_encoded_mp4_reads_back_as_the_animation_it_was_written_from() {
        val plugin = engineFor()
        val t = JSONObject(plugin.await(
            """
            (async () => {
              const t = {}
              using canvas = inu.canvas.create(160, 120)
              const ctx = canvas.getContext('2d')
              using encoder = await inu.canvas.createEncoder({ type: 'video/mp4', width: 160, height: 120, fps: 10 })
              const start = performance.now()
              for (let i = 0; i < 10; i++) {
                ctx.fillStyle = '#000000'
                ctx.fillRect(0, 0, 160, 120)
                ctx.fillStyle = '#ffffff'
                ctx.fillRect(i * 16, 20, 16, 80)
                await encoder.addFrame(canvas, 100)
              }
              t.encodeMillis = Math.round(performance.now() - start)
              using mp4 = await encoder.finish()
              t.bytes = mp4.size
              t.type = mp4.type

              using animation = await inu.canvas.decodeAnimation(mp4)
              t.width = animation.width
              t.height = animation.height
              t.frameCount = animation.frameCount
              t.duration = animation.duration
              t.fps = animation.fps
              let read = 0
              let painted = 0
              let ordered = true
              let last = -1
              using into = inu.canvas.create(160, 120)
              const inner = into.getContext('2d')
              for await (using frame of animation) {
                if (frame.width === animation.width && frame.height === animation.height) read++
                if (frame.timestamp <= last) ordered = false
                last = frame.timestamp
                inner.drawImage(frame, 0, 0)
                if (inner.getAverageColor().a > 0) painted++
              }
              t.read = read
              t.painted = painted
              t.ordered = ordered
              t.last = last
              using third = await animation.frame(2)
              t.thirdAt = third.timestamp
              return t
            })()
            """,
        ))

        assertEquals("video/mp4", t.getString("type"))
        assertTrue(t.getInt("bytes") > 0, "the encoder wrote nothing")
        assertEquals(160, t.getInt("width"))
        assertEquals(120, t.getInt("height"))
        assertTrue(t.getInt("frameCount") > 1, "a one-second video read back as ${t.getInt("frameCount")} frames")
        assertTrue(t.getInt("read") in 8..12, "ten written frames read back as ${t.getInt("read")}")
        assertEquals(t.getInt("read"), t.getInt("painted"), "a frame came back empty")
        assertTrue(t.getBoolean("ordered"), "timestamps did not climb")
        assertTrue(t.getInt("last") in 700..1000, "the last frame sits at ${t.getInt("last")}ms of a one-second video")
        assertTrue(t.getInt("thirdAt") in 150..250, "the third frame sits at ${t.getInt("thirdAt")}ms at 10fps")
        assertTrue(t.getInt("duration") in 500..1500, "a one-second video read back as ${t.getInt("duration")}ms")
    }

    @Test
    fun a_video_is_decoded_at_the_size_it_was_asked_for() {
        val plugin = engineFor()
        val t = JSONObject(plugin.await(
            """
            (async () => {
              const t = {}
              using canvas = inu.canvas.create(160, 128)
              const ctx = canvas.getContext('2d')
              using encoder = await inu.canvas.createEncoder({ width: 160, height: 128, fps: 10 })
              for (let i = 0; i < 4; i++) {
                ctx.fillStyle = '#ffffff'
                ctx.fillRect(0, 0, 160, 128)
                await encoder.addFrame(canvas)
              }
              using mp4 = await encoder.finish()
              using animation = await inu.canvas.decodeAnimation(mp4, { width: 40, height: 40 })
              t.width = animation.width
              t.height = animation.height
              let frames = 0
              let sized = 0
              let lit = 0
              using into = inu.canvas.create(40, 40)
              const inner = into.getContext('2d')
              for await (using frame of animation) {
                frames++
                if (frame.width === 40 && frame.height === 40) sized++
                inner.drawImage(frame, 0, 0)
                if (inner.getAverageColor().r > 200) lit++
              }
              t.frames = frames
              t.sized = sized
              t.lit = lit
              return t
            })()
            """,
        ))
        assertEquals(40, t.getInt("width"))
        assertEquals(40, t.getInt("height"))
        assertTrue(t.getInt("frames") > 0, "nothing was read")
        assertEquals(t.getInt("frames"), t.getInt("sized"), "a frame came back at the source size")
        assertEquals(t.getInt("frames"), t.getInt("lit"), "a scaled frame lost its content")
    }

    @Test
    fun the_platform_reads_the_written_file_as_a_silent_video() {
        val plugin = engineFor()
        plugin.await(
            """
            (async () => {
              using canvas = inu.canvas.create(120, 80)
              const ctx = canvas.getContext('2d')
              using encoder = await inu.canvas.createEncoder({ width: 120, height: 80, fps: 5 })
              for (let i = 0; i < 5; i++) {
                ctx.fillStyle = i % 2 === 0 ? '#ff0000' : '#0000ff'
                ctx.fillRect(0, 0, 120, 80)
                await encoder.addFrame(canvas, i === 4 ? 1000 : 100)
              }
              globalThis.kept = await encoder.finish()
            })()
            """,
        )
        val written = encodedFiles(plugin)
        assertEquals(1, written.size, "expected one written mp4, found ${written.map { it.name }}")

        val described = PluginMedia.describeLocalDocument(written[0], "video/mp4", asDocument = false)
        val attributes = described.attributes
        val video = assertNotNull(
            attributes.filterIsInstance<TLRPC.TL_documentAttributeVideo>().singleOrNull(),
            "nothing described the file as a video",
        )
        assertEquals(120, video.w)
        assertEquals(80, video.h)
        assertTrue(video.supports_streaming, "the composer marks every video it sends as streamable")
        assertTrue(video.duration in 1.35..1.45, "4 x 100ms + 1000ms became ${video.duration}s")
        assertTrue(
            attributes.any { it is TLRPC.TL_documentAttributeAnimated },
            "a silent video was not described as an animation, so it would arrive as a video",
        )
        // stock draws an empty bubble until reopen without one
        val thumb = assertNotNull(described.thumb, "nothing covered the file with a thumbnail")
        assertTrue(thumb.w > 0 && thumb.h > 0, "the thumbnail is ${thumb.w}x${thumb.h}")
        assertTrue(thumb.size > 0, "the thumbnail was saved empty")

        val asDocument = PluginMedia.describeLocalDocument(written[0], "video/mp4", asDocument = true)
        assertTrue(
            asDocument.attributes.isEmpty() && asDocument.thumb == null,
            "a file the caller asked to send as a document was described anyway",
        )
    }

    @Test
    fun a_lottie_source_answers_its_own_frames_and_renders_at_the_size_it_was_asked_for() {
        val plugin = engineFor()
        plugin.js("globalThis.lottie = ${JSONObject.quote(lottieJson(30, 30))}")
        val t = JSONObject(plugin.await(
            """
            (async () => {
              const t = {}
              using animation = await inu.canvas.decodeAnimation(new TextEncoder().encode(lottie), { width: 128, height: 128 })
              t.width = animation.width
              t.height = animation.height
              t.frameCount = animation.frameCount
              t.fps = animation.fps
              t.duration = animation.duration
              using frame = await animation.frame(1)
              t.frameWidth = frame.width
              t.frameHeight = frame.height
              t.frameAt = frame.timestamp
              using unsized = await inu.canvas.decodeAnimation(new TextEncoder().encode(lottie))
              t.defaultSide = unsized.width
              using canvas = inu.canvas.create(128, 128)
              const ctx = canvas.getContext('2d')
              ctx.drawImage(frame, 0, 0)
              t.alpha = ctx.getAverageColor().a
              return t
            })()
            """,
        ))
        assertEquals(128, t.getInt("width"))
        assertEquals(128, t.getInt("height"))
        assertEquals(128, t.getInt("frameWidth"))
        assertEquals(128, t.getInt("frameHeight"))
        assertEquals(33, t.getInt("frameAt"), "the second frame of a 30fps animation")
        assertEquals(512, t.getInt("defaultSide"), "a lottie animation with no size asked for renders at the sticker size")
        assertEquals(30, t.getInt("fps"))
        assertTrue(t.getInt("frameCount") in 25..35, "a 30-frame animation read back as ${t.getInt("frameCount")}")
        assertTrue(t.getInt("duration") in 800..1200, "a one-second animation read back as ${t.getInt("duration")}ms")
        assertTrue(t.getInt("alpha") > 0, "the rendered frame was empty")
    }

    @Test
    fun a_source_with_one_frame_is_an_animation_of_one_frame() {
        val plugin = engineFor()
        val t = JSONObject(plugin.await(
            """
            (async () => {
              const t = {}
              using canvas = inu.canvas.create(32, 32)
              const ctx = canvas.getContext('2d')
              ctx.fillStyle = '#00ff00'
              ctx.fillRect(0, 0, 32, 32)
              using png = await canvas.convertToBlob()
              using animation = await inu.canvas.decodeAnimation(png)
              t.frameCount = animation.frameCount
              t.width = animation.width
              t.fps = animation.fps
              t.duration = animation.duration
              using frame = await animation.frame(0)
              t.frameWidth = frame.width
              t.frameAt = frame.timestamp
              let more = 0
              for await (using again of animation) more++
              t.more = more
              return t
            })()
            """,
        ))
        assertEquals(1, t.getInt("frameCount"))
        assertEquals(32, t.getInt("width"))
        assertEquals(32, t.getInt("frameWidth"))
        assertEquals(0, t.getInt("fps"))
        assertEquals(0, t.getInt("duration"))
        assertEquals(0, t.getInt("frameAt"))
        assertEquals(0, t.getInt("more"), "a still already read by index was iterated again")
    }

    @Test
    fun what_the_device_cannot_take_is_refused_rather_than_written_badly() {
        val plugin = engineFor()
        val t = JSONObject(plugin.await(
            """
            (async () => {
              const t = {}
              const refused = async (label, body) => {
                try {
                  await body()
                  t[label] = 'nothing was thrown'
                } catch (e) {
                  t[label] = e instanceof inu.PluginError ? e.code : String(e)
                }
              }
              await refused('odd', () => inu.canvas.createEncoder({ width: 101, height: 80 }))
              await refused('notAnEncoding', () => inu.canvas.createEncoder({ width: 100, height: 80, type: 'image/gif' }))
              await refused('notAnAnimation', () => inu.canvas.decodeAnimation(new Uint8Array([1, 2, 3, 4])))
              using encoder = await inu.canvas.createEncoder({ width: 100, height: 80 })
              await refused('empty', () => encoder.finish())
              using still = inu.canvas.create(8, 8)
              using animation = await inu.canvas.decodeAnimation(await still.convertToBlob())
              await refused('pastTheEnd', () => animation.frame(9))
              return t
            })()
            """,
        ))
        for (key in listOf("odd", "notAnEncoding", "notAnAnimation", "empty", "pastTheEnd")) {
            assertEquals("invalid-argument", t.getString(key), key)
        }
    }

    @Test
    fun an_abandoned_encoder_leaves_no_file_behind() {
        val plugin = engineFor()
        plugin.await(
            """
            (async () => {
              using canvas = inu.canvas.create(64, 64)
              const encoder = await inu.canvas.createEncoder({ width: 64, height: 64 })
              await encoder.addFrame(canvas)
              encoder.dispose()
            })()
            """,
        )
        settle()
        Thread.sleep(200)
        val written = encodedFiles(plugin)
        assertTrue(written.isEmpty(), "an abandoned encoder left ${written.map { it.name }}")
    }

    @Test
    fun sliced_yuv_planes_report_the_complete_input_extent() {
        val pixels = ByteBuffer.allocateDirect(4 * 2 * 4)
        repeat(8) { pixels.put(byteArrayOf(255.toByte(), 0, 0, 255.toByte())) }
        for (semiPlanar in listOf(false, true)) {
            val input = ByteBuffer.allocateDirect(12)
            val y = input.duplicate().apply { limit(8) }.slice()
            val u = input.duplicate().apply { position(8); limit(if (semiPlanar) 11 else 10) }.slice()
            val v = input.duplicate().apply { position(if (semiPlanar) 9 else 10) }.slice()
            val stride = if (semiPlanar) 4 else 2
            val step = if (semiPlanar) 2 else 1
            assertEquals(
                12,
                NativePixels.rgbaToYuv420(pixels, 4, 2, y, 0, 4, 1, u, 0, stride, step, v, 0, stride, step),
                "sliced ${if (semiPlanar) "semi-planar" else "planar"} input lost its chroma extent",
            )
            assertEquals(81, input.get(0).toInt() and 0xff)
            assertEquals(90, input.get(8).toInt() and 0xff)
            assertEquals(240, input.get(11).toInt() and 0xff)
        }

        val padded = ByteBuffer.allocateDirect(24)
        val y = padded.duplicate().apply { limit(12) }.slice()
        val u = padded.duplicate().apply { position(16); limit(18) }.slice()
        val v = padded.duplicate().apply { position(20); limit(22) }.slice()
        assertEquals(22, NativePixels.rgbaToYuv420(pixels, 4, 2, y, 0, 8, 1, u, 0, 4, 1, v, 0, 4, 1))
        assertEquals(81, padded.get(8).toInt() and 0xff)
        assertEquals(90, padded.get(17).toInt() and 0xff)
        assertEquals(240, padded.get(21).toInt() and 0xff)
    }

    @Test
    fun variable_delay_gif_indices_match_sequential_frames_and_reject_the_real_end() {
        val plugin = engineFor()
        val gif = testAsset("canvas-variable-delay.gif").joinToString(",") { (it.toInt() and 0xff).toString() }
        val t = JSONObject(plugin.await(
            """
            (async () => {
              const t = {}
              using animation = await inu.canvas.decodeAnimation(new Uint8Array([$gif]))
              using canvas = inu.canvas.create(16, 16)
              const ctx = canvas.getContext('2d')
              const frames = []
              for await (using frame of animation) {
                ctx.drawImage(frame, 0, 0)
                frames.push([frame.timestamp, ctx.getAverageColor()])
              }
              t.times = frames.map(frame => frame[0])
              t.same = true
              for (const index of [2, 0, 2, 1, 2]) {
                using frame = await animation.frame(index)
                ctx.drawImage(frame, 0, 0)
                if (JSON.stringify([frame.timestamp, ctx.getAverageColor()]) !== JSON.stringify(frames[index])) t.same = false
              }
              using first = await animation.frame(0)
              try { await animation.frame(3); t.pastEnd = 'accepted' }
              catch (e) { t.pastEnd = e.code }
              return t
            })()
            """,
        ))
        assertEquals("[0,900,1000]", t.getJSONArray("times").toString())
        assertTrue(t.getBoolean("same"), "indexed reads differed from decoded frames")
        assertEquals("invalid-argument", t.getString("pastEnd"))
    }

    private companion object {
        const val TAG = "InuAnimation"
    }
}

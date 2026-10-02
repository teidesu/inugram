package desu.inugram.core.sticker

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.util.Random
import kotlin.math.*

class StickerFitTest {
    private fun rectangle(l: Double, t: Double, r: Double, b: Double) = listOf(
        StickerFit.Point(l, t), StickerFit.Point(r, t), StickerFit.Point(r, b), StickerFit.Point(l, b),
    )

    private fun layer(pixels: IntArray, width: Int, height: Int, matrix: StickerFit.Affine) =
        StickerFit.Layer(ByteArray(pixels.size) { (pixels[it] ushr 24).toByte() }, width, height, matrix)

    @Test fun opaqueSquareRespectsRoundedCorners() {
        val points = rectangle(-256.0, -256.0, 256.0, 256.0)
        val fit = StickerFit.fit(points, 0.0, 512.0)!!
        assertTrue(fit.scale < .94)
        assertContained(points, 0.0, fit)
        assertFalse(StickerFit.contains(256.0 * fit.scale * 1.001, 256.0 * fit.scale * 1.001, 512.0, 1.0))
    }

    @Test fun growsSmallContentAndCentersOffCenterSubject() {
        val points = rectangle(700.0, -35.0, 720.0, 5.0)
        val fit = StickerFit.fit(points, 0.0, 512.0)!!
        assertTrue(fit.scale > 10)
        assertEquals(-710 * fit.scale, fit.translationX, 1e-6)
        assertEquals(15 * fit.scale, fit.translationY, 1e-6)
        assertContained(points, 0.0, fit)
    }

    @Test fun transparentMarginsAndLowAlphaPixelsAreHandled() {
        val pixels = IntArray(100 * 80)
        pixels[40 * 100 + 70] = 0x01000000
        val support = StickerFit.support(listOf(layer(pixels, 100, 80, StickerFit.Affine())), emptyList(), null)
        assertEquals(69.5, support.minOf { it.x }, 0.0)
        assertEquals(71.5, support.maxOf { it.x }, 0.0)
        assertTrue(StickerFit.fit(support, 0.0, 512.0)!!.scale > 100)
    }

    @Test fun unsignedAlphaCoveragePreservesAllOpacityLevels() {
        val alpha = ByteArray(257) { if (it < 256) it.toByte() else 0 }
        val layer = StickerFit.Layer(alpha, 257, 1, StickerFit.Affine())
        val points = StickerFit.support(listOf(layer), emptyList(), null, ignoreAlphaSpecks = true)
        assertEquals(.5, points.minOf { it.x }, 0.0)
        assertEquals(256.5, points.maxOf { it.x }, 0.0)
        assertEquals(StickerFit.support(listOf(layer), emptyList(), null), points)
        val fit = StickerFit.fit(points, 37.0, 512.0)!!
        assertContained(points, 37.0, fit)
    }

    @Test fun chunkedAlphaCopiesPreserveRowsAndPartialChunks() {
        for ((width, height) in listOf(4 to 5, 7 to 2, 9 to 3, 1 to 21, 16385 to 3)) {
            val buffer = IntArray(if (width > 100) 16384 else 7)
            val source = IntArray(width * height) { ((it % 256) shl 24) or 0xabcdef }
            val alpha = StickerFit.copyAlpha(width, height, buffer) { x, y, columns, rows ->
                assertTrue(columns * rows <= buffer.size)
                assertTrue(x + columns <= width && y + rows <= height)
                for (row in 0 until rows) source.copyInto(buffer, row * columns, (y + row) * width + x, (y + row) * width + x + columns)
            }
            assertArrayEquals("$width x $height", ByteArray(source.size) { (source[it] ushr 24).toByte() }, alpha)
        }
    }

    @Test fun detachedAlphaSpecksDoNotLimitAngledContent() {
        val width = 40
        val height = 62
        val clean = IntArray(width * height)
        for (y in 0 until height) {
            val left = max(0, 6 - y / 3)
            val right = if (y < 8) 6 + y * 2 else 39
            for (x in left..right) clean[y * width + x] = -1
        }
        // Like the supplied PNG: the speck is inside the unrotated bounds,
        // but far outside the visible silhouette after rotation.
        val noisy = clean.copyOf().apply { this[2 * width + 33] = 0x01000000 }
        val matrix = StickerFit.Affine(tx = -width / 2.0, ty = -height / 2.0)
        fun support(pixels: IntArray, ignore: Boolean) = StickerFit.support(
            listOf(layer(pixels, width, height, matrix)), emptyList(), null, ignore,
        )
        val expected = support(clean, false)
        val filtered = support(noisy, true)
        val unfiltered = support(noisy, false)
        assertEquals(expected, filtered)
        for (angle in listOf(145.9, -18.47, 71.36)) {
            val fit = StickerFit.fit(filtered, angle, 512.0)!!
            assertContained(expected, angle, fit)
            assertEquals(StickerFit.fit(expected, angle, 512.0), fit)
            assertTrue(fit.scale > StickerFit.fit(unfiltered, angle, 512.0)!!.scale * 1.01)
        }
        for (angle in listOf(0.0, 90.0, 180.0, 270.0)) {
            assertEquals(StickerFit.fit(unfiltered, angle, 512.0), StickerFit.fit(filtered, angle, 512.0))
        }
    }

    @Test fun speckFilteringPreservesConnectedFaintEdges() {
        val pixels = IntArray(30 * 30)
        pixels[0] = -1
        // The faint edge extends beyond the flood-fill queue and connects diagonally.
        for (i in 1..25) pixels[i * 30 + i] = 0x01000000
        val layer = layer(pixels, 30, 30, StickerFit.Affine())
        assertEquals(
            StickerFit.support(listOf(layer), emptyList(), null),
            StickerFit.support(listOf(layer), emptyList(), null, true),
        )
    }

    @Test fun speckFilteringPreservesLargerFaintRegionsAndVisibleDots() {
        val pixels = IntArray(40 * 3)
        pixels[40] = -1
        for (x in 10..26) pixels[40 + x] = 0x02000000
        pixels[39] = 0x03000000
        val layer = layer(pixels, 40, 3, StickerFit.Affine())
        assertEquals(
            StickerFit.support(listOf(layer), emptyList(), null),
            StickerFit.support(listOf(layer), emptyList(), null, true),
        )
    }

    @Test fun speckFilteringPreservesWhollyFaintLayers() {
        val pixels = IntArray(100 * 80)
        pixels[40 * 100 + 70] = 0x01000000
        val layer = layer(pixels, 100, 80, StickerFit.Affine())
        assertEquals(
            StickerFit.support(listOf(layer), emptyList(), null),
            StickerFit.support(listOf(layer), emptyList(), null, true),
        )
    }

    @Test fun cropDoesNotRestoreTransparentOrErasedContent() {
        val pixels = intArrayOf(-1, 0, 0, -1)
        val layer = layer(pixels, 4, 1, StickerFit.Affine())
        assertTrue(StickerFit.support(listOf(layer), emptyList(), StickerFit.Rect(1.6, 0.0, 2.4, 1.0)).isEmpty())
        val clipped = StickerFit.support(listOf(layer), emptyList(), StickerFit.Rect(0.0, 0.0, 1.0, 1.0))
        assertEquals(0.0, clipped.minOf { it.x }, 0.0)
        assertEquals(1.0, clipped.maxOf { it.x }, 0.0)
    }

    @Test fun rotatedCropDoesNotFillErasedGapsBetweenVisibleRuns() {
        val width = 7
        val pixels = IntArray(width * 3) { if (it % width == 0 || it % width == 6) -1 else 0 }
        val c = cos(37.0 * PI / 180); val s = sin(37.0 * PI / 180)
        val matrix = StickerFit.Affine(c, s, -s, c, -3.5 * c + 1.5 * s, -3.5 * s - 1.5 * c)
        val crop = StickerFit.Rect(-.1, -.1, .1, .1)
        assertTrue(StickerFit.support(listOf(layer(pixels, width, 3, matrix)), emptyList(), crop).isEmpty())
        pixels[width + 3] = -1
        val actual = StickerFit.support(listOf(layer(pixels, width, 3, matrix)), emptyList(), crop)
        assertSameCoverage(rectangle(-.1, -.1, .1, .1), actual)
    }

    @Test fun boundaryQueriesMatchClippedPixelPolygons() {
        val random = Random(55)
        val angle = 89.999999 * PI / 180
        val transforms = listOf(
            StickerFit.Affine(tx = -4.0, ty = -3.0),
            StickerFit.Affine(.8, .6, -.6, .8, -3.0, -5.0),
            StickerFit.Affine(-1.7, .4, .5, .9, 8.0, -4.0),
            StickerFit.Affine(0.0, 1.0, -1.0, 0.0, 4.0, -5.0),
            StickerFit.Affine(cos(angle), sin(angle), -sin(angle), cos(angle), 4.0, -5.0),
            StickerFit.Affine(1.0, .4, 2.0, .8, -5.0, -2.0),
            StickerFit.Affine(0.0, 1.0, 0.0, 2.0, 0.0, -5.0),
            StickerFit.Affine(0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        )
        repeat(80) {
            val width = 12; val height = 9
            val alpha = ByteArray(width * height) { if (random.nextInt(4) == 0) 0 else -1 }
            val left = random.nextDouble() * 16 - 8
            val top = random.nextDouble() * 12 - 6
            val crops = listOf(null, StickerFit.Rect(left, top, left + random.nextDouble() * 8, top + random.nextDouble() * 6))
            for (matrix in transforms) for (crop in crops) {
                val layer = StickerFit.Layer(alpha, width, height, matrix)
                val expected = referenceSupport(layer, crop)
                val actual = StickerFit.support(listOf(layer), emptyList(), crop)
                assertSameCoverage(expected, actual)
            }
        }
    }

    @Test fun fragmentedNativeMaskHasBoundedGeometryAllocations() {
        val bean = ManagementFactory.getThreadMXBean()
        assumeTrue(bean is com.sun.management.ThreadMXBean)
        val allocations = bean as com.sun.management.ThreadMXBean
        assumeTrue(allocations.isThreadAllocatedMemorySupported)
        allocations.isThreadAllocatedMemoryEnabled = true
        val width = 4096; val height = 2048
        val alpha = ByteArray(width * height) { if ((it % width + it / width) % 2 == 0) -1 else 0 }
        val matrix = StickerFit.Affine(.8, .6, -.6, .8, -width * .4 + height * .3, -width * .3 - height * .4)
        val layers = listOf(StickerFit.Layer(alpha, width, height, matrix))
        for (crop in listOf(null, StickerFit.Rect(-256.0, -256.0, 256.0, 256.0))) {
            val before = allocations.getThreadAllocatedBytes(Thread.currentThread().id)
            val actual = StickerFit.support(layers, emptyList(), crop, ignoreAlphaSpecks = true)
            val bytes = allocations.getThreadAllocatedBytes(Thread.currentThread().id) - before
            assertTrue("Fragmented geometry allocated $bytes bytes", bytes < 32L * 1024 * 1024)
            if (crop != null) assertSameCoverage(rectangle(-256.0, -256.0, 256.0, 256.0), actual)
            else assertTrue(actual.isNotEmpty())
        }
    }

    /** Independent oracle: clip each occupied pixel's expanded polygon before taking its hull. */
    private fun referenceSupport(layer: StickerFit.Layer, crop: StickerFit.Rect?): List<StickerFit.Point> {
        val points = ArrayList<StickerFit.Point>()
        for (y in 0 until layer.height) for (x in 0 until layer.width) {
            if (layer.alpha[y * layer.width + x].toInt() == 0) continue
            var polygon = rectangle(x - .5, y - .5, x + 1.5, y + 1.5).map { layer.matrix.map(it.x, it.y) }
            if (crop != null) for (edge in 0..3) {
                val input = polygon
                polygon = buildList {
                    if (input.isEmpty()) return@buildList
                    fun distance(p: StickerFit.Point) = when (edge) {
                        0 -> p.x - crop.left; 1 -> crop.right - p.x
                        2 -> p.y - crop.top; else -> crop.bottom - p.y
                    }
                    var previous = input.last(); var pd = distance(previous)
                    for (current in input) {
                        val cd = distance(current)
                        if ((pd >= 0) != (cd >= 0)) {
                            val t = pd / (pd - cd)
                            add(StickerFit.Point(previous.x + t * (current.x - previous.x), previous.y + t * (current.y - previous.y)))
                        }
                        if (cd >= 0) add(current)
                        previous = current; pd = cd
                    }
                }
            }
            points.addAll(polygon)
        }
        return StickerFit.hull(points)
    }

    private fun assertSameCoverage(expected: List<StickerFit.Point>, actual: List<StickerFit.Point>) {
        assertEquals("Empty coverage differs", expected.isEmpty(), actual.isEmpty())
        if (expected.isEmpty()) return
        for (angle in 0 until 360 step 5) {
            val c = cos(angle * PI / 180); val s = sin(angle * PI / 180)
            fun projection(p: StickerFit.Point) = p.x * c + p.y * s
            assertEquals("Minimum support differs at $angle", expected.minOf(::projection), actual.minOf(::projection), 1e-8)
            assertEquals("Maximum support differs at $angle", expected.maxOf(::projection), actual.maxOf(::projection), 1e-8)
        }
    }

    @Test fun everyAnglePreservesContainmentAndMaximizesScale() {
        val points = rectangle(-140.0, -40.0, 120.0, 60.0)
        for (angle in listOf(-180.0, -179.0, -90.0, -45.0, 0.0, 23.0, 45.0, 90.0, 179.0, 180.0)) {
            val fit = StickerFit.fit(points, angle, 512.0)!!
            assertContained(points, angle, fit)
            val radians = angle * PI / 180
            val violates = points.any {
                val x = (cos(radians) * it.x - sin(radians) * it.y) * fit.scale + fit.translationX
                val y = (sin(radians) * it.x + cos(radians) * it.y) * fit.scale + fit.translationY
                !StickerFit.contains(x * 1.001, y * 1.001, 512.0, 1.0)
            }
            assertTrue("not maximal at $angle", violates)
        }
    }

    @Test fun fullAnimatedLayerBoundsParticipateInFit() {
        val base = rectangle(-10.0, -10.0, 10.0, 10.0)
        val overlay = rectangle(400.0, -80.0, 800.0, 80.0)
        val combined = StickerFit.support(emptyList(), listOf(base, overlay), null)
        val fit = StickerFit.fit(combined, 45.0, 512.0)!!
        assertContained(base + overlay, 45.0, fit)
        assertTrue(fit.scale < StickerFit.fit(base, 45.0, 512.0)!!.scale)
    }

    @Test fun angledAlphaContentUsesTransparentCornersInsteadOfItsBoundingBox() {
        val pixels = IntArray(41 * 41) { index ->
            if (abs(index % 41 - 20) + abs(index / 41 - 20) <= 20) -1 else 0
        }
        val matrix = StickerFit.Affine(a = 10.0, d = 10.0, tx = -205.0, ty = -205.0)
        val points = StickerFit.support(listOf(layer(pixels, 41, 41, matrix)), emptyList(), null)
        val bounds = rectangle(-205.0, -205.0, 205.0, 205.0)
        for (angle in listOf(23.0, 37.0, 74.0, -137.0, 196.0)) {
            val fit = StickerFit.fit(points, angle, 512.0)!!
            assertContained(points, angle, fit)
            assertTrue("transparent corners wasted at $angle", fit.scale > StickerFit.fit(bounds, angle, 512.0)!!.scale * 1.1)
        }
    }

    @Test fun extremeAspectRatiosAreNotClampedToStockLimits() {
        val fit = StickerFit.fit(rectangle(-1e6, -1.0, 1e6, 1.0), 0.0, 512.0)!!
        assertTrue(fit.scale > 0 && fit.scale < .33)
    }

    @Test fun fitIsIndependentOfPreviewSizeAndIdempotent() {
        val points = rectangle(-120.0, -180.0, 120.0, 180.0)
        val a = StickerFit.fit(points, 37.0, 512.0)!!
        val b = StickerFit.fit(points.map { StickerFit.Point(it.x * .5, it.y * .5) }, 37.0, 256.0)!!
        assertEquals(a.scale, b.scale, 1e-9)
        assertEquals(a.translationX * .5, b.translationX, 1e-9)
        assertEquals(a, StickerFit.fit(points, 37.0, 512.0))
    }

    @Test fun emptyOrInvalidGeometryDoesNotFit() {
        assertNull(StickerFit.fit(emptyList(), 0.0, 512.0))
        assertNull(StickerFit.fit(rectangle(0.0, 0.0, 1.0, 1.0), 0.0, 0.0))
        assertNull(StickerFit.fit(listOf(StickerFit.Point(Double.NaN, 1.0)), 0.0, 512.0))
    }

    private fun assertContained(points: List<StickerFit.Point>, angle: Double, fit: StickerFit.Result) {
        val radians = angle * PI / 180
        for (p in points) {
            val x = (cos(radians) * p.x - sin(radians) * p.y) * fit.scale + fit.translationX
            val y = (sin(radians) * p.x + cos(radians) * p.y) * fit.scale + fit.translationY
            // Centering and applying translation can differ by a few floating-point ulps.
            assertTrue("outside at $angle: $x, $y", StickerFit.contains(x, y, 512.0, 1.0 - 1e-7))
        }
    }
}

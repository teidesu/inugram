package desu.inugram.core.sticker

import kotlin.math.*

/** Geometry uses the editor's logical pixels, independent of preview size. */
object StickerFit {
    data class Point(val x: Double, val y: Double)
    data class Rect(val left: Double, val top: Double, val right: Double, val bottom: Double)
    data class Affine(
        val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0,
        val d: Double = 1.0, val tx: Double = 0.0, val ty: Double = 0.0,
    ) {
        fun map(x: Double, y: Double) = Point(a * x + c * y + tx, b * x + d * y + ty)
    }
    data class Layer(val alpha: ByteArray, val width: Int, val height: Int, val matrix: Affine)
    data class Result(val scale: Double, val translationX: Double, val translationY: Double)

    /** The caller budgets the mask first. Read native pixels through one reusable ARGB buffer. */
    fun copyAlpha(width: Int, height: Int, pixels: IntArray, read: (Int, Int, Int, Int) -> Unit): ByteArray {
        val count = width.toLong() * height
        require(width > 0 && height > 0 && count <= Int.MAX_VALUE && pixels.isNotEmpty())
        val alpha = ByteArray(count.toInt())
        var offset = 0
        while (offset < alpha.size) {
            val x = offset % width
            val y = offset / width
            val columns = min(pixels.size, width - x)
            val rows = if (x == 0 && width <= pixels.size) min(pixels.size / columns, height - y) else 1
            val length = columns * rows
            read(x, y, columns, rows)
            for (i in 0 until length) alpha[offset + i] = (pixels[i] ushr 24).toByte()
            offset += length
        }
        return alpha
    }

    /** Retain coverage on row/crop boundaries before taking the composition's hull. */
    fun support(layers: List<Layer>, rectangles: List<List<Point>>, crop: Rect?, ignoreAlphaSpecks: Boolean = false): List<Point> {
        val points = SupportAccumulator()
        for (layer in layers) {
            require(layer.width > 0 && layer.height > 0 && layer.alpha.size.toLong() == layer.width.toLong() * layer.height)
            CoverageBoundary(layer, if (ignoreAlphaSpecks) alphaSpecks(layer) else null, crop, points).collect()
        }
        for (polygon in rectangles) {
            for (point in if (crop == null) polygon else clip(polygon, crop)) points.add(point)
        }
        return points.finish()
    }

    /** Keep only the actual hull plus a small pending batch, regardless of disconnected runs. */
    private class SupportAccumulator {
        private var boundary = emptyList<Point>()
        private val pending = ArrayList<Point>(512)

        fun add(point: Point) {
            pending.add(point)
            if (pending.size == 512) flush()
        }

        private fun flush() {
            if (pending.isEmpty()) return
            boundary = hull(boundary + pending)
            pending.clear()
        }

        fun finish(): List<Point> { flush(); return boundary }
    }

    /**
     * Every vertex of a clipped pixel run lies on a row edge or a crop edge.
     * The first/last covered points on those six lines give the same hull without
     * constructing a polygon for every run. Checking alpha along each line keeps
     * holes and erased pixels intact even when the crop lies between visible runs.
     */
    private class CoverageBoundary(
        private val layer: Layer,
        private val specks: ByteArray?,
        private val crop: Rect?,
        private val points: SupportAccumulator,
    ) {
        private val m = layer.matrix
        private var row = 0
        private var first = 0
        private var last = 0
        private var from = 0.0
        private var to = 1.0

        private fun covered(x: Int) = layer.alpha[row * layer.width + x].toInt() != 0 &&
            specks?.get(row * layer.width + x)?.toInt() != 2

        fun collect() {
            for (y in 0 until layer.height) {
                row = y
                first = 0
                while (first < layer.width && !covered(first)) first++
                if (first == layer.width) continue
                last = layer.width - 1
                while (!covered(last)) last--
                // Match the existing half-pixel allowance for bilinear filtering.
                val l = first - .5; val r = last + 1.5
                val t = y - .5; val b = y + 1.5
                line(l, t, r, t)
                line(l, b, r, b)
                if (crop != null) {
                    for (edge in 0..3) {
                        val horizontal = edge >= 2
                        val a = if (horizontal) m.b else m.a
                        val c = if (horizontal) m.d else m.c
                        val value = when (edge) {
                            0 -> crop.left; 1 -> crop.right; 2 -> crop.top; else -> crop.bottom
                        } - if (horizontal) m.ty else m.tx
                        // Parameterize in the more stable source direction. No inverse
                        // matrix is needed, including for mirrored or singular transforms.
                        if (abs(c) >= abs(a) && c != 0.0) {
                            line(l, (value - a * l) / c, r, (value - a * r) / c, edge)
                        } else if (a != 0.0) {
                            line((value - c * t) / a, t, (value - c * b) / a, b, edge)
                        }
                    }
                }
            }
        }

        private fun restrict(start: Double, end: Double, minimum: Double, maximum: Double): Boolean {
            val delta = end - start
            if (delta == 0.0) return start >= minimum && start <= maximum
            val a = (minimum - start) / delta
            val b = (maximum - start) / delta
            from = max(from, min(a, b))
            to = min(to, max(a, b))
            return from <= to
        }

        private fun line(x0: Double, y0: Double, x1: Double, y1: Double, edge: Int = -1) {
            from = 0.0; to = 1.0
            if (edge >= 0 && (!restrict(x0, x1, first - .5, last + 1.5) ||
                !restrict(y0, y1, row - .5, row + 1.5))) return
            val u0 = m.a * x0 + m.c * y0 + m.tx; val u1 = m.a * x1 + m.c * y1 + m.tx
            val v0 = m.b * x0 + m.d * y0 + m.ty; val v1 = m.b * x1 + m.d * y1 + m.ty
            if (crop != null) {
                // A crop edge's fixed coordinate is exact; don't reject it due to
                // cancellation error when mapping its source-space equation back.
                if ((edge < 0 || edge >= 2) && !restrict(u0, u1, crop.left, crop.right)) return
                if (edge < 2 && !restrict(v0, v1, crop.top, crop.bottom)) return
            }
            val dx = x1 - x0
            val xFrom = x0 + dx * from; val xTo = x0 + dx * to
            val left = min(xFrom, xTo); val right = max(xFrom, xTo)
            var start = max(first, ceil(left - 1.5).toInt())
            var end = min(last, floor(right + .5).toInt())
            while (start <= end && !covered(start)) start++
            if (start > end) return
            while (!covered(end)) end--
            if (dx != 0.0) {
                val a = (max(left, start - .5) - x0) / dx
                val b = (min(right, end + 1.5) - x0) / dx
                from = max(from, min(a, b))
                to = min(to, max(a, b))
            }
            add(u0 + (u1 - u0) * from, v0 + (v1 - v0) * from, edge)
            add(u0 + (u1 - u0) * to, v0 + (v1 - v0) * to, edge)
        }

        private fun add(x: Double, y: Double, edge: Int) {
            points.add(Point(
                when (edge) { 0 -> crop!!.left; 1 -> crop!!.right; else -> x },
                when (edge) { 2 -> crop!!.top; 3 -> crop!!.bottom; else -> y },
            ))
        }
    }

    /** Ignore only detached specks under 1% opacity, never connected edges or wholly faint layers. */
    private fun alphaSpecks(layer: Layer): ByteArray? {
        val maximumAlpha = 2
        val maximumPixels = 16
        var hasFaint = false
        var hasVisible = false
        for (value in layer.alpha) {
            val alpha = value.toInt() and 0xff
            if (alpha in 1..maximumAlpha) hasFaint = true
            if (alpha > maximumAlpha) hasVisible = true
            if (hasFaint && hasVisible) break
        }
        if (!hasFaint || !hasVisible) return null
        // 0 = unvisited, 1 = retained, 2 = speck, 3 = queued in the current region.
        val states = ByteArray(layer.alpha.size)
        val queue = IntArray(maximumPixels + 1)
        for (start in layer.alpha.indices) {
            if (states[start].toInt() != 0 || (layer.alpha[start].toInt() and 0xff) !in 1..maximumAlpha) continue
            queue[0] = start
            states[start] = 3
            var size = 1
            var cursor = 0
            var retain = false
            region@ while (cursor < size) {
                val index = queue[cursor++]
                val x = index % layer.width
                val y = index / layer.width
                for (ny in max(0, y - 1)..min(layer.height - 1, y + 1)) {
                    for (nx in max(0, x - 1)..min(layer.width - 1, x + 1)) {
                        val neighbor = ny * layer.width + nx
                        val alpha = layer.alpha[neighbor].toInt() and 0xff
                        if (alpha == 0) continue
                        if (alpha > maximumAlpha || states[neighbor].toInt() == 1) {
                            retain = true
                            break@region
                        }
                        if (states[neighbor].toInt() == 0) {
                            states[neighbor] = 3
                            queue[size++] = neighbor
                            if (size > maximumPixels) {
                                retain = true
                                break@region
                            }
                        }
                    }
                }
            }
            for (i in 0 until size) states[queue[i]] = if (retain) 1 else 2
        }
        return states
    }

    fun fit(points: List<Point>, angle: Double, side: Double, margin: Double = side / 512.0): Result? {
        if (points.isEmpty() || side <= 0 || !side.isFinite()) return null
        val radians = angle * PI / 180
        val c = cos(radians); val s = sin(radians)
        val rotated = points.map { Point(c * it.x - s * it.y, s * it.x + c * it.y) }
        if (rotated.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val left = rotated.minOf { it.x }; val right = rotated.maxOf { it.x }
        val top = rotated.minOf { it.y }; val bottom = rotated.maxOf { it.y }
        val cx = (left + right) / 2; val cy = (top + bottom) / 2
        val extent = max(right - left, bottom - top)
        if (extent <= 0 || margin >= side / 2) return null
        val centered = rotated.map { Point(it.x - cx, it.y - cy) }
        var lo = 0.0; var hi = side / extent
        repeat(60) {
            val mid = (lo + hi) / 2
            if (centered.all { contains(it.x * mid, it.y * mid, side, margin) }) lo = mid else hi = mid
        }
        return Result(lo, -cx * lo, -cy * lo)
    }

    /** Rounded square eroded by margin, with its center at the origin. */
    fun contains(x: Double, y: Double, side: Double, margin: Double): Boolean {
        val half = side / 2 - margin
        val radius = max(0.0, side / 8 - margin)
        val ax = abs(x); val ay = abs(y)
        if (ax > half || ay > half) return false
        val dx = max(0.0, ax - (half - radius)); val dy = max(0.0, ay - (half - radius))
        return dx * dx + dy * dy <= radius * radius + 1e-9
    }

    private fun clip(polygon: List<Point>, rect: Rect): List<Point> {
        var result = polygon
        for (edge in 0..3) {
            val input = result
            result = ArrayList()
            if (input.isEmpty()) break
            fun distance(p: Point) = when (edge) {
                0 -> p.x - rect.left; 1 -> rect.right - p.x
                2 -> p.y - rect.top; else -> rect.bottom - p.y
            }
            var previous = input.last(); var pd = distance(previous)
            for (current in input) {
                val cd = distance(current)
                if ((pd >= 0) != (cd >= 0)) {
                    val t = pd / (pd - cd)
                    result.add(Point(previous.x + t * (current.x - previous.x), previous.y + t * (current.y - previous.y)))
                }
                if (cd >= 0) result.add(current)
                previous = current; pd = cd
            }
        }
        return result
    }

    fun hull(points: List<Point>): List<Point> {
        val sorted = points.distinct().sortedWith(compareBy<Point> { it.x }.thenBy { it.y })
        if (sorted.size <= 2) return sorted
        fun cross(a: Point, b: Point, c: Point) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        val lower = ArrayList<Point>(); val upper = ArrayList<Point>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.lastIndex)
            lower.add(p)
        }
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.lastIndex)
            upper.add(p)
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }
}

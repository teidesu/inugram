package desu.inugram.core.sticker

object StickerRotation {
    /** Reduce complete turns for display or after a gesture, never while a pointer is moving. */
    fun normalize(angle: Float): Float {
        val wrapped = angle % 360f
        return when {
            wrapped > 180f -> wrapped - 360f
            wrapped < -180f -> wrapped + 360f
            else -> wrapped
        }
    }

    /** Positions are in dp, giving the thumbwheel one degree per dp on every screen density. */
    class Drag {
        var pointerId = -1
            private set
        val isDragging get() = pointerId != -1
        var angle = 0f
            private set
        private var previousX = 0.0
        private var startAngle = 0.0
        private var delta = 0.0

        fun start(pointerId: Int, x: Double, angle: Float) {
            this.pointerId = pointerId
            previousX = x
            startAngle = angle.toDouble()
            delta = 0.0
            this.angle = angle
        }

        fun move(pointerId: Int, x: Double): Float? {
            if (!isDragging || pointerId != this.pointerId) return null
            delta += x - previousX
            previousX = x
            angle = (startAngle + delta).toFloat()
            return angle
        }

        fun replacePointer(pointerId: Int, x: Double) {
            if (!isDragging) return
            this.pointerId = pointerId
            previousX = x
        }

        /** Reset or another explicit edit can change the angle while a pointer is held. */
        fun sync(angle: Float) {
            if (angle == this.angle) return
            startAngle = angle.toDouble()
            delta = 0.0
            this.angle = angle
        }

        /** Cancellation keeps the last applied position, like the editor's other gestures. */
        fun end(): Float? {
            if (!isDragging) return null
            pointerId = -1
            angle = normalize(angle)
            return angle
        }
    }
}

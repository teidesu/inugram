package desu.inugram.core.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StickerRotationTest {
    @Test fun normalizationPreservesBothHalfTurnDirections() {
        for (angle in listOf(-180f, -179.999f, -179f, 0f, 179f, 179.999f, 180f)) {
            assertEquals(angle, StickerRotation.normalize(angle), 0f)
        }
    }

    @Test fun completedTurnsHaveTheSameNormalizedAngle() {
        for (turns in -10..10) {
            assertEquals(37f, StickerRotation.normalize(37f + turns * 360f), 0f)
        }
        assertEquals(180f, StickerRotation.normalize(540f), 0f)
        assertEquals(-180f, StickerRotation.normalize(-540f), 0f)
        assertEquals(-179f, StickerRotation.normalize(181f), 0f)
        assertEquals(179f, StickerRotation.normalize(-181f), 0f)
    }

    @Test fun draggingContinuesThroughBothHalfTurnsAndFullTurns() {
        val drag = StickerRotation.Drag()
        drag.start(7, 20.0, 179f)
        assertEquals(181f, drag.move(7, 22.0)!!, 0f)
        assertEquals(899f, drag.move(7, 740.0)!!, 0f)
        assertEquals(179f, drag.end()!!, 0f)
        drag.start(7, 200.0, -179f)
        assertEquals(-181f, drag.move(7, 198.0)!!, 0f)
        assertEquals(-899f, drag.move(7, -520.0)!!, 0f)
        assertEquals(-179f, drag.end()!!, 0f)
    }

    @Test fun repeatedSwipesKeepRotatingInTheSameDirection() {
        for (direction in listOf(-1f, 1f)) {
            val drag = StickerRotation.Drag()
            var angle = 0f
            for (swipe in 1..20) {
                drag.start(3, 20.0, angle)
                angle = drag.move(3, 20.0 + direction * 90)!!
                assertEquals(StickerRotation.normalize(direction * swipe * 90), StickerRotation.normalize(angle), 0f)
                angle = drag.end()!!
            }
        }
    }

    @Test fun replacingTheActivePointerDoesNotMoveTheImage() {
        val drag = StickerRotation.Drag()
        drag.start(3, 20.0, 0f)
        assertEquals(30f, drag.move(3, 50.0)!!, 0f)
        assertNull(drag.move(9, 240.0))
        drag.replacePointer(9, 240.0)
        assertEquals(30f, drag.angle, 0f)
        assertNull(drag.move(3, 100.0))
        assertEquals(40f, drag.move(9, 250.0)!!, 0f)
    }

    @Test fun cancellationStopsMovesAndTheNextSwipeStartsAtTheCurrentAngle() {
        val drag = StickerRotation.Drag()
        drag.start(3, 20.0, 10f)
        drag.move(3, 45.0)
        assertEquals(35f, drag.end()!!, 0f)
        assertFalse(drag.isDragging)
        assertNull(drag.move(3, 100.0))
        assertNull(drag.end())
        drag.start(4, 100.0, drag.angle)
        assertEquals(34f, drag.move(4, 99.0)!!, 0f)
    }

    @Test fun resetDuringADragDoesNotRestoreThePreviousAngle() {
        val drag = StickerRotation.Drag()
        drag.start(3, 20.0, 45f)
        drag.move(3, 50.0)
        drag.sync(0f)
        assertEquals(1f, drag.move(3, 51.0)!!, 0f)
    }

    @Test fun smallMovementsAccumulateWithoutFrameByFrameRounding() {
        val drag = StickerRotation.Drag()
        drag.start(1, 0.0, 12.25f)
        for (step in 1..100) {
            drag.move(1, step / 10.0)
            // Mirrors the editor syncing the current transform before drawing each frame.
            drag.sync(drag.angle)
        }
        assertEquals(22.25f, drag.angle, 0f)
    }
}

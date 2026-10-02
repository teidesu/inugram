package desu.inugram.core.sticker

import org.junit.Assert.*
import org.junit.Test

class StickerFitSnapshotBudgetTest {
    private val scratchBytes = StickerFitSnapshotBudget.COPY_PIXELS * 4L

    @Test fun nativeEditorPhotoAndOverlayFitWithinTheBudget() {
        val budget = StickerFitSnapshotBudget(48L * 1024 * 1024)
        assertEquals(2560 * 2560, budget.reserve(2560, 2560, render = true))
        assertEquals(512 * 512, budget.reserve(512, 512, render = false))
        assertEquals(512 * 512, budget.reserve(512, 512, render = true))
    }

    @Test fun allLayersShareThePixelLimit() {
        val budget = StickerFitSnapshotBudget(48L * 1024 * 1024, maximumPixels = 3L * 512 * 512)
        budget.reserve(512, 512, render = true)
        budget.reserve(512, 512, render = false)
        budget.reserve(512, 512, render = true)
        assertRejected { budget.reserve(1, 1, render = true) }
    }

    @Test fun rasterAndRetainedMasksCountTowardPeakMemory() {
        val budget = StickerFitSnapshotBudget(scratchBytes + 550)
        budget.reserve(10, 10, render = false)
        assertRejected { budget.reserve(10, 10, render = true) }
        // Rejecting a layer must not consume the budget for subsequent smaller layers.
        assertEquals(90, budget.reserve(9, 10, render = true))
    }

    @Test fun analysisScratchCountsTowardPeakMemory() {
        val budget = StickerFitSnapshotBudget(scratchBytes + 400)
        budget.reserve(10, 10, render = false)
        budget.reserve(10, 10, render = false)
        assertRejected { budget.reserve(1, 1, render = false) }
    }

    @Test fun giantAndOverflowingDimensionsAreRejectedBeforeAllocation() {
        val budget = StickerFitSnapshotBudget(48L * 1024 * 1024)
        assertRejected { budget.reserve(8072, 12464, render = true) }
        assertRejected { budget.reserve(Int.MAX_VALUE, Int.MAX_VALUE, render = true) }
        assertRejected { budget.reserve(65536, 65536, render = false) }
        assertEquals(2560 * 2560, budget.reserve(2560, 2560, render = true))
    }

    @Test fun smallerHeapBudgetsRejectEvenAnOtherwiseAllowedRaster() {
        val budget = StickerFitSnapshotBudget(4L * 1024 * 1024)
        assertRejected { budget.reserve(2560, 2560, render = true) }
        assertEquals(512 * 512, budget.reserve(512, 512, render = true))
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("Snapshot exceeding the budget was accepted")
        } catch (_: IllegalStateException) {
            // The caller uses the existing Fit failure path.
        }
    }
}

package desu.inugram.core.sticker

import org.junit.Assert.*
import org.junit.Test

class StickerFitStateTest {
    private val points = listOf(StickerFit.Point(1.0, 2.0))

    @Test fun controlsBecomeReadyOnCalculationCompletion() {
        val state = StickerFitState()
        assertFalse(state.ready)
        val token = state.begin(listOf("image"))!!
        assertTrue(state.busy)
        assertFalse(state.ready)
        assertTrue(state.complete(token, Result.success(points)))
        assertTrue(state.ready)
        assertFalse(state.busy)
    }

    @Test fun returningFromAToolRejectsItsOldSnapshot() {
        val state = StickerFitState()
        val old = state.begin(listOf("before eraser"))!!
        state.invalidate()
        val edited = state.begin(listOf("after eraser"))!!
        assertFalse(state.complete(old, Result.success(emptyList())))
        assertTrue(state.busy)
        assertTrue(state.complete(edited, Result.success(points)))
        assertTrue(state.ready)
        assertEquals(points, state.support)
    }

    @Test fun unchangedLayoutKeepsCompletedGeometry() {
        val state = StickerFitState()
        val key = listOf("document", 100, 200)
        val token = state.begin(key)!!
        state.complete(token, Result.success(points))
        assertFalse(state.needsAnalysis(key.toList()))
        assertNull(state.begin(key.toList()))
        assertTrue(state.ready)
        assertEquals(points, state.support)
    }

    @Test fun layoutSizeChangeRequiresFreshGeometry() {
        val state = StickerFitState()
        val original = listOf("cutout", 400, 800)
        val old = state.begin(original)!!
        state.complete(old, Result.success(points))
        assertFalse(state.needsAnalysis(original))
        assertTrue(state.ready)
        val resized = listOf("cutout", 800, 400)
        assertTrue(state.needsAnalysis(resized))
        val latest = state.begin(resized)!!
        assertFalse(state.ready)
        assertFalse(state.complete(old, Result.success(points)))
        state.complete(latest, Result.success(points))
        assertFalse(state.needsAnalysis(resized))
        assertTrue(state.ready)
    }

    @Test fun replacedContentSkipsQueuedWorkAndRejectsOldResults() {
        val state = StickerFitState()
        val old = state.begin(listOf("old outline"))!!
        state.invalidate()
        assertFalse(state.isCurrent(old))
        val latest = state.begin(listOf("new outline"))!!
        assertFalse(state.complete(old, Result.success(points)))
        assertTrue(state.complete(latest, Result.success(points)))
        assertTrue(state.ready)
    }

    @Test fun emptyContentFinishesLoadingWithoutEnablingFit() {
        val state = StickerFitState()
        val token = state.begin(listOf("fully erased"))!!
        state.complete(token, Result.success(emptyList()))
        assertFalse(state.busy)
        assertFalse(state.ready)
        assertNull(state.begin(listOf("fully erased")))
    }

    @Test fun failedSnapshotAllowsAnExplicitRetry() {
        val state = StickerFitState()
        val key = listOf("image")
        val token = state.begin(key)!!
        state.complete(token, Result.failure(IllegalStateException("snapshot")))
        assertTrue(state.failed)
        assertTrue(state.ready)
        state.invalidate()
        val retry = state.begin(key)!!
        assertTrue(state.complete(retry, Result.success(points)))
        assertFalse(state.failed)
        assertTrue(state.ready)
    }
}

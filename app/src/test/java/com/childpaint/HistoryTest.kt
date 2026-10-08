package com.childpaint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The undo bookkeeping, without a View or a bitmap anywhere near it. Robolectric
 * only because [ObjectPicker] holds android.graphics objects that a plain JVM
 * cannot initialise.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryTest {

    private fun op() = ClearOp(0)

    @Test
    fun undoAndRedoWalkTheListInOrder() {
        val h = History()
        val a = op()
        val b = op()
        h.record(a)
        h.record(b)
        assertSame(b, h.undo())
        assertSame(a, h.undo())
        assertNull(h.undo())
        assertFalse(h.canUndo)
        assertSame(a, h.redo())
        assertSame(b, h.redo())
        assertNull(h.redo())
        assertEquals(listOf(a, b), h.ops)
    }

    @Test
    fun newWorkThrowsAwayWhatCouldHaveBeenRedone() {
        val h = History()
        h.record(op())
        h.undo()
        assertTrue(h.canRedo)
        h.record(op())
        assertFalse(h.canRedo)
    }

    @Test
    fun resetStartsOverWithNothingToRedo() {
        val h = History()
        h.record(op())
        h.undo()
        val restored = listOf(op(), op(), op())
        h.reset(restored)
        assertEquals(restored, h.ops)
        assertFalse(h.canRedo)
        h.reset()
        assertFalse(h.canUndo)
    }

    @Test
    fun hiddenCountsRelocatedOpsAndWhatIsHeld() {
        val h = History()
        repeat(4) { h.record(op()) }
        h.record(RelocateOp(intArrayOf(1), null))
        assertEquals(setOf(1), h.hidden())
        assertEquals(setOf(1, 3), h.hidden(intArrayOf(3)))
    }

    /** A snapshot that already has an op painted in is wrong once that op is taken back. */
    @Test
    fun aSnapshotIsUnusableOnceALaterRelocationReachesIntoIt() {
        val h = History()
        repeat(12) { h.record(op()) }
        assertTrue(h.snapshotUsable(12))
        h.record(RelocateOp(intArrayOf(12 - 1), null))
        assertFalse(h.snapshotUsable(12))
        // An earlier snapshot, taken before the hidden op existed, is still fine.
        assertTrue(h.snapshotUsable(11))
        // Undoing the relocation makes the later snapshot usable again.
        h.undo()
        assertTrue(h.snapshotUsable(12))
    }

    @Test
    fun aSnapshotIsUnusableWhileSomethingInItIsHeld() {
        val h = History()
        repeat(12) { h.record(op()) }
        assertFalse(h.snapshotUsable(12, held = intArrayOf(4)))
        assertTrue(h.snapshotUsable(4, held = intArrayOf(4)))
    }
}

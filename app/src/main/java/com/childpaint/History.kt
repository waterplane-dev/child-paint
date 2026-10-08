package com.childpaint

/**
 * The op list and its redo stack, and the rules that make them more than two
 * lists: which ops are on the paper right now, and whether a snapshot of the
 * first few is still a fair place to start a replay from.
 *
 * Kept out of [PaintCanvasView] so the bookkeeping that undo, redo and the hand
 * all lean on can be tested without a View. It holds no bitmaps — the view owns
 * the paper and its snapshots, and asks this what to paint over them.
 */
class History {

    private val done = mutableListOf<DrawOp>()
    private val undone = mutableListOf<DrawOp>()

    /** Everything recorded, oldest first. Indices are what [RelocateOp.hidden] refers to. */
    val ops: List<DrawOp> get() = done

    val size: Int get() = done.size
    val canUndo: Boolean get() = done.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()

    /** New work: recorded, and whatever could have been redone is gone. */
    fun record(op: DrawOp) {
        done += op
        undone.clear()
    }

    /** Starts over on [ops] — empty for a new page — with nothing to redo. */
    fun reset(ops: List<DrawOp> = emptyList()) {
        done.clear()
        done += ops
        undone.clear()
    }

    /** Takes the newest op off the list, or null when there is none. */
    fun undo(): DrawOp? = done.removeLastOrNull()?.also { undone += it }

    /** Puts the most recently undone op back, or null when there is none. */
    fun redo(): DrawOp? = undone.removeLastOrNull()?.also { done += it }

    /** Every op index that is off the paper right now: relocated, or [held] in the hand. */
    fun hidden(held: IntArray = NONE): Set<Int> =
        ObjectPicker.hiddenIn(done).apply { held.forEach { add(it) } }

    /**
     * Whether a snapshot of the first [count] ops is still the right starting point.
     * It is not once something later — a relocation, or the thing [held] in the
     * hand — takes back an op the snapshot already has painted in.
     */
    fun snapshotUsable(count: Int, held: IntArray = NONE): Boolean {
        if (held.any { it < count }) return false
        for (k in count until done.size) {
            val op = done[k]
            if (op is RelocateOp && op.hidden.any { it < count }) return false
        }
        return true
    }

    /**
     * Which of the snapshots taken after [counts] ops (oldest first) to let go of
     * when there are too many: one that can no longer start a replay, or else the
     * oldest. Keeping a dead one over a live one leaves every undo replaying from
     * the first op.
     */
    fun snapshotToDrop(counts: List<Int>): Int =
        counts.indexOfFirst { !snapshotUsable(it) }.takeIf { it >= 0 } ?: 0

    private companion object {
        val NONE = IntArray(0)
    }
}

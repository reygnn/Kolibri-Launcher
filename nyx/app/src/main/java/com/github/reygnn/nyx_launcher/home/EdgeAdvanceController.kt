package com.github.reygnn.nyx_launcher.home

/**
 * -1 near the left pager edge, +1 near the right, 0 in between. Pure given the
 * pager bounds ([left]/[right], in drag-layer coordinates) and the edge band
 * width [edgePx]. The view glue (reading the pager rect + density) stays in
 * MainActivity.edgeDirection.
 */
internal fun pageEdgeDirection(x: Int, left: Int, right: Int, edgePx: Int): Int = when {
    x <= left + edgePx -> -1
    x >= right - edgePx -> 1
    else -> 0
}

/**
 * The "hold a drag at the pager edge to page across grids" state machine, lifted
 * out of MainActivity so its timing/dwell/re-arm logic is JVM-testable (shared
 * Rule 10) with a fake [Scheduler] instead of a real `Handler`/`postDelayed`.
 *
 * Behaviour (unchanged from the inline version): while a drag dwells within the
 * edge band, after [dwellMs] flip one page toward that edge, then re-arm so
 * paging continues while the finger is held; leaving the band (or the drag
 * ending) cancels the pending flip. A flip is clamped to `[0, maxPage]` and only
 * fires if it actually changes the page.
 *
 * The view/runtime glue is injected: [scheduler] wraps `postDelayed`/
 * `removeCallbacks`, [isDragging] the live drag state, [direction] the
 * view-based [pageEdgeDirection], and [currentPage]/[maxPage]/[goToPage] the
 * pager.
 */
internal class EdgeAdvanceController(
    private val dwellMs: Long,
    private val scheduler: Scheduler,
    private val isDragging: () -> Boolean,
    private val direction: (x: Int) -> Int,
    private val currentPage: () -> Int,
    private val maxPage: () -> Int,
    private val goToPage: (Int) -> Unit,
) {
    /** Abstracts the single delayed callback so tests can fire the dwell deterministically. */
    interface Scheduler {
        fun postDelayed(delayMs: Long, action: Runnable)
        fun cancel(action: Runnable)
    }

    private var lastX = 0
    private var scheduled = false
    private val advance = Runnable { onDwellElapsed() }

    /** A drag moved to [x] (drag-layer coordinate): arm at an edge, cancel off it. */
    fun onDragMove(x: Int) {
        lastX = x
        if (direction(x) != 0) schedule() else cancel()
    }

    /** The drag ended: drop any pending flip. */
    fun onDragEnd() = cancel()

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        scheduler.postDelayed(dwellMs, advance)
    }

    private fun cancel() {
        if (!scheduled) return
        scheduler.cancel(advance)
        scheduled = false
    }

    private fun onDwellElapsed() {
        scheduled = false
        if (!isDragging()) return
        val dir = direction(lastX)
        if (dir == 0) return
        val target = (currentPage() + dir).coerceIn(0, maxPage())
        if (target != currentPage()) goToPage(target)
        schedule() // keep paging while held at the edge
    }
}

package com.github.reygnn.nyx_launcher.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PageEdgeDirectionTest {
    // pager spans [100, 500], edge band 40px.
    private fun dir(x: Int) = pageEdgeDirection(x, left = 100, right = 500, edgePx = 40)

    @Test fun left_band_is_minus_one() {
        assertThat(dir(100)).isEqualTo(-1)
        assertThat(dir(140)).isEqualTo(-1) // inclusive at left + edge
    }

    @Test fun right_band_is_plus_one() {
        assertThat(dir(500)).isEqualTo(1)
        assertThat(dir(460)).isEqualTo(1) // inclusive at right - edge
    }

    @Test fun middle_is_zero() {
        assertThat(dir(300)).isEqualTo(0)
        assertThat(dir(141)).isEqualTo(0)
        assertThat(dir(459)).isEqualTo(0)
    }
}

class EdgeAdvanceControllerTest {

    /** Fake scheduler: remembers the pending runnable; [fireDwell] simulates the delay elapsing. */
    private class FakeScheduler : EdgeAdvanceController.Scheduler {
        private var pending: Runnable? = null
        var lastDelayMs: Long = -1; private set
        val hasPending get() = pending != null

        override fun postDelayed(delayMs: Long, action: Runnable) {
            lastDelayMs = delayMs
            pending = action
        }

        override fun cancel(action: Runnable) {
            if (pending === action) pending = null
        }

        fun fireDwell() {
            val r = pending ?: error("no pending dwell to fire")
            pending = null
            r.run()
        }
    }

    private val scheduler = FakeScheduler()
    private var dragging = true
    private var direction = 0
    private var page = 0
    private var maxPage = 3

    private val controller = EdgeAdvanceController(
        dwellMs = 500L,
        scheduler = scheduler,
        isDragging = { dragging },
        direction = { direction },
        currentPage = { page },
        maxPage = { maxPage },
        goToPage = { page = it },
    )

    @Test fun dwell_at_right_edge_advances_one_page_and_rearms() {
        direction = 1
        controller.onDragMove(x = 999)
        assertThat(scheduler.hasPending).isTrue()
        assertThat(scheduler.lastDelayMs).isEqualTo(500L)

        scheduler.fireDwell()
        assertThat(page).isEqualTo(1)
        assertThat(scheduler.hasPending).isTrue() // re-armed while held
    }

    @Test fun continuous_hold_pages_repeatedly() {
        direction = 1
        controller.onDragMove(x = 999)
        scheduler.fireDwell()
        scheduler.fireDwell()
        assertThat(page).isEqualTo(2)
    }

    @Test fun does_not_advance_past_the_last_page() {
        direction = 1
        page = 3 // already at maxPage
        controller.onDragMove(x = 999)
        scheduler.fireDwell()
        assertThat(page).isEqualTo(3)
        assertThat(scheduler.hasPending).isTrue() // still re-arms; clamps each time
    }

    @Test fun does_not_advance_before_the_first_page() {
        direction = -1
        page = 0
        controller.onDragMove(x = 0)
        scheduler.fireDwell()
        assertThat(page).isEqualTo(0)
    }

    @Test fun leaving_the_edge_cancels_the_pending_flip() {
        direction = 1
        controller.onDragMove(x = 999)
        assertThat(scheduler.hasPending).isTrue()

        direction = 0
        controller.onDragMove(x = 300) // moved off the edge
        assertThat(scheduler.hasPending).isFalse()
    }

    @Test fun drag_end_cancels_the_pending_flip() {
        direction = 1
        controller.onDragMove(x = 999)
        controller.onDragEnd()
        assertThat(scheduler.hasPending).isFalse()
    }

    @Test fun dwell_firing_after_drag_ended_does_not_advance() {
        direction = 1
        controller.onDragMove(x = 999)
        dragging = false // drag ended between arm and dwell
        scheduler.fireDwell()
        assertThat(page).isEqualTo(0)
    }

    @Test fun move_off_the_edge_never_schedules() {
        direction = 0
        controller.onDragMove(x = 300)
        assertThat(scheduler.hasPending).isFalse()
    }

    @Test fun drag_end_without_a_pending_flip_is_a_no_op() {
        // No prior onDragMove at an edge → nothing scheduled; onDragEnd must not throw.
        controller.onDragEnd()
        assertThat(scheduler.hasPending).isFalse()
    }

    @Test fun re_arming_is_idempotent_while_already_scheduled() {
        direction = 1
        controller.onDragMove(x = 999)
        controller.onDragMove(x = 998) // still in the edge band
        assertThat(scheduler.hasPending).isTrue()
        // Only one pending flip; firing it once advances exactly one page.
        scheduler.fireDwell()
        assertThat(page).isEqualTo(1)
    }
}

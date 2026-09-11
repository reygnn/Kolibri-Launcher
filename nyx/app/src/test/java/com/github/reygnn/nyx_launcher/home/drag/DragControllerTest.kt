package com.github.reygnn.nyx_launcher.home.drag

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.nyx_launcher.home.DragPayload
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * State-machine + hit-test pins for [DragController] (HOME_DRAG_ENGINE_SPEC §9,
 * DRG-INV-2/3). Robolectric only for `android.graphics.Rect` / a throwaway
 * `View`; the controller itself is plain logic behind [DragViewHost].
 */
@RunWith(RobolectricTestRunner::class)
class DragControllerTest {

    private val host = FakeDragViewHost()
    private val controller = DragController(host)
    private val source = View(ApplicationProvider.getApplicationContext<Context>())
    private val payload = DragPayload.Existing(ItemId("a"))

    private fun zone(l: Int, t: Int, r: Int, b: Int, accept: Boolean = true) =
        FakeDropZone(Rect(l, t, r, b), accept)

    private fun start(x: Int = 5, y: Int = 5) = controller.startDrag(payload, source, x, y)

    @Test fun start_marks_dragging_shows_view_and_fires_start() {
        var started = false
        controller.onDragStart = { started = true }
        start()
        assertThat(controller.isDragging).isTrue()
        assertThat(host.added).isEqualTo(1)
        assertThat(started).isTrue()
    }

    @Test fun move_updates_view_and_enters_zone() {
        val z = zone(0, 0, 100, 100)
        controller.addDropZone(z)
        start(x = -50, y = -50) // outside the zone
        controller.onMove(10, 10) // into it
        assertThat(host.moves).isAtLeast(1)
        assertThat(z.entered).isEqualTo(1)
        assertThat(z.exited).isEqualTo(0)
    }

    @Test fun higher_priority_zone_wins_on_overlap() {
        val top = zone(0, 0, 100, 100) // registered first = higher priority
        val bottom = zone(0, 0, 100, 100)
        controller.addDropZone(top)
        controller.addDropZone(bottom)
        start(x = 50, y = 50)
        controller.onDrop(50, 50)
        assertThat(top.dropped).isEqualTo(payload)
        assertThat(bottom.dropped).isNull()
    }

    @Test fun zone_rejecting_payload_is_skipped() {
        val rejecting = zone(0, 0, 100, 100, accept = false)
        val accepting = zone(0, 0, 100, 100)
        controller.addDropZone(rejecting)
        controller.addDropZone(accepting)
        start(x = 50, y = 50)
        controller.onDrop(50, 50)
        assertThat(rejecting.dropped).isNull()
        assertThat(accepting.dropped).isEqualTo(payload)
    }

    @Test fun drop_commits_clears_state_and_leaves_view() {
        val z = zone(0, 0, 100, 100)
        controller.addDropZone(z)
        var ended = false
        var settled = false
        controller.onDragEnd = { ended = true }
        controller.onDropSettle = { settled = true }
        start(x = 50, y = 50)
        controller.onDrop(40, 60)
        assertThat(z.dropped).isEqualTo(payload)
        assertThat(z.dropX).isEqualTo(40)
        assertThat(z.dropY).isEqualTo(60)
        assertThat(controller.isDragging).isFalse()
        assertThat(ended).isTrue()
        assertThat(settled).isTrue()
        assertThat(host.removed).isEqualTo(0) // drop leaves the drag view for the host to settle
    }

    @Test fun cancel_clears_without_drop_and_removes_view() {
        val z = zone(0, 0, 100, 100)
        controller.addDropZone(z)
        var settled = false
        controller.onDropSettle = { settled = true }
        start(x = 50, y = 50) // enters z
        controller.onCancel()
        assertThat(z.dropped).isNull()
        assertThat(controller.isDragging).isFalse()
        assertThat(host.removed).isEqualTo(1)
        assertThat(settled).isFalse() // settle is drop-only
        assertThat(z.exited).isEqualTo(1) // entered at start, exited on cancel
    }

    @Test fun moving_between_zones_exits_old_enters_new() {
        val a = zone(0, 0, 50, 100)
        val b = zone(50, 0, 100, 100)
        controller.addDropZone(a)
        controller.addDropZone(b)
        start(x = 10, y = 10) // in a
        assertThat(a.entered).isEqualTo(1)
        controller.onMove(70, 10) // into b
        assertThat(a.exited).isEqualTo(1)
        assertThat(b.entered).isEqualTo(1)
    }

    @Test fun drop_outside_any_zone_is_a_noop_commit() {
        val z = zone(0, 0, 50, 50)
        controller.addDropZone(z)
        var settled = false
        controller.onDropSettle = { settled = true }
        start(x = 10, y = 10) // in z
        controller.onDrop(999, 999) // outside
        assertThat(z.dropped).isNull()
        assertThat(controller.isDragging).isFalse()
        assertThat(settled).isTrue()
        assertThat(host.removed).isEqualTo(0)
    }

    @Test fun move_before_start_is_ignored() {
        controller.onMove(10, 10)
        assertThat(host.moves).isEqualTo(0)
        assertThat(controller.isDragging).isFalse()
    }
}

private class FakeDragViewHost : DragViewHost {
    var added = 0
    var moves = 0
    var removed = 0
    override fun addDragView(source: View, x: Int, y: Int) { added++ }
    override fun moveDragView(x: Int, y: Int) { moves++ }
    override fun removeDragView() { removed++ }
}

private class FakeDropZone(private val rect: Rect, private val accept: Boolean) : DropZone {
    var entered = 0
    var exited = 0
    var dropped: DragPayload? = null
    var dropX = -1
    var dropY = -1
    override fun hitRect(out: Rect) { out.set(rect) }
    override fun accepts(payload: DragPayload) = accept
    override fun onDragEnter() { entered++ }
    override fun onDragExit() { exited++ }
    override fun onDrop(payload: DragPayload, x: Int, y: Int) {
        dropped = payload
        dropX = x
        dropY = y
    }
}

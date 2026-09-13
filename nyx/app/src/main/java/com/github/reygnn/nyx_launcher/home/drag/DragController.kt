package com.github.reygnn.nyx_launcher.home.drag

import android.graphics.Rect
import android.view.View
import com.github.reygnn.nyx_launcher.home.DragPayload

/**
 * State machine for an in-progress home drag (HOME_DRAG_ENGINE_SPEC §3–§4).
 * Pure orchestration: it owns the payload + current zone, hit-tests a priority
 * ordered list of [DropZone]s by raw coordinates, and drives the [DragLayer]'s
 * drag view. Domain-untouched (DRG-INV-4): a drop just calls the zone's
 * [DropZone.onDrop]; a miss animates nothing and loses nothing (DRG-INV-3).
 *
 * All coordinates are [DragLayer] coordinates.
 */
class DragController(private val host: DragViewHost) {

    private val zones = ArrayList<DropZone>()
    private val tmp = Rect()

    private var payload: DragPayload? = null
    private var currentZone: DropZone? = null

    /** Notified once when a drag starts / ends (e.g. to show/hide the remove bar). */
    var onDragStart: (() -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    /** Notified on every drag move (DragLayer coords) — e.g. for pager edge-advance. */
    var onDragMove: ((x: Int, y: Int) -> Unit)? = null

    /**
     * Notified after a drop is committed, while the drag view is deliberately
     * still shown at the drop point. The host clears it once the drop's re-render
     * lands (or via a fallback), so the icon doesn't flicker back to its old cell
     * during the async model update. Cancel does NOT fire this (it clears at once).
     */
    var onDropSettle: (() -> Unit)? = null

    val isDragging: Boolean get() = payload != null

    /** Registration order is priority (first containing + accepting zone wins). */
    fun addDropZone(zone: DropZone) { zones.add(zone) }
    fun clearDropZones() { zones.clear() }

    fun startDrag(payload: DragPayload, source: View, x: Int, y: Int) {
        if (isDragging) return
        this.payload = payload
        host.addDragView(source, x, y)
        onDragStart?.invoke()
        updateZone(x, y)
    }

    fun onMove(x: Int, y: Int) {
        if (!isDragging) return
        host.moveDragView(x, y)
        updateZone(x, y)
        onDragMove?.invoke(x, y)
    }

    fun onDrop(x: Int, y: Int) {
        val p = payload ?: return
        val zone = findZone(p, x, y)
        endDragState()
        // Leave the drag view where it was dropped; the host removes it when the
        // commit's re-render arrives (see onDropSettle), avoiding the old-cell
        // flicker during the async move/place.
        zone?.onDrop(p, x, y)
        onDropSettle?.invoke()
    }

    fun onCancel() {
        if (!isDragging) return
        endDragState()
        host.removeDragView()
    }

    /** Ends the interactive drag (touch flows normally again) but does not touch
     *  the drag view — [onDrop] leaves it for the host to settle. */
    private fun endDragState() {
        currentZone?.onDragExit()
        currentZone = null
        payload = null
        onDragEnd?.invoke()
    }

    private fun updateZone(x: Int, y: Int) {
        val p = payload ?: return
        val zone = findZone(p, x, y)
        if (zone !== currentZone) {
            currentZone?.onDragExit()
            currentZone = zone
            zone?.onDragEnter()
        }
    }

    private fun findZone(payload: DragPayload, x: Int, y: Int): DropZone? =
        zones.firstOrNull { z ->
            z.accepts(payload) && run { z.hitRect(tmp); tmp.contains(x, y) }
        }
}

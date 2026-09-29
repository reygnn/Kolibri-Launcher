package com.github.reygnn.launcher.common.ui.wallpaperfab

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FabDragHandlerTest {

    private val slop = 8

    @Test
    fun `onDown initialises a non-dragging gesture`() {
        val handler = FabDragHandler(touchSlopPx = slop)
        handler.onDown(rawX = 100f, rawY = 200f)
        assertThat(handler.isDragging).isFalse()
    }

    @Test
    fun `onMove below slop returns null and stays not-dragging`() {
        val handler = FabDragHandler(touchSlopPx = slop)
        handler.onDown(100f, 200f)
        val delta = handler.onMove(rawX = 105f, rawY = 203f)
        assertThat(delta).isNull()
        assertThat(handler.isDragging).isFalse()
    }

    @Test
    fun `onMove past slop returns cumulative delta and flips dragging`() {
        val handler = FabDragHandler(touchSlopPx = slop)
        handler.onDown(100f, 200f)
        val delta = handler.onMove(rawX = 120f, rawY = 220f)
        assertThat(delta).isNotNull()
        assertThat(delta!!.dx).isEqualTo(20f)
        assertThat(delta.dy).isEqualTo(20f)
        assertThat(handler.isDragging).isTrue()
    }

    @Test
    fun `onMove after slop continues to emit deltas even when motion shrinks below slop`() {
        val handler = FabDragHandler(touchSlopPx = slop)
        handler.onDown(0f, 0f)
        handler.onMove(20f, 20f) // crosses slop
        val delta = handler.onMove(rawX = 3f, rawY = 3f) // back near origin
        // Once dragging, every move emits a delta — even small ones.
        assertThat(delta).isNotNull()
        assertThat(delta!!.dx).isEqualTo(3f)
        assertThat(delta.dy).isEqualTo(3f)
        assertThat(handler.isDragging).isTrue()
    }

    @Test
    fun `onUp reports Tap when no drag occurred`() {
        val handler = FabDragHandler(touchSlopPx = slop)
        handler.onDown(0f, 0f)
        handler.onMove(2f, 2f)
        assertThat(handler.onUp()).isEqualTo(FabDragHandler.EndState.Tap)
    }

    @Test
    fun `onUp reports Drag when drag was started`() {
        val handler = FabDragHandler(touchSlopPx = slop)
        handler.onDown(0f, 0f)
        handler.onMove(20f, 20f)
        assertThat(handler.onUp()).isEqualTo(FabDragHandler.EndState.Drag)
    }

    @Test
    fun `consecutive gestures reset dragging state on onDown`() {
        val handler = FabDragHandler(touchSlopPx = slop)
        handler.onDown(0f, 0f)
        handler.onMove(20f, 20f) // drag
        handler.onUp()

        handler.onDown(0f, 0f)
        assertThat(handler.isDragging).isFalse()
        assertThat(handler.onMove(2f, 2f)).isNull()
    }

    @Test
    fun `vertical-only movement past slop flips dragging`() {
        val handler = FabDragHandler(touchSlopPx = slop)
        handler.onDown(0f, 0f)
        val delta = handler.onMove(rawX = 0f, rawY = 20f)
        assertThat(delta).isNotNull()
        assertThat(delta!!.dx).isEqualTo(0f)
        assertThat(delta.dy).isEqualTo(20f)
        assertThat(handler.isDragging).isTrue()
    }

    @Test
    fun `negative deltas are preserved`() {
        val handler = FabDragHandler(touchSlopPx = slop)
        handler.onDown(100f, 100f)
        val delta = handler.onMove(rawX = 50f, rawY = 70f)
        assertThat(delta).isNotNull()
        assertThat(delta!!.dx).isEqualTo(-50f)
        assertThat(delta.dy).isEqualTo(-30f)
    }
}

package com.github.reygnn.kolibri_launcher.ui.home.wallpaperfab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FabDragHandlerTest {

    private val slop = 8

    @Test
    fun `onDown initialises a non-dragging gesture`() {
        val handler = FabDragHandler(touchSlopPx = slop)
        handler.onDown(rawX = 100f, rawY = 200f)
        assertFalse(handler.isDragging)
    }

    @Test
    fun `onMove below slop returns null and stays not-dragging`() {
        val handler = FabDragHandler(touchSlopPx = slop)
        handler.onDown(100f, 200f)
        val delta = handler.onMove(rawX = 105f, rawY = 203f)
        assertNull(delta)
        assertFalse(handler.isDragging)
    }

    @Test
    fun `onMove past slop returns cumulative delta and flips dragging`() {
        val handler = FabDragHandler(touchSlopPx = slop)
        handler.onDown(100f, 200f)
        val delta = handler.onMove(rawX = 120f, rawY = 220f)
        assertNotNull(delta)
        assertEquals(20f, delta!!.dx)
        assertEquals(20f, delta.dy)
        assertTrue(handler.isDragging)
    }

    @Test
    fun `onMove after slop continues to emit deltas even when motion shrinks below slop`() {
        val handler = FabDragHandler(touchSlopPx = slop)
        handler.onDown(0f, 0f)
        handler.onMove(20f, 20f) // crosses slop
        val delta = handler.onMove(rawX = 3f, rawY = 3f) // back near origin
        // Once dragging, every move emits a delta — even small ones.
        assertNotNull(delta)
        assertEquals(3f, delta!!.dx)
        assertEquals(3f, delta.dy)
        assertTrue(handler.isDragging)
    }

    @Test
    fun `onUp reports Tap when no drag occurred`() {
        val handler = FabDragHandler(touchSlopPx = slop)
        handler.onDown(0f, 0f)
        handler.onMove(2f, 2f)
        assertEquals(FabDragHandler.EndState.Tap, handler.onUp())
    }

    @Test
    fun `onUp reports Drag when drag was started`() {
        val handler = FabDragHandler(touchSlopPx = slop)
        handler.onDown(0f, 0f)
        handler.onMove(20f, 20f)
        assertEquals(FabDragHandler.EndState.Drag, handler.onUp())
    }

    @Test
    fun `consecutive gestures reset dragging state on onDown`() {
        val handler = FabDragHandler(touchSlopPx = slop)
        handler.onDown(0f, 0f)
        handler.onMove(20f, 20f) // drag
        handler.onUp()

        handler.onDown(0f, 0f)
        assertFalse(handler.isDragging)
        assertNull(handler.onMove(2f, 2f))
    }

    @Test
    fun `vertical-only movement past slop flips dragging`() {
        val handler = FabDragHandler(touchSlopPx = slop)
        handler.onDown(0f, 0f)
        val delta = handler.onMove(rawX = 0f, rawY = 20f)
        assertNotNull(delta)
        assertEquals(0f, delta!!.dx)
        assertEquals(20f, delta.dy)
        assertTrue(handler.isDragging)
    }

    @Test
    fun `negative deltas are preserved`() {
        val handler = FabDragHandler(touchSlopPx = slop)
        handler.onDown(100f, 100f)
        val delta = handler.onMove(rawX = 50f, rawY = 70f)
        assertNotNull(delta)
        assertEquals(-50f, delta!!.dx)
        assertEquals(-30f, delta.dy)
    }
}

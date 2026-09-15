package com.github.reygnn.launcher.common.ui

import android.os.Bundle
import android.view.View
import android.view.ViewPropertyAnimator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM pins for the shared [DrawerOverlayController]: the intended-open state
 * transitions, the show/hide idempotency guards, the app hooks, and the
 * config-change save/restore. The slide animation itself is View/VPA plumbing
 * (relaxed mock, not asserted); the LOGIC that both hosts rely on is here.
 */
class DrawerOverlayControllerTest {

    private val container = mockk<View>(relaxed = true)
    private var shownCount = 0
    private var lastShownAnimate: Boolean? = null
    private var hiddenCount = 0

    private fun newController() = DrawerOverlayController(
        container = container,
        slideDistancePx = { 1000f },
        slideDurationMs = 180L,
        onShown = { animate -> shownCount++; lastShownAnimate = animate },
        onHidden = { hiddenCount++ },
    )

    @Test fun `starts closed`() {
        assertFalse(newController().isOpen)
    }

    @Test fun `show opens and invokes onShown with the animate flag`() {
        val c = newController()
        c.show()
        assertTrue(c.isOpen)
        assertEquals(1, shownCount)
        assertEquals(true, lastShownAnimate)
    }

    @Test fun `show(animate=false) passes the restore flag through to onShown`() {
        val c = newController()
        c.show(animate = false)
        assertTrue(c.isOpen)
        assertEquals(false, lastShownAnimate)
    }

    @Test fun `show is a no-op when already open`() {
        val c = newController()
        c.show()
        c.show()
        assertEquals("onShown must fire once per logical open", 1, shownCount)
    }

    @Test fun `hide closes and invokes onHidden`() {
        val c = newController()
        c.show()
        c.hide()
        assertFalse(c.isOpen)
        assertEquals(1, hiddenCount)
    }

    @Test fun `hide is a no-op when already closed`() {
        val c = newController()
        c.hide()
        assertEquals(0, hiddenCount)
        assertFalse(c.isOpen)
    }

    @Test fun `restore of a saved-open state normalises the container and reports open`() {
        val saved = mockk<Bundle>(relaxed = true)
        every { saved.getBoolean("drawer_overlay_open", false) } returns true

        assertTrue(newController().restore(saved))
        // Container forced hidden first so a restored view-visibility cannot
        // desync from isOpen.
        verify { container.visibility = View.GONE }
    }

    @Test fun `restore of a saved-closed state or null reports closed`() {
        val saved = mockk<Bundle>(relaxed = true)
        every { saved.getBoolean("drawer_overlay_open", false) } returns false
        assertFalse(newController().restore(saved))
        assertFalse(newController().restore(null))
    }

    @Test fun `onSaveInstanceState persists the open flag`() {
        val out = mockk<Bundle>(relaxed = true)
        val c = newController()
        c.show()
        c.onSaveInstanceState(out)
        verify { out.putBoolean("drawer_overlay_open", true) }
    }

    // Capture the hide animation's withEndAction so the (relaxed-mock) end action
    // can be run deterministically — the cancel-safe visibility hand-off is the
    // whole point of the controller and the relaxed VPA never fires it on its own.
    private fun stubEndActionCapture(sink: MutableList<Runnable>) {
        val animator = mockk<ViewPropertyAnimator>(relaxed = true)
        every { container.animate() } returns animator
        every { animator.translationY(any()) } returns animator
        every { animator.setDuration(any()) } returns animator
        every { animator.setInterpolator(any()) } returns animator
        every { animator.withEndAction(capture(sink)) } returns animator
    }

    @Test fun `a completed hide commits the container to gone`() {
        val endActions = mutableListOf<Runnable>()
        stubEndActionCapture(endActions)

        val c = newController()
        c.show()
        c.hide()
        endActions.last().run() // simulate the slide finishing

        verify { container.visibility = View.GONE }
        verify { container.translationY = 0f }
    }

    @Test fun `a reopen during the hide slide is not clobbered to gone`() {
        val endActions = mutableListOf<Runnable>()
        stubEndActionCapture(endActions)

        val c = newController()
        c.show()
        c.hide()
        c.show() // reopen mid-hide → isOpen true again
        endActions.last().run() // the superseded hide end action fires

        assertTrue("A mid-hide reopen must stay open", c.isOpen)
        verify(exactly = 0) { container.visibility = View.GONE }
    }

    @Test fun `a genuine open from a hidden container starts off-screen`() {
        every { container.visibility } returns View.GONE // not visible
        newController().show()
        verify { container.translationY = 1000f } // slideDistancePx()
    }

    @Test fun `a reopen while still visible keeps the current offset`() {
        every { container.visibility } returns View.VISIBLE // mid-hide, still visible
        newController().show()
        verify(exactly = 0) { container.translationY = 1000f }
    }
}

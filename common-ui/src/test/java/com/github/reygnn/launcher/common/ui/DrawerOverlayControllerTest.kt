package com.github.reygnn.launcher.common.ui

import android.os.Bundle
import android.view.View
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
}

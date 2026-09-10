package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.home.SnapIconResolver
import com.github.reygnn.kolibri_launcher.ui.home.SnapMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SnapIconResolverTest {

    @get:Rule
    val timberRule = TimberRule()

    // ========== MAGNET ==========

    @Test
    fun `resolveMagnet returns on-icon when enabled`() {
        assertEquals(R.drawable.ic_magnet_on, SnapIconResolver.resolveMagnet(true))
    }

    @Test
    fun `resolveMagnet returns off-icon when disabled`() {
        assertEquals(R.drawable.ic_magnet_off, SnapIconResolver.resolveMagnet(false))
    }

    // ========== SNAP MODE (toggle icon) ==========

    @Test
    fun `resolveSnapMode returns rectangle icon for EDGE mode`() {
        assertEquals(R.drawable.ic_rectangle_on, SnapIconResolver.resolveSnapMode(SnapMode.EDGE))
    }

    @Test
    fun `resolveSnapMode returns center icon for CENTER mode`() {
        assertEquals(R.drawable.ic_center_on, SnapIconResolver.resolveSnapMode(SnapMode.CENTER))
    }

    // ========== HORIZONTAL ==========

    @Test
    fun `resolveHorizontal EDGE enabled`() {
        assertEquals(
            R.drawable.ic_horizontal_edge_on,
            SnapIconResolver.resolveHorizontal(enabled = true, mode = SnapMode.EDGE),
        )
    }

    @Test
    fun `resolveHorizontal EDGE disabled`() {
        assertEquals(
            R.drawable.ic_horizontal_edge_off,
            SnapIconResolver.resolveHorizontal(enabled = false, mode = SnapMode.EDGE),
        )
    }

    @Test
    fun `resolveHorizontal CENTER enabled`() {
        assertEquals(
            R.drawable.ic_horizontal_center_on,
            SnapIconResolver.resolveHorizontal(enabled = true, mode = SnapMode.CENTER),
        )
    }

    @Test
    fun `resolveHorizontal CENTER disabled`() {
        assertEquals(
            R.drawable.ic_horizontal_center_off,
            SnapIconResolver.resolveHorizontal(enabled = false, mode = SnapMode.CENTER),
        )
    }

    // ========== VERTICAL ==========

    @Test
    fun `resolveVertical EDGE enabled`() {
        assertEquals(
            R.drawable.ic_vertical_edge_on,
            SnapIconResolver.resolveVertical(enabled = true, mode = SnapMode.EDGE),
        )
    }

    @Test
    fun `resolveVertical EDGE disabled`() {
        assertEquals(
            R.drawable.ic_vertical_edge_off,
            SnapIconResolver.resolveVertical(enabled = false, mode = SnapMode.EDGE),
        )
    }

    @Test
    fun `resolveVertical CENTER enabled`() {
        assertEquals(
            R.drawable.ic_vertical_center_on,
            SnapIconResolver.resolveVertical(enabled = true, mode = SnapMode.CENTER),
        )
    }

    @Test
    fun `resolveVertical CENTER disabled`() {
        assertEquals(
            R.drawable.ic_vertical_center_off,
            SnapIconResolver.resolveVertical(enabled = false, mode = SnapMode.CENTER),
        )
    }

    // ========== EXHAUSTIVENESS GUARD ==========

    @Test
    fun `all SnapMode values are handled by every resolver`() {
        // Fängt ab, falls jemand einen neuen SnapMode-Wert hinzufügt, ohne das
        // when-Mapping zu ergänzen (when expressions werfen dann eine
        // Kotlin-Exception zur Laufzeit, wenn sie nicht exhaustive sind).
        SnapMode.values().forEach { mode ->
            SnapIconResolver.resolveSnapMode(mode)
            SnapIconResolver.resolveHorizontal(true, mode)
            SnapIconResolver.resolveHorizontal(false, mode)
            SnapIconResolver.resolveVertical(true, mode)
            SnapIconResolver.resolveVertical(false, mode)
        }
    }
}

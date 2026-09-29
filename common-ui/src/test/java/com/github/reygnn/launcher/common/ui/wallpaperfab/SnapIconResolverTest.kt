package com.github.reygnn.launcher.common.ui.wallpaperfab

import com.github.reygnn.launcher.common.ui.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SnapIconResolverTest {

    // ========== MAGNET ==========

    @Test
    fun `resolveMagnet returns on-icon when enabled`() {
        assertThat(SnapIconResolver.resolveMagnet(true)).isEqualTo(R.drawable.ic_magnet_on)
    }

    @Test
    fun `resolveMagnet returns off-icon when disabled`() {
        assertThat(SnapIconResolver.resolveMagnet(false)).isEqualTo(R.drawable.ic_magnet_off)
    }

    // ========== SNAP MODE (toggle icon) ==========

    @Test
    fun `resolveSnapMode returns rectangle icon for EDGE mode`() {
        assertThat(SnapIconResolver.resolveSnapMode(SnapMode.EDGE)).isEqualTo(R.drawable.ic_rectangle_on)
    }

    @Test
    fun `resolveSnapMode returns center icon for CENTER mode`() {
        assertThat(SnapIconResolver.resolveSnapMode(SnapMode.CENTER)).isEqualTo(R.drawable.ic_center_on)
    }

    // ========== HORIZONTAL ==========

    @Test
    fun `resolveHorizontal EDGE enabled`() {
        assertThat(SnapIconResolver.resolveHorizontal(enabled = true, mode = SnapMode.EDGE)).isEqualTo(R.drawable.ic_horizontal_edge_on)
    }

    @Test
    fun `resolveHorizontal EDGE disabled`() {
        assertThat(SnapIconResolver.resolveHorizontal(enabled = false, mode = SnapMode.EDGE)).isEqualTo(R.drawable.ic_horizontal_edge_off)
    }

    @Test
    fun `resolveHorizontal CENTER enabled`() {
        assertThat(SnapIconResolver.resolveHorizontal(enabled = true, mode = SnapMode.CENTER)).isEqualTo(R.drawable.ic_horizontal_center_on)
    }

    @Test
    fun `resolveHorizontal CENTER disabled`() {
        assertThat(SnapIconResolver.resolveHorizontal(enabled = false, mode = SnapMode.CENTER)).isEqualTo(R.drawable.ic_horizontal_center_off)
    }

    // ========== VERTICAL ==========

    @Test
    fun `resolveVertical EDGE enabled`() {
        assertThat(SnapIconResolver.resolveVertical(enabled = true, mode = SnapMode.EDGE)).isEqualTo(R.drawable.ic_vertical_edge_on)
    }

    @Test
    fun `resolveVertical EDGE disabled`() {
        assertThat(SnapIconResolver.resolveVertical(enabled = false, mode = SnapMode.EDGE)).isEqualTo(R.drawable.ic_vertical_edge_off)
    }

    @Test
    fun `resolveVertical CENTER enabled`() {
        assertThat(SnapIconResolver.resolveVertical(enabled = true, mode = SnapMode.CENTER)).isEqualTo(R.drawable.ic_vertical_center_on)
    }

    @Test
    fun `resolveVertical CENTER disabled`() {
        assertThat(SnapIconResolver.resolveVertical(enabled = false, mode = SnapMode.CENTER)).isEqualTo(R.drawable.ic_vertical_center_off)
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

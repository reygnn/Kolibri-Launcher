package com.github.reygnn.nyx_launcher.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the load-bearing rotation-lock grid guard: a locked launcher must skip a
 * landscape grid fit (else a transient-landscape cold-start repack scrambles the
 * portrait layout), while every other state fits normally.
 */
class RotationGridPolicyTest {

    @Test
    fun `locked and landscape skips the fit`() {
        assertThat(RotationGridPolicy.shouldSkipGridFit(rotationLocked = true, isLandscape = true)).isTrue()
    }

    @Test
    fun `locked and portrait fits`() {
        assertThat(RotationGridPolicy.shouldSkipGridFit(rotationLocked = true, isLandscape = false)).isFalse()
    }

    @Test
    fun `unlocked and landscape fits`() {
        assertThat(RotationGridPolicy.shouldSkipGridFit(rotationLocked = false, isLandscape = true)).isFalse()
    }

    @Test
    fun `unlocked and portrait fits`() {
        assertThat(RotationGridPolicy.shouldSkipGridFit(rotationLocked = false, isLandscape = false)).isFalse()
    }
}

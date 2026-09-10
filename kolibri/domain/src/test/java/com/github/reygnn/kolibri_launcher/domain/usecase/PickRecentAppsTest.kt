package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Fail-capable guards for [pickRecentApps] (RAL-4 map-only §3.3).
 *
 * The key test feeds a multi-activity package whose **enumeration-first** activity
 * is NOT its **alphabetically-first** one, with `currentApps` unsorted — so the
 * explicit `displayNameLower` tie-break is the only thing that yields the alpha
 * representative. A regression to the old `putIfAbsent` (first-seen) behaviour
 * would pick the enumeration-first activity and turn this red. The masking upstream
 * `applyCustomNames` (still sorting today) is bypassed by testing the pure function
 * directly.
 */
class PickRecentAppsTest {

    // Same package `com.cam`, two launchable activities; input (enumeration) order
    // lists "Zed Cam" first, so first-seen ≠ alphabetically-first.
    private val zedCam = AppInfo("Zed Cam", "Zed Cam", "com.cam", "com.cam.Zed")
    private val alphaCam = AppInfo("Alpha Cam", "Alpha Cam", "com.cam", "com.cam.Alpha")
    private val clock = AppInfo("Clock", "Clock", "com.clock", "com.clock.Main")

    @Test
    fun `multi-activity package is represented by its alphabetically-first activity`() {
        val result = pickRecentApps(
            currentApps = listOf(zedCam, alphaCam, clock), // enumeration-first for com.cam is Zed
            hidden = emptySet(),
            recentPackages = listOf("com.cam"),
            limit = 8,
        )

        // Alpha-first ("Alpha Cam") wins the tie-break, NOT enumeration-first ("Zed Cam").
        assertThat(result.map { it.displayName }).containsExactly("Alpha Cam")
    }

    @Test
    fun `output follows recency order, not input order`() {
        val result = pickRecentApps(
            currentApps = listOf(clock, alphaCam),
            hidden = emptySet(),
            recentPackages = listOf("com.clock", "com.cam"),
            limit = 8,
        )

        assertThat(result.map { it.packageName }).containsExactly("com.clock", "com.cam").inOrder()
    }

    @Test
    fun `hidden components are excluded`() {
        val result = pickRecentApps(
            currentApps = listOf(clock, alphaCam),
            hidden = setOf(alphaCam.componentName),
            recentPackages = listOf("com.cam", "com.clock"),
            limit = 8,
        )

        // com.cam's only visible activity is hidden → package drops out.
        assertThat(result.map { it.packageName }).containsExactly("com.clock")
    }

    @Test
    fun `limit caps the result`() {
        val result = pickRecentApps(
            currentApps = listOf(clock, alphaCam),
            hidden = emptySet(),
            recentPackages = listOf("com.clock", "com.cam"),
            limit = 1,
        )

        assertThat(result).hasSize(1)
        assertThat(result.first().packageName).isEqualTo("com.clock")
    }

    /**
     * The tie-break and the hidden-filter INTERACT: `com.cam` exposes both
     * activities, but its alphabetically-first one ("Alpha Cam") is hidden, so the
     * next visible activity ("Zed Cam") must represent the package. Neither
     * single-axis test above covers this — the tie-break test hides nothing, and
     * the hidden test leaves only one activity per package. A regression that
     * applied the alpha tie-break BEFORE the hidden filter would wrongly drop the
     * package (its representative would be the hidden Alpha) and turn this red.
     */
    @Test
    fun `when the alphabetically-first activity is hidden, the next visible one represents the package`() {
        val result = pickRecentApps(
            currentApps = listOf(zedCam, alphaCam),
            hidden = setOf(alphaCam.componentName),
            recentPackages = listOf("com.cam"),
            limit = 8,
        )

        assertThat(result.map { it.displayName }).containsExactly("Zed Cam")
    }

    /**
     * `limit = 0` is the boundary the caller (`GetRecentAppsUseCase`) guards before
     * ever calling in, but the pure function is documented "total" and used
     * directly in tests, so its zero edge is pinned here: `take(0)` yields empty,
     * never a crash. (A negative limit would throw from `take`, which is why the
     * caller's `limit <= 0` guard — not this function — owns that case.)
     */
    @Test
    fun `a zero limit yields an empty list`() {
        val result = pickRecentApps(
            currentApps = listOf(clock, alphaCam),
            hidden = emptySet(),
            recentPackages = listOf("com.clock", "com.cam"),
            limit = 0,
        )

        assertThat(result).isEmpty()
    }
}

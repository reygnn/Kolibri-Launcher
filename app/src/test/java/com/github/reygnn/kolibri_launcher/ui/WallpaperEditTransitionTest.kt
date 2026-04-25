package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.home.ZoomableImageView
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperEditState
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperEditTransition
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Pure JVM tests for [WallpaperEditTransition.Companion.targetState]. No
 * MockK, no Robolectric, no Android dependencies — same shape as
 * [LayerButtonsStateTest], [SnapIconResolverTest], [ContextMenuResultTest].
 *
 * Each test asserts the *full* target state. Adding a new field to
 * [WallpaperEditState] forces the two `WallpaperEditState(...)` literals
 * below to be updated alongside the two literals inside `targetState`.
 * That compiler pressure is the bug-fang: "new edit-mode field added,
 * exit-reset forgotten" stops compiling, not stops working at runtime.
 */
class WallpaperEditTransitionTest {

    @get:Rule
    val timberRule = TimberRule()

    // ------------------------------------------------------------------------
    // Per-transition target state.
    // ------------------------------------------------------------------------

    @Test
    fun `Enter targets edit-on state with snaps off and dimmed root`() {
        val expected = WallpaperEditState(
            isEditMode = true,
            snapEnabled = false,
            horizontalSnapEnabled = false,
            verticalSnapEnabled = false,
            snapMode = ZoomableImageView.SnapMode.EDGE,
            overlayVisible = true,
            rootLayoutAlpha = 0.7f,
            toolbarAlpha = 1.0f,
            toolbarDockedTop = false,
        )
        assertEquals(expected, WallpaperEditTransition.targetState(WallpaperEditTransition.Enter))
    }

    @Test
    fun `Exit targets edit-off state with snaps on and full root alpha`() {
        val expected = WallpaperEditState(
            isEditMode = false,
            snapEnabled = true,
            horizontalSnapEnabled = true,
            verticalSnapEnabled = true,
            snapMode = ZoomableImageView.SnapMode.EDGE,
            overlayVisible = false,
            rootLayoutAlpha = 1.0f,
            toolbarAlpha = 1.0f,
            toolbarDockedTop = false,
        )
        assertEquals(expected, WallpaperEditTransition.targetState(WallpaperEditTransition.Exit))
    }

    // ------------------------------------------------------------------------
    // forMode mapping contract.
    // ------------------------------------------------------------------------

    @Test
    fun `forMode maps true to Enter and false to Exit`() {
        assertEquals(WallpaperEditTransition.Enter, WallpaperEditTransition.forMode(true))
        assertEquals(WallpaperEditTransition.Exit, WallpaperEditTransition.forMode(false))
    }
}

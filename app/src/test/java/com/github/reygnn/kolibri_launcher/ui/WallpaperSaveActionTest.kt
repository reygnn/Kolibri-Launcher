package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperSaveAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Pure JVM tests for [WallpaperSaveAction.Companion.decide]. No MockK,
 * no Robolectric, no Android dependencies — same shape as
 * [LayerButtonsStateTest], [SnapIconResolverTest],
 * [ContextMenuResultTest], [WallpaperEditTransitionTest].
 */
class WallpaperSaveActionTest {

    @get:Rule
    val timberRule = TimberRule()

    private val sampleAllTransforms = listOf(
        Triple(1.5f, 10f, 20f),
        Triple(2.0f, 30f, 40f),
    )
    private val sampleSingleTransform = Triple(1.25f, 5f, 7f)

    // ------------------------------------------------------------------------
    // Three base branches, one test each.
    // ------------------------------------------------------------------------

    @Test
    fun `decide returns SaveAllLayers when isMultiLayer is true`() {
        val result = WallpaperSaveAction.decide(
            isMultiLayer = true,
            hasWallpaper = true,
            allLayerTransforms = sampleAllTransforms,
            singleTransform = sampleSingleTransform,
        )
        assertEquals(WallpaperSaveAction.SaveAllLayers(sampleAllTransforms), result)
    }

    @Test
    fun `decide returns SaveSingle when not multi-layer but has wallpaper`() {
        val result = WallpaperSaveAction.decide(
            isMultiLayer = false,
            hasWallpaper = true,
            allLayerTransforms = sampleAllTransforms,
            singleTransform = sampleSingleTransform,
        )
        assertEquals(
            WallpaperSaveAction.SaveSingle(scale = 1.25f, translateX = 5f, translateY = 7f),
            result,
        )
    }

    @Test
    fun `decide returns NoOp when neither multi-layer nor wallpaper`() {
        val result = WallpaperSaveAction.decide(
            isMultiLayer = false,
            hasWallpaper = false,
            allLayerTransforms = emptyList(),
            singleTransform = Triple(1f, 0f, 0f),
        )
        assertEquals(WallpaperSaveAction.NoOp, result)
    }

    // ------------------------------------------------------------------------
    // Branch precedence on overlap.
    // ------------------------------------------------------------------------

    @Test
    fun `decide picks SaveAllLayers over SaveSingle when both predicates are true`() {
        // Documents the precedence rule: isMultiLayer wins. The mixed
        // state should not occur in practice (hasWallpaper reads layers
        // when isMultiLayer is true), but precedence is fixed here so it
        // cannot drift if the predicates ever evolve independently.
        val result = WallpaperSaveAction.decide(
            isMultiLayer = true,
            hasWallpaper = true,
            allLayerTransforms = sampleAllTransforms,
            singleTransform = sampleSingleTransform,
        )
        assertEquals(WallpaperSaveAction.SaveAllLayers(sampleAllTransforms), result)
    }

    // ------------------------------------------------------------------------
    // Unused-transform contract.
    // ------------------------------------------------------------------------

    @Test
    fun `decide ignores allLayerTransforms when picking SaveSingle`() {
        val result = WallpaperSaveAction.decide(
            isMultiLayer = false,
            hasWallpaper = true,
            allLayerTransforms = listOf(Triple(99f, 99f, 99f)),  // garbage
            singleTransform = sampleSingleTransform,
        )
        assertEquals(
            WallpaperSaveAction.SaveSingle(scale = 1.25f, translateX = 5f, translateY = 7f),
            result,
        )
    }

    @Test
    fun `decide ignores singleTransform when picking SaveAllLayers`() {
        val result = WallpaperSaveAction.decide(
            isMultiLayer = true,
            hasWallpaper = true,
            allLayerTransforms = sampleAllTransforms,
            singleTransform = Triple(99f, 99f, 99f),  // garbage
        )
        assertEquals(WallpaperSaveAction.SaveAllLayers(sampleAllTransforms), result)
    }
}

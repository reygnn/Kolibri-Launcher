package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [WallpaperViewBinder.remapUpdatesToAddedLayers] — the
 * pure guard that keeps layer property updates aligned with the layers
 * that actually loaded during a [RebuildPlan.FullRebuild].
 *
 * Runs under Robolectric for the same reason as [WallpaperViewDiffTest]:
 * the plan is produced by [WallpaperViewDiff.diff], whose inputs carry
 * real `Uri` instances that can't be `mockk()`'d cleanly.
 *
 * The scenario under test is AUDIT-6 #1: `applyFullRebuild` skips a spec
 * whose bitmap fails to load (`continue`), shifting every following
 * layer's position down. Without the remap, `applyUpdates` would apply
 * `plan.updates[i]` (still indexed against the original spec position) to
 * the wrong view layer — persistent visual corruption of a multi-layer
 * wallpaper. Each layer here carries a distinct translateX so a surviving
 * update (via its transform) can be traced back to the layer it belongs to.
 */
@RunWith(RobolectricTestRunner::class)
class WallpaperViewBinderTest {

    // --- Fixtures ---

    private fun uri(path: String): String = "file://$path"

    private fun layer(id: String, translateX: Float): WallpaperLayerState =
        WallpaperLayerState(
            id = id,
            imageUri = uri("/data/$id.jpg"),
            translateX = translateX,
        )

    /**
     * Builds a [RebuildPlan.FullRebuild] over [layers] by diffing an empty
     * view against a multi-layer target. Gives us realistic, aligned
     * `plan.layers` / `plan.updates` (updates[i].layerIndex == i) without
     * hand-constructing them.
     */
    private fun fullRebuild(vararg layers: WallpaperLayerState): RebuildPlan.FullRebuild {
        val plan = WallpaperViewDiff.diff(
            current = ViewLayerSnapshot.EMPTY,
            target = WallpaperState.multiLayer(layers.toList())
        )
        return plan as RebuildPlan.FullRebuild
    }

    // Distinct translateX so an update can be identified by the layer it came from.
    private val l0 = layer("L0", translateX = 10f)
    private val l1 = layer("L1", translateX = 20f)
    private val l2 = layer("L2", translateX = 30f)
    private val l3 = layer("L3", translateX = 40f)

    // ===========================================
    // FAST PATH — nothing skipped
    // ===========================================

    @Test
    fun `all layers loaded returns updates unchanged`() {
        val plan = fullRebuild(l0, l1, l2, l3)

        val result = WallpaperViewBinder.remapUpdatesToAddedLayers(
            plannedLayers = plan.layers,
            updates = plan.updates,
            addedLayerIds = listOf("L0", "L1", "L2", "L3")
        )

        // Fast path: same instance list, indices 0..3 untouched.
        assertEquals(plan.updates, result)
        assertEquals(listOf(0, 1, 2, 3), result.map { it.layerIndex })
    }

    // ===========================================
    // NON-TERMINAL FAILURE — the actual bug
    // ===========================================

    @Test
    fun `middle layer failing to load reindexes the survivors and drops its update`() {
        val plan = fullRebuild(l0, l1, l2, l3)

        // L1's bitmap failed → view holds [L0, L2, L3] at positions 0,1,2.
        val result = WallpaperViewBinder.remapUpdatesToAddedLayers(
            plannedLayers = plan.layers,
            updates = plan.updates,
            addedLayerIds = listOf("L0", "L2", "L3")
        )

        // L1's update is dropped; L0/L2/L3 map to real positions 0/1/2.
        assertEquals(3, result.size)
        assertEquals(10f to 0, result[0].transform?.translateX to result[0].layerIndex) // L0
        assertEquals(30f to 1, result[1].transform?.translateX to result[1].layerIndex) // L2
        assertEquals(40f to 2, result[2].transform?.translateX to result[2].layerIndex) // L3
    }

    @Test
    fun `first layer failing to load shifts every survivor down by one`() {
        val plan = fullRebuild(l0, l1, l2, l3)

        // L0 failed → view holds [L1, L2, L3] at positions 0,1,2.
        val result = WallpaperViewBinder.remapUpdatesToAddedLayers(
            plannedLayers = plan.layers,
            updates = plan.updates,
            addedLayerIds = listOf("L1", "L2", "L3")
        )

        assertEquals(3, result.size)
        assertEquals(20f to 0, result[0].transform?.translateX to result[0].layerIndex) // L1
        assertEquals(30f to 1, result[1].transform?.translateX to result[1].layerIndex) // L2
        assertEquals(40f to 2, result[2].transform?.translateX to result[2].layerIndex) // L3
    }

    // ===========================================
    // TERMINAL FAILURE — benign, survivors keep positions
    // ===========================================

    @Test
    fun `terminal layer failing to load leaves survivor indices unchanged`() {
        val plan = fullRebuild(l0, l1, l2, l3)

        // Only the last layer failed → [L0, L1, L2] at positions 0,1,2.
        val result = WallpaperViewBinder.remapUpdatesToAddedLayers(
            plannedLayers = plan.layers,
            updates = plan.updates,
            addedLayerIds = listOf("L0", "L1", "L2")
        )

        assertEquals(3, result.size)
        assertEquals(listOf(0, 1, 2), result.map { it.layerIndex })
        assertEquals(listOf(10f, 20f, 30f), result.map { it.transform?.translateX })
    }

    // ===========================================
    // TOTAL FAILURE — empty view
    // ===========================================

    @Test
    fun `all layers failing to load yields no updates`() {
        val plan = fullRebuild(l0, l1, l2, l3)

        val result = WallpaperViewBinder.remapUpdatesToAddedLayers(
            plannedLayers = plan.layers,
            updates = plan.updates,
            addedLayerIds = emptyList()
        )

        assertEquals(emptyList<LayerPropertyUpdate>(), result)
    }
}

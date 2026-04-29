package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import android.net.Uri
import androidx.core.net.toUri
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [WallpaperViewDiff] — the pure-logic heart of the
 * wallpaper rebuild reconciliation.
 *
 * Runs under Robolectric so that `Uri.parse` / `String.toUri()` work on
 * the JVM — same pattern as [com.github.reygnn.kolibri_launcher.data.WallpaperRepositoryImplTest].
 * The diff logic itself is pure Kotlin with no Android dependencies, but
 * its inputs ([WallpaperLayerState] / [WallpaperState]) carry real `Uri`
 * instances, which can't be `mockk()`'d cleanly because several `Uri`
 * methods are `@JvmStatic` / inline and reject stubbing.
 *
 * Highlights:
 *  - [rebuild when layer identities differ but counts match] is the
 *    regression guard for the cancel-bug (delete + add + cancel →
 *    same count but different IDs → must rebuild).
 *  - [rebuild preserves active layer id when it survives] pins the UX
 *    expectation that selections stick around when possible.
 */
@RunWith(RobolectricTestRunner::class)
class WallpaperViewDiffTest {

    // --- Fixtures ---

    private fun uri(path: String): Uri = "file://$path".toUri()

    private fun layer(
        id: String,
        imageUri: Uri? = uri("/data/$id.jpg"),
        scale: Float = 1f,
        translateX: Float = 0f,
        translateY: Float = 0f,
        alpha: Float = 1f,
        blendModeName: String? = null,
        isVisible: Boolean = true
    ): WallpaperLayerState = WallpaperLayerState(
        id = id,
        imageUri = imageUri,
        scale = scale,
        translateX = translateX,
        translateY = translateY,
        alpha = alpha,
        blendModeName = blendModeName,
        isVisible = isVisible,
        label = null
    )

    private fun multiLayer(vararg layers: WallpaperLayerState) =
        WallpaperState.multiLayer(layers.toList())

    private fun snapshot(
        isMulti: Boolean = true,
        ids: List<String> = emptyList(),
        activeId: String? = ids.lastOrNull()
    ) = ViewLayerSnapshot(
        isMultiLayerMode = isMulti,
        layerIds = ids,
        activeLayerId = activeId
    )

    // ===========================================
    // HIDE-ALL CASES
    // ===========================================

    @Test
    fun `empty state yields HideAll`() {
        val plan = WallpaperViewDiff.diff(
            current = snapshot(isMulti = true, ids = listOf("L1")),
            target = WallpaperState.NONE
        )
        assertEquals(RebuildPlan.HideAll, plan)
    }

    @Test
    fun `multi-layer state with no images yields HideAll`() {
        val target = multiLayer(layer("L1", imageUri = null))
        val plan = WallpaperViewDiff.diff(snapshot(ids = emptyList()), target)
        assertEquals(RebuildPlan.HideAll, plan)
    }

    // ===========================================
    // SINGLE-LAYER CASES
    // ===========================================

    @Test
    fun `single-layer untransformed state yields SwitchToSingleLayer without transform`() {
        val target = WallpaperState(imageUri = uri("/data/s.jpg"))
        val plan = WallpaperViewDiff.diff(snapshot(), target)

        assertTrue(plan is RebuildPlan.SwitchToSingleLayer)
        plan as RebuildPlan.SwitchToSingleLayer
        assertNull(plan.transform)
    }

    @Test
    fun `single-layer transformed state carries the transform`() {
        val target = WallpaperState(
            imageUri = uri("/data/s.jpg"),
            scale = 2.5f,
            translateX = -100f,
            translateY = 50f
        )
        val plan = WallpaperViewDiff.diff(snapshot(), target)

        assertTrue(plan is RebuildPlan.SwitchToSingleLayer)
        plan as RebuildPlan.SwitchToSingleLayer
        assertEquals(
            LayerPropertyUpdate.Transform(2.5f, -100f, 50f),
            plan.transform
        )
    }

    // ===========================================
    // MULTI-LAYER: FULL REBUILD CASES
    // ===========================================

    @Test
    fun `rebuild when view is empty and target is multi-layer`() {
        val target = multiLayer(layer("L1"), layer("L2"))
        val plan = WallpaperViewDiff.diff(snapshot(ids = emptyList(), isMulti = false), target)

        assertTrue(plan is RebuildPlan.FullRebuild)
        plan as RebuildPlan.FullRebuild
        assertEquals(listOf("L1", "L2"), plan.layers.map { it.id })
    }

    @Test
    fun `rebuild when view is in single-layer mode but target is multi`() {
        val target = multiLayer(layer("L1"))
        val plan = WallpaperViewDiff.diff(
            current = snapshot(isMulti = false, ids = emptyList()),
            target = target
        )
        assertTrue(plan is RebuildPlan.FullRebuild)
    }

    @Test
    fun `rebuild when layer count differs`() {
        val target = multiLayer(layer("L1"), layer("L2"), layer("L3"))
        val plan = WallpaperViewDiff.diff(
            current = snapshot(ids = listOf("L1", "L2")),
            target = target
        )
        assertTrue(plan is RebuildPlan.FullRebuild)
    }

    @Test
    fun `rebuild when layer identities differ but counts match`() {
        // THE CANCEL-BUG REGRESSION GUARD.
        // Scenario: user had 3 layers [L1, L2, L3], entered edit mode,
        // deleted L1 → view is now [L2, L3], added L4 → view is [L2, L3, L4].
        // User clicks Cancel → state reverts to [L1, L2, L3]. Count is
        // still 3 but identities are completely different at position 0 and 2.
        // Without this guard, the view would keep showing [L2, L3, L4]
        // with transforms from [L1, L2, L3] applied to the wrong slots.
        val target = multiLayer(layer("L1"), layer("L2"), layer("L3"))
        val current = snapshot(ids = listOf("L2", "L3", "L4"), activeId = "L4")

        val plan = WallpaperViewDiff.diff(current, target)

        assertTrue(
            "identity mismatch with same count must still trigger rebuild",
            plan is RebuildPlan.FullRebuild
        )
    }

    @Test
    fun `rebuild when layer order changes but ids are the same set`() {
        // A layer swap produces same layer IDs but different positions.
        // This must trigger a rebuild too — position matters for rendering
        // order (which layer is on top).
        val target = multiLayer(layer("L2"), layer("L1"))
        val current = snapshot(ids = listOf("L1", "L2"))

        val plan = WallpaperViewDiff.diff(current, target)

        assertTrue(plan is RebuildPlan.FullRebuild)
    }

    @Test
    fun `rebuild preserves active layer id when it survives`() {
        val target = multiLayer(layer("L1"), layer("L2"), layer("L3"))
        val current = snapshot(ids = listOf("L2", "L3", "L4"), activeId = "L3")

        val plan = WallpaperViewDiff.diff(current, target)

        plan as RebuildPlan.FullRebuild
        assertEquals("L3", plan.restoreActiveLayerId)
    }

    @Test
    fun `rebuild drops active layer id when it does not survive`() {
        val target = multiLayer(layer("L1"), layer("L2"), layer("L3"))
        // Active was L4, which is not in the target set.
        val current = snapshot(ids = listOf("L2", "L3", "L4"), activeId = "L4")

        val plan = WallpaperViewDiff.diff(current, target)

        plan as RebuildPlan.FullRebuild
        assertNull(
            "active layer id must be dropped when it no longer exists in target",
            plan.restoreActiveLayerId
        )
    }

    @Test
    fun `rebuild carries load specs with correct properties`() {
        val target = multiLayer(
            layer("L1", scale = 2f, translateX = 10f, translateY = 20f, alpha = 0.5f)
        )
        val plan = WallpaperViewDiff.diff(snapshot(ids = emptyList()), target)

        plan as RebuildPlan.FullRebuild
        assertEquals(1, plan.layers.size)
        val spec = plan.layers[0]
        assertEquals("L1", spec.id)
        assertEquals(0.5f, spec.alpha)
        assertEquals(false, spec.centerCrop) // isTransformed → don't center-crop
    }

    @Test
    fun `rebuild untransformed layer requests center-crop`() {
        val target = multiLayer(layer("L1", scale = 1f, translateX = 0f, translateY = 0f))
        val plan = WallpaperViewDiff.diff(snapshot(ids = emptyList()), target)

        plan as RebuildPlan.FullRebuild
        assertEquals(true, plan.layers[0].centerCrop)
    }

    @Test
    fun `rebuild skips layers without imageUri from load specs`() {
        val target = multiLayer(
            layer("L1"),
            layer("L2", imageUri = null),
            layer("L3")
        )
        val plan = WallpaperViewDiff.diff(snapshot(ids = emptyList()), target)

        plan as RebuildPlan.FullRebuild
        assertEquals(
            "layers without imageUri must not appear in load specs",
            listOf("L1", "L3"),
            plan.layers.map { it.id }
        )
    }

    // ===========================================
    // MULTI-LAYER: UPDATE-ONLY CASE
    // ===========================================

    @Test
    fun `identical identities yield UpdatePropertiesOnly`() {
        val target = multiLayer(layer("L1"), layer("L2"))
        val current = snapshot(ids = listOf("L1", "L2"))

        val plan = WallpaperViewDiff.diff(current, target)

        assertTrue(
            "same identities must NOT trigger a rebuild — just update properties",
            plan is RebuildPlan.UpdatePropertiesOnly
        )
    }

    @Test
    fun `UpdatePropertiesOnly carries transforms and properties`() {
        val target = multiLayer(
            layer("L1", scale = 2f, translateX = 10f, translateY = 20f, alpha = 0.7f),
            layer("L2", alpha = 0.3f, isVisible = false)
        )
        val current = snapshot(ids = listOf("L1", "L2"))

        val plan = WallpaperViewDiff.diff(current, target)

        plan as RebuildPlan.UpdatePropertiesOnly
        assertEquals(2, plan.updates.size)

        val u0 = plan.updates[0]
        assertEquals(0, u0.layerIndex)
        assertEquals(
            LayerPropertyUpdate.Transform(2f, 10f, 20f),
            u0.transform
        )
        assertEquals(0.7f, u0.alpha)

        val u1 = plan.updates[1]
        assertNull("untransformed layer → null transform (will center-crop)", u1.transform)
        assertEquals(0.3f, u1.alpha)
        assertEquals(false, u1.isVisible)
    }

    @Test
    fun `UpdatePropertiesOnly skips layers without imageUri`() {
        // Subtle contract: layers with imageUri == null are filtered out
        // BEFORE the identity comparison. The view never sees them in the
        // first place, so when we diff the view against a target state
        // that contains some image-less layers, we compare
        //   view.layerIds  vs  target.layers.filter { it has image }.map { id }
        //
        // Here: view has [L1, L3], target has [L1, L2(null), L3]. After
        // filtering, target-ids-with-image == view-ids == ["L1", "L3"],
        // so identities match → UpdatePropertiesOnly (not a rebuild).
        // This is the correct behavior: nothing needs reloading.
        val target = multiLayer(
            layer("L1"),
            layer("L2", imageUri = null),
            layer("L3")
        )
        val current = snapshot(ids = listOf("L1", "L3"))

        val plan = WallpaperViewDiff.diff(current, target)

        assertTrue(
            "image-less layers are invisible to the diff; matching ids → UpdatePropertiesOnly",
            plan is RebuildPlan.UpdatePropertiesOnly
        )

        plan as RebuildPlan.UpdatePropertiesOnly
        // The updates should only reference the view indices that
        // actually exist (0 and 1), not 0/1/2.
        assertEquals(2, plan.updates.size)
        assertEquals(0, plan.updates[0].layerIndex)
        assertEquals(1, plan.updates[1].layerIndex)
    }

    // ===========================================
    // TRANSITION CASES (single ↔ multi)
    // ===========================================

    @Test
    fun `transition from multi to single yields SwitchToSingleLayer`() {
        val target = WallpaperState(imageUri = uri("/data/s.jpg"))
        val current = snapshot(ids = listOf("L1", "L2"))

        val plan = WallpaperViewDiff.diff(current, target)

        assertTrue(plan is RebuildPlan.SwitchToSingleLayer)
    }

    @Test
    fun `transition from single to empty yields HideAll`() {
        val plan = WallpaperViewDiff.diff(
            current = snapshot(isMulti = false),
            target = WallpaperState.NONE
        )
        assertEquals(RebuildPlan.HideAll, plan)
    }
}
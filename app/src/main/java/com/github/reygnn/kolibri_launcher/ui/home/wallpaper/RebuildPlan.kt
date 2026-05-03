package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import android.graphics.BlendMode
import android.net.Uri
import androidx.core.net.toUri
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.ui.util.toAndroidBlendMode

/**
 * =====================================================================================
 * ARCHITECTURAL NOTE: Why the diff compares layer IDENTITIES, not just counts
 * =====================================================================================
 *
 * The rebuild decision in this diff uses an **identity comparison** (layer IDs in
 * order) rather than a simple **count comparison** (`viewLayerCount == stateLayerCount`).
 * This looks like over-engineering on the surface — comparing lists of strings is more
 * expensive than comparing two integers, and for the common case of "user just tweaked
 * a transform" the count would tell us everything we need. A well-meaning future
 * maintainer might look at this and think: "this is a hot path, why not just compare
 * counts and save the allocation?".
 *
 * DO NOT DO THIS. The count-only comparison was the original implementation, and it
 * caused a production bug where cancelling an edit session left the wallpapers visually
 * mis-assigned. This note exists to document why, so that nobody "optimizes" it back.
 *
 * **The Bug (historical context):**
 * The user had 3 wallpaper layers, entered edit mode, deleted the main wallpaper at
 * position 0, added a new wallpaper (now at position 2 of a 3-layer list), then clicked
 * Cancel. The delegate's transactional edit-session correctly reverted the STATE to the
 * original 3 layers — but the view kept displaying the wrong images with wrong transforms.
 *
 * **Root Cause:**
 * Count-based diffing cannot distinguish these two situations:
 *   - View = [L2, L3, L4]  (3 layers)   ← after delete+add during session
 *   - State = [L1, L2, L3] (3 layers)   ← after cancel restored the snapshot
 *
 * From the count's perspective, both are "3 layers, nothing to rebuild, just reapply
 * transforms per-index". The rebuild was skipped, transforms from L1 landed on view-slot 0
 * (which was actually displaying L2's bitmap), and so on. The user saw scrambled
 * wallpapers. Only reopening the app drawer (which triggered an unrelated view reset)
 * would fix the display.
 *
 * **Why IDs Solve It:**
 * Every [com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState] carries
 * a stable, process-unique ID assigned at creation time. That ID is propagated through
 * to the view-side [com.github.reygnn.kolibri_launcher.ui.home.WallpaperLayer] via
 * the `id` parameter of `ZoomableImageView.addLayer(...)`. By comparing the ordered list
 * of view IDs against the ordered list of target IDs, we detect any substitution,
 * reordering, insertion, or removal — regardless of whether the count changes.
 *
 * **Why This Isn't a Performance Problem:**
 * - The comparison is a pointwise string equality over lists that are almost always
 *   ≤ 4 elements long (typical multi-layer setup).
 * - The alternative — not rebuilding when we should — produces a user-visible bug
 *   that takes significantly longer to reproduce, diagnose, and fix than any savings
 *   in this path would ever recover.
 * - `Kotlin's List.equals` short-circuits on the first mismatch, so the common case
 *   (identical sequence) is still a single pointer-equality pass if the underlying
 *   strings are interned, or O(N*avgStringLen) worst case — trivial.
 *
 * **Regression Guard:**
 * The test `rebuild when layer identities differ but counts match` in
 * [com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperViewDiffTest]
 * pins this behavior explicitly. If someone refactors the diff back to count-only,
 * that test fails loudly with a clear error message pointing to this note.
 *
 * **The Separate "Image-less Layer" Edge Case:**
 * Layers with `imageUri == null` are filtered out of the comparison BEFORE the identity
 * check, because the view never renders them. If the state contains [L1, L2(null), L3]
 * and the view shows [L1, L3], identities match (["L1", "L3"] == ["L1", "L3"]) → no
 * rebuild. This is intentional and covered by
 * `UpdatePropertiesOnly skips layers without imageUri`.
 *
 * @see com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperViewDiffTest
 * @see com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState.id
 */

/**
 * Snapshot of the view-side state, used as input to [WallpaperViewBinder.diff].
 *
 * Intentionally kept as a value type with primitive fields so it can be
 * constructed from the live view at the call site, captured as test
 * fixtures, and compared cheaply.
 *
 * @property isMultiLayerMode whether the view currently renders in multi-layer mode
 * @property layerIds ordered list of stable layer IDs currently in the view
 * @property activeLayerId id of the currently-selected layer, or null if none
 */
data class ViewLayerSnapshot(
    val isMultiLayerMode: Boolean,
    val layerIds: List<String>,
    val activeLayerId: String?
) {
    val layerCount: Int get() = layerIds.size

    companion object {
        val EMPTY = ViewLayerSnapshot(
            isMultiLayerMode = false,
            layerIds = emptyList(),
            activeLayerId = null
        )
    }
}

/**
 * Specification for adding a single layer to the view.
 * Does not contain the Bitmap itself — that gets resolved by the caller
 * via [WallpaperViewBinder.Loader].
 */
data class LayerLoadSpec(
    val id: String,
    val imageUri: Uri,
    val label: String?,
    val centerCrop: Boolean,
    val alpha: Float,
    val blendMode: BlendMode?
) {
    companion object {
        fun fromLayerState(state: WallpaperLayerState): LayerLoadSpec? {
            val uriString = state.imageUri ?: return null
            return LayerLoadSpec(
                id = state.id,
                imageUri = uriString.toUri(),
                label = state.label,
                centerCrop = !state.isTransformed,
                alpha = state.alpha,
                blendMode = state.blendMode?.toAndroidBlendMode()
            )
        }
    }
}

/**
 * An instruction to update a single layer's display properties in-place
 * without reloading its bitmap. Used in the lightweight update path.
 *
 * @property layerIndex position of the target layer in the view
 * @property transform null means "recenter crop", non-null means
 *     "apply this exact scale/translate"
 */
data class LayerPropertyUpdate(
    val layerIndex: Int,
    val transform: Transform?,
    val alpha: Float,
    val blendMode: BlendMode?,
    val isVisible: Boolean
) {
    data class Transform(val scale: Float, val translateX: Float, val translateY: Float)

    companion object {
        fun fromLayerState(index: Int, state: WallpaperLayerState): LayerPropertyUpdate {
            val transform = if (state.isTransformed) {
                Transform(state.scale, state.translateX, state.translateY)
            } else {
                null
            }
            return LayerPropertyUpdate(
                layerIndex = index,
                transform = transform,
                alpha = state.alpha,
                blendMode = state.blendMode?.toAndroidBlendMode(),
                isVisible = state.isVisible
            )
        }
    }
}

/**
 * Plan for how to reconcile a [ViewLayerSnapshot] with a target
 * [WallpaperState]. The [WallpaperViewBinder.apply] step turns a plan
 * into actual view mutations.
 *
 * Four discrete cases:
 *
 *  - [Noop]: view is already in sync, nothing to do.
 *  - [HideAll]: view should be empty (no wallpaper at all).
 *  - [SwitchToSingleLayer]: transition to the legacy single-image mode.
 *  - [UpdatePropertiesOnly]: identities and count match; just reapply
 *      transforms, alpha, blend mode and visibility. No bitmap reload.
 *  - [FullRebuild]: layer identities or count differ; clear the view
 *      and re-add all layers from scratch. [restoreActiveLayerId]
 *      allows preserving the user's selection across the rebuild.
 */
sealed class RebuildPlan {

    object Noop : RebuildPlan()

    object HideAll : RebuildPlan()

    data class SwitchToSingleLayer(
        val imageUri: Uri,
        val transform: LayerPropertyUpdate.Transform?
    ) : RebuildPlan()

    data class UpdatePropertiesOnly(
        val updates: List<LayerPropertyUpdate>
    ) : RebuildPlan()

    data class FullRebuild(
        val layers: List<LayerLoadSpec>,
        val updates: List<LayerPropertyUpdate>,
        val restoreActiveLayerId: String?
    ) : RebuildPlan()
}

/**
 * Entry point for the binder: computes the minimum-work plan to move
 * from [current] to [target].
 *
 * The diff logic is pure — it does no IO, no bitmap decoding and no
 * view mutations. Everything it needs about the view must be captured
 * in [ViewLayerSnapshot] first. This is what makes the binder trivially
 * unit-testable.
 */
object WallpaperViewDiff {

    fun diff(current: ViewLayerSnapshot, target: WallpaperState): RebuildPlan {
        // ── Case 1: no wallpaper at all ──
        if (!target.hasWallpaper) {
            return RebuildPlan.HideAll
        }

        // ── Case 2: single-layer target ──
        if (!target.isMultiLayer) {
            val uriString = target.imageUri ?: return RebuildPlan.HideAll
            val transform = if (target.isTransformed) {
                LayerPropertyUpdate.Transform(
                    target.scale,
                    target.translateX,
                    target.translateY
                )
            } else {
                null
            }
            return RebuildPlan.SwitchToSingleLayer(uriString.toUri(), transform)
        }

        // ── Case 3: multi-layer target ──

        // Empty target layers (shouldn't happen because hasWallpaper would
        // be false, but guard anyway).
        val loadSpecs = target.layers.mapNotNull(LayerLoadSpec::fromLayerState)
        if (loadSpecs.isEmpty()) {
            return RebuildPlan.HideAll
        }

        val targetIds = loadSpecs.map { it.id }
        val identitiesMatch = current.isMultiLayerMode &&
                current.layerIds == targetIds

        val updates = target.layers.withIndex()
            // Keep updates aligned with the load specs — filter out layers
            // that have no image (those don't make it into the view).
            .filter { (_, layer) -> layer.imageUri != null }
            .mapIndexed { viewIndex, (_, layer) ->
                LayerPropertyUpdate.fromLayerState(viewIndex, layer)
            }

        return if (identitiesMatch) {
            RebuildPlan.UpdatePropertiesOnly(updates)
        } else {
            RebuildPlan.FullRebuild(
                layers = loadSpecs,
                updates = updates,
                // Preserve the currently-active layer across the rebuild
                // if (and only if) it survives into the target set.
                restoreActiveLayerId = current.activeLayerId
                    ?.takeIf { it in targetIds }
            )
        }
    }
}
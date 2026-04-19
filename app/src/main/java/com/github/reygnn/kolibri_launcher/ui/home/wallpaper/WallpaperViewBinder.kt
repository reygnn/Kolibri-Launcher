package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.ui.home.ZoomableImageView

/**
 * Binds a [WallpaperState] to a [ZoomableImageView] by computing and
 * applying a [RebuildPlan]. Separated from the Fragment so the
 * reconciliation logic can be unit-tested in isolation
 * (see [WallpaperViewDiff]).
 *
 * == SPLIT OF RESPONSIBILITIES ==
 * - [WallpaperViewDiff] (pure): decides WHAT needs to happen.
 * - [WallpaperViewBinder] (this class): executes the plan against the
 *   actual view. Does bitmap loading, view mutation, layout-post.
 *
 * The binder is stateless. All input comes from the caller; all output
 * goes into the view. It is safe to construct one per Fragment instance
 * and reuse, or to construct per call — the cost is negligible.
 */
class WallpaperViewBinder(
    private val bitmapLoader: BitmapLoader
) {

    /**
     * Abstract bitmap loader so the binder doesn't depend on Android's
     * ContentResolver directly. The Fragment-side implementation should
     * call [android.content.ContentResolver.openInputStream] and
     * [android.graphics.BitmapFactory.decodeStream], returning null on
     * failure.
     */
    fun interface BitmapLoader {
        fun load(uri: Uri): Bitmap?
    }

    /**
     * Compute the diff against the given target state and apply it to
     * the view. This is the single entry point for the binder.
     *
     * @param view the wallpaper view to mutate
     * @param target the desired state after this call returns
     * @param preferredActiveLayerId optional one-shot override. If non-null
     *     AND a rebuild actually happens AND the given id survives into
     *     the target, that layer will be selected as active instead of
     *     the default restore-by-previous-id behavior. Use this to focus
     *     a just-added layer.
     * @param onRebuildComplete optional hook invoked synchronously after
     *     a full rebuild finishes — useful for kicking off a toolbar
     *     refresh in edit mode.
     */
    fun bind(
        view: ZoomableImageView,
        target: WallpaperState,
        preferredActiveLayerId: String? = null,
        onRebuildComplete: (() -> Unit)? = null
    ) {
        val snapshot = view.snapshot()
        val plan = WallpaperViewDiff.diff(snapshot, target)

        // Upgrade a FullRebuild's restoreActiveLayerId when the caller
        // supplied an explicit focus hint. Only if the id actually made
        // it into the target's layers, otherwise ignore (stale hint).
        val effectivePlan = if (
            plan is RebuildPlan.FullRebuild &&
            preferredActiveLayerId != null &&
            plan.layers.any { it.id == preferredActiveLayerId }
        ) {
            plan.copy(restoreActiveLayerId = preferredActiveLayerId)
        } else {
            plan
        }

        apply(view, effectivePlan, onRebuildComplete)
    }

    // ===========================================
    // PLAN APPLICATION
    // ===========================================

    private fun apply(
        view: ZoomableImageView,
        plan: RebuildPlan,
        onRebuildComplete: (() -> Unit)?
    ) {
        when (plan) {
            is RebuildPlan.Noop -> Unit

            is RebuildPlan.HideAll -> applyHideAll(view)

            is RebuildPlan.SwitchToSingleLayer -> applySingleLayer(view, plan, onRebuildComplete)

            is RebuildPlan.UpdatePropertiesOnly -> applyUpdates(view, plan.updates, onRebuildComplete)

            is RebuildPlan.FullRebuild -> applyFullRebuild(view, plan, onRebuildComplete)
        }
    }

    private fun applyHideAll(view: ZoomableImageView) {
        if (view.isMultiLayerMode) {
            view.clearLayers()
        }
        view.setImageDrawable(null)
        view.visibility = View.GONE
    }

    private fun applySingleLayer(
        view: ZoomableImageView,
        plan: RebuildPlan.SwitchToSingleLayer,
        onRebuildComplete: (() -> Unit)?
    ) {
        if (view.isMultiLayerMode) {
            view.clearLayers()
        }
        try {
            view.setImageURI(plan.imageUri)
            view.visibility = View.VISIBLE

            view.post {
                try {
                    val t = plan.transform
                    if (t != null) {
                        view.applyTransform(t.scale, t.translateX, t.translateY)
                    } else {
                        view.centerCrop()
                    }
                    onRebuildComplete?.invoke()
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error applying single-layer transform")
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error loading single-layer wallpaper")
            view.visibility = View.GONE
        }
    }

    private fun applyFullRebuild(
        view: ZoomableImageView,
        plan: RebuildPlan.FullRebuild,
        onRebuildComplete: (() -> Unit)?
    ) {
        view.clearLayers()

        for (spec in plan.layers) {
            try {
                val bitmap = bitmapLoader.load(spec.imageUri) ?: continue
                view.addLayer(
                    bitmap = bitmap,
                    label = spec.label,
                    centerCrop = spec.centerCrop,
                    alpha = spec.alpha,
                    blendMode = spec.blendMode,
                    sourceUri = spec.imageUri,
                    id = spec.id
                )
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error loading layer ${spec.id}")
            }
        }

        view.visibility = if (view.layerCount > 0) View.VISIBLE else View.GONE

        // Restore active selection by ID, fall back to topmost layer
        // (matches the UX expectation that a fresh "add layer" selects
        // the newly-added one).
        if (view.layerCount > 0) {
            val restoredIndex = plan.restoreActiveLayerId?.let { id ->
                (0 until view.layerCount).firstOrNull { view.getLayer(it)?.id == id }
            }
            view.activeLayerIndex = restoredIndex ?: (view.layerCount - 1)
        }

        // Transforms and properties are applied after the view has had a
        // chance to measure — same pattern as the original code.
        applyUpdates(view, plan.updates, onRebuildComplete)
    }

    private fun applyUpdates(
        view: ZoomableImageView,
        updates: List<LayerPropertyUpdate>,
        onRebuildComplete: (() -> Unit)?
    ) {
        view.post {
            try {
                for (update in updates) {
                    if (update.layerIndex >= view.layerCount) break

                    if (update.transform != null) {
                        view.applyTransform(
                            update.layerIndex,
                            update.transform.scale,
                            update.transform.translateX,
                            update.transform.translateY
                        )
                    } else {
                        view.centerCropLayer(update.layerIndex)
                    }

                    view.getLayer(update.layerIndex)?.let { layer ->
                        layer.alpha = update.alpha
                        layer.blendMode = update.blendMode
                        layer.isVisible = update.isVisible
                    }
                }
                view.invalidate()
                onRebuildComplete?.invoke()
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error applying layer updates")
            }
        }
    }
}

/**
 * Extension that captures the view's current state for diffing.
 * Pulled out so tests can construct [ViewLayerSnapshot] directly
 * without instantiating a real view.
 */
fun ZoomableImageView.snapshot(): ViewLayerSnapshot {
    return ViewLayerSnapshot(
        isMultiLayerMode = isMultiLayerMode,
        layerIds = (0 until layerCount).mapNotNull { getLayer(it)?.id },
        activeLayerId = getLayer(activeLayerIndex)?.id
    )
}
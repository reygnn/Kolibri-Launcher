package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import androidx.annotation.VisibleForTesting
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.ui.home.ZoomableImageView
import com.github.reygnn.kolibri_launcher.ui.util.LaunchTrace
import kotlinx.coroutines.CancellationException

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
     * ContentResolver directly. `suspend` because decoding is I/O + CPU and MUST
     * run off the main thread (the Fragment-side impl wraps the
     * openInputStream + bounded decode in `withContext(Dispatchers.IO)`). The
     * binder only calls it for plans that actually load bitmaps (SwitchToSingleLayer
     * / FullRebuild), so a property-only update never touches I/O. Returns null on
     * failure (caller treats null as "skip this layer").
     */
    fun interface BitmapLoader {
        suspend fun load(uri: Uri): DecodedWallpaperBitmap?
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
    suspend fun bind(
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

    private suspend fun apply(
        view: ZoomableImageView,
        plan: RebuildPlan,
        onRebuildComplete: (() -> Unit)?
    ) {
        when (plan) {
            is RebuildPlan.Noop -> Unit

            is RebuildPlan.HideAll -> applyHideAll(view)

            is RebuildPlan.SwitchToSingleLayer -> applySingleLayer(view, plan, onRebuildComplete)

            is RebuildPlan.UpdatePropertiesOnly ->
                applyUpdates(view, plan.updates, onRebuildComplete, revealWhenDone = false)

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

    /**
     * Applies [transform] to the single-layer view, or falls back to the
     * default initial transform when [transform] is `null`.
     *
     * The default policy when no saved transform exists is **center-crop**:
     * the image is scaled to cover the view (`scale = max(viewW/dW, viewH/dH)`)
     * and centered on both axes. This default is intentionally chosen here
     * — historically, the inline form was duplicated across single-layer
     * and per-layer bind paths; this function and its sibling
     * [applyLayerTransformOrDefault] are the single source of truth.
     *
     * If the default ever needs to change (e.g. fit-to-width as a new
     * app-wide default), this is the only place to edit. Note that
     * [ZoomableImageView.centerCrop] itself stays mechanical and unchanged
     * — it is the operation, not the policy.
     */
    private fun applySingleTransformOrDefault(
        view: ZoomableImageView,
        transform: LayerPropertyUpdate.Transform?,
    ) {
        if (transform != null) {
            val scale = compensatedScale(
                transform,
                view.singleSampleSize,
                view.singleOriginalWidth,
                view.singleOriginalHeight,
            )
            view.applyTransform(scale, transform.translateX, transform.translateY)
        } else {
            view.centerCrop()
        }
    }

    /**
     * Resolution-compensates a restored [transform]'s bitmap-absolute scale
     * (WALLPAPER_RENDER_RES_SPEC §3.2/§4-Y): a scale saved against a bitmap
     * downsampled by `S_captured` must be multiplied by `S_render / S_captured`
     * to render identically against the freshly-decoded bitmap (downsampled by
     * [sRender]). `S_captured` comes from the persisted `captureSampleSize`, or
     * — for a legacy field-less transform — is backfilled from the original
     * image dimensions (§7). Translate is view-space and unchanged.
     */
    private fun compensatedScale(
        transform: LayerPropertyUpdate.Transform,
        sRender: Int,
        originalWidth: Int,
        originalHeight: Int,
    ): Float {
        val sCaptured = resolveCaptureSampleSize(
            transform.captureSampleSize,
            originalWidth,
            originalHeight,
        )
        return compensateScaleForSampleSize(transform.scale, sCaptured, sRender)
    }

    /**
     * Per-layer variant of [applySingleTransformOrDefault]. See that
     * function for the rationale and the default-policy contract.
     */
    private fun applyLayerTransformOrDefault(
        view: ZoomableImageView,
        layerIndex: Int,
        transform: LayerPropertyUpdate.Transform?,
    ) {
        if (transform != null) {
            val layer = view.getLayer(layerIndex)
            val scale = if (layer != null) {
                compensatedScale(transform, layer.sampleSize, layer.originalWidth, layer.originalHeight)
            } else {
                transform.scale
            }
            view.applyTransform(
                layerIndex,
                scale,
                transform.translateX,
                transform.translateY,
            )
        } else {
            view.centerCropLayer(layerIndex)
        }
    }

    private suspend fun applySingleLayer(
        view: ZoomableImageView,
        plan: RebuildPlan.SwitchToSingleLayer,
        onRebuildComplete: (() -> Unit)?
    ) {
        if (view.isMultiLayerMode) {
            view.clearLayers()
        }
        // Route through the same bounded [bitmapLoader] as the multi-layer path
        // instead of setImageURI: setImageURI decodes at full resolution, so a
        // huge camera photo (e.g. POCO 108 MP) overruns the Canvas ~100 MB draw
        // limit and crashes ZoomableImageView.onDraw. The loader downsamples.
        val loaded = bitmapLoader.load(plan.imageUri)
        if (loaded == null) {
            view.visibility = View.GONE
            return
        }
        try {
            // Option A (wallpaper rebuild flicker): hide before swapping the
            // bitmap so the pre-transform frame (image at the identity matrix:
            // native 1:1, anchored top-left) is never drawn. Revealed again in
            // the finally below, after the transform. INVISIBLE (not GONE)
            // preserves the measured size so Option B's sync path still fires.
            view.visibility = View.INVISIBLE
            // setWallpaperBitmap (not setImageBitmap): records S_render + the
            // full-res dims so applySingleTransformOrDefault can compensate.
            view.setWallpaperBitmap(
                loaded.bitmap,
                loaded.sampleSize,
                loaded.originalWidth,
                loaded.originalHeight,
            )

            runWhenMeasured(view) {
                try {
                    // Decision: saved transform or default. See
                    // applySingleTransformOrDefault for the policy
                    // (single source of truth, shared with the per-layer
                    // path in applyUpdates below).
                    applySingleTransformOrDefault(view, plan.transform)
                    onRebuildComplete?.invoke()
                } catch (e: Throwable) {
                    // No suspension point: the block is pure view mutation, run
                    // synchronously when measured or as a plain View.post
                    // Runnable otherwise — never from a coroutine suspension.
                    TimberWrapper.silentError(e, "Error applying single-layer transform")
                } finally {
                    // Reveal only now, after the transform attempt: happy path is
                    // already correct (no flicker); on the error path showing the
                    // identity render still beats a permanently blank home.
                    view.visibility = View.VISIBLE
                }
            }
        } catch (e: Throwable) {
            // No suspension point: the suspending bitmapLoader.load call sits
            // ABOVE this try deliberately, so cancellation propagates from
            // there. What remains inside is view mutation plus a View.post.
            TimberWrapper.silentError(e, "Error loading single-layer wallpaper")
            view.visibility = View.GONE
        }
    }

    private suspend fun applyFullRebuild(
        view: ZoomableImageView,
        plan: RebuildPlan.FullRebuild,
        onRebuildComplete: (() -> Unit)?
    ) {
        // Option A (wallpaper rebuild flicker): hide before mutating so the
        // intermediate state — freshly added layers still at the identity matrix
        // (native 1:1, top-left) — is never drawn. Revealed again only after the
        // transforms are applied (in applyUpdates, revealWhenDone = true).
        // INVISIBLE (not GONE) preserves the measured size so Option B's
        // synchronous-when-measured path still fires.
        view.visibility = View.INVISIBLE
        view.clearLayers()

        // Track which specs actually made it into the view. A bitmap that
        // fails to load is skipped (`continue`), which shifts every
        // following layer's position down by one. The updates in
        // plan.updates are indexed against the ORIGINAL spec positions, so
        // they must be remapped onto the surviving layers' real positions
        // before they are applied — see remapUpdatesToAddedLayers.
        val addedLayerIds = ArrayList<String>(plan.layers.size)

        for (spec in plan.layers) {
            try {
                val loaded = bitmapLoader.load(spec.imageUri) ?: continue
                // Traced (jank): addLayer is synchronous Main-thread view
                // mutation — one section per decoded layer. No suspension
                // point inside, so the sync section is balanced on one thread.
                LaunchTrace.section(LaunchTrace.Names.WALLPAPER_ADD_LAYER) {
                    view.addLayer(
                        bitmap = loaded.bitmap,
                        label = spec.label,
                        centerCrop = spec.centerCrop,
                        alpha = spec.alpha,
                        blendMode = spec.blendMode,
                        sourceUri = spec.imageUri,
                        id = spec.id,
                        sampleSize = loaded.sampleSize,
                        originalWidth = loaded.originalWidth,
                        originalHeight = loaded.originalHeight,
                    )
                }
                addedLayerIds.add(spec.id)
            } catch (e: CancellationException) {
                // Rethrow per canonical: bitmapLoader.load is a suspension
                // point (HomeFragment wraps the decode in withContext(IO)),
                // and cancellation is normal control flow on this path — the
                // render job is cancelled by the next wallpaper state
                // (latest-wins) and by onDestroyView. Letting it fall into
                // the Throwable branch below reports a bogus crash AND keeps
                // decoding the remaining layers on behalf of a dead job.
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error loading layer ${spec.id}")
            }
        }

        // Visibility is set later, inside applyUpdates' apply step, AFTER the
        // transforms are applied (Option A) — so a fresh rebuild never flashes
        // the untransformed layers. GONE-when-empty is handled there too.

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
        // chance to measure — same pattern as the original code. The
        // updates are remapped onto the actually-added layers first, so a
        // skipped load can't scramble the property assignment.
        val effectiveUpdates = remapUpdatesToAddedLayers(plan.layers, plan.updates, addedLayerIds)
        applyUpdates(view, effectiveUpdates, onRebuildComplete, revealWhenDone = true)
    }

    private fun applyUpdates(
        view: ZoomableImageView,
        updates: List<LayerPropertyUpdate>,
        onRebuildComplete: (() -> Unit)?,
        revealWhenDone: Boolean,
    ) {
        runWhenMeasured(view) {
            // Traced (jank): the synchronous Main-thread reveal/transform/
            // invalidate that produces the first rebuilt frame. No suspension
            // point (pure view mutation), so the sync section is balanced.
            LaunchTrace.section(LaunchTrace.Names.WALLPAPER_APPLY) {
                try {
                    for (update in updates) {
                        if (update.layerIndex >= view.layerCount) break

                        // Decision: saved transform or default. Shared policy
                        // with the single-layer path; the bounds-check above,
                        // the property setters below, and the single
                        // invalidate() outside this loop stay caller-side
                        // because they are not part of the decision.
                        applyLayerTransformOrDefault(view, update.layerIndex, update.transform)

                        view.getLayer(update.layerIndex)?.let { layer ->
                            layer.alpha = update.alpha
                            layer.blendMode = update.blendMode
                            layer.isVisible = update.isVisible
                        }
                    }
                    view.invalidate()
                    onRebuildComplete?.invoke()
                } catch (e: Throwable) {
                    // No suspension point: the block is pure view mutation, run
                    // synchronously when measured or as a plain View.post Runnable
                    // otherwise — never from a coroutine suspension.
                    TimberWrapper.silentError(e, "Error applying layer updates")
                } finally {
                    // Option A: reveal the view only now, after the transforms are
                    // applied, so a fresh full rebuild never paints the identity
                    // frame. Only the rebuild path reveals (revealWhenDone); a
                    // property-only update leaves visibility untouched as before.
                    // GONE when a rebuild produced zero layers (all loads failed).
                    if (revealWhenDone) {
                        view.visibility = if (view.layerCount > 0) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    /**
     * Runs [block] against [view] once it is measured. Option B of the
     * wallpaper-rebuild-flicker fix: when the view is already laid out (the
     * drawer→home rebuild always is), the transform is applied in the SAME
     * frame, so the intermediate identity-matrix state never reaches the
     * screen. Only a not-yet-measured view (first inflate, width == 0) defers
     * to the next frame via [View.post], where the caller's Option-A reveal
     * keeps that frame blank rather than wrong.
     *
     * [block] MUST contain no coroutine suspension point: it can run
     * synchronously inside a suspending caller (applySingleLayer /
     * applyFullRebuild), so a suspend call here could land a
     * CancellationException in its broad catch. It is pure view mutation today
     * — keep it that way (this file is on the checkConventions cancel_files
     * whitelist).
     */
    private fun runWhenMeasured(view: View, block: () -> Unit) {
        if (view.isLaidOut && view.width > 0 && view.height > 0) {
            block()
        } else {
            view.post { block() }
        }
    }

    companion object {
        /**
         * Remap [updates] — whose `layerIndex` is expressed against the
         * planned layer positions in [plannedLayers] — onto the actual
         * positions of the layers that were successfully added to the
         * view, given by [addedLayerIds] in add order.
         *
         * [WallpaperViewDiff] builds `loadSpecs` (→ [plannedLayers]) and
         * `updates` over the SAME image-bearing layers in the same order,
         * so `updates[i].layerIndex == i` addresses `plannedLayers[i]`.
         * When [applyFullRebuild] skips a spec whose bitmap fails to load,
         * every following layer shifts down while the updates still point
         * at the original indices — the "scrambled wallpaper on load
         * failure" class the [RebuildPlan] identity-diff was written
         * against, re-entered through the load-error path.
         *
         * This rewrites each surviving update's `layerIndex` to its
         * layer's real post-rebuild position (matched by stable id) and
         * drops updates whose layer never made it into the view. Pure and
         * view-free so it can be unit-tested directly.
         */
        @VisibleForTesting
        internal fun remapUpdatesToAddedLayers(
            plannedLayers: List<LayerLoadSpec>,
            updates: List<LayerPropertyUpdate>,
            addedLayerIds: List<String>,
        ): List<LayerPropertyUpdate> {
            // Fast path: every layer loaded, positions already aligned.
            if (addedLayerIds.size == plannedLayers.size) return updates

            val positionById = HashMap<String, Int>(addedLayerIds.size)
            addedLayerIds.forEachIndexed { position, id -> positionById[id] = position }

            return updates.mapNotNull { update ->
                val id = plannedLayers.getOrNull(update.layerIndex)?.id ?: return@mapNotNull null
                val newPosition = positionById[id] ?: return@mapNotNull null
                if (newPosition == update.layerIndex) update else update.copy(layerIndex = newPosition)
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
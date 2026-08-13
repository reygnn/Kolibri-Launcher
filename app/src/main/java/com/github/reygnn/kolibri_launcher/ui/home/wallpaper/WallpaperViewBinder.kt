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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    // maxParallelDecodes is declared BEFORE bitmapLoader on purpose: bitmapLoader is
    // a `fun interface` and callers construct via trailing-lambda SAM
    // (`WallpaperViewBinder { uri -> … }`), which binds to the LAST parameter. Keeping
    // bitmapLoader last preserves every trailing-lambda call site; an Int last would
    // break them. Production never passes it (default = DEFAULT_MAX_PARALLEL_DECODES);
    // tests override the cap via the named first arg
    // (`WallpaperViewBinder(maxParallelDecodes = 2) { uri -> … }`) — named args work
    // regardless of the private visibility.
    private val maxParallelDecodes: Int = DEFAULT_MAX_PARALLEL_DECODES,
    private val bitmapLoader: BitmapLoader,
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

        // Phase 1: decode every layer in parallel, bounded to maxParallelDecodes
        // concurrent decodes. awaitAll preserves input order — decoded[i] is the
        // result for plan.layers[i] regardless of which decode finished first — so
        // the add phase below rebuilds z-order exactly. Each decode isolates its own
        // cancellation rethrow and its own failure-to-value catch (decodeLayer), so
        // one bad layer never cancels the scope, while a supersede/onDestroyView
        // cancellation propagates out of awaitAll to the caller (latest-wins). The
        // semaphore is per-render: it bounds THIS render to maxParallelDecodes, not
        // the whole system — a rapid supersede can briefly overlap up to 2×cap
        // in-flight decodes (WALLPAPER_PARALLEL_DECODE_SPEC §5).
        val decoded: List<LayerDecodeResult> = coroutineScope {
            val gate = Semaphore(maxParallelDecodes)
            plan.layers
                .map { spec -> async { gate.withPermit { decodeLayer(spec) } } }
                .awaitAll()
        }

        // Phase 2: add the surviving layers to the view IN PLAN ORDER, on the
        // caller's Main context. Pure synchronous view mutation, no suspension point
        // — z-order = plan order because we iterate the index-ordered results, not
        // completion order. A skipped layer (null bitmap or Failed) shifts every
        // following layer's position down by one, exactly as the old `continue` did;
        // the updates in plan.updates are indexed against the ORIGINAL spec
        // positions, so they are remapped onto the surviving layers' real positions
        // afterwards — see remapUpdatesToAddedLayers.
        val addedLayerIds = ArrayList<String>(plan.layers.size)
        for (result in decoded) {
            when (result) {
                is LayerDecodeResult.Loaded -> {
                    // Loader returned null: it already logged (loadBitmapFromUri owns
                    // that report), so skip silently — matches the old `?: continue`.
                    val loaded = result.bitmap ?: continue
                    // Traced (jank): addLayer is synchronous Main-thread view
                    // mutation — one section per decoded layer. No suspension
                    // point inside, so the sync section is balanced on one thread.
                    LaunchTrace.section(LaunchTrace.Names.WALLPAPER_ADD_LAYER) {
                        view.addLayer(
                            bitmap = loaded.bitmap,
                            label = result.spec.label,
                            centerCrop = result.spec.centerCrop,
                            alpha = result.spec.alpha,
                            blendMode = result.spec.blendMode,
                            sourceUri = result.spec.imageUri,
                            id = result.spec.id,
                            sampleSize = loaded.sampleSize,
                            originalWidth = loaded.originalWidth,
                            originalHeight = loaded.originalHeight,
                        )
                    }
                    addedLayerIds.add(result.spec.id)
                }
                // Loader threw a real (non-cancellation) error: report once and skip
                // the layer. Logged here in the single-threaded Phase 2 (not inside
                // the async) so reports stay deterministic in plan order and a
                // DEBUG-only silentError throw (Rule 9) cannot escape a decode
                // coroutine and cancel its siblings across threads.
                is LayerDecodeResult.Failed ->
                    TimberWrapper.silentError(result.error, "Error loading layer ${result.spec.id}")
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

    /**
     * Decode a single layer, converting every outcome into a [LayerDecodeResult]
     * value so [applyFullRebuild]'s parallel decode phase can collect results
     * without one layer's failure tearing down the others.
     *
     * - `load` returning `null` → [LayerDecodeResult.Loaded] with a null bitmap; the
     *   loader already reported it, so the add phase skips it silently.
     * - `load` throwing a real error → [LayerDecodeResult.Failed]; the add phase logs
     *   it once (in single-threaded Phase 2, not here) and skips the layer.
     * - `CancellationException` → rethrown, never collapsed into [LayerDecodeResult.Failed]:
     *   `bitmapLoader.load` is the suspension point (HomeFragment wraps the decode in
     *   `withContext(IO)`), and cancellation is normal control flow here — a
     *   latest-wins wallpaper switch or `onDestroyView` cancels the render. Swallowing
     *   it would file a bogus ACRA report and keep decoding for a dead job. This is
     *   the file's `cancel_files` contract (Rule 11 cancellation-rethrow linter): the
     *   broad `catch (Throwable)` sits behind this `CancellationException`-first arm.
     */
    private suspend fun decodeLayer(spec: LayerLoadSpec): LayerDecodeResult =
        try {
            LayerDecodeResult.Loaded(spec, bitmapLoader.load(spec.imageUri))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LayerDecodeResult.Failed(spec, e)
        }

    /**
     * Outcome of one layer decode in [applyFullRebuild]'s parallel decode phase.
     * [Loaded] carries the (possibly null) bitmap; [Failed] carries the error so the
     * add phase can report it once. Cancellation is never modelled here — it is
     * rethrown out of [decodeLayer] instead.
     */
    private sealed interface LayerDecodeResult {
        data class Loaded(val spec: LayerLoadSpec, val bitmap: DecodedWallpaperBitmap?) : LayerDecodeResult
        data class Failed(val spec: LayerLoadSpec, val error: Throwable) : LayerDecodeResult
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
         * Default upper bound on concurrent layer decodes in [applyFullRebuild]'s
         * parallel decode phase. BitmapFactory decode is CPU-heavy; capping at 4
         * (≈ core count on the target device class) keeps the CPU from
         * oversubscribing and bounds the concurrent decode-memory transient. The
         * measured 4-layer case never hits the cap; it is the insurance against the
         * high-layer-count re-eval case (WALLPAPER_PARALLEL_DECODE_SPEC §4.5).
         */
        const val DEFAULT_MAX_PARALLEL_DECODES = 4

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
package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

/**
 * Three-way decision for "what to save when wallpaper edit mode commits or
 * a layer-operation needs to flush in-flight transforms":
 *  - all layer transforms (multi-layer mode),
 *  - one single transform (single-layer mode with a wallpaper),
 *  - nothing (no wallpaper at all).
 *
 * Pattern matches the existing pure-logic extracts (LayerButtonsState,
 * SnapIconResolver, ContextMenuResult, WallpaperEditTransition): this
 * class makes the *decision* — which save action applies — and the
 * Fragment (and its delegate) carries out the *side effect* of the save.
 *
 * Surfaced during a post-phase walkthrough after the original three
 * planned extracts were done. Two callers in HomeFragment share this
 * decision shape but differ in how they compute the `isMultiLayer`
 * input (race-guard at the layer-op caller, plain state read at the
 * save-button caller). That asymmetry is intentional and lives at the
 * call sites — this class does not know about it. Keeps the class dumb,
 * the callers explicit.
 */
/**
 * A view-read transform plus the decode downsample factor the bitmap it was
 * measured against is currently at (WALLPAPER_RENDER_RES_SPEC §4-Y). [sampleSize]
 * (S_render) is persisted as the layer's `captureSampleSize` so a later
 * render-budget change can compensate the bitmap-absolute [scale].
 */
data class LayerTransform(
    val scale: Float,
    val translateX: Float,
    val translateY: Float,
    val sampleSize: Int,
)

sealed interface WallpaperSaveAction {

    /**
     * Save all layer transforms. Picked when the effective multi-layer
     * input is `true`, regardless of `hasWallpaper`.
     */
    data class SaveAllLayers(
        val transforms: List<LayerTransform>,
    ) : WallpaperSaveAction

    /**
     * Save the single-layer transform. Picked when not multi-layer and a
     * wallpaper is present.
     */
    data class SaveSingle(
        val scale: Float,
        val translateX: Float,
        val translateY: Float,
        val sampleSize: Int,
    ) : WallpaperSaveAction

    /**
     * Save nothing. Picked when neither multi-layer nor a single-layer
     * wallpaper is present. The caller may still need to follow up with
     * a commit on the edit session — that lives outside this decision.
     */
    data object NoOp : WallpaperSaveAction

    companion object {
        /**
         * Decides which save action applies given the two state-side
         * predicates and both pre-extracted transform variants from the
         * view. The unused transform variant for the chosen branch is
         * ignored — callers compute both up-front because the read cost
         * is trivial (`List.getOrNull` plus a few Float reads) and a
         * branch-free call site is simpler than wiring lambdas.
         *
         * Branch precedence on overlap: when `isMultiLayer == true`,
         * [SaveAllLayers] wins regardless of `hasWallpaper`. The mixed
         * state should not occur in practice (the `hasWallpaper` getter
         * reads from `layers` when `isMultiLayer` is true), but the
         * precedence is fixed so it cannot drift.
         */
        fun decide(
            isMultiLayer: Boolean,
            hasWallpaper: Boolean,
            allLayerTransforms: List<LayerTransform>,
            singleTransform: LayerTransform,
        ): WallpaperSaveAction = when {
            isMultiLayer -> SaveAllLayers(allLayerTransforms)
            hasWallpaper -> SaveSingle(
                scale = singleTransform.scale,
                translateX = singleTransform.translateX,
                translateY = singleTransform.translateY,
                sampleSize = singleTransform.sampleSize,
            )
            else -> NoOp
        }
    }
}

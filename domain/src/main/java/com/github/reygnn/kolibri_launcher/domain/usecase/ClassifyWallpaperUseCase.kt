package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.CompositeLuminanceSignal
import com.github.reygnn.kolibri_launcher.core.SystemWallpaperColorsSignal
import com.github.reygnn.kolibri_launcher.domain.model.LuminanceClassification
import com.github.reygnn.kolibri_launcher.domain.model.DomainWallpaperColors
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperBitmapLuminance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Decides whether the AppDrawer should use its LIGHT or DARK surface
 * by inspecting the perceived background. Two signal sources:
 *
 * 1. **Kolibri-internal wallpaper** (preferred when present and
 *    visually dominant). Single-layer: classify the only image.
 *    Multi-layer: classify the flattened **composite** — its median
 *    luminance is sampled during the warm's flatten and delivered via
 *    [CompositeLuminanceSignal] (v4.3; the composite lives only in the
 *    in-memory display cache, so a `:domain` use case reaches it through
 *    this IoC signal, not a file). Until the warm emits (cold start, or
 *    just after an edit), it falls back to `layers[0]` (the bottom-most
 *    layer, painted first) "only when opaque enough to dominate
 *    perception". See `WALLPAPER_COMPOSITE_LIFECYCLE_SPEC` §5 and
 *    `ACCEPTED_LIMITATIONS.md` #1 (closed again in v4.3, with transient
 *    residuals #1a/#1b). Two cooperating gates decide the fallback's
 *    "opaque enough":
 *
 *    - **Layer-level alpha gate** (this use case): the persisted
 *      `WallpaperLayerState.alpha` ≥ [DOMINANT_ALPHA_THRESHOLD]
 *      **and** Normal blend mode (no `blendModeName`).
 *    - **Pixel-level coverage gate**
 *      (`WallpaperBitmapLuminanceImpl`): the bitmap itself must
 *      have a high enough fraction of effectively-opaque pixels
 *      (alpha ≥ 80%) to dominate. AMOLED-converted wallpapers
 *      typically have 10..20% coverage and fall through this gate;
 *      fully-opaque images have ~100% and pass. The impl signals
 *      "not visually dominant" by returning `null` from
 *      `compute()`, indistinguishable from a load failure.
 *
 *    When either gate fails, the classifier falls through to the
 *    system signal — that's what the user actually sees through the
 *    transparent or low-coverage Kolibri layer.
 *
 *    The bottom-layer soft spots — a transparent `layers[0]` over an
 *    opaque higher layer, or an opaque `layers[0]` with a non-Normal
 *    blend (e.g. MULTIPLY at alpha 1.0) — are exactly what the composite
 *    luminance resolves once the warm has emitted; they apply only during
 *    the brief fallback window (`ACCEPTED_LIMITATIONS.md` #1a/#1b).
 *
 * 2. **System-wallpaper `colorHints`** via
 *    [SystemWallpaperColorsSignal]. The OS publishes
 *    `HINT_SUPPORTS_DARK_TEXT` per system wallpaper as a stable
 *    boolean — same signal the homescreen text-colour pipeline
 *    already uses.
 *
 * If both signals are unavailable (no Kolibri wallpaper AND no
 * system colour hints — rare; a custom wallpaper implementation
 * that returns `null` from `getWallpaperColors`) the classifier
 * defaults to [LuminanceClassification.DARK], matching the
 * pre-feature behaviour.
 *
 * **Hysteresis is deliberately omitted.** Kolibri-internal
 * wallpapers are pure-static data classes (URI + transforms; no
 * animation, no sensor, no time-of-day), and `colorHints` is OS-
 * stable per system wallpaper. Neither input flaps on its own, so
 * a deadband would be cosmetic. **Re-evaluation trigger:**
 * introduce hysteresis once dynamic Kolibri-internal layers are
 * supported (sensor parallax, time-of-day swaps, etc.).
 */
class ClassifyWallpaperUseCase @Inject constructor(
    private val observeWallpaperStateUseCase: ObserveWallpaperStateUseCase,
    private val systemWallpaperColorsSignal: SystemWallpaperColorsSignal,
    private val wallpaperBitmapLuminance: WallpaperBitmapLuminance,
    private val compositeLuminanceSignal: CompositeLuminanceSignal,
) {

    operator fun invoke(): Flow<LuminanceClassification> {
        // Three signals; the final combine picks Kolibri-internal when present, else system,
        // else DARK. The Kolibri-internal signal combines the wallpaper state with the composite
        // luminance (v4.3): for a multi-layer state we classify the COMPOSITE's luminance (pushed
        // by the warm through CompositeLuminanceSignal — the resolved composition, closing
        // ACCEPTED_LIMITATIONS #1), falling back to the layers[0] heuristic only until the warm
        // emits (#1a cold-start, #1b eventual consistency). Single-layer classifies its image.
        //
        // Project to a small deduped input BEFORE the decode (AUDIT-19 F4): the bottom-layer /
        // single-image luminance depends only on its URI, not on scale/translate, so a pan/zoom
        // save (distinct state, same URI) must not re-run the decode. The composite path takes no
        // decode at all — it reads the pre-computed luminance from the signal.
        val kolibriInternalFlow: Flow<LuminanceClassification?> =
            combine(
                observeWallpaperStateUseCase().map { projectKolibriInput(it) }.distinctUntilChanged(),
                compositeLuminanceSignal.luminance,
            ) { input, compositeLum ->
                when (input) {
                    KolibriInput.None -> null
                    is KolibriInput.Single -> classifyDominant(input.uri)
                    is KolibriInput.Multi ->
                        compositeLum?.let { classifyByLuminance(it) }
                            ?: input.fallbackUri?.let { classifyDominant(it) }
                }
            }

        val systemFlow: Flow<LuminanceClassification?> =
            systemWallpaperColorsSignal.colors
                .map { it?.let(::classifySystem) }

        return combine(kolibriInternalFlow, systemFlow) { kolibri, system ->
            kolibri ?: system ?: LuminanceClassification.DARK
        }
    }

    private suspend fun classifyDominant(uri: String): LuminanceClassification? {
        val luminance = wallpaperBitmapLuminance.compute(uri) ?: return null
        return classifyByLuminance(luminance)
    }

    /**
     * The deduped classification input for a state. Multi-layer carries the bottom-layer
     * FALLBACK URI (used only until the composite luminance arrives); the composite itself is
     * classified from [CompositeLuminanceSignal], not from here.
     */
    private sealed interface KolibriInput {
        data object None : KolibriInput
        data class Single(val uri: String) : KolibriInput
        data class Multi(val fallbackUri: String?) : KolibriInput
    }

    private fun projectKolibriInput(state: WallpaperState): KolibriInput {
        if (state.isMultiLayer) {
            // Bottom-most layer (painted first) behind the alpha + Normal-blend gates — anything
            // below either bar means the system wallpaper bleeds through. This is only the
            // FALLBACK while the composite luminance is not yet available (v4.3).
            val bottom = state.layers.firstOrNull() ?: return KolibriInput.Multi(null)
            val fallback =
                if (bottom.alpha < DOMINANT_ALPHA_THRESHOLD || bottom.blendModeName != null) null
                else bottom.imageUri
            return KolibriInput.Multi(fallback)
        }
        return state.imageUri?.let { KolibriInput.Single(it) } ?: KolibriInput.None
    }

    private fun classifySystem(
        colors: DomainWallpaperColors,
    ): LuminanceClassification =
        if (colors.supportsDarkText) LuminanceClassification.LIGHT
        else LuminanceClassification.DARK

    private fun classifyByLuminance(luminance: Float): LuminanceClassification =
        if (luminance > LUMINANCE_THRESHOLD) LuminanceClassification.LIGHT
        else LuminanceClassification.DARK

    companion object {
        /**
         * Threshold above which `layers[0]` is "opaque enough" to
         * dominate the perceived background. Anything below means
         * the system wallpaper visibly bleeds through.
         */
        const val DOMINANT_ALPHA_THRESHOLD: Float = 0.8f

        /** WCAG midpoint, strict greater-than (matches `ResolvedBackground`). */
        const val LUMINANCE_THRESHOLD: Float = 0.5f
    }
}

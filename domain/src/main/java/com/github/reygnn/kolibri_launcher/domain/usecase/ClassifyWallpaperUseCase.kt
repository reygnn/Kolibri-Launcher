package com.github.reygnn.kolibri_launcher.domain.usecase

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
 *    Multi-layer: classify `layers[0]` (the bottom-most layer in
 *    the render order, painted first), but only when it is "opaque
 *    enough to dominate perception". Two cooperating gates decide
 *    that:
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
 *    Edge case (documented limitation): an opaque layer with a
 *    non-Normal blend mode (e.g. MULTIPLY at alpha 1.0 over a
 *    bright image) falls through to the system signal as well —
 *    the resolved composition isn't approximated. Keeping the
 *    fall-through branch deterministic and cheap is preferable to
 *    a half-correct composite renderer; see
 *    `ACCEPTED_LIMITATIONS.md` §2.
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
) {

    operator fun invoke(): Flow<LuminanceClassification> {
        // Kolibri-internal classification only changes when the
        // wallpaper state changes; computing the bitmap luminance on
        // every system-colour emission too would be wasteful. Each
        // upstream feeds an independent intermediate flow; the final
        // combine just picks Kolibri-internal when present, else
        // system, else fallback.
        //
        // Project to the dominant URI BEFORE dedup (AUDIT-19 F4): the
        // luminance depends only on that URI, not on the layer's
        // scale/translate. Dedup'ing the whole WallpaperState would
        // re-run the bitmap decode on every pan/zoom save (a distinct
        // state, same URI); dedup'ing the projected URI skips it.
        val kolibriInternalFlow: Flow<LuminanceClassification?> =
            observeWallpaperStateUseCase()
                .map { pickDominantUri(it) }
                .distinctUntilChanged()
                .map { uri -> uri?.let { classifyDominant(it) } }

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
     * Returns the URI whose pixels we should sample, or `null` if
     * Kolibri-internal isn't visually dominant for this state.
     */
    private fun pickDominantUri(state: WallpaperState): String? {
        if (state.isMultiLayer) {
            val bottom = state.layers.firstOrNull() ?: return null
            // Alpha-gate + Normal blend: anything below either bar
            // means the system wallpaper bleeds through and should
            // drive classification instead.
            if (bottom.alpha < DOMINANT_ALPHA_THRESHOLD) return null
            if (bottom.blendModeName != null) return null
            return bottom.imageUri
        }
        return state.imageUri
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

package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.ColorMath
import com.github.reygnn.kolibri_launcher.core.coerceInSafe
import com.github.reygnn.kolibri_launcher.domain.model.LuminanceClassification
import com.github.reygnn.kolibri_launcher.domain.model.DomainWallpaperColors
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

class ObserveUiColorsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val classifyWallpaperUseCase: ClassifyWallpaperUseCase,
) {
    /**
     * Kombiniert alle Farb-Einstellungen zu einem einzigen UiColorsState-Flow.
     *
     * `smart_contrast` mode reads its binary BLACK/WHITE choice from
     * [ClassifyWallpaperUseCase], which considers Kolibri-internal
     * layer wallpapers AND the system-wallpaper `colorHints` — same
     * signal that drives the AppDrawer's AUTO surface mode. So the
     * homescreen text colour follows whatever the user actually
     * perceives as the background, not just the system wallpaper.
     *
     * `adaptive_colors` mode keeps reading `secondaryColorArgb` from
     * the system wallpaper directly. Kolibri-internal layers are not
     * sampled for a "secondary tint" — that would require palette
     * extraction and a tinting heuristic. Out of scope here.
     *
     * The user's manual text-colour override (`textColorFlow != 0`)
     * still beats both modes — unchanged.
     *
     * @param wallpaperColorsFlow A flow of system-wallpaper colour
     *   hints (still consumed by `adaptive_colors`). Today fed by
     *   `ThemingDelegate` polling on resume; the listener-driven
     *   `SystemWallpaperColorsSignal` is a strict superset and could
     *   replace this in a follow-up refactor — out of scope here.
     */
    operator fun invoke(
        wallpaperColorsFlow: Flow<DomainWallpaperColors?>
    ): Flow<UiColorsState> {
        // Pre-bundle the two external signals (wallpaper colours +
        // perceived-background classification) so the main combine
        // stays at 5 inputs and doesn't have to resort to the vararg
        // overload.
        val signalsFlow: Flow<Signals> =
            combine(wallpaperColorsFlow, classifyWallpaperUseCase()) { colors, classification ->
                Signals(colors, classification)
            }

        return combine(
            settingsRepository.textColorFlow,
            settingsRepository.textShadowEnabledFlow,
            settingsRepository.chipBackgroundColorFlow,
            settingsRepository.readabilityModeFlow,
            signalsFlow,
        ) { userColor, shadowEnabled, chipColor, readabilityMode, signals ->
            // Pure Color-Math und Bitops — kann nicht werfen. Ein
            // Programmierfehler propagiert zum Consumer (BaseViewModel
            // mit launchSafe). Frühere Throwable-Catches hier waren
            // dead, weil weder die `when`-Branches noch
            // `calculateTonalShadowColor` werfen.
            val finalTextColor = if (userColor != 0) {
                userColor
            } else {
                when (readabilityMode) {
                    "smart_contrast" -> {
                        // Driven by ClassifyWallpaperUseCase — Kolibri-
                        // internal layers AND system colorHints, in the
                        // priority order documented on that use case.
                        if (signals.classification == LuminanceClassification.LIGHT) {
                            ColorMath.BLACK
                        } else {
                            ColorMath.WHITE
                        }
                    }
                    "adaptive_colors" ->
                        signals.colors?.secondaryColorArgb ?: ColorMath.WHITE
                    else -> ColorMath.WHITE
                }
            }

            val finalShadowColor = if (shadowEnabled) {
                calculateTonalShadowColor(finalTextColor)
            } else {
                ColorMath.TRANSPARENT
            }

            UiColorsState(
                textColor = finalTextColor,
                shadowColor = finalShadowColor,
                chipBackgroundColor = chipColor
            )
        }
    }

    private fun calculateTonalShadowColor(baseColor: Int): Int {
        val luminance = ColorMath.calculateLuminance(baseColor)
        fun lerp(start: Double, stop: Double, fraction: Double): Double {
            return (start + fraction * (stop - start)).coerceInSafe(0.0, 1.0)
        }
        return when {
            luminance < 0.1 -> ColorMath.argb(204, 255, 255, 255)
            luminance < 0.5 -> {
                val fraction = ((luminance - 0.1) / 0.4).coerceInSafe(0.0, 1.0)
                val alpha = lerp(0.75, 0.4, fraction)
                ColorMath.argb((alpha * 255).toInt().coerceInSafe(0, 255), 255, 255, 255)
            }
            luminance < 0.9 -> {
                val fraction = ((luminance - 0.5) / 0.4).coerceInSafe(0.0, 1.0)
                val alpha = lerp(0.3, 0.6, fraction)
                ColorMath.argb((alpha * 255).toInt().coerceInSafe(0, 255), 0, 0, 0)
            }
            else -> ColorMath.argb(153, 0, 0, 0)
        }
    }

    private data class Signals(
        val colors: DomainWallpaperColors?,
        val classification: LuminanceClassification,
    )
}

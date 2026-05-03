package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.ColorMath
import com.github.reygnn.kolibri_launcher.core.coerceInSafe
import com.github.reygnn.kolibri_launcher.domain.model.DomainWallpaperColors
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

class ObserveUiColorsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    /**
     * Kombiniert alle Farb-Einstellungen zu einem einzigen UiColorsState-Flow.
     *
     * @param wallpaperColorsFlow Ein Flow (z.B. StateFlow) aus dem ViewModel, der
     *   die aktuellen Wallpaper-Farben als Domain-Projektion bereitstellt.
     *   UI-side maps `android.app.WallpaperColors` to [DomainWallpaperColors].
     */
    operator fun invoke(
        wallpaperColorsFlow: Flow<DomainWallpaperColors?>
    ): Flow<UiColorsState> {
        // Kombiniere alle relevanten Einstellungs-Flows
        return combine(
            settingsRepository.textColorFlow,
            settingsRepository.textShadowEnabledFlow,
            settingsRepository.chipBackgroundColorFlow,
            settingsRepository.readabilityModeFlow,
            wallpaperColorsFlow // <-- Kombiniere mit dem Input aus dem VM
        ) { userColor, shadowEnabled, chipColor, readabilityMode, wallpaperColors ->
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
                        if (wallpaperColors?.supportsDarkText == true) {
                            ColorMath.BLACK
                        } else {
                            ColorMath.WHITE
                        }
                    }
                    "adaptive_colors" -> wallpaperColors?.secondaryColorArgb ?: ColorMath.WHITE
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
}

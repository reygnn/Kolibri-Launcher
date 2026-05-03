package com.github.reygnn.kolibri_launcher.domain.usecase

import android.app.WallpaperColors
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.github.reygnn.kolibri_launcher.core.coerceInSafe
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

class ObserveUiColorsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    // Standardfarben als Konstanten
    companion object {
        private const val DEFAULT_TEXT_COLOR = Color.WHITE
        private const val DEFAULT_SHADOW_COLOR = Color.BLACK
        private const val DEFAULT_CHIP_BG_COLOR = 0
    }

    /**
     * Kombiniert alle Farb-Einstellungen zu einem einzigen UiColorsState-Flow.
     * @param wallpaperColorsFlow Ein Flow (z.B. StateFlow) aus dem ViewModel, der
     * die aktuellen Wallpaper-Farben bereitstellt.
     */
    operator fun invoke(wallpaperColorsFlow: Flow<WallpaperColors?>): Flow<UiColorsState> {
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
                        if (wallpaperColors != null &&
                            (wallpaperColors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
                        ) Color.BLACK else Color.WHITE
                    }
                    "adaptive_colors" -> wallpaperColors?.secondaryColor?.toArgb() ?: Color.WHITE
                    else -> Color.WHITE
                }
            }

            val finalShadowColor = if (shadowEnabled) {
                calculateTonalShadowColor(finalTextColor)
            } else {
                Color.TRANSPARENT
            }

            UiColorsState(
                textColor = finalTextColor,
                shadowColor = finalShadowColor,
                chipBackgroundColor = chipColor
            )
        }
    }

    private fun calculateTonalShadowColor(baseColor: Int): Int {
        // ColorUtils.calculateLuminance + Color.argb + safe-coerce-Math.
        // Frühere Catches waren CANT_THROW.
        val luminance = ColorUtils.calculateLuminance(baseColor).toDouble()
        fun lerp(start: Double, stop: Double, fraction: Double): Double {
            return (start + fraction * (stop - start)).coerceInSafe(0.0, 1.0)
        }
        return when {
            luminance < 0.1 -> Color.argb(204, 255, 255, 255)
            luminance < 0.5 -> {
                val fraction = ((luminance - 0.1) / 0.4).coerceInSafe(0.0, 1.0)
                val alpha = lerp(0.75, 0.4, fraction)
                Color.argb((alpha * 255).toInt().coerceInSafe(0, 255), 255, 255, 255)
            }
            luminance < 0.9 -> {
                val fraction = ((luminance - 0.5) / 0.4).coerceInSafe(0.0, 1.0)
                val alpha = lerp(0.3, 0.6, fraction)
                Color.argb((alpha * 255).toInt().coerceInSafe(0, 255), 0, 0, 0)
            }
            else -> Color.argb(153, 0, 0, 0)
        }
    }
}
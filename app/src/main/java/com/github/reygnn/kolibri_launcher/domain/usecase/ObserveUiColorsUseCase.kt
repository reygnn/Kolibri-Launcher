package com.github.reygnn.kolibri_launcher.domain.usecase

import android.app.WallpaperColors
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
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

            try {
                // 1. Logik für Textfarbe (aus 'updateUiColors')
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

                // 2. Logik für Schattenfarbe (aus 'updateUiColors')
                val finalShadowColor = if (shadowEnabled) {
                    calculateTonalShadowColor(finalTextColor) // (Private Funktion unten)
                } else {
                    Color.TRANSPARENT
                }

                // 3. Gebe den finalen State zurück
                UiColorsState(
                    textColor = finalTextColor,
                    shadowColor = finalShadowColor,
                    chipBackgroundColor = chipColor
                )

            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error updating UI colors from settings")
                UiColorsState(DEFAULT_TEXT_COLOR, DEFAULT_SHADOW_COLOR, DEFAULT_CHIP_BG_COLOR)
            }
        }
    }

    // Die private Helper-Funktion 1:1 aus dem ViewModel kopieren:
    private fun calculateTonalShadowColor(baseColor: Int): Int {
        return try {
            val luminance = ColorUtils.calculateLuminance(baseColor).toDouble()
            // ... (Simple lerp function) ...
            fun lerp(start: Double, stop: Double, fraction: Double): Double {
                return (start + fraction * (stop - start)).coerceIn(0.0, 1.0)
            }
            // ... (restliche when-Logik 1:1 kopieren) ...
            when {
                luminance < 0.1 -> Color.argb(204, 255, 255, 255)
                luminance < 0.5 -> {
                    val fraction = ((luminance - 0.1) / 0.4).coerceIn(0.0, 1.0)
                    val alpha = lerp(0.75, 0.4, fraction)
                    Color.argb((alpha * 255).toInt().coerceIn(0, 255), 255, 255, 255)
                }
                luminance < 0.9 -> {
                    val fraction = ((luminance - 0.5) / 0.4).coerceIn(0.0, 1.0)
                    val alpha = lerp(0.3, 0.6, fraction)
                    Color.argb((alpha * 255).toInt().coerceIn(0, 255), 0, 0, 0)
                }
                else -> Color.argb(153, 0, 0, 0)
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error calculating luminance, using default shadow")
            DEFAULT_SHADOW_COLOR
        }
    }
}
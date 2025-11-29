package com.github.reygnn.kolibri_launcher.ui.home

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.coerceAtLeastSafe
import com.github.reygnn.kolibri_launcher.core.coerceInSafe

/**
 * Berechnet Layout-Parameter basierend auf User-Einstellungen.
 *
 * SICHERHEITSRELEVANT: Stellt sicher, dass Text immer lesbar bleibt.
 * Defensive Behandlung aller Eingabewerte unter Nutzung globaler Konstanten.
 */
class LayoutCalculator {

    data class LayoutCache(
        val textSizePx: Float,
        val verticalPaddingPx: Int,
        val isBold: Boolean
    )

    /**
     * @param scale Layout-Skala (Muss im Bereich AppConstants.LAYOUT_SCALE_MIN..MAX liegen)
     * @param paddingFactor Padding-Faktor (Muss im Bereich AppConstants.VERTICAL_PADDING_SCALE_MIN..MAX liegen)
     * @param isBold Fettschrift aktiviert
     * @param minTextSizePx Minimale Textgröße in Pixel
     * @param maxTextSizePx Maximale Textgröße in Pixel (Referenzwert für Scale = 1.0, kann überschritten werden wenn Scale > 1.0)
     */
    fun calculate(
        scale: Float,
        paddingFactor: Float,
        isBold: Boolean,
        minTextSizePx: Float,
        maxTextSizePx: Float
    ): LayoutCache {
        // Defensive: Werte in den global definierten gültigen Bereich zwingen
        // FIX: Magic Numbers 0f/1f durch AppConstants ersetzt
        val safeScale = scale.coerceInSafe(
            AppConstants.LAYOUT_SCALE_MIN,
            AppConstants.LAYOUT_SCALE_MAX
        )

        // FIX: Magic Numbers 0f/1f durch AppConstants ersetzt
        val safePaddingFactor = paddingFactor.coerceInSafe(
            AppConstants.VERTICAL_PADDING_SCALE_MIN,
            AppConstants.VERTICAL_PADDING_SCALE_MAX
        )

        // Sicherheitsnetz: Mindestens 1px Textgröße, sonst crasht Layout-Rendering
        val safeMinSize = minTextSizePx.coerceAtLeastSafe(1f)

        // MaxSize muss logischerweise >= MinSize sein
        val safeMaxSize = maxTextSizePx.coerceAtLeastSafe(safeMinSize)

        // Berechnung: Lineare Interpolation
        // HINWEIS: Wenn safeScale > 1.0 ist (was durch AppConstants erlaubt sein kann),
        // wird der Text größer als safeMaxSize. Das ist mathematisch korrekt für einen "Zoom-Faktor".
        val textSizePx = safeMinSize + (safeMaxSize - safeMinSize) * safeScale

        val verticalPaddingPx = (textSizePx * safePaddingFactor).toInt()

        return LayoutCache(
            textSizePx = textSizePx,
            verticalPaddingPx = verticalPaddingPx,
            isBold = isBold
        )
    }
}
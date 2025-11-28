package com.github.reygnn.kolibri_launcher.ui.home

import com.github.reygnn.kolibri_launcher.core.coerceAtLeastSafe
import com.github.reygnn.kolibri_launcher.core.coerceInSafe

/**
 * Berechnet Layout-Parameter basierend auf User-Einstellungen.
 *
 * SICHERHEITSRELEVANT: Stellt sicher, dass Text immer lesbar bleibt.
 * Defensive Behandlung aller Eingabewerte.
 */
class LayoutCalculator {

    data class LayoutCache(
        val textSizePx: Float,
        val verticalPaddingPx: Int,
        val isBold: Boolean
    )

    /**
     * @param scale Layout-Skala (0.0 - 1.0)
     * @param paddingFactor Padding-Faktor (0.0 - 1.0)
     * @param isBold Fettschrift aktiviert
     * @param minTextSizePx Minimale Textgröße in Pixel
     * @param maxTextSizePx Maximale Textgröße in Pixel
     */
    fun calculate(
        scale: Float,
        paddingFactor: Float,
        isBold: Boolean,
        minTextSizePx: Float,
        maxTextSizePx: Float
    ): LayoutCache {
        // Defensive: Werte in gültigen Bereich zwingen
        val safeScale = scale.coerceInSafe(0f, 1f)
        val safePaddingFactor = paddingFactor.coerceInSafe(0f, 1f)
        val safeMinSize = minTextSizePx.coerceAtLeastSafe(1f)
        val safeMaxSize = maxTextSizePx.coerceAtLeastSafe(safeMinSize)

        val textSizePx = safeMinSize + (safeMaxSize - safeMinSize) * safeScale
        val verticalPaddingPx = (textSizePx * safePaddingFactor).toInt()

        return LayoutCache(
            textSizePx = textSizePx,
            verticalPaddingPx = verticalPaddingPx,
            isBold = isBold
        )
    }
}
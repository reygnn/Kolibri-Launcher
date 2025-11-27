package com.github.reygnn.kolibri_launcher.ui.home

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
        val safeScale = scale.coerceIn(0f, 1f)
        val safePaddingFactor = paddingFactor.coerceIn(0f, 1f)
        val safeMinSize = minTextSizePx.coerceAtLeast(1f)
        val safeMaxSize = maxTextSizePx.coerceAtLeast(safeMinSize)

        val textSizePx = safeMinSize + (safeMaxSize - safeMinSize) * safeScale
        val verticalPaddingPx = (textSizePx * safePaddingFactor).toInt()

        return LayoutCache(
            textSizePx = textSizePx,
            verticalPaddingPx = verticalPaddingPx,
            isBold = isBold
        )
    }
}
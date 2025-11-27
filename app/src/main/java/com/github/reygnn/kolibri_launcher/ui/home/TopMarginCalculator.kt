package com.github.reygnn.kolibri_launcher.ui.home

/**
 * Berechnet den Top-Margin für den Favorites-Container.
 *
 * Ermöglicht dem User, die App-Liste nach unten zu verschieben.
 * Defensive Behandlung aller Eingabewerte.
 */
class TopMarginCalculator {

    companion object {
        const val DEFAULT_MAX_ADDITIONAL_FRACTION = 0.30f
    }

    /**
     * Berechnet den Top-Margin in Pixel.
     *
     * @param scale User-Einstellung (0.0 - 1.0), 0 = kein Extra-Margin
     * @param baseMarginPx Basis-Abstand aus XML in Pixel
     * @param screenHeightPx Bildschirmhöhe in Pixel
     * @param maxAdditionalFraction Maximaler zusätzlicher Abstand als Bruchteil der Bildschirmhöhe
     * @return Berechneter Top-Margin in Pixel
     */
    fun calculate(
        scale: Float,
        baseMarginPx: Int,
        screenHeightPx: Int,
        maxAdditionalFraction: Float = DEFAULT_MAX_ADDITIONAL_FRACTION
    ): Int {
        // Defensive: Werte in gültigen Bereich zwingen
        val safeScale = scale.coerceIn(0f, 1f)
        val safeBaseMargin = baseMarginPx.coerceAtLeast(0)
        val safeScreenHeight = screenHeightPx.coerceAtLeast(0)
        val safeFraction = maxAdditionalFraction.coerceIn(0f, 1f)

        // Berechnung: Basis + (Bildschirmhöhe * MaxFraction * Scale)
        val maxAdditionalMargin = safeScreenHeight * safeFraction
        val additionalMargin = maxAdditionalMargin * safeScale

        return (safeBaseMargin + additionalMargin).toInt()
    }
}
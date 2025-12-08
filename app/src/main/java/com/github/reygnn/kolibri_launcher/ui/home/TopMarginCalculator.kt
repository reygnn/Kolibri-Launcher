package com.github.reygnn.kolibri_launcher.ui.home

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.coerceAtLeastSafe
import com.github.reygnn.kolibri_launcher.core.coerceInSafe

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
        val safeScale = scale.coerceInSafe(AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN, AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX)
        val safeBaseMargin = baseMarginPx.coerceAtLeastSafe(0)
        val safeScreenHeight = screenHeightPx.coerceAtLeastSafe(0)
        val safeFraction = maxAdditionalFraction.coerceInSafe(0f, 1f)

        // Berechnung: Basis + (Bildschirmhöhe * MaxFraction * Scale)
        val maxAdditionalMargin = safeScreenHeight * safeFraction
        val additionalMargin = maxAdditionalMargin * safeScale

        return (safeBaseMargin + additionalMargin).toInt()
    }
}
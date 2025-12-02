package com.github.reygnn.kolibri_launcher.ui.home

import kotlin.math.max

/**
 * Berechnet den vertikalen Abstand zwischen Uhrzeit und Favoriten.
 * Pure Funktion: Gleiche Inputs = Gleiches Ergebnis, kein interner State.
 */
class ContentSpacingCalculator {

    companion object {
        const val DEFAULT_MIN_GAP = 0
    }

    /**
     * Berechnet den Top-Margin basierend auf User-Präferenz und Chips-Status.
     *
     * Logik: Chips "fressen" die Margin auf (Collapsing Margins).
     *
     * @return Den berechneten Margin in Pixeln.
     */
    fun calculate(
        userPreferredMarginPx: Int,
        chipsHeightPx: Int,
        areChipsVisible: Boolean,
        minGapPx: Int = DEFAULT_MIN_GAP
    ): Int {
        // Chips aus oder Höhe 0 -> volle Margin
        if (!areChipsVisible || chipsHeightPx <= 0) {
            return userPreferredMarginPx
        }

        // Chips "fressen" die Margin auf, aber nie unter minGap
        val remainingMargin = userPreferredMarginPx - chipsHeightPx
        return max(remainingMargin, minGapPx)
    }
}
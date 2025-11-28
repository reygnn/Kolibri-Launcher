package com.github.reygnn.kolibri_launcher.ui.home

import timber.log.Timber
import kotlin.math.max

/**
 * Verantwortlich für die Berechnung des vertikalen Abstands zwischen Uhrzeit und Favoriten.
 *
 * Features:
 * - Berechnet "Collapsing Margins" (Chips verdrängen Leerraum, statt Inhalt zu verschieben).
 * - Integriertes Caching: Verhindert unnötige Neuberechnungen bei gleichen Inputs.
 * - Stateless Logic nach aussen, State-Caching nach innen.
 */
class ContentSpacingCalculator {

    // =================================================================================================
    // Constants
    // =================================================================================================

    private companion object {
        const val TAG = "ContentSpacingCalculator"
        const val DEFAULT_MIN_GAP = 0
    }

    // =================================================================================================
    // State (Caching)
    // =================================================================================================

    // Wir speichern die Inputs der letzten Berechnung, um unnötige Zyklen zu vermeiden.
    private var lastUserPreferredMargin: Int? = null
    private var lastChipsHeight: Int? = null
    private var lastChipsVisible: Boolean? = null

    // =================================================================================================
    // Init & Cleanup
    // =================================================================================================

    init {
        // Optional: Logging beim Starten
        // Timber.d("$TAG initialized")
    }

    /**
     * Setzt den internen Cache zurück.
     * Sollte aufgerufen werden, wenn der View zerstört wird oder ein kompletter Reset nötig ist.
     */
    fun cleanup() {
        lastUserPreferredMargin = null
        lastChipsHeight = null
        lastChipsVisible = null
        // Timber.d("$TAG cache cleared")
    }

    // =================================================================================================
    // Public API
    // =================================================================================================

    /**
     * Berechnet den neuen Top-Margin.
     *
     * @return Den neuen Margin in Pixeln, oder NULL, wenn sich nichts geändert hat (Performance-Optimierung).
     */
    fun calculate(
        userPreferredMarginPx: Int,
        chipsHeightPx: Int,
        areChipsVisible: Boolean,
        minGapPx: Int = DEFAULT_MIN_GAP
    ): Int? {
        // 1. TÜRSTEHER (Input Deduplication)
        // Wenn die Eingaben identisch zur letzten Berechnung sind -> Abbruch (return null)
        if (isCacheValid(userPreferredMarginPx, chipsHeightPx, areChipsVisible)) {
            return null
        }

        // 2. BERECHNUNG (Core Logic)
        val newMargin = performCalculation(
            userPreferredMarginPx,
            chipsHeightPx,
            areChipsVisible,
            minGapPx
        )

        // 3. CACHE UPDATE & LOGGING
        updateCache(userPreferredMarginPx, chipsHeightPx, areChipsVisible)
        logCalculation(userPreferredMarginPx, newMargin, chipsHeightPx)

        return newMargin
    }

    // =================================================================================================
    // Private Helpers
    // =================================================================================================

    private fun isCacheValid(
        margin: Int,
        height: Int,
        visible: Boolean
    ): Boolean {
        return lastUserPreferredMargin == margin &&
                lastChipsHeight == height &&
                lastChipsVisible == visible
    }

    private fun performCalculation(
        userMargin: Int,
        chipsHeight: Int,
        visible: Boolean,
        minGap: Int
    ): Int {
        // Logik: Wenn Chips aus sind oder Höhe 0, volle Margin.
        if (!visible || chipsHeight <= 0) {
            return userMargin
        }

        // Logik: Chips "fressen" die Margin auf.
        val remainingMargin = userMargin - chipsHeight

        // Logik: Niemals weniger als minGap (verhindert Überlappung).
        return max(remainingMargin, minGap)
    }

    private fun updateCache(margin: Int, height: Int, visible: Boolean) {
        lastUserPreferredMargin = margin
        lastChipsHeight = height
        lastChipsVisible = visible
    }

    private fun logCalculation(oldMargin: Int, newMargin: Int, chipsHeight: Int) {
        val reduction = oldMargin - newMargin
        // Nur Info-Log, wenn sich wirklich was rechnerisch getan hat
        Timber.i("📏 Spacing Update: Chips=${chipsHeight}px | Base=$oldMargin -> New=$newMargin (Reduced by $reduction)")
    }
}
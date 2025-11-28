package com.github.reygnn.kolibri_launcher.ui.home

import android.content.res.Configuration
import com.github.reygnn.kolibri_launcher.core.coerceAtLeastSafe

/**
 * Berechnet die Layout-Gewichtungen für Split-Mode vs Full-Mode.
 *
 * Bestimmt wie viel Platz die ScrollView (Favorites) vs
 * die GestureZone (für Swipe-Gesten) bekommen.
 */
class SplitWeightCalculator {

    data class LayoutWeights(
        val scrollViewWeight: Float,
        val gestureZoneWeight: Float,
        val gestureZoneVisible: Boolean
    )

    /**
     * Berechnet Layout-Gewichtungen basierend auf Split-State und Orientation.
     *
     * @param enableSplit Ob Split-Mode aktiv ist
     * @param orientation Aktuelle Orientation (Configuration.ORIENTATION_*)
     * @param portraitScrollWeight Gewichtung ScrollView im Portrait Split-Mode
     * @param portraitGestureWeight Gewichtung GestureZone im Portrait Split-Mode
     * @param landscapeScrollWeight Gewichtung ScrollView im Landscape Split-Mode
     * @param landscapeGestureWeight Gewichtung GestureZone im Landscape Split-Mode
     * @return Berechnete Gewichtungen
     */
    fun calculate(
        enableSplit: Boolean,
        orientation: Int,
        portraitScrollWeight: Float,
        portraitGestureWeight: Float,
        landscapeScrollWeight: Float,
        landscapeGestureWeight: Float
    ): LayoutWeights {
        if (!enableSplit) {
            // Full Mode: 100% ScrollView, keine GestureZone
            return LayoutWeights(
                scrollViewWeight = 1f,
                gestureZoneWeight = 0f,
                gestureZoneVisible = false
            )
        }

        // Split Mode: Orientation-abhängig
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE

        return if (isLandscape) {
            LayoutWeights(
                scrollViewWeight = landscapeScrollWeight.coerceAtLeastSafe(0f),
                gestureZoneWeight = landscapeGestureWeight.coerceAtLeastSafe(0f),
                gestureZoneVisible = true
            )
        } else {
            LayoutWeights(
                scrollViewWeight = portraitScrollWeight.coerceAtLeastSafe(0f),
                gestureZoneWeight = portraitGestureWeight.coerceAtLeastSafe(0f),
                gestureZoneVisible = true
            )
        }
    }
}
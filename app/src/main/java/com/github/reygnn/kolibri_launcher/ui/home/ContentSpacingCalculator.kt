package com.github.reygnn.kolibri_launcher.ui.home

import kotlin.math.max

/**
 * Berechnet den dynamischen oberen Abstand (Margin) für den Favoriten-Container.
 * Ziel: Wenn Chips sichtbar sind, soll ihre Höhe von der Margin abgezogen werden,
 * damit das Gesamtbild stabil bleibt und nicht unnötig springt.
 */
class ContentSpacingCalculator {

    /**
     * @param userPreferredMarginPx Der Abstand, den der User in den Settings gewählt hat (als "Basis").
     * @param chipsHeightPx Die aktuelle Höhe der Chips-View (inkl. Padding).
     * @param areChipsVisible Ob die Chips aktuell sichtbar sind.
     * @param minGapPx Ein Mindestabstand (Sicherheitsabstand), falls die Chips größer sind als die Margin.
     */
    fun calculateFavoritesTopMargin(
        userPreferredMarginPx: Int,
        chipsHeightPx: Int,
        areChipsVisible: Boolean,
        minGapPx: Int = 0
    ): Int {
        // Fall 1: Chips sind nicht da. Wir nehmen den vollen Abstand.
        if (!areChipsVisible || chipsHeightPx <= 0) {
            return userPreferredMarginPx
        }

        // Fall 2: Chips sind da.
        // Wir ziehen die Höhe der Chips vom gewünschten Abstand ab.
        // Beispiel: User will 24px Platz. Chips sind 16px hoch.
        // Neuer Margin = 8px.
        // Visuell: Time -> [16px Chip] -> [8px Margin] -> Favorites. Summe = 24px.
        val remainingMargin = userPreferredMarginPx - chipsHeightPx

        // Fall 3: Die Chips sind grösser als der gewünschte Abstand (z.B. Chips 50px, Margin 10px).
        // 10 - 50 = -40. Negative Margins können zu Clipping führen.
        // Wir nutzen stattdessen den minGapPx (z.B. 4px), damit es nicht klebt.
        return max(remainingMargin, minGapPx)
    }
}
package com.github.reygnn.kolibri_launcher.ui.home

/**
 * Berechnet den dynamischen Abstand (Margin) für den Content-Bereich,
 * basierend auf der User-Einstellung und der Sichtbarkeit von Elementen.
 */
class ContentSpacingCalculator {

    /**
     * @param userPreferredMarginPx Die Margin, die der User eingestellt hat (in Pixeln).
     * @param chipsHeightPx Die tatsächliche Höhe der Chips-View (in Pixeln).
     * @param areChipsVisible Ob die Chips aktuell angezeigt werden.
     * @param minGapPx (Optional) Ein Mindestabstand, damit Text nicht direkt an den Chips klebt (z.B. 4dp).
     */
    fun calculateFavoritesTopMargin(
        userPreferredMarginPx: Int,
        chipsHeightPx: Int,
        areChipsVisible: Boolean,
        minGapPx: Int = 0
    ): Int {
        // Fall 1: Keine Chips sichtbar.
        // Wir nutzen einfach den vollen Abstand, den der User wollte.
        if (!areChipsVisible || chipsHeightPx <= 0) {
            return userPreferredMarginPx
        }

        // Fall 2: Chips sind sichtbar.
        // Wir ziehen die Höhe der Chips von der gewünschten Margin ab.
        // Idee: Die Chips "füllen" den Leerraum auf.
        val remainingMargin = userPreferredMarginPx - chipsHeightPx

        // Fall 2a: Die Chips sind kleiner als die Margin (z.B. Chips 16px, Margin 24px -> Rest 8px).
        // Fall 2b: Die Chips sind größer als die Margin (z.B. Chips 50px, Margin 10px -> Rest -40px).
        // Wir wollen keine negative Margin (Überlappung), sondern mindestens 'minGapPx'.
        // Das bedeutet im Fall 2b verschieben sich die Favoriten zwangsläufig nach unten,
        // was aber physikalisch notwendig ist.
        return maxOf(remainingMargin, minGapPx)
    }
}
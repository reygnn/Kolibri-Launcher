package com.github.reygnn.kolibri_launcher.ui.home

/**
 * SICHERHEITSKRITISCHE KLASSE - Split-Mode Entscheidungslogik
 *
 * Diese Klasse entscheidet, ob der Home-Screen im Split-Modus (scrollbar)
 * oder Full-Modus (statisch) angezeigt wird.
 *
 * FAIL-SAFE PRINZIP:
 * - Im Zweifel: Split aktivieren (User kann scrollen)
 * - Negative/ungültige Werte: Defensiv behandeln
 * - Kein Crash, keine Exception nach aussen
 *
 * NIEMALS darf diese Klasse:
 * - Den User "einsperren" (Apps nicht erreichbar)
 * - Einen Crash verursachen
 * - Unvorhersehbares Verhalten zeigen
 *
 * Getestet mit 43 Unit-Tests inkl. pathologischer Edge Cases.
 *
 * @see HomeFragment.checkAndEmitScrollState
 */


/**
 * Reine Logik für Split-Mode-Entscheidungen.
 * Keine Android-Dependencies = perfekt für Unit-Tests!
 */
class SplitModeCalculator {

    /**
     * Berechnet, ob Split-Mode aktiviert werden soll.
     *
     * @param threshold 0 = Auto-Modus, >0 = Power-User Pixel-Threshold
     * @param canScrollDown Kann nach unten gescrollt werden?
     * @param canScrollUp Kann nach oben gescrollt werden?
     * @param contentHeight Höhe des Inhalts (nur für Power-User-Modus)
     * @param containerHeight Höhe des Containers (nur für Power-User-Modus)
     * @return true wenn Split-Mode aktiviert werden soll
     */
    fun shouldSplit(
        threshold: Int,
        canScrollDown: Boolean,
        canScrollUp: Boolean,
        contentHeight: Int = 0,
        containerHeight: Int = 0
    ): Boolean {
        // Defensive: Negative threshold = Auto-Modus
        if (threshold <= 0) {
            return canScrollDown || canScrollUp
        }

        val scrollablePixels = calculateScrollablePixels(contentHeight, containerHeight)
        return scrollablePixels > threshold
    }

    fun calculateScrollablePixels(contentHeight: Int, containerHeight: Int): Int {
        // Defensive: Negative Werte auf 0 setzen
        val safeContent = contentHeight.coerceAtLeast(0)
        val safeContainer = containerHeight.coerceAtLeast(0)

        // Overflow-safe: Erst coercen, dann subtrahieren
        return (safeContent - safeContainer).coerceAtLeast(0)
    }
}
package com.github.reygnn.kolibri_launcher.ui.home

import com.github.reygnn.kolibri_launcher.core.coerceInSafe

/**
 * Berechnet die Hintergrundfarbe für Chips (Kalender/Alarm Events).
 *
 * Keine Android-Dependencies - pure Kotlin für Unit-Tests.
 */
class ChipBackgroundCalculator {

    companion object {
        const val DEFAULT_ALPHA = 40
        const val NO_COLOR = 0
    }

    data class ColorComponents(
        val alpha: Int,
        val red: Int,
        val green: Int,
        val blue: Int
    ) {
        /**
         * Konvertiert zu Android Color Int.
         * Format: ARGB (0xAARRGGBB)
         */
        fun toColorInt(): Int {
            return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }

    /**
     * Berechnet die finale Chip-Hintergrundfarbe.
     *
     * @param chipBackgroundColor User-gesetzte Farbe (0 = keine)
     * @param textColorInt Aktuelle Textfarbe als Fallback-Basis (ARGB Int)
     * @param defaultAlpha Alpha-Wert für automatische Farbe (0-255)
     * @return Berechnete Hintergrundfarbe als ARGB Int
     */
    fun calculate(
        chipBackgroundColor: Int,
        textColorInt: Int,
        defaultAlpha: Int = DEFAULT_ALPHA
    ): Int {
        // User hat explizite Farbe gesetzt
        if (chipBackgroundColor != NO_COLOR) {
            return chipBackgroundColor
        }

        // Automatisch: Semi-transparente Version der Textfarbe
        val safeAlpha = defaultAlpha.coerceInSafe(0, 255)

        // Extrahiere RGB aus textColorInt (Format: 0xAARRGGBB)
        val red = (textColorInt shr 16) and 0xFF
        val green = (textColorInt shr 8) and 0xFF
        val blue = textColorInt and 0xFF

        return (safeAlpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    /**
     * Extrahiert Alpha-Komponente aus Color Int.
     */
    fun extractAlpha(colorInt: Int): Int = (colorInt shr 24) and 0xFF

    /**
     * Extrahiert Red-Komponente aus Color Int.
     */
    fun extractRed(colorInt: Int): Int = (colorInt shr 16) and 0xFF

    /**
     * Extrahiert Green-Komponente aus Color Int.
     */
    fun extractGreen(colorInt: Int): Int = (colorInt shr 8) and 0xFF

    /**
     * Extrahiert Blue-Komponente aus Color Int.
     */
    fun extractBlue(colorInt: Int): Int = colorInt and 0xFF
}
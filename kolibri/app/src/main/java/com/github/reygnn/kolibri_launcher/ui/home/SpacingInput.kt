package com.github.reygnn.kolibri_launcher.ui.home

/**
 * Repräsentiert die Eingabewerte für die Spacing-Berechnung.
 * Data Class für automatischen equals()/hashCode() Vergleich.
 */
data class SpacingInput(
    val userPreferredMarginPx: Int,
    val chipsHeightPx: Int,
    val areChipsVisible: Boolean
)
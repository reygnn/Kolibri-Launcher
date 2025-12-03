package com.github.reygnn.kolibri_launcher.ui.swipeactions

/**
 * Definiert die beiden Slots für die Wischgesten sowie einen neutralen Zustand,
 * der anzeigt, dass eine App keinem Slot zugewiesen ist oder
 * dass gerade kein Slot zur Zuweisung aktiv ist.
 */
enum class SwipeSlot {
    SWIPE_FROM_LEFT,     // Swipe nach rechts → linker Chip
    SWIPE_FROM_RIGHT,    // Swipe nach links → rechter Chip
    NONE
}
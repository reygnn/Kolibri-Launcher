package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Defines the two slots for swipe gestures plus a neutral state that
 * indicates an app is not assigned to any slot, or that no slot is
 * currently active for assignment.
 */
enum class SwipeSlot {
    SWIPE_FROM_LEFT_TO_RIGHT,
    SWIPE_FROM_RIGHT_TO_LEFT,
    NONE
}

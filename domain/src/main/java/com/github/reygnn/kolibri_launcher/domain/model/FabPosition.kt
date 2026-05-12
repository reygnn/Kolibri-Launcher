package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Persisted position of the wallpaper-edit speed-dial FAB.
 *
 * Stored as the FAB's center expressed as a fraction of the parent
 * container's width/height (both in `[0f, 1f]`). The fraction form is
 * resolution-, density- and orientation-agnostic — a position picked
 * in portrait still lands in roughly the same visual spot in
 * landscape, on a tablet, or after a font-scale change.
 *
 * Clamping so the FAB stays fully on-screen is the consumer's job (the
 * drag handler does it at apply-time); the stored value is the user's
 * intent, not the rendered position.
 */
data class FabPosition(
    val xFraction: Float,
    val yFraction: Float,
) {
    companion object {
        /**
         * Bottom-right starting position. Matches the canonical FAB
         * placement (Material BottomEnd) when the user has never moved
         * the FAB.
         */
        val DEFAULT: FabPosition = FabPosition(xFraction = 0.9f, yFraction = 0.9f)
    }
}

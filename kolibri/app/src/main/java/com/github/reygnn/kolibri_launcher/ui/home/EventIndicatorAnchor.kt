package com.github.reygnn.kolibri_launcher.ui.home

/**
 * Pure vertical-anchoring math for the home-screen event indicators (the alarm +
 * calendar icons next to the clock).
 *
 * Replaces the old hand-tuned `layout_constraintVertical_bias="0.58"` (TODO §24), which
 * was eyeballed against a 60sp clock at 1× system font and drifted at other font scales.
 *
 * The wish (Mr. Monk): the ALARM's visible top edge is flush with the clock's visible
 * digit top, and the CALENDAR's visible bottom edge is flush with the digit bottom — the
 * pair spans the digit band, with the gap between the two absorbing the slack (the box
 * pair is taller than the band, so a rigid stack cannot be flush at both ends). Each icon
 * is therefore anchored independently via its own `translationY`.
 *
 * "Visible" is the key word: the drawn mark sits INSET inside the icon's view box (view
 * padding + the vector's own interior margin + the tonal outline rim). HomeFragment
 * measures that opaque inset at runtime (rasterise the view, scan alpha) and feeds it in
 * here, so the math is exact for whatever the icons and font actually render. Kept as
 * pure functions (no Android types) so the flush invariant is unit-testable on the JVM
 * (Rule 10); HomeFragment is the thin glue that measures and applies.
 *
 * All values are in the clock `TextView`'s own coordinate space (origin = its top). Both
 * icon boxes are constrained with their top at the clock's top, so at `translationY = 0`
 * a box's top sits at 0.
 */
object EventIndicatorAnchor {

    /**
     * `translationY` (px) for the ALARM icon so its visible top edge lands on the digit top.
     *
     * @param digitTopPx        the visible digit-top Y (clock baseline + tight glyph top,
     *                          the latter negative).
     * @param alarmOpaqueInsetTopPx  px from the alarm view-box top down to its first
     *                          opaque (drawn) row.
     */
    fun alarmTranslationY(digitTopPx: Int, alarmOpaqueInsetTopPx: Int): Float =
        (digitTopPx - alarmOpaqueInsetTopPx).toFloat()

    /**
     * `translationY` (px) for the CALENDAR icon so its visible bottom edge lands on the
     * digit bottom.
     *
     * @param digitBottomPx     the visible digit-bottom Y (clock baseline + tight glyph
     *                          bottom, ~baseline for digits).
     * @param calendarBoxHeightPx the calendar icon view-box height.
     * @param calendarOpaqueInsetBottomPx px from the calendar view-box bottom up to its
     *                          last opaque (drawn) row.
     */
    fun calendarTranslationY(
        digitBottomPx: Int,
        calendarBoxHeightPx: Int,
        calendarOpaqueInsetBottomPx: Int,
    ): Float = (digitBottomPx - calendarBoxHeightPx + calendarOpaqueInsetBottomPx).toFloat()
}

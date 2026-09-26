package com.github.reygnn.kolibri_launcher.ui.home

/**
 * Pure vertical-anchoring math for the home-screen event indicators (the alarm +
 * calendar icon pair next to the clock).
 *
 * This replaces the old hand-tuned `layout_constraintVertical_bias="0.58"` (TODO §24):
 * that value was measured against a 60sp clock at 1× system font and drifted under a
 * larger system font, because the clock `TextView`'s line box is much taller than the
 * visible digits (asymmetric font padding). Here the pair is instead centred on the
 * clock's VISIBLE digit band, which HomeFragment derives at runtime from the font
 * metrics — so the alignment holds at any font scale.
 *
 * Kept as a pure function (no Android types) so the invariant "the indicator pair is
 * vertically centred on the visible digit band" is unit-testable on the JVM (Rule 10);
 * HomeFragment is the thin glue that reads the metrics and applies the result.
 */
object EventIndicatorAnchor {

    /**
     * Vertical translation, in pixels, to apply to the indicator container so its centre
     * lands on the centre of the clock's visible digit band.
     *
     * All inputs are in the clock `TextView`'s own coordinate space (origin = its top),
     * and the container's top is pinned to the clock's top (so at `translationY = 0` the
     * container's centre sits at `pairHeightPx / 2`).
     *
     * @param baselinePx    the text baseline offset from the view top (`View.getBaseline()`).
     * @param glyphTopPx    tight glyph-bounds top RELATIVE TO THE BASELINE
     *                      (`Paint.getTextBounds` `rect.top`; negative — above the baseline).
     * @param glyphBottomPx tight glyph-bounds bottom relative to the baseline
     *                      (`rect.bottom`; ~0 for digits, which have no descender).
     * @param pairHeightPx  the measured height of the indicator container.
     * @return the `translationY` (px) that centres the pair on the visible digit band.
     */
    fun translationY(
        baselinePx: Int,
        glyphTopPx: Int,
        glyphBottomPx: Int,
        pairHeightPx: Int,
    ): Float {
        val digitBandCentre = baselinePx + (glyphTopPx + glyphBottomPx) / 2f
        return digitBandCentre - pairHeightPx / 2f
    }
}

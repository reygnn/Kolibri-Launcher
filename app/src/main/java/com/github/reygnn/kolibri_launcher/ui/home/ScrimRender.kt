package com.github.reygnn.kolibri_launcher.ui.home

import kotlin.math.roundToInt

/**
 * Pure render decision for the home wallpaper scrim (a user-controlled dim that
 * darkens the wallpaper on the home screen to rescue text legibility on extreme
 * wallpapers). Android-free so the skip logic + alpha→ARGB mapping are
 * JVM-unit-testable (Rule 10); the Fragment stays thin glue
 * (`HomeFragment.applyScrim`).
 *
 * The strength is baked into the alpha byte of an opaque-black colour so the
 * scrim View can be drawn with `View.alpha = 1f`: a non-1 `View.alpha` on a
 * full-screen leaf view would force the renderer to allocate an offscreen
 * `saveLayer` buffer, while a solid background colour whose alpha byte carries
 * the strength composites directly with no buffer.
 */
object ScrimRender {

    /**
     * @return the opaque-black-tinted ARGB fill with [alpha] baked into the alpha
     *   byte, or `null` when the scrim must not draw at all (View `GONE`): in
     *   wallpaper edit mode (the user is adjusting the wallpaper against its true
     *   appearance), or when the strength rounds to a fully transparent byte.
     */
    fun colorOrNull(alpha: Float, isEditMode: Boolean): Int? {
        if (isEditMode) return null
        val alphaByte = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        if (alphaByte == 0) return null
        // Opaque black RGB (0x000000); strength lives in the alpha byte.
        return alphaByte shl 24
    }
}

package com.github.reygnn.kolibri_launcher.ui.util

import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Animates this view's alpha to [targetAlpha] over 200 ms with a decelerate
 * interpolator. Shared by the dialog drag helper ([enableDialogDrag]) and the
 * layout-customisation slider fade, which previously carried identical copies.
 */
fun View.fadeTo(targetAlpha: Float) {
    animate()
        .alpha(targetAlpha)
        .setDuration(200)
        .setInterpolator(DecelerateInterpolator())
        .start()
}

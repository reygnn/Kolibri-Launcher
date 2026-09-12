package com.github.reygnn.launcher.common.ui

import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Animates this view's alpha to [targetAlpha] over 200 ms with a decelerate
 * interpolator. Used by the live-preview dialogs to fade their content out of
 * the way (alpha 0) while a slider is being dragged, revealing the live home
 * behind them (an alpha-0 view still receives touches, so the drag continues).
 */
fun View.fadeTo(targetAlpha: Float) {
    animate()
        .alpha(targetAlpha)
        .setDuration(200)
        .setInterpolator(DecelerateInterpolator())
        .start()
}

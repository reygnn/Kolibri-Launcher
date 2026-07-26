package com.github.reygnn.kolibri_launcher.ui.util

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.fragment.app.DialogFragment
import com.github.reygnn.kolibri_launcher.core.TimberWrapper

/**
 * Wires [dragZone] as a drag handle that moves this dialog's window
 * vertically. Because the window uses `Gravity.BOTTOM`, `params.y` is the
 * distance from the bottom edge, so the natural gesture (`initialY - delta`)
 * makes the sheet follow the finger down and up. [contentRoot] fades to 50 %
 * while dragging and back to full on release, and a haptic tick fires on
 * touch-down.
 *
 * Shared by the colour- and layout-customisation dialogs. The colour dialog
 * previously carried a divergent copy that dragged on both axes with an
 * inverted vertical sign (`initialY + delta`) and no feedback — a bug that
 * made the sheet jump the wrong way. This is the single source of truth.
 */
@SuppressLint("ClickableViewAccessibility")
fun DialogFragment.enableDialogDrag(dragZone: View, contentRoot: View) {
    var initialY = 0
    var initialTouchY = 0f

    dragZone.setOnTouchListener { _, event ->
        try {
            val window = dialog?.window ?: return@setOnTouchListener false
            val params = window.attributes
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    initialTouchY = event.rawY
                    contentRoot.fadeTo(0.5f)
                    dragZone.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.y = initialY - (event.rawY - initialTouchY).toInt()
                    window.attributes = params
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    contentRoot.fadeTo(1.0f)
                    true
                }
                else -> false
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in dialog drag")
            // Make sure the dialog is visible again if something went wrong.
            contentRoot.fadeTo(1.0f)
            false
        }
    }
}

private fun View.fadeTo(targetAlpha: Float) {
    animate()
        .alpha(targetAlpha)
        .setDuration(200)
        .setInterpolator(DecelerateInterpolator())
        .start()
}

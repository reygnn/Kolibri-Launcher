package com.github.reygnn.launcher.common.ui

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.fragment.app.DialogFragment
import com.github.reygnn.launcher.core.TimberWrapper

/**
 * Wires [dragZone] as a drag handle that moves this dialog's window vertically.
 * Because the live-preview window uses `Gravity.BOTTOM`, `params.y` is the
 * distance from the bottom edge, so the natural gesture (`initialY - delta`)
 * makes the sheet follow the finger up and down. [contentRoot] fades to 50 %
 * while dragging and back to full on release, and a haptic tick fires on
 * touch-down. The position is clamped so the sheet can't be flung off-screen.
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
                    val proposed = initialY - (event.rawY - initialTouchY).toInt()
                    // Clamp so the sheet stays fully on-screen: y past
                    // (screenHeight - sheetHeight) would push its top off the top.
                    val maxUp = (resources.displayMetrics.heightPixels - contentRoot.height)
                        .coerceAtLeast(0)
                    params.y = proposed.coerceIn(0, maxUp)
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

package com.github.reygnn.launcher.common.ui

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment

/**
 * Configures this dialog's window as a bottom-anchored sheet with NO scrim:
 * transparent background (so the content's own rounded drawable shows), and
 * — crucially for live preview — `FLAG_DIM_BEHIND` cleared so nothing dims the
 * home screen behind the dialog. Combined with fading the content view to
 * alpha 0 on slider-drag, this reveals the live home while the user adjusts.
 * Bottom-centre gravity, width [widthFraction] of the screen, bottom offset
 * [yOffset] px.
 */
fun DialogFragment.configureLivePreviewWindow(widthFraction: Double, yOffset: Int) {
    dialog?.window?.let { window ->
        window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)

        val width = (resources.displayMetrics.widthPixels * widthFraction).toInt()
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        val params = window.attributes
        params.y = yOffset
        window.attributes = params
    }
}

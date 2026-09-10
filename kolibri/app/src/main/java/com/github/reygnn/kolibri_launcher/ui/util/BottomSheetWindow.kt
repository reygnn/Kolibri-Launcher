package com.github.reygnn.kolibri_launcher.ui.util

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment

/**
 * Configures this dialog's window as a bottom-anchored sheet: transparent
 * background (so the content's own rounded drawable shows), no scrim
 * (`FLAG_DIM_BEHIND` cleared), bottom-centre gravity, a width of
 * [widthFraction] of the screen, and a bottom offset of [yOffset] pixels.
 *
 * Shared by the colour- and layout-customisation dialogs, which previously
 * carried byte-identical window setup differing only in these two values.
 * The values stay per-dialog because each sheet is tuned to its own content
 * height — folding them here only removes the duplicated mechanics, not the
 * intentional sizing difference.
 *
 * Only the window setup is shared; the drag-handle listeners stay per-dialog
 * (they differ in axis, direction and haptic/alpha feedback).
 */
fun DialogFragment.configureBottomSheetWindow(widthFraction: Double, yOffset: Int) {
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

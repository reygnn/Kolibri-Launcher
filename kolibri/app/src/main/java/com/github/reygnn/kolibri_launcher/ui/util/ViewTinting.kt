package com.github.reygnn.kolibri_launcher.ui.util

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorInt

/**
 * Recursively tints every [TextView] descendant of this [ViewGroup] with
 * [color]. A node for which [skip] returns `true` is skipped together with
 * its entire subtree (the recursion does not descend into it).
 *
 * `MaterialButton` / `MaterialSwitch` subclass [TextView], so they are
 * tinted too. Shared by the colour- and layout-customisation dialogs, which
 * previously carried byte-identical private copies differing only in the
 * skip predicate.
 */
fun ViewGroup.tintTextViews(@ColorInt color: Int, skip: (View) -> Boolean = { false }) {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (skip(child)) continue
        if (child is TextView) child.setTextColor(color)
        if (child is ViewGroup) child.tintTextViews(color, skip)
    }
}

package com.github.reygnn.kolibri_launcher.ui.util

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import com.github.reygnn.kolibri_launcher.core.TimberWrapper

/**
 * Resolves a theme-attribute colour to a colour int, returning [fallback]
 * when the attribute is not present on the current theme.
 *
 * Shared by `SettingsFragment` and `ColorCustomizationDialogFragment`,
 * which previously carried divergent copies (different fallbacks and
 * error handling). Callers keep their own fallback by passing it in.
 */
@ColorInt
fun Context.resolveThemeColor(@AttrRes attr: Int, @ColorInt fallback: Int): Int {
    try {
        val typedValue = TypedValue()
        if (theme.resolveAttribute(attr, typedValue, true)) {
            return typedValue.data
        }
    } catch (e: Throwable) {
        TimberWrapper.silentError(e, "Failed to resolve theme color attribute: $attr")
    }
    return fallback
}

package com.github.reygnn.kolibri_launcher.ui.util

import android.widget.Toast
import androidx.fragment.app.Fragment
import com.github.reygnn.kolibri_launcher.core.TimberWrapper

/**
 * Shows a [Toast] from a [Fragment], guarded against the two failure modes
 * that bite in practice:
 *
 * - the Fragment is detached / has no context (checked via [Fragment.isAdded]
 *   and [Fragment.isDetached] before touching [Fragment.context]);
 * - `Toast.makeText` / `show()` do IPC and have been observed to throw on
 *   Samsung devices — the broad `catch (Throwable)` mirrors
 *   `BaseActivity.showToastSafe`.
 *
 * The Fragment counterpart to `BaseActivity.showToastSafe`, extracted from
 * per-fragment copies of the same guard + try/catch.
 */
fun Fragment.showToastSafe(message: String, duration: Int = Toast.LENGTH_SHORT) {
    try {
        if (isAdded && !isDetached) {
            context?.let { ctx -> Toast.makeText(ctx, message, duration).show() }
        }
    } catch (e: Throwable) {
        TimberWrapper.silentError(e, "Error showing toast")
    }
}

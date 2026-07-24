package com.github.reygnn.kolibri_launcher.ui.util

import android.content.Context
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.github.reygnn.kolibri_launcher.core.TimberWrapper

/**
 * Shows a [Toast] safely — the single toast entry point for the whole app:
 *
 * - StrictMode is relaxed for the call (see [withRelaxedStrictMode]) because
 *   Samsung does on-UI-thread IPC/DB reads inside `Toast.makeText`;
 * - the rare `Throwable` that Toast IPC can raise is swallowed and routed
 *   through [TimberWrapper.silentError] instead of escaping.
 *
 * `BaseActivity`'s toast calls resolve here via its `Context` receiver, and the
 * [Fragment] overload below delegates here too, so Activity and Fragment toasts
 * get identical platform handling.
 */
fun Context.showToastSafe(message: String, duration: Int = Toast.LENGTH_SHORT) {
    try {
        withRelaxedStrictMode {
            Toast.makeText(this, message, duration).show()
        }
    } catch (e: Throwable) {
        TimberWrapper.silentError(e, "Error showing toast")
    }
}

/**
 * [Fragment] overload of [showToastSafe]: no-ops when the Fragment is detached
 * or has no context, otherwise delegates to the [Context] core above.
 */
fun Fragment.showToastSafe(message: String, duration: Int = Toast.LENGTH_SHORT) {
    if (isAdded && !isDetached) {
        context?.showToastSafe(message, duration)
    }
}

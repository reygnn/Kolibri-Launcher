package com.github.reygnn.kolibri_launcher.ui.util

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.github.reygnn.kolibri_launcher.core.TimberWrapper

/**
 * Shows a [Toast] safely — the single toast entry point for the whole app.
 * Every user-facing toast in production code routes through one of these
 * overloads; a bare `Toast.makeText(...)` outside this file is a lint error
 * (`./gradlew checkConventions`), so the platform handling below can never
 * silently drift out of a call site again.
 *
 * - StrictMode is relaxed for the call (see [withRelaxedStrictMode]) because
 *   Samsung does on-UI-thread IPC/DB reads inside `Toast.makeText`;
 * - the rare `Throwable` that Toast IPC can raise is swallowed and routed
 *   through [TimberWrapper.silentError] instead of escaping.
 *
 * `BaseActivity`'s toast calls resolve here via its `Context` receiver, and the
 * [Fragment] overloads below delegate here too, so Activity and Fragment toasts
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
 * `@StringRes` overload of [showToastSafe]: resolves [messageResId] against this
 * [Context] and delegates to the string core above. Saves call sites the
 * `getString(...)` boilerplate for the common fixed-message case.
 */
fun Context.showToastSafe(@StringRes messageResId: Int, duration: Int = Toast.LENGTH_SHORT) {
    showToastSafe(getString(messageResId), duration)
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

/**
 * `@StringRes` [Fragment] overload of [showToastSafe]: same detach guard as the
 * string [Fragment] overload, resolving [messageResId] via the Fragment context.
 */
fun Fragment.showToastSafe(@StringRes messageResId: Int, duration: Int = Toast.LENGTH_SHORT) {
    if (isAdded && !isDetached) {
        context?.showToastSafe(messageResId, duration)
    }
}

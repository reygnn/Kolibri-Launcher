package com.github.reygnn.launcher.feature.crashreporting

import android.util.Log
import com.github.reygnn.launcher.common.ui.ErrorEventBus
import timber.log.Timber

/**
 * A Timber tree that forwards ERROR-level logs to the global [ErrorEventBus], so a
 * subscribed UI (a `BaseActivity`) can surface a developer error toast. Shared by both
 * launchers — plant it in the Application's DEBUG Timber setup; non-error priorities
 * are ignored.
 *
 * The log tag is passed through unchanged so the consumer can suppress specific tags:
 * `BaseActivity` skips the toast for `TimberWrapper.SILENT_LOG_TAG` entries (those
 * already threw in DEBUG), while `ACRA_REPORT` and untagged errors still toast.
 */
class ToastErrorTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority != Log.ERROR) return
        val formatted = if (t != null) {
            "$message\n${t.localizedMessage ?: t}"
        } else {
            message
        }
        ErrorEventBus.post(formatted, tag)
    }
}

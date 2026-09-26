package com.github.reygnn.launcher.feature.crashreporting

import com.github.reygnn.launcher.core.KolibriLog
import com.github.reygnn.launcher.core.TimberWrapper
import timber.log.Timber

/**
 * Wire the pure-Kotlin :core logging seam ([KolibriLog] + [TimberWrapper]) to Timber.
 *
 * :core is a pure-Kotlin module with no Timber on its compile classpath (Timber 5.x is
 * .aar-only), so it exposes lambda handlers that each Application forwards to Timber.
 * Extracted here so BOTH launchers wire the SAME routing — a drift in log/crash routing
 * between the two apps would otherwise be silent. Call from `Application.onCreate` BEFORE
 * any code path that may log or call `silentError` ([TimberWrapper.isDebugBuild] must be
 * set first). [isDebugBuild] stays a per-module `BuildConfig.DEBUG` argument.
 */
fun wireKolibriLogToTimber(isDebugBuild: Boolean) {
    TimberWrapper.isDebugBuild = isDebugBuild
    KolibriLog.dHandler = { message -> Timber.d(message) }
    KolibriLog.wHandler = { throwable, message ->
        if (throwable != null) Timber.w(throwable, message) else Timber.w(message)
    }
    KolibriLog.taggedErrorHandler = { tag, throwable, message ->
        val tree = Timber.tag(tag)
        if (throwable != null) tree.e(throwable, message) else tree.e(message)
    }
}

package com.github.reygnn.kolibri_launcher.core

/**
 * Pure-Kotlin logging façade for the `:domain` module.
 *
 * Exposes the subset of Timber's API that domain code uses (debug,
 * warning, tagged error). Each call goes through a runtime-injected
 * lambda so that `:domain` can stay free of `timber.log.Timber` on its
 * compile classpath — Timber 5.x is distributed only as an `.aar`,
 * which a pure-Kotlin (`kotlin("jvm")`) module cannot consume.
 *
 * `:app` wires the lambdas at startup
 * (see `KolibriLauncherApp.onCreate`); without that wire-up the
 * default no-op handlers preserve the safe-fallback semantic
 * `silentError` already follows: log nothing rather than crash.
 *
 * Pattern parallel to [TimberWrapper.isDebugBuild] / [TimberWrapper.preventCrashForTesting]:
 * a static field in a `:domain` object that the application layer
 * configures once at process start.
 */
object KolibriLog {

    /** Backend for [d]. Default: no-op. Set by `:app` at init. */
    @Volatile
    var dHandler: (message: String) -> Unit = { _ -> }

    /** Backend for [w] / [w] with throwable. Default: no-op. */
    @Volatile
    var wHandler: (throwable: Throwable?, message: String) -> Unit = { _, _ -> }

    /**
     * Backend for [taggedError]. Default: no-op. Used by
     * [TimberWrapper.silentError] to attach the SILENT_ERROR tag.
     */
    @Volatile
    var taggedErrorHandler: (tag: String, throwable: Throwable?, message: String) -> Unit =
        { _, _, _ -> }

    /** Debug log — same semantics as `Timber.d(message)`. */
    fun d(message: String) = dHandler(message)

    /** Warning log without throwable — same semantics as `Timber.w(message)`. */
    fun w(message: String) = wHandler(null, message)

    /** Warning log with throwable — same semantics as `Timber.w(throwable, message)`. */
    fun w(throwable: Throwable, message: String) = wHandler(throwable, message)

    /**
     * Error log with an explicit tag — same semantics as
     * `Timber.tag(tag).e(throwable, message)`. Used by
     * [TimberWrapper] for SILENT_ERROR / FATAL paths.
     */
    fun taggedError(tag: String, throwable: Throwable?, message: String) =
        taggedErrorHandler(tag, throwable, message)
}

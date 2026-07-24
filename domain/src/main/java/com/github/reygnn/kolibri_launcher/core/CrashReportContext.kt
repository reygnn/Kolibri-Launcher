package com.github.reygnn.kolibri_launcher.core

import java.util.concurrent.CancellationException

/**
 * Builds the throwable that `AcraTree` forwards to ACRA, folding the Timber
 * log context (priority, tag, message) INTO the reported throwable instead of
 * routing it through ACRA's process-global custom-data map.
 *
 * == WHY ==
 * The previous approach called `ACRA.errorReporter.putCustomData(...)` for
 * `log_priority` / `log_tag` / `log_message` before `handleSilentException()`.
 * That is a per-report payload written to a PROCESS-GLOBAL, shared mutable map,
 * so two reports racing on different threads could swap each other's metadata
 * (AUDIT-6 #4). Worse, the app's ACRA `reportContent` does not list
 * `ReportField.CUSTOM_DATA`, so ACRA's `CustomDataCollector` never ran and the
 * metadata never reached the server at all.
 *
 * Folding the context into a carrier exception makes it per-report by
 * construction (a fresh object each call — no shared state, no lock, no
 * executor) and lands it in `ReportField.STACK_TRACE`, which IS collected. The
 * original throwable is kept as the cause, so its real stack trace is preserved
 * under "Caused by:".
 *
 * Pure and Android-free so it can be JVM-tested (Rule 10); `AcraTree` is the
 * thin Android-runtime glue that calls it.
 */

/** Carrier exception whose message carries the Timber log context. */
internal class LoggedThrowable(message: String, cause: Throwable) : RuntimeException(message, cause)

/**
 * Wraps [cause] in a [LoggedThrowable] whose message encodes the logcat-style
 * context `"[<level>/<tag>] <cause-type>: <message>"`.
 *
 * The original exception's simple class name is included because the report's
 * top-level type is now always `LoggedThrowable`; keeping the real type in the
 * message keeps ACRA reports groupable/filterable by error type server-side
 * (its full trace still rides along under "Caused by:").
 *
 * A [CancellationException] cause gets an extra diagnostic note: it is
 * control-flow, not a real error, so logging it as one points at a faulty
 * `catch (e: Exception)`. The fresh stack trace captured when the carrier is
 * constructed (down the Timber call chain from the offending `catch`) makes
 * that site visible in the report.
 */
fun buildAcraReportThrowable(
    priority: Int,
    tag: String?,
    message: String,
    cause: Throwable,
): Throwable {
    val causeType = cause::class.simpleName ?: "Throwable"
    val header = "[${priorityLabel(priority)}/${tag ?: "Unknown"}] $causeType: $message"
    val fullMessage = if (cause is CancellationException) {
        "$header — DIAGNOSIS: CancellationException improperly caught and logged as an error."
    } else {
        header
    }
    return LoggedThrowable(fullMessage, cause)
}

/**
 * Maps an `android.util.Log` priority to its single-letter logcat label.
 *
 * The literals mirror `android.util.Log`, which `:domain` cannot import as a
 * pure-Kotlin module, so each is pinned to its constant below. `AcraTree` only
 * forwards WARN+ (V/D/I are unreachable via that path), but the helper is kept
 * deliberately general.
 */
private fun priorityLabel(priority: Int): String = when (priority) {
    2 -> "V" // Log.VERBOSE
    3 -> "D" // Log.DEBUG
    4 -> "I" // Log.INFO
    5 -> "W" // Log.WARN
    6 -> "E" // Log.ERROR
    7 -> "A" // Log.ASSERT
    else -> priority.toString()
}

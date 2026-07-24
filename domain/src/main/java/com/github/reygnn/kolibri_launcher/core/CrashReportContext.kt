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
class LoggedThrowable(message: String, cause: Throwable) : RuntimeException(message, cause)

/**
 * Wraps [cause] in a [LoggedThrowable] whose message encodes the logcat-style
 * context `"[<level>/<tag>] <message>"`.
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
    val header = "[${priorityLabel(priority)}/${tag ?: "Unknown"}] $message"
    val fullMessage = if (cause is CancellationException) {
        "$header — DIAGNOSIS: CancellationException improperly caught and logged as an error."
    } else {
        header
    }
    return LoggedThrowable(fullMessage, cause)
}

/** Maps an `android.util.Log` priority to its single-letter logcat label. */
private fun priorityLabel(priority: Int): String = when (priority) {
    2 -> "V"
    3 -> "D"
    4 -> "I"
    5 -> "W"
    6 -> "E"
    7 -> "A"
    else -> priority.toString()
}

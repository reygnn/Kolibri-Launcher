package com.github.reygnn.kolibri_launcher.crashreporting.ingestion

/**
 * Single ANR record extracted from [ApplicationExitInfo]. Pure-data, no Android
 * types — fine to log, serialise, attach to ACRA reports etc.
 *
 * @property timestamp Wallclock millis when the system registered the ANR.
 * @property description System-supplied short description, e.g. "Input
 *     dispatching timed out (Waiting because no window has focus…)".
 * @property importance Process importance at ANR time (one of the
 *     `RunningAppProcessInfo.IMPORTANCE_*` constants).
 * @property threadDump Full multi-thread dump including held locks, or null if
 *     the system did not supply one (or reading it failed).
 */
data class AnrReport(
    val timestamp: Long,
    val description: String,
    val importance: Int,
    val threadDump: String?,
)

/**
 * Synthetic throwable carrying a post-mortem ANR into the single delivery path
 * ([AcraTree]). No longer implements a throttle-bypass marker: the client-side
 * throttle is gone (B3), so there is nothing to opt out of — ANR dedup is the
 * [AnrReporter] watermark alone.
 */
class AnrException(message: String) : RuntimeException(message)

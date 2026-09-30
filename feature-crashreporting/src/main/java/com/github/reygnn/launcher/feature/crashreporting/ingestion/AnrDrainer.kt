package com.github.reygnn.launcher.feature.crashreporting.ingestion

/**
 * The ANR-drain seam: the crash-reporting bootstrap depends on this interface,
 * not on the concrete [AnrReporter].
 *
 * [AnrReporter] (this module, since SPEC_NYX_REWRITE 1c-1) is the production
 * implementation in both apps. The narrow port stays so the bootstrap can be
 * exercised with a fake drainer. (It was introduced when the reporter still lived
 * in Kolibri's :app and wrote into that app's settings store; moving the watermark
 * into this module's own store removed that coupling.)
 */
interface AnrDrainer {
    suspend fun reportPendingAnrs(handler: suspend (AnrReport) -> Unit)
}

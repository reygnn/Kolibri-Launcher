package com.github.reygnn.launcher.feature.crashreporting.ingestion

/**
 * The ANR-drain seam: the crash-reporting bootstrap depends on this interface,
 * not on the concrete [AnrReporter].
 *
 * Rationale (MONOREPO_MERGE_SPEC §3 / AcraConfig sibling): [AnrReporter] is
 * app-specific — it persists its dedup watermark in the app's *settings*
 * DataStore and registers that key with the settings-store keep-list
 * (`OwnsSettingsStoreKeys` + `@IntoSet`). That machinery cannot move into a
 * neutral shared feature module, so the bootstrap takes this narrow port
 * instead; each app supplies its own drainer (or a no-op).
 */
interface AnrDrainer {
    suspend fun reportPendingAnrs(handler: suspend (AnrReport) -> Unit)
}

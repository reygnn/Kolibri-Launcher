package com.github.reygnn.nyx_launcher

import com.github.reygnn.kolibri_launcher.crashreporting.ingestion.AnrDrainer
import com.github.reygnn.kolibri_launcher.crashreporting.ingestion.AnrReport

/**
 * Nyx has no ANR-watermark store yet (that is AnrReporter's app-specific
 * settings-store machinery, which stayed in Kolibri). The shared crash-reporting
 * bootstrap still expects an [AnrDrainer]; Nyx supplies this no-op so ACRA init,
 * consent gating and the delivery tree all run — only the post-mortem ANR drain
 * is skipped. A real Nyx AnrReporter can replace this later.
 */
class NoOpAnrDrainer : AnrDrainer {
    override suspend fun reportPendingAnrs(handler: suspend (AnrReport) -> Unit) {
        // no pending ANRs to drain
    }
}

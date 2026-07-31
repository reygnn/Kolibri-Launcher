package com.github.reygnn.kolibri_launcher.crashreporting.resilience

import android.content.Context
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import org.acra.file.ReportLocator
import java.io.File
import javax.inject.Inject

/**
 * Baseline pipeline liveness (ACRA_SPEC.md G3-C). A dead sender/server shows up
 * as unsent report files piling up; this reads that backlog from the main
 * process via ACRA's public [ReportLocator] — no second sender, no cross-process
 * store. Feeds the Settings dev screen (§8c).
 *
 * Growing backlog = sender/server dead; empty = healthy OR never crashed
 * (deliberately ambiguous — the accepted limit of C over the full send-marker,
 * which would need the single cross-process file exception; §11/§X4).
 *
 * Not crash-infra: runs only on a dev-screen tap, so a read failure reports via
 * `silentError` (loud in DEBUG) and returns an empty backlog.
 *
 * The file sources are a seam so the count/oldest logic is JVM-testable without
 * a real ACRA report directory.
 */
class PipelineBacklogProbe internal constructor(
    private val approvedFiles: () -> List<File>,
    private val unapprovedFiles: () -> List<File>,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        approvedFiles = { ReportLocator(context).approvedReports.toList() },
        unapprovedFiles = { ReportLocator(context).unapprovedReports.toList() },
    )

    /**
     * @property approved count of persisted-but-approved-not-yet-sent reports.
     * @property unapproved count of unapproved reports.
     * @property oldestMillis `lastModified` of the oldest report across both
     *   folders, or `null` when both are empty.
     */
    data class Backlog(val approved: Int, val unapproved: Int, val oldestMillis: Long?)

    fun read(): Backlog {
        return try {
            val approved = approvedFiles()
            val unapproved = unapprovedFiles()
            val oldest = (approved + unapproved)
                .map { it.lastModified() }
                .filter { it > 0L }
                .minOrNull()
            Backlog(approved = approved.size, unapproved = unapproved.size, oldestMillis = oldest)
        } catch (t: Throwable) {
            TimberWrapper.silentError(t, "Failed to read ACRA report backlog")
            Backlog(approved = 0, unapproved = 0, oldestMillis = null)
        }
    }
}

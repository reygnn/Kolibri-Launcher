package com.github.reygnn.kolibri_launcher.crashreporting.consent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.acra.ACRA
import org.acra.file.BulkReportDeleter
import javax.inject.Inject

/**
 * Seam over ACRA's in-memory enable/disable AND the unsent-report queue purge.
 *
 * Behind an interface so [ConsentController] can drive ACRA while staying
 * JVM-testable — tests supply a fake and verify the toggle/purge were driven,
 * without the ACRA singleton. Keeps the ACRA side effect in the app layer
 * (never `:domain`).
 */
interface AcraToggle {

    /** Flips ACRA's volatile `enabled` flag — the single privacy gate (B1). */
    fun setEnabled(enabled: Boolean)

    /**
     * Deletes the unsent ACRA report queue. Called on revoke: a switch to
     * denied is destructive (A7), so no report created during the consent
     * phase can drain after a later re-consent.
     */
    fun purgeReportQueue()
}

class AcraToggleImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AcraToggle {

    override fun setEnabled(enabled: Boolean) {
        ACRA.errorReporter.setEnabled(enabled)
    }

    override fun purgeReportQueue() {
        // Drop BOTH folders — ACRA holds ACRA-approved AND ACRA-unapproved;
        // deleting only one leaves half the queue behind (§13). Official API
        // (BulkReportDeleter), no reach into private directories. nrToKeep = 0
        // clears everything.
        val deleter = BulkReportDeleter(context)
        deleter.deleteReports(true, 0)
        deleter.deleteReports(false, 0)
    }
}

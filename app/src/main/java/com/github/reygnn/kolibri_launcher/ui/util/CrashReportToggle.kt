package com.github.reygnn.kolibri_launcher.ui.util

import org.acra.ACRA
import javax.inject.Inject

/**
 * Seam over ACRA's in-memory enable/disable.
 *
 * Behind an interface so [CrashReportConsentController] can apply a consent
 * decision to ACRA while staying JVM-testable — tests supply a fake and
 * verify the toggle was driven, without the ACRA singleton. Same pattern as
 * [com.github.reygnn.kolibri_launcher.ui.main.AppLauncher].
 *
 * This keeps the ACRA side effect in the app layer (never in `:domain`),
 * exactly where it was before it moved out of the UI callers (AUDIT-10 #12).
 */
interface CrashReportToggle {
    fun setEnabled(enabled: Boolean)
}

class CrashReportToggleImpl @Inject constructor() : CrashReportToggle {
    override fun setEnabled(enabled: Boolean) {
        ACRA.errorReporter.setEnabled(enabled)
    }
}

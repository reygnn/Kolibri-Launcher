package com.github.reygnn.kolibri_launcher.crashreporting.consent

import android.content.Context
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.MainDispatcher
import com.github.reygnn.kolibri_launcher.ui.util.showToastSafe
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Seam for telling the user that a consent choice could not be saved.
 *
 * Behind an interface for the same reason as [AcraToggle]: it keeps the
 * Android side effect (a toast, on the main thread) in the app layer while
 * leaving [ConsentController] JVM-testable — tests supply a fake and verify
 * the user was informed, without a `Context` or a Looper.
 *
 * Why this exists: a failed persist would otherwise be invisible. The
 * in-memory ACRA state follows the tap immediately and the confirmation toast
 * fires either way, so a user who revokes consent while the write fails is
 * told it worked, and the next launch quietly re-affirms the OLD stored
 * decision — nothing re-asks (A4; see the [ConsentWriteResult] KDoc). Logging
 * that only reaches DEBUG builds is not enough for a decision the user
 * believes they made.
 */
interface ConsentSaveFailureNotifier {
    suspend fun notifySaveFailed()
}

class ConsentSaveFailureNotifierImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : ConsentSaveFailureNotifier {

    /**
     * Hops to the main thread itself, so callers can stay on whatever scope
     * owns the write (the app scope runs on the Default dispatcher). The toast
     * goes through [showToastSafe], which owns the StrictMode relax and the
     * Throwable catch — so a failing toast cannot escalate a failed write into
     * a crash.
     */
    override suspend fun notifySaveFailed() = withContext(mainDispatcher) {
        context.showToastSafe(R.string.toast_crash_report_consent_save_failed)
    }
}

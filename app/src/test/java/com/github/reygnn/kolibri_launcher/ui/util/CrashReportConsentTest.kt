package com.github.reygnn.kolibri_launcher.ui.util

import android.content.Context
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * JVM regression test for [CrashReportConsent.forceShowConsentDialog].
 *
 * Pins the AUDIT-10 #6 fix: a dialog that CANNOT be shown must report the
 * failure via a `null` return only — it must NOT invoke `onResult`. Before
 * the fix the helper called `onResult(false)` on its failure paths, which
 * the callers wired to `persistConsent(false)`, permanently writing a
 * decline the user never made and suppressing the dialog forever.
 *
 * The non-Activity path is the one failure mode reachable without an
 * Android runtime, so it is covered here on the JVM. The `show()`-throw
 * path shares the exact same "log + return null, no onResult" contract;
 * forcing a real `WindowManager.BadTokenException` would need an emulator
 * and buys no extra assurance over pinning the contract on the reachable
 * path. The happy path (real dialog + button taps) is pinned separately in
 * `CrashReportConsentRobolectricTest`.
 */
class CrashReportConsentTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    @Test
    fun `non-Activity context returns null and never invokes onResult`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // A plain Context (not an Activity) cannot host a dialog.
            val nonActivityContext = mockk<Context>()
            val reported = mutableListOf<Boolean>()

            val dialog = CrashReportConsent.forceShowConsentDialog(nonActivityContext) {
                reported.add(it)
            }

            assertNull("No dialog can be shown without an Activity context", dialog)
            assertTrue(
                "A show failure must NOT invoke onResult — otherwise it would " +
                    "persist a decline the user never made (AUDIT-10 #6)",
                reported.isEmpty()
            )
        }

    @Test
    fun `non-Activity context in particular does not report a decline`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val nonActivityContext = mockk<Context>()
            var declineReported = false

            CrashReportConsent.forceShowConsentDialog(nonActivityContext) { userGaveConsent ->
                if (!userGaveConsent) declineReported = true
            }

            assertEquals(
                "The old regression fired onResult(false) here; the fix must not",
                false,
                declineReported
            )
        }
}

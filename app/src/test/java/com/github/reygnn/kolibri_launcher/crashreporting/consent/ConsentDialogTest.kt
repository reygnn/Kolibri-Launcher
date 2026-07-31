package com.github.reygnn.kolibri_launcher.crashreporting.consent

import android.content.Context
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * JVM regression test for [ConsentDialog.show].
 *
 * Pins the A3 / AUDIT-10 #6 fix: a dialog that CANNOT be shown must report the
 * failure via a `null` return only — it must NOT invoke `onResult`. Routing a
 * show-failure through `onResult(false)` would let the caller persist a decline
 * the user never made and suppress the dialog forever.
 *
 * The non-Activity path is the one failure mode reachable without an Android
 * runtime, so it is covered here on the JVM. The `show()`-throw path shares the
 * exact same "log + return null, no onResult" contract. The happy path (real
 * dialog + button taps) is pinned in `ConsentDialogRobolectricTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentDialogTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    @Test
    fun `non-Activity context returns null and never invokes onResult`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val nonActivityContext = mockk<Context>()
            val reported = mutableListOf<Boolean>()

            val dialog = ConsentDialog.show(nonActivityContext) { reported.add(it) }

            assertNull("No dialog can be shown without an Activity context", dialog)
            assertTrue(
                "A show failure must NOT invoke onResult — otherwise it would " +
                    "persist a decline the user never made (A3)",
                reported.isEmpty(),
            )
        }

    @Test
    fun `non-Activity context in particular does not report a decline`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val nonActivityContext = mockk<Context>()
            var declineReported = false

            ConsentDialog.show(nonActivityContext) { granted ->
                if (!granted) declineReported = true
            }

            assertEquals("A show failure must not fire onResult(false)", false, declineReported)
        }
}

package com.github.reygnn.kolibri_launcher.crashreporting.consent

import android.content.Intent
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.kolibri_launcher.HiltTestActivity
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric happy-path pins for [ConsentDialog.show].
 *
 * Complements the JVM [ConsentDialogTest] (which pins the A3 failure path).
 * This one needs a real Activity + a real AlertDialog, so it runs under
 * Robolectric, hosted in the project's [HiltTestActivity] (`src/debug/`). It
 * pins two things:
 *
 *  - A shown dialog is RETURNED (non-null), so the caller can track and dismiss
 *    it on teardown — a visible `setCancelable(false)` dialog must never be
 *    discarded to null (AUDIT-10 #9).
 *  - Each button tap invokes `onResult` exactly once with the correct value,
 *    and `onResult` never fires before a tap — a real user decision is the ONLY
 *    thing that reaches `onResult` (A3).
 */
@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
class ConsentDialogRobolectricTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private fun launchHost(): ActivityScenario<HiltTestActivity> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), HiltTestActivity::class.java)
        return ActivityScenario.launch(intent)
    }

    @Test
    fun `accept tap reports true exactly once and the dialog is returned`() =
        runTest(mainDispatcherRule.testDispatcher) {
            launchHost().use { scenario ->
                lateinit var activity: HiltTestActivity
                scenario.onActivity { activity = it }

                val reported = mutableListOf<Boolean>()
                val dialog = ConsentDialog.show(activity) { reported.add(it) }

                assertNotNull(
                    "A shown dialog must be returned so the caller can track and dismiss it (AUDIT-10 #9)",
                    dialog,
                )
                assertTrue("onResult must not fire before a tap", reported.isEmpty())

                dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                // AlertDialog dispatches button clicks through a Handler, so the
                // listener runs on the next looper turn, not inline.
                shadowOf(Looper.getMainLooper()).idle()

                assertEquals(listOf(true), reported)
            }
        }

    @Test
    fun `decline tap reports false exactly once`() =
        runTest(mainDispatcherRule.testDispatcher) {
            launchHost().use { scenario ->
                lateinit var activity: HiltTestActivity
                scenario.onActivity { activity = it }

                val reported = mutableListOf<Boolean>()
                val dialog = ConsentDialog.show(activity) { reported.add(it) }

                assertNotNull(dialog)
                dialog!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()

                assertEquals(listOf(false), reported)
            }
        }
}

package com.github.reygnn.kolibri_launcher.ui.onboarding

import androidx.test.core.app.ActivityScenario
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric pilot for §2's missing test safety net.
 *
 * Goal: verify the smallest meaningful Activity (`OnboardingActivity`) launches
 * to RESUMED state without crashing under Robolectric + Hilt. If this is stable,
 * the same pattern can underwrite the §2 try/catch sweep on UI files.
 *
 * Setup notes:
 * - `@HiltAndroidTest` + `HiltTestApplication` give us a Hilt-managed
 *   Application without involving the production [com.github.reygnn.kolibri_launcher
 *   .KolibriLauncherApp] (which would try to initialise ACRA, register a
 *   PackageReceiver, etc. — none of that should run in tests).
 * - Production Hilt modules are still in effect; only `@TestInstallIn` would
 *   replace them. For the pilot we use the real bindings and accept that the
 *   few file-touching ones (DataStore, DataMigrationManager) get exercised
 *   on the Robolectric in-memory filesystem.
 */
@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
class OnboardingActivityRobolectricTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun `activity launches without crashing`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("Activity instance must be non-null after launch", activity)
            }
        }
    }
}

package com.github.reygnn.kolibri_launcher.ui.main

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
 * Robolectric smoke test for MainActivity, the launcher's host activity.
 *
 * Goal: verify the activity reaches a usable state under Robolectric + Hilt
 * without crashing. Doubles as the second test of the pattern from
 * `OnboardingActivityRobolectricTest` and proves the approach scales to a
 * larger activity (Hilt graph touching wallpaper, navigation, multiple
 * delegates).
 */
@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
class MainActivityRobolectricTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun `activity launches without crashing`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("MainActivity instance must be non-null after launch", activity)
            }
        }
    }
}

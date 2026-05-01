package com.github.reygnn.kolibri_launcher.ui.customnames

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
 * Robolectric smoke-test backstop for the CustomNamesActivity §2 sweep.
 * Same pattern as OnboardingActivityRobolectricTest / MainActivityRobolectricTest.
 */
@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
class CustomNamesActivityRobolectricTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun `activity launches without crashing`() {
        ActivityScenario.launch(CustomNamesActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
            }
        }
    }
}

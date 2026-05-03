package com.github.reygnn.kolibri_launcher.ui.home

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.kolibri_launcher.HiltTestActivity
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
 * Robolectric smoke-test backstop for HomeFragment, written as the
 * mandatory pre-cursor to the TODO §A try/catch sweep.
 *
 * The fragment is hosted in the project's existing [HiltTestActivity]
 * (defined in `src/debug/`, registered in the debug manifest, available to
 * Robolectric on the debug variant). Same pattern as
 * [AppDrawerFragmentRobolectricTest] — HomeFragment also uses
 * `activityViewModels<LauncherViewModel>()` and therefore needs a
 * Hilt-aware host activity to resolve the activity-scoped @HiltViewModel.
 */
@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
class HomeFragmentRobolectricTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun `fragment attaches to host activity without crashing`() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            HiltTestActivity::class.java
        )
        ActivityScenario.launch<HiltTestActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = HomeFragment()
                activity.supportFragmentManager.beginTransaction()
                    .add(android.R.id.content, fragment, "test")
                    .commitNow()

                assertNotNull(
                    "HomeFragment must attach without throwing",
                    activity.supportFragmentManager.findFragmentByTag("test")
                )
            }
        }
    }
}

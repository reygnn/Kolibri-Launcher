package com.github.reygnn.kolibri_launcher.ui.appcontextmenu

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.kolibri_launcher.HiltTestActivity
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.MenuContext
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
 * Robolectric smoke-test backstop for the AppContextMenuDialogFragment §2 sweep.
 * Hosted in HiltTestActivity (lives in src/debug/), so the test belongs in
 * src/testDebug/.
 */
@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
class AppContextMenuDialogFragmentRobolectricTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun `dialog fragment shows without crashing`() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            HiltTestActivity::class.java
        )
        ActivityScenario.launch<HiltTestActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val appInfo = AppInfo(
                    originalName = "Test",
                    displayName = "Test",
                    packageName = "com.example.test",
                    className = ".TestActivity"
                )
                val dialog = AppContextMenuDialogFragment.newInstance(
                    appInfo = appInfo,
                    context = MenuContext.HOME_SCREEN,
                    hasUsageData = false
                )
                dialog.show(activity.supportFragmentManager, "test")
                activity.supportFragmentManager.executePendingTransactions()

                assertNotNull(activity.supportFragmentManager.findFragmentByTag("test"))
            }
        }
    }
}

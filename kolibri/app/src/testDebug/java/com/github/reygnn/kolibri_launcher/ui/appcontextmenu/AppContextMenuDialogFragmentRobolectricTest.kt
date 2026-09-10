package com.github.reygnn.kolibri_launcher.ui.appcontextmenu

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.kolibri_launcher.HiltTestActivity
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.MenuContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    // --- AUDIT-3 #2: crash-safety on malformed / missing arguments ---
    //
    // onCreate's parse-failure paths call dismiss() and return, but the same
    // lifecycle pass still runs onViewCreated. Before the argsValid guard,
    // onViewCreated dereferenced the uninitialised lateinit appInfo/menuContext
    // and threw UninitializedPropertyAccessException — so executePendingTransactions
    // below would rethrow and fail the test. With the guard it dismisses cleanly.

    /** onCreate catch branch: `requireArguments()` throws when no args are set. */
    @Test
    fun `dialog with no arguments dismisses without crashing`() {
        showWithArgumentsAndAssertDismissed(arguments = null)
    }

    /** onCreate null-parcelable branch: args present but ARG_APP_INFO absent. */
    @Test
    fun `dialog with missing AppInfo arg dismisses without crashing`() {
        showWithArgumentsAndAssertDismissed(arguments = Bundle())
    }

    private fun showWithArgumentsAndAssertDismissed(arguments: Bundle?) {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            HiltTestActivity::class.java
        )
        ActivityScenario.launch<HiltTestActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = AppContextMenuDialogFragment().apply {
                    this.arguments = arguments
                }
                val fm = activity.supportFragmentManager

                // Drives onCreate -> onViewCreated synchronously; the pre-fix
                // UninitializedPropertyAccessException would rethrow out of here.
                fragment.show(fm, "menu")
                fm.executePendingTransactions()

                // Flush the dismiss()-scheduled removal transaction.
                shadowOf(Looper.getMainLooper()).idle()
                fm.executePendingTransactions()

                assertNull(
                    "Fragment must have dismissed itself cleanly, not crashed",
                    fm.findFragmentByTag("menu")
                )
            }
        }
    }
}

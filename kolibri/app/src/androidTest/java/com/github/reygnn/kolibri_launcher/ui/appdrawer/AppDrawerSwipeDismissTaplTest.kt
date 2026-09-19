package com.github.reygnn.kolibri_launcher.ui.appdrawer

import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.tapl.Launcher
import com.github.reygnn.launcher.core.crashreporting.consent.ConsentDecision
import com.github.reygnn.launcher.feature.crashreporting.consent.ConsentBootstrap
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The facade port of AppDrawerSwipeDismissTest. Same coverage — open drawer
 * through the production onFlingUp path, fast swipe-down commits
 * DragToDismissCore, overlay hides — but the sync/gesture mechanics live in the
 * AppDrawer page object now, so the test body reads as intent.
 *
 * The original AppDrawerSwipeDismissTest can be deleted once this is green; kept
 * side by side here only for the review diff. Conventions unchanged: no
 * MainDispatcherRule (INSTRUMENTED_TESTING_NOTES §1), Hilt at order 0, seeding
 * in @Before before launch.
 */
@HiltAndroidTest
class AppDrawerSwipeDismissTaplTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var settings: SettingsRepository

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            settings.setOnboardingCompleted()
            ConsentBootstrap.seedDecision(ctx, ConsentDecision.Denied)
        }
    }

    @Test
    fun fastSwipeDownOnDrawer_dismissesOverlay() {
        Launcher.start().use { launcher ->
            launcher.openAppDrawer()   // waits for the overlay + list internally
                .swipeDownToDismiss()  // gesture + waits for app_drawer_root gone
        }
    }
}

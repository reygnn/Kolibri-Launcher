package com.github.reygnn.kolibri_launcher.ui

import android.app.Activity
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.di.AppLauncherModule
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.tapl.Launcher
import com.github.reygnn.kolibri_launcher.ui.main.AppLauncher
import com.github.reygnn.launcher.common.ui.AppLaunchResult
import com.github.reygnn.launcher.core.crashreporting.consent.ConsentDecision
import com.github.reygnn.launcher.feature.crashreporting.consent.ConsentBootstrap
import com.github.reygnn.launcher.testing.awaitUntil
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Why instrumented: a real horizontal home swipe must fire the assigned swipe-slot
 * action. The device-worthy seam is the whole touch pipeline —
 * HomeGestureLayout analyses a real left-to-right fling -> onSwipeRight ->
 * viewModel.onSwipeFromLeftToRight -> HandleSwipeActionUseCase(SWIPE_FROM_LEFT_TO_RIGHT)
 * -> UiEvent.LaunchApp -> AppLauncher.launch. The use-case decision is JVM-covered;
 * this covers the gesture -> launch wiring end-to-end.
 *
 * The terminal launch uses LauncherApps.startMainActivity (a system call Espresso
 * Intents cannot intercept and that would foreground a real app), so the
 * AppLauncher binding is replaced with a recording fake (AppLauncherModule
 * uninstalled). The gesture pipeline is exercised for real; only the system
 * launch is captured. The slot is assigned from a real loaded app's own
 * componentName so the use-case's getCurrentApps() lookup matches exactly.
 */
@HiltAndroidTest
@UninstallModules(AppLauncherModule::class)
class SwipeSlotActionTaplTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    private val recordingLauncher = RecordingAppLauncher()

    @BindValue @JvmField val appLauncher: AppLauncher = recordingLauncher

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var swipeActions: SwipeActionsRepository
    @Inject lateinit var installedApps: InstalledAppsStateRepository

    private var expectedComponent: String? = null

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            settings.setOnboardingCompleted()
            ConsentBootstrap.seedDecision(ctx, ConsentDecision.Denied)
        }
    }

    @Test
    fun swipeLeftToRight_launchesAssignedApp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        )

        Launcher.start().use { launcher ->
            launcher.home()

            // Wait for the app list to load, then assign the LEFT_TO_RIGHT slot to
            // a real app using its own componentName (exact match for the use case).
            var app: AppInfo? = null
            awaitUntil(timeoutMs = 10_000, describe = { "installed apps never loaded" }) {
                app = installedApps.getCurrentApps().firstOrNull()
                app != null
            }
            val target = app!!
            expectedComponent = target.componentName
            runBlocking {
                swipeActions.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, target.componentName)
            }
            assumeTrue("Assigned component must be resolvable", target.componentName.isNotBlank())

            launcher.home().swipeLeftToRight()

            awaitUntil(
                timeoutMs = 10_000,
                describe = { "swipe never launched the assigned app; launched=${recordingLauncher.launched?.componentName}" },
            ) {
                recordingLauncher.launched?.componentName == target.componentName
            }
        }

        assertThat(recordingLauncher.launched?.componentName).isEqualTo(expectedComponent)
    }
}

/** Records the app handed to [launch] instead of hitting LauncherApps. */
private class RecordingAppLauncher : AppLauncher {
    @Volatile var launched: AppInfo? = null
    override fun launch(activity: Activity, appInfo: AppInfo): AppLaunchResult {
        launched = appInfo
        return AppLaunchResult.Launched
    }
}

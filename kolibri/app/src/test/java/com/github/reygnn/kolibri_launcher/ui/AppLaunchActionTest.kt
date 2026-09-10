package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.main.AppLaunchAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Pure JVM tests for [AppLaunchAction.Companion.decide]. No MockK,
 * no Robolectric — same shape as [RenameDecisionTest], [WallpaperSaveActionTest],
 * [LayerButtonsStateTest].
 *
 * The class itself only encodes the navigation-state decision; the side
 * effects (calling `navController.popBackStack()` and `launchApp(...)`)
 * stay in `MainActivity` and are exercised manually / via integration
 * runs of the launcher.
 */
class AppLaunchActionTest {

    @get:Rule
    val timberRule = TimberRule()

    private val sampleApp = AppInfo(
        originalName = "Camera",
        displayName = "Camera",
        packageName = "com.example.camera",
        className = "com.example.camera.MainActivity",
    )

    private val drawerId = 100
    private val homeId = 200

    @Test
    fun `decide returns PopThenLaunch when current destination is the drawer`() {
        val result = AppLaunchAction.decide(
            currentDestinationId = drawerId,
            drawerDestinationId = drawerId,
            app = sampleApp,
        )
        assertEquals(AppLaunchAction.PopThenLaunch(sampleApp), result)
    }

    @Test
    fun `decide returns JustLaunch when current destination is not the drawer`() {
        val result = AppLaunchAction.decide(
            currentDestinationId = homeId,
            drawerDestinationId = drawerId,
            app = sampleApp,
        )
        assertEquals(AppLaunchAction.JustLaunch(sampleApp), result)
    }

    @Test
    fun `decide returns JustLaunch when current destination is null`() {
        // NavController not yet set up — pop would fail, so we skip it.
        val result = AppLaunchAction.decide(
            currentDestinationId = null,
            drawerDestinationId = drawerId,
            app = sampleApp,
        )
        assertEquals(AppLaunchAction.JustLaunch(sampleApp), result)
    }

    @Test
    fun `decide forwards the app instance unchanged in both branches`() {
        // Sanity check that both branches return the same app reference,
        // not a defensive copy or a remapped variant. Callers rely on
        // this to dispatch `launchApp(action.app)` regardless of branch.
        val popResult = AppLaunchAction.decide(
            currentDestinationId = drawerId,
            drawerDestinationId = drawerId,
            app = sampleApp,
        )
        val justResult = AppLaunchAction.decide(
            currentDestinationId = homeId,
            drawerDestinationId = drawerId,
            app = sampleApp,
        )
        assertEquals(sampleApp, popResult.app)
        assertEquals(sampleApp, justResult.app)
    }
}

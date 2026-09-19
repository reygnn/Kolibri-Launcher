package com.github.reygnn.kolibri_launcher.tapl

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.ui.main.MainActivity
import com.github.reygnn.launcher.testing.currentResumed
import com.github.reygnn.launcher.testing.onMainSync

/**
 * Entry point of the Kolibri test facade — the one object a test constructs
 * directly (analogous to AOSP TAPL's LauncherInstrumentation). Owns the
 * [scenario]; use in try-with-resources (`Launcher.start().use { ... }`).
 *
 * Deliberately NOT here: any launcher-side test-protocol / event bus. State is
 * detected from visible views + awaitUntil (see BasePage), which is why this
 * needs no production hooks beyond what already exists.
 *
 * Seeding (onboarding-completed, consent) belongs in the test's @Before, BEFORE
 * [start], so the facade stays stateless and each test configures explicitly
 * (INSTRUMENTED_TESTING_NOTES §4 keeps app-data cleanup in the orchestrator).
 */
internal class Launcher private constructor(
    val scenario: ActivityScenario<MainActivity>,
) : AutoCloseable {

    /** We are on Home once MainActivity is up and favourites render. */
    fun home(): Home = Home()

    /**
     * Opens the drawer through the PRODUCTION path
     * (onFlingUp -> UiEvent.ShowAppDrawer -> showDrawer), mirroring exactly
     * what AppDrawerSwipeDismissTest does by hand today — not by attaching a
     * fragment directly.
     */
    fun openAppDrawer(): AppDrawer {
        onMainSync {
            (currentResumed<MainActivity>()
                ?: error("MainActivity not RESUMED — cannot open drawer"))
                .viewModel.onFlingUp()
        }
        return AppDrawer(this)
    }

    override fun close(): Unit = scenario.close()

    companion object {
        /** Launches MainActivity and returns the facade positioned on Home. */
        fun start(): Launcher {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            // PM pre-warm: get the launcher list cached before MainActivity's
            // drawerApps pipeline starts, so a cold first launch doesn't stretch
            // past Espresso's RootViewPicker patience.
            ctx.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
            )
            val intent = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return Launcher(ActivityScenario.launch(intent))
        }
    }
}

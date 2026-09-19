package com.github.reygnn.nyx_launcher.tapl

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.nyx_launcher.home.MainActivity

/**
 * Entry point of the Nyx test facade — the one object a test constructs
 * directly (analogous to the Kolibri `Launcher`). Owns the [scenario]; use in
 * try-with-resources (`NyxLauncher.start().use { ... }`).
 *
 * State is detected from visible views + awaitUntil (see BasePage). The one
 * sanctioned exception is a minimal READ-ONLY drag-state hook
 * (`DragLayer.isDragArmed`): the drawer-drag arm→promote transition has no
 * visible/model signal to await, so a read-only reflection of existing state is
 * allowed there (no production writes, no test-only branches). Seeding (consent,
 * home layout) belongs in the test's @Before BEFORE [start], so the facade stays
 * stateless.
 */
internal class NyxLauncher private constructor(
    val scenario: ActivityScenario<MainActivity>,
) : AutoCloseable {

    /** We are on the home grid once MainActivity is up and the pager renders. */
    fun home(): NyxHome = NyxHome()

    override fun close(): Unit = scenario.close()

    companion object {
        fun start(): NyxLauncher {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            // PM pre-warm: cache the launcher list before the grid pipeline starts,
            // so a cold first launch doesn't stretch past Espresso's patience.
            ctx.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
            )
            val intent = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return NyxLauncher(ActivityScenario.launch(intent))
        }
    }
}

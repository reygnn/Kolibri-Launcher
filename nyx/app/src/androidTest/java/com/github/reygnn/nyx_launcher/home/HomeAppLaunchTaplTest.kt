package com.github.reygnn.nyx_launcher.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.launcher.common.ui.AppLaunchResult
import com.github.reygnn.launcher.common.ui.AppLauncher
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.crashreporting.consent.ConsentDecision
import com.github.reygnn.launcher.feature.crashreporting.consent.ConsentBootstrap
import com.github.reygnn.launcher.testing.awaitUntil
import com.github.reygnn.nyx_launcher.di.AppLauncherModule
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.tapl.NyxLauncher
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Why instrumented: verifies that a real tap on a home app routes through the shared
 * [AppLauncher] seam (`:common-ui`, backed in production by
 * `LauncherApps.startMainActivity`) with the tapped tile's [ComponentKey]. The tap →
 * grid-adapter onLaunch → `MainActivity.launchApp` → `appLauncher.launch` wiring is
 * device-only (touch dispatch), and this is the seam Nyx's own [AppLauncherModule]
 * `@UninstallModules` hook exists for — the parity companion to Kolibri's
 * `SwipeSlotActionTaplTest`. A [RecordingAppLauncher] replaces the real call because
 * `startMainActivity` is a system call Espresso Intents can't intercept.
 *
 * Setup: seed a deterministic single-app layout via the injected HomeLayoutRepository
 * BEFORE launch (so FirstRunSeeder is a no-op), plus a consent decision so the
 * first-launch dialog can't block the grid.
 */
@HiltAndroidTest
@UninstallModules(AppLauncherModule::class)
class HomeAppLaunchTaplTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    private val recordingLauncher = RecordingAppLauncher()

    @BindValue @JvmField val appLauncher: AppLauncher = recordingLauncher

    @Inject lateinit var homeLayout: HomeLayoutRepository
    @Inject @ApplicationContext lateinit var context: Context

    private val appId = ItemId("tapl-launch-app")
    private lateinit var seededKey: ComponentKey

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val resolved = ctx.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        )
        assumeTrue("Need ≥1 launchable app to seed the grid", resolved.isNotEmpty())
        val info = resolved.first().activityInfo
        seededKey = ComponentKey(info.packageName, info.name)

        runBlocking {
            ConsentBootstrap.seedDecision(context, ConsentDecision.Denied)
            homeLayout.save(
                HomeLayout(
                    grid = GridSpec(columns = 4, rows = 5),
                    pages = 1,
                    items = listOf(
                        PlacedItem(HomeItem.App(appId, seededKey), CellPos(page = 0, x = 0, y = 0)),
                    ),
                    dock = emptyList(),
                )
            )
        }
    }

    @Test
    fun tappingHomeApp_routesThroughSharedAppLauncher() {
        NyxLauncher.start().use { launcher ->
            launcher.home()

            // Wait until the seeded app has settled at (0,0,0) after any device re-grid, so the
            // tap lands on the laid-out tile rather than an empty cell.
            awaitUntil(timeoutMs = 10_000, describe = { "seeded app never settled at (0,0,0)" }) {
                runBlocking {
                    homeLayout.layout().first().items
                        .firstOrNull { it.item.id == appId }?.pos == CellPos(page = 0, x = 0, y = 0)
                }
            }

            launcher.home().tapCell(0)

            awaitUntil(
                timeoutMs = 5_000,
                describe = { "tap never reached AppLauncher; launched=${recordingLauncher.launched?.flat}" },
            ) {
                recordingLauncher.launched?.flat == seededKey.flat
            }
        }

        assertThat(recordingLauncher.launched?.flat).isEqualTo(seededKey.flat)
    }
}

/** Records the component handed to [launch] instead of hitting LauncherApps. */
private class RecordingAppLauncher : AppLauncher {
    @Volatile var launched: ComponentKey? = null
    override fun launch(activity: Activity, key: ComponentKey): AppLaunchResult {
        launched = key
        return AppLaunchResult.Launched
    }
}

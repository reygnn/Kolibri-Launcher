package com.github.reygnn.nyx_launcher.home

import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.crashreporting.consent.ConsentDecision
import com.github.reygnn.launcher.feature.crashreporting.consent.ConsentBootstrap
import com.github.reygnn.launcher.testing.awaitUntil
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
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Why instrumented: the Nyx home grid reorders via a custom long-press drag
 * engine (DragLayer captures the stream → DragController → DropZone → drop →
 * HomeViewModel.move → HomeLayoutRepository). Touch-slop promotion, the
 * DragLayer dispatch capture, and DropZone hit-testing are real-device
 * behaviour Robolectric can't dispatch. The pure decision logic
 * (DragController, geometry, move transitions) is already JVM-covered; this
 * covers the touch pipeline end-to-end.
 *
 * Setup: seed a deterministic single-app layout via the injected
 * HomeLayoutRepository BEFORE launch (so FirstRunSeeder is a no-op), plus a
 * consent decision so the first-launch dialog can't block the grid.
 *
 * What it asserts: after long-press-dragging the app from cell 0 onto empty
 * cell 1, the persisted layout places that item's id at cell (page 0, x 1, y 0).
 */
@HiltAndroidTest
class HomeGridDragReorderTaplTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var homeLayout: HomeLayoutRepository
    @Inject @ApplicationContext lateinit var context: Context

    private val appId = ItemId("tapl-drag-app")

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = ctx.packageManager.queryIntentActivities(launcherIntent, 0)
        assumeTrue("Need ≥1 launchable app to seed the grid", resolved.isNotEmpty())
        val info = resolved.first().activityInfo
        val key = ComponentKey(info.packageName, info.name)

        runBlocking {
            ConsentBootstrap.seedDecision(context, ConsentDecision.Denied)
            homeLayout.save(
                HomeLayout(
                    grid = GridSpec(columns = 4, rows = 5),
                    pages = 1,
                    items = listOf(
                        PlacedItem(HomeItem.App(appId, key), CellPos(page = 0, x = 0, y = 0)),
                    ),
                    dock = emptyList(),
                )
            )
        }
    }

    @Test
    fun dragAppToEmptyCell_persistsNewPosition() {
        NyxLauncher.start().use { launcher ->
            launcher.home()

            // Wait until the seeded app has settled at (0,0,0) after any device
            // re-grid, so the drag reads a laid-out source cell.
            awaitUntil(timeoutMs = 10_000, describe = { "seeded app never settled at (0,0,0)" }) {
                runBlocking {
                    homeLayout.layout().first().items
                        .firstOrNull { it.item.id == appId }?.pos == CellPos(0, 0, 0)
                }
            }

            launcher.home().dragCellWithinPage(from = 0, to = 1)

            awaitUntil(
                timeoutMs = 10_000,
                describe = {
                    val pos = runBlocking {
                        homeLayout.layout().first().items.firstOrNull { it.item.id == appId }?.pos
                    }
                    "app never moved to cell (0,1,0); pos=$pos"
                },
            ) {
                runBlocking {
                    homeLayout.layout().first().items
                        .firstOrNull { it.item.id == appId }?.pos == CellPos(0, 1, 0)
                }
            }
        }

        runBlocking {
            val pos = homeLayout.layout().first().items.first { it.item.id == appId }.pos
            assertThat(pos).isEqualTo(CellPos(0, 1, 0))
        }
    }
}

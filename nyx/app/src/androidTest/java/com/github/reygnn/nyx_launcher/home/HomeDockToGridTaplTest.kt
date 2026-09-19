package com.github.reygnn.nyx_launcher.home

import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.crashreporting.consent.ConsentDecision
import com.github.reygnn.launcher.feature.crashreporting.consent.ConsentBootstrap
import com.github.reygnn.launcher.testing.awaitUntil
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
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
 * Why instrumented: the reverse of [HomeGridDragToDockTaplTest] — dragging a DOCK
 * item back up onto the grid. It exercises the grid drop zone (rectInDragLayer(
 * pager) -> resolveGridDrop -> DropTarget.Cell) for a payload that STARTED in the
 * dock, through the real DragLayer touch pipeline. The move transition is
 * JVM-covered; this covers the cross-region dock->grid touch drop.
 *
 * Seeding: one dock app, empty grid (dock non-empty -> no first-run seed). The
 * dock item arms via homeRoot.armDrag like a grid item, so a fixed long-press
 * hold suffices (no isDragArmed hook needed here). Assertion: the app ends up on
 * the grid and leaves the dock.
 */
@HiltAndroidTest
class HomeDockToGridTaplTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var homeLayout: HomeLayoutRepository
    @Inject @ApplicationContext lateinit var context: Context

    private val appId = ItemId("tapl-dock-to-grid-app")
    private lateinit var key: ComponentKey

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val resolved = ctx.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        )
        assumeTrue("Need ≥1 launchable app", resolved.isNotEmpty())
        key = ComponentKey(resolved[0].activityInfo.packageName, resolved[0].activityInfo.name)

        runBlocking {
            ConsentBootstrap.seedDecision(context, ConsentDecision.Denied)
            // One dock app (dock non-empty → no first-run seed), empty grid.
            homeLayout.save(
                HomeLayout(
                    grid = GridSpec(4, 5),
                    pages = 1,
                    items = emptyList(),
                    dock = listOf(HomeItem.App(appId, key)),
                )
            )
        }
    }

    @Test
    fun dragDockItemToGrid_movesItOntoTheGrid() {
        NyxLauncher.start().use { launcher ->
            launcher.home()
            awaitUntil(timeoutMs = 10_000, describe = { "seeded app never settled in the dock" }) {
                runBlocking { homeLayout.layout().first().dock.any { it is HomeItem.App && it.key == key } }
            }

            launcher.home().dragDockItemToCell(dockIndex = 0, toCell = 0)

            awaitUntil(
                timeoutMs = 10_000,
                describe = {
                    val l = runBlocking { homeLayout.layout().first() }
                    "app never moved onto the grid; items=${l.items.map { it.item }} dock=${l.dock}"
                },
            ) {
                runBlocking {
                    val l = homeLayout.layout().first()
                    l.items.any { it.item.id == appId } &&
                        l.dock.none { it is HomeItem.App && it.key == key }
                }
            }
        }

        runBlocking {
            val l = homeLayout.layout().first()
            assertThat(l.items.map { it.item.id }).contains(appId)
            assertThat(l.dock.filterIsInstance<HomeItem.App>().map { it.key }).doesNotContain(key)
        }
    }
}

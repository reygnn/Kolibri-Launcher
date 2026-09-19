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
 * Why instrumented: dragging a grid item down into the dock exercises the dock
 * DropZone (rectInDragLayer(dock), priority remove > dock > grid) through the
 * real DragLayer touch pipeline — a drop over the dock inserts a DockSlot and
 * the item moves from the grid into the dock (HomeViewModel.move). The move
 * transition is JVM-covered; this covers the cross-region touch drop.
 */
@HiltAndroidTest
class HomeGridDragToDockTaplTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var homeLayout: HomeLayoutRepository
    @Inject @ApplicationContext lateinit var context: Context

    private val appId = ItemId("tapl-dock-app")
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
            // One grid app (items non-empty → no first-run seed), empty dock.
            homeLayout.save(
                HomeLayout(
                    grid = GridSpec(4, 5),
                    pages = 1,
                    items = listOf(PlacedItem(HomeItem.App(appId, key), CellPos(0, 0, 0))),
                    dock = emptyList(),
                )
            )
        }
    }

    @Test
    fun dragGridItemToDock_movesItIntoDock() {
        NyxLauncher.start().use { launcher ->
            launcher.home()
            awaitUntil(timeoutMs = 10_000, describe = { "seeded app never settled on the grid" }) {
                runBlocking { homeLayout.layout().first().items.any { it.item.id == appId } }
            }

            launcher.home().dragCellToDock(from = 0)

            awaitUntil(
                timeoutMs = 10_000,
                describe = {
                    val l = runBlocking { homeLayout.layout().first() }
                    "app never moved into the dock; items=${l.items.map { it.item }} dock=${l.dock}"
                },
            ) {
                runBlocking {
                    val l = homeLayout.layout().first()
                    l.dock.any { it is HomeItem.App && it.key == key } &&
                        l.items.none { it.item.id == appId }
                }
            }
        }

        runBlocking {
            val l = homeLayout.layout().first()
            assertThat(l.dock.filterIsInstance<HomeItem.App>().map { it.key }).contains(key)
            assertThat(l.items.map { it.item.id }).doesNotContain(appId)
        }
    }
}

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
 * Why instrumented: the hardest home-drag seam — carrying an icon to the
 * right pager edge, holding until the edge-advance flips to the next page
 * (DragController.onDragMove -> onDragEdge -> pager.setCurrentItem, timer-driven),
 * then dropping on the new page. Nothing but a real, continuous, dwelling touch
 * stream on a device exercises this: the promotion, the edge dwell timer, the
 * ViewPager2 settle, and the drop all happen in one gesture.
 *
 * Nyx always renders a single trailing empty landing page, so page 1 exists as a
 * drop target without seeding a second page. Assertion: the app's persisted
 * position ends up on page 1.
 */
@HiltAndroidTest
class HomeGridDragCrossPageTaplTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var homeLayout: HomeLayoutRepository
    @Inject @ApplicationContext lateinit var context: Context

    private val appId = ItemId("tapl-crosspage-app")

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
    fun dragAppToNextPage_persistsOnPageOne() {
        NyxLauncher.start().use { launcher ->
            launcher.home()

            awaitUntil(timeoutMs = 10_000, describe = { "seeded app never settled at (0,0,0)" }) {
                runBlocking {
                    homeLayout.layout().first().items
                        .firstOrNull { it.item.id == appId }?.pos == CellPos(0, 0, 0)
                }
            }

            launcher.home().dragCellToNextPage(from = 0, to = 0)

            awaitUntil(
                timeoutMs = 15_000,
                describe = {
                    val pos = runBlocking {
                        homeLayout.layout().first().items.firstOrNull { it.item.id == appId }?.pos
                    }
                    "app never moved to page 1; pos=$pos"
                },
            ) {
                runBlocking {
                    homeLayout.layout().first().items
                        .firstOrNull { it.item.id == appId }?.pos?.page == 1
                }
            }
        }

        runBlocking {
            val pos = homeLayout.layout().first().items.first { it.item.id == appId }.pos
            assertThat(pos.page).isEqualTo(1)
        }
    }
}

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
 * Why instrumented: the edge-auto-scroll FINESSE the single-page cross-page drag
 * doesn't reach — holding a drag at the pager edge long enough that the
 * edge-advance RE-ARMS and flips across TWO pages (0 -> 1 -> 2) in one continuous
 * gesture, then drops on the far page. The re-arm loop, the real ViewPager2 settle
 * between advances, and the drag surviving both page changes only come together on
 * a device (the controller logic itself is JVM-covered in EdgeAdvanceControllerTest).
 *
 * Seeding: an app X on page 0 (dragged) and an app Y on page 1, so
 * renderedPageCount = maxPage(1) + 2 = 3 → pages 0,1,2 exist as drop targets.
 * Assertion: X ends up on page 2 (it crossed two pages, not one).
 */
@HiltAndroidTest
class HomeGridEdgeAutoScrollTaplTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var homeLayout: HomeLayoutRepository
    @Inject @ApplicationContext lateinit var context: Context

    private val movedId = ItemId("tapl-edgescroll-x")
    private lateinit var keyX: ComponentKey
    private lateinit var keyY: ComponentKey

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val resolved = ctx.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        )
        assumeTrue("Need ≥2 launchable apps", resolved.size >= 2)
        keyX = ComponentKey(resolved[0].activityInfo.packageName, resolved[0].activityInfo.name)
        keyY = ComponentKey(resolved[1].activityInfo.packageName, resolved[1].activityInfo.name)

        runBlocking {
            ConsentBootstrap.seedDecision(context, ConsentDecision.Denied)
            // X on page 0 (dragged), Y on page 1 → maxPage 1 → pages 0,1,2 rendered.
            homeLayout.save(
                HomeLayout(
                    grid = GridSpec(4, 5),
                    pages = 2,
                    items = listOf(
                        PlacedItem(HomeItem.App(movedId, keyX), CellPos(0, 0, 0)),
                        PlacedItem(HomeItem.App(ItemId("tapl-edgescroll-y"), keyY), CellPos(1, 0, 0)),
                    ),
                    dock = emptyList(),
                )
            )
        }
    }

    @Test
    fun holdingAtEdge_advancesAcrossTwoPages() {
        NyxLauncher.start().use { launcher ->
            launcher.home()
            awaitUntil(timeoutMs = 10_000, describe = { "seeded X never settled on page 0" }) {
                runBlocking { pageOf(movedId) == 0 }
            }

            // Dwell long enough (>= 2 * edge-advance timer + settles) to advance twice.
            launcher.home().dragCellAcrossPages(from = 0, to = 1, dwellMs = 3_000)

            awaitUntil(
                timeoutMs = 15_000,
                describe = { "X never reached page 2; page=${runBlocking { pageOf(movedId) }}" },
            ) {
                runBlocking { pageOf(movedId) == 2 }
            }
        }

        runBlocking { assertThat(pageOf(movedId)).isEqualTo(2) }
    }

    private suspend fun pageOf(id: ItemId): Int? =
        homeLayout.layout().first().items.firstOrNull { it.item.id == id }?.pos?.page
}

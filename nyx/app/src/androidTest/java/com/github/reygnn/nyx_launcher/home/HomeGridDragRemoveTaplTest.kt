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
 * Why instrumented: dragging a home item up to the remove bar (the top-strip
 * drop zone that reaches y=0) removes it from the layout. The upward drag into
 * the top strip and the remove DropZone hit-test only happen through a real
 * touch stream on a device.
 */
@HiltAndroidTest
class HomeGridDragRemoveTaplTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var homeLayout: HomeLayoutRepository
    @Inject @ApplicationContext lateinit var context: Context

    private val appId = ItemId("tapl-remove-app")

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val resolved = ctx.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        )
        assumeTrue("Need ≥1 launchable app", resolved.isNotEmpty())
        val key = ComponentKey(resolved[0].activityInfo.packageName, resolved[0].activityInfo.name)

        runBlocking {
            ConsentBootstrap.seedDecision(context, ConsentDecision.Denied)
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
    fun dragAppToRemoveBar_removesFromLayout() {
        NyxLauncher.start().use { launcher ->
            launcher.home()
            awaitUntil(timeoutMs = 10_000, describe = { "seeded app never settled at (0,0,0)" }) {
                runBlocking {
                    homeLayout.layout().first().items.any { it.item.id == appId }
                }
            }

            launcher.home().dragCellToRemoveBar(from = 0)

            awaitUntil(
                timeoutMs = 10_000,
                describe = {
                    val items = runBlocking { homeLayout.layout().first().items }
                    "app never removed; items=${items.map { it.item }}"
                },
            ) {
                runBlocking { homeLayout.layout().first().items.none { it.item.id == appId } }
            }
        }

        runBlocking {
            val items = homeLayout.layout().first().items
            assertThat(items.map { it.item.id }).doesNotContain(appId)
        }
    }
}

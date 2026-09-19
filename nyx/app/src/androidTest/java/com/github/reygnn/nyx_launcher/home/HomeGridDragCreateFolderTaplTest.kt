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
 * Why instrumented: dropping one home app onto another (over the cell centre)
 * makes a folder — DropTarget.Cell onto an occupied App cell → the folder
 * transition. The centre-vs-edge hit test and the whole DragLayer touch pipeline
 * are real-device behaviour; the transition logic itself is JVM-covered, this
 * covers the drop landing on an occupied cell through the real gesture.
 */
@HiltAndroidTest
class HomeGridDragCreateFolderTaplTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var homeLayout: HomeLayoutRepository
    @Inject @ApplicationContext lateinit var context: Context

    private lateinit var key0: ComponentKey
    private lateinit var key1: ComponentKey

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val resolved = ctx.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        )
        assumeTrue("Need ≥2 launchable apps", resolved.size >= 2)
        key0 = ComponentKey(resolved[0].activityInfo.packageName, resolved[0].activityInfo.name)
        key1 = ComponentKey(resolved[1].activityInfo.packageName, resolved[1].activityInfo.name)

        runBlocking {
            ConsentBootstrap.seedDecision(context, ConsentDecision.Denied)
            homeLayout.save(
                HomeLayout(
                    grid = GridSpec(4, 5),
                    pages = 1,
                    items = listOf(
                        PlacedItem(HomeItem.App(ItemId("a0"), key0), CellPos(0, 0, 0)),
                        PlacedItem(HomeItem.App(ItemId("a1"), key1), CellPos(0, 1, 0)),
                    ),
                    dock = emptyList(),
                )
            )
        }
    }

    @Test
    fun dragAppOntoApp_createsFolder() {
        NyxLauncher.start().use { launcher ->
            launcher.home()
            awaitUntil(timeoutMs = 10_000, describe = { "apps never settled at cells 0 and 1" }) {
                runBlocking {
                    val items = homeLayout.layout().first().items
                    items.any { it.pos == CellPos(0, 0, 0) } && items.any { it.pos == CellPos(0, 1, 0) }
                }
            }

            launcher.home().dragCellWithinPage(from = 0, to = 1)

            awaitUntil(
                timeoutMs = 10_000,
                describe = {
                    val items = runBlocking { homeLayout.layout().first().items }
                    "no folder with both apps yet; items=${items.map { it.item }}"
                },
            ) {
                runBlocking { folderWithBothKeys(homeLayout) }
            }
        }

        runBlocking {
            val items = homeLayout.layout().first().items
            val folders = items.map { it.item }.filterIsInstance<HomeItem.Folder>()
            assertThat(folders).hasSize(1)
            assertThat(folders.single().members).containsAtLeast(key0, key1)
            assertThat(items.map { it.item }.filterIsInstance<HomeItem.App>()).isEmpty()
        }
    }

    private suspend fun folderWithBothKeys(repo: HomeLayoutRepository): Boolean {
        val folders = repo.layout().first().items.map { it.item }.filterIsInstance<HomeItem.Folder>()
        return folders.any { it.members.containsAll(listOf(key0, key1)) }
    }
}

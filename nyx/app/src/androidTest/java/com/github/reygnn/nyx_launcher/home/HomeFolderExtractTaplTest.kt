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
 * Why instrumented: extracting a member from a home folder is a finger-drag out
 * of the folder overlay onto the grid — tap the folder (openFolder overlay),
 * long-press a member (startFolderMemberDrag -> immediate startDrag + overlay
 * dismiss, DragPayload.FolderMember), drag onto an empty cell -> drop ->
 * extractFromFolder(target). The overlay-to-grid touch handoff and the DragLayer
 * capture are device-only; the extract/dissolve transition is JVM-covered.
 *
 * Seed a 2-member home folder; extract one member. Since a folder auto-dissolves
 * below two members, the result is BOTH apps as top-level grid tiles and no
 * folder — the assertion pins the extracted member as a top-level app and the
 * folder gone.
 */
@HiltAndroidTest
class HomeFolderExtractTaplTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var homeLayout: HomeLayoutRepository
    @Inject @ApplicationContext lateinit var context: Context

    private lateinit var member0: ComponentKey
    private lateinit var member1: ComponentKey

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val resolved = ctx.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        )
        // Distinct components: two LAUNCHER ResolveInfos can map to the same
        // ComponentKey; a duplicate-member folder wouldn't extract (removeFromFolder
        // no-ops), so dedup before picking.
        val keys = resolved.map { ComponentKey(it.activityInfo.packageName, it.activityInfo.name) }.distinct()
        assumeTrue("Need ≥2 distinct launchable apps", keys.size >= 2)
        member0 = keys[0]
        member1 = keys[1]

        runBlocking {
            ConsentBootstrap.seedDecision(context, ConsentDecision.Denied)
            homeLayout.save(
                HomeLayout(
                    grid = GridSpec(4, 5),
                    pages = 1,
                    items = listOf(
                        PlacedItem(
                            HomeItem.Folder(ItemId("f0"), title = "", members = listOf(member0, member1)),
                            CellPos(0, 0, 0),
                        ),
                    ),
                    dock = emptyList(),
                )
            )
        }
    }

    @Test
    fun dragMemberOutOfFolder_extractsToGrid() {
        NyxLauncher.start().use { launcher ->
            val home = launcher.home()
            awaitUntil(timeoutMs = 10_000, describe = { "folder never rendered on the grid" }) {
                runBlocking { homeLayout.layout().first().items.any { it.item is HomeItem.Folder } }
            }

            home.openFolderAtCell(cellIndex = 0).extractMemberToCell(memberIndex = 0, targetCellIndex = 1)

            awaitUntil(
                timeoutMs = 10_000,
                describe = {
                    val items = runBlocking { homeLayout.layout().first().items }
                    "member never extracted to the grid; items=${items.map { it.item }}"
                },
            ) {
                runBlocking {
                    val items = homeLayout.layout().first().items
                    items.map { it.item }.filterIsInstance<HomeItem.App>().any { it.key == member0 }
                }
            }
        }

        runBlocking {
            val items = homeLayout.layout().first().items.map { it.item }
            // 2 -> 1 member dissolves the folder: both apps become top-level tiles.
            assertThat(items.filterIsInstance<HomeItem.Folder>()).isEmpty()
            val topLevelKeys = items.filterIsInstance<HomeItem.App>().map { it.key }
            assertThat(topLevelKeys).containsAtLeast(member0, member1)
        }
    }
}

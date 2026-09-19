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
 * Why instrumented: dragging a home app onto an existing folder (cell centre)
 * adds it as a member — DropTarget.Cell onto an occupied Folder cell. Same
 * device touch pipeline as folder creation; here the target cell holds a folder,
 * so the transition appends a member rather than creating a new folder.
 */
@HiltAndroidTest
class HomeGridDragAddToFolderTaplTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var homeLayout: HomeLayoutRepository
    @Inject @ApplicationContext lateinit var context: Context

    private val folderId = ItemId("f0")
    private lateinit var member0: ComponentKey
    private lateinit var member1: ComponentKey
    private lateinit var loose: ComponentKey

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val resolved = ctx.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        )
        // Distinct components — duplicate folder members / a loose app equal to a
        // member would make the add a no-op (two LAUNCHER entries can share a key).
        val keys = resolved.map { ComponentKey(it.activityInfo.packageName, it.activityInfo.name) }.distinct()
        assumeTrue("Need ≥3 distinct launchable apps", keys.size >= 3)
        member0 = keys[0]; member1 = keys[1]; loose = keys[2]

        runBlocking {
            ConsentBootstrap.seedDecision(context, ConsentDecision.Denied)
            homeLayout.save(
                HomeLayout(
                    grid = GridSpec(4, 5),
                    pages = 1,
                    items = listOf(
                        PlacedItem(
                            HomeItem.Folder(folderId, title = "", members = listOf(member0, member1)),
                            CellPos(0, 0, 0),
                        ),
                        PlacedItem(HomeItem.App(ItemId("a-loose"), loose), CellPos(0, 1, 0)),
                    ),
                    dock = emptyList(),
                )
            )
        }
    }

    @Test
    fun dragAppOntoFolder_addsMember() {
        NyxLauncher.start().use { launcher ->
            launcher.home()
            awaitUntil(timeoutMs = 10_000, describe = { "folder + loose app never settled" }) {
                runBlocking {
                    val items = homeLayout.layout().first().items
                    items.any { it.item is HomeItem.Folder } && items.any { it.item is HomeItem.App }
                }
            }

            // Drop the loose app (cell 1) onto the folder (cell 0).
            launcher.home().dragCellWithinPage(from = 1, to = 0)

            awaitUntil(
                timeoutMs = 10_000,
                describe = {
                    val items = runBlocking { homeLayout.layout().first().items }
                    "loose app never joined the folder; items=${items.map { it.item }}"
                },
            ) {
                runBlocking {
                    val folder = homeLayout.layout().first().items.map { it.item }
                        .filterIsInstance<HomeItem.Folder>().firstOrNull()
                    folder != null && folder.members.contains(loose)
                }
            }
        }

        runBlocking {
            val items = homeLayout.layout().first().items
            val folders = items.map { it.item }.filterIsInstance<HomeItem.Folder>()
            assertThat(folders).hasSize(1)
            assertThat(folders.single().members).containsAtLeast(member0, member1, loose)
            // The loose app is no longer a top-level tile.
            assertThat(items.map { it.item }.filterIsInstance<HomeItem.App>().map { it.key })
                .doesNotContain(loose)
        }
    }
}

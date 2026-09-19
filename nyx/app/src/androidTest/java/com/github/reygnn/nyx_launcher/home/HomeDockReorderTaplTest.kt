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
 * Why instrumented: reordering WITHIN the dock. Dragging dock item 0 onto the
 * outer (right) edge of dock item 1 hits the dock DropZone's DockSlot branch
 * (insert between icons, not the central DockItem folder band) through the real
 * touch pipeline — the finger-x → insert-index math and the reorder move only
 * come together on a device. The move itself is JVM-covered.
 *
 * Seeding: two dock apps [A, B], empty grid (dock non-empty → no first-run seed).
 * Assertion: after dragging A after B, the dock order flips to [B, A].
 */
@HiltAndroidTest
class HomeDockReorderTaplTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var homeLayout: HomeLayoutRepository
    @Inject @ApplicationContext lateinit var context: Context

    private lateinit var keyA: ComponentKey
    private lateinit var keyB: ComponentKey

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val resolved = ctx.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        )
        assumeTrue("Need ≥2 launchable apps for a dock reorder", resolved.size >= 2)
        keyA = ComponentKey(resolved[0].activityInfo.packageName, resolved[0].activityInfo.name)
        keyB = ComponentKey(resolved[1].activityInfo.packageName, resolved[1].activityInfo.name)

        runBlocking {
            ConsentBootstrap.seedDecision(context, ConsentDecision.Denied)
            homeLayout.save(
                HomeLayout(
                    grid = GridSpec(4, 5),
                    pages = 1,
                    items = emptyList(),
                    dock = listOf(
                        HomeItem.App(ItemId("tapl-dock-a"), keyA),
                        HomeItem.App(ItemId("tapl-dock-b"), keyB),
                    ),
                )
            )
        }
    }

    @Test
    fun dragDockItemAfterAnother_reordersTheDock() {
        NyxLauncher.start().use { launcher ->
            launcher.home()
            awaitUntil(timeoutMs = 10_000, describe = { "dock never settled as [A, B]" }) {
                runBlocking { dockKeys() == listOf(keyA, keyB) }
            }

            launcher.home().dragDockItemAfter(fromIndex = 0, ontoIndex = 1)

            awaitUntil(
                timeoutMs = 10_000,
                describe = { "dock never reordered to [B, A]; now=${runBlocking { dockKeys() }}" },
            ) {
                runBlocking { dockKeys() == listOf(keyB, keyA) }
            }
        }

        runBlocking { assertThat(dockKeys()).containsExactly(keyB, keyA).inOrder() }
    }

    private suspend fun dockKeys(): List<ComponentKey> =
        homeLayout.layout().first().dock.filterIsInstance<HomeItem.App>().map { it.key }
}

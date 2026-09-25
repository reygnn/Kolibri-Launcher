package com.github.reygnn.nyx_launcher.home

import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.crashreporting.consent.ConsentDecision
import com.github.reygnn.launcher.feature.crashreporting.consent.ConsentBootstrap
import com.github.reygnn.launcher.testing.awaitUntil
import com.github.reygnn.nyx_launcher.InstalledAppsHolderPump
import com.github.reygnn.nyx_launcher.home.usecase.GetDrawerAppsUseCase
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.model.displayName
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
 * Why instrumented: the drag route (Weg 2) of adding a drawer app to home — the
 * pure-gesture counterpart to the context-menu route in [DrawerAppToHomeTaplTest].
 * A drawer entry is long-pressed to ARM a DragPayload.NewApp, then a continuous
 * move PROMOTES it to a real drag that is carried onto the add-to-home bar and
 * dropped (onDrop -> addToHome -> HomeViewModel.place). The arm→promote transition
 * is the seam plain gesture timing couldn't hit reliably; the drag primitive
 * awaits the read-only DragLayer.isDragArmed hook to sequence it deterministically.
 *
 * Seeding mirrors the menu test: one UNRESOLVABLE placeholder marks the layout as
 * established (so no default seed) yet is never a drawer entry, so the dragged
 * real app is guaranteed not already on home. Assertion: a new real app appears.
 */
@HiltAndroidTest
class DrawerAppToHomeDragTaplTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var homeLayout: HomeLayoutRepository
    @Inject lateinit var getDrawerApps: GetDrawerAppsUseCase
    @Inject lateinit var installedAppsHolderPump: InstalledAppsHolderPump
    @Inject @ApplicationContext lateinit var context: Context

    private val placeholderKey = ComponentKey("com.example.tapl.absent", "com.example.tapl.absent.Nope")
    private var beforeKeys: Set<ComponentKey> = emptySet()
    private lateinit var searchQuery: String

    @Before fun setUp() {
        hiltRule.inject()
        // Option A: the drawer reads the shared in-RAM holder, which is fed by
        // InstalledAppsHolderPump. In production that pump is started from
        // NyxApplication.onCreate, but the instrumented harness runs HiltTestApplication,
        // so NyxApplication never runs. Start it here to warm the holder (mirrors the
        // production wiring); without it getDrawerApps() returns empty and the test
        // silently skips via assumeTrue below.
        installedAppsHolderPump.start()
        InstrumentationRegistry.getInstrumentation().targetContext.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)

        val apps = runBlocking { getDrawerApps() }
        assumeTrue("Need ≥1 installed app for the drawer", apps.isNotEmpty())
        searchQuery = apps.first().displayName.trim().substringBefore(' ')
        assumeTrue("First app displayName is blank", searchQuery.isNotBlank())

        runBlocking {
            ConsentBootstrap.seedDecision(context, ConsentDecision.Denied)
            homeLayout.save(
                HomeLayout(
                    grid = GridSpec(4, 5),
                    pages = 1,
                    items = listOf(
                        PlacedItem(HomeItem.App(ItemId("tapl-placeholder"), placeholderKey), CellPos(0, 0, 0)),
                    ),
                    dock = emptyList(),
                )
            )
        }
    }

    @Test
    fun dragDrawerAppToHomeBar_placesItOnGrid() {
        NyxLauncher.start().use { launcher ->
            val home = launcher.home()

            awaitUntil(timeoutMs = 10_000, describe = { "home layout never became readable" }) {
                runBlocking { homeLayout.layout().first(); true }
            }
            beforeKeys = runBlocking { topLevelAppKeys() }

            home.openDrawer().dragAppToHomeBar(query = searchQuery)

            awaitUntil(
                timeoutMs = 15_000,
                describe = {
                    val keys = runBlocking { topLevelAppKeys() }
                    "no new app landed on home; before=$beforeKeys now=$keys"
                },
            ) {
                runBlocking { (topLevelAppKeys() - beforeKeys).isNotEmpty() }
            }
        }

        runBlocking {
            val added = topLevelAppKeys() - beforeKeys
            assertThat(added).isNotEmpty()
            assertThat(added).doesNotContain(placeholderKey)
        }
    }

    private suspend fun topLevelAppKeys(): Set<ComponentKey> {
        val layout = homeLayout.layout().first()
        return (layout.items.map { it.item } + layout.dock)
            .filterIsInstance<HomeItem.App>().map { it.key }.toSet()
    }
}

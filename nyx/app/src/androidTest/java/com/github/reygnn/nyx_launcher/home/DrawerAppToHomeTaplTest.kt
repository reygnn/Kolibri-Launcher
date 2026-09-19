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
import com.github.reygnn.nyx_launcher.home.model.AppLoadResult
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.model.displayName
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.InstalledAppsRepository
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
 * Why instrumented: adding a drawer app to home is the cross-surface flow — open
 * the drawer (swipe-up gesture), long-press a drawer entry to arm a
 * DragPayload.NewApp (which raises the in-DragLayer context menu), and pick
 * "Add to home" -> addToHome -> HomeViewModel.place. The swipe-up recognition,
 * the drawer overlay, the long-press arm, and the overlay context menu are all
 * real-device behaviour. This uses the context-menu route (the deterministic of
 * the two production ways; the other drags the icon onto the add-to-home bar).
 *
 * Seeding: a single UNRESOLVABLE placeholder grid item marks the layout as
 * established (HomeLayoutRepositoryImpl seeds default apps only when items AND
 * dock are both empty) yet is never a drawer entry — so the dragged drawer app
 * (a real installed app) is guaranteed not already on home, sidestepping the
 * items∪dock uniqueness invariant. Assertion: a new real app appears on home.
 */
@HiltAndroidTest
class DrawerAppToHomeTaplTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var homeLayout: HomeLayoutRepository
    @Inject lateinit var installedApps: InstalledAppsRepository
    @Inject @ApplicationContext lateinit var context: Context

    private val placeholderKey = ComponentKey("com.example.tapl.absent", "com.example.tapl.absent.Nope")
    private var beforeKeys: Set<ComponentKey> = emptySet()
    private lateinit var searchQuery: String

    @Before fun setUp() {
        hiltRule.inject()
        InstrumentationRegistry.getInstrumentation().targetContext.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)

        // Derive the search query from the SAME source the drawer lists
        // (InstalledAppsRepository → LauncherApp.displayName), not PackageManager
        // loadLabel — so the query is guaranteed to match at least one drawer row
        // (the two label sources can otherwise diverge). First word keeps it clean
        // for token/prefix filters; replaceText in NyxDrawer handles any Unicode.
        val loaded = runBlocking { installedApps.loadInstalledApps() }
        assumeTrue("Installed apps didn't load", loaded is AppLoadResult.Loaded)
        val apps = (loaded as AppLoadResult.Loaded).apps
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
    fun addDrawerAppToHomeViaContextMenu_placesItOnGrid() {
        NyxLauncher.start().use { launcher ->
            val home = launcher.home()

            awaitUntil(timeoutMs = 10_000, describe = { "home layout never became readable" }) {
                runBlocking { homeLayout.layout().first(); true }
            }
            beforeKeys = runBlocking { topLevelAppKeys() }

            home.openDrawer().addAppToHomeViaMenu(query = searchQuery)

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

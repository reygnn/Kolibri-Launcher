package com.github.reygnn.kolibri_launcher.ui.favorites

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasMinimumChildCount
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.github.reygnn.kolibri_launcher.HiltTestActivity
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.tapl.FavoritesSort
import com.github.reygnn.launcher.core.crashreporting.consent.ConsentDecision
import com.github.reygnn.launcher.feature.crashreporting.consent.ConsentBootstrap
import com.github.reygnn.launcher.testing.awaitUntil
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Why instrumented: FavoritesSortFragment reorders favorites via
 * `ItemTouchHelper` long-press drag-and-drop (`onMove` -> adapter.moveItem ->
 * onMoveFinished -> viewModel.onMoved -> FavoritesOrderRepository.saveOrder).
 * The touch-slop / long-press promotion / nested `onMove` chain is real-device
 * behaviour Robolectric cannot dispatch — the screen had NO test at any level
 * before this (the Rule 10 gap several audits flagged). JVM already covers the
 * pure sort/persist logic in FavoritesSortViewModel; this covers the gesture.
 *
 * Hosting: FavoritesSortFragment is built with its production factory
 * `newInstance(favorites)` and hosted in HiltTestActivity (the src/debug
 * @AndroidEntryPoint shim), same pattern as BackupFragmentSafImportTest —
 * driving the Settings navigation around it would only add flakiness the
 * reorder gesture doesn't need.
 *
 * What it asserts: after dragging row 0 to the bottom, the persisted order has
 * the first component moved to last (a genuine end-to-end reorder), observed
 * through the injected FavoritesOrderRepository rather than the view state.
 */
@HiltAndroidTest
class FavoritesSortDragReorderTaplTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var favoritesOrderRepository: FavoritesOrderRepository

    private lateinit var seededApps: List<AppInfo>

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            ConsentBootstrap.seedDecision(ctx, ConsentDecision.Denied)
        }

        // Three real, DISTINCT launchable components — the reorder persists
        // componentName strings, so duplicate componentNames (two LAUNCHER entries
        // can map to the same package/activity) would make the seeded list
        // ambiguous. Dedup by componentName before taking three.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val distinctInfos = ctx.packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo }
            .distinctBy { "${it.packageName}/${it.name}" }
        assumeTrue("Need ≥3 distinct launchable apps to seed the reorder list", distinctInfos.size >= 3)
        seededApps = distinctInfos.take(3).mapIndexed { i, info ->
            AppInfo(
                originalName = "App$i",
                displayName = "App$i",
                packageName = info.packageName,
                className = info.name,
                isFavorite = true,
            )
        }
    }

    @Test
    fun dragFirstFavoriteToBottom_persistsReorderedComponents() {
        val comps = seededApps.map { it.componentName }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val launchIntent = Intent(ctx, HiltTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<HiltTestActivity>(launchIntent).use {
            // Attach the fragment via its production newInstance factory.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<HiltTestActivity>()
                    .single()
                activity.supportFragmentManager.beginTransaction()
                    .replace(
                        android.R.id.content,
                        FavoritesSortFragment.newInstance(seededApps),
                        "favorites-sort",
                    )
                    .commitNow()
            }

            // Wait for all three rows to lay out before dragging.
            awaitUntil(
                timeoutMs = 10_000,
                describe = { "favorites-sort list never showed 3 rows" },
            ) {
                runCatching {
                    onView(withId(R.id.recyclerView)).check(matches(hasMinimumChildCount(3)))
                }.isSuccess
            }

            // ACT: long-press-drag row 0 onto row 2.
            FavoritesSort().dragItem(from = 0, to = 2)

            // ASSERT: a genuine reorder persisted — the dragged row (originally
            // first) moved to a later position. Asserting the exact landing slot
            // would test drag *distance* rather than the gesture path; what this
            // pins is that the ItemTouchHelper onMove -> persist chain fired.
            awaitUntil(
                timeoutMs = 10_000,
                describe = {
                    val saved = runBlocking { favoritesOrderRepository.getFavoriteComponentsOrderSnapshot() }
                    "persisted order never reflected a downward drag; saved=$saved of original $comps"
                },
            ) {
                val saved = runBlocking { favoritesOrderRepository.getFavoriteComponentsOrderSnapshot() }
                saved.size == 3 && saved.toSet() == comps.toSet() &&
                    saved.indexOf(comps.first()) > 0 && saved != comps
            }
        }

        runBlocking {
            val finalOrder = favoritesOrderRepository.getFavoriteComponentsOrderSnapshot()
            assertThat(finalOrder).containsExactlyElementsIn(comps)
            assertThat(finalOrder.indexOf(comps.first())).isGreaterThan(0)
            assertThat(finalOrder).isNotEqualTo(comps)
        }
    }
}

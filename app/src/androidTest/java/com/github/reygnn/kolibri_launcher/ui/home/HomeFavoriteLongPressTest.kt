package com.github.reygnn.kolibri_launcher.ui.home

import android.content.Intent
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.ViewMatchers.hasMinimumChildCount
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentBootstrap
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentDecision
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.support.awaitUntil
import com.github.reygnn.kolibri_launcher.ui.main.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Pins that long-press on a favorite opens the favorite's
 * app-context-menu (the BottomSheet) and NOT the wrapper's
 * customization-options dialog.
 *
 * Why this test exists: round-2 of the HomeGesture-Wrapper
 * migration's real-device validation surfaced a double-fire bug
 * where the wrapper's tap-detector fired its long-press timer in
 * parallel with the favorite Button's own `setOnLongClickListener`,
 * so a single long-press opened BOTH dialogs at once. Round-4
 * fixed it structurally with the
 * `HomeGestureLayout.hasOwnTouchPipelineDescendantAt` hit-test that
 * suppresses the wrapper's tap-detector when the touch lands on a
 * descendant with its own touch pipeline (long-clickable or
 * clickable). This test pins that the hit-test still gives the
 * favorite priority — a future change to the wrapper's dispatch
 * logic, or to the way favorite buttons signal `isLongClickable`,
 * would surface here.
 */
@HiltAndroidTest
class HomeFavoriteLongPressTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var favoritesRepository: FavoritesRepository

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            settings.setOnboardingCompleted()
            ConsentBootstrap.seedDecision(ctx, ConsentDecision.Denied)

            // One real launchable component as a favorite is enough
            // for this test (we long-press item 0). Real components
            // per `INSTRUMENTED_TESTING_NOTES` "ASSUMPTION TRAPS" #1.
            val launcherIntent =
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = ctx.packageManager.queryIntentActivities(launcherIntent, 0)
            assumeTrue(
                "Need ≥1 launchable app to seed as a favorite",
                resolved.isNotEmpty(),
            )
            val first = resolved.first().activityInfo
            val componentName = "${first.packageName}/${first.name}"
            favoritesRepository.saveFavoriteComponents(listOf(componentName))
        }
    }

    @Test
    fun longPressOnFavorite_opensAppContextMenu_notCustomizeDialog() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        ctx.packageManager.queryIntentActivities(launcherIntent, 0)

        val launchIntent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launchIntent).use {
            // Wait for the seeded favorite to land in the RecyclerView.
            awaitUntil(
                timeoutMs = 10_000,
                describe = { "favoritesRecyclerView never showed ≥1 child" },
            ) {
                try {
                    onView(withId(R.id.favoritesRecyclerView))
                        .check(matches(hasMinimumChildCount(1)))
                    true
                } catch (_: Throwable) {
                    false
                }
            }

            // ACT: long-click on item position 0. The action's default
            // press point is the view's center, which on a
            // `WRAP_CONTENT` favorite Button lands on the text — and
            // the text view IS the favorite (button is the item's root
            // view in `HomeFavoritesAdapter.onCreateViewHolder`).
            onView(withId(R.id.favoritesRecyclerView)).perform(
                actionOnItemAtPosition<RecyclerView.ViewHolder>(0, longClick())
            )

            // ASSERT 1: app-context-menu BottomSheet appears.
            // `R.id.appNameText` is the title TextView in the
            // BottomSheet (`bottom_sheet_app_context_menu.xml`).
            awaitUntil(
                timeoutMs = 5_000,
                describe = { "appNameText (app-context-menu) never appeared after long-press" },
            ) {
                try {
                    onView(withId(R.id.appNameText)).check(matches(isDisplayed()))
                    true
                } catch (_: Throwable) {
                    false
                }
            }

            // ASSERT 2: the wrapper's customize-options dialog did NOT
            // also fire. The dialog's title text is "Customize"
            // (R.string.customize_title); `doesNotExist()` covers all
            // currently-attached roots.
            onView(withText(R.string.customize_title)).check(doesNotExist())
        }
    }
}

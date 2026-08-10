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
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
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
 * Deterministic sibling of [HomeFavoriteLongPressTest] that verifies the
 * AUDIT-14 F3 part 3 refactor (listeners hoisted onto the WRAP_CONTENT button)
 * still gives a favorite long-press priority over the wrapper's customize
 * dialog — i.e. the `HomeGestureLayout.hasOwnTouchPipelineDescendantAt`
 * hit-test still reads `isLongClickable` on the button.
 *
 * The difference from its sibling is [FavoritesAlignment.CENTER]: the favorite
 * button is WRAP_CONTENT, and Espresso's `longClick()` targets the item's
 * CENTER. With the default START alignment and a short app label, that center
 * lands in the empty FrameLayout space, so the press never hits the button —
 * environment fragility that makes the sibling flake on some devices/emulators
 * regardless of the production change. Centering the button makes the press
 * land on it deterministically, so a failure here is a real regression.
 */
@HiltAndroidTest
class HomeFavoriteLongPressCenteredTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var favoritesRepository: FavoritesRepository

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            settings.setOnboardingCompleted()
            // Center the favorite so the item-center long-press lands ON the
            // WRAP_CONTENT button (see class KDoc).
            settings.setFavoritesAlignment(FavoritesAlignment.CENTER)
            ConsentBootstrap.seedDecision(ctx, ConsentDecision.Denied)

            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = ctx.packageManager.queryIntentActivities(launcherIntent, 0)
            assumeTrue(
                "Need ≥1 launchable app to seed as a favorite",
                resolved.isNotEmpty(),
            )
            val first = resolved.first().activityInfo
            favoritesRepository.saveFavoriteComponents(listOf("${first.packageName}/${first.name}"))
        }
    }

    @Test
    fun longPressCenteredFavorite_opensAppContextMenu_notCustomizeDialog() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val launchIntent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        ActivityScenario.launch<MainActivity>(launchIntent).use {
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

            onView(withId(R.id.favoritesRecyclerView)).perform(
                actionOnItemAtPosition<RecyclerView.ViewHolder>(0, longClick()),
            )

            // The favorite's own long-press (hoisted onto the button in F3 part 3)
            // must open the app-context-menu BottomSheet.
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

            // And the wrapper's customize dialog must NOT also fire.
            onView(withText(R.string.customize_title)).check(doesNotExist())
        }
    }
}

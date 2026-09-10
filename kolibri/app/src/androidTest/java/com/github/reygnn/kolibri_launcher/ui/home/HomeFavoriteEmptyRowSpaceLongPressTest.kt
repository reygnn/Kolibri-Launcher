package com.github.reygnn.kolibri_launcher.ui.home

import android.content.Intent
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasMinimumChildCount
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentBootstrap
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentDecision
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
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
 * The reverse half of the AUDIT-14 F3 hit-test contract (see
 * [HomeFavoriteLongPressCenteredTest] for the button-hit half): a long-press on
 * the EMPTY space of a favorites row — the FrameLayout area beside the
 * WRAP_CONTENT button — must reach the wrapper and open its customize dialog,
 * NOT the favorite's app-context-menu. This is the guarantee the KDoc on
 * [HomeFavoritesAdapter.onCreateViewHolder] makes: the button carries the touch
 * pipeline (isLongClickable), the surrounding FrameLayout does not, so
 * `HomeGestureLayout.hasOwnTouchPipelineDescendantAt` leaves the wrapper's
 * long-press alive there. Part 3 moved WHERE the button's listener is wired; it
 * must not have made the container long-clickable.
 *
 * Determinism: a one-glyph custom name makes the button tiny (left, START
 * alignment) so the row's right 90% is guaranteed empty; the row is the only
 * favorite, so the RecyclerView is one row tall and a press at (90% width,
 * mid-height) lands squarely in that empty FrameLayout space.
 */
@HiltAndroidTest
class HomeFavoriteEmptyRowSpaceLongPressTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var favoritesRepository: FavoritesRepository
    @Inject lateinit var customNames: CustomNamesRepository

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            settings.setOnboardingCompleted()
            // START (default): the narrow button sits at the left, leaving the
            // rest of the row as empty FrameLayout space to press.
            settings.setFavoritesAlignment(FavoritesAlignment.START)
            ConsentBootstrap.seedDecision(ctx, ConsentDecision.Denied)

            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = ctx.packageManager.queryIntentActivities(launcherIntent, 0)
            assumeTrue(
                "Need ≥1 launchable app to seed as a favorite",
                resolved.isNotEmpty(),
            )
            val first = resolved.first().activityInfo
            favoritesRepository.saveFavoriteComponents(listOf("${first.packageName}/${first.name}"))
            // One-glyph name -> tiny button -> the right of the row is empty.
            customNames.setCustomNameForPackage(first.packageName, "•")
        }
    }

    @Test
    fun longPressOnEmptyRowSpace_opensCustomizeDialog_notAppContextMenu() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val launchIntent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        ActivityScenario.launch<MainActivity>(launchIntent).use {
            awaitUntil(
                timeoutMs = 10_000,
                describe = { "favoritesRecyclerView never showed the seeded favorite" },
            ) {
                try {
                    onView(withId(R.id.favoritesRecyclerView))
                        .check(matches(hasMinimumChildCount(1)))
                    true
                } catch (_: Throwable) {
                    false
                }
            }

            // Long-press the empty right portion of the single favorite row.
            onView(withId(R.id.favoritesRecyclerView)).perform(
                GeneralClickAction(
                    Tap.LONG,
                    emptyRightOfSingleRow,
                    Press.FINGER,
                    InputDevice.SOURCE_UNKNOWN,
                    MotionEvent.BUTTON_PRIMARY,
                ),
            )

            // The wrapper's customize dialog must fire.
            awaitUntil(
                timeoutMs = 5_000,
                describe = { "customize dialog never appeared after empty-space long-press" },
            ) {
                try {
                    onView(withText(R.string.customize_title)).check(matches(isDisplayed()))
                    true
                } catch (_: Throwable) {
                    false
                }
            }

            // And the favorite's app-context-menu must NOT.
            onView(withId(R.id.appNameText)).check(doesNotExist())
        }
    }

    private companion object {
        // With exactly one favorite the RecyclerView is one row tall, so
        // (90% width, mid-height) is the empty FrameLayout space to the right of
        // the left-aligned one-glyph button.
        val emptyRightOfSingleRow = CoordinatesProvider { view ->
            val onScreen = IntArray(2)
            view.getLocationOnScreen(onScreen)
            floatArrayOf(
                onScreen[0] + view.width * 0.9f,
                onScreen[1] + view.height / 2f,
            )
        }
    }
}

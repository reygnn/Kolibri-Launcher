package com.github.reygnn.kolibri_launcher.ui.layoutcustomization

import android.content.Intent
import android.widget.Button
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasMinimumChildCount
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.tapl.Launcher
import com.github.reygnn.kolibri_launcher.ui.main.MainActivity
import com.github.reygnn.launcher.core.crashreporting.consent.ConsentDecision
import com.github.reygnn.launcher.feature.crashreporting.consent.ConsentBootstrap
import com.github.reygnn.launcher.testing.awaitUntil
import com.github.reygnn.launcher.testing.currentResumed
import com.github.reygnn.launcher.testing.onMainSync
import com.github.reygnn.launcher.testing.probeFloat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Why instrumented: the layout dialog's text-size Slider forwards to
 * `viewModel.onSetLayoutScale` ONLY when `fromUser` is true, so the live
 * preview fires exclusively on a real touch drag — a programmatic value-set is
 * silently ignored. That fromUser gate, plus the fact that the dialog shares
 * `LauncherViewModel` with the host and previews live on the Home favorites
 * behind it, is behaviour Robolectric can't reproduce (no real touch stream, no
 * second window). JVM already covers the slider-value math in
 * CustomizationDialogModelTest; this covers the touch -> VM -> Home-render seam.
 *
 * What it asserts:
 *  1. Dragging slider_text_size raises LauncherViewModel.layoutScaleState
 *     (the fromUser propagation actually fired).
 *  2. After dismissing the dialog, the seeded favorite's rendered text is
 *     larger than before (the live preview re-measured Home).
 */
@HiltAndroidTest
class LayoutCustomizationLivePreviewTaplTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var favoritesRepository: FavoritesRepository

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            settings.setOnboardingCompleted()
            ConsentBootstrap.seedDecision(ctx, ConsentDecision.Denied)

            // Exactly one favorite so R.id.app_name is unambiguous for probing.
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = ctx.packageManager.queryIntentActivities(launcherIntent, 0)
            assumeTrue("Need ≥1 launchable app to seed a favorite", resolved.isNotEmpty())
            val first = resolved.first().activityInfo
            favoritesRepository.saveFavoriteComponents(listOf("${first.packageName}/${first.name}"))
        }
    }

    @Test
    fun draggingTextSizeSlider_growsFavoriteTextOnHome() {
        Launcher.start().use { launcher ->
            launcher.home()

            // Wait for the seeded favorite to render.
            awaitUntil(timeoutMs = 10_000, describe = { "favorite never rendered on Home" }) {
                runCatching {
                    onView(withId(R.id.favoritesRecyclerView)).check(matches(hasMinimumChildCount(1)))
                }.isSuccess
            }

            val sizeBefore = probeFloat(favoriteButton()) { (it as TextView).textSize }
            val scaleBefore = currentLayoutScale()

            // ACT: open the layout dialog -> drag the text-size slider.
            launcher.openLayoutCustomization()
                .dragTextSizeUp()

            // ASSERT 1: the fromUser slider path raised the layout scale.
            awaitUntil(
                timeoutMs = 5_000,
                describe = { "layoutScaleState never rose above $scaleBefore after slider drag" },
            ) {
                currentLayoutScale() > scaleBefore
            }

            // Dismiss the dialog so Home is the top window again.
            pressBack()
            awaitUntil(timeoutMs = 5_000, describe = { "layout dialog never dismissed" }) {
                runCatching {
                    onView(withId(R.id.favoritesRecyclerView)).check(matches(isDisplayed()))
                    // slider gone => dialog dismissed
                    runCatching { onView(withId(R.id.slider_text_size)).check(matches(isDisplayed())) }
                        .isFailure
                }.getOrDefault(false)
            }

            // ASSERT 2: the live preview re-measured the favorite bigger.
            awaitUntil(
                timeoutMs = 5_000,
                describe = { "favorite text size never grew from $sizeBefore after preview" },
            ) {
                probeFloat(favoriteButton()) { (it as TextView).textSize } > sizeBefore
            }
        }
    }

    /**
     * The single seeded Home favorite. HomeFavoritesAdapter builds each favorite
     * as a programmatic OutlinedButton (no id), so it is matched by type within
     * the favorites list rather than by id.
     */
    private fun favoriteButton() = allOf(
        isAssignableFrom(Button::class.java),
        isDescendantOfA(withId(R.id.favoritesRecyclerView)),
        isDisplayed(),
    )

    private fun currentLayoutScale(): Float {
        val holder = FloatArray(1)
        onMainSync {
            holder[0] = (currentResumed<MainActivity>()
                ?: error("MainActivity not RESUMED — cannot read layout scale"))
                .viewModel.layoutScaleState.value
        }
        return holder[0]
    }
}

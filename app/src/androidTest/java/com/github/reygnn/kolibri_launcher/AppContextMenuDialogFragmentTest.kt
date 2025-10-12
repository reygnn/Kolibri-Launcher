package com.github.reygnn.kolibri_launcher

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.hamcrest.CoreMatchers.allOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class AppContextMenuDialogFragmentTest : BaseAndroidTest() {

    private val testApp = AppInfo("Test App", "Test App", "com.test.app", "com.test.app.MainActivity")

    @Before
    fun setup() {
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    private fun launchDialog(
        app: AppInfo,
        context: MenuContext,
        hasUsageData: Boolean
    ): ActivityScenario<HiltTestActivity> {
        val scenario = ActivityScenario.launch(HiltTestActivity::class.java)
        val dialog = AppContextMenuDialogFragment.newInstance(app, context, hasUsageData)
        scenario.onActivity { activity -> dialog.show(activity.supportFragmentManager, "TestDialog") }
        return scenario
    }

    @Test
    fun dialogIsDisplayed_andAppNameIsCorrect() = testCoroutineRule.runTestAndLaunchUI {
        launchDialog(testApp, MenuContext.APP_DRAWER, false)

        onView(withId(R.id.appNameText))
            .inRoot(isDialog())
            .check(matches(allOf(isDisplayed(), withText("Test App"))))
    }

    @Test
    fun showsDefaultActions_whenNotFavoriteOrHidden() = testCoroutineRule.runTestAndLaunchUI {
        launchDialog(testApp, MenuContext.APP_DRAWER, false)

        onView(withText(R.string.add_to_favorites)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText(R.string.hide_app_from_drawer)).inRoot(isDialog()).check(matches(isDisplayed()))
    }

    @Test
    fun showsRemoveFromFavorites_whenAppIsFavorite() = testCoroutineRule.runTestAndLaunchUI {
        // Zustand vorbereiten: App zu Favoriten hinzufügen
        (favoritesRepository as FakeFavoritesRepository).addFavoriteComponent(testApp.componentName)

        launchDialog(testApp, MenuContext.APP_DRAWER, false)

        onView(withText(R.string.remove_from_favorites)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText(R.string.add_to_favorites)).check(doesNotExist())
    }

    @Test
    fun showsUnhideAction_whenAppIsHidden() = testCoroutineRule.runTestAndLaunchUI {
        // Zustand vorbereiten: App verstecken
        (appVisibilityRepository as FakeAppVisibilityRepository).hideComponent(testApp.componentName)

        launchDialog(testApp, MenuContext.APP_DRAWER, false)

        onView(withText(R.string.unhide_app_in_drawer)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText(R.string.hide_app_from_drawer)).check(doesNotExist())
    }

    @Test
    fun showsResetSortingAction_whenInDrawerAndHasUsage() = testCoroutineRule.runTestAndLaunchUI {
        // Die Bedingung (hasUsageData = true) wird direkt übergeben
        launchDialog(testApp, MenuContext.APP_DRAWER, true)

        onView(withText(R.string.action_reset_sorting)).inRoot(isDialog()).check(matches(isDisplayed()))
    }

    @Test
    fun doesNotShowResetSortingAction_whenOnHomeScreen() = testCoroutineRule.runTestAndLaunchUI {
        // Kontext ist HOME_SCREEN
        launchDialog(testApp, MenuContext.HOME_SCREEN, true)

        onView(withText(R.string.action_reset_sorting)).check(doesNotExist())
    }

    @Test
    fun doesNotShowResetSortingAction_whenNoUsageData() = testCoroutineRule.runTestAndLaunchUI {
        // hasUsageData ist false
        launchDialog(testApp, MenuContext.APP_DRAWER, false)

        onView(withText(R.string.action_reset_sorting)).check(doesNotExist())
    }

    @Test
    fun showsRestoreNameAction_whenAppHasCustomName() = testCoroutineRule.runTestAndLaunchUI {
        // Zustand vorbereiten: Einen Custom Name im Fake setzen
        (appNamesRepository as FakeAppNamesRepository).setCustomNameForPackage(testApp.packageName, "My Cool App")

        launchDialog(testApp, MenuContext.APP_DRAWER, false)

        onView(withText(R.string.restore_original_name)).inRoot(isDialog()).check(matches(isDisplayed()))
    }

    @Test
    fun doesNotShowRestoreNameAction_whenAppHasNoCustomName() = testCoroutineRule.runTestAndLaunchUI {
        // Der Zustand ist standardmäßig leer, keine Vorbereitung nötig.
        launchDialog(testApp, MenuContext.APP_DRAWER, false)

        onView(withText(R.string.restore_original_name)).check(doesNotExist())
    }
}
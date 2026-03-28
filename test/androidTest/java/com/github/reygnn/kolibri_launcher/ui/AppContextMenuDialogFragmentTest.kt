package com.github.reygnn.kolibri_launcher.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.BaseAndroidTest
import com.github.reygnn.kolibri_launcher.HiltTestActivity
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.MenuContext
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.AppContextMenuDialogFragment
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import org.hamcrest.CoreMatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class AppContextMenuDialogFragmentTest : BaseAndroidTest() {

    //@get:Rule
    // override val testCoroutineRule = TestCoroutineRule(TestCoroutineRule.Mode.FAST)

    private val testApp =
        AppInfo("Test App", "Test App", "com.test.app", "com.test.app.MainActivity")

    @Before
    fun setup() {
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    private suspend fun launchDialog(
        app: AppInfo,
        context: MenuContext,
        hasUsageData: Boolean
    ): ActivityScenario<HiltTestActivity> {
        val scenario = ActivityScenario.launch(HiltTestActivity::class.java)
        val dialog = AppContextMenuDialogFragment.Companion.newInstance(app, context, hasUsageData)
        scenario.onActivity { activity ->
            dialog.show(activity.supportFragmentManager, "TestDialog")
        }

        try {
            Espresso.onView(ViewMatchers.withId(R.id.appNameText))
                .inRoot(RootMatchers.isDialog())
                .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        } catch (e: Exception) {
            delay(500)
        }

        return scenario
    }

    @Test
    fun dialogIsDisplayed_andAppNameIsCorrect() = testCoroutineRule.runTestAndLaunchUI {
        launchDialog(testApp, MenuContext.APP_DRAWER, false)

        Espresso.onView(ViewMatchers.withId(R.id.appNameText))
            .inRoot(RootMatchers.isDialog())
            .check(
                ViewAssertions.matches(
                    CoreMatchers.allOf(
                        ViewMatchers.isDisplayed(),
                        ViewMatchers.withText("Test App")
                    )
                )
            )
    }

    @Test
    fun showsDefaultActions_whenNotFavoriteOrHidden() = testCoroutineRule.runTestAndLaunchUI {
        launchDialog(testApp, MenuContext.APP_DRAWER, false)

        Espresso.onView(ViewMatchers.withText(R.string.add_to_favorites))
            .inRoot(RootMatchers.isDialog()).check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText(R.string.hide_app_from_drawer))
            .inRoot(RootMatchers.isDialog()).check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun showsRemoveFromFavorites_whenAppIsFavorite() = testCoroutineRule.runTestAndLaunchUI {
        (favoritesRepository as FakeFavoritesRepository).addFavoriteComponent(testApp.componentName)
        launchDialog(testApp, MenuContext.APP_DRAWER, false)

        Espresso.onView(ViewMatchers.withText(R.string.remove_from_favorites))
            .inRoot(RootMatchers.isDialog()).check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText(R.string.add_to_favorites))
            .check(ViewAssertions.doesNotExist())
    }

    @Test
    fun showsUnhideAction_whenAppIsHidden() = testCoroutineRule.runTestAndLaunchUI {
        (appVisibilityRepository as FakeHiddenAppsRepository).hideComponent(testApp.componentName)
        launchDialog(testApp, MenuContext.APP_DRAWER, false)

        Espresso.onView(ViewMatchers.withText(R.string.unhide_app_in_drawer))
            .inRoot(RootMatchers.isDialog()).check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText(R.string.hide_app_from_drawer))
            .check(ViewAssertions.doesNotExist())
    }

    @Test
    fun showsResetSortingAction_whenInDrawerAndHasUsage() = testCoroutineRule.runTestAndLaunchUI {
        launchDialog(testApp, MenuContext.APP_DRAWER, true)
        Espresso.onView(ViewMatchers.withText(R.string.action_reset_sorting))
            .inRoot(RootMatchers.isDialog()).check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun AppContextMenuDialogFragmentTest() = testCoroutineRule.runTestAndLaunchUI {
        launchDialog(testApp, MenuContext.HOME_SCREEN, true)
        Espresso.onView(ViewMatchers.withText(R.string.action_reset_sorting))
            .check(ViewAssertions.doesNotExist())
    }

    @Test
    fun doesNotShowResetSortingAction_whenNoUsageData() = testCoroutineRule.runTestAndLaunchUI {
        launchDialog(testApp, MenuContext.APP_DRAWER, false)
        Espresso.onView(ViewMatchers.withText(R.string.action_reset_sorting))
            .check(ViewAssertions.doesNotExist())
    }

    @Test
    fun showsRestoreNameAction_whenAppHasCustomName() = testCoroutineRule.runTestAndLaunchUI {
        (customNamesRepository as FakeCustomNamesRepository).setCustomNameForPackage(testApp.packageName, "My Cool App")
        launchDialog(testApp, MenuContext.APP_DRAWER, false)

        Espresso.onView(ViewMatchers.withText(R.string.restore_original_name))
            .inRoot(RootMatchers.isDialog()).check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun doesNotShowRestoreNameAction_whenAppHasNoCustomName() = testCoroutineRule.runTestAndLaunchUI {
        launchDialog(testApp, MenuContext.APP_DRAWER, false)
        Espresso.onView(ViewMatchers.withText(R.string.restore_original_name))
            .check(ViewAssertions.doesNotExist())
    }
}
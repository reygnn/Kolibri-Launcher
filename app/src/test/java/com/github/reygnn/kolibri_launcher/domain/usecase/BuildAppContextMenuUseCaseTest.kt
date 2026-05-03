package com.github.reygnn.kolibri_launcher.domain.usecase

import android.content.pm.ShortcutInfo
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.MenuContext
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ShortcutRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.AppContextMenuAction
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * JVM tests for [BuildAppContextMenuUseCase]. Uses the project Fakes for the
 * three Kolibri-owned repositories (favorites, custom names, hidden) and a
 * MockK mock only for [ShortcutRepository] — it is system-API driven
 * (LauncherApps) and has no fake (see `ShortcutRepositoryContract`).
 *
 * Failure-injection tests build a one-off `mockk<Interface>(relaxed = true)`
 * for the broken side, because the project fakes have no
 * "fail next call" hook on the read methods this use case touches
 * (`isFavoriteComponent`, `hasCustomNameForPackage`, `isComponentHidden`).
 *
 * `ShortcutInfo` is mocked too because it's a final Android system class
 * with no public constructor; only its `id` is consulted indirectly via
 * the adapter's DiffUtil callback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuildAppContextMenuUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var shortcutRepository: ShortcutRepository
    private lateinit var fakeFavorites: FakeFavoritesRepository
    private lateinit var fakeCustomNames: FakeCustomNamesRepository
    private lateinit var fakeHidden: FakeHiddenAppsRepository
    private lateinit var useCase: BuildAppContextMenuUseCase

    private val app = AppInfo(
        originalName = "Camera",
        displayName = "Camera",
        packageName = "com.example.camera",
        className = "com.example.camera.MainActivity",
    )

    @Before
    fun setup() {
        shortcutRepository = mockk()
        every { shortcutRepository.getShortcutsForPackage(any()) } returns emptyList()

        fakeFavorites = FakeFavoritesRepository()
        fakeCustomNames = FakeCustomNamesRepository()
        fakeHidden = FakeHiddenAppsRepository()

        useCase = newUseCase()
    }

    private fun newUseCase(
        favoritesRepo: FavoritesRepository = fakeFavorites,
        customNamesRepo: CustomNamesRepository = fakeCustomNames,
        hiddenRepo: HiddenAppsRepository = fakeHidden,
    ) = BuildAppContextMenuUseCase(
        shortcutRepository = shortcutRepository,
        favoritesRepository = favoritesRepo,
        customNamesRepository = customNamesRepo,
        hiddenAppsRepository = hiddenRepo,
    )

    private fun launcherAction(action: AppContextMenuAction): AppContextMenuAction.LauncherAction =
        action as AppContextMenuAction.LauncherAction

    // ------------------------------------------------------------------
    // Baseline shape
    // ------------------------------------------------------------------

    @Test
    fun `default state on home screen produces favorite, rename, hide, app-info in order`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val result = useCase(
                appInfo = app,
                menuContext = MenuContext.HOME_SCREEN,
                hasUsageData = false,
            )

            // No shortcuts → no separator.
            assertEquals(4, result.size)
            assertEquals(AppContextMenuAction.ACTION_ID_TOGGLE_FAVORITE, launcherAction(result[0]).id)
            assertEquals(AppContextMenuAction.ACTION_ID_RENAME_APP, launcherAction(result[1]).id)
            assertEquals(AppContextMenuAction.ACTION_ID_HIDE_APP, launcherAction(result[2]).id)
            assertEquals(AppContextMenuAction.ACTION_ID_APP_INFO, launcherAction(result[3]).id)
        }

    // ------------------------------------------------------------------
    // Shortcuts + separator
    // ------------------------------------------------------------------

    @Test
    fun `shortcuts are emitted before a separator and the rest of the menu`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val s1: ShortcutInfo = mockk()
            val s2: ShortcutInfo = mockk()
            every { shortcutRepository.getShortcutsForPackage(app.packageName) } returns listOf(s1, s2)

            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false)

            assertEquals(AppContextMenuAction.Shortcut(s1), result[0])
            assertEquals(AppContextMenuAction.Shortcut(s2), result[1])
            assertEquals(AppContextMenuAction.Separator, result[2])
            // Then favorite, rename, hide, app-info — total 7.
            assertEquals(7, result.size)
        }

    @Test
    fun `no separator emitted when there are no shortcuts`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false)

            assertTrue(result.none { it is AppContextMenuAction.Separator })
        }

    // ------------------------------------------------------------------
    // Favorite branch
    // ------------------------------------------------------------------

    @Test
    fun `favorite action label flips from add to remove when isFavorite is true`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeFavorites.favorites = setOf(app.componentName)

            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false)
            val toggleFavorite = result.first {
                it is AppContextMenuAction.LauncherAction &&
                    it.id == AppContextMenuAction.ACTION_ID_TOGGLE_FAVORITE
            } as AppContextMenuAction.LauncherAction
            assertEquals(R.string.remove_from_favorites, toggleFavorite.labelRes)
        }

    @Test
    fun `favorite action label is add_to_favorites when not currently a favorite`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false)
            val toggleFavorite = result.first {
                it is AppContextMenuAction.LauncherAction &&
                    it.id == AppContextMenuAction.ACTION_ID_TOGGLE_FAVORITE
            } as AppContextMenuAction.LauncherAction
            assertEquals(R.string.add_to_favorites, toggleFavorite.labelRes)
        }

    // ------------------------------------------------------------------
    // Custom-name branch
    // ------------------------------------------------------------------

    @Test
    fun `restore-original-name action is present when a custom name is set`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeCustomNames.setCustomNameForPackage(app.packageName, "MyCam")

            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false)
            assertTrue(
                result.any {
                    it is AppContextMenuAction.LauncherAction &&
                        it.id == AppContextMenuAction.ACTION_ID_RESTORE_NAME &&
                        it.labelRes == R.string.restore_original_name
                },
            )
        }

    @Test
    fun `restore-original-name action is absent when no custom name is set`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false)
            assertTrue(
                result.none {
                    it is AppContextMenuAction.LauncherAction &&
                        it.id == AppContextMenuAction.ACTION_ID_RESTORE_NAME
                },
            )
        }

    // ------------------------------------------------------------------
    // Hidden branch
    // ------------------------------------------------------------------

    @Test
    fun `hide action becomes unhide with switched id and label when isHidden is true`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeHidden.hiddenApps = setOf(app.componentName)

            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false)
            val hideAction = result.first {
                it is AppContextMenuAction.LauncherAction &&
                    (it.id == AppContextMenuAction.ACTION_ID_HIDE_APP ||
                        it.id == AppContextMenuAction.ACTION_ID_UNHIDE_APP)
            } as AppContextMenuAction.LauncherAction
            assertEquals(AppContextMenuAction.ACTION_ID_UNHIDE_APP, hideAction.id)
            assertEquals(R.string.unhide_app_in_drawer, hideAction.labelRes)
        }

    @Test
    fun `hide action stays as hide when not hidden`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false)
            val hideAction = result.first {
                it is AppContextMenuAction.LauncherAction &&
                    (it.id == AppContextMenuAction.ACTION_ID_HIDE_APP ||
                        it.id == AppContextMenuAction.ACTION_ID_UNHIDE_APP)
            } as AppContextMenuAction.LauncherAction
            assertEquals(AppContextMenuAction.ACTION_ID_HIDE_APP, hideAction.id)
            assertEquals(R.string.hide_app_from_drawer, hideAction.labelRes)
        }

    // ------------------------------------------------------------------
    // Reset-usage branch (drawer-only, depends on hasUsageData)
    // ------------------------------------------------------------------

    @Test
    fun `reset-usage action is present in app drawer when usage data exists`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val result = useCase(app, MenuContext.APP_DRAWER, hasUsageData = true)
            assertTrue(
                result.any {
                    it is AppContextMenuAction.LauncherAction &&
                        it.id == AppContextMenuAction.ACTION_ID_RESET_USAGE
                },
            )
        }

    @Test
    fun `reset-usage action is absent in app drawer when no usage data`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val result = useCase(app, MenuContext.APP_DRAWER, hasUsageData = false)
            assertTrue(
                result.none {
                    it is AppContextMenuAction.LauncherAction &&
                        it.id == AppContextMenuAction.ACTION_ID_RESET_USAGE
                },
            )
        }

    @Test
    fun `reset-usage action is absent on home screen even with usage data`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = true)
            assertTrue(
                result.none {
                    it is AppContextMenuAction.LauncherAction &&
                        it.id == AppContextMenuAction.ACTION_ID_RESET_USAGE
                },
            )
        }

    // ------------------------------------------------------------------
    // Per-repo error fallback (the inherited crash-safety pattern)
    // ------------------------------------------------------------------

    @Test
    fun `shortcut repository failure leaves menu intact, just without shortcuts`() =
        runTest(mainDispatcherRule.testDispatcher) {
            every { shortcutRepository.getShortcutsForPackage(any()) } throws RuntimeException("boom")

            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false)
            // No shortcuts, no separator, but the rest is present.
            assertTrue(result.none { it is AppContextMenuAction.Shortcut })
            assertTrue(result.none { it is AppContextMenuAction.Separator })
            assertEquals(4, result.size)
        }

    @Test
    fun `favorites repository failure falls back to add_to_favorites label`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val brokenFavorites = mockk<FavoritesRepository>(relaxed = true) {
                coEvery { isFavoriteComponent(any()) } throws RuntimeException("boom")
            }
            useCase = newUseCase(favoritesRepo = brokenFavorites)

            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false)
            val toggleFavorite = result.first {
                it is AppContextMenuAction.LauncherAction &&
                    it.id == AppContextMenuAction.ACTION_ID_TOGGLE_FAVORITE
            } as AppContextMenuAction.LauncherAction
            // Fallback is `false` → "add_to_favorites".
            assertEquals(R.string.add_to_favorites, toggleFavorite.labelRes)
        }

    @Test
    fun `custom-names repository failure suppresses restore-original-name`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val brokenCustomNames = mockk<CustomNamesRepository>(relaxed = true) {
                coEvery { hasCustomNameForPackage(any()) } throws RuntimeException("boom")
            }
            useCase = newUseCase(customNamesRepo = brokenCustomNames)

            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false)
            assertTrue(
                result.none {
                    it is AppContextMenuAction.LauncherAction &&
                        it.id == AppContextMenuAction.ACTION_ID_RESTORE_NAME
                },
            )
        }

    @Test
    fun `hidden-apps repository failure falls back to hide id and label`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val brokenHidden = mockk<HiddenAppsRepository>(relaxed = true) {
                coEvery { isComponentHidden(any()) } throws RuntimeException("boom")
            }
            useCase = newUseCase(hiddenRepo = brokenHidden)

            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false)
            val hideAction = result.first {
                it is AppContextMenuAction.LauncherAction &&
                    (it.id == AppContextMenuAction.ACTION_ID_HIDE_APP ||
                        it.id == AppContextMenuAction.ACTION_ID_UNHIDE_APP)
            } as AppContextMenuAction.LauncherAction
            assertEquals(AppContextMenuAction.ACTION_ID_HIDE_APP, hideAction.id)
            assertEquals(R.string.hide_app_from_drawer, hideAction.labelRes)
        }

    // ------------------------------------------------------------------
    // Combined: full menu shape
    // ------------------------------------------------------------------

    @Test
    fun `full menu in app drawer with all features active matches expected order`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val s1: ShortcutInfo = mockk()
            every { shortcutRepository.getShortcutsForPackage(any()) } returns listOf(s1)
            fakeFavorites.favorites = setOf(app.componentName)
            fakeCustomNames.setCustomNameForPackage(app.packageName, "MyCam")
            fakeHidden.hiddenApps = setOf(app.componentName)

            val result = useCase(app, MenuContext.APP_DRAWER, hasUsageData = true)

            assertEquals(8, result.size)
            assertEquals(AppContextMenuAction.Shortcut(s1), result[0])
            assertEquals(AppContextMenuAction.Separator, result[1])
            assertEquals(AppContextMenuAction.ACTION_ID_TOGGLE_FAVORITE, launcherAction(result[2]).id)
            assertEquals(AppContextMenuAction.ACTION_ID_RESTORE_NAME, launcherAction(result[3]).id)
            assertEquals(AppContextMenuAction.ACTION_ID_RENAME_APP, launcherAction(result[4]).id)
            assertEquals(AppContextMenuAction.ACTION_ID_UNHIDE_APP, launcherAction(result[5]).id)
            assertEquals(AppContextMenuAction.ACTION_ID_RESET_USAGE, launcherAction(result[6]).id)
            assertEquals(AppContextMenuAction.ACTION_ID_APP_INFO, launcherAction(result[7]).id)
        }
}

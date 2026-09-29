package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.LauncherShortcut
import com.github.reygnn.kolibri_launcher.domain.model.LauncherActionLabel
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.MenuContext
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ShortcutRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.domain.model.AppContextMenuAction
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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
    fun `default state on home screen produces favorite, rename, hide, app-info, uninstall in order`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val result = useCase(
                appInfo = app,
                menuContext = MenuContext.HOME_SCREEN,
                hasUsageData = false,
                isSystemApp = false,
            )

            // No shortcuts → no separator. Non-system app → uninstall is last.
            assertThat(result.size).isEqualTo(5)
            assertThat(launcherAction(result[0]).id).isEqualTo(AppContextMenuAction.ACTION_ID_TOGGLE_FAVORITE)
            assertThat(launcherAction(result[1]).id).isEqualTo(AppContextMenuAction.ACTION_ID_RENAME_APP)
            assertThat(launcherAction(result[2]).id).isEqualTo(AppContextMenuAction.ACTION_ID_HIDE_APP)
            assertThat(launcherAction(result[3]).id).isEqualTo(AppContextMenuAction.ACTION_ID_APP_INFO)
            assertThat(launcherAction(result[4]).id).isEqualTo(AppContextMenuAction.ACTION_ID_UNINSTALL)
        }

    // ------------------------------------------------------------------
    // Shortcuts + separator
    // ------------------------------------------------------------------

    @Test
    fun `shortcuts are emitted before a separator and the rest of the menu`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val s1 = LauncherShortcut(id = "s1", packageName = app.packageName, shortLabel = "S1")
            val s2 = LauncherShortcut(id = "s2", packageName = app.packageName, shortLabel = "S2")
            every { shortcutRepository.getShortcutsForPackage(app.packageName) } returns listOf(s1, s2)

            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false, isSystemApp = false)

            assertThat(result[0]).isEqualTo(AppContextMenuAction.Shortcut(s1))
            assertThat(result[1]).isEqualTo(AppContextMenuAction.Shortcut(s2))
            assertThat(result[2]).isEqualTo(AppContextMenuAction.Separator)
            // Then favorite, rename, hide, app-info, uninstall — total 8.
            assertThat(result.size).isEqualTo(8)
        }

    @Test
    fun `no separator emitted when there are no shortcuts`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false, isSystemApp = false)

            assertThat(result.none { it is AppContextMenuAction.Separator }).isTrue()
        }

    // ------------------------------------------------------------------
    // Favorite branch
    // ------------------------------------------------------------------

    @Test
    fun `favorite action label flips from add to remove when isFavorite is true`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeFavorites.favorites = setOf(app.componentName)

            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false, isSystemApp = false)
            val toggleFavorite = result.first {
                it is AppContextMenuAction.LauncherAction &&
                    it.id == AppContextMenuAction.ACTION_ID_TOGGLE_FAVORITE
            } as AppContextMenuAction.LauncherAction
            assertThat(toggleFavorite.label).isEqualTo(LauncherActionLabel.RemoveFromFavorites)
        }

    @Test
    fun `favorite action label is add_to_favorites when not currently a favorite`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false, isSystemApp = false)
            val toggleFavorite = result.first {
                it is AppContextMenuAction.LauncherAction &&
                    it.id == AppContextMenuAction.ACTION_ID_TOGGLE_FAVORITE
            } as AppContextMenuAction.LauncherAction
            assertThat(toggleFavorite.label).isEqualTo(LauncherActionLabel.AddToFavorites)
        }

    // ------------------------------------------------------------------
    // Custom-name branch
    // ------------------------------------------------------------------

    @Test
    fun `restore-original-name action is present when a custom name is set`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeCustomNames.setCustomNameForPackage(app.packageName, "MyCam")

            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false, isSystemApp = false)
            assertThat(result.any {
                    it is AppContextMenuAction.LauncherAction &&
                        it.id == AppContextMenuAction.ACTION_ID_RESTORE_NAME &&
                        it.label == LauncherActionLabel.RestoreOriginalName
                }).isTrue()
        }

    @Test
    fun `restore-original-name action is absent when no custom name is set`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false, isSystemApp = false)
            assertThat(result.none {
                    it is AppContextMenuAction.LauncherAction &&
                        it.id == AppContextMenuAction.ACTION_ID_RESTORE_NAME
                }).isTrue()
        }

    // ------------------------------------------------------------------
    // Hidden branch
    // ------------------------------------------------------------------

    @Test
    fun `hide action becomes unhide with switched id and label when isHidden is true`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeHidden.hiddenApps = setOf(app.componentName)

            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false, isSystemApp = false)
            val hideAction = result.first {
                it is AppContextMenuAction.LauncherAction &&
                    (it.id == AppContextMenuAction.ACTION_ID_HIDE_APP ||
                        it.id == AppContextMenuAction.ACTION_ID_UNHIDE_APP)
            } as AppContextMenuAction.LauncherAction
            assertThat(hideAction.id).isEqualTo(AppContextMenuAction.ACTION_ID_UNHIDE_APP)
            assertThat(hideAction.label).isEqualTo(LauncherActionLabel.UnhideAppInDrawer)
        }

    @Test
    fun `hide action stays as hide when not hidden`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false, isSystemApp = false)
            val hideAction = result.first {
                it is AppContextMenuAction.LauncherAction &&
                    (it.id == AppContextMenuAction.ACTION_ID_HIDE_APP ||
                        it.id == AppContextMenuAction.ACTION_ID_UNHIDE_APP)
            } as AppContextMenuAction.LauncherAction
            assertThat(hideAction.id).isEqualTo(AppContextMenuAction.ACTION_ID_HIDE_APP)
            assertThat(hideAction.label).isEqualTo(LauncherActionLabel.HideAppFromDrawer)
        }

    // ------------------------------------------------------------------
    // Reset-usage branch (drawer-only, depends on hasUsageData)
    // ------------------------------------------------------------------

    @Test
    fun `reset-usage action is present in app drawer when usage data exists`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val result = useCase(app, MenuContext.APP_DRAWER, hasUsageData = true, isSystemApp = false)
            assertThat(result.any {
                    it is AppContextMenuAction.LauncherAction &&
                        it.id == AppContextMenuAction.ACTION_ID_RESET_USAGE
                }).isTrue()
        }

    @Test
    fun `reset-usage action is absent in app drawer when no usage data`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val result = useCase(app, MenuContext.APP_DRAWER, hasUsageData = false, isSystemApp = false)
            assertThat(result.none {
                    it is AppContextMenuAction.LauncherAction &&
                        it.id == AppContextMenuAction.ACTION_ID_RESET_USAGE
                }).isTrue()
        }

    @Test
    fun `reset-usage action is absent on home screen even with usage data`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = true, isSystemApp = false)
            assertThat(result.none {
                    it is AppContextMenuAction.LauncherAction &&
                        it.id == AppContextMenuAction.ACTION_ID_RESET_USAGE
                }).isTrue()
        }

    // ------------------------------------------------------------------
    // Per-repo error fallback (the inherited crash-safety pattern)
    // ------------------------------------------------------------------

    @Test
    fun `shortcut repository failure leaves menu intact, just without shortcuts`() =
        runTest(mainDispatcherRule.testDispatcher) {
            every { shortcutRepository.getShortcutsForPackage(any()) } throws RuntimeException("boom")

            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false, isSystemApp = false)
            // No shortcuts, no separator, but the rest is present.
            assertThat(result.none { it is AppContextMenuAction.Shortcut }).isTrue()
            assertThat(result.none { it is AppContextMenuAction.Separator }).isTrue()
            assertThat(result.size).isEqualTo(5)
        }

    @Test
    fun `favorites repository failure falls back to add_to_favorites label`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val brokenFavorites = mockk<FavoritesRepository>(relaxed = true) {
                coEvery { isFavoriteComponent(any()) } throws RuntimeException("boom")
            }
            useCase = newUseCase(favoritesRepo = brokenFavorites)

            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false, isSystemApp = false)
            val toggleFavorite = result.first {
                it is AppContextMenuAction.LauncherAction &&
                    it.id == AppContextMenuAction.ACTION_ID_TOGGLE_FAVORITE
            } as AppContextMenuAction.LauncherAction
            // Fallback is `false` → "add_to_favorites".
            assertThat(toggleFavorite.label).isEqualTo(LauncherActionLabel.AddToFavorites)
        }

    @Test
    fun `custom-names repository failure suppresses restore-original-name`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val brokenCustomNames = mockk<CustomNamesRepository>(relaxed = true) {
                coEvery { hasCustomNameForPackage(any()) } throws RuntimeException("boom")
            }
            useCase = newUseCase(customNamesRepo = brokenCustomNames)

            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false, isSystemApp = false)
            assertThat(result.none {
                    it is AppContextMenuAction.LauncherAction &&
                        it.id == AppContextMenuAction.ACTION_ID_RESTORE_NAME
                }).isTrue()
        }

    @Test
    fun `hidden-apps repository failure falls back to hide id and label`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val brokenHidden = mockk<HiddenAppsRepository>(relaxed = true) {
                coEvery { isComponentHidden(any()) } throws RuntimeException("boom")
            }
            useCase = newUseCase(hiddenRepo = brokenHidden)

            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false, isSystemApp = false)
            val hideAction = result.first {
                it is AppContextMenuAction.LauncherAction &&
                    (it.id == AppContextMenuAction.ACTION_ID_HIDE_APP ||
                        it.id == AppContextMenuAction.ACTION_ID_UNHIDE_APP)
            } as AppContextMenuAction.LauncherAction
            assertThat(hideAction.id).isEqualTo(AppContextMenuAction.ACTION_ID_HIDE_APP)
            assertThat(hideAction.label).isEqualTo(LauncherActionLabel.HideAppFromDrawer)
        }

    // ------------------------------------------------------------------
    // Combined: full menu shape
    // ------------------------------------------------------------------

    @Test
    fun `full menu in app drawer with all features active matches expected order`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val s1 = LauncherShortcut(id = "s1", packageName = app.packageName, shortLabel = "S1")
            every { shortcutRepository.getShortcutsForPackage(any()) } returns listOf(s1)
            fakeFavorites.favorites = setOf(app.componentName)
            fakeCustomNames.setCustomNameForPackage(app.packageName, "MyCam")
            fakeHidden.hiddenApps = setOf(app.componentName)

            val result = useCase(app, MenuContext.APP_DRAWER, hasUsageData = true, isSystemApp = false)

            assertThat(result.size).isEqualTo(9)
            assertThat(result[0]).isEqualTo(AppContextMenuAction.Shortcut(s1))
            assertThat(result[1]).isEqualTo(AppContextMenuAction.Separator)
            assertThat(launcherAction(result[2]).id).isEqualTo(AppContextMenuAction.ACTION_ID_TOGGLE_FAVORITE)
            assertThat(launcherAction(result[3]).id).isEqualTo(AppContextMenuAction.ACTION_ID_RESTORE_NAME)
            assertThat(launcherAction(result[4]).id).isEqualTo(AppContextMenuAction.ACTION_ID_RENAME_APP)
            assertThat(launcherAction(result[5]).id).isEqualTo(AppContextMenuAction.ACTION_ID_UNHIDE_APP)
            assertThat(launcherAction(result[6]).id).isEqualTo(AppContextMenuAction.ACTION_ID_RESET_USAGE)
            assertThat(launcherAction(result[7]).id).isEqualTo(AppContextMenuAction.ACTION_ID_APP_INFO)
            assertThat(launcherAction(result[8]).id).isEqualTo(AppContextMenuAction.ACTION_ID_UNINSTALL)
        }

    // ------------------------------------------------------------------
    // Uninstall branch (last entry, gated on non-system app)
    // ------------------------------------------------------------------

    @Test
    fun `uninstall action is present and last for a non-system app`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false, isSystemApp = false)

            assertThat(launcherAction(result.last()).id).isEqualTo(AppContextMenuAction.ACTION_ID_UNINSTALL)
            assertThat(launcherAction(result.last()).label).isEqualTo(LauncherActionLabel.Uninstall)
        }

    @Test
    fun `uninstall action is absent for a system app`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val result = useCase(app, MenuContext.HOME_SCREEN, hasUsageData = false, isSystemApp = true)

            assertThat(result.none {
                    it is AppContextMenuAction.LauncherAction &&
                        it.id == AppContextMenuAction.ACTION_ID_UNINSTALL
                }).isTrue()
        }
}

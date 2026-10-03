package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.launcher.feature.wallpaper.FabPositionStore
import com.github.reygnn.launcher.feature.wallpaper.WallpaperDisplaySettingsStore
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.kolibri_launcher.domain.usecase.FactoryResetUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.fakes.FakeWallpaperRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.InstalledAppsRepository
import com.github.reygnn.launcher.core.InstalledAppsStateRepository
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.timeinfo.TimeBasedEventsRepository
import com.github.reygnn.launcher.core.wallpaper.FabPosition
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * 2b-4c, F1: a store that fails to purge makes Kolibri's factory reset report `PartialFailure` —
 * reachable for the first time, since purges used to swallow their errors. The other stores are
 * purged anyway (per-store isolation in `purgeAll`), and a cancellation passes unchanged.
 */
class FactoryResetPartialFailureTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private val settingsStore = FakeDataStore()
    private val favoritesStore = FakeDataStore()

    private fun factoryReset(): FactoryResetUseCase {
        val reset = ResetRepositoryImpl(
            favoritesRepository = FavoritesRepositoryImpl(favoritesStore),
            hiddenAppsRepository = HiddenAppsRepositoryImpl(settingsStore),
            customNamesRepository = CustomNamesRepositoryImpl(settingsStore),
            appUsageRepository = AppUsageRepositoryImpl(FakeDataStore(), mockk(relaxed = true)),
            favoritesOrderRepository = FavoritesOrderRepositoryImpl(settingsStore),
            swipeActionsRepository = SwipeActionsRepositoryImpl(settingsStore),
            wallpaperRepository = FakeWallpaperRepository(),
            fabPositionRepository = FabPositionStore(settingsStore),
            settingsRepository = SettingsRepositoryImpl(settingsStore, WallpaperDisplaySettingsStore(settingsStore, KolibriWallpaperDisplayKeys)),
            installedAppsStateRepository = mockk<InstalledAppsStateRepository>(relaxed = true),
            timeBasedEventsRepository = mockk<TimeBasedEventsRepository>(relaxed = true),
        )
        return FactoryResetUseCase(reset, mockk<InstalledAppsRepository>(relaxed = true))
    }

    private suspend fun seedSettingsStore() {
        HiddenAppsRepositoryImpl(settingsStore).updateComponentVisibilities(setOf("com.h/com.h.Main"), emptySet())
        CustomNamesRepositoryImpl(settingsStore).setCustomNamesInBatch(mapOf("com.a" to "Alpha"))
        FavoritesOrderRepositoryImpl(settingsStore).saveOrder(listOf("com.a/com.a.Main"))
        SwipeActionsRepositoryImpl(settingsStore).setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, "com.a/com.a.Main")
        FabPositionStore(settingsStore).saveFabPosition(FabPosition(0.2f, 0.3f))
        with(SettingsRepositoryImpl(settingsStore, WallpaperDisplaySettingsStore(settingsStore, KolibriWallpaperDisplayKeys))) {
            setTextColor(0xFF123456.toInt())
            setOnboardingCompleted()
        }
    }

    @Test
    fun `a failing store makes the reset a partial failure and the others are purged anyway`() =
        runTest(mainDispatcherRule.testDispatcher) {
            seedSettingsStore()
            favoritesStore.makeEditFail()

            val result = factoryReset()(includeUsageData = true)

            assertThat(result).isEqualTo(FactoryResetUseCase.Result.PartialFailure)
            assertThat(settingsStore.data.first().asMap().keys.map { it.name })
                .containsExactly(AppConstants.PrefKeys.ONBOARDING_COMPLETED) // purge-exempt
        }

    @Test
    fun `a cancelled purge passes through the reset unchanged`() = runTest(mainDispatcherRule.testDispatcher) {
        favoritesStore.makeCancellable()

        assertFailsWith<CancellationException> { factoryReset()(includeUsageData = true) }
    }
}

package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.DefaultAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.installedapps.FakeInstalledAppsRepository
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** 2b/29: after a reset Kolibri reseeds its curated default favorites — only into empty favorites. */
class SeedDefaultFavoritesUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val phone = AppInfo("Phone", "Phone", "com.phone", "com.phone.Main")
    private val browser = AppInfo("Browser", "Browser", "com.browser", "com.browser.Main")
    private val other = AppInfo("Other", "Other", "com.other", "com.other.Main")

    private val favorites = FakeFavoritesRepository()
    private val settings = FakeSettingsRepository()
    private val installedApps = FakeInstalledAppsRepository()
    private val defaultApps = mockk<DefaultAppsRepository> {
        coEvery { getDefaultAppPackages() } returns listOf("com.phone", "com.browser")
    }

    private val seed = SeedDefaultFavoritesUseCase(
        favoritesRepository = favorites,
        onboardingAppsUseCase = GetOnboardingAppsUseCase(installedApps, FakeCustomNamesRepository()),
        getDefaultFavoriteComponentsUseCase = GetDefaultFavoriteComponentsUseCase(defaultApps),
        completeOnboardingUseCase = CompleteOnboardingUseCase(favorites, settings),
    )

    @Test
    fun empty_favorites_get_the_curated_default_set() = runTest(mainDispatcherRule.testDispatcher) {
        installedApps.installedApps = listOf(other, phone, browser)

        assertThat(seed()).isTrue()

        assertThat(favorites.favorites).containsExactly(phone.componentName, browser.componentName)
        // Same save path as onboarding's done step, but the onboarding flag is left alone.
        assertThat(settings.onboardingCompletedFlow.first()).isFalse()
    }

    @Test
    fun favorites_that_survived_a_failed_purge_are_never_overwritten() = runTest(mainDispatcherRule.testDispatcher) {
        // PartialFailure: the favorites purge failed, the user's set is still there.
        installedApps.installedApps = listOf(other, phone, browser)
        favorites.favorites = setOf(other.componentName)

        assertThat(seed()).isFalse()

        assertThat(favorites.favorites).containsExactly(other.componentName)
    }

    @Test
    fun no_apps_in_time_seeds_nothing() = runTest(mainDispatcherRule.testDispatcher) {
        // The enumeration never populates: bounded wait, legitimate empty result, no hang.
        assertThat(seed()).isFalse()

        assertThat(favorites.favorites).isEmpty()
    }
}

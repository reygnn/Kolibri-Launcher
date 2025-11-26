package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.usecase.CompleteOnboardingUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompleteOnboardingUseCaseTest {

    private lateinit var favoritesRepository: FakeFavoritesRepository
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var useCase: CompleteOnboardingUseCase

    private val testComponentNames = listOf(
        "com.app1/com.app1.Main",
        "com.app2/com.app2.Main",
        "com.app3/com.app3.Main"
    )

    @Before
    fun setup() {
        favoritesRepository = FakeFavoritesRepository()
        settingsRepository = FakeSettingsRepository()
        useCase = CompleteOnboardingUseCase(favoritesRepository, settingsRepository)
    }

    // =========================================================================
    // Favoriten speichern
    // =========================================================================

    @Test
    fun `invoke saves favorite components`() = runTest {
        // Act
        useCase(testComponentNames, isInitialSetup = false)

        // Assert
        assertThat(favoritesRepository.favorites).containsExactlyElementsIn(testComponentNames)
    }

    @Test
    fun `invoke with empty list clears favorites`() = runTest {
        // Arrange: Existierende Favoriten
        favoritesRepository.favorites = testComponentNames.toSet()

        // Act
        useCase(emptyList(), isInitialSetup = false)

        // Assert
        assertThat(favoritesRepository.favorites).isEmpty()
    }

    // =========================================================================
    // Initial Setup - Onboarding abschließen
    // =========================================================================

    @Test
    fun `invoke with isInitialSetup true marks onboarding completed`() = runTest {
        // Arrange
        assertThat(settingsRepository.onboardingCompleted).isFalse()

        // Act
        useCase(testComponentNames, isInitialSetup = true)

        // Assert
        assertThat(settingsRepository.onboardingCompleted).isTrue()
    }

    @Test
    fun `invoke with isInitialSetup true saves favorites AND marks onboarding`() = runTest {
        // Act
        useCase(testComponentNames, isInitialSetup = true)

        // Assert
        assertThat(favoritesRepository.favorites).containsExactlyElementsIn(testComponentNames)
        assertThat(settingsRepository.onboardingCompleted).isTrue()
    }

    // =========================================================================
    // Edit Favorites - Onboarding nicht ändern
    // =========================================================================

    @Test
    fun `invoke with isInitialSetup false does not mark onboarding completed`() = runTest {
        // Arrange
        assertThat(settingsRepository.onboardingCompleted).isFalse()

        // Act
        useCase(testComponentNames, isInitialSetup = false)

        // Assert
        assertThat(settingsRepository.onboardingCompleted).isFalse()
    }

    @Test
    fun `invoke with isInitialSetup false does not change existing onboarding status`() = runTest {
        // Arrange: Onboarding bereits abgeschlossen
        settingsRepository.setOnboardingCompletedForTest(true)

        // Act
        useCase(testComponentNames, isInitialSetup = false)

        // Assert: Bleibt true
        assertThat(settingsRepository.onboardingCompleted).isTrue()
    }
}
package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleSortOrderUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToggleSortOrderUseCaseTest {

    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var useCase: ToggleSortOrderUseCase

    @Before
    fun setup() {
        settingsRepository = FakeSettingsRepository()
        useCase = ToggleSortOrderUseCase(settingsRepository)
    }

    // =========================================================================
    // Toggle von ALPHABETICAL zu TIME_WEIGHTED_USAGE
    // =========================================================================

    @Test
    fun `invoke toggles from ALPHABETICAL to TIME_WEIGHTED_USAGE`() = runTest {
        // Arrange
        settingsRepository.setSortOrderForTest(SortOrder.ALPHABETICAL)

        // Act
        useCase()

        // Assert
        assertThat(settingsRepository.currentSortOrder).isEqualTo(SortOrder.TIME_WEIGHTED_USAGE)
    }

    // =========================================================================
    // Toggle von TIME_WEIGHTED_USAGE zu ALPHABETICAL
    // =========================================================================

    @Test
    fun `invoke toggles from TIME_WEIGHTED_USAGE to ALPHABETICAL`() = runTest {
        // Arrange
        settingsRepository.setSortOrderForTest(SortOrder.TIME_WEIGHTED_USAGE)

        // Act
        useCase()

        // Assert
        assertThat(settingsRepository.currentSortOrder).isEqualTo(SortOrder.ALPHABETICAL)
    }

    // =========================================================================
    // Doppelter Toggle
    // =========================================================================

    @Test
    fun `invoke twice returns to original order`() = runTest {
        // Arrange
        settingsRepository.setSortOrderForTest(SortOrder.ALPHABETICAL)

        // Act
        useCase()
        useCase()

        // Assert
        assertThat(settingsRepository.currentSortOrder).isEqualTo(SortOrder.ALPHABETICAL)
    }
}
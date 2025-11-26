package com.github.reygnn.kolibri_launcher.domain

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveHomeSettingsUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

@ExperimentalCoroutinesApi
class ObserveHomeSettingsUseCaseTest {

    @Mock
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var useCase: ObserveHomeSettingsUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = ObserveHomeSettingsUseCase(settingsRepository)
    }

    @Test
    fun `invoke - combines flows into HomeSettings correctly`() = runTest {
        // Arrange
        // Nutzung eines echten Enum-Wertes statt eines Mocks
        val expectedSortOrder = SortOrder.ALPHABETICAL

        val sortOrderFlow = MutableStateFlow(expectedSortOrder)
        val doubleTapFlow = MutableStateFlow(true)
        val swipeDownFlow = MutableStateFlow(false)
        val autoLaunchFlow = MutableStateFlow(true)

        whenever(settingsRepository.sortOrderFlow).thenReturn(sortOrderFlow)
        whenever(settingsRepository.doubleTapToLockEnabledFlow).thenReturn(doubleTapFlow)
        whenever(settingsRepository.swipeDownToNotificationsEnabledFlow).thenReturn(swipeDownFlow)
        whenever(settingsRepository.autoLaunchAppFlow).thenReturn(autoLaunchFlow)

        // Act
        useCase().test {
            val result = awaitItem()

            // Assert
            assertEquals(expectedSortOrder, result.sortOrder)
            assertEquals(true, result.doubleTapToLockEnabled)
            assertEquals(false, result.swipeDownToNotificationsEnabled)
            assertEquals(true, result.autoLaunchApp)
        }
    }
}
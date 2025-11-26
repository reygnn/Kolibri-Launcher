package com.github.reygnn.kolibri_launcher.domain.usecase

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
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
            // 1. Initial State Check
            val initialResult = awaitItem()
            assertEquals(expectedSortOrder, initialResult.sortOrder)
            assertEquals(true, initialResult.doubleTapToLockEnabled)
            assertEquals(false, initialResult.swipeDownToNotificationsEnabled)
            assertEquals(true, initialResult.autoLaunchApp)

            // 2. Reactivity Check (Update one flow)
            // Ändere einen Wert im Repository
            val newSortOrder = SortOrder.TIME_WEIGHTED_USAGE
            sortOrderFlow.value = newSortOrder

            // Der UseCase muss ein neues HomeSettings Objekt emittieren
            val updatedResult = awaitItem()
            assertEquals(newSortOrder, updatedResult.sortOrder)
            // Die anderen Werte müssen unverändert bleiben
            assertEquals(true, updatedResult.doubleTapToLockEnabled)
        }
    }
}
package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertSame

@ExperimentalCoroutinesApi
class GetLayoutSettingsUseCasesTest {

    @Mock
    private lateinit var repository: SettingsRepository

    // Flows für den Mock
    private val layoutScaleFlow = MutableStateFlow(1.0f)
    private val verticalPaddingFlow = MutableStateFlow(1.0f)
    private val isFontBoldFlow = MutableStateFlow(false)
    private val contentTopMarginFlow = MutableStateFlow(1.0f)

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)

        // Mock-Verhalten für die Flows definieren
        whenever(repository.layoutScaleStateFlow).thenReturn(layoutScaleFlow)
        whenever(repository.verticalPaddingStateFlow).thenReturn(verticalPaddingFlow)
        whenever(repository.isFontBoldStateFlow).thenReturn(isFontBoldFlow)
        whenever(repository.contentTopMarginScaleFlow).thenReturn(contentTopMarginFlow)
    }

    @Test
    fun `GetLayoutSettingsUseCase - wires up repository flows correctly`() {
        // Arrange
        val useCase = GetLayoutSettingsUseCase(repository)

        // Assert
        // Wir prüfen hier auf Referenz-Gleichheit (assertSame), da der UseCase
        // exakt denselben Flow-Objekt zurückgeben soll, den das Repo liefert.
        assertSame(layoutScaleFlow, useCase.layoutScale)
        assertSame(verticalPaddingFlow, useCase.verticalPadding)
        assertSame(isFontBoldFlow, useCase.isFontBold)
        assertSame(contentTopMarginFlow, useCase.contentTopMargin)
    }

    @Test
    fun `SetLayoutScaleUseCase - delegates to repository`() = runTest {
        val useCase = SetLayoutScaleUseCase(repository)
        val testValue = 1.5f

        useCase(testValue)

        verify(repository).setLayoutScale(testValue)
    }

    @Test
    fun `SetVerticalPaddingUseCase - delegates to repository`() = runTest {
        val useCase = SetVerticalPaddingUseCase(repository)
        val testValue = 2.0f

        useCase(testValue)

        verify(repository).setVerticalPadding(testValue)
    }

    @Test
    fun `SetFontBoldUseCase - delegates to repository`() = runTest {
        val useCase = SetFontBoldUseCase(repository)
        val testValue = true

        useCase(testValue)

        verify(repository).setFontBold(testValue)
    }

    @Test
    fun `SetContentTopMarginUseCase - delegates to repository`() = runTest {
        val useCase = SetContentTopMarginUseCase(repository)
        val testValue = 0.8f

        useCase(testValue)

        verify(repository).setContentTopMarginScale(testValue)
    }
}
package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetHiddenAppsUseCase
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
class GetHiddenAppsUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var repository: HiddenAppsRepository

    private lateinit var useCase: GetHiddenAppsUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = GetHiddenAppsUseCase(repository)
    }

    @Test
    fun `invoke - returns hiddenAppsFlow from repository`() = runTest {
        // Arrange
        val expectedSet = setOf("com.app1/Component", "com.app2/Component")
        Mockito.`when`(repository.hiddenAppsFlow).thenReturn(flowOf(expectedSet))

        // Act
        val resultFlow = useCase()

        // Assert
        resultFlow.collect { result ->
            assertEquals(expectedSet, result)
        }
        // Verify property access
        verify(repository).hiddenAppsFlow
    }
}
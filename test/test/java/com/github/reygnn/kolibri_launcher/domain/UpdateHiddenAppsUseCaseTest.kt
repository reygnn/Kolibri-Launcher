package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.UpdateHiddenAppsUseCase
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
class UpdateHiddenAppsUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var repository: HiddenAppsRepository

    private lateinit var useCase: UpdateHiddenAppsUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = UpdateHiddenAppsUseCase(repository)
    }

    @Test
    fun `invoke - delegates to repository updateComponentVisibilities`() = runTest {
        // Arrange
        val toHide = setOf("com.hide/Component")
        val toShow = setOf("com.show/Component")

        // Act
        useCase(toHide, toShow)

        // Assert
        verify(repository).updateComponentVisibilities(toHide, toShow)
    }
}
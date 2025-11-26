package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.RemoveCustomNameUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
class RemoveCustomNameUseCaseTest {

    @Mock
    private lateinit var repository: CustomNamesRepository

    private lateinit var useCase: RemoveCustomNameUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = RemoveCustomNameUseCase(repository)
    }

    @Test
    fun `invoke - delegates to repository removeCustomNameForPackage`() = runTest {
        // Arrange
        val packageName = "com.test.app"
        // Nutzung von Standard-Mockito statt 'whenever'
        // `when` muss in Backticks gesetzt werden, da es ein Kotlin-Keyword ist
        Mockito.`when`(repository.removeCustomNameForPackage(packageName)).thenReturn(true)

        // Act
        val result = useCase(packageName)

        // Assert
        assertTrue(result)
        verify(repository).removeCustomNameForPackage(packageName)
    }
}
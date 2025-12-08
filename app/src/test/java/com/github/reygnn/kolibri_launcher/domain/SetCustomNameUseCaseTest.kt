package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.SetCustomNameUseCase
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class SetCustomNameUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var repository: CustomNamesRepository

    private lateinit var useCase: SetCustomNameUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = SetCustomNameUseCase(repository)
    }

    @Test
    fun `invoke - delegates to repository setCustomNameForPackage`() = runTest {
        // Arrange
        val packageName = "com.test.app"
        val customName = "My App"
        whenever(repository.setCustomNameForPackage(packageName, customName)).thenReturn(true)

        // Act
        val result = useCase(packageName, customName)

        // Assert
        assertTrue(result)
        verify(repository).setCustomNameForPackage(packageName, customName)
    }
}
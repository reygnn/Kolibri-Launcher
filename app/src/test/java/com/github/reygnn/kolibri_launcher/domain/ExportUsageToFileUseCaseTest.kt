package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.UsageExportRepository
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnitRunner
import java.io.IOException

@RunWith(MockitoJUnitRunner::class)
class ExportUsageToFileUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var repository: UsageExportRepository

    private lateinit var useCase: ExportUsageToFileUseCase

    @Before
    fun setup() {
        useCase = ExportUsageToFileUseCase(repository)
    }

    @Test
    fun `invoke - when repository returns true - returns Success`() = runTest {
        // Arrange
        val uri = "content://valid"
        `when`(repository.saveToFile(uri)).thenReturn(true)

        // Act
        val result = useCase(uri)

        // Assert
        assertTrue(result.isSuccess)
    }

    @Test
    fun `invoke - when repository returns false - returns Failure with IOException`() = runTest {
        // Arrange
        val uri = "content://valid"
        `when`(repository.saveToFile(uri)).thenReturn(false)

        // Act
        val result = useCase(uri)

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue(result.exceptionOrNull()?.message == "Could not write file")
    }

    @Test
    fun `invoke - when repository throws Exception - catches and returns Failure`() = runTest {
        // Arrange
        val uri = "content://crash"
        val exception = RuntimeException("Boom")
        `when`(repository.saveToFile(uri)).thenThrow(exception)

        // Act
        val result = useCase(uri)

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() == exception)
    }
}
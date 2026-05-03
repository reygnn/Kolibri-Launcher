package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.UsageExportRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class ExportUsageToFileUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var repository: UsageExportRepository

    private lateinit var useCase: ExportUsageToFileUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = ExportUsageToFileUseCase(repository)
    }

    @Test
    fun `invoke - when repository returns true - returns Success`() = runTest {
        // Arrange
        val uri = "content://valid"
        coEvery { repository.saveToFile(uri) } returns true

        // Act
        val result = useCase(uri)

        // Assert
        assertTrue(result.isSuccess)
    }

    @Test
    fun `invoke - when repository returns false - returns Failure with IOException`() = runTest {
        // Arrange
        val uri = "content://valid"
        coEvery { repository.saveToFile(uri) } returns false

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
        coEvery { repository.saveToFile(uri) } throws exception

        // Act
        val result = useCase(uri)

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() == exception)
    }
}

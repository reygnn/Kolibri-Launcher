package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.domain.repository.UsageExportRepository
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class ImportUsageFromFileUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var repository: UsageExportRepository

    private lateinit var useCase: ImportUsageFromFileUseCase

    @Before
    fun setup() {
        useCase = ImportUsageFromFileUseCase(repository)
    }

    @Test
    fun `invoke - calls repository with correct params and returns Success result`() = runTest {
        // Arrange
        val uri = "content://backup.json"
        val merge = true
        val expectedResult = UsageImportResult.Success(
            packagesImported = 5,
            timestampsImported = 20,
            packagesSkipped = 0
        )

        `when`(repository.loadFromFile(uri, merge)).thenReturn(expectedResult)

        // Act
        val result = useCase(uri, merge)

        // Assert
        assertEquals(expectedResult, result)
        verify(repository).loadFromFile(uri, merge)
    }

    @Test
    fun `invoke - calls repository and returns Error result`() = runTest {
        // Arrange
        val uri = "content://corrupt.json"
        val merge = false
        val expectedResult = UsageImportResult.InvalidFormat

        `when`(repository.loadFromFile(uri, merge)).thenReturn(expectedResult)

        // Act
        val result = useCase(uri, merge)

        // Assert
        assertEquals(expectedResult, result)
        verify(repository).loadFromFile(uri, merge)
    }
}
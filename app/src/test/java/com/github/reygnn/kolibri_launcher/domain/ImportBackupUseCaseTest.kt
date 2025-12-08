package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.ImportBackupUseCase
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class ImportBackupUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var repository: BackupRepository

    private lateinit var useCase: ImportBackupUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = ImportBackupUseCase(repository)
    }

    @Test
    fun `invoke - delegates to repository loadBackupFromFile with options`() = runTest {
        // Arrange
        val uriString = "content://backup"
        val options = ImportOptions(importFavorites = true, importOrder = false)
        val expectedResult = ImportResult.Success(5, 0, emptySet())

        whenever(repository.loadBackupFromFile(uriString, options)).thenReturn(expectedResult)

        // Act
        val result = useCase(uriString, options)

        // Assert
        assertEquals(expectedResult, result)
        verify(repository).loadBackupFromFile(uriString, options)
    }
}
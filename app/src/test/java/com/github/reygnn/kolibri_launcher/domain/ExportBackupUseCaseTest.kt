package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.ExportBackupUseCase
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class ExportBackupUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var repository: BackupRepository

    private lateinit var useCase: ExportBackupUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = ExportBackupUseCase(repository)
    }

    @Test
    fun `invoke - delegates to repository saveBackupToFile`() = runTest {
        // Arrange
        val uriString = "content://backup"
        whenever(repository.saveBackupToFile(uriString)).thenReturn(true)

        // Act
        val result = useCase(uriString)

        // Assert
        assertTrue(result)
        verify(repository).saveBackupToFile(uriString)
    }
}
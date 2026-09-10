package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.ExportBackupUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class ExportBackupUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var repository: BackupRepository

    private lateinit var useCase: ExportBackupUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = ExportBackupUseCase(repository)
    }

    @Test
    fun `invoke - delegates to repository saveBackupToFile`() = runTest {
        // Arrange
        val uriString = "content://backup"
        coEvery { repository.saveBackupToFile(uriString) } returns true

        // Act
        val result = useCase(uriString)

        // Assert
        assertTrue(result)
        coVerify { repository.saveBackupToFile(uriString) }
    }
}

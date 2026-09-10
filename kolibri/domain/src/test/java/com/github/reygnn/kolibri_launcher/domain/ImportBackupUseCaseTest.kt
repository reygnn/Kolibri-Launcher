package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.ImportBackupUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
class ImportBackupUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var repository: BackupRepository

    private lateinit var useCase: ImportBackupUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = ImportBackupUseCase(repository)
    }

    @Test
    fun `invoke - delegates to repository loadBackupFromFile with options`() = runTest {
        val uriString = "content://backup"
        val options = ImportOptions(importFavorites = true, importOrder = false)
        val expectedResult = ImportResult.Success(5, 0, emptySet())

        coEvery { repository.loadBackupFromFile(uriString, options) } returns expectedResult

        val result = useCase(uriString, options)

        assertEquals(expectedResult, result)
        coVerify { repository.loadBackupFromFile(uriString, options) }
    }
}

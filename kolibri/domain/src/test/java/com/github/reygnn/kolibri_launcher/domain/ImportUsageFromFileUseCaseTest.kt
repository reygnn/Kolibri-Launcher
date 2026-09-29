package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.domain.repository.UsageExportRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ImportUsageFromFileUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var repository: UsageExportRepository

    private lateinit var useCase: ImportUsageFromFileUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = ImportUsageFromFileUseCase(repository)
    }

    @Test
    fun `invoke - calls repository with correct params and returns Success result`() = runTest {
        val uri = "content://backup.json"
        val merge = true
        val expectedResult = UsageImportResult.Success(
            packagesImported = 5, timestampsImported = 20, packagesSkipped = 0
        )
        coEvery { repository.loadFromFile(uri, merge) } returns expectedResult

        val result = useCase(uri, merge)

        assertThat(result).isEqualTo(expectedResult)
        coVerify { repository.loadFromFile(uri, merge) }
    }

    @Test
    fun `invoke - calls repository and returns Error result`() = runTest {
        val uri = "content://corrupt.json"
        val merge = false
        coEvery { repository.loadFromFile(uri, merge) } returns UsageImportResult.InvalidFormat

        val result = useCase(uri, merge)

        assertThat(result).isEqualTo(UsageImportResult.InvalidFormat)
        coVerify { repository.loadFromFile(uri, merge) }
    }
}

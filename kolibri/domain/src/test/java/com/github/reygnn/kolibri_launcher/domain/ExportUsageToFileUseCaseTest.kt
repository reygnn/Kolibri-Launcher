package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.UsageExportRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
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
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `invoke - when repository returns false - returns Failure with IOException`() = runTest {
        // Arrange
        val uri = "content://valid"
        coEvery { repository.saveToFile(uri) } returns false

        // Act
        val result = useCase(uri)

        // Assert
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(result.exceptionOrNull()?.message == "Could not write file").isTrue()
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
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull() == exception).isTrue()
    }
}

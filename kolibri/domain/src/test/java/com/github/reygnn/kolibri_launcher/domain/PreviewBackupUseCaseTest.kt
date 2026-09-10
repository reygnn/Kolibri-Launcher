package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.model.BackupPreview
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.PreviewBackupUseCase
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
class PreviewBackupUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var repository: BackupRepository

    private lateinit var useCase: PreviewBackupUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = PreviewBackupUseCase(repository)
    }

    @Test
    fun `invoke - delegates to repository previewBackup`() = runTest {
        val uriString = "content://backup"
        val expectedPreview = BackupPreview(
            version = "1.0", timestamp = 123456L, favoriteCount = 5, orderCount = 5,
            hiddenCount = 0, customNamesCount = 0, hasSwipeLeft = false, hasSwipeRight = false,
            hasThemeSettings = true, hasWallpaper = false,
            hasTimeBasedEvents = false, hasQualityOfLife = false, hasPowerUserSettings = false
        )

        coEvery { repository.previewBackup(uriString) } returns expectedPreview

        val result = useCase(uriString)

        assertEquals(expectedPreview, result)
        coVerify { repository.previewBackup(uriString) }
    }
}

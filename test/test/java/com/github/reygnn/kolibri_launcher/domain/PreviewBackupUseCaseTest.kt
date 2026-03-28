package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.model.BackupPreview
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.PreviewBackupUseCase
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
class PreviewBackupUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var repository: BackupRepository

    private lateinit var useCase: PreviewBackupUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = PreviewBackupUseCase(repository)
    }

    @Test
    fun `invoke - delegates to repository previewBackup`() = runTest {
        // Arrange
        val uriString = "content://backup"
        val expectedPreview = BackupPreview(
            version = "1.0",
            timestamp = 123456L,
            favoriteCount = 5,
            orderCount = 5,
            hiddenCount = 0,
            customNamesCount = 0,
            hasSwipeLeft = false,
            hasSwipeRight = false,
            hasThemeSettings = true,
            hasWallpaper = false,
            hasGestureSettings = false,
            hasTimeBasedEvents = false,
            hasQualityOfLife = false,
            hasPowerUserSettings = false
        )

        whenever(repository.previewBackup(uriString)).thenReturn(expectedPreview)

        // Act
        val result = useCase(uriString)

        // Assert
        assertEquals(expectedPreview, result)
        verify(repository).previewBackup(uriString)
    }
}
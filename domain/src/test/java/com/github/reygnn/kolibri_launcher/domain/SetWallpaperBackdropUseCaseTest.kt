package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.model.WallpaperBackdrop
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.SetWallpaperBackdropUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
class SetWallpaperBackdropUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK(relaxUnitFun = true)
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var useCase: SetWallpaperBackdropUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = SetWallpaperBackdropUseCase(settingsRepository)
    }

    @Test
    fun `invoke - delegates BLACK to repository`() = runTest {
        useCase(WallpaperBackdrop.BLACK)
        coVerify { settingsRepository.setWallpaperBackdrop(WallpaperBackdrop.BLACK) }
    }

    @Test
    fun `invoke - delegates SYSTEM_WALLPAPER to repository`() = runTest {
        useCase(WallpaperBackdrop.SYSTEM_WALLPAPER)
        coVerify { settingsRepository.setWallpaperBackdrop(WallpaperBackdrop.SYSTEM_WALLPAPER) }
    }
}

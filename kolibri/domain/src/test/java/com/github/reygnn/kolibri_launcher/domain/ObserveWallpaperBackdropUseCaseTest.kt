package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.model.WallpaperBackdrop
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveWallpaperBackdropUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

@ExperimentalCoroutinesApi
class ObserveWallpaperBackdropUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var useCase: ObserveWallpaperBackdropUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = ObserveWallpaperBackdropUseCase(settingsRepository)
    }

    @Test
    fun `invoke - passes through the repository flow`() = runTest {
        every { settingsRepository.wallpaperBackdropFlow } returns
            flowOf(WallpaperBackdrop.SYSTEM_WALLPAPER, WallpaperBackdrop.BLACK)

        val emissions = useCase().toList()

        assertEquals(
            listOf(WallpaperBackdrop.SYSTEM_WALLPAPER, WallpaperBackdrop.BLACK),
            emissions,
        )
    }
}

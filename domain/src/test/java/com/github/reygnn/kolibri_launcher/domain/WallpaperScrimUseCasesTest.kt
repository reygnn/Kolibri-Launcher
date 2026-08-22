package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class WallpaperScrimUseCasesTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK(relaxUnitFun = true)
    private lateinit var repository: SettingsRepository

    private val scrimFlow = MutableStateFlow(0.0f)

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        every { repository.wallpaperScrimAlphaStateFlow } returns scrimFlow
    }

    @Test
    fun `GetWallpaperScrimAlphaUseCase - wires up repository flow`() {
        val useCase = GetWallpaperScrimAlphaUseCase(repository)
        assertSame(scrimFlow, useCase())
    }

    @Test
    fun `SetWallpaperScrimAlphaUseCase - delegates to repository`() = runTest {
        val useCase = SetWallpaperScrimAlphaUseCase(repository)
        useCase(0.3f)
        coVerify { repository.setWallpaperScrimAlpha(0.3f) }
    }
}

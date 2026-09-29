package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import com.github.reygnn.kolibri_launcher.domain.model.LuminanceClassification
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * JVM tests for [ResolveWallpaperSurfaceUseCase].
 *
 * Pins the mode → classification mapping. The classifier itself
 * (`ClassifyWallpaperUseCase`) is mocked here; its own logic has
 * dedicated tests in [ClassifyWallpaperUseCaseTest].
 *
 * AUTO mode now delegates to the classifier instead of defaulting
 * to DARK (the Commit-1 placeholder). LIGHT/DARK still bypass the
 * classifier entirely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResolveWallpaperSurfaceUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var classifyWallpaperUseCase: ClassifyWallpaperUseCase
    private lateinit var useCase: ResolveWallpaperSurfaceUseCase

    @Before
    fun setUp() {
        settingsRepository = FakeSettingsRepository()
        classifyWallpaperUseCase = mockk()
        useCase = ResolveWallpaperSurfaceUseCase(
            settingsRepository = settingsRepository,
            classifyWallpaperUseCase = classifyWallpaperUseCase,
        )
    }

    @Test
    fun `LIGHT mode resolves to LIGHT regardless of classifier`() =
        runTest(mainDispatcherRule.testDispatcher) {
            settingsRepository.setWallpaperSurfaceMode(WallpaperSurfaceMode.LIGHT)
            every { classifyWallpaperUseCase() } returns
                    flowOf(LuminanceClassification.DARK)
            assertThat(useCase().first()).isEqualTo(LuminanceClassification.LIGHT)
        }

    @Test
    fun `DARK mode resolves to DARK regardless of classifier`() =
        runTest(mainDispatcherRule.testDispatcher) {
            settingsRepository.setWallpaperSurfaceMode(WallpaperSurfaceMode.DARK)
            every { classifyWallpaperUseCase() } returns
                    flowOf(LuminanceClassification.LIGHT)
            assertThat(useCase().first()).isEqualTo(LuminanceClassification.DARK)
        }

    @Test
    fun `AUTO mode delegates to classifier — LIGHT path`() =
        runTest(mainDispatcherRule.testDispatcher) {
            settingsRepository.setWallpaperSurfaceMode(WallpaperSurfaceMode.AUTO)
            every { classifyWallpaperUseCase() } returns
                    flowOf(LuminanceClassification.LIGHT)
            assertThat(useCase().first()).isEqualTo(LuminanceClassification.LIGHT)
        }

    @Test
    fun `AUTO mode delegates to classifier — DARK path`() =
        runTest(mainDispatcherRule.testDispatcher) {
            settingsRepository.setWallpaperSurfaceMode(WallpaperSurfaceMode.AUTO)
            every { classifyWallpaperUseCase() } returns
                    flowOf(LuminanceClassification.DARK)
            assertThat(useCase().first()).isEqualTo(LuminanceClassification.DARK)
        }
}

package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppDrawerMode
import com.github.reygnn.kolibri_launcher.domain.model.AppDrawerSurfaceClassification
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * JVM tests for [ResolveAppDrawerSurfaceUseCase].
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
class ResolveAppDrawerSurfaceUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var classifyWallpaperUseCase: ClassifyWallpaperUseCase
    private lateinit var useCase: ResolveAppDrawerSurfaceUseCase

    @Before
    fun setUp() {
        settingsRepository = FakeSettingsRepository()
        classifyWallpaperUseCase = mockk()
        useCase = ResolveAppDrawerSurfaceUseCase(
            settingsRepository = settingsRepository,
            classifyWallpaperUseCase = classifyWallpaperUseCase,
        )
    }

    @Test
    fun `LIGHT mode resolves to LIGHT regardless of classifier`() =
        runTest(mainDispatcherRule.testDispatcher) {
            settingsRepository.setAppDrawerMode(AppDrawerMode.LIGHT)
            every { classifyWallpaperUseCase() } returns
                    flowOf(AppDrawerSurfaceClassification.DARK)
            assertEquals(AppDrawerSurfaceClassification.LIGHT, useCase().first())
        }

    @Test
    fun `DARK mode resolves to DARK regardless of classifier`() =
        runTest(mainDispatcherRule.testDispatcher) {
            settingsRepository.setAppDrawerMode(AppDrawerMode.DARK)
            every { classifyWallpaperUseCase() } returns
                    flowOf(AppDrawerSurfaceClassification.LIGHT)
            assertEquals(AppDrawerSurfaceClassification.DARK, useCase().first())
        }

    @Test
    fun `AUTO mode delegates to classifier — LIGHT path`() =
        runTest(mainDispatcherRule.testDispatcher) {
            settingsRepository.setAppDrawerMode(AppDrawerMode.AUTO)
            every { classifyWallpaperUseCase() } returns
                    flowOf(AppDrawerSurfaceClassification.LIGHT)
            assertEquals(AppDrawerSurfaceClassification.LIGHT, useCase().first())
        }

    @Test
    fun `AUTO mode delegates to classifier — DARK path`() =
        runTest(mainDispatcherRule.testDispatcher) {
            settingsRepository.setAppDrawerMode(AppDrawerMode.AUTO)
            every { classifyWallpaperUseCase() } returns
                    flowOf(AppDrawerSurfaceClassification.DARK)
            assertEquals(AppDrawerSurfaceClassification.DARK, useCase().first())
        }
}

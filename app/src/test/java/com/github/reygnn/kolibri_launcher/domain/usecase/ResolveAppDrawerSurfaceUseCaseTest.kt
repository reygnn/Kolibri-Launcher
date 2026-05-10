package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppDrawerMode
import com.github.reygnn.kolibri_launcher.domain.model.AppDrawerSurfaceClassification
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * JVM tests for [ResolveAppDrawerSurfaceUseCase].
 *
 * Pins the Commit-1 mapping (manual override path + AUTO defaulting to
 * DARK as a regression-safe stand-in for the not-yet-built classifier).
 * When Commit 2 wires the wallpaper-aware classifier, the AUTO test below
 * will need to be expanded — flag it then, don't quietly delete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResolveAppDrawerSurfaceUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var useCase: ResolveAppDrawerSurfaceUseCase

    @Before
    fun setUp() {
        settingsRepository = FakeSettingsRepository()
        useCase = ResolveAppDrawerSurfaceUseCase(settingsRepository)
    }

    @Test
    fun `LIGHT mode resolves to LIGHT classification`() = runTest(mainDispatcherRule.testDispatcher) {
        settingsRepository.setAppDrawerMode(AppDrawerMode.LIGHT)
        assertEquals(AppDrawerSurfaceClassification.LIGHT, useCase().first())
    }

    @Test
    fun `DARK mode resolves to DARK classification`() = runTest(mainDispatcherRule.testDispatcher) {
        settingsRepository.setAppDrawerMode(AppDrawerMode.DARK)
        assertEquals(AppDrawerSurfaceClassification.DARK, useCase().first())
    }

    @Test
    fun `AUTO mode resolves to DARK classification — Commit 1 default, Commit 2 wires classifier`() =
        runTest(mainDispatcherRule.testDispatcher) {
            settingsRepository.setAppDrawerMode(AppDrawerMode.AUTO)
            assertEquals(AppDrawerSurfaceClassification.DARK, useCase().first())
        }

    @Test
    fun `default repository state resolves to DARK — matches pre-feature behaviour`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // No setAppDrawerMode call — exercise the fresh-default path.
            assertEquals(AppDrawerSurfaceClassification.DARK, useCase().first())
        }
}

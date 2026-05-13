package com.github.reygnn.kolibri_launcher.domain

import android.graphics.Color
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.model.LuminanceClassification
import com.github.reygnn.kolibri_launcher.domain.usecase.ClassifyWallpaperUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveUiColorsUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveUiColorsUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var classifyWallpaperUseCase: ClassifyWallpaperUseCase
    private lateinit var classificationFlow: MutableStateFlow<LuminanceClassification>
    private lateinit var useCase: ObserveUiColorsUseCase

    @Before
    fun setup() {
        settingsRepository = FakeSettingsRepository()
        // Default to DARK so callers that don't care about classification
        // (user-override) get WHITE — same as the historical
        // supportsDarkText=false default.
        classificationFlow = MutableStateFlow(LuminanceClassification.DARK)
        classifyWallpaperUseCase = mockk()
        every { classifyWallpaperUseCase() } returns classificationFlow
        useCase = ObserveUiColorsUseCase(
            settingsRepository = settingsRepository,
            classifyWallpaperUseCase = classifyWallpaperUseCase,
        )
    }

    // =========================================================================
    // User-defined text colour
    // =========================================================================

    @Test
    fun `user-defined text color overrides the classifier`() = runTest {
        val customColor = Color.RED
        settingsRepository.setTextColor(customColor)
        // Even with classification=LIGHT (which would produce BLACK from
        // the classifier), the user override wins.
        classificationFlow.value = LuminanceClassification.LIGHT

        useCase().test {
            assertThat(awaitItem().textColor).isEqualTo(customColor)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Smart Contrast — the only mode, driven by ClassifyWallpaperUseCase
    // =========================================================================

    @Test
    fun `classification LIGHT returns black text`() = runTest {
        settingsRepository.setTextColor(0)
        classificationFlow.value = LuminanceClassification.LIGHT

        useCase().test {
            assertThat(awaitItem().textColor).isEqualTo(Color.BLACK)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `classification DARK returns white text`() = runTest {
        settingsRepository.setTextColor(0)
        classificationFlow.value = LuminanceClassification.DARK

        useCase().test {
            assertThat(awaitItem().textColor).isEqualTo(Color.WHITE)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Shadow logic
    // =========================================================================

    @Test
    fun `shadow disabled returns transparent shadow`() = runTest {
        settingsRepository.setTextShadowEnabled(false)

        useCase().test {
            assertThat(awaitItem().shadowColor).isEqualTo(Color.TRANSPARENT)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `shadow enabled calls calculateTonalShadowColor`() = runTest {
        settingsRepository.setTextColor(Color.WHITE)
        settingsRepository.setTextShadowEnabled(true)

        useCase().test {
            awaitItem() // Just verify no crash
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Chip Background Color
    // =========================================================================

    @Test
    fun `chip background color is passed through`() = runTest {
        val chipColor = Color.MAGENTA
        settingsRepository.setChipBackgroundColor(chipColor)

        useCase().test {
            assertThat(awaitItem().chipBackgroundColor).isEqualTo(chipColor)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Reactivity
    // =========================================================================

    @Test
    fun `emits new state when settings change`() = runTest {
        settingsRepository.setTextColor(Color.RED)

        useCase().test {
            assertThat(awaitItem().textColor).isEqualTo(Color.RED)

            settingsRepository.setTextColor(Color.BLUE)

            assertThat(awaitItem().textColor).isEqualTo(Color.BLUE)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits new state when classification changes`() = runTest {
        settingsRepository.setTextColor(0)
        classificationFlow.value = LuminanceClassification.DARK

        useCase().test {
            assertThat(awaitItem().textColor).isEqualTo(Color.WHITE)

            classificationFlow.value = LuminanceClassification.LIGHT
            assertThat(awaitItem().textColor).isEqualTo(Color.BLACK)

            cancelAndIgnoreRemainingEvents()
        }
    }
}

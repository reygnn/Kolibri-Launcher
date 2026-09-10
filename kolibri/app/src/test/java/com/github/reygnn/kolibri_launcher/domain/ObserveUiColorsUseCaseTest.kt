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

    // The tonal shadow must INVERT against text luminance for contrast: dark text
    // gets a near-opaque white shadow, light text gets a black shadow. The old
    // test only asserted "no crash" — a swapped inversion or moved threshold would
    // have shipped a dark (invisible) shadow behind dark text with every test
    // green. Assertions use literal packed ARGB ints so they depend on neither
    // ColorMath.argb's packing nor the luminance formula's mid-range precision
    // (BLACK luminance is definitionally 0, WHITE is 1).

    @Test
    fun `shadow for very dark text is a near-opaque white shadow`() = runTest {
        settingsRepository.setTextColor(Color.BLACK) // luminance 0 -> band 1
        settingsRepository.setTextShadowEnabled(true)

        useCase().test {
            assertThat(awaitItem().shadowColor).isEqualTo(0xCCFFFFFF.toInt()) // argb(204,255,255,255)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `shadow for very light text is a black shadow (inversion)`() = runTest {
        settingsRepository.setTextColor(Color.WHITE) // luminance 1 -> band 4
        settingsRepository.setTextShadowEnabled(true)

        useCase().test {
            assertThat(awaitItem().shadowColor).isEqualTo(0x99000000.toInt()) // argb(153,0,0,0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // The two BLACK/WHITE tests above only exercise band 1 (<0.1) and the else
    // band (>=0.9) — luminance 0 and 1 by definition. The interpolated middle
    // bands run for any GRAY user text color and were unpinned (RC edge-case
    // audit B1). The expected packed ARGB below was computed from the real
    // ColorMath.calculateLuminance (WCAG sRGB) — JVM double math is deterministic,
    // so these are exact, and they also pin the white/black inversion per band.

    @Test
    fun `shadow for mid-dark gray text is an interpolated white shadow (band 2)`() = runTest {
        settingsRepository.setTextColor(0xFF808080.toInt()) // luminance ~0.216 -> band 2 (<0.5)
        settingsRepository.setTextShadowEnabled(true)

        useCase().test {
            // Interpolated alpha between band 1 (0.75) and band 3-onset (0.4), white channel.
            assertThat(awaitItem().shadowColor).isEqualTo(0xA5FFFFFF.toInt()) // argb(165,255,255,255)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `shadow for mid-light gray text is an interpolated black shadow (band 3)`() = runTest {
        settingsRepository.setTextColor(0xFFC8C8C8.toInt()) // luminance ~0.578 -> band 3 (<0.9)
        settingsRepository.setTextShadowEnabled(true)

        useCase().test {
            // Interpolated alpha between 0.3 and 0.6, black channel (inversion vs band 2).
            assertThat(awaitItem().shadowColor).isEqualTo(0x5B000000) // argb(91,0,0,0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `auto surface shadow inverts against the classifier-derived text color`() = runTest {
        // The real auto path (no user override): LIGHT surface -> black text ->
        // white shadow; DARK surface -> white text -> black shadow.
        settingsRepository.setTextColor(0)
        settingsRepository.setTextShadowEnabled(true)

        classificationFlow.value = LuminanceClassification.LIGHT
        useCase().test {
            assertThat(awaitItem().shadowColor).isEqualTo(0xCCFFFFFF.toInt())

            classificationFlow.value = LuminanceClassification.DARK
            assertThat(awaitItem().shadowColor).isEqualTo(0x99000000.toInt())
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

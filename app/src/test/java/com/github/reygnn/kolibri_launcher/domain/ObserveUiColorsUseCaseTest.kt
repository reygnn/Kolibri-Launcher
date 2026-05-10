package com.github.reygnn.kolibri_launcher.domain

import android.graphics.Color
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.model.AppDrawerSurfaceClassification
import com.github.reygnn.kolibri_launcher.domain.model.DomainWallpaperColors
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
    private lateinit var classificationFlow: MutableStateFlow<AppDrawerSurfaceClassification>
    private lateinit var useCase: ObserveUiColorsUseCase
    private lateinit var wallpaperColorsFlow: MutableStateFlow<DomainWallpaperColors?>

    @Before
    fun setup() {
        settingsRepository = FakeSettingsRepository()
        // Default to DARK so callers that don't care about classification
        // (user-override / adaptive_colors / default mode) get WHITE in
        // smart_contrast — same as the old supportsDarkText=false default.
        classificationFlow = MutableStateFlow(AppDrawerSurfaceClassification.DARK)
        classifyWallpaperUseCase = mockk()
        every { classifyWallpaperUseCase() } returns classificationFlow
        useCase = ObserveUiColorsUseCase(
            settingsRepository = settingsRepository,
            classifyWallpaperUseCase = classifyWallpaperUseCase,
        )
        wallpaperColorsFlow = MutableStateFlow(null)
    }

    // =========================================================================
    // User-defined text colour
    // =========================================================================

    @Test
    fun `user-defined text color overrides all modes`() = runTest {
        val customColor = Color.RED
        settingsRepository.setTextColor(customColor)
        settingsRepository.setReadabilityMode("smart_contrast")
        // Even with classification=LIGHT (which would produce BLACK in
        // smart_contrast), the user override wins.
        classificationFlow.value = AppDrawerSurfaceClassification.LIGHT

        useCase(wallpaperColorsFlow).test {
            assertThat(awaitItem().textColor).isEqualTo(customColor)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Smart Contrast Mode — driven by ClassifyWallpaperUseCase
    // =========================================================================

    @Test
    fun `smart contrast with classification LIGHT returns black text`() = runTest {
        settingsRepository.setTextColor(0)
        settingsRepository.setReadabilityMode("smart_contrast")
        classificationFlow.value = AppDrawerSurfaceClassification.LIGHT

        useCase(wallpaperColorsFlow).test {
            assertThat(awaitItem().textColor).isEqualTo(Color.BLACK)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `smart contrast with classification DARK returns white text`() = runTest {
        settingsRepository.setTextColor(0)
        settingsRepository.setReadabilityMode("smart_contrast")
        classificationFlow.value = AppDrawerSurfaceClassification.DARK

        useCase(wallpaperColorsFlow).test {
            assertThat(awaitItem().textColor).isEqualTo(Color.WHITE)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `smart contrast ignores wallpaperColors supportsDarkText — classifier drives it now`() =
        runTest {
            // Pre-Commit-3 contract: smart_contrast read supportsDarkText
            // directly. Now the classifier owns the binary decision.
            // Even with supportsDarkText=true on the wallpaper-colours
            // signal, classification=DARK pins text to WHITE.
            settingsRepository.setTextColor(0)
            settingsRepository.setReadabilityMode("smart_contrast")
            wallpaperColorsFlow.value = DomainWallpaperColors(
                supportsDarkText = true,
                secondaryColorArgb = null,
            )
            classificationFlow.value = AppDrawerSurfaceClassification.DARK

            useCase(wallpaperColorsFlow).test {
                assertThat(awaitItem().textColor).isEqualTo(Color.WHITE)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // =========================================================================
    // Adaptive Colors Mode — still uses system-wallpaper secondaryColorArgb
    // =========================================================================

    @Test
    fun `adaptive colors mode uses wallpaper secondary color`() = runTest {
        settingsRepository.setTextColor(0)
        settingsRepository.setReadabilityMode("adaptive_colors")

        wallpaperColorsFlow.value = DomainWallpaperColors(
            supportsDarkText = false,
            secondaryColorArgb = Color.CYAN,
        )

        useCase(wallpaperColorsFlow).test {
            assertThat(awaitItem().textColor).isEqualTo(Color.CYAN)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `adaptive colors mode without wallpaper returns white`() = runTest {
        settingsRepository.setTextColor(0)
        settingsRepository.setReadabilityMode("adaptive_colors")
        wallpaperColorsFlow.value = null

        useCase(wallpaperColorsFlow).test {
            assertThat(awaitItem().textColor).isEqualTo(Color.WHITE)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `adaptive colors mode is independent of classification`() = runTest {
        // Classifier output doesn't reach the adaptive_colors branch.
        // Pin that by setting classification=LIGHT (which would produce
        // BLACK in smart_contrast) and asserting the secondary colour
        // still wins.
        settingsRepository.setTextColor(0)
        settingsRepository.setReadabilityMode("adaptive_colors")
        wallpaperColorsFlow.value = DomainWallpaperColors(
            supportsDarkText = false,
            secondaryColorArgb = Color.CYAN,
        )
        classificationFlow.value = AppDrawerSurfaceClassification.LIGHT

        useCase(wallpaperColorsFlow).test {
            assertThat(awaitItem().textColor).isEqualTo(Color.CYAN)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Default Mode
    // =========================================================================

    @Test
    fun `default mode returns white text`() = runTest {
        settingsRepository.setTextColor(0)
        settingsRepository.setReadabilityMode("default")

        useCase(wallpaperColorsFlow).test {
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

        useCase(wallpaperColorsFlow).test {
            assertThat(awaitItem().shadowColor).isEqualTo(Color.TRANSPARENT)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `shadow enabled calls calculateTonalShadowColor`() = runTest {
        settingsRepository.setTextColor(Color.WHITE)
        settingsRepository.setTextShadowEnabled(true)

        useCase(wallpaperColorsFlow).test {
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

        useCase(wallpaperColorsFlow).test {
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

        useCase(wallpaperColorsFlow).test {
            assertThat(awaitItem().textColor).isEqualTo(Color.RED)

            settingsRepository.setTextColor(Color.BLUE)

            assertThat(awaitItem().textColor).isEqualTo(Color.BLUE)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits new state when classification changes — smart_contrast`() = runTest {
        // Pre-Commit-3 reactivity test was "wallpaperColorsFlow change
        // re-emits". Updated to the new contract: smart_contrast reacts
        // to classifier output, not to wallpaper-colours hints.
        settingsRepository.setTextColor(0)
        settingsRepository.setReadabilityMode("smart_contrast")
        classificationFlow.value = AppDrawerSurfaceClassification.DARK

        useCase(wallpaperColorsFlow).test {
            assertThat(awaitItem().textColor).isEqualTo(Color.WHITE)

            classificationFlow.value = AppDrawerSurfaceClassification.LIGHT
            assertThat(awaitItem().textColor).isEqualTo(Color.BLACK)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits new state when wallpaper colours change — adaptive_colors`() = runTest {
        // Reactivity to wallpaper-colour changes is preserved for
        // adaptive_colors mode (which still consumes that signal
        // directly).
        settingsRepository.setTextColor(0)
        settingsRepository.setReadabilityMode("adaptive_colors")
        wallpaperColorsFlow.value = DomainWallpaperColors(
            supportsDarkText = false,
            secondaryColorArgb = Color.CYAN,
        )

        useCase(wallpaperColorsFlow).test {
            assertThat(awaitItem().textColor).isEqualTo(Color.CYAN)

            wallpaperColorsFlow.value = DomainWallpaperColors(
                supportsDarkText = false,
                secondaryColorArgb = Color.MAGENTA,
            )
            assertThat(awaitItem().textColor).isEqualTo(Color.MAGENTA)

            cancelAndIgnoreRemainingEvents()
        }
    }
}

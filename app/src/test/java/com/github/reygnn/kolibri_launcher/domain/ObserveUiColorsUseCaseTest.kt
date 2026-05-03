package com.github.reygnn.kolibri_launcher.domain

import android.graphics.Color
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.model.DomainWallpaperColors
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveUiColorsUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
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
    private lateinit var useCase: ObserveUiColorsUseCase
    private lateinit var wallpaperColorsFlow: MutableStateFlow<DomainWallpaperColors?>

    @Before
    fun setup() {
        settingsRepository = FakeSettingsRepository()
        useCase = ObserveUiColorsUseCase(settingsRepository)
        wallpaperColorsFlow = MutableStateFlow(null)
    }

    // =========================================================================
    // Benutzerdefinierte Textfarbe
    // =========================================================================

    @Test
    fun `user-defined text color overrides all modes`() = runTest {
        val customColor = Color.RED
        settingsRepository.setTextColor(customColor)
        settingsRepository.setReadabilityMode("smart_contrast")

        useCase(wallpaperColorsFlow).test {
            assertThat(awaitItem().textColor).isEqualTo(customColor)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Smart Contrast Mode
    // =========================================================================

    @Test
    fun `smart contrast mode with light wallpaper returns black text`() = runTest {
        settingsRepository.setTextColor(0)
        settingsRepository.setReadabilityMode("smart_contrast")

        wallpaperColorsFlow.value = DomainWallpaperColors(supportsDarkText = true, secondaryColorArgb = null)

        useCase(wallpaperColorsFlow).test {
            assertThat(awaitItem().textColor).isEqualTo(Color.BLACK)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `smart contrast mode with dark wallpaper returns white text`() = runTest {
        settingsRepository.setTextColor(0)
        settingsRepository.setReadabilityMode("smart_contrast")

        wallpaperColorsFlow.value = DomainWallpaperColors(supportsDarkText = false, secondaryColorArgb = null)

        useCase(wallpaperColorsFlow).test {
            assertThat(awaitItem().textColor).isEqualTo(Color.WHITE)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `smart contrast mode without wallpaper returns white text`() = runTest {
        settingsRepository.setTextColor(0)
        settingsRepository.setReadabilityMode("smart_contrast")
        wallpaperColorsFlow.value = null

        useCase(wallpaperColorsFlow).test {
            assertThat(awaitItem().textColor).isEqualTo(Color.WHITE)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Adaptive Colors Mode
    // =========================================================================

    @Test
    fun `adaptive colors mode uses wallpaper secondary color`() = runTest {
        settingsRepository.setTextColor(0)
        settingsRepository.setReadabilityMode("adaptive_colors")

        wallpaperColorsFlow.value = DomainWallpaperColors(
            supportsDarkText = false,
            secondaryColorArgb = Color.CYAN
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
    // Schatten-Logik
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
    // Reaktivität
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
    fun `emits new state when wallpaper colors change`() = runTest {
        settingsRepository.setTextColor(0)
        settingsRepository.setReadabilityMode("smart_contrast")

        val darkWallpaper = DomainWallpaperColors(supportsDarkText = false, secondaryColorArgb = null)
        val lightWallpaper = DomainWallpaperColors(supportsDarkText = true, secondaryColorArgb = null)

        useCase(wallpaperColorsFlow).test {
            assertThat(awaitItem().textColor).isEqualTo(Color.WHITE)

            wallpaperColorsFlow.value = darkWallpaper
            assertThat(awaitItem().textColor).isEqualTo(Color.WHITE)

            wallpaperColorsFlow.value = lightWallpaper
            assertThat(awaitItem().textColor).isEqualTo(Color.BLACK)

            cancelAndIgnoreRemainingEvents()
        }
    }
}

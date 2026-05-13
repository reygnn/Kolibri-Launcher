package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.SystemWallpaperColorsSignal
import com.github.reygnn.kolibri_launcher.domain.model.LuminanceClassification
import com.github.reygnn.kolibri_launcher.domain.model.DomainWallpaperColors
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperBlendMode
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperBitmapLuminance
import com.github.reygnn.kolibri_launcher.fakes.FakeWallpaperRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * JVM tests for [ClassifyWallpaperUseCase] — covers the priority
 * order (Kolibri-internal beats system, system beats fallback) and
 * the alpha/blend gates on `layers[0]` for multi-layer states.
 *
 * `WallpaperBitmapLuminance` is mocked because its impl depends on
 * `android.graphics.Bitmap`; impl-level coverage lives in
 * `WallpaperBitmapLuminanceImplTest` (Robolectric).
 *
 * The classifier deliberately avoids hysteresis — see KDoc on the
 * use case. No "consistent N emissions before flipping" tests
 * because that's not the contract: the output reflects the latest
 * upstream signal directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClassifyWallpaperUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fakeWallpaperRepository: FakeWallpaperRepository
    private lateinit var observeWallpaperStateUseCase: ObserveWallpaperStateUseCase
    private lateinit var systemColorsSignal: SystemWallpaperColorsSignal
    private lateinit var bitmapLuminance: WallpaperBitmapLuminance
    private lateinit var useCase: ClassifyWallpaperUseCase

    @Before
    fun setUp() {
        fakeWallpaperRepository = FakeWallpaperRepository()
        observeWallpaperStateUseCase = ObserveWallpaperStateUseCase(fakeWallpaperRepository)
        systemColorsSignal = SystemWallpaperColorsSignal()
        bitmapLuminance = mockk()
        useCase = ClassifyWallpaperUseCase(
            observeWallpaperStateUseCase = observeWallpaperStateUseCase,
            systemWallpaperColorsSignal = systemColorsSignal,
            wallpaperBitmapLuminance = bitmapLuminance,
        )
    }

    // ============================================================
    // Priority 1: Kolibri-internal wallpaper, single-layer
    // ============================================================

    @Test
    fun `single-layer with bright image classifies LIGHT`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeWallpaperRepository.currentState =
                WallpaperState(imageUri = "file:///wallpapers/bright.png")
            coEvery { bitmapLuminance.compute("file:///wallpapers/bright.png") } returns 0.92f
            assertEquals(LuminanceClassification.LIGHT, useCase().first())
        }

    @Test
    fun `single-layer with dark image classifies DARK`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeWallpaperRepository.currentState =
                WallpaperState(imageUri = "file:///wallpapers/dark.png")
            coEvery { bitmapLuminance.compute("file:///wallpapers/dark.png") } returns 0.05f
            assertEquals(LuminanceClassification.DARK, useCase().first())
        }

    @Test
    fun `single-layer at exactly threshold classifies DARK — strict greater-than`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeWallpaperRepository.currentState =
                WallpaperState(imageUri = "file:///wallpapers/mid.png")
            coEvery { bitmapLuminance.compute("file:///wallpapers/mid.png") } returns 0.5f
            assertEquals(LuminanceClassification.DARK, useCase().first())
        }

    @Test
    fun `single-layer with bitmap-load failure falls through to system`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeWallpaperRepository.currentState =
                WallpaperState(imageUri = "file:///wallpapers/missing.png")
            coEvery { bitmapLuminance.compute("file:///wallpapers/missing.png") } returns null
            // System says supports-dark-text → LIGHT
            systemColorsSignal.emit(
                DomainWallpaperColors(supportsDarkText = true, secondaryColorArgb = null),
            )
            assertEquals(LuminanceClassification.LIGHT, useCase().first())
        }

    // ============================================================
    // Priority 1: Kolibri-internal wallpaper, multi-layer
    // ============================================================

    @Test
    fun `multi-layer opaque layer 0 classifies that layer`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeWallpaperRepository.currentState = WallpaperState.multiLayer(
                listOf(
                    WallpaperLayerState(imageUri = "file:///wallpapers/sky.png", alpha = 1.0f),
                    WallpaperLayerState(imageUri = "file:///wallpapers/stars.png", alpha = 0.5f),
                ),
            )
            coEvery { bitmapLuminance.compute("file:///wallpapers/sky.png") } returns 0.8f
            assertEquals(LuminanceClassification.LIGHT, useCase().first())
        }

    @Test
    fun `multi-layer transparent layer 0 falls through to system — alpha-gate`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // The user's reported scenario: small grey motif on
            // transparent background as layer 0 over white system
            // wallpaper. The alpha-gate kicks the classifier into
            // the system-signal branch, which sees supportsDarkText
            // (set by the OS for white wallpapers) and resolves to
            // LIGHT — the AppDrawer correctly follows what the user
            // actually sees.
            fakeWallpaperRepository.currentState = WallpaperState.multiLayer(
                listOf(
                    WallpaperLayerState(imageUri = "file:///wallpapers/grey-motif.png", alpha = 0.3f),
                ),
            )
            systemColorsSignal.emit(
                DomainWallpaperColors(supportsDarkText = true, secondaryColorArgb = null),
            )
            assertEquals(LuminanceClassification.LIGHT, useCase().first())
        }

    @Test
    fun `multi-layer layer 0 just below alpha threshold falls through`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeWallpaperRepository.currentState = WallpaperState.multiLayer(
                listOf(
                    WallpaperLayerState(imageUri = "file:///x.png", alpha = 0.79f),
                ),
            )
            systemColorsSignal.emit(
                DomainWallpaperColors(supportsDarkText = false, secondaryColorArgb = null),
            )
            assertEquals(LuminanceClassification.DARK, useCase().first())
        }

    @Test
    fun `multi-layer layer 0 at alpha threshold qualifies — ge comparison`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeWallpaperRepository.currentState = WallpaperState.multiLayer(
                listOf(
                    WallpaperLayerState(imageUri = "file:///x.png", alpha = 0.8f),
                ),
            )
            coEvery { bitmapLuminance.compute("file:///x.png") } returns 0.95f
            assertEquals(LuminanceClassification.LIGHT, useCase().first())
        }

    @Test
    fun `multi-layer layer 0 with non-Normal blend mode falls through`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Documented heuristic limit: opaque layer with
            // MULTIPLY blend doesn't fully cover what's beneath, so
            // we punt to the system signal. ACCEPTED_LIMITATIONS §2.
            fakeWallpaperRepository.currentState = WallpaperState.multiLayer(
                listOf(
                    WallpaperLayerState(
                        imageUri = "file:///x.png",
                        alpha = 1.0f,
                        blendModeName = WallpaperBlendMode.MULTIPLY.name,
                    ),
                ),
            )
            systemColorsSignal.emit(
                DomainWallpaperColors(supportsDarkText = true, secondaryColorArgb = null),
            )
            assertEquals(LuminanceClassification.LIGHT, useCase().first())
        }

    @Test
    fun `multi-layer empty layer 0 image URI falls through`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeWallpaperRepository.currentState = WallpaperState.multiLayer(
                listOf(
                    WallpaperLayerState(imageUri = null, alpha = 1.0f),
                ),
            )
            systemColorsSignal.emit(
                DomainWallpaperColors(supportsDarkText = false, secondaryColorArgb = null),
            )
            assertEquals(LuminanceClassification.DARK, useCase().first())
        }

    // ============================================================
    // Priority 2: System-wallpaper colorHints
    // ============================================================

    @Test
    fun `no Kolibri wallpaper, system supportsDarkText TRUE resolves to LIGHT`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeWallpaperRepository.currentState = WallpaperState.NONE
            systemColorsSignal.emit(
                DomainWallpaperColors(supportsDarkText = true, secondaryColorArgb = null),
            )
            assertEquals(LuminanceClassification.LIGHT, useCase().first())
        }

    @Test
    fun `no Kolibri wallpaper, system supportsDarkText FALSE resolves to DARK`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeWallpaperRepository.currentState = WallpaperState.NONE
            systemColorsSignal.emit(
                DomainWallpaperColors(supportsDarkText = false, secondaryColorArgb = null),
            )
            assertEquals(LuminanceClassification.DARK, useCase().first())
        }

    // ============================================================
    // Priority 3: Final fallback
    // ============================================================

    @Test
    fun `no Kolibri wallpaper and no system colours falls back to DARK`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeWallpaperRepository.currentState = WallpaperState.NONE
            // systemColorsSignal stays at its initial null value
            assertEquals(LuminanceClassification.DARK, useCase().first())
        }
}

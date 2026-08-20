package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.SystemWallpaperColorsSignal
import com.github.reygnn.kolibri_launcher.core.CompositeLuminanceSignal
import com.github.reygnn.kolibri_launcher.domain.model.LuminanceClassification
import com.github.reygnn.kolibri_launcher.domain.model.DomainWallpaperColors
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperBitmapLuminance
import com.github.reygnn.kolibri_launcher.fakes.FakeWallpaperRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * JVM tests for [ClassifyWallpaperUseCase] — covers the priority
 * order (Kolibri-internal beats system, system beats fallback) and
 * the `layers[0]` image fallback for multi-layer states.
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
    private lateinit var compositeLuminanceSignal: CompositeLuminanceSignal
    private lateinit var useCase: ClassifyWallpaperUseCase

    @Before
    fun setUp() {
        fakeWallpaperRepository = FakeWallpaperRepository()
        observeWallpaperStateUseCase = ObserveWallpaperStateUseCase(fakeWallpaperRepository)
        systemColorsSignal = SystemWallpaperColorsSignal()
        bitmapLuminance = mockk()
        compositeLuminanceSignal = CompositeLuminanceSignal()
        useCase = ClassifyWallpaperUseCase(
            observeWallpaperStateUseCase = observeWallpaperStateUseCase,
            systemWallpaperColorsSignal = systemColorsSignal,
            wallpaperBitmapLuminance = bitmapLuminance,
            compositeLuminanceSignal = compositeLuminanceSignal,
        )
    }

    // ============================================================
    // Priority 1: Kolibri-internal wallpaper, single-layer
    // ============================================================

    @Test
    fun `single-layer with bright image classifies LIGHT`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeWallpaperRepository.currentState =
                WallpaperState.single("file:///wallpapers/bright.png")
            coEvery { bitmapLuminance.compute("file:///wallpapers/bright.png") } returns 0.92f
            assertEquals(LuminanceClassification.LIGHT, useCase().first())
        }

    @Test
    fun `single-layer with dark image classifies DARK`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeWallpaperRepository.currentState =
                WallpaperState.single("file:///wallpapers/dark.png")
            coEvery { bitmapLuminance.compute("file:///wallpapers/dark.png") } returns 0.05f
            assertEquals(LuminanceClassification.DARK, useCase().first())
        }

    @Test
    fun `single-layer at exactly threshold classifies DARK — strict greater-than`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeWallpaperRepository.currentState =
                WallpaperState.single("file:///wallpapers/mid.png")
            coEvery { bitmapLuminance.compute("file:///wallpapers/mid.png") } returns 0.5f
            assertEquals(LuminanceClassification.DARK, useCase().first())
        }

    @Test
    fun `single-layer with bitmap-load failure falls through to system`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeWallpaperRepository.currentState =
                WallpaperState.single("file:///wallpapers/missing.png")
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
                    WallpaperLayerState(imageUri = "file:///wallpapers/sky.png"),
                    WallpaperLayerState(imageUri = "file:///wallpapers/stars.png"),
                ),
            )
            coEvery { bitmapLuminance.compute("file:///wallpapers/sky.png") } returns 0.8f
            assertEquals(LuminanceClassification.LIGHT, useCase().first())
        }

    // ============================================================
    // v4.3: composite luminance from CompositeLuminanceSignal
    // ============================================================

    @Test
    fun `multi-layer classifies the COMPOSITE luminance when the signal has one (v4_3)`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // A dark bottom layer that WOULD classify DARK via the fallback heuristic...
            fakeWallpaperRepository.currentState = WallpaperState.multiLayer(
                listOf(
                    WallpaperLayerState(imageUri = "file:///wallpapers/dark-bottom.png"),
                    WallpaperLayerState(imageUri = "file:///wallpapers/bright-top.png"),
                ),
            )
            // ...but the warm resolved a BRIGHT composite → LIGHT. The composite luminance wins,
            // and the bottom layer is never sampled (no decode).
            compositeLuminanceSignal.emit(0.9f)
            assertEquals(LuminanceClassification.LIGHT, useCase().first())
            coVerify(exactly = 0) { bitmapLuminance.compute(any()) }
        }

    @Test
    fun `multi-layer falls back to layers 0 until the composite luminance arrives (cold-start 1a)`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // No composite luminance yet (signal null) → the bottom-layer heuristic runs.
            fakeWallpaperRepository.currentState = WallpaperState.multiLayer(
                listOf(WallpaperLayerState(imageUri = "file:///wallpapers/bottom.png")),
            )
            coEvery { bitmapLuminance.compute("file:///wallpapers/bottom.png") } returns 0.1f
            assertEquals(LuminanceClassification.DARK, useCase().first())
        }

    @Test
    fun `multi-layer empty layer 0 image URI falls through`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeWallpaperRepository.currentState = WallpaperState.multiLayer(
                listOf(
                    WallpaperLayerState(imageUri = null),
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

    // ============================================================
    // AUDIT-19 F4: luminance depends only on the URI, so a
    // transform-only change (same URI, different scale/translate)
    // must NOT re-run the bitmap decode.
    // ============================================================

    @Test
    fun `transform-only change does not re-run the bitmap decode`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val uri = "file:///wallpapers/x.png"
            coEvery { bitmapLuminance.compute(uri) } returns 0.9f
            fakeWallpaperRepository.currentState = WallpaperState.single(uri, scale = 1.0f)

            val seen = mutableListOf<LuminanceClassification>()
            val job = launch { useCase().collect { seen.add(it) } }
            testScheduler.runCurrent()

            // Pan/zoom save: same URI, only the transform fields changed.
            fakeWallpaperRepository.currentState = WallpaperState.single(uri, scale = 2.0f)
            testScheduler.runCurrent()

            job.cancel()

            // The projected-URI dedup collapses the two states → one decode,
            // one downstream emission.
            coVerify(exactly = 1) { bitmapLuminance.compute(uri) }
            assertEquals(listOf(LuminanceClassification.LIGHT), seen)
        }
}

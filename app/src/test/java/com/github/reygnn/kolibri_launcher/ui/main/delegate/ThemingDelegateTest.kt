package com.github.reygnn.kolibri_launcher.ui.main.delegate

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.LuminanceClassification
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.kolibri_launcher.domain.usecase.GetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetWallpaperScrimAlphaUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveUiColorsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ResolveWallpaperSurfaceUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetChipBackgroundColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetWallpaperScrimAlphaUseCase
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThemingDelegateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private val sentEvents = mutableListOf<UiEvent>()

    private lateinit var observeUiColorsUseCase: ObserveUiColorsUseCase
    private lateinit var setTextColorUseCase: SetTextColorUseCase
    private lateinit var setTextShadowEnabledUseCase: SetTextShadowEnabledUseCase
    private lateinit var setChipBackgroundColorUseCase: SetChipBackgroundColorUseCase
    private lateinit var getTextShadowEnabledUseCase: GetTextShadowEnabledUseCase
    private lateinit var getWallpaperScrimAlphaUseCase: GetWallpaperScrimAlphaUseCase
    private lateinit var setWallpaperScrimAlphaUseCase: SetWallpaperScrimAlphaUseCase
    private lateinit var resolveAppDrawerSurfaceUseCase: ResolveWallpaperSurfaceUseCase

    @Before
    fun setUp() {
        sentEvents.clear()

        observeUiColorsUseCase = mockk(relaxed = true)
        setTextColorUseCase = mockk(relaxed = true)
        setTextShadowEnabledUseCase = mockk(relaxed = true)
        setChipBackgroundColorUseCase = mockk(relaxed = true)
        getTextShadowEnabledUseCase = mockk(relaxed = true)
        getWallpaperScrimAlphaUseCase = mockk(relaxed = true)
        setWallpaperScrimAlphaUseCase = mockk(relaxed = true)
        every { getWallpaperScrimAlphaUseCase() } returns
                flowOf(AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA)
        resolveAppDrawerSurfaceUseCase = mockk(relaxed = true)
        every { resolveAppDrawerSurfaceUseCase.invoke() } returns
                flowOf(LuminanceClassification.DARK)
    }

    private fun createDelegateScope() = DelegateScope(
        coroutineScope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob()),
        mainDispatcher = mainDispatcherRule.testDispatcher,
        eventSender = { event -> sentEvents.add(event) }
    )

    private fun createDelegate() = ThemingDelegate(
        observeUiColorsUseCase = observeUiColorsUseCase,
        setTextColorUseCase = setTextColorUseCase,
        setTextShadowEnabledUseCase = setTextShadowEnabledUseCase,
        setChipBackgroundColorUseCase = setChipBackgroundColorUseCase,
        getTextShadowEnabledUseCase = getTextShadowEnabledUseCase,
        getWallpaperScrimAlphaUseCase = getWallpaperScrimAlphaUseCase,
        setWallpaperScrimAlphaUseCase = setWallpaperScrimAlphaUseCase,
        resolveAppDrawerSurfaceUseCase = resolveAppDrawerSurfaceUseCase,
        appDrawerSurfaceLightColor = APP_DRAWER_LIGHT_TEST_COLOR,
        appDrawerSurfaceDarkColor = APP_DRAWER_DARK_TEST_COLOR,
        scope = createDelegateScope()
    )

    companion object {
        private const val APP_DRAWER_LIGHT_TEST_COLOR: Int = 0xF0FFFFFF.toInt()
        private const val APP_DRAWER_DARK_TEST_COLOR: Int = 0xDD000000.toInt()
    }

    // ===========================================
    // INITIAL STATE
    // ===========================================

    @Test
    fun `initial uiColorsState is default`() {
        val delegate = createDelegate()
        assertEquals(UiColorsState(), delegate.uiColorsState.value)
    }

    // ===========================================
    // START - OBSERVE COLORS
    // ===========================================

    @Test
    fun `start observes ui colors and updates state`() = runTest {
        val expectedColors: UiColorsState = mockk()
        every { observeUiColorsUseCase() } returns flowOf(expectedColors)

        val delegate = createDelegate()

        delegate.start()
        advanceUntilIdle()

        assertEquals(expectedColors, delegate.uiColorsState.value)
    }

    @Test
    fun `start handles multiple color emissions`() = runTest {
        val colors1: UiColorsState = mockk()
        val colors2: UiColorsState = mockk()
        val colorsFlow = MutableStateFlow(colors1)

        every { observeUiColorsUseCase() } returns colorsFlow

        val delegate = createDelegate()

        delegate.start()
        advanceUntilIdle()

        assertEquals(colors1, delegate.uiColorsState.value)

        colorsFlow.value = colors2
        advanceUntilIdle()

        assertEquals(colors2, delegate.uiColorsState.value)
    }

    // ===========================================
    // SET TEXT COLOR
    // ===========================================

    @Test
    fun `onSetTextColor calls useCase with correct color`() = runTest {
        val color = 0xFF00FF00.toInt()
        val delegate = createDelegate()

        delegate.onSetTextColor(color)
        advanceUntilIdle()

        coVerify { setTextColorUseCase.invoke(color) }
    }

    @Test
    fun `onSetTextColor shows error toast on failure`() = runTest {
        coEvery { setTextColorUseCase(any()) } throws RuntimeException("Fail")

        val delegate = createDelegate()

        delegate.onSetTextColor(0xFF0000)
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.ShowToast })
    }

    @Test
    fun `onSetTextColor sends no event on success`() = runTest {
        val delegate = createDelegate()

        delegate.onSetTextColor(0xFF0000)
        advanceUntilIdle()

        assertTrue(sentEvents.isEmpty())
    }

    // ===========================================
    // SET TEXT SHADOW
    // ===========================================

    @Test
    fun `onSetTextShadowEnabled calls useCase with true`() = runTest {
        val delegate = createDelegate()

        delegate.onSetTextShadowEnabled(true)
        advanceUntilIdle()

        coVerify { setTextShadowEnabledUseCase.invoke(true) }
    }

    @Test
    fun `onSetTextShadowEnabled calls useCase with false`() = runTest {
        val delegate = createDelegate()

        delegate.onSetTextShadowEnabled(false)
        advanceUntilIdle()

        coVerify { setTextShadowEnabledUseCase.invoke(false) }
    }

    @Test
    fun `onSetTextShadowEnabled shows error toast on failure`() = runTest {
        coEvery { setTextShadowEnabledUseCase(any()) } throws RuntimeException("Fail")

        val delegate = createDelegate()

        delegate.onSetTextShadowEnabled(true)
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.ShowToast })
    }

    // ===========================================
    // SET CHIP BACKGROUND COLOR
    // ===========================================

    @Test
    fun `onSetChipBackgroundColor calls useCase with correct color`() = runTest {
        val color = 0xFFFF0000.toInt()
        val delegate = createDelegate()

        delegate.onSetChipBackgroundColor(color)
        advanceUntilIdle()

        coVerify { setChipBackgroundColorUseCase.invoke(color) }
    }

    @Test
    fun `onSetChipBackgroundColor shows error toast on failure`() = runTest {
        coEvery { setChipBackgroundColorUseCase(any()) } throws RuntimeException("Fail")

        val delegate = createDelegate()

        delegate.onSetChipBackgroundColor(0xFF0000)
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.ShowToast })
    }

    @Test
    fun `onSetChipBackgroundColor sends no event on success`() = runTest {
        val delegate = createDelegate()

        delegate.onSetChipBackgroundColor(0xFF0000)
        advanceUntilIdle()

        assertTrue(sentEvents.isEmpty())
    }

    // ===========================================
    // QUERY: TEXT SHADOW ENABLED
    // ===========================================

    @Test
    fun `isTextShadowEnabled returns true when enabled`() = runTest {
        coEvery { getTextShadowEnabledUseCase() } returns true

        val delegate = createDelegate()

        assertTrue(delegate.isTextShadowEnabled())
    }

    @Test
    fun `isTextShadowEnabled returns false when disabled`() = runTest {
        coEvery { getTextShadowEnabledUseCase() } returns false

        val delegate = createDelegate()

        assertFalse(delegate.isTextShadowEnabled())
    }

    // ===========================================
    // WALLPAPER SCRIM ALPHA
    // ===========================================

    @Test
    fun `wallpaperScrimAlphaState starts with default value`() = runTest {
        val delegate = createDelegate()
        advanceUntilIdle()
        assertEquals(
            AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA,
            delegate.wallpaperScrimAlphaState.value,
            0.0001f
        )
    }

    @Test
    fun `wallpaperScrimAlphaState reflects flow updates`() = runTest {
        val scrimFlow = MutableStateFlow(AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA)
        every { getWallpaperScrimAlphaUseCase() } returns scrimFlow

        val delegate = createDelegate()
        advanceUntilIdle()
        assertEquals(
            AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA,
            delegate.wallpaperScrimAlphaState.value,
            0.0001f
        )

        scrimFlow.value = 0.3f
        advanceUntilIdle()
        assertEquals(0.3f, delegate.wallpaperScrimAlphaState.value, 0.0001f)
    }

    @Test
    fun `onSetWallpaperScrimAlpha delegates coerced value`() = runTest {
        val delegate = createDelegate()

        delegate.onSetWallpaperScrimAlpha(0.25f)
        advanceUntilIdle()

        coVerify { setWallpaperScrimAlphaUseCase.invoke(0.25f) }
    }

    @Test
    fun `onSetWallpaperScrimAlpha coerces above-max to max`() = runTest {
        val delegate = createDelegate()

        delegate.onSetWallpaperScrimAlpha(99f)
        advanceUntilIdle()

        coVerify { setWallpaperScrimAlphaUseCase.invoke(AppConstants.WALLPAPER_SCRIM_ALPHA_MAX) }
    }

    @Test
    fun `onSetWallpaperScrimAlpha coerces below-min to min`() = runTest {
        val delegate = createDelegate()

        delegate.onSetWallpaperScrimAlpha(-5f)
        advanceUntilIdle()

        coVerify { setWallpaperScrimAlphaUseCase.invoke(AppConstants.WALLPAPER_SCRIM_ALPHA_MIN) }
    }
}
package com.github.reygnn.kolibri_launcher.ui.main.delegate

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.usecase.GetLayoutSettingsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetContentTopMarginUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetFontBoldUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetLayoutScaleUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetVerticalPaddingUseCase
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LayoutDelegateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private val sentEvents = mutableListOf<UiEvent>()

    private lateinit var getLayoutSettingsUseCase: GetLayoutSettingsUseCase
    private lateinit var setLayoutScaleUseCase: SetLayoutScaleUseCase
    private lateinit var setVerticalPaddingUseCase: SetVerticalPaddingUseCase
    private lateinit var setFontBoldUseCase: SetFontBoldUseCase
    private lateinit var setContentTopMarginUseCase: SetContentTopMarginUseCase

    @Before
    fun setUp() {
        sentEvents.clear()

        getLayoutSettingsUseCase = mockk {
            every { layoutScale } returns flowOf(AppConstants.DEFAULT_LAYOUT_SCALE)
            every { verticalPadding } returns flowOf(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)
            every { isFontBold } returns flowOf(AppConstants.DEFAULT_FONT_BOLD)
            every { contentTopMargin } returns flowOf(0f)
        }

        setLayoutScaleUseCase = mockk(relaxed = true)
        setVerticalPaddingUseCase = mockk(relaxed = true)
        setFontBoldUseCase = mockk(relaxed = true)
        setContentTopMarginUseCase = mockk(relaxed = true)
    }

    private fun createDelegateScope() = DelegateScope(
        coroutineScope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob()),
        mainDispatcher = mainDispatcherRule.testDispatcher,
        eventSender = { event -> sentEvents.add(event) }
    )

    private fun createDelegate(
        getLayoutSettingsUseCase: GetLayoutSettingsUseCase = this.getLayoutSettingsUseCase
    ) = LayoutDelegate(
        getLayoutSettingsUseCase = getLayoutSettingsUseCase,
        setLayoutScaleUseCase = setLayoutScaleUseCase,
        setVerticalPaddingUseCase = setVerticalPaddingUseCase,
        setFontBoldUseCase = setFontBoldUseCase,
        setContentTopMarginUseCase = setContentTopMarginUseCase,
        scope = createDelegateScope()
    )

    // ===========================================
    // INITIAL / OBSERVED STATE
    // ===========================================

    @Test
    fun `layoutScaleState starts with default value`() = runTest {
        val delegate = createDelegate()
        advanceUntilIdle()

        assertEquals(AppConstants.DEFAULT_LAYOUT_SCALE, delegate.layoutScaleState.value)
    }

    @Test
    fun `verticalPaddingState starts with default value`() = runTest {
        val delegate = createDelegate()
        advanceUntilIdle()

        assertEquals(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR, delegate.verticalPaddingState.value)
    }

    @Test
    fun `isFontBoldState starts with default value`() = runTest {
        val delegate = createDelegate()
        advanceUntilIdle()

        assertEquals(AppConstants.DEFAULT_FONT_BOLD, delegate.isFontBoldState.value)
    }

    @Test
    fun `contentTopMarginState starts with zero`() = runTest {
        val delegate = createDelegate()
        advanceUntilIdle()

        assertEquals(0f, delegate.contentTopMarginState.value)
    }

    // ===========================================
    // OBSERVED STATE UPDATES
    // ===========================================

    @Test
    fun `layoutScaleState reflects flow updates`() = runTest {
        val scaleFlow = MutableStateFlow(AppConstants.DEFAULT_LAYOUT_SCALE)
        val useCase: GetLayoutSettingsUseCase = mockk {
            every { layoutScale } returns scaleFlow
            every { verticalPadding } returns flowOf(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)
            every { isFontBold } returns flowOf(AppConstants.DEFAULT_FONT_BOLD)
            every { contentTopMargin } returns flowOf(0f)
        }

        val delegate = createDelegate(getLayoutSettingsUseCase = useCase)

        advanceUntilIdle()
        assertEquals(AppConstants.DEFAULT_LAYOUT_SCALE, delegate.layoutScaleState.value)

        scaleFlow.value = 1.5f
        advanceUntilIdle()
        assertEquals(1.5f, delegate.layoutScaleState.value)
    }

    @Test
    fun `layoutScaleState falls back to default on error`() = runTest {
        val useCase: GetLayoutSettingsUseCase = mockk {
            every { layoutScale } returns flow<Float> {
                throw RuntimeException("DB error")
            }
            every { verticalPadding } returns flowOf(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)
            every { isFontBold } returns flowOf(AppConstants.DEFAULT_FONT_BOLD)
            every { contentTopMargin } returns flowOf(0f)
        }

        val delegate = createDelegate(getLayoutSettingsUseCase = useCase)
        advanceUntilIdle()

        assertEquals(AppConstants.DEFAULT_LAYOUT_SCALE, delegate.layoutScaleState.value)
    }

    // ===========================================
    // SET LAYOUT SCALE
    // ===========================================

    @Test
    fun `onSetLayoutScale calls useCase with clamped value`() = runTest {
        val validScale = (AppConstants.LAYOUT_SCALE_MIN + AppConstants.LAYOUT_SCALE_MAX) / 2f
        val delegate = createDelegate()

        delegate.onSetLayoutScale(validScale)
        advanceUntilIdle()

        coVerify { setLayoutScaleUseCase.invoke(validScale) }
    }

    @Test
    fun `onSetLayoutScale clamps value above max`() = runTest {
        val tooHigh = AppConstants.LAYOUT_SCALE_MAX + 10f
        val delegate = createDelegate()

        delegate.onSetLayoutScale(tooHigh)
        advanceUntilIdle()

        coVerify { setLayoutScaleUseCase.invoke(AppConstants.LAYOUT_SCALE_MAX) }
    }

    @Test
    fun `onSetLayoutScale clamps value below min`() = runTest {
        val tooLow = AppConstants.LAYOUT_SCALE_MIN - 10f
        val delegate = createDelegate()

        delegate.onSetLayoutScale(tooLow)
        advanceUntilIdle()

        coVerify { setLayoutScaleUseCase.invoke(AppConstants.LAYOUT_SCALE_MIN) }
    }

    // ===========================================
    // SET VERTICAL PADDING
    // ===========================================

    @Test
    fun `onSetVerticalPadding calls useCase with clamped value`() = runTest {
        val validPadding = (AppConstants.VERTICAL_PADDING_SCALE_MIN + AppConstants.VERTICAL_PADDING_SCALE_MAX) / 2f
        val delegate = createDelegate()

        delegate.onSetVerticalPadding(validPadding)
        advanceUntilIdle()

        coVerify { setVerticalPaddingUseCase.invoke(validPadding) }
    }

    @Test
    fun `onSetVerticalPadding clamps value above max`() = runTest {
        val tooHigh = AppConstants.VERTICAL_PADDING_SCALE_MAX + 10f
        val delegate = createDelegate()

        delegate.onSetVerticalPadding(tooHigh)
        advanceUntilIdle()

        coVerify { setVerticalPaddingUseCase.invoke(AppConstants.VERTICAL_PADDING_SCALE_MAX) }
    }

    @Test
    fun `onSetVerticalPadding clamps value below min`() = runTest {
        val tooLow = AppConstants.VERTICAL_PADDING_SCALE_MIN - 10f
        val delegate = createDelegate()

        delegate.onSetVerticalPadding(tooLow)
        advanceUntilIdle()

        coVerify { setVerticalPaddingUseCase.invoke(AppConstants.VERTICAL_PADDING_SCALE_MIN) }
    }

    // ===========================================
    // SET FONT BOLD
    // ===========================================

    @Test
    fun `onSetFontBold calls useCase with true`() = runTest {
        val delegate = createDelegate()

        delegate.onSetFontBold(true)
        advanceUntilIdle()

        coVerify { setFontBoldUseCase.invoke(true) }
    }

    @Test
    fun `onSetFontBold calls useCase with false`() = runTest {
        val delegate = createDelegate()

        delegate.onSetFontBold(false)
        advanceUntilIdle()

        coVerify { setFontBoldUseCase.invoke(false) }
    }

    // ===========================================
    // SET CONTENT TOP MARGIN
    // ===========================================

    @Test
    fun `onSetContentTopMargin calls useCase with clamped value`() = runTest {
        val validMargin = (AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN + AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX) / 2f
        val delegate = createDelegate()

        delegate.onSetContentTopMargin(validMargin)
        advanceUntilIdle()

        coVerify { setContentTopMarginUseCase.invoke(validMargin) }
    }

    @Test
    fun `onSetContentTopMargin clamps value above max`() = runTest {
        val tooHigh = AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX + 10f
        val delegate = createDelegate()

        delegate.onSetContentTopMargin(tooHigh)
        advanceUntilIdle()

        coVerify { setContentTopMarginUseCase.invoke(AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX) }
    }

    @Test
    fun `onSetContentTopMargin clamps value below min`() = runTest {
        val tooLow = AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN - 10f
        val delegate = createDelegate()

        delegate.onSetContentTopMargin(tooLow)
        advanceUntilIdle()

        coVerify { setContentTopMarginUseCase.invoke(AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN) }
    }

    // ===========================================
    // RESET LAYOUT SETTINGS
    // ===========================================

    @Test
    fun `onResetLayoutSettings calls all setters with defaults`() = runTest {
        val delegate = createDelegate()

        delegate.onResetLayoutSettings()
        advanceUntilIdle()

        coVerify { setLayoutScaleUseCase.invoke(AppConstants.DEFAULT_LAYOUT_SCALE) }
        coVerify { setVerticalPaddingUseCase.invoke(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR) }
        coVerify { setFontBoldUseCase.invoke(AppConstants.DEFAULT_FONT_BOLD) }
        coVerify { setContentTopMarginUseCase.invoke(AppConstants.DEFAULT_TOP_MARGIN) }
    }

    @Test
    fun `onResetLayoutSettings does not send any events on success`() = runTest {
        val delegate = createDelegate()

        delegate.onResetLayoutSettings()
        advanceUntilIdle()

        assertTrue(sentEvents.isEmpty())
    }
}
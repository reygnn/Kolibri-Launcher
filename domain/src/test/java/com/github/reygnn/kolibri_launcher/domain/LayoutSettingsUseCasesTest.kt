package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertSame

@ExperimentalCoroutinesApi
class LayoutSettingsUseCasesTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK(relaxUnitFun = true)
    private lateinit var repository: SettingsRepository

    private val layoutScaleFlow = MutableStateFlow(1.0f)
    private val wallpaperScrimAlphaFlow = MutableStateFlow(0.0f)
    private val verticalPaddingFlow = MutableStateFlow(1.0f)
    private val isFontBoldFlow = MutableStateFlow(false)
    private val contentTopMarginFlow = MutableStateFlow(1.0f)
    private val favoritesAlignmentFlow = MutableStateFlow(AppConstants.DEFAULT_FAVORITES_ALIGNMENT)

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        every { repository.layoutScaleStateFlow } returns layoutScaleFlow
        every { repository.wallpaperScrimAlphaStateFlow } returns wallpaperScrimAlphaFlow
        every { repository.verticalPaddingStateFlow } returns verticalPaddingFlow
        every { repository.isFontBoldStateFlow } returns isFontBoldFlow
        every { repository.contentTopMarginScaleFlow } returns contentTopMarginFlow
        every { repository.favoritesAlignmentFlow } returns favoritesAlignmentFlow
    }

    @Test
    fun `GetLayoutSettingsUseCase - wires up repository flows correctly`() {
        val useCase = GetLayoutSettingsUseCase(repository)

        assertSame(layoutScaleFlow, useCase.layoutScale)
        assertSame(wallpaperScrimAlphaFlow, useCase.wallpaperScrimAlpha)
        assertSame(verticalPaddingFlow, useCase.verticalPadding)
        assertSame(isFontBoldFlow, useCase.isFontBold)
        assertSame(contentTopMarginFlow, useCase.contentTopMargin)
        assertSame(favoritesAlignmentFlow, useCase.favoritesAlignment)
    }

    @Test
    fun `SetLayoutScaleUseCase - delegates to repository`() = runTest {
        val useCase = SetLayoutScaleUseCase(repository)
        useCase(1.5f)
        coVerify { repository.setLayoutScale(1.5f) }
    }

    @Test
    fun `SetWallpaperScrimAlphaUseCase - delegates to repository`() = runTest {
        val useCase = SetWallpaperScrimAlphaUseCase(repository)
        useCase(0.3f)
        coVerify { repository.setWallpaperScrimAlpha(0.3f) }
    }

    @Test
    fun `SetVerticalPaddingUseCase - delegates to repository`() = runTest {
        val useCase = SetVerticalPaddingUseCase(repository)
        useCase(2.0f)
        coVerify { repository.setVerticalPadding(2.0f) }
    }

    @Test
    fun `SetFontBoldUseCase - delegates to repository`() = runTest {
        val useCase = SetFontBoldUseCase(repository)
        useCase(true)
        coVerify { repository.setFontBold(true) }
    }

    @Test
    fun `SetContentTopMarginUseCase - delegates to repository`() = runTest {
        val useCase = SetContentTopMarginUseCase(repository)
        useCase(0.8f)
        coVerify { repository.setContentTopMarginScale(0.8f) }
    }

    @Test
    fun `SetFavoritesAlignmentUseCase - delegates to repository`() = runTest {
        val useCase = SetFavoritesAlignmentUseCase(repository)
        useCase(FavoritesAlignment.CENTER)
        coVerify { repository.setFavoritesAlignment(FavoritesAlignment.CENTER) }
    }
}

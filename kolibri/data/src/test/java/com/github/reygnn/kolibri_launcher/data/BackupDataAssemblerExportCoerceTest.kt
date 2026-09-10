package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperBackdrop
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperSurfaceMode
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * ============================================================================
 * BACKUP-DATA-ASSEMBLER — EXPORT FLOAT-COERCION TESTS
 * ============================================================================
 *
 * Pins the export-side belt-and-suspenders clamp in [BackupDataAssembler.buildBackupData]:
 * the four scale/alpha floats (layoutScale, wallpaperScrimAlpha, verticalPaddingScale,
 * contentTopMarginScale) are `coerceInSafe`-clamped before they enter the backup JSON,
 * symmetric with the restore side. A non-finite value from a pre-coercion build must
 * never serialize into the backup (JSON cannot represent NaN/Infinity), and an
 * out-of-range value must be pulled back to its bound.
 *
 * The export path (`buildBackupData`) had no assembler-level coverage before this;
 * the existing `BackupDataAssembler*Test` files exercise only the import side.
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupDataAssemblerExportCoerceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private val favoritesRepository: FavoritesRepository = mockk(relaxed = true)
    private val favoritesOrderRepository: FavoritesOrderRepository = mockk(relaxed = true)
    private val hiddenAppsRepository: HiddenAppsRepository = mockk(relaxed = true)
    private val customNamesRepository: CustomNamesRepository = mockk(relaxed = true)
    private val installedAppsRepository: InstalledAppsRepository = mockk(relaxed = true)
    private val swipeActionsRepository: SwipeActionsRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val wallpaperRepository: WallpaperRepository = mockk(relaxed = true)

    private fun makeAssembler(): BackupDataAssembler = BackupDataAssembler(
        favoritesRepository = favoritesRepository,
        favoritesOrderRepository = favoritesOrderRepository,
        hiddenAppsRepository = hiddenAppsRepository,
        customNamesRepository = customNamesRepository,
        installedAppsRepository = installedAppsRepository,
        swipeActionsRepository = swipeActionsRepository,
        settingsRepository = settingsRepository,
        wallpaperRepository = wallpaperRepository,
        appVersionName = "test",
    )

    /**
     * Stub every flow `buildBackupData` reads with a valid default; the four coerced
     * floats are parameterised. Non-flow reads (favorites snapshot, wallpaper state,
     * swipe assignments) come back from the relaxed mocks and are irrelevant here.
     */
    private fun stubExportFlows(
        layoutScale: Float,
        scrimAlpha: Float,
        verticalPadding: Float,
        contentTopMargin: Float,
    ) {
        every { hiddenAppsRepository.hiddenAppsFlow } returns flowOf(emptySet())

        every { settingsRepository.textColorFlow } returns flowOf(0)
        every { settingsRepository.textShadowEnabledFlow } returns flowOf(false)
        every { settingsRepository.layoutScaleStateFlow } returns flowOf(layoutScale)
        every { settingsRepository.wallpaperScrimAlphaStateFlow } returns flowOf(scrimAlpha)
        every { settingsRepository.verticalPaddingStateFlow } returns flowOf(verticalPadding)
        every { settingsRepository.isFontBoldStateFlow } returns flowOf(false)
        every { settingsRepository.contentTopMarginScaleFlow } returns flowOf(contentTopMargin)
        every { settingsRepository.favoritesAlignmentFlow } returns flowOf(FavoritesAlignment.START)
        every { settingsRepository.wallpaperSurfaceModeFlow } returns flowOf(WallpaperSurfaceMode.AUTO)
        every { settingsRepository.wallpaperBackdropFlow } returns flowOf(WallpaperBackdrop.SYSTEM_WALLPAPER)
        every { settingsRepository.showCalendarEventFlow } returns flowOf(false)
        every { settingsRepository.showAlarmFlow } returns flowOf(false)
        every { settingsRepository.autoShowKeyboardFlow } returns flowOf(false)
        every { settingsRepository.autoLaunchAppFlow } returns flowOf(false)
        every { settingsRepository.sortOrderFlow } returns flowOf(SortOrder.ALPHABETICAL)
        every { settingsRepository.rotationLockedFlow } returns flowOf(false)
    }

    @Test
    fun `buildBackupData clamps NaN floats to their min on export`() = runTest {
        // coerceInSafe maps NaN -> min. A NaN stored by a pre-coercion build must not
        // reach the backup JSON (which cannot represent NaN).
        stubExportFlows(
            layoutScale = Float.NaN,
            scrimAlpha = Float.NaN,
            verticalPadding = Float.NaN,
            contentTopMargin = Float.NaN,
        )

        val result = makeAssembler().buildBackupData()

        assertThat(result.settings.layoutScale).isEqualTo(AppConstants.LAYOUT_SCALE_MIN)
        assertThat(result.settings.wallpaperScrimAlpha).isEqualTo(AppConstants.WALLPAPER_SCRIM_ALPHA_MIN)
        assertThat(result.settings.verticalPaddingScale).isEqualTo(AppConstants.VERTICAL_PADDING_SCALE_MIN)
        assertThat(result.settings.contentTopMarginScale).isEqualTo(AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN)
    }

    @Test
    fun `buildBackupData clamps out-of-range and infinite floats to their bounds on export`() = runTest {
        // +Infinity -> max, -Infinity -> min, finite out-of-range -> nearest bound.
        stubExportFlows(
            layoutScale = Float.POSITIVE_INFINITY,   // -> LAYOUT_SCALE_MAX
            scrimAlpha = 99f,                         // -> WALLPAPER_SCRIM_ALPHA_MAX
            verticalPadding = Float.NEGATIVE_INFINITY, // -> VERTICAL_PADDING_SCALE_MIN
            contentTopMargin = 5f,                    // -> CONTENT_TOP_MARGIN_SCALE_MAX
        )

        val result = makeAssembler().buildBackupData()

        assertThat(result.settings.layoutScale).isEqualTo(AppConstants.LAYOUT_SCALE_MAX)
        assertThat(result.settings.wallpaperScrimAlpha).isEqualTo(AppConstants.WALLPAPER_SCRIM_ALPHA_MAX)
        assertThat(result.settings.verticalPaddingScale).isEqualTo(AppConstants.VERTICAL_PADDING_SCALE_MIN)
        assertThat(result.settings.contentTopMarginScale).isEqualTo(AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX)
    }

    @Test
    fun `buildBackupData leaves in-range floats unchanged on export`() = runTest {
        // Regression guard: the clamp is a no-op for legitimate values, so real backups
        // are byte-identical to before the export-side hardening.
        stubExportFlows(
            layoutScale = 1.5f,
            scrimAlpha = 0.3f,
            verticalPadding = 1.2f,
            contentTopMargin = 0.8f,
        )

        val result = makeAssembler().buildBackupData()

        assertThat(result.settings.layoutScale).isEqualTo(1.5f)
        assertThat(result.settings.wallpaperScrimAlpha).isEqualTo(0.3f)
        assertThat(result.settings.verticalPaddingScale).isEqualTo(1.2f)
        assertThat(result.settings.contentTopMarginScale).isEqualTo(0.8f)
    }
}

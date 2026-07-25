package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperSurfaceMode
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * ============================================================================
 * SETTINGS REPOSITORY — CONTRACT TEST
 * ============================================================================
 *
 * Siehe [FavoritesRepositoryContract] für Hintergrund und Konventionen.
 *
 * Das [SettingsRepository]-Interface hat ~20 Flow/Setter-Paare, die einem
 * gemeinsamen Muster folgen (`flow` liefert Default → `set()` wird reaktiv
 * sichtbar). Wir testen NICHT jede einzelne Property; der Contract deckt ein
 * repräsentatives Sample je Datentyp (Boolean, Int, Float, String, Enum) plus
 * die Stellen mit nicht-trivialer Logik ab:
 *
 *   - `onboardingCompleted`: einseitiger Setter (nur true), überlebt `purge`
 *   - `purgeRepository`: setzt alles auf Default ZURÜCK, außer Onboarding
 *
 * Wenn neue Logik im Manager oder Fake dazukommt (Validierung, Transformation),
 * gehört der Test dafür in diesen Contract.
 *
 * @see FakeSettingsRepositoryContractTest
 * @see SettingsRepositoryImplContractTest
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class SettingsRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    /**
     * Baut eine frische Repository-Instanz für genau diesen Test.
     *
     * [SettingsRepository]-Implementierungen brauchen aktuell keinen Scope
     * (Manager benutzt im primären Konstruktor auch keinen), deshalb nimmt die
     * Factory keinen Scope-Parameter — anders als bei
     * [FavoritesRepositoryContract].
     */
    protected abstract fun createRepository(): SettingsRepository

    // ---------- Defaults auf frischem Repository ----------

    @Test
    fun `fresh repository emits default sortOrder`() = runTest {
        val repo = createRepository()
        assertEquals(AppConstants.DEFAULT_SORT_ORDER, repo.sortOrderFlow.first())
    }

    @Test
    fun `fresh repository emits default textColor`() = runTest {
        val repo = createRepository()
        assertEquals(AppConstants.DEFAULT_TEXT_COLOR, repo.textColorFlow.first())
    }

    @Test
    fun `fresh repository emits default layoutScale`() = runTest {
        val repo = createRepository()
        assertEquals(
            AppConstants.DEFAULT_LAYOUT_SCALE,
            repo.layoutScaleStateFlow.first(),
            0.0001f
        )
    }

    @Test
    fun `fresh repository has onboarding not completed`() = runTest {
        val repo = createRepository()
        assertFalse(repo.onboardingCompletedFlow.first())
    }

    @Test
    fun `fresh repository emits default rotationLocked`() = runTest {
        val repo = createRepository()
        assertEquals(AppConstants.DEFAULT_ROTATION_LOCKED, repo.rotationLockedFlow.first())
    }

    @Test
    fun `fresh repository emits default favoritesAlignment`() = runTest {
        val repo = createRepository()
        assertEquals(
            AppConstants.DEFAULT_FAVORITES_ALIGNMENT,
            repo.favoritesAlignmentFlow.first(),
        )
    }

    @Test
    fun `fresh repository emits default wallpaperSurfaceMode`() = runTest {
        val repo = createRepository()
        assertEquals(
            AppConstants.DEFAULT_WALLPAPER_SURFACE_MODE,
            repo.wallpaperSurfaceModeFlow.first(),
        )
    }

    // ---------- Roundtrip: Set -> Flow reflects change ----------

    @Test
    fun `setSortOrder reflects in flow`() = runTest {
        val repo = createRepository()
        // Neue Wert muss != Default sein, damit der Test aussagekräftig bleibt.
        val newValue = SortOrder.entries.first { it != AppConstants.DEFAULT_SORT_ORDER }
        repo.setSortOrder(newValue)
        assertEquals(newValue, repo.sortOrderFlow.first())
    }

    @Test
    fun `setTextColor reflects in flow`() = runTest {
        val repo = createRepository()
        val newColor = 0x11223344.toInt()
        assertNotEquals(AppConstants.DEFAULT_TEXT_COLOR, newColor)
        repo.setTextColor(newColor)
        assertEquals(newColor, repo.textColorFlow.first())
    }

    @Test
    fun `setLayoutScale reflects in flow`() = runTest {
        val repo = createRepository()
        val newScale = AppConstants.DEFAULT_LAYOUT_SCALE + 0.25f
        repo.setLayoutScale(newScale)
        assertEquals(newScale, repo.layoutScaleStateFlow.first(), 0.0001f)
    }

    @Test
    fun `setRotationLocked reflects in flow`() = runTest {
        val repo = createRepository()
        val flipped = !AppConstants.DEFAULT_ROTATION_LOCKED
        repo.setRotationLocked(flipped)
        assertEquals(flipped, repo.rotationLockedFlow.first())
    }

    @Test
    fun `setFavoritesAlignment reflects in flow`() = runTest {
        val repo = createRepository()
        val newValue = FavoritesAlignment.entries
            .first { it != AppConstants.DEFAULT_FAVORITES_ALIGNMENT }
        repo.setFavoritesAlignment(newValue)
        assertEquals(newValue, repo.favoritesAlignmentFlow.first())
    }

    @Test
    fun `setWallpaperSurfaceMode reflects in flow`() = runTest {
        val repo = createRepository()
        val newValue = WallpaperSurfaceMode.entries
            .first { it != AppConstants.DEFAULT_WALLPAPER_SURFACE_MODE }
        repo.setWallpaperSurfaceMode(newValue)
        assertEquals(newValue, repo.wallpaperSurfaceModeFlow.first())
    }

    @Test
    fun `setter overwrites previous value`() = runTest {
        val repo = createRepository()
        repo.setTextColor(0x11111111.toInt())
        repo.setTextColor(0x22222222.toInt())
        assertEquals(0x22222222.toInt(), repo.textColorFlow.first())
    }

    // ---------- onboardingCompleted: einseitig ----------

    @Test
    fun `setOnboardingCompleted marks onboarding as completed`() = runTest {
        val repo = createRepository()
        repo.setOnboardingCompleted()
        assertTrue(repo.onboardingCompletedFlow.first())
    }

    @Test
    fun `setOnboardingCompleted is idempotent`() = runTest {
        val repo = createRepository()
        repo.setOnboardingCompleted()
        repo.setOnboardingCompleted()
        assertTrue(repo.onboardingCompletedFlow.first())
    }

    // ---------- purgeRepository ----------

    @Test
    fun `purgeRepository resets visual settings to defaults`() = runTest {
        val repo = createRepository()
        repo.setTextColor(0xDEADBEEF.toInt())
        repo.setLayoutScale(AppConstants.DEFAULT_LAYOUT_SCALE + 0.5f)
        repo.setFontBold(!AppConstants.DEFAULT_FONT_BOLD)

        repo.purgeRepository()

        assertEquals(AppConstants.DEFAULT_TEXT_COLOR, repo.textColorFlow.first())
        assertEquals(
            AppConstants.DEFAULT_LAYOUT_SCALE,
            repo.layoutScaleStateFlow.first(),
            0.0001f
        )
        assertEquals(AppConstants.DEFAULT_FONT_BOLD, repo.isFontBoldStateFlow.first())
    }

    @Test
    fun `purgeRepository resets feature toggles to defaults`() = runTest {
        val repo = createRepository()
        repo.setSwipeDownToNotifications(!AppConstants.DEFAULT_SWIPE_DOWN_NOTIFICATIONS)
        repo.setAutoShowKeyboard(!AppConstants.DEFAULT_AUTO_SHOW_KEYBOARD)

        repo.purgeRepository()

        assertEquals(
            AppConstants.DEFAULT_SWIPE_DOWN_NOTIFICATIONS,
            repo.swipeDownToNotificationsEnabledFlow.first()
        )
        assertEquals(
            AppConstants.DEFAULT_AUTO_SHOW_KEYBOARD,
            repo.autoShowKeyboardFlow.first()
        )
    }

    @Test
    fun `purgeRepository resets sortOrder`() = runTest {
        val repo = createRepository()
        val nonDefaultSort =
            SortOrder.entries.first { it != AppConstants.DEFAULT_SORT_ORDER }
        repo.setSortOrder(nonDefaultSort)

        repo.purgeRepository()

        assertEquals(AppConstants.DEFAULT_SORT_ORDER, repo.sortOrderFlow.first())
    }

    @Test
    fun `purgeRepository resets wallpaperSurfaceMode to default`() = runTest {
        val repo = createRepository()
        val nonDefault = WallpaperSurfaceMode.entries
            .first { it != AppConstants.DEFAULT_WALLPAPER_SURFACE_MODE }
        repo.setWallpaperSurfaceMode(nonDefault)

        repo.purgeRepository()

        assertEquals(
            AppConstants.DEFAULT_WALLPAPER_SURFACE_MODE,
            repo.wallpaperSurfaceModeFlow.first()
        )
    }

    @Test
    fun `purgeRepository resets favoritesAlignment to default`() = runTest {
        val repo = createRepository()
        val nonDefault = FavoritesAlignment.entries
            .first { it != AppConstants.DEFAULT_FAVORITES_ALIGNMENT }
        repo.setFavoritesAlignment(nonDefault)

        repo.purgeRepository()

        assertEquals(
            AppConstants.DEFAULT_FAVORITES_ALIGNMENT,
            repo.favoritesAlignmentFlow.first(),
        )
    }

    /**
     * Kritische Invariante: `purgeRepository` ist explizit als "alles außer
     * Onboarding" dokumentiert — wenn Nutzer Einstellungen zurücksetzen, sollen
     * sie nicht durch das Onboarding neu geführt werden müssen.
     */
    @Test
    fun `purgeRepository preserves completed onboarding state`() = runTest {
        val repo = createRepository()
        repo.setOnboardingCompleted()

        repo.purgeRepository()

        assertTrue(
            "Onboarding-Status muss `purgeRepository` überleben",
            repo.onboardingCompletedFlow.first()
        )
    }

    @Test
    fun `purgeRepository preserves not-yet-completed onboarding state`() = runTest {
        val repo = createRepository()
        // Onboarding wurde nie abgeschlossen.
        repo.purgeRepository()
        assertFalse(repo.onboardingCompletedFlow.first())
    }

    @Test
    fun `purgeRepository is safe on fresh repository`() = runTest {
        val repo = createRepository()
        repo.purgeRepository()
        // Keine Assertion nötig — es darf nur nicht werfen und die Defaults
        // müssen danach weiterhin gelten.
        assertEquals(AppConstants.DEFAULT_TEXT_COLOR, repo.textColorFlow.first())
    }

    @Test
    fun `purgeRepository can be called multiple times`() = runTest {
        val repo = createRepository()
        repo.setTextColor(0xAAAAAAAA.toInt())
        repo.purgeRepository()
        repo.setTextColor(0xBBBBBBBB.toInt())
        repo.purgeRepository()
        assertEquals(AppConstants.DEFAULT_TEXT_COLOR, repo.textColorFlow.first())
    }
}

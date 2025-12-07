package com.github.reygnn.kolibri_launcher.contract

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract Tests für SettingsRepository.
 *
 * Testet Kernverhalten nach Typ, nicht jeden einzelnen Flow.
 * Spezialfälle (Validierung, purge-Ausnahmen) werden explizit getestet.
 */
abstract class SettingsRepositoryContractTest {

    abstract fun createRepository(): SettingsRepository

    // ===========================================
    // BOOLEAN SETTINGS (Beispiel: textShadow)
    // ===========================================

    @Test
    fun `boolean setting - set updates flow`() = runTest {
        val repo = createRepository()

        repo.setTextShadowEnabled(true)
        assertTrue(repo.textShadowEnabledFlow.first())

        repo.setTextShadowEnabled(false)
        assertFalse(repo.textShadowEnabledFlow.first())
    }

    @Test
    fun `boolean setting - default value from AppConstants`() = runTest {
        val repo = createRepository()

        assertEquals(
            AppConstants.DEFAULT_TEXT_SHADOW_ENABLED,
            repo.textShadowEnabledFlow.first()
        )
    }

    // ===========================================
    // INT SETTINGS (Beispiel: textColor)
    // ===========================================

    @Test
    fun `int setting - set updates flow`() = runTest {
        val repo = createRepository()
        val testColor = 0xFF00FF00.toInt()

        repo.setTextColor(testColor)

        assertEquals(testColor, repo.textColorFlow.first())
    }

    // ===========================================
    // FLOAT SETTINGS (Beispiel: layoutScale)
    // ===========================================

    @Test
    fun `float setting - set updates flow`() = runTest {
        val repo = createRepository()

        repo.setLayoutScale(1.5f)

        assertEquals(1.5f, repo.layoutScaleStateFlow.first(), 0.001f)
    }

    // ===========================================
    // STRING SETTINGS (Beispiel: readabilityMode)
    // ===========================================

    @Test
    fun `string setting - set updates flow`() = runTest {
        val repo = createRepository()

        repo.setReadabilityMode("HIGH_CONTRAST")

        assertEquals("HIGH_CONTRAST", repo.readabilityModeFlow.first())
    }

    // ===========================================
    // ENUM SETTINGS (SortOrder)
    // ===========================================

    @Test
    fun `enum setting - set updates flow`() = runTest {
        val repo = createRepository()

        repo.setSortOrder(SortOrder.ALPHABETICAL)

        assertEquals(SortOrder.ALPHABETICAL, repo.sortOrderFlow.first())
    }

    @Test
    fun `enum setting - default is TIME_WEIGHTED_USAGE`() = runTest {
        val repo = createRepository()

        assertEquals(SortOrder.TIME_WEIGHTED_USAGE, repo.sortOrderFlow.first())
    }

    // ===========================================
    // SPEZIALFALL: splitModeThreshold Validierung
    // ===========================================

    @Test
    fun `splitModeThreshold - coerces value to valid range`() = runTest {
        val repo = createRepository()

        // Unter Minimum
        repo.setSplitModeThreshold(-100)
        assertEquals(
            AppConstants.SPLIT_MODE_THRESHOLD_MIN,
            repo.splitModeThresholdFlow.first()
        )

        // Über Maximum
        repo.setSplitModeThreshold(9999)
        assertEquals(
            AppConstants.SPLIT_MODE_THRESHOLD_MAX,
            repo.splitModeThresholdFlow.first()
        )

        // Gültiger Wert
        repo.setSplitModeThreshold(100)
        assertEquals(100, repo.splitModeThresholdFlow.first())
    }

    // ===========================================
    // SPEZIALFALL: Onboarding (nur setzen, nicht zurücksetzen)
    // ===========================================

    @Test
    fun `onboarding - starts as false`() = runTest {
        val repo = createRepository()

        assertFalse(repo.onboardingCompletedFlow.first())
    }

    @Test
    fun `onboarding - setOnboardingCompleted sets to true`() = runTest {
        val repo = createRepository()

        repo.setOnboardingCompleted()

        assertTrue(repo.onboardingCompletedFlow.first())
    }

    // ===========================================
    // PURGE REPOSITORY
    // ===========================================

    @Test
    fun `purgeRepository - resets settings to defaults`() = runTest {
        val repo = createRepository()

        // Ändere einige Werte
        repo.setTextShadowEnabled(!AppConstants.DEFAULT_TEXT_SHADOW_ENABLED)
        repo.setLayoutScale(2.5f)
        repo.setSortOrder(SortOrder.ALPHABETICAL)

        // Purge
        repo.purgeRepository()

        // Prüfe Defaults
        assertEquals(AppConstants.DEFAULT_TEXT_SHADOW_ENABLED, repo.textShadowEnabledFlow.first())
        assertEquals(AppConstants.DEFAULT_LAYOUT_SCALE, repo.layoutScaleStateFlow.first(), 0.001f)
        assertEquals(SortOrder.TIME_WEIGHTED_USAGE, repo.sortOrderFlow.first())
    }

    @Test
    fun `purgeRepository - preserves onboarding status`() = runTest {
        val repo = createRepository()

        repo.setOnboardingCompleted()
        repo.purgeRepository()

        // Onboarding sollte NICHT zurückgesetzt werden
        assertTrue(repo.onboardingCompletedFlow.first())
    }

    @Test
    fun `boolean settings - all follow same pattern`() = runTest {
        val repo = createRepository()

        // secureWindow
        repo.setSecureWindow(true)
        assertTrue(repo.secureWindowFlow.first())
        repo.setSecureWindow(false)
        assertFalse(repo.secureWindowFlow.first())

        // Weitere Boolean-Settings könnten hier ergänzt werden
    }
}

/**
 * Verifiziert den Fake
 */
class FakeSettingsRepositoryContractTest : SettingsRepositoryContractTest() {
    override fun createRepository(): SettingsRepository = FakeSettingsRepository()
}
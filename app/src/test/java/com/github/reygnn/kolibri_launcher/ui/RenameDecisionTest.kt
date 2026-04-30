package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.customnames.RenameDecision
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Pure JVM tests for [RenameDecision.Companion.decide]. No MockK,
 * no Robolectric, no Android dependencies — same shape as
 * [WallpaperSaveActionTest], [LayerButtonsStateTest], etc.
 */
class RenameDecisionTest {

    @get:Rule
    val timberRule = TimberRule()

    private val originalName = "Camera"

    // ------------------------------------------------------------------
    // The four base branches
    // ------------------------------------------------------------------

    @Test
    fun `decide returns Remove for empty input`() {
        val result = RenameDecision.decide(newName = "", originalName = originalName)
        assertEquals(RenameDecision.Remove, result)
    }

    @Test
    fun `decide returns TooLong when input exceeds MAX_APP_NAME_LENGTH`() {
        val tooLong = "x".repeat(RenameDecision.MAX_APP_NAME_LENGTH + 1)
        val result = RenameDecision.decide(newName = tooLong, originalName = originalName)
        assertEquals(RenameDecision.TooLong(RenameDecision.MAX_APP_NAME_LENGTH), result)
    }

    @Test
    fun `decide returns Remove when input equals original name`() {
        val result = RenameDecision.decide(newName = originalName, originalName = originalName)
        assertEquals(RenameDecision.Remove, result)
    }

    @Test
    fun `decide returns Set with new name in normal case`() {
        val result = RenameDecision.decide(newName = "Kamera", originalName = originalName)
        assertEquals(RenameDecision.Set("Kamera"), result)
    }

    // ------------------------------------------------------------------
    // Edge cases
    // ------------------------------------------------------------------

    @Test
    fun `decide accepts input exactly at MAX_APP_NAME_LENGTH`() {
        val atLimit = "x".repeat(RenameDecision.MAX_APP_NAME_LENGTH)
        val result = RenameDecision.decide(newName = atLimit, originalName = originalName)
        // 50 chars is allowed; the check uses strict greater-than, not >=.
        assertEquals(RenameDecision.Set(atLimit), result)
    }

    @Test
    fun `decide returns Remove when both inputs are empty (empty wins over equality)`() {
        val result = RenameDecision.decide(newName = "", originalName = "")
        // Empty input is checked first, so this short-circuits to Remove
        // without ever reaching the equality branch. Either path yields
        // the same outcome here, but the test pins precedence.
        assertEquals(RenameDecision.Remove, result)
    }

    @Test
    fun `decide returns TooLong when too-long input also equals original (length wins over equality)`() {
        val tooLongOriginal = "x".repeat(RenameDecision.MAX_APP_NAME_LENGTH + 5)
        val result = RenameDecision.decide(
            newName = tooLongOriginal,
            originalName = tooLongOriginal,
        )
        // Length is checked before equality, so the user gets the
        // length-error feedback even when the name happens to match.
        assertEquals(RenameDecision.TooLong(RenameDecision.MAX_APP_NAME_LENGTH), result)
    }

    @Test
    fun `decide treats whitespace-only input as Set (matches pre-extraction behavior)`() {
        // `isEmpty`, not `isBlank` — see KDoc on RenameDecision.decide.
        // The downstream ViewModel maps blank-or-equals-original to a
        // remove, so the user-visible outcome is still "no custom name".
        val result = RenameDecision.decide(newName = "   ", originalName = originalName)
        assertEquals(RenameDecision.Set("   "), result)
    }

    @Test
    fun `decide is case sensitive on equality check`() {
        val result = RenameDecision.decide(newName = "camera", originalName = "Camera")
        assertEquals(RenameDecision.Set("camera"), result)
    }
}

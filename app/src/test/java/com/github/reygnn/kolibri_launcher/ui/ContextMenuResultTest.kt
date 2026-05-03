package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.domain.model.AppContextMenuAction
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.ContextMenuResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Pure JVM tests for [ContextMenuResult.parse]. No MockK, no Robolectric, no
 * Android dependencies — same shape as [LayerButtonsStateTest],
 * [SnapIconResolverTest], etc.
 */
class ContextMenuResultTest {

    @get:Rule
    val timberRule = TimberRule()

    // ------------------------------------------------------------------------
    // Known actions: each branch reachable, mapping verified.
    // ------------------------------------------------------------------------
    //
    // Data-driven over the five known action strings. If a new action is
    // added to ContextMenuResult, this list grows and the compiler does NOT
    // force an update here — only the consuming Fragment's `when` will fail
    // exhaustiveness. This test verifies the *current* mapping; it does not
    // claim to be exhaustive over the sealed interface.

    @Test
    fun `parse maps each known action string to its branch`() {
        val cases: List<Pair<String, ContextMenuResult>> = listOf(
            ContextMenuResult.ACTION_LAUNCH_SHORTCUT to ContextMenuResult.LaunchShortcut,
            AppContextMenuAction.ACTION_ID_APP_INFO to ContextMenuResult.AppInfo,
            AppContextMenuAction.ACTION_ID_TOGGLE_FAVORITE to ContextMenuResult.ToggleFavorite,
            AppContextMenuAction.ACTION_ID_HIDE_APP to ContextMenuResult.HideApp,
            AppContextMenuAction.ACTION_ID_UNHIDE_APP to ContextMenuResult.UnhideApp,
            AppContextMenuAction.ACTION_ID_RESET_USAGE to ContextMenuResult.ResetUsage,
        )

        cases.forEach { (input, expected) ->
            assertEquals(
                "parse(\"$input\") should map to $expected",
                expected,
                ContextMenuResult.parse(input),
            )
        }
    }

    // ------------------------------------------------------------------------
    // Unknown branch: null, empty, unrecognized, and known-but-not-routed.
    // ------------------------------------------------------------------------

    @Test
    fun `parse returns Unknown(null) for null action`() {
        assertEquals(ContextMenuResult.Unknown(null), ContextMenuResult.parse(null))
    }

    @Test
    fun `parse returns Unknown for empty action`() {
        assertEquals(ContextMenuResult.Unknown(""), ContextMenuResult.parse(""))
    }

    @Test
    fun `parse returns Unknown for unrecognized action`() {
        assertEquals(
            ContextMenuResult.Unknown("not_a_real_action"),
            ContextMenuResult.parse("not_a_real_action"),
        )
    }

    @Test
    fun `parse returns Unknown for actions handled inside the dialog`() {
        // AppContextMenuAction defines RENAME_APP and RESTORE_NAME for
        // actions that the dialog itself handles (the rename input flow)
        // — they never reach a result listener via setFragmentResult.
        // They legitimately reach Unknown if ever observed, and the
        // log line carries the real action string for diagnosis.
        val handledInsideDialog = listOf(
            AppContextMenuAction.ACTION_ID_RENAME_APP,
            AppContextMenuAction.ACTION_ID_RESTORE_NAME,
        )

        handledInsideDialog.forEach { action ->
            val result = ContextMenuResult.parse(action)
            assertTrue(
                "expected Unknown for action $action, got $result",
                result is ContextMenuResult.Unknown,
            )
            assertEquals(
                "Unknown should preserve the original action string",
                action,
                (result as ContextMenuResult.Unknown).action,
            )
        }
    }

    @Test
    fun `Unknown carries the original string for log diagnosis`() {
        // Demonstrates the smart-cast pattern the consuming Fragment uses:
        //   is ContextMenuResult.Unknown -> Timber.w("... ${result.action}")
        val result = ContextMenuResult.parse("some_future_action")
        assertTrue(result is ContextMenuResult.Unknown)
        assertEquals("some_future_action", (result as ContextMenuResult.Unknown).action)
    }
}

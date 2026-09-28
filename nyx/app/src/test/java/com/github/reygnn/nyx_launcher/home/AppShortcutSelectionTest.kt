package com.github.reygnn.nyx_launcher.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure JVM tests for the home-context-menu shortcut selection policy. */
class AppShortcutSelectionTest {

    // Identity projection: the candidate IS the element, so the tests read directly.
    private fun select(vararg candidates: ShortcutCandidate) =
        selectAppShortcuts(candidates.toList()) { it }

    private fun sc(
        enabled: Boolean = true,
        rank: Int = 0,
        shortLabel: String? = "s",
        longLabel: String? = "l",
    ) = ShortcutCandidate(enabled, rank, shortLabel, longLabel)

    @Test fun disabled_shortcuts_are_dropped() {
        val out = select(sc(enabled = false, shortLabel = "gone"), sc(shortLabel = "kept"))
        assertThat(out.map { it.second }).containsExactly("kept")
    }

    @Test fun kept_shortcuts_are_ordered_by_ascending_rank() {
        val out = select(
            sc(rank = 2, shortLabel = "c"),
            sc(rank = 0, shortLabel = "a"),
            sc(rank = 1, shortLabel = "b"),
        )
        assertThat(out.map { it.second }).containsExactly("a", "b", "c").inOrder()
    }

    @Test fun the_cap_keeps_only_the_first_four_by_rank() {
        val out = select(
            sc(rank = 0, shortLabel = "a"),
            sc(rank = 1, shortLabel = "b"),
            sc(rank = 2, shortLabel = "c"),
            sc(rank = 3, shortLabel = "d"),
            sc(rank = 4, shortLabel = "e"), // beyond MAX_APP_SHORTCUTS
        )
        assertThat(out.map { it.second }).containsExactly("a", "b", "c", "d").inOrder()
    }

    @Test fun short_label_wins_over_long_label() {
        val out = select(sc(shortLabel = "short", longLabel = "long"))
        assertThat(out.single().second).isEqualTo("short")
    }

    @Test fun long_label_is_the_fallback_when_short_is_null() {
        val out = select(sc(shortLabel = null, longLabel = "long"))
        assertThat(out.single().second).isEqualTo("long")
    }

    @Test fun a_label_less_shortcut_is_dropped_but_still_consumed_a_slot() {
        // ranks 0..4; the rank-2 shortcut has no usable label. The cap takes ranks 0..3
        // FIRST, then the label drop removes rank 2 — so the result is a/b/d (size 3) and
        // rank 4 is NOT backfilled into the freed slot. Pins the take-then-drop order.
        val out = select(
            sc(rank = 0, shortLabel = "a"),
            sc(rank = 1, shortLabel = "b"),
            sc(rank = 2, shortLabel = null, longLabel = null),
            sc(rank = 3, shortLabel = "d"),
            sc(rank = 4, shortLabel = "e"),
        )
        assertThat(out.map { it.second }).containsExactly("a", "b", "d").inOrder()
    }

    @Test fun no_shortcuts_yields_an_empty_list() {
        assertThat(selectAppShortcuts(emptyList<ShortcutCandidate>()) { it }).isEmpty()
    }

    @Test fun the_original_element_is_carried_through_for_the_tap_handler() {
        // The generic pair keeps the source object so the UI can call startShortcut on it.
        val source = sc(rank = 0, shortLabel = "x")
        val out = selectAppShortcuts(listOf(source)) { it }
        assertThat(out.single().first).isSameInstanceAs(source)
    }
}

package com.github.reygnn.nyx_launcher.home.model

import com.github.reygnn.launcher.core.ComponentKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pins the merge invariant of the Settings hidden-apps manager (merge, not replace). */
class HiddenAppsSelectionTest {

    private fun key(p: String) = ComponentKey(p, "$p.Main")

    @Test
    fun `keeps hidden keys for apps not shown, applies the checked selection for shown ones`() {
        val current = setOf(key("a"), key("b"), key("gone")) // "gone" is not currently installed
        val shown = setOf(key("a"), key("b"), key("c"))
        val checked = setOf(key("b"), key("c")) // a unchecked (unhide), b kept, c newly checked

        val merged = HiddenAppsSelection.merge(current, shown, checked)

        // a removed (shown+unchecked), b kept, c added, "gone" preserved (not in the shown list).
        assertThat(merged).containsExactly(key("b"), key("c"), key("gone"))
    }

    @Test
    fun `empty checked over all-shown clears the set`() {
        val current = setOf(key("a"), key("b"))
        val merged = HiddenAppsSelection.merge(current, shownKeys = setOf(key("a"), key("b")), checkedShown = emptySet())
        assertThat(merged).isEmpty()
    }
}

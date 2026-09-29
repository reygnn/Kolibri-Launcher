package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.backup.MissingAppsFormatter
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class MissingAppsFormatterTest {

    @get:Rule
    val timberRule = TimberRule()

    // ========== EMPTY ==========

    @Test
    fun `empty set returns empty result with no overflow`() {
        val result = MissingAppsFormatter().format(emptySet())
        assertThat(result.listText).isEqualTo("")
        assertThat(result.overflowCount).isEqualTo(0)
        assertThat(result.hasOverflow).isFalse()
    }

    // ========== PACKAGE NAME EXTRACTION ==========

    @Test
    fun `package name is extracted before first slash`() {
        val result = MissingAppsFormatter(maxDisplayed = 5)
            .format(setOf("com.example.foo/com.example.foo.MainActivity"))
        assertThat(result.listText).isEqualTo("com.example.foo")
    }

    @Test
    fun `entry without slash is kept as is`() {
        val result = MissingAppsFormatter(maxDisplayed = 5).format(setOf("com.example.foo"))
        assertThat(result.listText).isEqualTo("com.example.foo")
    }

    @Test
    fun `entries are joined by newline`() {
        // setOf(...) liefert LinkedHashSet - Insertion-Order bleibt erhalten
        val result = MissingAppsFormatter(maxDisplayed = 5)
            .format(setOf("a/x", "b/y", "c/z"))
        assertThat(result.listText).isEqualTo("a\nb\nc")
    }

    // ========== OVERFLOW ==========

    @Test
    fun `no overflow when size equals max`() {
        val result = MissingAppsFormatter(maxDisplayed = 3)
            .format(setOf("a/x", "b/y", "c/z"))
        assertThat(result.overflowCount).isEqualTo(0)
        assertThat(result.hasOverflow).isFalse()
        assertThat(result.listText).isEqualTo("a\nb\nc")
    }

    @Test
    fun `no overflow when size is below max`() {
        val result = MissingAppsFormatter(maxDisplayed = 10)
            .format(setOf("a/x", "b/y"))
        assertThat(result.overflowCount).isEqualTo(0)
        assertThat(result.listText).isEqualTo("a\nb")
    }

    @Test
    fun `overflow reports difference when size exceeds max`() {
        val result = MissingAppsFormatter(maxDisplayed = 2)
            .format(setOf("a/x", "b/y", "c/z", "d/w"))
        assertThat(result.overflowCount).isEqualTo(2)
        assertThat(result.hasOverflow).isTrue()
    }

    @Test
    fun `list is truncated to maxDisplayed items`() {
        val result = MissingAppsFormatter(maxDisplayed = 2)
            .format(setOf("a/x", "b/y", "c/z", "d/w"))
        assertThat(result.listText).isEqualTo("a\nb")
    }

    @Test
    fun `defensive - max zero yields empty list and overflow equals size`() {
        val result = MissingAppsFormatter(maxDisplayed = 0)
            .format(setOf("a/x", "b/y"))
        assertThat(result.listText).isEqualTo("")
        assertThat(result.overflowCount).isEqualTo(2)
    }

    @Test
    fun `overflow count is coerced to zero or positive`() {
        // Stellt sicher, dass bei großem maxDisplayed kein negativer overflow entsteht.
        val result = MissingAppsFormatter(maxDisplayed = 100)
            .format(setOf("a/x"))
        assertThat(result.overflowCount).isEqualTo(0)
    }
}

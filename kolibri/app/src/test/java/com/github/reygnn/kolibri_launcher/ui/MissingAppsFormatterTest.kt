package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.backup.MissingAppsFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MissingAppsFormatterTest {

    @get:Rule
    val timberRule = TimberRule()

    // ========== EMPTY ==========

    @Test
    fun `empty set returns empty result with no overflow`() {
        val result = MissingAppsFormatter().format(emptySet())
        assertEquals("", result.listText)
        assertEquals(0, result.overflowCount)
        assertFalse(result.hasOverflow)
    }

    // ========== PACKAGE NAME EXTRACTION ==========

    @Test
    fun `package name is extracted before first slash`() {
        val result = MissingAppsFormatter(maxDisplayed = 5)
            .format(setOf("com.example.foo/com.example.foo.MainActivity"))
        assertEquals("com.example.foo", result.listText)
    }

    @Test
    fun `entry without slash is kept as is`() {
        val result = MissingAppsFormatter(maxDisplayed = 5).format(setOf("com.example.foo"))
        assertEquals("com.example.foo", result.listText)
    }

    @Test
    fun `entries are joined by newline`() {
        // setOf(...) liefert LinkedHashSet - Insertion-Order bleibt erhalten
        val result = MissingAppsFormatter(maxDisplayed = 5)
            .format(setOf("a/x", "b/y", "c/z"))
        assertEquals("a\nb\nc", result.listText)
    }

    // ========== OVERFLOW ==========

    @Test
    fun `no overflow when size equals max`() {
        val result = MissingAppsFormatter(maxDisplayed = 3)
            .format(setOf("a/x", "b/y", "c/z"))
        assertEquals(0, result.overflowCount)
        assertFalse(result.hasOverflow)
        assertEquals("a\nb\nc", result.listText)
    }

    @Test
    fun `no overflow when size is below max`() {
        val result = MissingAppsFormatter(maxDisplayed = 10)
            .format(setOf("a/x", "b/y"))
        assertEquals(0, result.overflowCount)
        assertEquals("a\nb", result.listText)
    }

    @Test
    fun `overflow reports difference when size exceeds max`() {
        val result = MissingAppsFormatter(maxDisplayed = 2)
            .format(setOf("a/x", "b/y", "c/z", "d/w"))
        assertEquals(2, result.overflowCount)
        assertTrue(result.hasOverflow)
    }

    @Test
    fun `list is truncated to maxDisplayed items`() {
        val result = MissingAppsFormatter(maxDisplayed = 2)
            .format(setOf("a/x", "b/y", "c/z", "d/w"))
        assertEquals("a\nb", result.listText)
    }

    @Test
    fun `defensive - max zero yields empty list and overflow equals size`() {
        val result = MissingAppsFormatter(maxDisplayed = 0)
            .format(setOf("a/x", "b/y"))
        assertEquals("", result.listText)
        assertEquals(2, result.overflowCount)
    }

    @Test
    fun `overflow count is coerced to zero or positive`() {
        // Stellt sicher, dass bei großem maxDisplayed kein negativer overflow entsteht.
        val result = MissingAppsFormatter(maxDisplayed = 100)
            .format(setOf("a/x"))
        assertEquals(0, result.overflowCount)
    }
}

package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure-JVM tests for [applyCustomNames]. Pins the allocation optimization (empty
 * map / no-name apps are not copied) alongside the name-resolution behavior.
 */
class ApplyCustomNamesTest {

    private fun app(pkg: String, name: String) =
        AppInfo(originalName = name, displayName = name, packageName = pkg, className = "$pkg.Main")

    @Test
    fun `empty custom-names map returns the input list unchanged (same reference)`() {
        val apps = listOf(app("com.a", "A"), app("com.b", "B"))
        // Same reference: no per-element copy, no new backing list — the common
        // path for a user with no custom names.
        assertSame(apps, applyCustomNames(apps, emptyMap()))
    }

    @Test
    fun `an app without a custom name is returned as the same instance (no copy)`() {
        val a = app("com.a", "A")
        val b = app("com.b", "B")
        val result = applyCustomNames(listOf(a, b), mapOf("com.b" to "Bee"))
        assertSame(a, result[0])
        assertEquals("Bee", result[1].displayName)
    }

    @Test
    fun `a custom name overrides displayName but keeps originalName`() {
        val result = applyCustomNames(listOf(app("com.a", "Alpha")), mapOf("com.a" to "Renamed"))
        assertEquals("Renamed", result[0].displayName)
        assertEquals("Alpha", result[0].originalName)
    }
}

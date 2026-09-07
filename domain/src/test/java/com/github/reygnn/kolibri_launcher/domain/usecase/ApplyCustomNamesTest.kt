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

    @Test
    fun `a package with two launcher activities renames both (keyed by package, not component)`() {
        // Custom names are keyed by packageName, so a package exposing two launcher
        // activities gets BOTH entries renamed. A regression to a component-keyed
        // lookup would rename only one — this pins the per-package semantics.
        val a = AppInfo("Dialer", "Dialer", "com.dual", "com.dual.A")
        val b = AppInfo("Dialer", "Dialer", "com.dual", "com.dual.B")

        val result = applyCustomNames(listOf(a, b), mapOf("com.dual" to "Phone"))

        assertEquals(listOf("Phone", "Phone"), result.map { it.displayName })
        // Distinct components survive; only the display name changed.
        assertEquals(
            listOf("com.dual/com.dual.A", "com.dual/com.dual.B"),
            result.map { it.componentName },
        )
        assertEquals(listOf("Dialer", "Dialer"), result.map { it.originalName })
    }

    @Test
    fun `a custom name for a package not in the list is ignored`() {
        // A stale custom name (e.g. the app was uninstalled) must not inject a phantom
        // entry or throw — the map is looked up per present app, never iterated. The
        // untouched app is returned as the same instance.
        val a = app("com.a", "A")

        val result = applyCustomNames(listOf(a), mapOf("com.gone" to "Ghost"))

        assertEquals(1, result.size)
        assertSame(a, result[0])
    }
}

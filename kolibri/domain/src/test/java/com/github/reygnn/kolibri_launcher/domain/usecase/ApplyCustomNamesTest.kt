package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.launcher.core.AppInfo
import com.google.common.truth.Truth.assertThat
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
        assertThat(applyCustomNames(apps, emptyMap())).isSameInstanceAs(apps)
    }

    @Test
    fun `an app without a custom name is returned as the same instance (no copy)`() {
        val a = app("com.a", "A")
        val b = app("com.b", "B")
        val result = applyCustomNames(listOf(a, b), mapOf("com.b" to "Bee"))
        assertThat(result[0]).isSameInstanceAs(a)
        assertThat(result[1].displayName).isEqualTo("Bee")
    }

    @Test
    fun `a custom name overrides displayName but keeps originalName`() {
        val result = applyCustomNames(listOf(app("com.a", "Alpha")), mapOf("com.a" to "Renamed"))
        assertThat(result[0].displayName).isEqualTo("Renamed")
        assertThat(result[0].originalName).isEqualTo("Alpha")
    }

    @Test
    fun `a package with two launcher activities renames both (keyed by package, not component)`() {
        // Custom names are keyed by packageName, so a package exposing two launcher
        // activities gets BOTH entries renamed. A regression to a component-keyed
        // lookup would rename only one — this pins the per-package semantics.
        val a = AppInfo("Dialer", "Dialer", "com.dual", "com.dual.A")
        val b = AppInfo("Dialer", "Dialer", "com.dual", "com.dual.B")

        val result = applyCustomNames(listOf(a, b), mapOf("com.dual" to "Phone"))

        assertThat(result.map { it.displayName }).isEqualTo(listOf("Phone", "Phone"))
        // Distinct components survive; only the display name changed.
        assertThat(result.map { it.componentName }).isEqualTo(listOf("com.dual/com.dual.A", "com.dual/com.dual.B"))
        assertThat(result.map { it.originalName }).isEqualTo(listOf("Dialer", "Dialer"))
    }

    @Test
    fun `a custom name for a package not in the list is ignored`() {
        // A stale custom name (e.g. the app was uninstalled) must not inject a phantom
        // entry or throw — the map is looked up per present app, never iterated. The
        // untouched app is returned as the same instance.
        val a = app("com.a", "A")

        val result = applyCustomNames(listOf(a), mapOf("com.gone" to "Ghost"))

        assertThat(result.size).isEqualTo(1)
        assertThat(result[0]).isSameInstanceAs(a)
    }
}

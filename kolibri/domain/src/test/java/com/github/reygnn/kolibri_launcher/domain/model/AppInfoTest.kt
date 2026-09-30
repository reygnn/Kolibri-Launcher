package com.github.reygnn.kolibri_launcher.domain.model

import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.ComponentKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards for the precomputed [AppInfo.displayNameLower] sort key (AUDIT-14 Nit §208)
 * and the precomputed [AppInfo.componentName] identity (AUDIT-14 Nit §212).
 */
class AppInfoTest {

    private fun appInfo(displayName: String) = AppInfo(
        originalName = displayName,
        displayName = displayName,
        packageName = "com.example",
        className = "MainActivity",
    )

    @Test
    fun `displayNameLower equals the locale-invariant lowercase of displayName`() {
        val app = appInfo("Camera ABC")
        assertThat(app.displayNameLower).isEqualTo("camera abc")
        assertThat(app.displayNameLower).isEqualTo(app.displayName.lowercase())
    }

    @Test
    fun `displayNameLower is recomputed on copy when displayName changes`() {
        val renamed = appInfo("Original").copy(displayName = "Renamed VALUE")
        // If the key were a constructor default instead of a body val, copy would
        // carry the stale "original" key — this pins the recompute.
        assertThat(renamed.displayNameLower).isEqualTo("renamed value")
    }

    @Test
    fun `displayNameLower is excluded from equals so distinctUntilChanged is unaffected`() {
        // Same constructor args -> equal, even though the derived key also matches.
        val a = appInfo("App")
        val b = appInfo("App")
        assertThat(b).isEqualTo(a)
        assertThat(b.hashCode()).isEqualTo(a.hashCode())

        // Different displayName -> not equal (sanity that equals still keys on it).
        assertThat(appInfo("Other")).isNotEqualTo(a)
    }

    @Test
    fun `componentName is cached - repeated reads return the same instance`() {
        val app = appInfo("Camera")
        // Body val, not a getter: the former getter allocated a new String per
        // read; the cache returns one instance (AUDIT-14 Nit §212).
        assertThat(app.componentName).isSameInstanceAs(app.componentName)
    }

    @Test
    fun `componentName normalizes a short-form className to the long form`() {
        val shortForm = AppInfo(
            originalName = "X",
            displayName = "X",
            packageName = "com.example",
            className = ".MainActivity",
        )
        assertThat(shortForm.componentName).isEqualTo("com.example/com.example.MainActivity")
    }

    @Test
    fun `componentName leaves a long-form className unchanged`() {
        val longForm = AppInfo(
            originalName = "X",
            displayName = "X",
            packageName = "com.example",
            className = "com.other.Activity",
        )
        assertThat(longForm.componentName).isEqualTo("com.example/com.other.Activity")
    }

    @Test
    fun `componentName is excluded from equals`() {
        // Two identical instances are equal and their derived componentName matches;
        // componentName is not a constructor param, so it never affects equals.
        val a = appInfo("App")
        val b = appInfo("App")
        assertThat(b).isEqualTo(a)
        assertThat(b.componentName).isEqualTo(a.componentName)
    }

    @Test
    fun `normalizedClassName expands a short-form className to the long form`() {
        val shortForm = AppInfo(
            originalName = "X",
            displayName = "X",
            packageName = "com.example",
            className = ".MainActivity",
        )
        // The launcher builds its ComponentName from this — a relative spelling
        // must be expanded, or the launch would not resolve against the manifest.
        assertThat(shortForm.normalizedClassName).isEqualTo("com.example.MainActivity")
    }

    @Test
    fun `normalizedClassName leaves a long-form className unchanged`() {
        val longForm = AppInfo(
            originalName = "X",
            displayName = "X",
            packageName = "com.example",
            className = "com.other.Activity",
        )
        assertThat(longForm.normalizedClassName).isEqualTo("com.other.Activity")
    }

    @Test
    fun `componentName is derived from normalizedClassName so launch and identity agree`() {
        // The single normalization source: componentName must equal
        // "package/normalizedClassName" for both the relative and the
        // fully-qualified spelling — otherwise the launcher (which uses
        // normalizedClassName) could target a different component than identity.
        val shortForm = AppInfo(
            originalName = "X",
            displayName = "X",
            packageName = "com.example",
            className = ".MainActivity",
        )
        assertThat(shortForm.componentName).isEqualTo("com.example/${shortForm.normalizedClassName}")
        assertThat(shortForm.componentName).isEqualTo("com.example/com.example.MainActivity")
    }

    @Test
    fun `normalizedClassName is cached - repeated reads return the same instance`() {
        val app = appInfo("Camera")
        assertThat(app.normalizedClassName).isSameInstanceAs(app.normalizedClassName)
    }

    @Test
    fun `normalizedClassName is excluded from equals`() {
        // Body val, not a constructor param, so it never affects equals — same
        // guarantee as displayNameLower / componentName.
        val a = appInfo("App")
        val b = appInfo("App")
        assertThat(b).isEqualTo(a)
        assertThat(b.normalizedClassName).isEqualTo(a.normalizedClassName)
    }

    // --- structured key (ComponentKey) + the projection drift anchor ---

    @Test
    fun `componentName is exactly the flat projection of key`() {
        // THE drift anchor: componentName must be byte-for-byte key.flat, so the
        // structured-identity refactor cannot shift the string that every
        // component-keyed store and DiffUtil identity relies on.
        val app = appInfo("Camera")
        assertThat(app.componentName).isEqualTo(app.key.flat)
        assertThat(app.componentName).isEqualTo("com.example/MainActivity")
    }

    @Test
    fun `key carries the normalized long-form class name`() {
        val shortForm = AppInfo(
            originalName = "X",
            displayName = "X",
            packageName = "com.example",
            className = ".MainActivity",
        )
        // Identity uses the same normalization source as the launcher, so key,
        // componentName and normalizedClassName never disagree.
        assertThat(shortForm.key).isEqualTo(ComponentKey.of("com.example", "com.example.MainActivity"))
        assertThat(shortForm.key.flat).isEqualTo("com.example/com.example.MainActivity")
    }

    @Test
    fun `key is cached - repeated reads return the same instance`() {
        val app = appInfo("Camera")
        assertThat(app.key).isSameInstanceAs(app.key)
    }

    @Test
    fun `key is excluded from equals`() {
        // Body val, not a constructor param — like displayNameLower / componentName
        // it must never affect equals, or Flow distinctUntilChanged would change.
        val a = appInfo("App")
        val b = appInfo("App")
        assertThat(b).isEqualTo(a)
        assertThat(b.key).isEqualTo(a.key)
    }
}

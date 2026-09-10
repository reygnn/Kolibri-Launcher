package com.github.reygnn.kolibri_launcher.domain.model

import com.github.reygnn.kolibri_launcher.core.ComponentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
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
        assertEquals("camera abc", app.displayNameLower)
        assertEquals(app.displayName.lowercase(), app.displayNameLower)
    }

    @Test
    fun `displayNameLower is recomputed on copy when displayName changes`() {
        val renamed = appInfo("Original").copy(displayName = "Renamed VALUE")
        // If the key were a constructor default instead of a body val, copy would
        // carry the stale "original" key — this pins the recompute.
        assertEquals("renamed value", renamed.displayNameLower)
    }

    @Test
    fun `displayNameLower is excluded from equals so distinctUntilChanged is unaffected`() {
        // Same constructor args -> equal, even though the derived key also matches.
        val a = appInfo("App")
        val b = appInfo("App")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        // Different displayName -> not equal (sanity that equals still keys on it).
        assertNotEquals(a, appInfo("Other"))
    }

    @Test
    fun `componentName is cached - repeated reads return the same instance`() {
        val app = appInfo("Camera")
        // Body val, not a getter: the former getter allocated a new String per
        // read; the cache returns one instance (AUDIT-14 Nit §212).
        assertSame(app.componentName, app.componentName)
    }

    @Test
    fun `componentName normalizes a short-form className to the long form`() {
        val shortForm = AppInfo(
            originalName = "X",
            displayName = "X",
            packageName = "com.example",
            className = ".MainActivity",
        )
        assertEquals("com.example/com.example.MainActivity", shortForm.componentName)
    }

    @Test
    fun `componentName leaves a long-form className unchanged`() {
        val longForm = AppInfo(
            originalName = "X",
            displayName = "X",
            packageName = "com.example",
            className = "com.other.Activity",
        )
        assertEquals("com.example/com.other.Activity", longForm.componentName)
    }

    @Test
    fun `componentName is excluded from equals`() {
        // Two identical instances are equal and their derived componentName matches;
        // componentName is not a constructor param, so it never affects equals.
        val a = appInfo("App")
        val b = appInfo("App")
        assertEquals(a, b)
        assertEquals(a.componentName, b.componentName)
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
        assertEquals("com.example.MainActivity", shortForm.normalizedClassName)
    }

    @Test
    fun `normalizedClassName leaves a long-form className unchanged`() {
        val longForm = AppInfo(
            originalName = "X",
            displayName = "X",
            packageName = "com.example",
            className = "com.other.Activity",
        )
        assertEquals("com.other.Activity", longForm.normalizedClassName)
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
        assertEquals(
            "com.example/${shortForm.normalizedClassName}",
            shortForm.componentName,
        )
        assertEquals("com.example/com.example.MainActivity", shortForm.componentName)
    }

    @Test
    fun `normalizedClassName is cached - repeated reads return the same instance`() {
        val app = appInfo("Camera")
        assertSame(app.normalizedClassName, app.normalizedClassName)
    }

    @Test
    fun `normalizedClassName is excluded from equals`() {
        // Body val, not a constructor param, so it never affects equals — same
        // guarantee as displayNameLower / componentName.
        val a = appInfo("App")
        val b = appInfo("App")
        assertEquals(a, b)
        assertEquals(a.normalizedClassName, b.normalizedClassName)
    }

    // --- structured key (ComponentKey) + the projection drift anchor ---

    @Test
    fun `componentName is exactly the flat projection of key`() {
        // THE drift anchor: componentName must be byte-for-byte key.flat, so the
        // structured-identity refactor cannot shift the string that every
        // component-keyed store and DiffUtil identity relies on.
        val app = appInfo("Camera")
        assertEquals(app.key.flat, app.componentName)
        assertEquals("com.example/MainActivity", app.componentName)
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
        assertEquals(
            ComponentKey("com.example", "com.example.MainActivity"),
            shortForm.key,
        )
        assertEquals("com.example/com.example.MainActivity", shortForm.key.flat)
    }

    @Test
    fun `key is cached - repeated reads return the same instance`() {
        val app = appInfo("Camera")
        assertSame(app.key, app.key)
    }

    @Test
    fun `key is excluded from equals`() {
        // Body val, not a constructor param — like displayNameLower / componentName
        // it must never affect equals, or Flow distinctUntilChanged would change.
        val a = appInfo("App")
        val b = appInfo("App")
        assertEquals(a, b)
        assertEquals(a.key, b.key)
    }
}

package com.github.reygnn.kolibri_launcher.domain.model

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
}

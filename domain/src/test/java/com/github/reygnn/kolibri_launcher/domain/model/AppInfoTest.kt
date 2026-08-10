package com.github.reygnn.kolibri_launcher.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Guards for the precomputed [AppInfo.displayNameLower] sort key (AUDIT-14 Nit §208).
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
}

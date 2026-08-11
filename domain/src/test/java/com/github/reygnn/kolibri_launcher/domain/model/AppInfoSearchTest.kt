package com.github.reygnn.kolibri_launcher.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [filterByName] — the shared display-name search predicate that
 * the drawer's AppSearchFilter and the four settings ViewModels all call.
 */
class AppInfoSearchTest {

    private fun app(display: String, original: String = display, pkg: String = display) =
        AppInfo(originalName = original, displayName = display, packageName = pkg, className = "C")

    private val camera = app("Camera")
    private val calendar = app("Calendar")
    // Custom-named: displayName differs from originalName.
    private val renamedPhone = app(display = "Dialer", original = "Phone", pkg = "com.phone")
    private val apps = listOf(camera, calendar, renamedPhone)

    @Test
    fun `blank query returns the receiver unchanged`() {
        assertSame(apps, apps.filterByName(""))
        assertSame(apps, apps.filterByName("   "))
    }

    @Test
    fun `matches displayName case-insensitively`() {
        assertEquals(listOf(camera), apps.filterByName("cam"))
        assertEquals(listOf(camera), apps.filterByName("CAMERA"))
    }

    @Test
    fun `matches a shared prefix across multiple apps`() {
        assertEquals(listOf(camera, calendar), apps.filterByName("ca"))
    }

    @Test
    fun `no match returns an empty list`() {
        assertTrue(apps.filterByName("zzz").isEmpty())
    }

    @Test
    fun `without includeOriginalName the originalName is not matched`() {
        // "Phone" is renamedPhone's originalName; displayName is "Dialer".
        assertTrue(apps.filterByName("phone").isEmpty())
    }

    @Test
    fun `with includeOriginalName the originalName is matched for custom-named apps`() {
        assertEquals(listOf(renamedPhone), apps.filterByName("phone", includeOriginalName = true))
        // displayName still matches too.
        assertEquals(listOf(renamedPhone), apps.filterByName("dialer", includeOriginalName = true))
    }

    @Test
    fun `includeOriginalName does not duplicate an app that matches both names`() {
        // "a" is in displayName (Camera/Calendar/Dialer) — the OR must not emit twice.
        val result = apps.filterByName("a", includeOriginalName = true)
        assertEquals(result.distinct(), result)
    }
}

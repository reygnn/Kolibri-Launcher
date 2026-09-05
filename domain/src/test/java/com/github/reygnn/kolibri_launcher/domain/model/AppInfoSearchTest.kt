package com.github.reygnn.kolibri_launcher.domain.model

import com.github.reygnn.kolibri_launcher.support.FormattingTestSupport.withDefaultLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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

    @Test
    fun `search is locale-invariant under the Turkish locale (dotless-i)`() {
        // Regression guard for the Turkish-i trap: a locale-sensitive lowercase
        // folds 'I' to dotless 'ı' under tr-TR, so "Instagram" would stop matching
        // the query "instagram". filterByName and AppInfo.displayNameLower both use
        // the locale-invariant Kotlin lowercase(), so the match must survive a
        // Turkish default locale. The AppInfo is built INSIDE the block so a
        // regression to a locale-sensitive fold on the name side is also caught.
        withDefaultLocale(Locale.forLanguageTag("tr-TR")) {
            val instagram = app("Instagram")
            val apps = listOf(instagram)
            assertEquals(listOf(instagram), apps.filterByName("instagram"))
            assertEquals(listOf(instagram), apps.filterByName("INSTAGRAM"))
            // An uppercase-I query must fold to the same key as the app name.
            assertEquals(listOf(instagram), apps.filterByName("I"))
        }
    }
}

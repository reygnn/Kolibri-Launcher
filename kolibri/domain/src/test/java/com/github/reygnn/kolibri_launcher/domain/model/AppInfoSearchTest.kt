package com.github.reygnn.kolibri_launcher.domain.model

import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.kolibri_launcher.support.FormattingTestSupport.withDefaultLocale
import com.google.common.truth.Truth.assertThat
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
        assertThat(apps.filterByName("")).isSameInstanceAs(apps)
        assertThat(apps.filterByName("   ")).isSameInstanceAs(apps)
    }

    @Test
    fun `matches displayName case-insensitively`() {
        assertThat(apps.filterByName("cam")).isEqualTo(listOf(camera))
        assertThat(apps.filterByName("CAMERA")).isEqualTo(listOf(camera))
    }

    @Test
    fun `matches a shared prefix across multiple apps`() {
        assertThat(apps.filterByName("ca")).isEqualTo(listOf(camera, calendar))
    }

    @Test
    fun `no match returns an empty list`() {
        assertThat(apps.filterByName("zzz").isEmpty()).isTrue()
    }

    @Test
    fun `without includeOriginalName the originalName is not matched`() {
        // "Phone" is renamedPhone's originalName; displayName is "Dialer".
        assertThat(apps.filterByName("phone").isEmpty()).isTrue()
    }

    @Test
    fun `with includeOriginalName the originalName is matched for custom-named apps`() {
        assertThat(apps.filterByName("phone", includeOriginalName = true)).isEqualTo(listOf(renamedPhone))
        // displayName still matches too.
        assertThat(apps.filterByName("dialer", includeOriginalName = true)).isEqualTo(listOf(renamedPhone))
    }

    @Test
    fun `includeOriginalName does not duplicate an app that matches both names`() {
        // "a" is in displayName (Camera/Calendar/Dialer) — the OR must not emit twice.
        val result = apps.filterByName("a", includeOriginalName = true)
        assertThat(result).isEqualTo(result.distinct())
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
            assertThat(apps.filterByName("instagram")).isEqualTo(listOf(instagram))
            assertThat(apps.filterByName("INSTAGRAM")).isEqualTo(listOf(instagram))
            // An uppercase-I query must fold to the same key as the app name.
            assertThat(apps.filterByName("I")).isEqualTo(listOf(instagram))
        }
    }
}

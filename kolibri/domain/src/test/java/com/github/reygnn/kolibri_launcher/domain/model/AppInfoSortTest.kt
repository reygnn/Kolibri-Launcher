package com.github.reygnn.kolibri_launcher.domain.model

import com.github.reygnn.kolibri_launcher.support.FormattingTestSupport.withDefaultLocale
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Unit tests for [sortedByDisplayName] — the shared drawer/onboarding/favorites-
 * fallback ordering used across the app. It sorts by [AppInfo.displayNameLower],
 * whose key is built with the locale-INVARIANT Kotlin `lowercase()` (AUDIT-14
 * Nit §208). Two of its properties were documented but unpinned: the sort had
 * no dedicated test, and the two call-site tests (CustomNamesShapingTest,
 * ToOnboardingPickerTest) only assert that it is *applied*, not how it orders.
 *
 * These pin the three things a refactor could silently break:
 *  1. case-insensitivity (dropping `.lowercase()` clusters capitalised names first),
 *  2. stability on ties (two apps with the same name keep input order), and
 *  3. locale-invariance — the sibling of AppInfoSearchTest's Turkish guard, for
 *     the SORT path, which had none.
 */
class AppInfoSortTest {

    private fun app(display: String, pkg: String = display) =
        AppInfo(originalName = display, displayName = display, packageName = pkg, className = "C")

    @Test
    fun `sorts case-insensitively by display name`() {
        // Key is displayNameLower, so capitalisation must not cluster capitalised
        // names ahead of lowercase ones — which a raw displayName sort ('A'=0x41 <
        // 'b'=0x62) would do, putting "Cherry" before "banana".
        val apps = listOf(app("banana"), app("Apple"), app("Cherry"))

        assertEquals(
            listOf("Apple", "banana", "Cherry"),
            apps.sortedByDisplayName().map { it.displayName },
        )
    }

    @Test
    fun `is stable on ties - equal display names keep input order`() {
        // A system + OEM "Settings" differ only by package. sortedBy is stable, so
        // relative input order is preserved — relied on for a deterministic list.
        val system = app("Settings", pkg = "com.android.settings")
        val oem = app("Settings", pkg = "com.oem.settings")

        assertEquals(
            listOf("com.android.settings", "com.oem.settings"),
            listOf(system, oem).sortedByDisplayName().map { it.packageName },
        )
        // Reverse input → reverse output: proves stability, not a lucky pre-order.
        assertEquals(
            listOf("com.oem.settings", "com.android.settings"),
            listOf(oem, system).sortedByDisplayName().map { it.packageName },
        )
    }

    @Test
    fun `sort is locale-invariant under the Turkish locale (dotless-i)`() {
        // Sibling of AppInfoSearchTest's Turkish guard, for the SORT path. The sort
        // key AppInfo.displayNameLower uses the locale-invariant Kotlin lowercase();
        // a regression to a locale-sensitive fold would, under tr-TR, lowercase 'I'
        // to dotless 'ı' (U+0131, which sorts AFTER 'j'), moving "Instagram" behind
        // "Jodel". Verified empirically: invariant → [Adblock, Instagram, Jodel];
        // a tr-TR fold → [Adblock, Jodel, Instagram]. The AppInfos are built INSIDE
        // the block, in a deliberately non-alphabetical input order, so a
        // locale-sensitive fold on the key side is caught.
        withDefaultLocale(Locale.forLanguageTag("tr-TR")) {
            val apps = listOf(app("Jodel"), app("Instagram"), app("Adblock"))

            assertEquals(
                listOf("Adblock", "Instagram", "Jodel"),
                apps.sortedByDisplayName().map { it.displayName },
            )
        }
    }

    @Test
    fun `empty list sorts to empty`() {
        assertEquals(emptyList<AppInfo>(), emptyList<AppInfo>().sortedByDisplayName())
    }
}

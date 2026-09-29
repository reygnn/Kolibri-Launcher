package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.appdrawer.AppSearchFilter
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class AppSearchFilterTest {

    @get:Rule
    val timberRule = TimberRule()

    private val filter = AppSearchFilter()

    // Dummy Apps helper
    private val appA = createApp("Alpha")
    private val appB = createApp("Beta")
    private val appC = createApp("Gamma")
    private val list = listOf(appA, appB, appC)

    private fun createApp(name: String) = AppInfo(
        packageName = "com.$name",
        className = "cls",
        displayName = name,
        originalName = name
    )

    // ========== FILTER LOGIC ==========

    @Test
    fun `empty query returns full list`() {
        val result = filter.filterAndDecide(list, "", false)
        assertThat(result).isInstanceOf(AppSearchFilter.FilterResult.ShowList::class.java)
        assertThat((result as AppSearchFilter.FilterResult.ShowList).apps.size).isEqualTo(3)
    }

    @Test
    fun `query finds single match`() {
        val result = filter.filterAndDecide(list, "Alpha", false)
        assertThat(result).isInstanceOf(AppSearchFilter.FilterResult.ShowList::class.java)
        val apps = (result as AppSearchFilter.FilterResult.ShowList).apps
        assertThat(apps.size).isEqualTo(1)
        assertThat(apps[0].displayName).isEqualTo("Alpha")
    }

    @Test
    fun `query is case insensitive`() {
        val result = filter.filterAndDecide(list, "alpha", false) // klein geschrieben
        val apps = (result as AppSearchFilter.FilterResult.ShowList).apps
        assertThat(apps.size).isEqualTo(1)
    }

    @Test
    fun `query finds no match returns empty list`() {
        val result = filter.filterAndDecide(list, "Zebra", false)
        val apps = (result as AppSearchFilter.FilterResult.ShowList).apps
        assertThat(apps.isEmpty()).isTrue()
    }

    // ========== AUTO LAUNCH LOGIC ==========

    @Test
    fun `auto launch triggered when enabled and single match`() {
        val result = filter.filterAndDecide(list, "Alpha", true) // Enabled!
        assertThat(result).isInstanceOf(AppSearchFilter.FilterResult.AutoLaunch::class.java)
        assertThat((result as AppSearchFilter.FilterResult.AutoLaunch).app.displayName).isEqualTo("Alpha")
    }

    @Test
    fun `auto launch NOT triggered if disabled`() {
        val result = filter.filterAndDecide(list, "Alpha", false) // Disabled!
        assertThat(result).isInstanceOf(AppSearchFilter.FilterResult.ShowList::class.java)
    }

    @Test
    fun `auto launch NOT triggered if multiple matches`() {
        val listWithTwoAlphas = listOf(createApp("Alpha1"), createApp("Alpha2"))
        val result = filter.filterAndDecide(listWithTwoAlphas, "Alpha", true)

        // 2 Treffer -> Kein AutoLaunch, User muss wählen
        assertThat(result).isInstanceOf(AppSearchFilter.FilterResult.ShowList::class.java)
        assertThat((result as AppSearchFilter.FilterResult.ShowList).apps.size).isEqualTo(2)
    }

    // ========== PARANOID EDGE CASES ==========

    @Test
    fun `auto launch NOT triggered on empty query even if only 1 app installed`() {
        // Szenario: User hat nur 1 App installiert und öffnet Drawer.
        // App darf NICHT sofort starten!
        val singleAppList = listOf(appA)

        val result = filter.filterAndDecide(singleAppList, "", true)

        assertThat(result).isInstanceOf(AppSearchFilter.FilterResult.ShowList::class.java)
        assertThat((result as AppSearchFilter.FilterResult.ShowList).apps.size).isEqualTo(1)
    }

    @Test
    fun `handles empty input list gracefully`() {
        val result = filter.filterAndDecide(emptyList(), "Search", true)
        val apps = (result as AppSearchFilter.FilterResult.ShowList).apps
        assertThat(apps.isEmpty()).isTrue()
    }
}
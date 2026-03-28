package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import com.github.reygnn.kolibri_launcher.ui.appdrawer.AppSearchFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertTrue(result is AppSearchFilter.FilterResult.ShowList)
        assertEquals(3, (result as AppSearchFilter.FilterResult.ShowList).apps.size)
    }

    @Test
    fun `query finds single match`() {
        val result = filter.filterAndDecide(list, "Alpha", false)
        assertTrue(result is AppSearchFilter.FilterResult.ShowList)
        val apps = (result as AppSearchFilter.FilterResult.ShowList).apps
        assertEquals(1, apps.size)
        assertEquals("Alpha", apps[0].displayName)
    }

    @Test
    fun `query is case insensitive`() {
        val result = filter.filterAndDecide(list, "alpha", false) // klein geschrieben
        val apps = (result as AppSearchFilter.FilterResult.ShowList).apps
        assertEquals(1, apps.size)
    }

    @Test
    fun `query finds no match returns empty list`() {
        val result = filter.filterAndDecide(list, "Zebra", false)
        val apps = (result as AppSearchFilter.FilterResult.ShowList).apps
        assertTrue(apps.isEmpty())
    }

    // ========== AUTO LAUNCH LOGIC ==========

    @Test
    fun `auto launch triggered when enabled and single match`() {
        val result = filter.filterAndDecide(list, "Alpha", true) // Enabled!
        assertTrue(result is AppSearchFilter.FilterResult.AutoLaunch)
        assertEquals("Alpha", (result as AppSearchFilter.FilterResult.AutoLaunch).app.displayName)
    }

    @Test
    fun `auto launch NOT triggered if disabled`() {
        val result = filter.filterAndDecide(list, "Alpha", false) // Disabled!
        assertTrue(result is AppSearchFilter.FilterResult.ShowList)
    }

    @Test
    fun `auto launch NOT triggered if multiple matches`() {
        val listWithTwoAlphas = listOf(createApp("Alpha1"), createApp("Alpha2"))
        val result = filter.filterAndDecide(listWithTwoAlphas, "Alpha", true)

        // 2 Treffer -> Kein AutoLaunch, User muss wählen
        assertTrue(result is AppSearchFilter.FilterResult.ShowList)
        assertEquals(2, (result as AppSearchFilter.FilterResult.ShowList).apps.size)
    }

    // ========== PARANOID EDGE CASES ==========

    @Test
    fun `auto launch NOT triggered on empty query even if only 1 app installed`() {
        // Szenario: User hat nur 1 App installiert und öffnet Drawer.
        // App darf NICHT sofort starten!
        val singleAppList = listOf(appA)

        val result = filter.filterAndDecide(singleAppList, "", true)

        assertTrue(result is AppSearchFilter.FilterResult.ShowList)
        assertEquals(1, (result as AppSearchFilter.FilterResult.ShowList).apps.size)
    }

    @Test
    fun `handles empty input list gracefully`() {
        val result = filter.filterAndDecide(emptyList(), "Search", true)
        val apps = (result as AppSearchFilter.FilterResult.ShowList).apps
        assertTrue(apps.isEmpty())
    }
}
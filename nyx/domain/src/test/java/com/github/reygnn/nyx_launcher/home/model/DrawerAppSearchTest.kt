package com.github.reygnn.nyx_launcher.home.model

import com.github.reygnn.launcher.core.ComponentKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pins the drawer-search decision engine: filtered list vs. single-match auto-launch. */
class DrawerAppSearchTest {

    private fun app(label: String) =
        LauncherApp(ComponentKey("com.$label", "com.$label.Main"), label)

    private val all = listOf(app("Camera"), app("Calendar"), app("Phone"))

    @Test
    fun `blank query shows the full list, never auto-launches`() {
        val result = DrawerAppSearch.filterAndDecide(all, "", isAutoLaunchEnabled = true)
        assertThat(result).isInstanceOf(DrawerSearchResult.ShowList::class.java)
        assertThat((result as DrawerSearchResult.ShowList).apps).isEqualTo(all)
    }

    @Test
    fun `multi-match query shows the filtered list`() {
        val result = DrawerAppSearch.filterAndDecide(all, "ca", isAutoLaunchEnabled = true)
        assertThat(result).isInstanceOf(DrawerSearchResult.ShowList::class.java)
        assertThat((result as DrawerSearchResult.ShowList).apps.map { it.displayName })
            .containsExactly("Camera", "Calendar")
    }

    @Test
    fun `single match auto-launches when enabled`() {
        val result = DrawerAppSearch.filterAndDecide(all, "phone", isAutoLaunchEnabled = true)
        assertThat(result).isInstanceOf(DrawerSearchResult.AutoLaunch::class.java)
        assertThat((result as DrawerSearchResult.AutoLaunch).app.displayName).isEqualTo("Phone")
    }

    @Test
    fun `single match shows the list when auto-launch disabled`() {
        val result = DrawerAppSearch.filterAndDecide(all, "phone", isAutoLaunchEnabled = false)
        assertThat(result).isInstanceOf(DrawerSearchResult.ShowList::class.java)
        assertThat((result as DrawerSearchResult.ShowList).apps.map { it.displayName })
            .containsExactly("Phone")
    }

    @Test
    fun `no match shows an empty list, never auto-launches`() {
        val result = DrawerAppSearch.filterAndDecide(all, "zzz", isAutoLaunchEnabled = true)
        assertThat(result).isEqualTo(DrawerSearchResult.ShowList(emptyList()))
    }
}

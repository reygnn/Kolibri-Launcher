package com.github.reygnn.nyx_launcher.home.model

import com.github.reygnn.launcher.core.ComponentKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pins the pure drawer-search predicate [filterByName] and the [displayName] rule. */
class LauncherAppSearchTest {

    private fun app(label: String, customName: String? = null) =
        LauncherApp(ComponentKey("com.$label", "com.$label.Main"), label, customName)

    @Test
    fun `display name is the label when no custom name`() {
        assertThat(app("Camera").displayName).isEqualTo("Camera")
    }

    @Test
    fun `display name is the custom name when set`() {
        assertThat(app("com.acme.cam", customName = "Snap").displayName).isEqualTo("Snap")
    }

    @Test
    fun `blank query returns the receiver unchanged`() {
        val apps = listOf(app("Camera"), app("Calendar"))
        assertThat(apps.filterByName("")).isEqualTo(apps)
        assertThat(apps.filterByName("   ")).isEqualTo(apps)
    }

    @Test
    fun `filter is case-insensitive and substring-based`() {
        val apps = listOf(app("Camera"), app("Calendar"), app("Phone"))
        assertThat(apps.filterByName("ca").map { it.displayName })
            .containsExactly("Camera", "Calendar")
    }

    @Test
    fun `filter matches on the custom name, not the raw label`() {
        val apps = listOf(app("com.acme.messenger", customName = "Chat"))
        assertThat(apps.filterByName("chat")).hasSize(1)
        assertThat(apps.filterByName("messenger")).isEmpty()
    }

    @Test
    fun `no match yields empty list`() {
        assertThat(listOf(app("Camera")).filterByName("zzz")).isEmpty()
    }
}

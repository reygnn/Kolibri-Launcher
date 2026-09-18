package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins [hasNotificationDot]: an app cell matches its own package, a folder cell
 * matches if any member does, and an empty cell never shows a dot.
 */
class HomeCellDotTest {

    private fun key(pkg: String) = ComponentKey(pkg, "$pkg.Main")

    @Test
    fun `empty cell never has a dot`() {
        assertThat(HomeCell.Empty.hasNotificationDot(setOf("com.a"))).isFalse()
    }

    @Test
    fun `app cell matches its own package`() {
        val cell = HomeCell.App(ItemId("1"), key("com.a"))
        assertThat(cell.hasNotificationDot(setOf("com.a"))).isTrue()
        assertThat(cell.hasNotificationDot(setOf("com.b"))).isFalse()
    }

    @Test
    fun `folder cell matches if any member has a dot`() {
        val cell = HomeCell.Folder(ItemId("f"), listOf(key("com.a"), key("com.b")))
        assertThat(cell.hasNotificationDot(setOf("com.b"))).isTrue()
        assertThat(cell.hasNotificationDot(setOf("com.z"))).isFalse()
    }

    @Test
    fun `folder cell with no matching member has no dot`() {
        val cell = HomeCell.Folder(ItemId("f"), listOf(key("com.a")))
        assertThat(cell.hasNotificationDot(emptySet())).isFalse()
    }
}

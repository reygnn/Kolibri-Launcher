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

    private fun key(pkg: String) = ComponentKey.of(pkg, "$pkg.Main")

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

    @Test
    fun `folder dot ignores an uninstalled dot-carrying member (presentMembers, not members)`() {
        // com.b carries a dot but is uninstalled (absent from presentMembers) — the greyed
        // composite must not show a dot for an app that can't notify.
        val cell = HomeCell.Folder(
            ItemId("f"),
            members = listOf(key("com.a"), key("com.b")),
            presentMembers = listOf(key("com.a")),
        )
        assertThat(cell.hasNotificationDot(setOf("com.b"))).isFalse()
        // A present member with a dot still shows one.
        assertThat(cell.hasNotificationDot(setOf("com.a"))).isTrue()
    }

    // --- rendersPackage (targeted icon-repaint matching, F1) ---

    @Test
    fun `empty cell never renders a package`() {
        assertThat(HomeCell.Empty.rendersPackage("com.a")).isFalse()
    }

    @Test
    fun `app cell renders its own package only`() {
        val cell = HomeCell.App(ItemId("1"), key("com.a"))
        assertThat(cell.rendersPackage("com.a")).isTrue()
        assertThat(cell.rendersPackage("com.b")).isFalse()
    }

    @Test
    fun `folder cell renders a present member's package`() {
        val cell = HomeCell.Folder(ItemId("f"), listOf(key("com.a"), key("com.b")))
        assertThat(cell.rendersPackage("com.b")).isTrue()
        assertThat(cell.rendersPackage("com.z")).isFalse()
    }

    @Test
    fun `folder cell does not render an uninstalled member's package (presentMembers)`() {
        // com.b is a member but uninstalled (absent from presentMembers): the composite does not
        // draw it, so a com.b icon change must not repaint this folder tile.
        val cell = HomeCell.Folder(
            ItemId("f"),
            members = listOf(key("com.a"), key("com.b")),
            presentMembers = listOf(key("com.a")),
        )
        assertThat(cell.rendersPackage("com.b")).isFalse()
        assertThat(cell.rendersPackage("com.a")).isTrue()
    }
}

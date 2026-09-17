package com.github.reygnn.nyx_launcher.home.model

import com.github.reygnn.launcher.core.ComponentKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pins the pure vendor grouping used by the drawer-folder "add all from maker" action. */
class DrawerVendorGroupingTest {

    private fun app(pkg: String) = LauncherApp(ComponentKey(pkg, "$pkg.Main"), pkg.substringAfterLast('.'))

    @Test
    fun `groups by curated maker label`() {
        val groups = DrawerVendorGrouping.groups(
            listOf(app("com.google.android.gm"), app("com.google.android.youtube")),
        )
        assertThat(groups.map { it.label }).containsExactly("Google")
        assertThat(groups.single().keys).hasSize(2)
    }

    @Test
    fun `samsung and sec prefixes merge into one Samsung group`() {
        val groups = DrawerVendorGrouping.groups(
            listOf(app("com.samsung.android.dialer"), app("com.sec.android.gallery3d")),
        )
        assertThat(groups.map { it.label }).containsExactly("Samsung")
        assertThat(groups.single().keys).hasSize(2)
    }

    @Test
    fun `single-app vendor is dropped (needs at least two)`() {
        val groups = DrawerVendorGrouping.groups(
            listOf(app("com.google.android.gm"), app("com.google.android.youtube"), app("com.spotify.music")),
        )
        assertThat(groups.map { it.label }).containsExactly("Google")
    }

    @Test
    fun `unknown vendor falls back to the capitalised second segment`() {
        val groups = DrawerVendorGrouping.groups(
            listOf(app("com.spotify.music"), app("com.spotify.tv")),
        )
        assertThat(groups.map { it.label }).containsExactly("Spotify")
    }

    @Test
    fun `groups are sorted by label case-insensitively`() {
        val groups = DrawerVendorGrouping.groups(
            listOf(
                app("com.spotify.music"), app("com.spotify.tv"),
                app("com.amazon.a"), app("com.amazon.b"),
            ),
        )
        assertThat(groups.map { it.label }).containsExactly("Amazon", "Spotify").inOrder()
    }

    @Test
    fun `keys keep the incoming app order`() {
        val a = app("com.google.android.gm")
        val b = app("com.google.android.youtube")
        val groups = DrawerVendorGrouping.groups(listOf(a, b))
        assertThat(groups.single().keys).containsExactly(a.key, b.key).inOrder()
    }

    @Test
    fun `github namespace groups by author, not by the shared github segment`() {
        val groups = DrawerVendorGrouping.groups(
            listOf(
                app("com.github.reygnn.nyx_launcher"), app("com.github.reygnn.kolibri"),
                app("com.github.someoneelse.tool"), app("com.github.someoneelse.other"),
            ),
        )
        assertThat(groups.map { it.label })
            .containsExactly("github.reygnn", "github.someoneelse")
        assertThat(groups.first { it.label == "github.reygnn" }.keys).hasSize(2)
    }

    @Test
    fun `io github namespace folds into the same github author labelling`() {
        val groups = DrawerVendorGrouping.groups(
            listOf(app("io.github.foo.a"), app("io.github.foo.b")),
        )
        assertThat(groups.map { it.label }).containsExactly("github.foo")
    }

    @Test
    fun `empty input yields no groups`() {
        assertThat(DrawerVendorGrouping.groups(emptyList())).isEmpty()
    }
}

package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.launcher.core.ComponentKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for [drawerFolderLiveMembers] (§Audit-3 A3-04): an OPEN drawer-folder overlay
 * reconciles its members against the live installed set with the drawer projection's rule —
 * uninstalled members drop out (never greyed), a reinstall restores them, and below two live
 * members the folder has dissolved (null → the overlay closes).
 */
class DrawerFolderLiveMembersTest {

    private fun ck(p: String) = ComponentKey.of(p, "$p.Main")
    private val a = ck("com.a")
    private val b = ck("com.b")
    private val c = ck("com.c")

    @Test fun all_installed_keeps_every_member_in_order() {
        assertThat(drawerFolderLiveMembers(listOf(c, a, b), setOf(a, b, c)))
            .containsExactly(c, a, b).inOrder()
    }

    @Test fun an_uninstalled_member_is_dropped_not_kept_as_a_phantom() {
        assertThat(drawerFolderLiveMembers(listOf(a, b, c), setOf(a, c)))
            .containsExactly(a, c).inOrder()
    }

    @Test fun a_reinstalled_member_comes_back() {
        // Membership is never mutated by the reconcile, so the same list re-derives with b.
        val members = listOf(a, b, c)
        assertThat(drawerFolderLiveMembers(members, setOf(a, c))).containsExactly(a, c).inOrder()
        assertThat(drawerFolderLiveMembers(members, setOf(a, b, c))).containsExactly(a, b, c).inOrder()
    }

    @Test fun fewer_than_two_live_members_dissolves_the_folder() {
        assertThat(drawerFolderLiveMembers(listOf(a, b), setOf(a))).isNull()
        assertThat(drawerFolderLiveMembers(listOf(a, b), setOf(c))).isNull()
    }

    @Test fun an_empty_installed_set_means_not_loaded_and_filters_nothing() {
        // Load-bearing LazySlotMembership guard: a not-yet-loaded set must not dissolve the folder.
        assertThat(drawerFolderLiveMembers(listOf(a, b), emptySet())).containsExactly(a, b).inOrder()
    }

    @Test fun an_optimistic_bulk_add_shows_up_when_installed() {
        assertThat(drawerFolderLiveMembers(listOf(a, b) + c, setOf(a, b, c)))
            .containsExactly(a, b, c).inOrder()
    }
}

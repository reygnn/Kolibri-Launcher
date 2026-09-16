package com.github.reygnn.nyx_launcher.home.model

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.FolderMembership.RemoveResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the pure membership semantics shared by home and drawer folders
 * (DRAWER_FOLDERS_SPEC §7 / §11). No Android, no placement, no ids.
 */
class FolderMembershipTest {

    private fun key(p: String) = ComponentKey(p, "$p.Main")
    private val a = key("a")
    private val b = key("b")
    private val c = key("c")

    // ---- create ----

    @Test
    fun `create keeps target first then dragged (D-1 order)`() {
        assertThat(FolderMembership.create(target = a, dragged = b)).containsExactly(a, b).inOrder()
    }

    // ---- add ----

    @Test
    fun `add appends a new member preserving order`() {
        assertThat(FolderMembership.add(listOf(a, b), c)).containsExactly(a, b, c).inOrder()
    }

    @Test
    fun `add is a no-op when the member is already present`() {
        val members = listOf(a, b)
        assertThat(FolderMembership.add(members, a)).isEqualTo(members)
    }

    // ---- remove ----

    @Test
    fun `remove from a folder with 3 members shrinks it and drops only that member`() {
        val result = FolderMembership.remove(listOf(a, b, c), b)
        assertThat(result).isEqualTo(RemoveResult.Removed(listOf(a, c)))
    }

    @Test
    fun `remove from a 2-member folder dissolves it to the survivor`() {
        val result = FolderMembership.remove(listOf(a, b), a)
        assertThat(result).isEqualTo(RemoveResult.Dissolved(survivor = b))
    }

    @Test
    fun `remove of an absent member is NotAMember`() {
        assertThat(FolderMembership.remove(listOf(a, b), c)).isEqualTo(RemoveResult.NotAMember)
    }

    @Test
    fun `remove of a duplicated member is NotAMember (ambiguous, invariant-violating blob)`() {
        // A hand-edited/imported blob may violate IHM-INV-7; "remove one" is ambiguous,
        // so the folder is left untouched rather than mangled.
        assertThat(FolderMembership.remove(listOf(a, a, b), a)).isEqualTo(RemoveResult.NotAMember)
    }
}

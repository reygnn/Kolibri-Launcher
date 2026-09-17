package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DrawerEntry
import com.github.reygnn.nyx_launcher.home.model.DrawerFolder
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderId
import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import com.github.reygnn.nyx_launcher.home.repository.FakeDrawerFoldersRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeHiddenAppsRepository
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Pins the pure drawer projection (DRAWER_FOLDERS_SPEC §5 / §11): reconcile
 * intersection, auto-dissolve, D-2 ordering (folders then apps), loose-vs-folder
 * split. Plus one Flow-level test that the use case combines its two sources.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GetDrawerContentUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun app(pkg: String, label: String = pkg, custom: String? = null) =
        LauncherApp(ComponentKey(pkg, "$pkg.Main"), label, custom)

    private fun folder(id: String, title: String, vararg members: LauncherApp) =
        DrawerFolder(DrawerFolderId(id), title, members.map { it.key })

    private fun folders(vararg f: DrawerFolder) = DrawerFolders(f.toList())

    // ---- pure projection ----

    @Test
    fun `no folders - every app is loose, sorted by display name`() {
        val apps = listOf(app("c"), app("a"), app("b"))
        val result = projectDrawerContent(apps, DrawerFolders.EMPTY)
        assertThat(result).isEqualTo(listOf(DrawerEntry.App(app("a")), DrawerEntry.App(app("b")), DrawerEntry.App(app("c"))))
    }

    @Test
    fun `folders come before loose apps (D-2)`() {
        val a = app("a"); val b = app("b"); val c = app("c"); val d = app("d")
        val result = projectDrawerContent(listOf(a, b, c, d), folders(folder("f1", "Stuff", a, b)))
        assertThat(result).isEqualTo(
            listOf(
                DrawerEntry.Folder(DrawerFolderId("f1"), "Stuff", listOf(a.key, b.key)),
                DrawerEntry.App(c),
                DrawerEntry.App(d),
            ),
        )
    }

    @Test
    fun `folder members are excluded from the loose app list`() {
        val a = app("a"); val b = app("b"); val c = app("c")
        val result = projectDrawerContent(listOf(a, b, c), folders(folder("f1", "", a, b)))
        val looseApps = result.filterIsInstance<DrawerEntry.App>().map { it.app }
        assertThat(looseApps).containsExactly(c)
    }

    @Test
    fun `folder block is sorted alphabetically by title`() {
        val a = app("a"); val b = app("b"); val c = app("c"); val d = app("d")
        val result = projectDrawerContent(
            listOf(a, b, c, d),
            folders(folder("z", "Zebra", a, b), folder("ap", "Apple", c, d)),
        )
        val titles = result.filterIsInstance<DrawerEntry.Folder>().map { it.title }
        assertThat(titles).containsExactly("Apple", "Zebra").inOrder()
    }

    @Test
    fun `reconcile drops an uninstalled member from the folder`() {
        val a = app("a"); val b = app("b") // c is persisted in the folder but not installed
        val persisted = DrawerFolder(DrawerFolderId("f1"), "", listOf(a.key, b.key, ComponentKey("c", "c.Main")))
        val result = projectDrawerContent(listOf(a, b), DrawerFolders(listOf(persisted)))
        val folderEntry = result.filterIsInstance<DrawerEntry.Folder>().single()
        assertThat(folderEntry.members).containsExactly(a.key, b.key).inOrder()
    }

    @Test
    fun `folder reconciled below two members auto-dissolves and its survivor goes loose`() {
        val a = app("a"); val x = app("x") // b persisted but uninstalled -> folder drops to {a}
        val persisted = DrawerFolder(DrawerFolderId("f1"), "", listOf(a.key, ComponentKey("b", "b.Main")))
        val result = projectDrawerContent(listOf(a, x), DrawerFolders(listOf(persisted)))
        assertThat(result.filterIsInstance<DrawerEntry.Folder>()).isEmpty()
        assertThat(result.filterIsInstance<DrawerEntry.App>().map { it.app }).containsExactly(a, x)
    }

    @Test
    fun `folder with all members uninstalled vanishes entirely`() {
        val x = app("x")
        val dead = DrawerFolder(DrawerFolderId("f1"), "Gone", listOf(ComponentKey("a", "a.Main"), ComponentKey("b", "b.Main")))
        val result = projectDrawerContent(listOf(x), DrawerFolders(listOf(dead)))
        assertThat(result).isEqualTo(listOf(DrawerEntry.App(x)))
    }

    @Test
    fun `loose apps sort by customName when set, else label`() {
        val a = app("a", label = "Aaa", custom = "Zzz") // sorts as "zzz"
        val b = app("b", label = "Bbb")                 // sorts as "bbb"
        val result = projectDrawerContent(listOf(a, b), DrawerFolders.EMPTY)
        assertThat(result).isEqualTo(listOf(DrawerEntry.App(b), DrawerEntry.App(a)))
    }

    // ---- hidden apps ----

    @Test
    fun `hidden loose app is filtered out when not revealing`() {
        val a = app("a"); val b = app("b")
        val result = projectDrawerContent(listOf(a, b), DrawerFolders.EMPTY, hidden = setOf(a.key))
        assertThat(result).isEqualTo(listOf(DrawerEntry.App(b)))
    }

    @Test
    fun `hidden loose app is shown marked when revealing`() {
        val a = app("a"); val b = app("b")
        val result = projectDrawerContent(listOf(a, b), DrawerFolders.EMPTY, hidden = setOf(a.key), revealHidden = true)
        assertThat(result).isEqualTo(
            listOf(DrawerEntry.App(a, hidden = true), DrawerEntry.App(b, hidden = false)),
        )
    }

    @Test
    fun `hidden member is removed from a folder, dissolving it below two (not revealing)`() {
        val a = app("a"); val b = app("b"); val c = app("c")
        // f1 = [a, b]; hiding a leaves one visible member, so the folder dissolves and b goes loose.
        val result = projectDrawerContent(listOf(a, b, c), folders(folder("f1", "Stuff", a, b)), hidden = setOf(a.key))
        assertThat(result).isEqualTo(listOf(DrawerEntry.App(b), DrawerEntry.App(c)))
    }

    @Test
    fun `hidden member stays in its folder when revealing`() {
        val a = app("a"); val b = app("b")
        val result = projectDrawerContent(listOf(a, b), folders(folder("f1", "Stuff", a, b)), hidden = setOf(a.key), revealHidden = true)
        assertThat(result).isEqualTo(
            listOf(DrawerEntry.Folder(DrawerFolderId("f1"), "Stuff", listOf(a.key, b.key))),
        )
    }

    // ---- Flow-level: the use case combines its sources ----

    @Test
    fun `use case combines the live apps with the folder membership`() =
        runTest(mainDispatcherRule.dispatcher) {
            val a = app("a"); val b = app("b"); val c = app("c")
            val repo = FakeDrawerFoldersRepository(folders(folder("f1", "Stuff", a, b)))
            val useCase = GetDrawerContentUseCase(repo, FakeHiddenAppsRepository())

            val content = useCase(flowOf(listOf(a, b, c)), flowOf(false)).first()

            assertThat(content).isEqualTo(
                listOf(
                    DrawerEntry.Folder(DrawerFolderId("f1"), "Stuff", listOf(a.key, b.key)),
                    DrawerEntry.App(c),
                ),
            )
        }

    @Test
    fun `use case filters the hidden set out of the loose apps`() =
        runTest(mainDispatcherRule.dispatcher) {
            val a = app("a"); val b = app("b")
            val useCase = GetDrawerContentUseCase(
                FakeDrawerFoldersRepository(),
                FakeHiddenAppsRepository(setOf(a.key)),
            )

            val content = useCase(flowOf(listOf(a, b)), flowOf(false)).first()

            assertThat(content).isEqualTo(listOf(DrawerEntry.App(b)))
        }
}

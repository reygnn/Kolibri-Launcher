package com.github.reygnn.nyx_launcher.home.wallpaper

import com.github.reygnn.launcher.feature.wallpaper.WallpaperComposite
import com.github.reygnn.nyx_launcher.data.home.NyxWallpaperEditing
import com.github.reygnn.launcher.feature.wallpaper.WallpaperImageStore
import kotlinx.coroutines.flow.first
import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.github.reygnn.nyx_launcher.data.home.NyxWallpaperDisplayKeys
import com.github.reygnn.launcher.feature.wallpaper.WallpaperDisplaySettingsStore
import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the transactional edit-session semantics of [NyxWallpaperEditCoordinator]
 * (ported from Kolibri's WallpaperDelegate): commit keeps, cancel reverts + cleans
 * up added files, in-edit removals defer their file delete to commit.
 */
@RunWith(RobolectricTestRunner::class)
class NyxWallpaperEditCoordinatorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repoState = MutableStateFlow(WallpaperState.NONE)
    private val repository = object : WallpaperRepository {
        override val wallpaperState: Flow<WallpaperState> get() = repoState
        override suspend fun saveWallpaperState(state: WallpaperState) { repoState.value = state }
        override suspend fun clearWallpaper() { repoState.value = WallpaperState.NONE }
        override suspend fun getWallpaperStateSync(): WallpaperState = repoState.value
        // Since 3b-3 every delete goes through the store, which decides against what is persisted.
        override suspend fun readPersistedImageUris(): Set<String> = repoState.value.referencedUris
        override suspend fun purgeRepository() { repoState.value = WallpaperState.NONE }
    }
    private val fileManager = mockk<WallpaperFileManager>(relaxed = true)
    // The real display-settings store over Nyx's in-memory home_layout store (3b-2): the backdrop
    // toggle lives there, so the toggle tests check what lands in the store.
    private val homeLayoutStore = FakeDataStore()
    private val displaySettings = WallpaperDisplaySettingsStore(homeLayoutStore, NyxWallpaperDisplayKeys)

    // Since 3b-3 the coordinator drives the shared session of NyxWallpaperEditing. Its app scope
    // runs on the test dispatcher (not backgroundScope: advanceUntilIdle must drive it, 3a-9c).
    private fun editing() = NyxWallpaperEditing(
        repository = repository,
        imageStore = WallpaperImageStore(fileManager, repository, mainDispatcherRule.testDispatcher),
        composite = WallpaperComposite.None(), // placeholder: no composite in these tests (3b-6)
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        appScope = kotlinx.coroutines.CoroutineScope(mainDispatcherRule.testDispatcher),
        mainDispatcher = mainDispatcherRule.testDispatcher,
    )

    private fun coordinator(editing: NyxWallpaperEditing = editing()) =
        NyxWallpaperEditCoordinator(
            editing = editing,
            displaySettings = displaySettings,
            scope = kotlinx.coroutines.CoroutineScope(mainDispatcherRule.testDispatcher),
        ).also { it.start() }

    private fun uri(s: String): Uri = Uri.parse(s)

    @Test
    fun `add layer outside edit appends and persists`() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { fileManager.copyToInternal(any()) } returns uri("file:///internal/wp_1")
        val c = coordinator()
        advanceUntilIdle()

        c.onAddLayer(uri("content://pick/a"))
        advanceUntilIdle()

        assertThat(repoState.value.layerCount).isEqualTo(1)
        assertThat(repoState.value.layers[0].imageUri).isEqualTo("file:///internal/wp_1")
        assertThat(repoState.value.layerCount).isEqualTo(1)
    }

    @Test
    fun `enter add commit keeps the added layer and its file`() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { fileManager.copyToInternal(any()) } returns uri("file:///internal/wp_1")
        val c = coordinator()
        advanceUntilIdle()

        c.onEnterEditMode()
        c.onAddLayer(uri("content://pick/a"))
        advanceUntilIdle()
        c.onCommitEditMode()
        advanceUntilIdle()

        assertThat(c.isEditMode.value).isFalse()
        assertThat(repoState.value.layerCount).isEqualTo(1)
        // The added file is kept (never deleted) on commit.
        verify(exactly = 0) { fileManager.deleteFile("file:///internal/wp_1") }
    }

    @Test
    fun `committing while an add copy is still in flight applies the add (E2)`() = runTest(mainDispatcherRule.testDispatcher) {
        // 3b-3, E2 as in Kolibri: the copy coroutine only runs on advanceUntilIdle, so here the
        // user commits BEFORE it resumes. A commit does not bump the rollback generation any more,
        // so the resuming add is applied after the commit and its file kept. (Before 3b-3 Nyx
        // discarded it — §Audit-3 A3-02.) Only a Cancel discards an add still copying.
        coEvery { fileManager.copyToInternal(any()) } returns uri("file:///internal/wp_late")
        val c = coordinator()
        advanceUntilIdle()

        c.onEnterEditMode()
        c.onAddLayer(uri("content://pick/late")) // schedules the suspending copy; NOT advanced yet
        c.onCommitEditMode()                      // session ends before the copy resumes
        advanceUntilIdle()                        // copy resumes → generation changed → discard

        assertThat(c.isEditMode.value).isFalse()
        assertThat(repoState.value.layerCount).isEqualTo(1) // the late layer is applied and persisted
        verify(exactly = 0) { fileManager.deleteFile("file:///internal/wp_late") } // its file is kept
    }

    @Test
    fun `enter add cancel reverts state and deletes the added file`() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { fileManager.copyToInternal(any()) } returns uri("file:///internal/wp_new")
        val c = coordinator()
        advanceUntilIdle()
        // pre-existing single wallpaper
        repoState.value = WallpaperState.single("file:///internal/wp_old")
        advanceUntilIdle()

        c.onEnterEditMode()
        c.onAddLayer(uri("content://pick/b"))
        advanceUntilIdle()
        assertThat(repoState.value.layerCount).isEqualTo(2)

        c.onCancelEditMode()
        advanceUntilIdle()

        // Reverted to the pre-edit snapshot (single old layer)...
        assertThat(repoState.value.layerCount).isEqualTo(1)
        assertThat(repoState.value.layers[0].imageUri).isEqualTo("file:///internal/wp_old")
        // ...and the file added during the session is cleaned up.
        verify { fileManager.deleteFile("file:///internal/wp_new") }
    }

    @Test
    fun `a second enter during a live session does not clobber the rollback snapshot`() = runTest(mainDispatcherRule.testDispatcher) {
        // §Audit-3 A3-10: re-entering mid-session used to re-snapshot the EDITED state and clear
        // the pending lists, so Cancel kept the added layer and leaked its file.
        coEvery { fileManager.copyToInternal(any()) } returns uri("file:///internal/wp_re")
        val c = coordinator()
        advanceUntilIdle()

        c.onEnterEditMode()
        c.onAddLayer(uri("content://pick/re"))
        advanceUntilIdle()
        c.onEnterEditMode() // re-entry: must be a no-op
        c.onCancelEditMode()
        advanceUntilIdle()

        assertThat(c.isEditMode.value).isFalse()
        assertThat(repoState.value.layerCount).isEqualTo(0) // reverted to the pre-session state
        verify { fileManager.deleteFile("file:///internal/wp_re") } // the added file is cleaned up
    }

    @Test
    fun `remove outside edit deletes the file immediately`() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { fileManager.copyToInternal(any()) } returns uri("file:///internal/wp_x")
        val c = coordinator()
        advanceUntilIdle()
        c.onAddLayer(uri("content://pick/x")); advanceUntilIdle()

        c.onRemoveLayer(0)
        advanceUntilIdle()

        assertThat(repoState.value.layerCount).isEqualTo(0)
        verify { fileManager.deleteFile("file:///internal/wp_x") }
    }

    @Test
    fun `remove in edit defers file delete until commit`() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { fileManager.copyToInternal(any()) } returns uri("file:///internal/wp_2")
        val c = coordinator()
        advanceUntilIdle()
        repoState.value = WallpaperState.single("file:///internal/wp_1")
        advanceUntilIdle()
        // add a second layer so removal doesn't empty the wallpaper
        c.onEnterEditMode()
        c.onAddLayer(uri("content://pick/c"))
        advanceUntilIdle()

        c.onRemoveLayer(0) // remove wp_1 during edit
        advanceUntilIdle()
        // not deleted yet
        verify(exactly = 0) { fileManager.deleteFile("file:///internal/wp_1") }

        c.onCommitEditMode()
        advanceUntilIdle()
        // deleted on commit
        verify { fileManager.deleteFile("file:///internal/wp_1") }
    }

    @Test
    fun `remove in edit then cancel keeps the file`() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { fileManager.copyToInternal(any()) } returns uri("file:///internal/wp_2")
        val c = coordinator()
        advanceUntilIdle()
        repoState.value = WallpaperState.single("file:///internal/wp_1")
        advanceUntilIdle()
        c.onEnterEditMode()
        c.onAddLayer(uri("content://pick/c"))
        advanceUntilIdle()

        c.onRemoveLayer(0) // remove wp_1 during edit
        advanceUntilIdle()
        c.onCancelEditMode()
        advanceUntilIdle()

        // wp_1 is restored by the snapshot, so its file must NOT have been deleted.
        verify(exactly = 0) { fileManager.deleteFile("file:///internal/wp_1") }
        assertThat(repoState.value.layers.map { it.imageUri }).containsExactly("file:///internal/wp_1")
    }

    @Test
    fun `swap layers reorders the state`() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { fileManager.copyToInternal(any()) } returnsMany listOf(uri("file:///a"), uri("file:///b"))
        val c = coordinator()
        advanceUntilIdle()
        c.onAddLayer(uri("content://pick/a")); advanceUntilIdle()
        c.onAddLayer(uri("content://pick/b")); advanceUntilIdle()

        c.onSwapLayers(0, 1)
        advanceUntilIdle()

        assertThat(repoState.value.layers.map { it.imageUri })
            .containsExactly("file:///b", "file:///a").inOrder()
    }

    @Test
    fun `stale repo emission during edit does not clobber optimistic state`() = runTest(mainDispatcherRule.testDispatcher) {
        // Guards #2/#4: a delayed persist round-trip of an earlier mutation (or an
        // old wallpaper) must not overwrite the live edit state via the mirror.
        coEvery { fileManager.copyToInternal(any()) } returnsMany listOf(uri("file:///a"), uri("file:///b"))
        val c = coordinator()
        advanceUntilIdle()
        c.onEnterEditMode()
        c.onAddLayer(uri("content://pick/a")); advanceUntilIdle()
        c.onAddLayer(uri("content://pick/b")); advanceUntilIdle()

        // Simulate a stale/delayed repository emission arriving mid-session.
        repoState.value = WallpaperState.single("file:///stale_old")
        advanceUntilIdle()

        // Ignored while editing — the two added layers stay.
        assertThat(c.wallpaperState.value.layers.map { it.imageUri })
            .containsExactly("file:///a", "file:///b").inOrder()
    }

    @Test
    fun `toggle backdrop flips and persists`() = runTest(mainDispatcherRule.testDispatcher) {
        // Nothing persisted: the default SYSTEM_WALLPAPER flips to BLACK, in the store.
        val c = coordinator()
        advanceUntilIdle()

        c.onToggleBackdrop()
        advanceUntilIdle()

        assertThat(displaySettings.wallpaperBackdropFlow.first()).isEqualTo(WallpaperBackdrop.BLACK)
    }

    @Test
    fun `toggle after a setter write flips from the setter's value`() = runTest(mainDispatcherRule.testDispatcher) {
        // The 14d case for Nyx: toggle to BLACK, then settings / a restore write SYSTEM_WALLPAPER
        // through the setter — the next toggle flips from that, visible on the FIRST tap.
        val c = coordinator()
        advanceUntilIdle()
        c.onToggleBackdrop()
        advanceUntilIdle()

        displaySettings.setWallpaperBackdrop(WallpaperBackdrop.SYSTEM_WALLPAPER)
        c.onToggleBackdrop()
        advanceUntilIdle()

        assertThat(displaySettings.wallpaperBackdropFlow.first()).isEqualTo(WallpaperBackdrop.BLACK)
    }
}

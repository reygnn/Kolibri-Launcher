package com.github.reygnn.nyx_launcher.data.home

import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import com.github.reygnn.launcher.feature.wallpaper.WallpaperImageStore
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric (only for real [android.net.Uri]): the disk-reclamation logic of
 * [NyxWallpaperImageSetter] over mocked [WallpaperFileManager] +
 * [WallpaperRepository]. Pins the replace-vs-strand and copy-failure decisions
 * (WV5) without touching real files.
 */
@RunWith(RobolectricTestRunner::class)
class NyxWallpaperImageSetterTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val fileManager = mockk<WallpaperFileManager>(relaxed = true)
    private val repository = mockk<WallpaperRepository>(relaxed = true)
    private val editState = NyxWallpaperEditState()

    // Since 3b-1 the deletes go through the shared store: it reads the persisted references
    // (readPersistedImageUris) and deletes nothing when they can't be read.
    private val setter = NyxWallpaperImageSetter(
        repository = repository,
        imageStore = WallpaperImageStore(fileManager, repository, mainDispatcherRule.testDispatcher),
        editState = editState,
    )

    /** What the store reads as persisted after the save/clear under test. */
    private fun persisted(vararg uris: String) {
        coEvery { repository.readPersistedImageUris() } returns uris.toSet()
    }

    private val sourceUri: Uri = Uri.parse("content://picker/image")

    private fun internalUri(value: String): Uri = Uri.parse(value)

    @Test
    fun setFromUri_saves_new_state_and_deletes_the_replaced_file() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { repository.getWallpaperStateSync() } returns WallpaperState.single("file:///old")
        coEvery { fileManager.copyToInternal(any()) } returns internalUri("file:///new")
        persisted("file:///new")

        val ok = setter.setFromUri(sourceUri)

        assertThat(ok).isTrue()
        coVerify(exactly = 1) { repository.saveWallpaperState(match { it.layers.singleOrNull()?.imageUri == "file:///new" }) }
        verify(exactly = 1) { fileManager.deleteFile("file:///old") }
    }

    @Test
    fun setFromUri_with_no_previous_wallpaper_saves_and_deletes_nothing() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { repository.getWallpaperStateSync() } returns WallpaperState.NONE
        coEvery { fileManager.copyToInternal(any()) } returns internalUri("file:///new")
        persisted("file:///new")

        val ok = setter.setFromUri(sourceUri)

        assertThat(ok).isTrue()
        coVerify(exactly = 1) { repository.saveWallpaperState(match { it.layers.singleOrNull()?.imageUri == "file:///new" }) }
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun setFromUri_does_not_delete_when_the_new_file_matches_the_old_reference() = runTest(mainDispatcherRule.testDispatcher) {
        // Re-picking the same internal file must not delete the file state now points at.
        coEvery { repository.getWallpaperStateSync() } returns WallpaperState.single("file:///same")
        coEvery { fileManager.copyToInternal(any()) } returns internalUri("file:///same")
        persisted("file:///same")

        setter.setFromUri(sourceUri)

        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun setFromUri_returns_false_and_touches_nothing_when_the_copy_fails() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { fileManager.copyToInternal(any()) } returns null

        val ok = setter.setFromUri(sourceUri)

        assertThat(ok).isFalse()
        coVerify(exactly = 0) { repository.saveWallpaperState(any()) }
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun clear_clears_state_and_deletes_the_files_once_nothing_is_persisted() = runTest(mainDispatcherRule.testDispatcher) {
        // 3b-1 (A1): state first, then the files — all of them, through the store, but only once
        // the persisted state references nothing (as in Kolibri, 3a-2d).
        coEvery { repository.getWallpaperStateSync() } returns WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(imageUri = "file:///a"),
                WallpaperLayerState(imageUri = "file:///b"),
            ),
        )
        persisted()

        val removed = setter.clear()

        assertThat(removed).isTrue()
        coVerify(exactly = 1) { repository.clearWallpaper() }
        verify(exactly = 1) { fileManager.clearAll() }
    }

    @Test
    fun clear_with_no_wallpaper_still_clears_state_and_deletes_nothing() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { repository.getWallpaperStateSync() } returns WallpaperState.NONE
        persisted()

        setter.clear()

        coVerify(exactly = 1) { repository.clearWallpaper() }
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun reclaimOrphans_sweeps_using_the_currently_referenced_uris() = runTest(mainDispatcherRule.testDispatcher) {
        // 3b-1 (A2): the references are read through readPersistedImageUris; no session open.
        persisted("file:///keep")
        editState.sessionOpen = false

        setter.reclaimOrphans()

        verify(exactly = 1) { fileManager.gcOrphans(setOf("file:///keep")) }
    }

    // ---- 3b-1: the gaps, now closed through the store ----

    @Test
    fun setFromUri_deletes_nothing_when_the_persisted_state_cannot_be_read() = runTest(mainDispatcherRule.testDispatcher) {
        // A swallowed save or an unreadable store must not cost the old file (3a-2c).
        coEvery { repository.getWallpaperStateSync() } returns WallpaperState.single("file:///old")
        coEvery { fileManager.copyToInternal(any()) } returns internalUri("file:///new")
        coEvery { repository.readPersistedImageUris() } returns null

        setter.setFromUri(sourceUri)

        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun setFromUri_during_an_open_edit_session_saves_but_deletes_nothing() = runTest(mainDispatcherRule.testDispatcher) {
        // 3b-1b-b: the open session's layers may still reference the previous file and write it
        // back on commit — it stays as an orphan for a GC with the session closed.
        editState.sessionOpen = true
        coEvery { repository.getWallpaperStateSync() } returns WallpaperState.single("file:///old")
        coEvery { fileManager.copyToInternal(any()) } returns internalUri("file:///new")
        persisted("file:///new")

        val ok = setter.setFromUri(sourceUri)

        assertThat(ok).isTrue()
        coVerify(exactly = 1) { repository.saveWallpaperState(match { it.layers.singleOrNull()?.imageUri == "file:///new" }) }
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun clear_that_did_not_land_reports_false_and_keeps_the_files() = runTest(mainDispatcherRule.testDispatcher) {
        // The clear was swallowed: the old state is still persisted, so its files stay (3a-2d).
        persisted("file:///a")

        val removed = setter.clear()

        assertThat(removed).isFalse()
        verify(exactly = 0) { fileManager.clearAll() }
    }

    @Test
    fun clear_during_an_open_edit_session_keeps_the_files_and_reports_only_the_clear() = runTest(mainDispatcherRule.testDispatcher) {
        // Until 3b-3 Nyx's session has no locks against a concurrent copy; its files stay as
        // orphans for the GC, and the result depends only on whether the clear landed.
        editState.sessionOpen = true
        persisted()

        val removed = setter.clear()

        assertThat(removed).isTrue()
        verify(exactly = 0) { fileManager.clearAll() }
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun reclaimOrphans_leaves_an_open_edit_session_alone() = runTest(mainDispatcherRule.testDispatcher) {
        persisted("file:///keep")
        editState.sessionOpen = true

        setter.reclaimOrphans()

        verify(exactly = 0) { fileManager.gcOrphans(any<Set<String>>()) }
    }

    @Test
    fun reclaimOrphans_does_not_run_on_an_unreadable_state() = runTest(mainDispatcherRule.testDispatcher) {
        // Before 3b-1 an unreadable store read as "nothing referenced" — every file an orphan.
        coEvery { repository.readPersistedImageUris() } returns null

        setter.reclaimOrphans()

        verify(exactly = 0) { fileManager.gcOrphans(any<Set<String>>()) }
    }
}

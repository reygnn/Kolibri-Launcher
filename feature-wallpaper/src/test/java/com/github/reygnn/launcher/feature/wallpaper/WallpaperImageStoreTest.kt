package com.github.reygnn.launcher.feature.wallpaper

import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * The rules of [WallpaperImageStore] in isolation (3a-2, 3a-2c); the end-to-end behaviour with real
 * files is `WallpaperImageStoreContract`.
 */
class WallpaperImageStoreTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val fileManager = mockk<WallpaperFileManager>(relaxed = true)
    private val repository = mockk<WallpaperRepository>(relaxed = true)
    private val store = WallpaperImageStore(fileManager, repository, mainDispatcherRule.testDispatcher)

    private val a = "file:///data/wallpapers/wp_a"
    private val b = "file:///data/wallpapers/wp_b"

    private fun persisted(uris: Set<String>?) {
        coEvery { repository.readPersistedImageUris() } returns uris
    }

    @Test
    fun only_files_no_persisted_layer_references_are_deleted() = runTest(mainDispatcherRule.testDispatcher) {
        // O2 safeguard: b is still referenced by a persisted layer, so it stays.
        persisted(setOf(b))

        store.deleteUnreferenced(listOf(a, b, a))

        verify(exactly = 1) { fileManager.deleteFile(a) }
        verify(exactly = 0) { fileManager.deleteFile(b) }
    }

    @Test
    fun byte_identical_files_are_decided_by_reference_only() = runTest(mainDispatcherRule.testDispatcher) {
        // 08c: a and b hold the same bytes (two copies of one picked image). The store decides on
        // URIs, never on content: only the unreferenced candidate goes, the twin stays.
        persisted(setOf(b))

        store.deleteUnreferenced(listOf(a))

        verify(exactly = 1) { fileManager.deleteFile(a) }
        verify(exactly = 0) { fileManager.deleteFile(b) }
    }

    @Test
    fun an_unreadable_persisted_state_deletes_nothing() = runTest(mainDispatcherRule.testDispatcher) {
        // Fail closed (3a-2c): without the persisted references there is no safe delete.
        persisted(null)

        store.deleteUnreferenced(listOf(a, b))

        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun a_referenced_candidate_touches_no_file() = runTest(mainDispatcherRule.testDispatcher) {
        persisted(setOf(a))

        store.deleteUnreferenced(listOf(a))

        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun the_orphan_gc_refuses_while_an_edit_session_is_open() = runTest(mainDispatcherRule.testDispatcher) {
        persisted(setOf(a))

        assertThat(store.collectOrphans(editSessionOpen = true)).isFalse()
        verify(exactly = 0) { fileManager.gcOrphans(any<Set<String>>()) }

        assertThat(store.collectOrphans(editSessionOpen = false)).isTrue()
        verify(exactly = 1) { fileManager.gcOrphans(setOf(a)) }
    }

    @Test
    fun the_orphan_gc_does_not_run_on_an_unreadable_persisted_state() = runTest(mainDispatcherRule.testDispatcher) {
        persisted(null)

        assertThat(store.collectOrphans(editSessionOpen = false)).isFalse()
        verify(exactly = 0) { fileManager.gcOrphans(any<Set<String>>()) }
    }

    @Test
    fun remove_deletes_every_file_once_nothing_is_persisted() = runTest(mainDispatcherRule.testDispatcher) {
        persisted(emptySet())
        every { fileManager.clearAll() } returns false // a file that won't go is only an orphan

        assertThat(store.deleteAllIfNothingPersisted()).isTrue()
        verify(exactly = 1) { fileManager.clearAll() }
    }

    @Test
    fun remove_keeps_the_files_while_the_persisted_state_still_references_any() = runTest(mainDispatcherRule.testDispatcher) {
        // 3a-2d: the clear did not land, so the files stay with the state that still needs them.
        persisted(setOf(a))

        assertThat(store.deleteAllIfNothingPersisted()).isFalse()
        verify(exactly = 0) { fileManager.clearAll() }
    }

    @Test
    fun remove_keeps_the_files_when_the_persisted_state_cant_be_read() = runTest(mainDispatcherRule.testDispatcher) {
        persisted(null)

        assertThat(store.deleteAllIfNothingPersisted()).isFalse()
        verify(exactly = 0) { fileManager.clearAll() }
    }
}

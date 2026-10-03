package com.github.reygnn.launcher.feature.wallpaper

import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * The rules of [WallpaperImageStore] in isolation (3a-2); the end-to-end behaviour with real files
 * is `WallpaperImageStoreContract`.
 */
class WallpaperImageStoreTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val fileManager = mockk<WallpaperFileManager>(relaxed = true)
    private val store = WallpaperImageStore(fileManager, mainDispatcherRule.testDispatcher)

    private val a = "file:///data/wallpapers/wp_a"
    private val b = "file:///data/wallpapers/wp_b"

    @Test
    fun only_files_no_saved_layer_references_are_deleted() = runTest(mainDispatcherRule.testDispatcher) {
        // O2 safeguard: b is still referenced by a layer of the saved state, so it stays.
        val saved = WallpaperState.multiLayer(listOf(WallpaperLayerState(id = "one", imageUri = b)))

        store.deleteUnreferenced(listOf(a, b, a), saved)

        verify(exactly = 1) { fileManager.deleteFile(a) }
        verify(exactly = 0) { fileManager.deleteFile(b) }
    }

    @Test
    fun nothing_to_delete_touches_no_file() = runTest(mainDispatcherRule.testDispatcher) {
        store.deleteUnreferenced(listOf(a), WallpaperState.single(uri = a))

        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun the_orphan_gc_refuses_while_an_edit_session_is_open() = runTest(mainDispatcherRule.testDispatcher) {
        val saved = WallpaperState.single(uri = a)

        assertThat(store.collectOrphans(saved, editSessionOpen = true)).isFalse()
        verify(exactly = 0) { fileManager.gcOrphans(any<Set<String>>()) }

        assertThat(store.collectOrphans(saved, editSessionOpen = false)).isTrue()
        verify(exactly = 1) { fileManager.gcOrphans(setOf(a)) }
    }

    @Test
    fun delete_all_reports_whether_everything_is_gone() = runTest(mainDispatcherRule.testDispatcher) {
        every { fileManager.clearAll() } returns false

        assertThat(store.deleteAll()).isFalse()
    }
}

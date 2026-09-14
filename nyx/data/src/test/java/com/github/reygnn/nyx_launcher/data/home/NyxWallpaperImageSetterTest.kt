package com.github.reygnn.nyx_launcher.data.home

import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
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

    private val fileManager = mockk<WallpaperFileManager>(relaxed = true)
    private val repository = mockk<WallpaperRepository>(relaxed = true)
    private val setter = NyxWallpaperImageSetter(fileManager, repository)

    private val sourceUri: Uri = Uri.parse("content://picker/image")

    private fun internalUri(value: String): Uri = Uri.parse(value)

    @Test
    fun setFromUri_saves_new_state_and_deletes_the_replaced_file() = runTest {
        coEvery { repository.getWallpaperStateSync() } returns WallpaperState.single("file:///old")
        coEvery { fileManager.copyToInternal(any()) } returns internalUri("file:///new")

        val ok = setter.setFromUri(sourceUri)

        assertThat(ok).isTrue()
        coVerify(exactly = 1) { repository.saveWallpaperState(match { it.layers.singleOrNull()?.imageUri == "file:///new" }) }
        verify(exactly = 1) { fileManager.deleteFile("file:///old") }
    }

    @Test
    fun setFromUri_with_no_previous_wallpaper_saves_and_deletes_nothing() = runTest {
        coEvery { repository.getWallpaperStateSync() } returns WallpaperState.NONE
        coEvery { fileManager.copyToInternal(any()) } returns internalUri("file:///new")

        val ok = setter.setFromUri(sourceUri)

        assertThat(ok).isTrue()
        coVerify(exactly = 1) { repository.saveWallpaperState(match { it.layers.singleOrNull()?.imageUri == "file:///new" }) }
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun setFromUri_does_not_delete_when_the_new_file_matches_the_old_reference() = runTest {
        // Re-picking the same internal file must not delete the file state now points at.
        coEvery { repository.getWallpaperStateSync() } returns WallpaperState.single("file:///same")
        coEvery { fileManager.copyToInternal(any()) } returns internalUri("file:///same")

        setter.setFromUri(sourceUri)

        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun setFromUri_returns_false_and_touches_nothing_when_the_copy_fails() = runTest {
        coEvery { fileManager.copyToInternal(any()) } returns null

        val ok = setter.setFromUri(sourceUri)

        assertThat(ok).isFalse()
        coVerify(exactly = 0) { repository.saveWallpaperState(any()) }
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun clear_clears_state_and_deletes_every_referenced_file() = runTest {
        coEvery { repository.getWallpaperStateSync() } returns WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(imageUri = "file:///a"),
                WallpaperLayerState(imageUri = "file:///b"),
            ),
        )

        setter.clear()

        coVerify(exactly = 1) { repository.clearWallpaper() }
        verify(exactly = 1) { fileManager.deleteFile("file:///a") }
        verify(exactly = 1) { fileManager.deleteFile("file:///b") }
    }

    @Test
    fun clear_with_no_wallpaper_still_clears_state_and_deletes_nothing() = runTest {
        coEvery { repository.getWallpaperStateSync() } returns WallpaperState.NONE

        setter.clear()

        coVerify(exactly = 1) { repository.clearWallpaper() }
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun reclaimOrphans_sweeps_using_the_currently_referenced_uris() = runTest {
        coEvery { repository.getWallpaperStateSync() } returns WallpaperState.single("file:///keep")

        setter.reclaimOrphans()

        verify(exactly = 1) { fileManager.gcOrphans(setOf("file:///keep")) }
    }
}

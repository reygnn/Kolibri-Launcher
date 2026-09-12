package com.github.reygnn.nyx_launcher.home.wallpaper

import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
        override suspend fun purgeRepository() { repoState.value = WallpaperState.NONE }
    }
    private val fileManager = mockk<WallpaperFileManager>(relaxed = true)
    private val displaySettings = mockk<WallpaperDisplaySettings>(relaxed = true)

    private fun coordinator() =
        NyxWallpaperEditCoordinator(
            repository = repository,
            fileManager = fileManager,
            displaySettings = displaySettings,
            scope = kotlinx.coroutines.CoroutineScope(mainDispatcherRule.dispatcher),
            ioDispatcher = mainDispatcherRule.dispatcher,
        ).also { it.start() }

    private fun uri(s: String): Uri = Uri.parse(s)

    @Test
    fun `add layer outside edit appends and persists`() = runTest(mainDispatcherRule.dispatcher) {
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
    fun `enter add commit keeps the added layer and its file`() = runTest(mainDispatcherRule.dispatcher) {
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
    fun `enter add cancel reverts state and deletes the added file`() = runTest(mainDispatcherRule.dispatcher) {
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
    fun `remove outside edit deletes the file immediately`() = runTest(mainDispatcherRule.dispatcher) {
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
    fun `remove in edit defers file delete until commit`() = runTest(mainDispatcherRule.dispatcher) {
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
    fun `remove in edit then cancel keeps the file`() = runTest(mainDispatcherRule.dispatcher) {
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
    fun `swap layers reorders the state`() = runTest(mainDispatcherRule.dispatcher) {
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
    fun `toggle backdrop flips and persists`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { displaySettings.wallpaperBackdropFlow } returns flowOf(WallpaperBackdrop.SYSTEM_WALLPAPER)
        val c = coordinator()
        advanceUntilIdle()

        c.onToggleBackdrop()
        advanceUntilIdle()

        coVerify { displaySettings.setWallpaperBackdrop(WallpaperBackdrop.BLACK) }
    }
}

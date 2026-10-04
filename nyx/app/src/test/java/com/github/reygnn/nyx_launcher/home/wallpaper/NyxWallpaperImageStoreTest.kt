package com.github.reygnn.nyx_launcher.home.wallpaper

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.feature.wallpaper.WallpaperImageStoreContract
import com.github.reygnn.nyx_launcher.data.home.NyxWallpaperImageSetter
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Nyx's run of the [WallpaperImageStoreContract] (3b-1a), FIRST against today's Nyx code: the
 * image setter (choose / remove wallpaper, orphan GC as MainActivity calls it) and the edit
 * coordinator, with the real [WallpaperFileManager] on Robolectric's files dir.
 *
 * Today's gaps are switched off visibly (the contract skips those cases), each to be switched on
 * by its fix:
 *  - [decidesDeletesThroughTheStore] = false until 3b-1: the setter deletes the previous files
 *    directly even when the save was swallowed, `clear()` deletes them even when the clear was
 *    swallowed, and the GC reads the referenced files through `getWallpaperStateSync()`, which
 *    falls back to "nothing" on a read error — and it has no edit guard.
 *  - [editsThroughSharedOperations] = false until 3b-3: a replace is not a session change, and the
 *    coordinator's commit deletes removed files without a reference check.
 * W1 (replace deletes the old file right away) Nyx has always done.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NyxWallpaperImageStoreTest : WallpaperImageStoreContract() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = ContractWallpaperRepository()
    private val fileManager = WallpaperFileManager(context, mainDispatcherRule.testDispatcher)
    private val setter = NyxWallpaperImageSetter(fileManager, repository)

    // Not backgroundScope: the contract drives every step with advanceUntilIdle(), which would
    // never run backgroundScope work (3a-9c). A separate scope on the test dispatcher, cancelled after.
    private val coordinatorScope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())
    private lateinit var coordinator: NyxWallpaperEditCoordinator

    @After
    fun stopCoordinator() {
        coordinatorScope.cancel()
    }

    override val deletesReplacedImageImmediately = true // Nyx's setter always did (W1)
    override val decidesDeletesThroughTheStore = false // switched on by 3b-1
    override val editsThroughSharedOperations = false // switched on by 3b-3

    override val wallpaperDir: File get() = File(context.filesDir, "wallpapers")

    /** Like Nyx's start: the coordinator observes the state, MainActivity reclaims orphans. */
    override suspend fun startStore() {
        wallpaperDir.deleteRecursively()
        coordinator = NyxWallpaperEditCoordinator(
            repository = repository,
            fileManager = fileManager,
            displaySettings = mockk(relaxed = true),
            scope = coordinatorScope,
            ioDispatcher = mainDispatcherRule.testDispatcher,
        )
        coordinator.start()
        setter.reclaimOrphans()
    }

    override suspend fun setWallpaper(image: File) {
        setter.setFromUri(Uri.fromFile(image))
    }

    override suspend fun enterEditSession() = coordinator.onEnterEditMode()

    override suspend fun commitEditSession() = coordinator.onCommitEditMode()

    override suspend fun cancelEditSession() = coordinator.onCancelEditMode()

    override suspend fun addLayer(image: File) {
        coordinator.onAddLayer(Uri.fromFile(image))
    }

    override suspend fun removeLayer(index: Int) {
        coordinator.onRemoveLayer(index)
    }

    override suspend fun removeWallpaper() {
        setter.clear()
    }

    /** Nyx's GC: what MainActivity calls at start. */
    override suspend fun runOrphanGc() = setter.reclaimOrphans()

    override suspend fun copyWithoutSave(image: File) {
        fileManager.copyToInternal(Uri.fromFile(image))
    }

    override suspend fun saveTwoLayersOnOneFile(image: File) {
        val copy = checkNotNull(fileManager.copyToInternal(Uri.fromFile(image))).toString()
        repository.saveWallpaperState(
            WallpaperState.multiLayer(
                listOf(WallpaperLayerState(id = "first", imageUri = copy), WallpaperLayerState(id = "second", imageUri = copy)),
            ),
        )
    }

    override fun failSavesSilently() {
        repository.failWritesSilently = true
    }

    override fun makePersistedStateUnreadable() {
        repository.unreadable = true
    }

    override suspend fun savedLayerFiles(): List<String> =
        repository.state.value.layers.mapNotNull { layer -> layer.imageUri?.let { File(Uri.parse(it).path!!).name } }

    /**
     * The persisted wallpaper state for this contract run, behaving like the real repository in a
     * release build: writes are swallowed when [failWritesSilently]; when [unreadable],
     * `getWallpaperStateSync()` falls back to NONE (the real read path does) and
     * `readPersistedImageUris()` reports "can't tell" (null).
     */
    private class ContractWallpaperRepository : WallpaperRepository {
        val state = MutableStateFlow(WallpaperState.NONE)
        var failWritesSilently = false
        var unreadable = false

        override val wallpaperState: Flow<WallpaperState> = state

        override suspend fun saveWallpaperState(state: WallpaperState) {
            if (failWritesSilently) return
            this.state.value = state
        }

        override suspend fun clearWallpaper() {
            if (failWritesSilently) return
            state.value = WallpaperState.NONE
        }

        override suspend fun getWallpaperStateSync(): WallpaperState =
            if (unreadable) WallpaperState.NONE else state.value

        override suspend fun readPersistedImageUris(): Set<String>? =
            if (unreadable) null else state.value.referencedUris

        override suspend fun purgeRepository() {
            state.value = WallpaperState.NONE
        }
    }
}

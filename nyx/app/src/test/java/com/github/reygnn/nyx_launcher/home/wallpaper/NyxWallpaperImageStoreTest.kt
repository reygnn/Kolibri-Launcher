package com.github.reygnn.nyx_launcher.home.wallpaper

import com.github.reygnn.launcher.feature.wallpaper.WallpaperComposite
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.wallpaper.FakeWallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.feature.wallpaper.WallpaperImageStore
import com.github.reygnn.launcher.feature.wallpaper.WallpaperImageStoreContract
import com.github.reygnn.nyx_launcher.data.home.NyxWallpaperEditing
import com.github.reygnn.nyx_launcher.data.home.NyxWallpaperImageSetter
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Nyx's run of the [WallpaperImageStoreContract] (3b-1a, first against the old Nyx code): the image
 * setter (choose / remove wallpaper) and the edit coordinator — since 3b-3 both on the ONE shared
 * session of [NyxWallpaperEditing] — with the real [WallpaperFileManager] on Robolectric's files dir.
 *
 * The repository is the shared [FakeWallpaperRepository] (3b-02), which behaves like the real one
 * in a release build: writes swallowed when told to, and an unreadable store reads as NONE through
 * `getWallpaperStateSync()` and as "can't tell" through `readPersistedImageUris()`.
 *
 * Gaps are switched off visibly (the contract skips those cases), each switched on by its fix:
 *  - [decidesDeletesThroughTheStore]: on since 3b-1 — the setter and the GC decide every delete
 *    through the shared store (against what is persisted, fail closed, edit guard). Before, they
 *    deleted directly and the GC read "nothing referenced" on a read error.
 *  - [editsThroughSharedOperations]: on since 3b-3 — replace is a session change, and a commit
 *    deletes only what no persisted layer references (before: Nyx's own session code).
 * W1 (replace deletes the old file right away) Nyx has always done.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NyxWallpaperImageStoreTest : WallpaperImageStoreContract() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = FakeWallpaperRepository()
    private val fileManager = WallpaperFileManager(context, mainDispatcherRule.testDispatcher)
    private lateinit var editing: NyxWallpaperEditing
    private lateinit var setter: NyxWallpaperImageSetter

    // Not backgroundScope: the contract drives every step with advanceUntilIdle(), which would
    // never run backgroundScope work (3a-9c). A separate scope on the test dispatcher, cancelled after.
    private val coordinatorScope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())
    private lateinit var coordinator: NyxWallpaperEditCoordinator

    @After
    fun stopCoordinator() {
        coordinatorScope.cancel()
    }

    override val deletesReplacedImageImmediately = true // Nyx's setter always did (W1)
    override val decidesDeletesThroughTheStore = true // since 3b-1
    override val editsThroughSharedOperations = true // since 3b-3

    override val wallpaperDir: File get() = File(context.filesDir, "wallpapers")

    /** Like Nyx's start: the coordinator observes the state, MainActivity reclaims orphans. */
    override suspend fun startStore() {
        wallpaperDir.deleteRecursively()
        // Since 3b-3 one shared session for the editor and the setter; its start-up observes the
        // persisted state and runs the orphan GC (what MainActivity's start does).
        editing = NyxWallpaperEditing(
            repository = repository,
            imageStore = WallpaperImageStore(fileManager, repository, mainDispatcherRule.testDispatcher),
            composite = WallpaperComposite.None(), // placeholder: no composite in these tests (3b-6)
            context = context,
            appScope = coordinatorScope,
            mainDispatcher = mainDispatcherRule.testDispatcher,
        )
        setter = NyxWallpaperImageSetter(editing)
        coordinator = NyxWallpaperEditCoordinator(
            editing = editing,
            displaySettings = mockk(relaxed = true),
            scope = coordinatorScope,
        )
        coordinator.start()
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
    /** Like Kolibri's run: another start-up of the operations, whose first emission runs the GC unless a session is open. */
    override suspend fun runOrphanGc() = editing.operations.start()

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
        repository.failSavesSilently = true
    }

    override fun makePersistedStateUnreadable() {
        repository.persistedStateUnreadable = true
    }

    override suspend fun savedLayerFiles(): List<String> =
        repository.currentState.layers.mapNotNull { layer -> layer.imageUri?.let { File(Uri.parse(it).path!!).name } }
}

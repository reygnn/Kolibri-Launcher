package com.github.reygnn.nyx_launcher.home.wallpaper

import android.net.Uri
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.wallpaper.LayerTransform
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.feature.wallpaper.WallpaperDisplaySettingsStore
import com.github.reygnn.nyx_launcher.data.home.NyxWallpaperEditing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Nyx's wallpaper edit orchestrator — a plain object held by MainActivity (ClockDelegate
 * pattern). Since 3b-3 it decides and executes nothing itself: the shared session
 * ([WallpaperEditSession], via [NyxWallpaperEditing]) decides, [WallpaperOperations] executes —
 * persist and delete in order, the re-sync after a session, add with the rollback generation, the
 * start-up wiring. This class translates the editor's intents and exposes the session's state.
 *
 * Behaviour now as in Kolibri: a commit does NOT discard an image still being copied (E2, it is
 * applied); after a session the display re-syncs with what was persisted (E4); saves land in
 * order; every delete goes through the store. No scrim-reset offer in Nyx (`onImageChanged` = {}).
 */
class NyxWallpaperEditCoordinator(
    private val editing: NyxWallpaperEditing,
    private val displaySettings: WallpaperDisplaySettingsStore,
    private val scope: CoroutineScope,
) {
    private val session = editing.session
    private val operations = editing.operations

    val wallpaperState: StateFlow<WallpaperState> = session.state
    val isEditMode: StateFlow<Boolean> = session.isEditMode

    fun consumePendingFocusLayerId(): String? {
        val id = session.pendingFocusLayerId.value
        session.consumePendingFocusLayerId()
        return id
    }

    /** Observe the persisted state and run the orphan GC — once per process (also on a recreated activity). */
    fun start() = editing.start()

    // ---- session ----

    fun onEnterEditMode() {
        session.enter()
    }

    fun onCommitEditMode() = operations.commit(onImageChanged = {})

    fun onCancelEditMode() = operations.cancel()

    // ---- layer operations ----

    fun onAddLayer(imageUri: Uri) {
        // The rollback generation at invocation time: a Cancel during the copy discards the add.
        val rollbackGenAtStart = session.rollbackGeneration
        editing.launchOnMain("Error adding wallpaper layer") { operations.addLayer(imageUri, rollbackGenAtStart) }
    }

    fun onRemoveLayer(layerIndex: Int) =
        operations.persistLater("Error removing wallpaper layer", session.removeLayer(layerIndex))

    fun onSwapLayers(indexA: Int, indexB: Int) =
        operations.persistLater("Error swapping wallpaper layers", session.swapLayers(indexA, indexB))

    fun onSaveLayerTransform(layerIndex: Int, scale: Float, translateX: Float, translateY: Float, captureSampleSize: Int?) =
        operations.persistLater(
            "Error saving layer transform",
            session.saveLayerTransform(layerIndex, scale, translateX, translateY, captureSampleSize),
        )

    fun onSaveAllLayerTransforms(transforms: List<LayerTransform>) =
        operations.persistLater("Error saving all layer transforms", session.saveAllLayerTransforms(transforms))

    // ---- backdrop ----

    /**
     * Flip system-wallpaper ↔ black (3b-2): the read-modify-write, its lock and the memory that
     * follows every writer live in the shared display-settings store (`toggleBackdrop`, 14b/14d).
     */
    fun onToggleBackdrop() = launchSafe("Error toggling wallpaper backdrop") {
        displaySettings.toggleBackdrop()
    }

    private inline fun launchSafe(errorMessage: String, crossinline block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, errorMessage)
            }
        }
    }
}

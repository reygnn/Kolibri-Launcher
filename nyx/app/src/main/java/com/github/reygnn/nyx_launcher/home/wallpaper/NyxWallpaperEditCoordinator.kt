package com.github.reygnn.nyx_launcher.home.wallpaper

import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.wallpaper.LayerTransform
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Nyx's wallpaper edit-session coordinator — a plain object held by MainActivity
 * (ClockDelegate pattern), a faithful port of Kolibri's WallpaperDelegate edit
 * session minus the pieces Nyx doesn't need: no composite cache / refillCache
 * (Nyx's wallpaper view is never torn down, so there's no drawer→home anti-flash
 * gap), no scrim-reset offer, no orphan GC here (MainActivity already runs the
 * setter's startup reclaim).
 *
 * It owns the live [wallpaperState] (seeded from and mirrored back to the shared
 * [WallpaperRepository]), and drives the transactional edit session:
 * Enter snapshots the state; layer add/remove/swap/transform mutate it live and
 * persist; Commit keeps the result (deletes deferred-removal files); Cancel
 * restores the snapshot SYNCHRONOUSLY (so the re-render reads it immediately) and
 * cleans up files added during the session.
 *
 * Functional-core / imperative-shell: every change is a pure [Mutation]; [applyState]
 * is the one synchronous critical section (main-dispatcher confined, non-suspending),
 * [persist] the async epilogue.
 */
class NyxWallpaperEditCoordinator(
    private val repository: WallpaperRepository,
    private val fileManager: WallpaperFileManager,
    private val displaySettings: WallpaperDisplaySettings,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _wallpaperState = MutableStateFlow(WallpaperState.NONE)
    val wallpaperState: StateFlow<WallpaperState> = _wallpaperState.asStateFlow()

    private val _isEditMode = MutableStateFlow(false)
    val isEditMode: StateFlow<Boolean> = _isEditMode.asStateFlow()

    /** One-shot "focus this new layer" hint (the added layer's id), consumed by the view. */
    private val _pendingFocusLayerId = MutableStateFlow<String?>(null)
    val pendingFocusLayerId: StateFlow<String?> = _pendingFocusLayerId.asStateFlow()

    fun consumePendingFocusLayerId(): String? {
        val id = _pendingFocusLayerId.value
        _pendingFocusLayerId.value = null
        return id
    }

    // --- Edit session state ---
    private var editSnapshot: WallpaperState? = null
    private var editRollbackGeneration = 0L
    private val pendingRemovalsOnCommit = mutableSetOf<String>()
    private val pendingRemovalsOnCancel = mutableSetOf<String>()

    private val backdropToggleMutex = Mutex()
    private var lastWrittenBackdrop: WallpaperBackdrop? = null

    /** Mirror the repository's persisted state into the live state (external changes). */
    fun start() {
        scope.launch {
            repository.wallpaperState.collect { state -> _wallpaperState.value = state }
        }
    }

    // ---- state-mutation core ----

    private data class Mutation(
        val newState: WallpaperState,
        val deleteNow: List<String> = emptyList(),
        val deferToCommit: List<String> = emptyList(),
        val trackForCancel: List<String> = emptyList(),
        val focusLayerId: String? = null,
    )

    /** THE synchronous critical section: write flows LAST, non-suspending. */
    private fun applyState(m: Mutation) {
        m.focusLayerId?.let { _pendingFocusLayerId.value = it }
        pendingRemovalsOnCommit.addAll(m.deferToCommit)
        pendingRemovalsOnCancel.addAll(m.trackForCancel)
        _wallpaperState.value = m.newState
    }

    private suspend fun persist(m: Mutation) {
        repository.saveWallpaperState(m.newState)
        if (m.deleteNow.isNotEmpty()) {
            withContext(ioDispatcher) { m.deleteNow.forEach { fileManager.deleteFile(it) } }
        }
    }

    private fun commit(errorMessage: String, m: Mutation) {
        applyState(m)
        launchSafe(errorMessage) { persist(m) }
    }

    // ---- session lifecycle ----

    fun onEnterEditMode() {
        editSnapshot = _wallpaperState.value
        pendingRemovalsOnCommit.clear()
        pendingRemovalsOnCancel.clear()
        _isEditMode.value = true
    }

    fun onCommitEditMode() {
        val filesToDelete = pendingRemovalsOnCommit.toSet()
        pendingRemovalsOnCommit.clear()
        pendingRemovalsOnCancel.clear()
        editSnapshot = null
        if (filesToDelete.isNotEmpty()) {
            launchSafe("Error committing wallpaper edit") {
                withContext(ioDispatcher) { filesToDelete.forEach { fileManager.deleteFile(it) } }
            }
        }
        _isEditMode.value = false
    }

    fun onCancelEditMode() {
        editRollbackGeneration++
        val snapshot = editSnapshot
        val filesToDelete = pendingRemovalsOnCancel.toSet()
        if (snapshot != null) _wallpaperState.value = snapshot
        _pendingFocusLayerId.value = null
        pendingRemovalsOnCancel.clear()
        pendingRemovalsOnCommit.clear()
        editSnapshot = null
        if (snapshot != null || filesToDelete.isNotEmpty()) {
            launchSafe("Error canceling wallpaper edit") {
                if (snapshot != null) repository.saveWallpaperState(snapshot)
                if (filesToDelete.isNotEmpty()) {
                    withContext(ioDispatcher) { filesToDelete.forEach { fileManager.deleteFile(it) } }
                }
            }
        }
        _isEditMode.value = false
    }

    // ---- layer operations ----

    fun onAddLayer(imageUri: Uri) {
        // Capture generation before the suspending copy; a synchronous Cancel can
        // restore the snapshot mid-copy, so an add resuming across it must discard.
        val rollbackGenAtStart = editRollbackGeneration
        launchSafe("Error adding wallpaper layer") {
            val internalUri = fileManager.copyToInternal(imageUri)
            if (internalUri == null) {
                TimberWrapper.silentError("Failed to copy layer image to internal storage")
                return@launchSafe
            }
            val internalUriString = internalUri.toString()
            if (editRollbackGeneration != rollbackGenAtStart) {
                withContext(ioDispatcher) { fileManager.deleteFile(internalUriString) }
                return@launchSafe
            }
            // No suspension from here to applyState — atomic.
            val current = _wallpaperState.value
            val newLayer = WallpaperLayerState(imageUri = internalUriString)
            val mutation = Mutation(
                newState = current.withAddedLayer(newLayer),
                trackForCancel = if (_isEditMode.value) listOf(internalUriString) else emptyList(),
                focusLayerId = newLayer.id,
            )
            applyState(mutation)
            persist(mutation)
        }
    }

    fun onRemoveLayer(layerIndex: Int) {
        val current = _wallpaperState.value
        val layerUri = current.getLayer(layerIndex)?.imageUri
        val inEdit = _isEditMode.value
        commit(
            "Error removing wallpaper layer",
            Mutation(
                newState = current.withRemovedLayer(layerIndex),
                deleteNow = if (!inEdit && layerUri != null) listOf(layerUri) else emptyList(),
                deferToCommit = if (inEdit && layerUri != null) listOf(layerUri) else emptyList(),
            ),
        )
    }

    fun onSwapLayers(indexA: Int, indexB: Int) =
        commit("Error swapping wallpaper layers", Mutation(_wallpaperState.value.withSwappedLayers(indexA, indexB)))

    fun onSaveLayerTransform(layerIndex: Int, scale: Float, translateX: Float, translateY: Float, captureSampleSize: Int?) =
        commit(
            "Error saving layer transform",
            Mutation(
                _wallpaperState.value.withUpdatedLayer(layerIndex) {
                    it.copy(scale = scale, translateX = translateX, translateY = translateY, captureSampleSize = captureSampleSize)
                },
            ),
        )

    fun onSaveAllLayerTransforms(transforms: List<LayerTransform>) {
        var state = _wallpaperState.value
        transforms.forEachIndexed { index, t ->
            state = state.withUpdatedLayer(index) {
                it.copy(scale = t.scale, translateX = t.translateX, translateY = t.translateY, captureSampleSize = t.sampleSize)
            }
        }
        commit("Error saving all layer transforms", Mutation(state))
    }

    // ---- backdrop ----

    /** Flip system-wallpaper ↔ black; serialized + double-tap-safe off the last written value. */
    fun onToggleBackdrop() = launchSafe("Error toggling wallpaper backdrop") {
        backdropToggleMutex.withLock {
            val current = lastWrittenBackdrop ?: displaySettings.wallpaperBackdropFlow.first()
            val next = when (current) {
                WallpaperBackdrop.SYSTEM_WALLPAPER -> WallpaperBackdrop.BLACK
                WallpaperBackdrop.BLACK -> WallpaperBackdrop.SYSTEM_WALLPAPER
            }
            displaySettings.setWallpaperBackdrop(next)
            lastWrittenBackdrop = next
        }
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

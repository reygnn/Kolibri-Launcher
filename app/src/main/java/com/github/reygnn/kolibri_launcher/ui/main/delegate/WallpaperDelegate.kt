/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.github.reygnn.kolibri_launcher.ui.main.delegate

import android.content.Context
import android.net.Uri
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.data.WallpaperFileManager
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.usecase.ClearWallpaperUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SaveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetWallpaperImageUseCase
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Delegate responsible for wallpaper management:
 * single-image wallpaper, multi-layer wallpaper,
 * edit mode, transforms, layer properties (alpha, blend, visibility).
 *
 * == EDIT SESSION ==
 * The delegate exposes a transactional edit-session API:
 *   [onEnterWallpaperEditMode] snapshots the current state. All mutations
 *   during the session can be rolled back via [onCancelWallpaperEditMode]
 *   or confirmed via [onCommitWallpaperEditMode]. Deletions made during
 *   the session defer their physical file deletion until commit, so that
 *   cancel can truly restore the state — including the file on disk.
 */
class WallpaperDelegate(
    private val context: Context,
    private val observeWallpaperStateUseCase: ObserveWallpaperStateUseCase,
    private val saveWallpaperStateUseCase: SaveWallpaperStateUseCase,
    private val setWallpaperImageUseCase: SetWallpaperImageUseCase,
    private val clearWallpaperUseCase: ClearWallpaperUseCase,
    private val wallpaperFileManager: WallpaperFileManager,
    private val scope: DelegateScope
) {

    // --- Exposed State ---

    private val _wallpaperState = MutableStateFlow(WallpaperState.NONE)
    val wallpaperState: StateFlow<WallpaperState> = _wallpaperState.asStateFlow()

    private val _isWallpaperEditMode = MutableStateFlow(false)
    val isWallpaperEditMode: StateFlow<Boolean> = _isWallpaperEditMode.asStateFlow()

    // --- Edit Session State ---

    /**
     * Snapshot of the wallpaper state at the moment edit mode was entered.
     * Used by [onCancelWallpaperEditMode] to roll back all changes made
     * during the edit session (transforms, adds, removes, swaps).
     * null when not in an edit session.
     */
    private var editSnapshot: WallpaperState? = null

    /**
     * URIs of layers removed during the current edit session.
     * The physical file deletion is deferred until commit so that a
     * cancel can fully restore the state – including the file on disk.
     */
    private val pendingRemovalsOnCommit = mutableSetOf<Uri>()

    /**
     * URIs of internal files created by layers added during the current
     * edit session. If the session is canceled, these orphan files are
     * cleaned up; if committed, they are kept.
     */
    private val pendingRemovalsOnCancel = mutableSetOf<Uri>()

    // --- Init ---

    fun start() {
        scope.launchSafe("Error observing wallpaper state") {
            observeWallpaperStateUseCase().collect { state ->
                _wallpaperState.value = state

                // Periodic-ish orphan GC: each time we observe a new state
                // (which happens at start and after every save), drop any
                // file on disk that no longer belongs to the authoritative
                // state. Cheap (single-directory listFiles) and safe — files
                // referenced by the state itself are always preserved.
                // NOTE: intentionally skipped while an edit session is live —
                // pending-cancel files must survive until the session ends.
                if (!_isWallpaperEditMode.value) {
                    try {
                        wallpaperFileManager.gcOrphans(state.referencedUris)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Wallpaper orphan GC failed")
                    }
                }
            }
        }
    }

    // ===========================================
    // SINGLE-LAYER WALLPAPER
    // ===========================================

    fun onSetWallpaperImage(imageUri: Uri) = scope.launchSafe("Error setting wallpaper image") {
        try {
            val displayName = getDisplayName(imageUri)

            val internalUri = wallpaperFileManager.copyToInternal(imageUri)
            if (internalUri == null) {
                TimberWrapper.silentError("Failed to copy wallpaper to internal storage")
                scope.sendEvent(UiEvent.ShowToast(R.string.error_generic))
                return@launchSafe
            }
            setWallpaperImageUseCase(internalUri)

            val message = displayName ?: context.getString(R.string.wallpaper_set_success)
            scope.sendEvent(UiEvent.ShowToastFromString(message))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting wallpaper image")
            scope.sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    fun onSaveWallpaperTransform(
        scale: Float,
        translateX: Float,
        translateY: Float
    ) = scope.launchSafe("Error saving wallpaper transform") {
        try {
            val currentState = _wallpaperState.value
            if (currentState.hasWallpaper) {
                saveWallpaperStateUseCase.updateTransform(
                    currentState = currentState,
                    scale = scale,
                    translateX = translateX,
                    translateY = translateY
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error saving wallpaper transform")
        }
    }

    fun onClearWallpaper() = scope.launchSafe("Error clearing wallpaper") {
        try {
            wallpaperFileManager.clearAll()
            clearWallpaperUseCase()
            scope.sendEvent(UiEvent.ShowToast(R.string.wallpaper_removed))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error clearing wallpaper")
            scope.sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    // ===========================================
    // EDIT MODE
    // ===========================================

    /**
     * Enters edit mode and snapshots the current wallpaper state.
     *
     * While in edit mode:
     * - Layer removals are persisted in state, but the underlying file
     *   on disk is kept alive (tracked in [pendingRemovalsOnCommit]).
     * - Added layers are tracked in [pendingRemovalsOnCancel] so their
     *   files can be cleaned up if the session is canceled.
     *
     * The session ends with either [onCommitWallpaperEditMode] (confirm)
     * or [onCancelWallpaperEditMode] (roll back all changes).
     */
    fun onEnterWallpaperEditMode() {
        editSnapshot = _wallpaperState.value
        pendingRemovalsOnCommit.clear()
        pendingRemovalsOnCancel.clear()
        _isWallpaperEditMode.value = true
    }

    /**
     * Exits edit mode and commits the session: deferred file deletions
     * from [onRemoveWallpaperLayer] are carried out, orphan tracking
     * is discarded, and in-memory state stays as-is (already persisted).
     */
    fun onCommitWallpaperEditMode() {
        val filesToDelete = pendingRemovalsOnCommit.toSet()
        pendingRemovalsOnCommit.clear()
        pendingRemovalsOnCancel.clear()
        editSnapshot = null
        _isWallpaperEditMode.value = false

        if (filesToDelete.isEmpty()) return

        scope.launchSafe("Error committing wallpaper edit") {
            try {
                filesToDelete.forEach { uri ->
                    try {
                        wallpaperFileManager.deleteFile(uri)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error deleting pending layer file")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error committing wallpaper edit")
            }
        }
    }

    /**
     * Exits edit mode and rolls the session back to the snapshot taken
     * on enter:
     * - In-memory [wallpaperState] is restored SYNCHRONOUSLY so callers
     *   can read the reverted value immediately after this method returns.
     * - Persistence and orphan-file cleanup happen asynchronously.
     * - Files of layers removed during the session are kept (they are
     *   referenced again by the restored snapshot).
     * - Files of layers added during the session are deleted.
     */
    fun onCancelWallpaperEditMode() {
        val snapshot = editSnapshot
        val filesToDelete = pendingRemovalsOnCancel.toSet()

        if (snapshot != null) {
            _wallpaperState.value = snapshot
        }
        pendingRemovalsOnCancel.clear()
        pendingRemovalsOnCommit.clear()
        editSnapshot = null
        _isWallpaperEditMode.value = false

        if (snapshot == null && filesToDelete.isEmpty()) return

        scope.launchSafe("Error canceling wallpaper edit") {
            try {
                if (snapshot != null) {
                    saveWallpaperStateUseCase(snapshot)
                }
                filesToDelete.forEach { uri ->
                    try {
                        wallpaperFileManager.deleteFile(uri)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error deleting canceled layer file")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error canceling wallpaper edit")
            }
        }
    }

    /**
     * Legacy API. Routes to [onEnterWallpaperEditMode] or
     * [onCommitWallpaperEditMode]. Kept to preserve behavior of call
     * sites that don't distinguish between commit and cancel
     * (e.g. long-press exit).
     */
    fun onSetWallpaperEditMode(enabled: Boolean) {
        if (enabled) onEnterWallpaperEditMode() else onCommitWallpaperEditMode()
    }

    fun onToggleWallpaperEditMode() {
        if (_isWallpaperEditMode.value) onCommitWallpaperEditMode() else onEnterWallpaperEditMode()
    }

    // ===========================================
    // MULTI-LAYER: MANAGEMENT
    // ===========================================

    fun onAddWallpaperLayer(imageUri: Uri, label: String? = null) =
        scope.launchSafe("Error adding wallpaper layer") {
            try {
                val internalUri = wallpaperFileManager.copyToInternal(imageUri)
                if (internalUri == null) {
                    TimberWrapper.silentError("Failed to copy layer image to internal storage")
                    return@launchSafe
                }

                // While in edit mode, track this file so its orphan copy on
                // disk gets cleaned up if the user cancels the session.
                if (_isWallpaperEditMode.value) {
                    pendingRemovalsOnCancel.add(internalUri)
                }

                val current = _wallpaperState.value

                // Migration: Single → Multi beim ersten addLayer
                val base = if (!current.isMultiLayer && current.hasWallpaper) {
                    current.toMultiLayer()
                } else {
                    current
                }

                // Auto-label: pick the lowest unused "Layer N" number so that
                // after a delete+add cycle we don't get "Layer 3" twice.
                val resolvedLabel = label ?: nextFreeAutoLabel(base)

                val newLayer = WallpaperLayerState(
                    imageUri = internalUri,
                    label = resolvedLabel
                )

                val newState = base.withAddedLayer(newLayer)
                _wallpaperState.value = newState
                saveWallpaperStateUseCase(newState)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error adding wallpaper layer")
            }
        }

    /**
     * Findet die kleinste freie Nummer N, so dass "Layer N" noch nicht im
     * [state] vorkommt. Verhindert Kollisionen nach Delete-then-Add-Zyklen.
     *
     * Defensiv gegen Mock-/Test-States: greift nicht blind auf [layers] zu,
     * fällt bei Problemen auf "Layer 1" zurück.
     */
    private fun nextFreeAutoLabel(state: WallpaperState): String {
        val used = try {
            state.layers.mapNotNull { layer ->
                layer.label?.removePrefix("Layer ")?.toIntOrNull()
            }.toSet()
        } catch (e: Throwable) {
            emptySet()
        }
        var n = 1
        while (n in used) n++
        return "Layer $n"
    }

    fun onRemoveWallpaperLayer(layerIndex: Int) =
        scope.launchSafe("Error removing wallpaper layer") {
            try {
                val current = _wallpaperState.value
                val layerUri = current.getLayer(layerIndex)?.imageUri

                // In edit mode: defer the physical delete until commit so that
                // a cancel can restore the snapshot including the file on disk.
                // Outside edit mode: delete immediately as before.
                if (layerUri != null) {
                    if (_isWallpaperEditMode.value) {
                        pendingRemovalsOnCommit.add(layerUri)
                    } else {
                        wallpaperFileManager.deleteFile(layerUri)
                    }
                }

                val newState = current.withRemovedLayer(layerIndex)

                // Unified persist path: set in-memory + persist. WallpaperManager's
                // saveWallpaperState handles the "no wallpaper" case by wiping all
                // keys, so we don't need a separate clearWallpaperUseCase call here.
                // (That avoids a brief UI flicker when the last layer is removed.)
                _wallpaperState.value = newState
                saveWallpaperStateUseCase(newState)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error removing wallpaper layer")
            }
        }

    fun onSwapWallpaperLayers(indexA: Int, indexB: Int) =
        scope.launchSafe("Error swapping wallpaper layers") {
            try {
                val newState = _wallpaperState.value.withSwappedLayers(indexA, indexB)
                _wallpaperState.value = newState
                saveWallpaperStateUseCase(newState)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error swapping wallpaper layers")
            }
        }

    // ===========================================
    // MULTI-LAYER: TRANSFORMS
    // ===========================================

    fun onSaveLayerTransform(
        layerIndex: Int,
        scale: Float,
        translateX: Float,
        translateY: Float
    ) = scope.launchSafe("Error saving layer transform") {
        try {
            val newState = _wallpaperState.value.withUpdatedLayer(layerIndex) {
                it.copy(scale = scale, translateX = translateX, translateY = translateY)
            }
            _wallpaperState.value = newState
            saveWallpaperStateUseCase(newState)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error saving layer transform")
        }
    }

    fun onSaveAllLayerTransforms(
        transforms: List<Triple<Float, Float, Float>>
    ) = scope.launchSafe("Error saving all layer transforms") {
        try {
            var state = _wallpaperState.value
            transforms.forEachIndexed { index, (scale, tx, ty) ->
                state = state.withUpdatedLayer(index) {
                    it.copy(scale = scale, translateX = tx, translateY = ty)
                }
            }
            _wallpaperState.value = state
            saveWallpaperStateUseCase(state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error saving all layer transforms")
        }
    }

    // ===========================================
    // MULTI-LAYER: PROPERTIES
    // ===========================================

    fun onSetLayerAlpha(layerIndex: Int, alpha: Float) =
        scope.launchSafe("Error setting layer alpha") {
            try {
                val newState = _wallpaperState.value.withUpdatedLayer(layerIndex) {
                    it.copy(alpha = alpha.coerceIn(0f, 1f))
                }
                _wallpaperState.value = newState
                saveWallpaperStateUseCase(newState)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error setting layer alpha")
            }
        }

    fun onSetLayerBlendMode(layerIndex: Int, blendModeName: String?) =
        scope.launchSafe("Error setting layer blend mode") {
            try {
                val newState = _wallpaperState.value.withUpdatedLayer(layerIndex) {
                    it.copy(blendModeName = blendModeName)
                }
                _wallpaperState.value = newState
                saveWallpaperStateUseCase(newState)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error setting layer blend mode")
            }
        }

    fun onSetLayerVisibility(layerIndex: Int, isVisible: Boolean) =
        scope.launchSafe("Error setting layer visibility") {
            try {
                val newState = _wallpaperState.value.withUpdatedLayer(layerIndex) {
                    it.copy(isVisible = isVisible)
                }
                _wallpaperState.value = newState
                saveWallpaperStateUseCase(newState)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error setting layer visibility")
            }
        }

    // ===========================================
    // INTERNAL
    // ===========================================

    private fun getDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Throwable) {
            null
        }
    }
}
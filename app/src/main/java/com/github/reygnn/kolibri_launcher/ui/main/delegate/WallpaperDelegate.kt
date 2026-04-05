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

    // --- Init ---

    fun start() {
        scope.launchSafe("Error observing wallpaper state") {
            observeWallpaperStateUseCase().collect { state ->
                _wallpaperState.value = state
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

    fun onSetWallpaperEditMode(enabled: Boolean) {
        _isWallpaperEditMode.value = enabled
    }

    fun onToggleWallpaperEditMode() {
        _isWallpaperEditMode.value = !_isWallpaperEditMode.value
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

                val current = _wallpaperState.value

                // Migration: Single → Multi beim ersten addLayer
                val base = if (!current.isMultiLayer && current.hasWallpaper) {
                    current.toMultiLayer()
                } else {
                    current
                }

                val newLayer = WallpaperLayerState(
                    imageUri = internalUri,
                    label = label ?: "Layer ${base.layerCount + 1}"
                )

                val newState = base.withAddedLayer(newLayer)
                _wallpaperState.value = newState
                saveWallpaperStateUseCase(newState.forPersistence())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error adding wallpaper layer")
            }
        }

    fun onRemoveWallpaperLayer(layerIndex: Int) =
        scope.launchSafe("Error removing wallpaper layer") {
            try {
                val current = _wallpaperState.value

                current.getLayer(layerIndex)?.imageUri?.let { uri ->
                    wallpaperFileManager.deleteFile(uri)
                }

                val newState = current.withRemovedLayer(layerIndex)

                if (newState.layers.isEmpty() && !newState.hasWallpaper) {
                    clearWallpaperUseCase()
                } else {
                    _wallpaperState.value = newState
                    saveWallpaperStateUseCase(newState.forPersistence())
                }
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
                saveWallpaperStateUseCase(newState.forPersistence())
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
            saveWallpaperStateUseCase(newState.forPersistence())
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
            saveWallpaperStateUseCase(state.forPersistence())
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
                saveWallpaperStateUseCase(newState.forPersistence())
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
                saveWallpaperStateUseCase(newState.forPersistence())
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
                saveWallpaperStateUseCase(newState.forPersistence())
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
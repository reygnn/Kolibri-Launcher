package com.github.reygnn.nyx_launcher.home.wallpaper

import com.github.reygnn.launcher.common.ui.wallpaperfab.LayerButtonsState

import android.view.View
import android.view.ViewStub
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperEditState
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperEditTransition
import com.github.reygnn.launcher.common.ui.wallpaper.ZoomableImageView
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.wallpaper.LayerTransform
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperMemoryReport
import com.github.reygnn.launcher.core.wallpaper.WallpaperSaveAction
import com.github.reygnn.launcher.core.wallpaper.formatMegabytes
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.home.model.FabPosition
import com.github.reygnn.nyx_launcher.home.wallpaperfab.CommandsPanel
import com.github.reygnn.nyx_launcher.home.wallpaperfab.SpeedDialFabCluster
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Owns Nyx's wallpaper-edit-mode UI surface — a faithful port of Kolibri's
 * WallpaperEditController, driving the same shared [ZoomableImageView] and the
 * copied [SpeedDialFabCluster] + [CommandsPanel] through their public APIs. Where
 * Kolibri routes actions through its LauncherViewModel, Nyx routes them to the
 * [NyxWallpaperEditCoordinator]; the edit-mode view-state transitions come from the
 * shared [WallpaperEditTransition].
 *
 * The overlay (interceptor, hint, panel, cluster) is inflated lazily from a
 * ViewStub on first entry into edit mode ([ensureOverlayInflated]).
 *
 * @param stub the wallpaperEditOverlayStub in the home layout.
 * @param wallpaperView the shared render surface.
 * @param dimTarget the home content view dimmed (alpha 0.7) while editing.
 * @param coordinator the edit-session engine.
 * @param onFabPositionChanged persists the dragged FAB-cluster position.
 * @param launchLayerPicker opens the image picker to add a layer.
 * @param rerenderWallpaper forces a re-render from current state (used on Cancel).
 */
class NyxWallpaperEditController(
    private val stub: ViewStub,
    private val wallpaperView: ZoomableImageView,
    private val dimTarget: View,
    private val coordinator: NyxWallpaperEditCoordinator,
    private val onFabPositionChanged: (FabPosition) -> Unit,
    private val launchLayerPicker: () -> Unit,
    private val rerenderWallpaper: () -> Unit,
) {
    private var overlayRoot: View? = null
    private var fabClusterView: SpeedDialFabCluster? = null
    private var commandsPanelView: CommandsPanel? = null
    private var touchInterceptorView: View? = null

    private var pendingFabPosition: FabPosition? = null
    private var pendingBackdrop: WallpaperBackdrop? = null
    private var inEditMode = false

    private val fabCluster: SpeedDialFabCluster get() = requireNotNull(fabClusterView)
    private val commandsPanel: CommandsPanel get() = requireNotNull(commandsPanelView)
    private val touchInterceptor: View get() = requireNotNull(touchInterceptorView)

    private fun ensureOverlayInflated() {
        if (overlayRoot != null) return
        val root = stub.inflate()
        overlayRoot = root
        fabClusterView = root.findViewById(R.id.wallpaperFabCluster)
        commandsPanelView = root.findViewById(R.id.wallpaperCommandsPanel)
        touchInterceptorView = root.findViewById(R.id.wallpaperTouchInterceptor)

        fabCluster.onPositionChanged = { x, y -> onFabPositionChanged(FabPosition(x, y)) }

        pendingFabPosition?.let {
            fabCluster.applyPosition(it.xFraction, it.yFraction)
            pendingFabPosition = null
        }
        pendingBackdrop?.let {
            commandsPanel.setBackdropToggleIcon(backdropIcon(it))
            pendingBackdrop = null
        }
    }

    // ---- edit-mode state apply ----

    private fun applyEditState(state: WallpaperEditState) {
        wallpaperView.isEditMode = state.isEditMode
        wallpaperView.isSnapEnabled = state.snapEnabled
        wallpaperView.isHorizontalSnapEnabled = state.horizontalSnapEnabled
        wallpaperView.isVerticalSnapEnabled = state.verticalSnapEnabled
        wallpaperView.snapMode = state.snapMode
        overlayRoot?.visibility = if (state.overlayVisible) View.VISIBLE else View.GONE
        dimTarget.alpha = state.rootLayoutAlpha
    }

    fun applyFabPosition(position: FabPosition) {
        val cluster = fabClusterView
        if (cluster != null) {
            cluster.applyPosition(position.xFraction, position.yFraction)
        } else {
            pendingFabPosition = position
        }
    }

    fun applyBackdrop(backdrop: WallpaperBackdrop) {
        if (commandsPanelView != null) {
            commandsPanel.setBackdropToggleIcon(backdropIcon(backdrop))
        } else {
            pendingBackdrop = backdrop
        }
    }

    private fun backdropIcon(backdrop: WallpaperBackdrop): Int = when (backdrop) {
        WallpaperBackdrop.SYSTEM_WALLPAPER -> R.drawable.ic_backdrop_system
        WallpaperBackdrop.BLACK -> R.drawable.ic_backdrop_black
    }

    // ---- transitions ----

    fun applyEditMode(isEditMode: Boolean) {
        inEditMode = isEditMode
        try {
            val target = WallpaperEditTransition.targetState(WallpaperEditTransition.forMode(isEditMode))
            if (isEditMode) {
                ensureOverlayInflated()
                applyEditState(target)
                wireEditModeListeners()
            } else {
                applyEditState(target)
                if (overlayRoot != null) {
                    clearEditModeListeners()
                    commandsPanel.hidePanel()
                }
            }
        } catch (e: Throwable) {
            // Orchestration boundary for edit-mode entry/exit (mirrors Kolibri).
            TimberWrapper.silentError(e, "Error updating wallpaper edit mode")
        }
    }

    private fun wireEditModeListeners() {
        touchInterceptor.setOnTouchListener { _, event -> wallpaperView.onTouchEvent(event) }

        fabCluster.setOnSaveClicked { commitEdit() }
        fabCluster.setOnCancelClicked {
            coordinator.onCancelEditMode()
            rerenderWallpaper()
        }
        fabCluster.setOnOneToOneClicked { wallpaperView.showOriginalSize() }
        fabCluster.setOnFitWidthClicked { wallpaperView.fitToWidth() }
        fabCluster.setOnAddLayerClicked {
            saveCurrentViewTransforms()
            launchLayerPicker()
        }
        fabCluster.setOnOpenCommandsClicked { commandsPanel.togglePanel() }

        commandsPanel.setOnCloseClicked { commandsPanel.hidePanel() }
        commandsPanel.setOnMemInfoClicked { showWallpaperMemoryDialog() }
        commandsPanel.setOnBackdropToggleClicked { coordinator.onToggleBackdrop() }

        updateSnapButtonIcon(wallpaperView.isSnapEnabled)
        commandsPanel.setOnSnapToggleClicked {
            wallpaperView.isSnapEnabled = !wallpaperView.isSnapEnabled
            updateSnapButtonIcon(wallpaperView.isSnapEnabled)
        }
        updateSnapModeButtonIcon(wallpaperView.snapMode)
        commandsPanel.setOnSnapModeClicked {
            wallpaperView.snapMode = when (wallpaperView.snapMode) {
                ZoomableImageView.SnapMode.EDGE -> ZoomableImageView.SnapMode.CENTER
                ZoomableImageView.SnapMode.CENTER -> ZoomableImageView.SnapMode.EDGE
            }
            updateSnapModeButtonIcon(wallpaperView.snapMode)
            updateHorizontalSnapButtonIcon(wallpaperView.isHorizontalSnapEnabled, wallpaperView.snapMode)
            updateVerticalSnapButtonIcon(wallpaperView.isVerticalSnapEnabled, wallpaperView.snapMode)
        }
        updateHorizontalSnapButtonIcon(wallpaperView.isHorizontalSnapEnabled, wallpaperView.snapMode)
        commandsPanel.setOnHorizontalSnapClicked {
            wallpaperView.isHorizontalSnapEnabled = !wallpaperView.isHorizontalSnapEnabled
            updateHorizontalSnapButtonIcon(wallpaperView.isHorizontalSnapEnabled, wallpaperView.snapMode)
        }
        updateVerticalSnapButtonIcon(wallpaperView.isVerticalSnapEnabled, wallpaperView.snapMode)
        commandsPanel.setOnVerticalSnapClicked {
            wallpaperView.isVerticalSnapEnabled = !wallpaperView.isVerticalSnapEnabled
            updateVerticalSnapButtonIcon(wallpaperView.isVerticalSnapEnabled, wallpaperView.snapMode)
        }

        applyLayerButtonsState()
        updateLayerIndicator()

        commandsPanel.setOnLayerDeleteClicked {
            val activeIndex = wallpaperView.activeLayerIndex
            if (activeIndex >= 0 && wallpaperView.layerCount > 0) {
                saveCurrentViewTransforms()
                coordinator.onRemoveLayer(activeIndex)
            }
        }
        commandsPanel.setOnLayerUpClicked {
            val activeIndex = wallpaperView.activeLayerIndex
            if (activeIndex < wallpaperView.layerCount - 1) {
                saveCurrentViewTransforms()
                wallpaperView.moveLayerUp(activeIndex)
                coordinator.onSwapLayers(activeIndex, activeIndex + 1)
                updateLayerIndicator()
                applyLayerButtonsState()
            }
        }
        commandsPanel.setOnLayerDownClicked {
            val activeIndex = wallpaperView.activeLayerIndex
            if (activeIndex > 0) {
                saveCurrentViewTransforms()
                wallpaperView.moveLayerDown(activeIndex)
                coordinator.onSwapLayers(activeIndex, activeIndex - 1)
                updateLayerIndicator()
                applyLayerButtonsState()
            }
        }
        wallpaperView.onLayerTapped = { _, _ ->
            updateLayerIndicator()
            applyLayerButtonsState()
        }
    }

    /**
     * Commits the edit session: flushes the live view transforms into state (so
     * pan/zoom aren't lost on rebuild), then commits. Used by the Save FAB AND the
     * back-press exit — both must flush, else back-press would drop unsaved transforms.
     */
    fun commitEdit() {
        val state = coordinator.wallpaperState.value
        val action = WallpaperSaveAction.decide(
            isMultiLayer = state.layerCount >= 2,
            hasWallpaper = state.hasWallpaper,
            allLayerTransforms = readAllLayerTransforms(),
            singleTransform = readSingleTransform(),
        )
        dispatchSaveAction(action)
        coordinator.onCommitEditMode()
    }

    private fun clearEditModeListeners() {
        touchInterceptor.setOnTouchListener(null)
        fabCluster.setOnSaveClicked { }
        fabCluster.setOnCancelClicked { }
        fabCluster.setOnAddLayerClicked { }
        fabCluster.setOnOneToOneClicked { }
        fabCluster.setOnFitWidthClicked { }
        fabCluster.setOnOpenCommandsClicked { }
        commandsPanel.setOnSnapToggleClicked { }
        commandsPanel.setOnSnapModeClicked { }
        commandsPanel.setOnHorizontalSnapClicked { }
        commandsPanel.setOnVerticalSnapClicked { }
        commandsPanel.setOnLayerDeleteClicked { }
        commandsPanel.setOnLayerUpClicked { }
        commandsPanel.setOnLayerDownClicked { }
        commandsPanel.setOnMemInfoClicked { }
        commandsPanel.setOnBackdropToggleClicked { }
        commandsPanel.setOnCloseClicked { }
        wallpaperView.onLayerTapped = null
    }

    private fun showWallpaperMemoryDialog() {
        val ctx = commandsPanel.context
        val rows = wallpaperView.collectWallpaperMemoryRows()
        val message = if (rows.isEmpty()) {
            ctx.getString(R.string.wallpaper_memory_empty)
        } else {
            val report = WallpaperMemoryReport.of(rows)
            buildString {
                for (row in rows) {
                    val dims = if (row.isDownsampled) {
                        ctx.getString(
                            R.string.wallpaper_memory_source,
                            row.decodedWidth, row.decodedHeight, row.originalWidth, row.originalHeight,
                        )
                    } else {
                        "${row.decodedWidth}×${row.decodedHeight}"
                    }
                    appendLine(
                        ctx.getString(R.string.wallpaper_memory_row, row.index + 1, formatMegabytes(row.bytes), row.config, dims),
                    )
                }
                appendLine()
                append(ctx.getString(R.string.wallpaper_memory_total, formatMegabytes(report.totalBytes)))
            }
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.wallpaper_memory_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---- transform persistence ----

    fun saveCurrentViewTransforms() {
        val state = coordinator.wallpaperState.value
        val isMultiLayerEffective = state.layerCount >= 2 && wallpaperView.isMultiLayerMode
        val action = WallpaperSaveAction.decide(
            isMultiLayer = isMultiLayerEffective,
            hasWallpaper = state.hasWallpaper,
            allLayerTransforms = readAllLayerTransforms(),
            singleTransform = readSingleTransform(),
        )
        dispatchSaveAction(action)
    }

    private fun readAllLayerTransforms(): List<LayerTransform> =
        (0 until wallpaperView.layerCount).map { i ->
            val layer = wallpaperView.getLayer(i)
            LayerTransform(
                scale = layer?.scale ?: 1f,
                translateX = layer?.translateX ?: 0f,
                translateY = layer?.translateY ?: 0f,
                sampleSize = layer?.sampleSize ?: 1,
            )
        }

    private fun readSingleTransform(): LayerTransform = LayerTransform(
        scale = wallpaperView.currentScale,
        translateX = wallpaperView.currentTranslateX,
        translateY = wallpaperView.currentTranslateY,
        sampleSize = wallpaperView.singleSampleSize,
    )

    private fun dispatchSaveAction(action: WallpaperSaveAction) {
        when (action) {
            is WallpaperSaveAction.SaveAllLayers -> coordinator.onSaveAllLayerTransforms(action.transforms)
            is WallpaperSaveAction.SaveSingle ->
                coordinator.onSaveLayerTransform(0, action.scale, action.translateX, action.translateY, action.sampleSize)
            is WallpaperSaveAction.NoOp -> Unit
        }
    }

    // ---- layer UI updates ----

    /**
     * Re-syncs the edit toolbar (indicator + layer buttons) after an async view
     * rebuild finishes — wired to the binder's onRebuildComplete. Without this, the
     * CommandsPanel stays stale after an Add/Delete until the next layer tap
     * (mirrors Kolibri's HomeFragment.updateWallpaper edit-mode refresh). No-op
     * outside edit mode (both callees guard on the inflated overlay).
     */
    fun onWallpaperRebuilt() {
        if (!inEditMode) return
        updateLayerIndicator()
        applyLayerButtonsState()
    }

    fun updateLayerIndicator() {
        overlayRoot ?: return
        val count = wallpaperView.layerCount
        val active = wallpaperView.activeLayerIndex
        if (count > 0) {
            commandsPanel.setLayerIndicator(
                commandsPanel.context.getString(R.string.wallpaper_layer_indicator, active + 1, count),
                visible = true,
            )
        } else {
            commandsPanel.setLayerIndicator(null, visible = false)
        }
    }

    fun applyLayerButtonsState() {
        overlayRoot ?: return
        val state = LayerButtonsState.from(
            isMultiLayerMode = wallpaperView.isMultiLayerMode,
            layerCount = wallpaperView.layerCount,
            activeLayerIndex = wallpaperView.activeLayerIndex,
        )
        fabCluster.setMiniFabVisible(SpeedDialFabCluster.MiniFab.AddLayer, state.addVisible)
        commandsPanel.setLayerButtonsState(
            deleteVisible = state.deleteVisible,
            deleteEnabled = state.deleteEnabled,
            upVisible = state.upVisible,
            upEnabled = state.upEnabled,
            downVisible = state.downVisible,
            downEnabled = state.downEnabled,
        )
    }

    // ---- snap-button icons ----

    private fun updateSnapButtonIcon(isEnabled: Boolean) {
        commandsPanel.setSnapToggleIcon(SnapIconResolver.resolveMagnet(isEnabled))
    }

    private fun updateSnapModeButtonIcon(mode: ZoomableImageView.SnapMode) {
        commandsPanel.setSnapModeIcon(SnapIconResolver.resolveSnapMode(mode.toIconMode()))
    }

    private fun updateHorizontalSnapButtonIcon(isEnabled: Boolean, mode: ZoomableImageView.SnapMode) {
        commandsPanel.setHorizontalSnapIcon(SnapIconResolver.resolveHorizontal(isEnabled, mode.toIconMode()))
    }

    private fun updateVerticalSnapButtonIcon(isEnabled: Boolean, mode: ZoomableImageView.SnapMode) {
        commandsPanel.setVerticalSnapIcon(SnapIconResolver.resolveVertical(isEnabled, mode.toIconMode()))
    }

    private fun ZoomableImageView.SnapMode.toIconMode(): SnapMode = when (this) {
        ZoomableImageView.SnapMode.EDGE -> SnapMode.EDGE
        ZoomableImageView.SnapMode.CENTER -> SnapMode.CENTER
    }
}

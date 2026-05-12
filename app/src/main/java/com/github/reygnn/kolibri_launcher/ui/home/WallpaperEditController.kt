package com.github.reygnn.kolibri_launcher.ui.home

import android.view.View
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.databinding.FragmentHomeBinding
import com.github.reygnn.kolibri_launcher.domain.model.FabPosition
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperEditState
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperEditTransition
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperSaveAction
import com.github.reygnn.kolibri_launcher.ui.home.wallpaperfab.SpeedDialFabCluster
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
import timber.log.Timber

/**
 * Owns the wallpaper-edit-mode UI surface. After the speed-dial FAB
 * refactor (May 2026) this class wires the [SpeedDialFabCluster] +
 * `CommandsPanel` controls instead of the legacy two-row bottom
 * toolbar; the public API and lifetime (created in `onViewCreated`,
 * nulled in `onDestroyView`) are unchanged.
 *
 * The cluster's main `Save` FAB is also the drag handle for the
 * cluster's on-screen position. Position is persisted via
 * [LauncherViewModel.onFabPositionChanged] and applied on edit-mode
 * entry from [LauncherViewModel.fabPosition] via [applyFabPosition].
 *
 * The legacy toolbar dim-on-drag and dock-top toggle features are
 * intentionally absent: a freely-draggable FAB makes both redundant.
 */
internal class WallpaperEditController(
    private val binding: FragmentHomeBinding,
    private val viewModel: LauncherViewModel,
    private val launchLayerPicker: () -> Unit,
    private val rerenderWallpaper: () -> Unit,
) {

    private val fabCluster: SpeedDialFabCluster get() = binding.wallpaperFabCluster
    private val commandsPanel get() = binding.wallpaperCommandsPanel

    init {
        // Drag callback wires straight to the ViewModel — does not need
        // to be reattached per edit session.
        fabCluster.onPositionChanged = { x, y ->
            viewModel.onFabPositionChanged(x, y)
        }
    }

    // ============================================================================
    // EDIT-MODE STATE — APPLY
    // ============================================================================

    fun applyEditState(state: WallpaperEditState) {
        val wallpaperView = binding.wallpaperView
        wallpaperView.isEditMode = state.isEditMode
        wallpaperView.isSnapEnabled = state.snapEnabled
        wallpaperView.isHorizontalSnapEnabled = state.horizontalSnapEnabled
        wallpaperView.isVerticalSnapEnabled = state.verticalSnapEnabled
        wallpaperView.snapMode = state.snapMode
        binding.wallpaperEditOverlay.visibility =
            if (state.overlayVisible) View.VISIBLE else View.GONE
        binding.rootLayout.alpha = state.rootLayoutAlpha
    }

    /**
     * Repositions the speed-dial cluster to the persisted user
     * placement. Called when the [LauncherViewModel.fabPosition] flow
     * emits — currently driven by the Fragment's lifecycle observer.
     */
    fun applyFabPosition(position: FabPosition) {
        fabCluster.applyPosition(position.xFraction, position.yFraction)
    }

    // ============================================================================
    // EDIT-MODE TRANSITIONS — listeners on/off
    // ============================================================================

    fun applyEditMode(isEditMode: Boolean) {
        try {
            applyEditState(
                WallpaperEditTransition.targetState(WallpaperEditTransition.forMode(isEditMode))
            )

            if (isEditMode) {
                wireEditModeListeners()
                Timber.d("Wallpaper edit mode: ON (multiLayer=${viewModel.wallpaperState.value.isMultiLayer}, layers=${binding.wallpaperView.layerCount})")
            } else {
                clearEditModeListeners()
                commandsPanel.hidePanel()
                Timber.d("Wallpaper edit mode: OFF")
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating wallpaper edit mode")
        }
    }

    private fun wireEditModeListeners() {
        val wallpaperView = binding.wallpaperView

        // ── TOUCH FORWARDING ──
        // wallpaperEditOverlay sits above wallpaperView with a full-
        // screen wallpaperTouchInterceptor as its bottom child. Without
        // an explicit forward, pinch-to-zoom and drag-to-pan in edit
        // mode never reach the wallpaperView — the interceptor (non-
        // clickable, no listener) silently ends the touch dispatch for
        // taps that fall outside the FAB cluster / CommandsPanel.
        // Forward to the underlying view so its existing edit-mode
        // gesture handling kicks in.
        binding.wallpaperTouchInterceptor.setOnTouchListener { _, event ->
            wallpaperView.onTouchEvent(event)
        }

        // ── SAVE (Main FAB tap, drag-aware) ──
        fabCluster.setOnSaveClicked {
            val currentWallpaperState = viewModel.wallpaperState.value
            // Save runs after a finished edit session, so we trust the
            // state's isMultiLayer value directly. No race-guard against
            // the view here — see saveCurrentViewTransforms below for the
            // contrast.
            val action = WallpaperSaveAction.decide(
                isMultiLayer = currentWallpaperState.isMultiLayer,
                hasWallpaper = currentWallpaperState.hasWallpaper,
                allLayerTransforms = readAllLayerTransforms(wallpaperView),
                singleTransform = readSingleTransform(wallpaperView),
            )
            dispatchSaveAction(action)
            viewModel.onCommitWallpaperEditMode()
        }

        // ── CANCEL ──
        // Rolls back the edit session via the delegate:
        //  - state restored to the snapshot taken on enter (in-memory
        //    sync, persistence + file cleanup async)
        //  - files of removed layers kept; files of added layers cleaned up
        fabCluster.setOnCancelClicked {
            viewModel.onCancelWallpaperEditMode()
            rerenderWallpaper()
        }

        // ── ZOOM PRESETS ──
        fabCluster.setOnOneToOneClicked { wallpaperView.showOriginalSize() }
        fabCluster.setOnFitWidthClicked { wallpaperView.fitToWidth() }

        // ── LAYER ADD ──
        fabCluster.setOnAddLayerClicked {
            saveCurrentViewTransforms()
            launchLayerPicker()
        }

        // ── OVERFLOW (☰) toggles CommandsPanel ──
        fabCluster.setOnOpenCommandsClicked { commandsPanel.togglePanel() }
        commandsPanel.setOnCloseClicked { commandsPanel.hidePanel() }

        // ── SNAP CONTROLS — wired on the panel ──
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

        // ── LAYER MANAGEMENT (Delete / Up / Down on the panel) ──
        applyLayerButtonsState()
        updateLayerIndicator()

        commandsPanel.setOnLayerDeleteClicked {
            val activeIndex = wallpaperView.activeLayerIndex
            if (activeIndex >= 0 && wallpaperView.layerCount > 0) {
                saveCurrentViewTransforms()
                viewModel.onRemoveWallpaperLayer(activeIndex)
            }
        }

        commandsPanel.setOnLayerUpClicked {
            val activeIndex = wallpaperView.activeLayerIndex
            if (activeIndex < wallpaperView.layerCount - 1) {
                saveCurrentViewTransforms()
                wallpaperView.moveLayerUp(activeIndex)
                viewModel.onSwapWallpaperLayers(activeIndex, activeIndex + 1)
                updateLayerIndicator()
                applyLayerButtonsState()
            }
        }

        commandsPanel.setOnLayerDownClicked {
            val activeIndex = wallpaperView.activeLayerIndex
            if (activeIndex > 0) {
                saveCurrentViewTransforms()
                wallpaperView.moveLayerDown(activeIndex)
                viewModel.onSwapWallpaperLayers(activeIndex, activeIndex - 1)
                updateLayerIndicator()
                applyLayerButtonsState()
            }
        }

        // Layer-tap callback — keeps the indicator and the layer-buttons
        // state in sync with the user's current selection.
        wallpaperView.onLayerTapped = { _, _ ->
            updateLayerIndicator()
            applyLayerButtonsState()
        }
    }

    private fun clearEditModeListeners() {
        binding.wallpaperTouchInterceptor.setOnTouchListener(null)

        fabCluster.setOnSaveClicked { /* no-op */ }
        fabCluster.setOnCancelClicked { /* no-op */ }
        fabCluster.setOnAddLayerClicked { /* no-op */ }
        fabCluster.setOnOneToOneClicked { /* no-op */ }
        fabCluster.setOnFitWidthClicked { /* no-op */ }
        fabCluster.setOnOpenCommandsClicked { /* no-op */ }

        commandsPanel.setOnSnapToggleClicked { /* no-op */ }
        commandsPanel.setOnSnapModeClicked { /* no-op */ }
        commandsPanel.setOnHorizontalSnapClicked { /* no-op */ }
        commandsPanel.setOnVerticalSnapClicked { /* no-op */ }
        commandsPanel.setOnLayerDeleteClicked { /* no-op */ }
        commandsPanel.setOnLayerUpClicked { /* no-op */ }
        commandsPanel.setOnLayerDownClicked { /* no-op */ }
        commandsPanel.setOnCloseClicked { /* no-op */ }

        binding.wallpaperView.onLayerTapped = null
    }

    // ============================================================================
    // VIEW-TRANSFORM PERSISTENCE
    // ============================================================================

    /**
     * Persists the current view transforms into the state. MUST be
     * called before every layer operation (Add, Delete, Swap), so
     * live transforms aren't lost when [rerenderWallpaper] rebuilds
     * the layers.
     */
    fun saveCurrentViewTransforms() {
        val wallpaperView = binding.wallpaperView
        val currentState = viewModel.wallpaperState.value

        // Race-guard: this runs before layer operations, in which the
        // WallpaperState and ZoomableImageView can temporarily diverge
        // (state updated, view not yet rebuilt — or vice versa). Take
        // the multi-layer save path only when both sides agree;
        // otherwise getLayer(i) would read from a not-yet-rebuilt view
        // and produce garbage or crash. The save-button path above
        // intentionally lacks this guard because it runs after a
        // finished edit session.
        val isMultiLayerEffective =
            currentState.isMultiLayer && wallpaperView.isMultiLayerMode

        val action = WallpaperSaveAction.decide(
            isMultiLayer = isMultiLayerEffective,
            hasWallpaper = currentState.hasWallpaper,
            allLayerTransforms = readAllLayerTransforms(wallpaperView),
            singleTransform = readSingleTransform(wallpaperView),
        )
        dispatchSaveAction(action)
    }

    private fun readAllLayerTransforms(
        wallpaperView: ZoomableImageView,
    ): List<Triple<Float, Float, Float>> =
        (0 until wallpaperView.layerCount).map { i ->
            val layer = wallpaperView.getLayer(i)
            Triple(
                layer?.scale ?: 1f,
                layer?.translateX ?: 0f,
                layer?.translateY ?: 0f,
            )
        }

    private fun readSingleTransform(
        wallpaperView: ZoomableImageView,
    ): Triple<Float, Float, Float> = Triple(
        wallpaperView.currentScale,
        wallpaperView.currentTranslateX,
        wallpaperView.currentTranslateY,
    )

    private fun dispatchSaveAction(action: WallpaperSaveAction) {
        when (action) {
            is WallpaperSaveAction.SaveAllLayers ->
                viewModel.onSaveAllLayerTransforms(action.transforms)
            is WallpaperSaveAction.SaveSingle ->
                viewModel.onSaveWallpaperTransform(
                    action.scale,
                    action.translateX,
                    action.translateY,
                )
            is WallpaperSaveAction.NoOp -> {
                // Nothing to save. The caller decides whether a follow-up
                // commit is still needed (save-button does, layer-op
                // pre-flush does not).
            }
        }
    }

    // ============================================================================
    // LAYER UI UPDATES (forwarded to CommandsPanel)
    // ============================================================================

    fun updateLayerIndicator() {
        val wallpaperView = binding.wallpaperView
        val count = wallpaperView.layerCount
        val active = wallpaperView.activeLayerIndex

        if (count > 0) {
            commandsPanel.setLayerIndicator("Layer ${active + 1}/$count", visible = true)
        } else {
            commandsPanel.setLayerIndicator(null, visible = false)
        }
    }

    fun applyLayerButtonsState() {
        val wallpaperView = binding.wallpaperView
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

    // ============================================================================
    // SNAP-BUTTON ICON UPDATES (panel-bound)
    // ============================================================================

    private fun updateSnapButtonIcon(isEnabled: Boolean) {
        commandsPanel.setSnapToggleIcon(SnapIconResolver.resolveMagnet(isEnabled))
    }

    private fun updateSnapModeButtonIcon(mode: ZoomableImageView.SnapMode) {
        commandsPanel.setSnapModeIcon(SnapIconResolver.resolveSnapMode(mode.toIconMode()))
    }

    private fun updateHorizontalSnapButtonIcon(isEnabled: Boolean, mode: ZoomableImageView.SnapMode) {
        commandsPanel.setHorizontalSnapIcon(
            SnapIconResolver.resolveHorizontal(isEnabled, mode.toIconMode()),
        )
    }

    private fun updateVerticalSnapButtonIcon(isEnabled: Boolean, mode: ZoomableImageView.SnapMode) {
        commandsPanel.setVerticalSnapIcon(
            SnapIconResolver.resolveVertical(isEnabled, mode.toIconMode()),
        )
    }

    /**
     * Bridges the View-nested [ZoomableImageView.SnapMode] to the
     * Android-free [SnapMode] enum used by [SnapIconResolver].
     */
    private fun ZoomableImageView.SnapMode.toIconMode(): SnapMode = when (this) {
        ZoomableImageView.SnapMode.EDGE -> SnapMode.EDGE
        ZoomableImageView.SnapMode.CENTER -> SnapMode.CENTER
    }
}

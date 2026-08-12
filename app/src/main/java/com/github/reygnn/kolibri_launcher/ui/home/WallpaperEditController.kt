package com.github.reygnn.kolibri_launcher.ui.home

import android.view.View
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.databinding.FragmentHomeBinding
import com.github.reygnn.kolibri_launcher.databinding.ViewWallpaperEditOverlayBinding
import com.github.reygnn.kolibri_launcher.domain.model.FabPosition
import com.github.reygnn.kolibri_launcher.ui.home.wallpaperfab.CommandsPanel
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.LayerTransform
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

    // The edit overlay (interceptor, hint, CommandsPanel, SpeedDialFabCluster)
    // lives behind a <ViewStub> in fragment_home and is inflated lazily on the
    // first entry into edit mode — see [ensureOverlayInflated]. Null until then;
    // ~47ms of inflation is thus kept off every launcher cold start (the UI is
    // only ever used in edit mode). All view access goes through the accessors
    // below, which are only reached after inflation — every entry point that can
    // run earlier (applyEditMode(false), applyFabPosition, the layer-state
    // updates) is guarded.
    private var overlay: ViewWallpaperEditOverlayBinding? = null

    // A FAB position emitted by the ViewModel before the overlay exists is
    // stashed here and applied at inflation time (see [applyFabPosition]).
    private var pendingFabPosition: FabPosition? = null

    private val fabCluster: SpeedDialFabCluster get() = requireNotNull(overlay).wallpaperFabCluster
    private val commandsPanel: CommandsPanel get() = requireNotNull(overlay).wallpaperCommandsPanel
    private val touchInterceptor: View get() = requireNotNull(overlay).wallpaperTouchInterceptor

    /**
     * Inflates the edit-overlay ViewStub exactly once and wires the
     * session-independent drag callback (formerly the `init` block). Idempotent:
     * returns the existing binding on later calls. Called only from
     * [applyEditMode]`(true)`, so the overlay is built when edit mode is first
     * entered — not at cold start.
     */
    private fun ensureOverlayInflated(): ViewWallpaperEditOverlayBinding {
        overlay?.let { return it }

        val root = binding.wallpaperEditOverlayStub.inflate()
        val bound = ViewWallpaperEditOverlayBinding.bind(root)
        overlay = bound

        // Drag callback wires straight to the ViewModel — session-independent,
        // so it is set once at inflation.
        bound.wallpaperFabCluster.onPositionChanged = { x, y ->
            viewModel.onFabPositionChanged(x, y)
        }

        // Apply a FAB position that arrived before the overlay existed.
        pendingFabPosition?.let {
            bound.wallpaperFabCluster.applyPosition(it.xFraction, it.yFraction)
            pendingFabPosition = null
        }

        return bound
    }

    // ============================================================================
    // EDIT-MODE STATE — APPLY
    // ============================================================================

    private fun applyEditState(state: WallpaperEditState) {
        val wallpaperView = binding.wallpaperView
        wallpaperView.isEditMode = state.isEditMode
        wallpaperView.isSnapEnabled = state.snapEnabled
        wallpaperView.isHorizontalSnapEnabled = state.horizontalSnapEnabled
        wallpaperView.isVerticalSnapEnabled = state.verticalSnapEnabled
        wallpaperView.snapMode = state.snapMode
        // Overlay visibility only applies once the stub is inflated. Before
        // that "not inflated" already IS the GONE state, so a null overlay is a
        // correct no-op (the safe-call assignment does nothing).
        overlay?.root?.visibility =
            if (state.overlayVisible) View.VISIBLE else View.GONE
        binding.rootLayout.alpha = state.rootLayoutAlpha
    }

    /**
     * Repositions the speed-dial cluster to the persisted user
     * placement. Called when the [LauncherViewModel.fabPosition] flow
     * emits — currently driven by the Fragment's lifecycle observer.
     */
    fun applyFabPosition(position: FabPosition) {
        val o = overlay
        if (o != null) {
            o.wallpaperFabCluster.applyPosition(position.xFraction, position.yFraction)
        } else {
            // Overlay not built yet (never entered edit mode) — the ViewModel's
            // fabPosition flow emits its initial value at startup. Stash it;
            // [ensureOverlayInflated] applies it when the overlay is built.
            pendingFabPosition = position
        }
    }

    // ============================================================================
    // EDIT-MODE TRANSITIONS — listeners on/off
    // ============================================================================

    fun applyEditMode(isEditMode: Boolean) {
        try {
            val targetState =
                WallpaperEditTransition.targetState(WallpaperEditTransition.forMode(isEditMode))

            if (isEditMode) {
                // Build the overlay on first entry, BEFORE applyEditState (which
                // sets the now-inflated overlay VISIBLE) and the listener wiring.
                ensureOverlayInflated()
                applyEditState(targetState)
                wireEditModeListeners()
                Timber.d("Wallpaper edit mode: ON (multiLayer=${viewModel.wallpaperState.value.isMultiLayer}, layers=${binding.wallpaperView.layerCount})")
            } else {
                applyEditState(targetState)
                // If the overlay was never inflated (never entered edit mode),
                // there is nothing to unwire or hide — the non-overlay off-state
                // above is all that applies.
                if (overlay != null) {
                    clearEditModeListeners()
                    commandsPanel.hidePanel()
                }
                Timber.d("Wallpaper edit mode: OFF")
            }
        } catch (e: Throwable) {
            // Outer Catchall kept: this is the orchestration boundary
            // for edit-mode entry / exit. HomeFragment's observer relies
            // on this catch so it can drop its own inner try/catch
            // (see HomeFragment.kt around the wallpaper-edit-mode
            // observer). silentError makes the failure loud in DEBUG.
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
        touchInterceptor.setOnTouchListener { _, event ->
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
        touchInterceptor.setOnTouchListener(null)

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

    // Each read carries the layer's current decode factor (sampleSize) so the
    // save can tag the transform with captureSampleSize (WALLPAPER_RENDER_RES_SPEC
    // §4-Y). getLayer(i) is null-guarded (race with a not-yet-rebuilt view); a
    // null layer falls back to sampleSize 1 (full-res), matching its 1f scale.
    private fun readAllLayerTransforms(
        wallpaperView: ZoomableImageView,
    ): List<LayerTransform> =
        (0 until wallpaperView.layerCount).map { i ->
            val layer = wallpaperView.getLayer(i)
            LayerTransform(
                scale = layer?.scale ?: 1f,
                translateX = layer?.translateX ?: 0f,
                translateY = layer?.translateY ?: 0f,
                sampleSize = layer?.sampleSize ?: 1,
            )
        }

    private fun readSingleTransform(
        wallpaperView: ZoomableImageView,
    ): LayerTransform = LayerTransform(
        scale = wallpaperView.currentScale,
        translateX = wallpaperView.currentTranslateX,
        translateY = wallpaperView.currentTranslateY,
        sampleSize = wallpaperView.singleSampleSize,
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
                    action.sampleSize,
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
        // Only meaningful in edit mode (overlay inflated). Guard so a layer
        // change arriving off edit-mode can't touch a null overlay.
        overlay ?: return
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
        // Only meaningful in edit mode (overlay inflated). Guard as above.
        overlay ?: return
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

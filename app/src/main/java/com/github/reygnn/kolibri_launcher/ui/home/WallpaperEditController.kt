package com.github.reygnn.kolibri_launcher.ui.home

import android.content.res.Resources
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.databinding.FragmentHomeBinding
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperEditState
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperEditTransition
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperSaveAction
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
import timber.log.Timber

/**
 * Owns the wallpaper-edit-mode UI surface that used to live as ~770
 * lines of private methods inside `HomeFragment`. Single responsibility:
 * everything between "user enters edit mode" and "user commits / cancels".
 *
 * The Fragment keeps the always-on home rendering. The controller
 * handles the edit-mode click listeners, toolbar dimming/docking,
 * layer-buttons state, snap controls, and the apply-state side effects.
 *
 * Lifetime: created in `onViewCreated`, nulled in `onDestroyView` —
 * same scope as `_binding`. Because the controller never outlives the
 * binding, methods don't repeat `_binding == null` guards; the Fragment
 * checks at the call site (or the call site is only wired during edit
 * mode, where the listener can't fire after teardown).
 *
 * Rule 11 sweep applied during extraction:
 *  - The 14 click-listener bodies that used to wrap every property
 *    write / pure decide()-call / when-expression in `try { … } catch
 *    (Throwable)` are now bare. Programmer-error bugs surface loudly.
 *  - Helper methods (`dimToolbar`, `dockToolbar`, `applyLayerButtonsState`,
 *    `updateLayerIndicator`, `saveCurrentViewTransforms`) lost their
 *    blanket `Throwable` catches around pure operations.
 *  - The single outer catch in [applyEditMode] stays — that's the
 *    orchestration boundary where escape-paths legitimately end up.
 *  - [setupInsets] keeps its outer catch because `setOnApplyWindowInsetsListener`
 *    can fail if the platform Window-decorations are in an odd state
 *    (foldable mid-fold, mid-rotation), and we'd rather render without
 *    the toolbar inset than crash.
 */
internal class WallpaperEditController(
    private val binding: FragmentHomeBinding,
    private val viewModel: LauncherViewModel,
    private val resources: Resources,
    private val launchLayerPicker: () -> Unit,
    private val rerenderWallpaper: () -> Unit,
) {

    private var isToolbarDockedTop = false

    // ============================================================================
    // INSET HANDLING
    // ============================================================================

    fun setupInsets() {
        try {
            ViewCompat.setOnApplyWindowInsetsListener(binding.wallpaperEditButtons) { view, insets ->
                val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                val basePadding = try {
                    resources.getDimensionPixelSize(R.dimen.layout_padding)
                } catch (e: Throwable) {
                    AppConstants.FALLBACK_DIMEN_PX
                }

                view.setPadding(
                    view.paddingLeft,
                    view.paddingTop,
                    view.paddingRight,
                    basePadding + navBarInsets.bottom,
                )
                insets
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting up wallpaper edit buttons insets")
        }
    }

    // ============================================================================
    // EDIT-MODE STATE — APPLY
    // ============================================================================

    /**
     * Applies a [WallpaperEditState] target onto the views and the
     * controller's own state. Pure side-effect; the decision of *which*
     * state to apply is made by [WallpaperEditTransition.targetState].
     *
     * Both Enter and Exit funnel through this method, so adding a new
     * field to [WallpaperEditState] only requires one place here to
     * apply it (plus the per-transition target values in
     * [WallpaperEditTransition], which the compiler enforces).
     */
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
        binding.wallpaperEditButtons.alpha = state.toolbarAlpha
        isToolbarDockedTop = state.toolbarDockedTop
    }

    // ============================================================================
    // EDIT-MODE TRANSITIONS — listeners on/off
    // ============================================================================

    /**
     * Wires the wallpaper-edit click listeners on entry and removes
     * them on exit. The outer try/catch is the orchestration boundary;
     * inner per-listener catches were removed during the extraction
     * because the click bodies are pure property toggles, view-state
     * reads, and viewModel state writes — all programmer-error-only
     * code paths per Rule 11.
     */
    fun applyEditMode(isEditMode: Boolean) {
        try {
            val wallpaperView = binding.wallpaperView
            val editOverlay = binding.wallpaperEditOverlay
            val touchInterceptor = binding.wallpaperTouchInterceptor

            applyEditState(
                WallpaperEditTransition.targetState(WallpaperEditTransition.forMode(isEditMode))
            )

            if (isEditMode) {
                wireEditModeListeners(touchInterceptor, wallpaperView)
                Timber.d("Wallpaper edit mode: ON (multiLayer=${viewModel.wallpaperState.value.isMultiLayer}, layers=${wallpaperView.layerCount})")
            } else {
                clearEditModeListeners(touchInterceptor, wallpaperView)
                Timber.d("Wallpaper edit mode: OFF")
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating wallpaper edit mode")
        }
    }

    private fun wireEditModeListeners(
        touchInterceptor: View,
        wallpaperView: ZoomableImageView,
    ) {
        // Toolbar dimming on drag/zoom — fades out while dragging,
        // fades back in on release so the user sees the wallpaper they're
        // adjusting.
        touchInterceptor.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Don't dim yet — wait for an actual drag/zoom.
                }
                MotionEvent.ACTION_MOVE -> dimToolbar(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dimToolbar(false)
            }
            wallpaperView.onTouchEvent(event)
        }

        // ── SAVE ──
        binding.btnWallpaperSave.setOnClickListener {
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
        // After rolling back, [rerenderWallpaper] re-runs the home
        // wallpaper render so pure transform drags (which never touched
        // the state) also reset on the view.
        binding.btnWallpaperCancel.setOnClickListener {
            viewModel.onCancelWallpaperEditMode()
            rerenderWallpaper()
        }

        // ── SNAP CONTROLS ──
        updateSnapButtonIcon(wallpaperView.isSnapEnabled)
        binding.btnWallpaperSnap.setOnClickListener {
            wallpaperView.isSnapEnabled = !wallpaperView.isSnapEnabled
            updateSnapButtonIcon(wallpaperView.isSnapEnabled)
        }

        updateSnapModeButtonIcon(wallpaperView.snapMode)
        binding.btnWallpaperSnapMode.setOnClickListener {
            wallpaperView.snapMode = when (wallpaperView.snapMode) {
                ZoomableImageView.SnapMode.EDGE -> ZoomableImageView.SnapMode.CENTER
                ZoomableImageView.SnapMode.CENTER -> ZoomableImageView.SnapMode.EDGE
            }
            updateSnapModeButtonIcon(wallpaperView.snapMode)
            updateHorizontalSnapButtonIcon(wallpaperView.isHorizontalSnapEnabled, wallpaperView.snapMode)
            updateVerticalSnapButtonIcon(wallpaperView.isVerticalSnapEnabled, wallpaperView.snapMode)
        }

        updateHorizontalSnapButtonIcon(wallpaperView.isHorizontalSnapEnabled, wallpaperView.snapMode)
        binding.btnWallpaperHSnap.setOnClickListener {
            wallpaperView.isHorizontalSnapEnabled = !wallpaperView.isHorizontalSnapEnabled
            updateHorizontalSnapButtonIcon(wallpaperView.isHorizontalSnapEnabled, wallpaperView.snapMode)
        }

        updateVerticalSnapButtonIcon(wallpaperView.isVerticalSnapEnabled, wallpaperView.snapMode)
        binding.btnWallpaperVSnap.setOnClickListener {
            wallpaperView.isVerticalSnapEnabled = !wallpaperView.isVerticalSnapEnabled
            updateVerticalSnapButtonIcon(wallpaperView.isVerticalSnapEnabled, wallpaperView.snapMode)
        }

        // ── ORIGINAL SIZE / FIT WIDTH ──
        binding.btnWallpaperOneToOne.setOnClickListener {
            wallpaperView.showOriginalSize()
        }
        binding.btnWallpaperFitWidth.setOnClickListener {
            wallpaperView.fitToWidth()
        }

        // ── DOCK TOGGLE ──
        binding.btnToolbarDock.setOnClickListener {
            isToolbarDockedTop = !isToolbarDockedTop
            dockToolbar(isToolbarDockedTop)
        }

        // ══════════════════════════════════════
        // LAYER MANAGEMENT
        // ══════════════════════════════════════

        applyLayerButtonsState()
        updateLayerIndicator()

        binding.btnLayerAdd.setOnClickListener {
            // Save transforms BEFORE the new layer is added.
            saveCurrentViewTransforms()
            launchLayerPicker()
        }

        binding.btnLayerDelete.setOnClickListener {
            val activeIndex = wallpaperView.activeLayerIndex
            if (activeIndex >= 0 && wallpaperView.layerCount > 0) {
                // Save the other layers' transforms before delete.
                saveCurrentViewTransforms()
                viewModel.onRemoveWallpaperLayer(activeIndex)
            }
        }

        binding.btnLayerUp.setOnClickListener {
            val activeIndex = wallpaperView.activeLayerIndex
            if (activeIndex < wallpaperView.layerCount - 1) {
                saveCurrentViewTransforms()
                wallpaperView.moveLayerUp(activeIndex)
                viewModel.onSwapWallpaperLayers(activeIndex, activeIndex + 1)
                updateLayerIndicator()
                applyLayerButtonsState()
            }
        }

        binding.btnLayerDown.setOnClickListener {
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

    private fun clearEditModeListeners(
        touchInterceptor: View,
        wallpaperView: ZoomableImageView,
    ) {
        touchInterceptor.setOnTouchListener(null)

        binding.btnWallpaperSave.setOnClickListener(null)
        binding.btnWallpaperCancel.setOnClickListener(null)
        binding.btnWallpaperSnap.setOnClickListener(null)
        binding.btnWallpaperSnapMode.setOnClickListener(null)
        binding.btnWallpaperHSnap.setOnClickListener(null)
        binding.btnWallpaperVSnap.setOnClickListener(null)
        binding.btnWallpaperOneToOne.setOnClickListener(null)
        binding.btnWallpaperFitWidth.setOnClickListener(null)
        binding.btnToolbarDock.setOnClickListener(null)

        binding.btnLayerAdd.setOnClickListener(null)
        binding.btnLayerDelete.setOnClickListener(null)
        binding.btnLayerUp.setOnClickListener(null)
        binding.btnLayerDown.setOnClickListener(null)

        wallpaperView.onLayerTapped = null
    }

    // ============================================================================
    // TOOLBAR — dimming and docking
    // ============================================================================

    /**
     * Dims the edit toolbar during drag/zoom gestures so the user can
     * see the underlying wallpaper.
     */
    fun dimToolbar(dim: Boolean) {
        if (dim) {
            // Fully invisible — no longer blocks touches.
            binding.wallpaperEditButtons.visibility = View.INVISIBLE
        } else {
            binding.wallpaperEditButtons.alpha = 0f
            binding.wallpaperEditButtons.visibility = View.VISIBLE
            binding.wallpaperEditButtons.animate()
                .alpha(1.0f)
                .setDuration(TOOLBAR_SHOW_DURATION_MS)
                .start()
        }
    }

    private fun dockToolbar(top: Boolean) {
        val toolbar = binding.wallpaperEditButtons
        val params = toolbar.layoutParams as? FrameLayout.LayoutParams ?: return

        if (top) {
            params.gravity = Gravity.TOP
            // Flip padding: top needs space for status bar.
            toolbar.setPadding(
                toolbar.paddingLeft,
                toolbar.paddingBottom, // What was at the bottom moves up.
                toolbar.paddingRight,
                TOOLBAR_MINIMAL_PADDING_PX,
            )
        } else {
            params.gravity = Gravity.BOTTOM
            // Restore original padding (insets are handled by [setupInsets]).
            toolbar.setPadding(
                toolbar.paddingLeft,
                TOOLBAR_MINIMAL_PADDING_PX,
                toolbar.paddingRight,
                toolbar.paddingTop, // What was on top moves down.
            )
        }

        toolbar.layoutParams = params

        // Icon shows the OTHER direction.
        binding.btnToolbarDock.setIconResource(
            if (top) R.drawable.ic_dock_bottom else R.drawable.ic_dock_top,
        )
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

    /**
     * Reads all layer transforms from the view in index order. Cheap:
     * `getLayer` is `List.getOrNull`, layer count is small. The caller
     * passes the list to [WallpaperSaveAction.decide] which may discard
     * it (when the chosen branch is single-layer or no-op).
     */
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

    /**
     * Reads the single-layer transform from the view. The view's
     * currentScale/Tx/Ty getters fall back to layer-active values in
     * multi-layer mode; that's fine because the caller passes this
     * triple to [WallpaperSaveAction.decide] which only uses it on
     * the single-layer branch.
     */
    private fun readSingleTransform(
        wallpaperView: ZoomableImageView,
    ): Triple<Float, Float, Float> = Triple(
        wallpaperView.currentScale,
        wallpaperView.currentTranslateX,
        wallpaperView.currentTranslateY,
    )

    /**
     * Forwards a [WallpaperSaveAction] decision to the corresponding
     * ViewModel call. The dispatch table is the only place where the
     * decision branches translate into side effects; the decision
     * itself is pure.
     */
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
    // LAYER UI UPDATES
    // ============================================================================

    /** Updates the layer indicator text: "Layer 2/3". */
    fun updateLayerIndicator() {
        val wallpaperView = binding.wallpaperView
        val count = wallpaperView.layerCount
        val active = wallpaperView.activeLayerIndex

        if (count > 0) {
            binding.txtLayerIndicator.text = "Layer ${active + 1}/$count"
            binding.txtLayerIndicator.visibility = View.VISIBLE
        } else {
            binding.txtLayerIndicator.visibility = View.GONE
        }
    }

    /**
     * Applies the [LayerButtonsState]-derived UI state to the layer
     * buttons (visibility, enabled, alpha). Idempotent.
     */
    fun applyLayerButtonsState() {
        val wallpaperView = binding.wallpaperView
        val state = LayerButtonsState.from(
            isMultiLayerMode = wallpaperView.isMultiLayerMode,
            layerCount = wallpaperView.layerCount,
            activeLayerIndex = wallpaperView.activeLayerIndex,
        )

        binding.btnLayerAdd.visibility = if (state.addVisible) View.VISIBLE else View.GONE
        binding.btnLayerDelete.visibility = if (state.deleteVisible) View.VISIBLE else View.GONE
        binding.btnLayerUp.visibility = if (state.upVisible) View.VISIBLE else View.GONE
        binding.btnLayerDown.visibility = if (state.downVisible) View.VISIBLE else View.GONE
        binding.txtLayerIndicator.visibility = if (state.indicatorVisible) View.VISIBLE else View.GONE

        binding.btnLayerUp.isEnabled = state.upEnabled
        binding.btnLayerDown.isEnabled = state.downEnabled
        binding.btnLayerDelete.isEnabled = state.deleteEnabled

        // Visual feedback: disabled buttons are half-transparent.
        binding.btnLayerUp.alpha = state.upAlpha
        binding.btnLayerDown.alpha = state.downAlpha
    }

    // ============================================================================
    // SNAP-BUTTON ICON UPDATES
    // ============================================================================

    private fun updateSnapButtonIcon(isEnabled: Boolean) {
        binding.btnWallpaperSnap.setIconResource(SnapIconResolver.resolveMagnet(isEnabled))
    }

    private fun updateSnapModeButtonIcon(mode: ZoomableImageView.SnapMode) {
        binding.btnWallpaperSnapMode.setIconResource(SnapIconResolver.resolveSnapMode(mode.toIconMode()))
    }

    private fun updateHorizontalSnapButtonIcon(isEnabled: Boolean, mode: ZoomableImageView.SnapMode) {
        binding.btnWallpaperHSnap.setIconResource(
            SnapIconResolver.resolveHorizontal(isEnabled, mode.toIconMode()),
        )
    }

    private fun updateVerticalSnapButtonIcon(isEnabled: Boolean, mode: ZoomableImageView.SnapMode) {
        binding.btnWallpaperVSnap.setIconResource(
            SnapIconResolver.resolveVertical(isEnabled, mode.toIconMode()),
        )
    }

    /**
     * Bridges the View-nested [ZoomableImageView.SnapMode] to the
     * Android-free [SnapMode] enum used by [SnapIconResolver].
     * Long-term, ZoomableImageView should migrate to the top-level enum
     * directly.
     */
    private fun ZoomableImageView.SnapMode.toIconMode(): SnapMode = when (this) {
        ZoomableImageView.SnapMode.EDGE -> SnapMode.EDGE
        ZoomableImageView.SnapMode.CENTER -> SnapMode.CENTER
    }

    private companion object {
        /** Animation duration for toolbar dim-to-show fade-in. */
        const val TOOLBAR_SHOW_DURATION_MS = 200L

        /** Minimal opposite-side padding when the toolbar is docked. */
        const val TOOLBAR_MINIMAL_PADDING_PX = 12
    }
}

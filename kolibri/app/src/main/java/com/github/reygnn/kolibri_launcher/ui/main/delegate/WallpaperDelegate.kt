/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

/**
 * =====================================================================================
 * ARCHITECTURAL NOTE (since 3a-9): who decides, who executes, who orchestrates
 * =====================================================================================
 *
 * The wallpaper edit is split into shared parts in `:feature-wallpaper`; this delegate only
 * translates UI intents and maps results onto Kolibri's UI:
 *
 *  - [WallpaperEditSession] DECIDES (pure, synchronous, main-confined): the displayed state, the
 *    session (enter → changes → commit | cancel) with its snapshot, the file marks, the rollback
 *    generation. Cancel restores synchronously; re-entering a running session is ignored;
 *    emissions are ignored during a session and until its re-sync.
 *  - [WallpaperOperations] EXECUTES the rules both apps need around it: persist then delete in
 *    order, the re-sync after a session, replace/add with the rollback generation, "remove
 *    wallpaper" (composite lock before persist lock), and the start-up wiring.
 *  - [WallpaperImageStore] owns every file decision (against what is persisted, fail closed).
 *  - [WallpaperComposite] owns the display composite (warm, cache, luminance, its lock).
 *
 * What stays here: the public intents, the mapping onto toasts/UiEvents, Kolibri's deferred
 * scrim-reset offer ([wallpaperImageChanged]), the composite host (with the debug toast), the
 * FAB position pass-through and the backdrop. The history of the protocol (the single
 * `onSetWallpaperEditMode` toggle that failed, the AUDIT findings) lives in SPEC_NYX_REWRITE and
 * in the git history; the invariants are pinned by `WallpaperEditSessionTest`,
 * `WallpaperOperationsTest`, `WallpaperImageStoreContract` and `WallpaperDelegateTest`.
 */
package com.github.reygnn.kolibri_launcher.ui.main.delegate

import com.github.reygnn.kolibri_launcher.domain.model.SettingsDefaults

import android.content.Context
import android.net.Uri
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.BuildConfig
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.feature.wallpaper.WallpaperComposite
import com.github.reygnn.launcher.feature.wallpaper.WallpaperEditSession
import com.github.reygnn.launcher.feature.wallpaper.WallpaperOperations
import com.github.reygnn.launcher.feature.wallpaper.WallpaperPersistence
import com.github.reygnn.launcher.feature.wallpaper.WallpaperImageStore
import com.github.reygnn.launcher.core.wallpaper.FabPosition
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.usecase.ClearWallpaperUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFabPositionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveWallpaperBackdropUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SaveFabPositionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SaveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetWallpaperBackdropUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetWallpaperImageUseCase
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.launcher.core.wallpaper.LayerTransform
import android.widget.Toast
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn

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
    // Unused since 3a-9 (K2: replace saves the single-image state through the persistence port,
    // which is what this use case did). Kept until its removal is approved (README cleanup list).
    @Suppress("UNUSED_PARAMETER") setWallpaperImageUseCase: SetWallpaperImageUseCase,
    private val clearWallpaperUseCase: ClearWallpaperUseCase,
    private val getFabPositionUseCase: GetFabPositionUseCase,
    private val saveFabPositionUseCase: SaveFabPositionUseCase,
    private val observeWallpaperBackdropUseCase: ObserveWallpaperBackdropUseCase,
    private val setWallpaperBackdropUseCase: SetWallpaperBackdropUseCase,
    /** Every file decision: copy in, delete what no layer needs, orphan GC (3a-2). */
    private val imageStore: WallpaperImageStore,
    /** The display composite since 3a-8: warm, cache, luminance, lock — behind one interface. */
    private val composite: WallpaperComposite,
    private val scope: DelegateScope
) {

    // --- Exposed State ---

    /**
     * The edit session and the displayed state (3a-3): pure and synchronous, main-thread confined
     * like this delegate. It decides, [operations] executes (3a-9), this delegate orchestrates.
     */
    private val session = WallpaperEditSession()

    val wallpaperState: StateFlow<WallpaperState> = session.state

    val isWallpaperEditMode: StateFlow<Boolean> = session.isEditMode

    /**
     * Fires when the wallpaper IMAGE content changed — a new/replaced image
     * ([onSetWallpaperImage]), an added layer ([onAddWallpaperLayer]), or a full
     * clear ([onClearWallpaper]). A pan/zoom-only edit ([onSaveWallpaperTransform])
     * and a cancelled session do NOT fire. A change made inside an edit session is
     * DEFERRED to commit (see [signalImageChanged] / [WallpaperEditSession.noteImageChanged]); a
     * standalone change (the picker path, no session) fires immediately. Neutral
     * signal: the ViewModel decides what to do with it (offer to reset the wallpaper
     * scrim when it is non-zero, so a leftover dim doesn't silently darken a fresh,
     * non-extreme wallpaper). `extraBufferCapacity = 1` keeps the emit non-suspending;
     * with `replay = 0` it does NOT retain a value across a zero-subscriber window —
     * that's fine because every emit path here happens while MainActivity's collector
     * is subscribed (it collects for the whole Activity lifetime), so the buffer only
     * smooths delivery to an already-active collector.
     */
    private val _wallpaperImageChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val wallpaperImageChanged: SharedFlow<Unit> = _wallpaperImageChanged.asSharedFlow()

    /**
     * One-shot signal that the next state emission carries a layer that
     * should be focused (selected as active) after the view rebuild.
     *
     * Emitted by [onAddWallpaperLayer] so that a freshly-added wallpaper
     * becomes active automatically — matches the UX expectation that
     * "I just picked this, I want to adjust it now". The consumer
     * (Fragment/Binder) reads the id alongside the state and then calls
     * [consumePendingFocusLayerId] to clear the signal.
     *
     * Null when nothing new is pending focus.
     */
    val pendingFocusLayerId: StateFlow<String?> = session.pendingFocusLayerId

    /**
     * Clears the pending-focus signal. The consumer must call this after
     * applying the focus, otherwise the next unrelated state emission
     * would spuriously re-focus the same layer.
     */
    fun consumePendingFocusLayerId() = session.consumePendingFocusLayerId()

    /**
     * Persisted position of the wallpaper-edit speed-dial FAB. Emits
     * [FabPosition.DEFAULT] until the user has dragged the FAB and the
     * write round-trips through DataStore. Stateflow rather than raw
     * Flow so the view can read `.value` synchronously on edit-mode
     * entry without suspending.
     */
    val fabPosition: StateFlow<FabPosition> = getFabPositionUseCase()
        .stateIn(
            scope = scope.coroutineScope,
            started = SharingStarted.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS),
            initialValue = FabPosition.DEFAULT,
        )

    /**
     * Persists the user-dragged FAB position. Both axes are stored as
     * fractions of the parent container — clamping into the visible
     * range is the view's job (see the drag handler).
     */
    fun onFabPositionChanged(xFraction: Float, yFraction: Float) =
        scope.launchSafe("Error saving FAB position") {
            saveFabPositionUseCase(FabPosition(xFraction = xFraction, yFraction = yFraction))
        }

    /**
     * The persisted backdrop that sits behind the wallpaper collage, read by the
     * wallpaper-edit CommandsPanel toggle to show the current state. A plain
     * `stateIn` of the DataStore flow: it only ever mirrors what is persisted, so
     * the edit-panel icon can never diverge from the actual on-screen backdrop
     * (which MainActivity drives from the same flow) — in particular a failed
     * write can never leave the icon showing a value that was never stored.
     * DataStore stays the single source of truth.
     *
     * Double-tap correctness is handled on the WRITE side ([onToggleWallpaperBackdrop]),
     * not by making this value optimistic — keeping display strictly = persisted.
     */
    val wallpaperBackdrop: StateFlow<WallpaperBackdrop> = observeWallpaperBackdropUseCase()
        .stateIn(
            scope = scope.coroutineScope,
            started = SharingStarted.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS),
            initialValue = SettingsDefaults.DEFAULT_WALLPAPER_BACKDROP,
        )

    /**
     * Serializes backdrop toggles and remembers the value we last *successfully*
     * persisted. A rapid double-tap thus flips from that intended value rather
     * than the write→read-lagged [wallpaperBackdrop] (which only updates after
     * the DataStore round-trip), so two taps net to a no-op instead of both
     * reading the same stale value. Advanced ONLY after a successful write, so a
     * failed persist leaves the next toggle computing from the last stored value.
     */
    private val backdropToggleMutex = Mutex()
    private var lastWrittenBackdrop: WallpaperBackdrop? = null

    /** Flips the backdrop between system-wallpaper and black and persists it. */
    fun onToggleWallpaperBackdrop() =
        scope.launchSafe("Error toggling wallpaper backdrop") {
            backdropToggleMutex.withLock {
                val current = lastWrittenBackdrop ?: wallpaperBackdrop.value
                val next = when (current) {
                    WallpaperBackdrop.SYSTEM_WALLPAPER -> WallpaperBackdrop.BLACK
                    WallpaperBackdrop.BLACK -> WallpaperBackdrop.SYSTEM_WALLPAPER
                }
                setWallpaperBackdropUseCase(next)
                lastWrittenBackdrop = next
            }
        }

    // --- Edit Session State ---

    // --- Init ---


    fun start() = operations.start()

    // ===========================================
    // STATE MUTATION CORE  (functional core / imperative shell)
    // ===========================================
    //
    // Since 3a-3 every wallpaper-state change is a synchronous transition of [session]
    // ([WallpaperEditSession]): the read-modify-write of the displayed state and the session
    // bookkeeping happen in one non-suspending step, so no suspension point can split it (the
    // AUDIT-6 lost-update race stays unrepresentable). The transition returns its effect as
    // data; suspending work — the file copy BEFORE (re-validated through the rollback
    // generation), the persist and the deletion AFTER — runs here.

    // ===========================================
    // SINGLE-LAYER WALLPAPER
    // ===========================================

    fun onSetWallpaperImage(imageUri: Uri) {
        // Like onAddWallpaperLayer: a Cancel during the copy restores the snapshot synchronously,
        // so the replace must then discard itself (the session compares the generation).
        val rollbackGenAtStart = session.rollbackGeneration
        setWallpaperImage(imageUri, rollbackGenAtStart)
    }

    private fun setWallpaperImage(imageUri: Uri, rollbackGenAtStart: Long) = scope.launchSafe(
        errorMessage = "Error setting wallpaper image",
        defaultErrorToast = R.string.error_generic
    ) {
        when (operations.replace(imageUri, rollbackGenAtStart)) {
            WallpaperOperations.ImageResult.CopyFailed -> scope.sendEvent(UiEvent.ShowToast(R.string.error_generic))
            WallpaperOperations.ImageResult.Discarded -> Unit
            // A new/replaced image → offer a scrim reset (deferred to commit if in a session).
            // No success toast: the new wallpaper IS the confirmation.
            WallpaperOperations.ImageResult.Applied -> signalImageChanged()
        }
    }

    fun onSaveWallpaperTransform(
        scale: Float,
        translateX: Float,
        translateY: Float,
        captureSampleSize: Int? = null
    ) {
        // A single-image wallpaper is the one-element layer list, so the SaveSingle path (view
        // single-mode) writes the transform back into layer 0. The session applies it
        // SYNCHRONOUSLY: persisting only to DataStore (the old path) left the displayed state
        // holding the OLD transform until the write round-tripped, and the commit-triggered
        // re-render briefly regressed the display.
        operations.persistLater(
            "Error saving wallpaper transform",
            session.saveSingleTransform(scale, translateX, translateY, captureSampleSize),
        )
    }

    fun onClearWallpaper() = scope.launchSafe(
        errorMessage = "Error clearing wallpaper",
        defaultErrorToast = R.string.error_generic
    ) {
        // The rules (state first, files second, composite lock before persist lock) live in
        // WallpaperOperations.clear(); here only the outcome is mapped onto the UI.
        if (!operations.clear()) {
            // The removal did not take effect; the wallpaper stays as it is on disk.
            scope.sendEvent(UiEvent.ShowToast(R.string.error_generic))
            return@launchSafe
        }
        scope.sendEvent(UiEvent.ShowToast(R.string.wallpaper_removed))
        signalImageChanged()
    }

    fun onDisplayConfigChanged() = operations.refillCurrent()

    // ===========================================
    // EDIT MODE
    // ===========================================

    /** Opens the session; ignored while one is open (3a-3, E3: the snapshot must not be overwritten). */
    fun onEnterWallpaperEditMode() {
        session.enter()
    }

    /**
     * Routes an image-content change to the scrim-reset offer: immediately when NOT
     * in an edit session (the picker path — the change is already on the settled home
     * screen), or deferred to [onCommitWallpaperEditMode] when mid-session so the
     * offer doesn't cover the edit UI (where the scrim is hidden anyway).
     */
    private fun signalImageChanged() {
        if (session.noteImageChanged()) _wallpaperImageChanged.tryEmit(Unit)
    }

    /**
     * Exits edit mode and commits the session: deferred file deletions
     * from [onRemoveWallpaperLayer] are carried out, orphan tracking
     * is discarded, and in-memory state stays as-is (already persisted).
     */
    fun onCommitWallpaperEditMode() {
        // An image change during the session (deferred so the offer doesn't pop mid-edit, where
        // the scrim is hidden anyway) surfaces now that the user is back on the home screen.
        operations.commit(onImageChanged = { _wallpaperImageChanged.tryEmit(Unit) })
    }

    /**
     * What the composite asks of this delegate (3a-8, J2): plain functions, no session. The
     * composite calls them on Main only, so reading the main-confined session here is safe.
     */
    private val compositeHost = object : WallpaperComposite.Host {
        override fun currentState(): WallpaperState = session.state.value
        override fun isEditing(): Boolean = session.isEditMode.value
        override fun displaySize(): Pair<Int, Int> =
            context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        override fun launch(block: suspend () -> Unit) {
            scope.launchSafe("Error refilling wallpaper cache") { block() }
        }
        override fun onCompositeFilled(widthPx: Int, heightPx: Int) {
            // Debug toast (SHOW_CACHE_TOASTS = true only in debug buildTypes): confirms the
            // composite cache was filled at this resolution. The callback is not suspending,
            // sendEvent is — so the toast is dispatched in its own coroutine (3a-8b).
            if (BuildConfig.SHOW_CACHE_TOASTS) {
                scope.launchSafe("Error showing the composite cache toast") {
                    scope.sendEvent(
                        UiEvent.ShowToastFromString(
                            "Composite cache filled (${widthPx}x$heightPx)",
                            Toast.LENGTH_SHORT,
                        )
                    )
                }
            }
        }
    }

    /** Kolibri's persistence port for [operations] (3a-9, K1): its use cases. */
    private val persistence = object : WallpaperPersistence {
        override suspend fun save(state: WallpaperState) = saveWallpaperStateUseCase(state)
        override suspend fun clear() = clearWallpaperUseCase()
        override fun observe() = observeWallpaperStateUseCase()
    }

    /**
     * The executing half (3a-9): persist/delete order, re-sync, replace/add, remove, start-up.
     * Declared after [compositeHost] (property initialization order). One per session.
     */
    private val operations = WallpaperOperations(
        session = session,
        persistence = persistence,
        imageStore = imageStore,
        composite = composite,
        compositeHost = compositeHost,
        launch = { errorMessage, block -> scope.launchSafe(errorMessage) { block() } },
    )

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
        // The session restores the snapshot synchronously and bumps the generation, so an add or
        // replace still copying discards itself; persisting and deleting run in Operations.
        operations.cancel()
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
        if (session.isEditMode.value) onCommitWallpaperEditMode() else onEnterWallpaperEditMode()
    }

    // ===========================================
    // MULTI-LAYER: MANAGEMENT
    // ===========================================

    fun onAddWallpaperLayer(imageUri: Uri) {
        // Capture the rollback generation synchronously, at invocation time: a Cancel during the
        // copy restores the snapshot before the add resumes, and Operations discards it then.
        val rollbackGenAtStart = session.rollbackGeneration
        scope.launchSafe("Error adding wallpaper layer") {
            // Added a layer → image content changed (deferred to commit in a session).
            if (operations.addLayer(imageUri, rollbackGenAtStart) == WallpaperOperations.ImageResult.Applied) {
                signalImageChanged()
            }
        }
    }

    fun onRemoveWallpaperLayer(layerIndex: Int) {
        // In edit mode the physical delete waits for commit, so a cancel can restore the snapshot
        // including the file; outside edit mode it happens after the save. Removing the last layer
        // needs no separate clear: saving a state without wallpaper wipes all keys.
        operations.persistLater("Error removing wallpaper layer", session.removeLayer(layerIndex))
    }

    fun onSwapWallpaperLayers(indexA: Int, indexB: Int) =
        operations.persistLater("Error swapping wallpaper layers", session.swapLayers(indexA, indexB))

    // ===========================================
    // MULTI-LAYER: TRANSFORMS
    // ===========================================

    fun onSaveLayerTransform(
        layerIndex: Int,
        scale: Float,
        translateX: Float,
        translateY: Float,
        captureSampleSize: Int? = null
    ) = operations.persistLater(
        "Error saving layer transform",
        session.saveLayerTransform(layerIndex, scale, translateX, translateY, captureSampleSize),
    )

    fun onSaveAllLayerTransforms(
        transforms: List<LayerTransform>
    ) {
        // The view scale is stored RAW and captureSampleSize tagged (Ansatz Y is tag-only, spec
        // §4-Y): no ÷S on save, the load side multiplies the S_render/S_captured ratio.
        operations.persistLater("Error saving all layer transforms", session.saveAllLayerTransforms(transforms))
    }

}
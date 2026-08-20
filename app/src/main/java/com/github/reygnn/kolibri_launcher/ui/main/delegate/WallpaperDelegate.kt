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
 * ARCHITECTURAL NOTE: The Transactional Edit-Session Protocol
 * =====================================================================================
 *
 * The edit session (Enter → mutations... → Commit | Cancel) is the most intricate
 * piece of this delegate, and it got that way because naive implementations kept
 * producing subtle bugs that only showed up in specific user workflows. This note
 * explains the contract so that future maintainers understand the invariants they
 * must preserve.
 *
 * **The Naive Approach (That Failed):**
 * Originally there was only one method: `onSetWallpaperEditMode(enabled: Boolean)`.
 * The fragment held a private field `wallpaperStateBeforeEdit: WallpaperState?`,
 * snapshotted it when entering edit mode, and on Cancel tried to apply each snapshot-
 * layer's transforms back to the view at matching positions.
 *
 * This broke the moment the user did the natural thing: enter edit mode, DELETE a
 * layer, then click Cancel. The delete had already persisted the shrunk state AND
 * deleted the file from disk (irreversibly). Cancel could only apply transforms to
 * whatever layers remained, producing wrong visual assignments. Even worse, the
 * deleted layer's file was gone — Cancel could never truly restore it.
 *
 * **The Contract Now:**
 *
 * 1. **Enter** — onEnterWallpaperEditMode:
 *    - Takes a snapshot of the current [WallpaperState] in [editSnapshot].
 *    - Clears both pending-removal sets.
 *    - Sets [isWallpaperEditMode] to true.
 *    - Fragment uses this signal to switch into edit UX (snap handles, toolbar, etc.).
 *
 * 2. **Mutations during session** — onAddWallpaperLayer, onRemoveWallpaperLayer,
 *    [onSaveLayerTransform], [onSwapWallpaperLayers], etc:
 *    - All update [_wallpaperState] AND persist via [saveWallpaperStateUseCase] normally.
 *    - **BUT file deletions are deferred**: [onRemoveWallpaperLayer] in edit mode adds
 *      the URI to [pendingRemovalsOnCommit] instead of deleting the file. This
 *      preserves the disk copy so Cancel can restore it.
 *    - **AND file additions are tracked**: [onAddWallpaperLayer] in edit mode adds
 *      the new internal URI to [pendingRemovalsOnCancel], so these orphan files get
 *      cleaned up if the user backs out.
 *
 * 3. **Commit** — onCommitWallpaperEditMode:
 *    - Processes [pendingRemovalsOnCommit]: actually deletes those files now.
 *    - Discards [pendingRemovalsOnCancel]: added layers survive.
 *    - Clears [editSnapshot] and sets [isWallpaperEditMode] to false.
 *
 * 4. **Cancel** — onCancelWallpaperEditMode:
 *    - **Synchronously** restores [_wallpaperState] to the snapshot. This is critical
 *      — see below.
 *    - Asynchronously persists the restored snapshot and processes
 *      [pendingRemovalsOnCancel] (cleaning up files from added-then-cancelled layers).
 *    - Discards [pendingRemovalsOnCommit]: deleted layers' files survive because they're
 *      referenced again by the restored snapshot.
 *    - Clears [pendingFocusLayerId] so that a stale add-layer focus hint doesn't
 *      point at a layer that no longer exists in the restored snapshot.
 *
 * **Why Synchronous State Restoration on Cancel:**
 * After `viewModel.onCancelWallpaperEditMode()` returns, the fragment immediately
 * reads `viewModel.wallpaperState.value` and passes it to `updateWallpaper()`. If the
 * restore happened asynchronously, this read would see the pre-cancel state and the
 * view rebuild would display the wrong thing — exactly the bug the edit session was
 * designed to fix. The disk persistence is still async (fire-and-forget via
 * `launchSafe`) because that's cosmetic, but the in-memory value MUST land before
 * the caller returns.
 *
 * **Why The Legacy `onSetWallpaperEditMode(enabled: Boolean)` Still Exists:**
 * Some call sites (long-press to exit, MainActivity lifecycle events) can't or
 * shouldn't distinguish between "I'm done, keep changes" and "cancel". They call
 * the legacy API with `true/false`, which routes to Enter/Commit respectively —
 * the same behavior as before the transactional API was added. This preserves
 * backward compatibility without forcing every caller to learn the new protocol.
 *
 * **Invariants A Maintainer Must Preserve:**
 * - An edit session always ends via EXACTLY ONE of: Commit, Cancel, or the legacy
 *   `onSetWallpaperEditMode(false)` (which routes to Commit).
 * - Never delete a file inline when _isWallpaperEditMode is true — always route
 *   through pendingRemovalsOnCommit so Cancel can restore.
 * - Never skip the pending-removal bookkeeping on add — dangling files will
 *   eventually be caught by `gcOrphans`, but relying on that instead of explicit
 *   tracking is fragile and makes the cancel path slower.
 * - Never make Cancel's state-restore asynchronous. The fragment's immediate read
 *   depends on synchronicity.
 * - A layer add must survive a Commit but not a Cancel. [onAddWallpaperLayer] copies
 *   the picked file on a suspending IO hop that releases the main dispatcher, so a
 *   synchronous Cancel can restore the snapshot mid-copy. The add captures
 *   [editRollbackGeneration] before the copy and re-checks it after; if a rollback
 *   happened, the add discards itself (deletes its orphan file, persists nothing)
 *   instead of reviving a layer onto the restored state. Commit does NOT bump the
 *   generation — it keeps state, so a resuming add is applied normally (appended to
 *   the committed state). Never bypass this re-check, and never bump the generation on
 *   Commit — synchronous restore only protects the read path, not a resuming add.
 * - Never suspend between reading and writing [_wallpaperState]. Every mutation
 *   is expressed as a pure `Mutation` and applied through the synchronous
 *   `applyState` critical section (see STATE MUTATION CORE below); suspending
 *   work — the file copy, the DataStore persist — is placed strictly BEFORE or
 *   AFTER it, never in the middle. The reverted `deleteFile -> withContext(IO)`
 *   change (AUDIT-6 addendum) violated exactly this and reintroduced a
 *   lost-update race; the structure now makes that class of bug unrepresentable.
 *
 * **Regression Guards:**
 * - `onRemoveWallpaperLayer applies the removal synchronously`
 * - `onAddWallpaperLayer resuming after cancel discards the layer and deletes its file`
 * - `onAddWallpaperLayer resuming after commit still persists the layer`
 * - `onAddWallpaperLayer resuming within the same session still persists the layer`
 * - `onCancelWallpaperEditMode restores snapshot state synchronously`
 * - `onCancelWallpaperEditMode does not delete deferred-remove files`
 * - `onCancelWallpaperEditMode deletes files added during edit mode`
 * - `onCommitWallpaperEditMode deletes deferred-remove files`
 * - `onCommitWallpaperEditMode does not delete files added during edit mode`
 * - `onRemoveWallpaperLayer in edit mode defers file deletion`
 * - `onRemoveWallpaperLayer outside edit mode deletes file immediately`
 * All in com.github.reygnn.kolibri_launcher.ui.main.delegate.WallpaperDelegateTest.
 * =====================================================================================
 */

package com.github.reygnn.kolibri_launcher.ui.main.delegate

import android.content.Context
import android.net.Uri
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.data.WallpaperFileManager
import com.github.reygnn.kolibri_launcher.domain.model.FabPosition
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.usecase.ClearWallpaperUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFabPositionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SaveFabPositionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SaveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetWallpaperImageUseCase
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.util.LaunchTrace
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.LayerTransform
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperFlattener
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperCompositeCache
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperCompositeKey
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.DecodedWallpaperBitmap
import android.graphics.Bitmap
import com.github.reygnn.kolibri_launcher.data.wallpaper.WallpaperBitmapLuminanceImpl
import com.github.reygnn.kolibri_launcher.core.CompositeLuminanceSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
// Async-trace cookies for the composite warm (see warmComposite). Constants are safe because
// single-flight guarantees no two warms overlap; warm and flatten nest by distinct name+cookie.
private const val WARM_TRACE_COOKIE = 0x7A31
private const val FLATTEN_TRACE_COOKIE = 0x7A32

class WallpaperDelegate(
    private val context: Context,
    private val observeWallpaperStateUseCase: ObserveWallpaperStateUseCase,
    private val saveWallpaperStateUseCase: SaveWallpaperStateUseCase,
    private val setWallpaperImageUseCase: SetWallpaperImageUseCase,
    private val clearWallpaperUseCase: ClearWallpaperUseCase,
    private val getFabPositionUseCase: GetFabPositionUseCase,
    private val saveFabPositionUseCase: SaveFabPositionUseCase,
    private val wallpaperFileManager: WallpaperFileManager,
    private val wallpaperFlattener: WallpaperFlattener,
    private val compositeCache: WallpaperCompositeCache,
    private val bitmapLuminance: WallpaperBitmapLuminanceImpl,
    private val compositeLuminanceSignal: CompositeLuminanceSignal,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: DelegateScope
) {

    // --- Exposed State ---

    private val _wallpaperState = MutableStateFlow(WallpaperState.NONE)
    val wallpaperState: StateFlow<WallpaperState> = _wallpaperState.asStateFlow()

    private val _isWallpaperEditMode = MutableStateFlow(false)
    val isWallpaperEditMode: StateFlow<Boolean> = _isWallpaperEditMode.asStateFlow()

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
    private val _pendingFocusLayerId = MutableStateFlow<String?>(null)
    val pendingFocusLayerId: StateFlow<String?> = _pendingFocusLayerId.asStateFlow()

    /**
     * Clears the pending-focus signal. The consumer must call this after
     * applying the focus, otherwise the next unrelated state emission
     * would spuriously re-focus the same layer.
     */
    fun consumePendingFocusLayerId() {
        _pendingFocusLayerId.value = null
    }

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

    // --- Edit Session State ---

    /**
     * Snapshot of the wallpaper state at the moment edit mode was entered.
     * Used by [onCancelWallpaperEditMode] to roll back all changes made
     * during the edit session (transforms, adds, removes, swaps).
     * null when not in an edit session.
     */
    private var editSnapshot: WallpaperState? = null

    /**
     * Guards the lazy cache refill ([refillCache]) against CONCURRENT runs: true
     * while a background fill (single-layer decode or multi-layer flatten) is in
     * flight, reset when it finishes. NOT once-per-process — a launcher runs for
     * weeks and can see several cache-less states over time (each backup restore
     * brings a different wallpaper), and each must get its own refill. Loop-safe
     * without a once-flag: a SUCCESSFUL refill caches the entry, so the re-emitted
     * state's key hits and no longer qualifies; a FAILURE just retries on the next
     * (rare) state change, not in a tight loop (state emissions for a stable
     * wallpaper are infrequent). Main-thread confined (set on the collect, reset in
     * the coroutine's finally, both on the delegate scope).
     */
    private var refillInProgress = false

    /**
     * Serializes the in-memory composite warm ([warmComposite]) against the user clear
     * ([onClearWallpaper]) on this delegate (WALLPAPER_COMPOSITE_LIFECYCLE_SPEC v4). The warm
     * ends in a cache `put`; the clear does a cache `invalidate` + optimistic NONE. Holding the
     * lock across both keeps them mutually exclusive, so a clear can't land between a warm's
     * flatten and its put and be immediately overwritten by a stale composite. Belt-and-braces
     * on top of the warm's key-gated put (which already drops a put whose key is no longer
     * current): a clear sets NONE, so a warm resuming on the lock fails its key gate and drops
     * its bitmap. No disk, no pointer, no cross-module dir lock — the whole F1–F8 disk class is
     * gone (the store was deleted); this lock now guards only the single in-memory resource.
     */
    private val compositeRegenLock = Mutex()

    /**
     * Monotonic counter bumped ONLY when an edit session is rolled back
     * ([onCancelWallpaperEditMode]). [onAddWallpaperLayer] captures it
     * before its suspending file copy and re-checks it after:
     * `copyToInternal` hops to `Dispatchers.IO` and releases the
     * single-threaded main dispatcher, so a synchronous Cancel can restore
     * the snapshot mid-copy. An add that resumes across a rollback must NOT
     * persist its layer — that would revive it onto the restored state and
     * race the snapshot save (the bug this guards).
     *
     * Only Cancel bumps this, deliberately: Commit keeps the current state
     * and does NOT restore, so an add resuming after a Commit is applied
     * normally (appended to the committed state and persisted) — exactly
     * the pre-guard behavior. See the ARCHITECTURAL NOTE above.
     */
    private var editRollbackGeneration = 0L

    /**
     * URIs of layers removed during the current edit session.
     * The physical file deletion is deferred until commit so that a
     * cancel can fully restore the state – including the file on disk.
     */
    private val pendingRemovalsOnCommit = mutableSetOf<String>()

    /**
     * URIs of internal files created by layers added during the current
     * edit session. If the session is canceled, these orphan files are
     * cleaned up; if committed, they are kept.
     */
    private val pendingRemovalsOnCancel = mutableSetOf<String>()

    // --- Init ---


    fun start() {
        scope.launchSafe("Error observing wallpaper state") {
            // =================================================================================
            // ARCHITECTURAL NOTE: Why the orphan GC runs EXACTLY ONCE per process, not per emission
            // =================================================================================
            //
            // Two questions a future maintainer will have:
            //
            //   Q1: "Shouldn't we GC more often? Files leak on every crash."
            //   Q2: "Shouldn't we GC inline right after every state save? Cheaper than a Flow."
            //
            // The answer to both is NO, for a subtle reason that is not obvious from reading
            // the code path in isolation.
            //
            // **Why Not Per-Emission:**
            // A per-emission GC would run every time the state changes — every time a layer
            // is added, removed, transformed, re-ordered, or when the edit session is committed
            // or cancelled. During a backup restore (BackupRepositoryImpl -> WallpaperFileManager.
            // copyFromInputStream for each extracted image) this emits *multiple* intermediate
            // states as each layer is added. If the GC ran between those, it would see
            // "state has 3 layers, disk has 4 files" and delete the fourth file — the one
            // that's about to be added to the state in the next tick. Restore would lose data.
            //
            // The 60-second age cutoff in gcOrphans would protect us in MOST of those cases,
            // but not all — a slow device extracting a large ZIP with many files could take
            // longer than 60s between file-write and state-save. We don't want to rely on
            // the age cutoff as the only safety net when we can avoid the race entirely.
            //
            // **Why Not Inline After Every Save:**
            // Same reason — the timing window is the problem, not the call frequency. Inline
            // GC would have the exact same race with backup restore, just triggered from a
            // different call site.
            //
            // **Why Once Per Process:**
            // - On app start, we get the FIRST authoritative state emission from DataStore.
            //   At that point, any file on disk that isn't in the state is either (a) an
            //   orphan from a previous crashed operation, or (b) a currently-in-progress
            //   write. Case (b) is handled by the age cutoff in gcOrphans.
            // - Running exactly once catches 100% of leftover orphans with zero risk of
            //   deleting a file mid-write later in the session (there's no later GC to race
            //   with).
            // - The gcHasRun closure variable makes this self-evident without pulling in
            //   a separate state flow.
            //
            // **Why The Edit-Mode Check:**
            // If the user enters edit mode BEFORE the first state emission (unusual but
            // possible during configuration changes), we defer the GC. Pending-removal files
            // tracked in pendingRemovalsOnCommit/pendingRemovalsOnCancel are only referenced
            // by the in-memory editSnapshot, NOT by state.referencedUris — a GC here would
            // destroy them. The GC will run on the first post-edit-mode emission instead.
            //
            // **Regression Guards:**
            // - `start runs gcOrphans exactly once across multiple state emissions`
            // - `start does not run gcOrphans while an edit session is active`
            // Both in [WallpaperDelegateTest].
            // =================================================================================
            var gcHasRun = false
            observeWallpaperStateUseCase().collect { state ->
                _wallpaperState.value = state

                if (!gcHasRun && !_isWallpaperEditMode.value) {
                    gcHasRun = true
                    try {
                        // gcOrphans does blocking disk I/O (listFiles + delete);
                        // hop off the main dispatcher (this collect runs on it).
                        withContext(ioDispatcher) {
                            wallpaperFileManager.gcOrphans(state.referencedUris)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Wallpaper orphan GC failed")
                    }
                }

                refillCache(state)
            }
        }
    }

    // ===========================================
    // STATE MUTATION CORE  (functional core / imperative shell)
    // ===========================================
    //
    // Every wallpaper-state change is expressed as a pure [Mutation] value —
    // the resulting state plus any file side-effects — computed WITHOUT touching
    // the world. [applyState] is then the single place that writes
    // [_wallpaperState] and the edit-session bookkeeping, and it is SYNCHRONOUS
    // and non-suspending by construction: there is no way to await between
    // reading `.value` and writing it back, so a mutation's read-modify-write
    // can never be split by a suspension point and clobbered by a concurrent
    // mutation on the single-threaded main dispatcher.
    //
    // This is the structural guard the AUDIT-6 addendum is about: the reverted
    // `deleteFile -> withContext(IO)` change inserted a suspend point INTO such a
    // read-modify-write and reintroduced a lost-update race. With the transition
    // pre-computed as data, that class of bug is gone by construction. The only
    // suspension left is the file copy in [onAddWallpaperLayer] (BEFORE the
    // transition, re-validated via [editRollbackGeneration]) and the persist
    // epilogue in [persist] (AFTER the atomic write).

    /**
     * Pure description of a single state change: the resulting [newState] plus
     * the file side-effects it implies. No I/O, no suspension — built by the
     * caller, executed by [applyState] + [persist].
     */
    private data class Mutation(
        val newState: WallpaperState,
        /** Internal files to delete now (outside an edit session). */
        val deleteNow: List<String> = emptyList(),
        /** Removed-layer URIs whose physical deletion is deferred until commit. */
        val deferToCommit: List<String> = emptyList(),
        /** Added-layer URIs to clean up if the session is cancelled. */
        val trackForCancel: List<String> = emptyList(),
        /** Layer id to focus (activate) after the imminent view rebuild, if any. */
        val focusLayerId: String? = null,
    )

    /**
     * THE synchronous critical section. Applies [m] to [_wallpaperState] and the
     * edit-session bookkeeping atomically. Non-suspending by construction: the
     * read-modify-write that produced [m] and this write cannot be interleaved
     * by another main-dispatcher coroutine.
     *
     * The state write happens LAST so that consumers reacting to the state
     * emission already see the focus hint and bookkeeping in place.
     */
    private fun applyState(m: Mutation) {
        m.focusLayerId?.let { _pendingFocusLayerId.value = it }
        pendingRemovalsOnCommit.addAll(m.deferToCommit)
        pendingRemovalsOnCancel.addAll(m.trackForCancel)
        _wallpaperState.value = m.newState
    }

    /** Persist epilogue — runs AFTER the atomic [applyState] write. */
    private suspend fun persist(m: Mutation) {
        saveWallpaperStateUseCase(m.newState)
        // deleteFile is blocking disk I/O — hop off the main dispatcher. It is
        // internally guarded (never throws), so no per-file try/catch is needed.
        if (m.deleteNow.isNotEmpty()) {
            withContext(ioDispatcher) {
                m.deleteNow.forEach { wallpaperFileManager.deleteFile(it) }
            }
        }
    }

    /**
     * Synchronous apply + scheduled persist. For the non-suspending mutations
     * (remove, swap, layer properties, batch transforms): the state transition
     * lands immediately and the DataStore write is fired off afterwards.
     */
    private fun commit(errorMessage: String, m: Mutation) {
        applyState(m)
        scope.launchSafe(errorMessage) { persist(m) }
    }

    // ===========================================
    // SINGLE-LAYER WALLPAPER
    // ===========================================

    fun onSetWallpaperImage(imageUri: Uri) = scope.launchSafe(
        errorMessage = "Error setting wallpaper image",
        defaultErrorToast = R.string.error_generic
    ) {
        val internalUri = wallpaperFileManager.copyToInternal(imageUri)
        if (internalUri == null) {
            TimberWrapper.silentError("Failed to copy wallpaper to internal storage")
            scope.sendEvent(UiEvent.ShowToast(R.string.error_generic))
            return@launchSafe
        }
        setWallpaperImageUseCase(internalUri.toString())
        // No success toast: the new wallpaper IS the confirmation — it is on screen
        // before any toast could be read. The previous one showed the picked file's
        // DISPLAY_NAME, which on a SAF/cloud provider is an opaque temporary name.
        // Dropping it also drops a blocking binder IPC into a foreign provider
        // (the DISPLAY_NAME query) from the wallpaper-set path.
    }

    fun onSaveWallpaperTransform(
        scale: Float,
        translateX: Float,
        translateY: Float,
        captureSampleSize: Int? = null
    ) {
        val currentState = _wallpaperState.value
        if (!currentState.hasWallpaper) return
        // Update _wallpaperState SYNCHRONOUSLY via the commit core — same as the
        // multi-layer transform save (onSaveLayerTransform). Persisting only to
        // DataStore (the old path) left _wallpaperState holding the OLD transform
        // until the async write round-tripped, so the commit-triggered re-render
        // (HomeFragment Observer 8, fired by the edit-mode flag flip) read the stale
        // state and briefly regressed the display to the old scale/position before
        // the DataStore emission corrected it. commit() applies synchronously first.
        commit(
            "Error saving wallpaper transform",
            Mutation(
                currentState.copy(
                    scale = scale,
                    translateX = translateX,
                    translateY = translateY,
                    captureSampleSize = captureSampleSize,
                )
            ),
        )
    }

    fun onClearWallpaper() = scope.launchSafe(
        errorMessage = "Error clearing wallpaper",
        defaultErrorToast = R.string.error_generic
    ) {
        // Serialize with composite regeneration (AUDIT-20 F6): a lazy refill or a
        // commit-triggered fill in flight must not re-cache a bitmap after the wallpaper
        // is gone. Holding the regen lock across the clear makes them mutually
        // exclusive; the optimistic NONE below makes a regen still queued on the lock
        // fail its latest-wins guard and drop its own file (F7) rather than resurrect
        // the removed wallpaper with an orphaned composite.
        compositeRegenLock.withLock {
            // clearAll does blocking disk I/O — hop off the main dispatcher.
            withContext(ioDispatcher) { wallpaperFileManager.clearAll() }
            // Removes the DataStore wallpaper keys.
            clearWallpaperUseCase()
            // Drop the in-memory composite (v4 §3, was AUDIT-20 F3): nothing displays a
            // composite after a clear, so the ~10 MB HARDWARE bitmap would otherwise stay
            // resident. invalidate() only drops the reference (never recycles).
            compositeCache.invalidate()
            // Drop the composite luminance too (v4.3), so the AUTO classifier stops using a
            // removed wallpaper's value and falls back to its heuristic / the system signal.
            compositeLuminanceSignal.emit(null)
            // Optimistic in-memory NONE, mirroring onCancelWallpaperEditMode's synchronous
            // restore: the observe flow re-emits NONE shortly (idempotent), but setting it now
            // closes the window in which a warm resuming right after this lock releases would
            // still read the pre-clear state (its key-gated put then fails on NONE).
            _wallpaperState.value = WallpaperState.NONE
        }
        scope.sendEvent(UiEvent.ShowToast(R.string.wallpaper_removed))
    }

    /**
     * The display configuration changed (rotate / fold / multi-window resize), so the composite
     * key's width/height changed and any cached composite is now wrong-resolution (v4 §3a/R2). A
     * config change emits no new [WallpaperState], so this is the refill trigger for that case:
     * re-evaluate the current state at the new metrics and refill on a miss. (Single-layer is
     * resolution-independent — its `file://` key is unchanged — so this is a no-op for it.)
     */
    fun onDisplayConfigChanged() = refillCache(_wallpaperState.value)

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

        // F13: a wallpaper edited down to a single layer collapses back to the
        // single-layer representation, so the next render takes the cheaper
        // decode-cache path (file://) instead of the flatten path (composite://).
        // No-op unless exactly one layer remains — see WallpaperState.toSingleLayer.
        // Done at the commit boundary, not per removal, so the live editor never
        // desyncs from a mid-session single-layer state. commit() applies
        // synchronously, so the snapshot below already sees the collapsed state.
        val current = _wallpaperState.value
        val collapsed = current.toSingleLayer()
        if (collapsed !== current) {
            commit("Error collapsing single-layer wallpaper", Mutation(collapsed))
        }

        // Snapshot the committed state now; the flatten below reads it.
        val committedState = _wallpaperState.value

        scope.launchSafe("Error committing wallpaper edit") {
            // deleteFile is blocking disk I/O — hop off the main dispatcher. It is
            // internally guarded (never throws), so a bad delete can't abort the
            // batch and no per-file wrapper is needed (Rule 11).
            if (filesToDelete.isNotEmpty()) {
                withContext(ioDispatcher) {
                    filesToDelete.forEach { wallpaperFileManager.deleteFile(it) }
                }
            }
            // Refill the display cache for the committed wallpaper (single-layer decode or
            // multi-layer composite) so drawer->home is a cache hit, not a re-decode (§9.3).
            refillCache(committedState)
        }
    }

    /**
     * Refills the in-memory display cache for [state] (WALLPAPER_COMPOSITE_LIFECYCLE_SPEC v4
     * §3/§3a): an existing wallpaper whose cached bitmap is missing — cold start, edit-commit,
     * backup restore, or a rotate/fold (new resolution) — gets one produced in the BACKGROUND so
     * the next drawer->home is a ~0 ms cache hit.
     *
     * ONE entry point, branching by representation (AUDIT-20 F15 — single-layer used to have no
     * proactive fill and only cached lazily on the render path):
     *  - no wallpaper       -> nothing
     *  - single-layer image -> HARDWARE decode, cached under the `file://` key. A lone image needs
     *    no compositing: the render positions it via the ImageView matrix, so the cache holds the
     *    raw (possibly small) bitmap and the system wallpaper shows through any uncovered area —
     *    cheaper than pre-baking a full-screen composite.
     *  - multi-layer        -> flatten N layers -> HARDWARE composite, cached under `composite://`.
     *
     * Deliberately NOT on the launch hot path. Single-flighted ([refillInProgress]) and skipped
     * during edit mode (the layers are mid-change; the commit path refills). Gated on a cache MISS
     * for the current key, so an already-warm state is a no-op. On completion it self-reschedules
     * (spec S5) if the current state moved to a DIFFERENT miss during the fill — but never re-fires
     * the SAME key, so a persistently failing fill cannot loop.
     */
    private fun refillCache(state: WallpaperState) {
        if (refillInProgress) return
        if (_isWallpaperEditMode.value) return
        val key = cacheKeyOrNull(state) ?: return
        if (compositeCache.get(key) != null) return
        refillInProgress = true
        scope.launchSafe("Error refilling wallpaper cache") {
            try {
                if (state.isMultiLayer) warmComposite(state, key) else warmSingleLayer(state, key)
            } finally {
                refillInProgress = false
                // Self-reschedule (S5): only if the current state is a DIFFERENT miss — never the
                // same key, so a failed/incomplete fill does not loop.
                val current = _wallpaperState.value
                if (!_isWallpaperEditMode.value) {
                    val currentKey = cacheKeyOrNull(current)
                    if (currentKey != null && currentKey != key && compositeCache.get(currentKey) == null) {
                        refillCache(current)
                    }
                }
            }
        }
    }

    /**
     * The display-cache key for [state], or null if it has no wallpaper. Multi-layer -> the
     * resolution-keyed `composite://` key; single-layer -> the raw `file://` image URI. The
     * single-layer key matches `HomeFragment.loadBitmapFromUri`'s `uri.toString()` because
     * wallpaper URIs come from `WallpaperFileManager.copyToInternal` (canonical `file://`), so
     * `imageUri == imageUri.toUri().toString()` — keeping this side Uri-free.
     */
    private fun cacheKeyOrNull(state: WallpaperState): String? = when {
        !state.hasWallpaper -> null
        state.isMultiLayer -> compositeKey(state)
        else -> state.imageUri
    }

    /**
     * Single-layer refill: HARDWARE-decode the lone image via the flattener (which owns the
     * `Uri`/`contentResolver`), then key-gated cache it under its `file://` key. A decode that
     * finishes after a clear (NONE) or a supersede drops its bitmap rather than stranding a stale
     * entry (AUDIT-20 F9). No compositing — the render positions it live via the ImageView matrix.
     */
    private suspend fun warmSingleLayer(state: WallpaperState, key: String) = compositeRegenLock.withLock {
        val uriString = state.imageUri ?: return@withLock
        val decoded = wallpaperFlattener.decodeSingle(uriString) ?: return@withLock
        val current = _wallpaperState.value
        if (!current.isMultiLayer && current.imageUri == key) {
            compositeCache.put(key, decoded)
            // TEMP (remove later): visual signal on each single-layer cache fill — kept until the
            // unified refill is verified 100% on-device. Symmetric with the composite warm's toast.
            val m = context.resources.displayMetrics
            scope.sendEvent(
                UiEvent.ShowToastFromString("Single-layer cache filled (${m.widthPixels}x${m.heightPixels})")
            )
        }
    }

    /**
     * The composite cache key for [state] at the CURRENT display metrics. The one pinned metric
     * source (spec §3a): both this warm-write side and the fragment's render-read side MUST read
     * `context.resources.displayMetrics`, or the two keys diverge and the hit never lands.
     */
    private fun compositeKey(state: WallpaperState): String {
        val m = context.resources.displayMetrics
        return WallpaperCompositeKey.of(state, m.widthPixels, m.heightPixels)
    }

    /**
     * Flatten [state] (SOFTWARE) -> copy to HARDWARE -> key-gated cache put -> recycle the
     * software temp (spec §3). A partial/incomplete flatten returns null from the flattener
     * (all-or-nothing) and is not cached. The HARDWARE copy is the transition the deleted disk
     * round-trip used to provide; it is the ~10 MB bitmap the cache holds and the view draws
     * (never recycled). Serialized with [onClearWallpaper] via [compositeRegenLock] so a clear
     * cannot interleave a warm's cache put.
     */
    private suspend fun warmComposite(state: WallpaperState, key: String) = compositeRegenLock.withLock {
        // Async trace sections (measured by :macrobenchmark) — the warm suspends / hops threads,
        // so sync sections would mis-report. Cookies are constants: single-flight guarantees no
        // two warms overlap, and warm/flatten nest by distinct name+cookie. try/finally keeps
        // them balanced across the `?:` early returns.
        LaunchTrace.beginAsync(LaunchTrace.Names.WALLPAPER_WARM, WARM_TRACE_COOKIE)
        try {
        val metrics = context.resources.displayMetrics
        LaunchTrace.beginAsync(LaunchTrace.Names.WALLPAPER_FLATTEN, FLATTEN_TRACE_COOKIE)
        val software = try {
            wallpaperFlattener.flatten(state, metrics.widthPixels, metrics.heightPixels)
        } finally {
            LaunchTrace.endAsync(LaunchTrace.Names.WALLPAPER_FLATTEN, FLATTEN_TRACE_COOKIE)
        }
        if (software == null) {
            // Failed/partial flatten: drop any stale luminance so the AUTO classifier does not keep
            // a PREVIOUS wallpaper's value for this state's un-producible composite (review #1).
            dropLuminanceIfCurrent(key)
            return@withLock
        }
        // Sample the composite LUMINANCE from the SOFTWARE bitmap (readable) BEFORE the HARDWARE
        // copy makes it unreadable (v4.3) — the AUTO classifier reads it via CompositeLuminanceSignal.
        // Then copy to HARDWARE for the display cache. Recycle the software temp either way.
        val hardware: Bitmap?
        val luminance: Float?
        try {
            val out = withContext(ioDispatcher) {
                val lum = bitmapLuminance.computeFromBitmap(software)
                val hw = software.copy(Bitmap.Config.HARDWARE, /* isMutable = */ false)
                hw to lum
            }
            hardware = out.first
            luminance = out.second
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // copy() of a full-screen composite is an allocation boundary (OOM). A failure just
            // means no composite this time; the display stays on the per-layer path.
            TimberWrapper.silentError(e, "Composite HARDWARE copy / luminance failed")
            dropLuminanceIfCurrent(key)
            return@withLock
        } finally {
            software.recycle()
        }
        if (hardware == null) {
            dropLuminanceIfCurrent(key)
            return@withLock
        }
        // Key-gated put (spec §1): only cache if this key is still the current wallpaper's key.
        // A warm that finishes after a clear (NONE, not multi-layer) or a supersede drops its
        // bitmap (uncached -> GC) rather than stranding a stale ~10 MB entry.
        val current = _wallpaperState.value
        if (current.isMultiLayer && compositeKey(current) == key) {
            compositeCache.put(
                key,
                DecodedWallpaperBitmap(
                    bitmap = hardware,
                    sampleSize = 1,
                    originalWidth = metrics.widthPixels,
                    originalHeight = metrics.heightPixels,
                ),
            )
            // Publish the composite luminance for the AUTO classifier (v4.3, ACCEPTED_LIMITATIONS #1).
            compositeLuminanceSignal.emit(luminance)
            // TEMP (remove later): visual signal on each composite cache (re)fill — to gauge how
            // often a re-flatten is actually needed (cold start / edit-commit / rotate). The
            // resolution in the text distinguishes a rotate-triggered refill from the rest.
            //
            // A ONE-layer state reaches this path too: `isMultiLayer` is `layers.isNotEmpty()`,
            // and removing layers in the editor never collapses back to the single-layer
            // representation — so "Composite" would be a lie for what the user just saved. It
            // reports as a single-layer fill instead, matching the wording of the decode-side
            // fill in HomeFragment. Note the MECHANISM still differs: this one flattened the
            // layer (decode -> compose -> HARDWARE copy) and cached it under a `composite://`
            // key, while the HomeFragment twin merely cached a `file://` decode.
            val what = if (state.layerCount == 1) "Single-layer" else "Composite"
            scope.sendEvent(
                UiEvent.ShowToastFromString(
                    "$what cache filled (${metrics.widthPixels}x${metrics.heightPixels})"
                )
            )
        }
        } finally {
            LaunchTrace.endAsync(LaunchTrace.Names.WALLPAPER_WARM, WARM_TRACE_COOKIE)
        }
    }

    /**
     * On a warm that could not produce a composite for [key], drop a now-stale composite luminance
     * (review #1) — but only if [key] is still the current wallpaper's key, so a superseded warm's
     * failure never clobbers a newer valid signal. Without this, a failed warm for a new wallpaper
     * would leave the AUTO classifier using the PREVIOUS wallpaper's luminance (a wrong LIGHT/DARK
     * until the next successful warm / rotate / restart); emitting null makes it fall back to this
     * wallpaper's own `layers[0]` heuristic instead.
     */
    private fun dropLuminanceIfCurrent(key: String) {
        val current = _wallpaperState.value
        if (current.isMultiLayer && compositeKey(current) == key) {
            compositeLuminanceSignal.emit(null)
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
        editRollbackGeneration++
        val snapshot = editSnapshot
        val filesToDelete = pendingRemovalsOnCancel.toSet()

        if (snapshot != null) {
            _wallpaperState.value = snapshot
        }
        // Any pending-focus hint from a mid-session add now points to a
        // layer that no longer exists in the restored snapshot.
        _pendingFocusLayerId.value = null
        pendingRemovalsOnCancel.clear()
        pendingRemovalsOnCommit.clear()
        editSnapshot = null
        _isWallpaperEditMode.value = false

        if (snapshot == null && filesToDelete.isEmpty()) return

        scope.launchSafe("Error canceling wallpaper edit") {
            if (snapshot != null) {
                saveWallpaperStateUseCase(snapshot)
            }
            // deleteFile is blocking disk I/O — hop off the main dispatcher. It is
            // internally guarded (never throws) — no per-file wrapper (Rule 11).
            if (filesToDelete.isNotEmpty()) {
                withContext(ioDispatcher) {
                    filesToDelete.forEach { wallpaperFileManager.deleteFile(it) }
                }
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

    fun onAddWallpaperLayer(imageUri: Uri, label: String? = null) {
        // Capture the rollback generation synchronously, at invocation time.
        // The copy below hops to Dispatchers.IO and releases the main
        // dispatcher, so a synchronous Cancel can restore the snapshot
        // before the add resumes — the re-check inside guards against it.
        // A Commit does NOT restore state, so it deliberately does not bump
        // the generation: an add resuming after Commit is applied normally.
        val rollbackGenAtStart = editRollbackGeneration

        scope.launchSafe("Error adding wallpaper layer") {
            val internalUri = wallpaperFileManager.copyToInternal(imageUri)
            if (internalUri == null) {
                TimberWrapper.silentError("Failed to copy layer image to internal storage")
                return@launchSafe
            }
            val internalUriString = internalUri.toString()

            // From here to applyState there is NO suspension: the rollback
            // re-check and the state write are atomic. If the edit session was
            // rolled back during the copy (the user hit Cancel mid-copy),
            // persisting now would revive a layer onto the already-restored
            // state and race the snapshot save. Discard the add and clean up its
            // orphaned file instead.
            if (editRollbackGeneration != rollbackGenAtStart) {
                // Rollback branch: discard the add and clean up its orphan file.
                // Suspending here is safe — this branch returns without touching
                // _wallpaperState, so it never enters the atomic section below.
                // deleteFile is blocking disk I/O → hop off the main dispatcher.
                withContext(ioDispatcher) {
                    wallpaperFileManager.deleteFile(internalUriString)
                }
                return@launchSafe
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
            val newLayer = WallpaperLayerState(
                imageUri = internalUriString,
                label = label ?: nextFreeAutoLabel(base)
            )

            val mutation = Mutation(
                newState = base.withAddedLayer(newLayer),
                // While in edit mode, track this file so its orphan copy on disk
                // gets cleaned up if the user cancels the session.
                trackForCancel = if (_isWallpaperEditMode.value) listOf(internalUriString) else emptyList(),
                // Signal the view to focus (activate) the new layer after the
                // imminent rebuild. Consumer calls consumePendingFocusLayerId().
                focusLayerId = newLayer.id,
            )
            applyState(mutation)
            persist(mutation)
        }
    }

    /**
     * Findet die kleinste freie Nummer N, so dass "Layer N" noch nicht im
     * [state] vorkommt. Verhindert Kollisionen nach Delete-then-Add-Zyklen.
     */
    private fun nextFreeAutoLabel(state: WallpaperState): String {
        val used = state.layers.mapNotNull { layer ->
            layer.label?.removePrefix("Layer ")?.toIntOrNull()
        }.toSet()
        var n = 1
        while (n in used) n++
        return "Layer $n"
    }

    fun onRemoveWallpaperLayer(layerIndex: Int) {
        val current = _wallpaperState.value
        val layerUri = current.getLayer(layerIndex)?.imageUri
        val inEdit = _isWallpaperEditMode.value

        // In edit mode: defer the physical delete until commit so that a cancel
        // can restore the snapshot including the file on disk. Outside edit mode:
        // delete immediately (in the persist epilogue).
        //
        // Unified persist path: WallpaperRepositoryImpl.saveWallpaperState handles
        // the "no wallpaper" case by wiping all keys, so removing the last layer
        // needs no separate clearWallpaperUseCase call (avoids a brief UI flicker).
        commit(
            errorMessage = "Error removing wallpaper layer",
            m = Mutation(
                newState = current.withRemovedLayer(layerIndex),
                deleteNow = if (!inEdit && layerUri != null) listOf(layerUri) else emptyList(),
                deferToCommit = if (inEdit && layerUri != null) listOf(layerUri) else emptyList(),
            )
        )
    }

    fun onSwapWallpaperLayers(indexA: Int, indexB: Int) =
        commit(
            "Error swapping wallpaper layers",
            Mutation(_wallpaperState.value.withSwappedLayers(indexA, indexB))
        )

    // ===========================================
    // MULTI-LAYER: TRANSFORMS
    // ===========================================

    /**
     * Applies [transform] to the layer at [layerIndex], mirrors the result
     * into [_wallpaperState] and persists it — the shared body of the
     * single-layer mutate-and-save operations.
     */
    private fun mutateLayerAndPersist(
        errorMessage: String,
        layerIndex: Int,
        transform: (WallpaperLayerState) -> WallpaperLayerState,
    ) = commit(
        errorMessage,
        Mutation(_wallpaperState.value.withUpdatedLayer(layerIndex, transform))
    )

    fun onSaveLayerTransform(
        layerIndex: Int,
        scale: Float,
        translateX: Float,
        translateY: Float,
        captureSampleSize: Int? = null
    ) = mutateLayerAndPersist("Error saving layer transform", layerIndex) {
        it.copy(
            scale = scale,
            translateX = translateX,
            translateY = translateY,
            captureSampleSize = captureSampleSize
        )
    }

    fun onSaveAllLayerTransforms(
        transforms: List<LayerTransform>
    ) {
        var state = _wallpaperState.value
        transforms.forEachIndexed { index, t ->
            state = state.withUpdatedLayer(index) {
                // Store the view scale RAW + tag captureSampleSize (Ansatz Y is
                // tag-only, spec §4-Y): no ÷S on save, the load side multiplies
                // the S_render/S_captured ratio.
                it.copy(
                    scale = t.scale,
                    translateX = t.translateX,
                    translateY = t.translateY,
                    captureSampleSize = t.sampleSize
                )
            }
        }
        commit("Error saving all layer transforms", Mutation(state))
    }

    // ===========================================
    // MULTI-LAYER: PROPERTIES
    // ===========================================
    //
    // INTENTIONALLY UNUSED IN PRODUCTION — the wallpaper editor stays
    // transform-only (add / remove / reorder / pan-zoom). Per-layer alpha,
    // blend mode and visibility get NO UI, by design: the layered-collage
    // workflow bakes its compositing into the source PNGs (darkroom/greenwall
    // alpha cutouts over darkroom/chiaroscuro AMOLED frames), which
    // ZoomableImageView.drawLayers already honours per-pixel — a uniform
    // layer-alpha / blend / visibility control is redundant. Visibility also
    // has no cheap wiring: the canvas hit-test skips invisible layers and there
    // is no layer list/cycler, so a hidden layer would be unreachable.
    // The three setters stay so the render/backup path keeps working (values
    // reachable only via imported backup JSON); do NOT wire a slider / picker /
    // toggle to them. Decision: ACCEPTED_LIMITATIONS.md §5 + AUDIT-20 F14.

    fun onSetLayerAlpha(layerIndex: Int, alpha: Float) =
        mutateLayerAndPersist("Error setting layer alpha", layerIndex) {
            it.copy(alpha = alpha.coerceIn(0f, 1f))
        }

    fun onSetLayerBlendMode(layerIndex: Int, blendModeName: String?) =
        mutateLayerAndPersist("Error setting layer blend mode", layerIndex) {
            it.copy(blendModeName = blendModeName)
        }

    fun onSetLayerVisibility(layerIndex: Int, isVisible: Boolean) =
        mutateLayerAndPersist("Error setting layer visibility", layerIndex) {
            it.copy(isVisible = isVisible)
        }

}
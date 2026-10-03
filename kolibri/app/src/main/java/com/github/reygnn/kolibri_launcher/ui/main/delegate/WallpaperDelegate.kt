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
 * **The Contract Now** (since 3a-3 the bookkeeping lives in [WallpaperEditSession];
 * this delegate executes its effects):
 *
 * 1. **Enter** — onEnterWallpaperEditMode:
 *    - Takes a snapshot of the current [WallpaperState] in the session snapshot.
 *    - Clears both pending-removal sets.
 *    - Sets [isWallpaperEditMode] to true.
 *    - Fragment uses this signal to switch into edit UX (snap handles, toolbar, etc.).
 *
 * 2. **Mutations during session** — onAddWallpaperLayer, onRemoveWallpaperLayer,
 *    [onSaveLayerTransform], [onSwapWallpaperLayers], etc:
 *    - All update the displayed state AND persist via [saveWallpaperStateUseCase] normally.
 *    - **BUT file deletions are deferred**: [onRemoveWallpaperLayer] in edit mode adds
 *      the URI to the commit marks instead of deleting the file. This
 *      preserves the disk copy so Cancel can restore it.
 *    - **AND file additions are tracked**: [onAddWallpaperLayer] in edit mode adds
 *      the new internal URI to the cancel marks, so these orphan files get
 *      cleaned up if the user backs out.
 *
 * 3. **Commit** — onCommitWallpaperEditMode:
 *    - Processes the commit marks: actually deletes those files now.
 *    - Discards the cancel marks: added layers survive.
 *    - Clears the session snapshot and sets [isWallpaperEditMode] to false.
 *
 * 4. **Cancel** — onCancelWallpaperEditMode:
 *    - **Synchronously** restores the displayed state to the snapshot. This is critical
 *      — see below.
 *    - Asynchronously persists the restored snapshot and processes
 *      the cancel marks (cleaning up files from added-then-cancelled layers).
 *    - Discards the commit marks: deleted layers' files survive because they're
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
 * - Never delete a file inline while a session is open — always route
 *   through the commit marks so Cancel can restore.
 * - Never skip the pending-removal bookkeeping on add — dangling files will
 *   eventually be caught by `gcOrphans`, but relying on that instead of explicit
 *   tracking is fragile and makes the cancel path slower.
 * - Never make Cancel's state-restore asynchronous. The fragment's immediate read
 *   depends on synchronicity.
 * - A layer add must survive a Commit but not a Cancel. [onAddWallpaperLayer] copies
 *   the picked file on a suspending IO hop that releases the main dispatcher, so a
 *   synchronous Cancel can restore the snapshot mid-copy. The add captures
 *   the rollback generation before the copy and re-checks it after; if a rollback
 *   happened, the add discards itself (deletes its orphan file, persists nothing)
 *   instead of reviving a layer onto the restored state. Commit does NOT bump the
 *   generation — it keeps state, so a resuming add is applied normally (appended to
 *   the committed state). Never bypass this re-check, and never bump the generation on
 *   Commit — synchronous restore only protects the read path, not a resuming add.
 * - Never suspend between reading and writing the displayed state. Every mutation
 *   is a synchronous [WallpaperEditSession] transition (since 3a-3; it holds the
 *   snapshot, the marks and the generation); suspending
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
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperRepositoryImpl

import com.github.reygnn.kolibri_launcher.domain.model.SettingsDefaults

import android.content.Context
import android.net.Uri
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.BuildConfig
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.feature.wallpaper.WallpaperEditSession
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
import com.github.reygnn.launcher.common.ui.LaunchTrace
import com.github.reygnn.launcher.core.wallpaper.LayerTransform
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperFlattener
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperCompositeCache
import com.github.reygnn.launcher.core.wallpaper.WallpaperCompositeKey
import com.github.reygnn.launcher.common.ui.wallpaper.DecodedWallpaperBitmap
import android.graphics.Bitmap
import android.widget.Toast
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperBitmapLuminanceImpl
import com.github.reygnn.launcher.core.CompositeLuminanceSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.firstOrNull
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
    private val observeWallpaperBackdropUseCase: ObserveWallpaperBackdropUseCase,
    private val setWallpaperBackdropUseCase: SetWallpaperBackdropUseCase,
    /** Every file decision: copy in, delete what no layer needs, orphan GC (3a-2). */
    private val imageStore: WallpaperImageStore,
    private val wallpaperFlattener: WallpaperFlattener,
    private val compositeCache: WallpaperCompositeCache,
    private val bitmapLuminance: WallpaperBitmapLuminanceImpl,
    private val compositeLuminanceSignal: CompositeLuminanceSignal,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: DelegateScope
) {

    // --- Exposed State ---

    /**
     * The edit session and the displayed state (3a-3): pure and synchronous, main-thread confined
     * like this delegate. It decides, this delegate executes (persist through the use cases, files
     * through [imageStore], composite, signals).
     */
    private val session = WallpaperEditSession()

    val wallpaperState: StateFlow<WallpaperState> = session.state

    val isWallpaperEditMode: StateFlow<Boolean> = session.isEditMode

    /**
     * Serializes every wallpaper write of this delegate (3a-3): saves land in the order they were
     * issued, and the re-sync after a session end reads only after all of them.
     */
    private val persistLock = Mutex()

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
            // marked by the session are only referenced by its in-memory snapshot, NOT by the
            // persisted state — a GC here would
            // destroy them. The GC will run on the first post-edit-mode emission instead.
            //
            // **Regression Guards:**
            // - `start runs gcOrphans exactly once across multiple state emissions`
            // - `start does not run gcOrphans while an edit session is active`
            // Both in [WallpaperDelegateTest].
            // =================================================================================
            var gcHasRun = false
            observeWallpaperStateUseCase().collect { state ->
                // Ignored while a session is open (3a-3, E4); the session end re-syncs.
                session.onPersistedState(state)

                if (!gcHasRun) {
                    val sessionOpen = session.isEditMode.value
                    // Once per process, as before — also when the GC fails; while a session is
                    // open the store refuses (edit guard) and the next emission tries again.
                    if (!sessionOpen) gcHasRun = true
                    try {
                        // The store hops its disk I/O off the main dispatcher (this collect runs on it).
                        imageStore.collectOrphans(editSessionOpen = sessionOpen)
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
    // Since 3a-3 every wallpaper-state change is a synchronous transition of [session]
    // ([WallpaperEditSession]): the read-modify-write of the displayed state and the session
    // bookkeeping happen in one non-suspending step, so no suspension point can split it (the
    // AUDIT-6 lost-update race stays unrepresentable). The transition returns its effect as
    // data; suspending work — the file copy BEFORE (re-validated through the rollback
    // generation), the persist and the deletion AFTER — runs here.

    /**
     * Persist epilogue for a session change ([WallpaperEditSession.Effect]): save, then let the
     * store delete what no persisted layer references (copy → save → delete, 3a-2c). Under
     * [persistLock], so saves land in the order they were issued.
     */
    private suspend fun persist(effect: WallpaperEditSession.Effect) {
        persistLock.withLock {
            saveWallpaperStateUseCase(effect.persist)
            imageStore.deleteUnreferenced(effect.deleteNow)
        }
    }

    /** A synchronous session change (already applied) plus its scheduled persist. */
    private fun persistLater(errorMessage: String, effect: WallpaperEditSession.Effect?) {
        if (effect == null) return
        scope.launchSafe(errorMessage) { persist(effect) }
    }

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
        val internalUri = imageStore.copyIn(imageUri)
        if (internalUri == null) {
            TimberWrapper.silentError("Failed to copy wallpaper to internal storage")
            scope.sendEvent(UiEvent.ShowToast(R.string.error_generic))
            return@launchSafe
        }
        val newUri = internalUri.toString()
        // Replace is a session change (3a-3, E4): applied to the displayed state right away, also
        // inside a session — the edit no longer waits for the repository emission. Outside a
        // session the old files go once the new state is saved; inside, at commit (cancel restores
        // them), and the new copy goes again on cancel. A cancel during the copy discards the
        // replace; the copy is then an orphan for the GC.
        val effect = session.replace(newUri, rollbackGenAtStart) ?: return@launchSafe
        persistLock.withLock {
            setWallpaperImageUseCase(newUri)
            imageStore.deleteUnreferenced(effect.deleteNow)
        }
        // A new/replaced image → offer a scrim reset (deferred to commit if in a session).
        signalImageChanged()
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
        // A single-image wallpaper is the one-element layer list, so the SaveSingle path (view
        // single-mode) writes the transform back into layer 0. The session applies it
        // SYNCHRONOUSLY: persisting only to DataStore (the old path) left the displayed state
        // holding the OLD transform until the write round-tripped, and the commit-triggered
        // re-render briefly regressed the display.
        persistLater(
            "Error saving wallpaper transform",
            session.saveSingleTransform(scale, translateX, translateY, captureSampleSize),
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
        val removed = compositeRegenLock.withLock {
            // State first, files second (3a-2d): the store deletes the files only once the
            // persisted state references nothing — a silently failed clear keeps them, and the
            // wallpaper with them, instead of leaving a dangling reference on disk.
            clearWallpaperUseCase()
            if (!imageStore.deleteAllIfNothingPersisted()) return@withLock false
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
            session.onPersistedState(WallpaperState.NONE)
            true
        }
        if (!removed) {
            // The removal did not take effect; the wallpaper stays as it is on disk.
            scope.sendEvent(UiEvent.ShowToast(R.string.error_generic))
            return@launchSafe
        }
        scope.sendEvent(UiEvent.ShowToast(R.string.wallpaper_removed))
        // The image content is gone → offer a scrim reset (so a leftover dim doesn't
        // darken the now-revealed system wallpaper). Clear runs outside a session, so
        // this surfaces immediately.
        signalImageChanged()
    }

    /**
     * The display configuration changed (rotate / fold / multi-window resize), so the composite
     * key's width/height changed and any cached composite is now wrong-resolution (v4 §3a/R2). A
     * config change emits no new [WallpaperState], so this is the refill trigger for that case:
     * re-evaluate the current state at the new metrics and refill on a miss. (Single-layer is
     * resolution-independent — its `file://` key is unchanged — so this is a no-op for it.)
     */
    fun onDisplayConfigChanged() = refillCache(session.state.value)

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
        val end = session.commit()

        // An image mutation during the session (deferred by [signalImageChanged] so
        // the offer doesn't pop mid-edit, where the scrim is hidden anyway) surfaces
        // now that the user is back on the settled home screen.
        if (end.imageChanged) _wallpaperImageChanged.tryEmit(Unit)

        // No representation collapse needed anymore (was AUDIT-20 F13): a wallpaper
        // edited down to one layer already IS the canonical single-image form, so
        // the render path takes the cheap decode-cache path (file://) off its
        // layerCount == 1 without any state rewrite. The old toSingleLayer() collapse
        // existed only because a lone image had a separate flat representation.

        if (end.deleteCandidates.isNotEmpty()) {
            scope.launchSafe("Error committing wallpaper edit") {
                // After the session's saves; the store deletes only files no persisted layer
                // still references (3a-2c).
                persistLock.withLock { imageStore.deleteUnreferenced(end.deleteCandidates) }
            }
        }

        // Exit edit mode + warm the display cache for the committed state through the
        // single funnel (AUDIT-20 F11).
        leaveEditMode(end.finalState)
    }

    /**
     * Refills the in-memory display cache for [state] (WALLPAPER_COMPOSITE_LIFECYCLE_SPEC v4
     * §3/§3a): an existing wallpaper whose cached bitmap is missing — cold start, edit-commit,
     * backup restore, or a rotate/fold (new resolution) — gets one produced in the BACKGROUND so
     * the next drawer->home is a ~0 ms cache hit.
     *
     * Only MULTI-layer wallpapers are warmed (§25 P4): the composite flatten is the per-frame-GPU
     * win the device spike locked, and it must survive as a single texture. A single-layer
     * wallpaper is NOT cached — since §25 P3 the render surface is Activity-hosted (never torn
     * down), so the drawer->home re-decode the single-layer cache once avoided no longer happens; a
     * lone image decodes live via the render's bounded loader.
     *  - no wallpaper / single-layer -> nothing to warm
     *  - multi-layer                 -> flatten N layers -> HARDWARE composite, cached under `composite://`.
     *
     * Deliberately NOT on the launch hot path. Single-flighted ([refillInProgress]) and skipped
     * during edit mode (the layers are mid-change; the commit path refills). Gated on a cache MISS
     * for the current key, so an already-warm state is a no-op. On completion it self-reschedules
     * (spec S5) if the current state moved to a DIFFERENT miss during the fill — but never re-fires
     * the SAME key, so a persistently failing fill cannot loop.
     */
    private fun refillCache(state: WallpaperState) {
        if (refillInProgress) return
        if (session.isEditMode.value) return
        val key = cacheKeyOrNull(state)
        if (key == null) {
            // Single-layer / no wallpaper: nothing is cached under a null key (§25 P4), so a
            // resident composite here is necessarily a STALE entry from a prior multi-layer state
            // — e.g. an edit deleted a layer down to one and committed. Drop it and its luminance,
            // guarded on the CURRENT state so a newer multi-layer state (whose own refill will
            // re-warm and re-emit) is never clobbered. Without this the multi->single transition
            // early-returned before the cleanup below, stranding the ~10 MB HARDWARE bitmap until
            // clear/next-warm/process-death and leaving the AUTO classifier on the removed
            // composite's luminance (the composite signal is only ever read for multi-layer, so
            // the stale value is inert while single-layer but wrong on a later single->multi
            // re-entry until the new warm emits).
            if (cacheKeyOrNull(session.state.value) == null) {
                compositeCache.invalidate()
                compositeLuminanceSignal.emit(null)
            }
            return
        }
        // F12 (structural): drop any entry cached under a now-dead key BEFORE deciding to
        // warm, so "entry for a dead resolution" is never even a state — independent of
        // whether the warm below succeeds. A rotate/fold (or a content change) versions the
        // key; the previous entry is a guaranteed miss for `key`, and a warm that then fails
        // would otherwise leave the old ~10 MB bitmap resident. No-op on a hit (same key) or
        // an empty cache, so the live current-key entry is never touched.
        compositeCache.invalidateIfNotKey(key)
        if (compositeCache.get(key) != null) {
            // Already warm (the composite is in memory) — nothing to do.
            return
        }
        refillInProgress = true
        scope.launchSafe("Error refilling wallpaper cache") {
            try {
                // Only multi-layer reaches here — cacheKeyOrNull returns null for single-layer.
                warmComposite(state, key)
            } finally {
                refillInProgress = false
                // Self-reschedule (S5): only if the current state is a DIFFERENT miss — never the
                // same key, so a failed/incomplete fill does not loop.
                val current = session.state.value
                if (!session.isEditMode.value) {
                    val currentKey = cacheKeyOrNull(current)
                    if (currentKey != null && currentKey != key && compositeCache.get(currentKey) == null) {
                        refillCache(current)
                    }
                }
            }
        }
    }

    /**
     * The display-cache key for [state], or null if there is nothing to warm. Only MULTI-layer
     * wallpapers get a key (the resolution-keyed `composite://` flatten). A single-layer wallpaper
     * returns null and is never cached (§25 P4): the Activity-hosted render surface (§25 P3) is not
     * torn down on drawer->home, so the re-decode the single-layer cache once avoided cannot happen;
     * a lone image decodes live via the render's bounded loader.
     */
    private fun cacheKeyOrNull(state: WallpaperState): String? =
        if (state.hasWallpaper && state.layerCount >= 2) compositeKey(state) else null

    /**
     * The composite cache key for [state] at the CURRENT display metrics (spec §3a). This warm-write
     * side reads its `@ApplicationContext` `context.resources.displayMetrics`; MainActivity's
     * render-read side ([MainActivity.compositeCacheKeyIfHit]) reads the Activity's resources. The
     * two coincide — so write key == read key and the hit lands — for a fullscreen launcher on the
     * primary display (which a HOME activity always is); they would diverge only in
     * multi-window/freeform or on a secondary display (WAH-INV-5).
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
        val current = session.state.value
        if (current.layerCount >= 2 && compositeKey(current) == key) {
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
            // Cache-diagnostic toast (F10), gated by BuildConfig.SHOW_CACHE_TOASTS —
            // debug + personal/daily-driver builds only, compiled out of a public
            // release. Visual signal on each composite cache (re)fill, to gauge how
            // often a re-flatten is actually needed (cold start / edit-commit / rotate);
            // the resolution in the text distinguishes a rotate-triggered refill. Only a
            // genuine composite (layerCount >= 2) reaches this path; a lone image is not
            // cached at all (§25 P4) and decodes live via the render's bounded loader.
            if (BuildConfig.SHOW_CACHE_TOASTS) {
                scope.sendEvent(
                    UiEvent.ShowToastFromString(
                        "Composite cache filled (${metrics.widthPixels}x${metrics.heightPixels})",
                        Toast.LENGTH_SHORT,
                    )
                )
            }
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
        val current = session.state.value
        if (current.layerCount >= 2 && compositeKey(current) == key) {
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
        // The session restores the snapshot synchronously and bumps the generation, so an add or
        // replace still copying discards itself.
        val end = session.cancel()

        // Persist the restored snapshot / delete session-added files only when there is
        // something to do; the exit + cache warm below runs unconditionally (AUDIT-20 F11).
        if (end.persist != null || end.deleteCandidates.isNotEmpty()) {
            scope.launchSafe("Error canceling wallpaper edit") {
                persistLock.withLock {
                    end.persist?.let { saveWallpaperStateUseCase(it) }
                    // After the restored snapshot is persisted: the session-added files no
                    // persisted layer references (3a-2c).
                    imageStore.deleteUnreferenced(end.deleteCandidates)
                }
            }
        }

        // Exit edit mode + warm the display cache for the restored state through the single
        // funnel (AUDIT-20 F11). A no-op cancel (unchanged snapshot) produces no DataStore
        // emission, so this is the only trigger that re-warms after a config change
        // (rotate/fold) that was deferred while editing.
        leaveEditMode(end.finalState)
    }

    /**
     * Single exit point for edit mode (AUDIT-20 F11). Clears the edit flag and refills the
     * display cache for [finalState] — the state the launcher returns to. Hanging the warm
     * here rather than on each exit means no exit path (commit, cancel, or a future third
     * one) can leave the composite cold: [refillCache] is deferred during edit mode (a
     * rotate/fold config change returns early), and a no-op cancel restores an unchanged
     * state that produces no DataStore emission to re-trigger the warm. Idempotent — a cache
     * hit is a no-op and the refill is single-flighted — so the redundant warm on a normal
     * commit/cancel (which also emits) costs nothing.
     */
    private fun leaveEditMode(finalState: WallpaperState) {
        refillCache(finalState)
        resyncWithPersisted()
    }

    /**
     * After a session end (3a-3, E4): the session ignored repository emissions while it was open
     * (and keeps ignoring them until this re-sync), so the latest PERSISTED state is applied now —
     * not just the next emission, which may never come. Runs after all writes issued so far
     * ([persistLock]), so it never reads a state older than the session's own saves (a removed
     * layer must not come back). If the persisted state
     * differs — a save swallowed in a release build — the display shows the truth.
     */
    private fun resyncWithPersisted() = scope.launchSafe("Error re-syncing the wallpaper after an edit") {
        var persisted: WallpaperState? = null
        try {
            persistLock.withLock { persisted = observeWallpaperStateUseCase().firstOrNull() }
        } finally {
            // Always end the re-sync, also when the read fails: until then the session ignores
            // emissions, and a pending re-sync must never freeze the display.
            val latest = persisted
            if (session.resync(latest) && latest != null) refillCache(latest)
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
        if (session.isEditMode.value) onCommitWallpaperEditMode() else onEnterWallpaperEditMode()
    }

    // ===========================================
    // MULTI-LAYER: MANAGEMENT
    // ===========================================

    fun onAddWallpaperLayer(imageUri: Uri) {
        // Capture the rollback generation synchronously, at invocation time. The copy below
        // releases the main dispatcher, so a synchronous Cancel can restore the snapshot before
        // the add resumes — the session compares the generation and discards the add then. A
        // Commit does NOT bump it (3a-3, E2): an add resuming after Commit is applied normally.
        val rollbackGenAtStart = session.rollbackGeneration

        scope.launchSafe("Error adding wallpaper layer") {
            val internalUri = imageStore.copyIn(imageUri)
            if (internalUri == null) {
                TimberWrapper.silentError("Failed to copy layer image to internal storage")
                return@launchSafe
            }
            val internalUriString = internalUri.toString()

            // The session's add is synchronous: re-check and state write are one step. A
            // single-image wallpaper is already the one-element layer list, so adding appends;
            // the new layer is tracked for cancel inside a session and gets the focus hint.
            val effect = session.addLayer(internalUriString, rollbackGenAtStart)
            if (effect == null) {
                // Rolled back during the copy: nothing persisted references the new copy, so the
                // store removes it (3a-2c).
                imageStore.deleteUnreferenced(listOf(internalUriString))
                return@launchSafe
            }
            persist(effect)
            // Added a layer → image content changed (deferred to commit in a session).
            signalImageChanged()
        }
    }

    fun onRemoveWallpaperLayer(layerIndex: Int) {
        // In edit mode the physical delete waits for commit, so a cancel can restore the snapshot
        // including the file; outside edit mode it happens after the save. Removing the last layer
        // needs no separate clear: saving a state without wallpaper wipes all keys.
        persistLater("Error removing wallpaper layer", session.removeLayer(layerIndex))
    }

    fun onSwapWallpaperLayers(indexA: Int, indexB: Int) =
        persistLater("Error swapping wallpaper layers", session.swapLayers(indexA, indexB))

    // ===========================================
    // MULTI-LAYER: TRANSFORMS
    // ===========================================

    fun onSaveLayerTransform(
        layerIndex: Int,
        scale: Float,
        translateX: Float,
        translateY: Float,
        captureSampleSize: Int? = null
    ) = persistLater(
        "Error saving layer transform",
        session.saveLayerTransform(layerIndex, scale, translateX, translateY, captureSampleSize),
    )

    fun onSaveAllLayerTransforms(
        transforms: List<LayerTransform>
    ) {
        // The view scale is stored RAW and captureSampleSize tagged (Ansatz Y is tag-only, spec
        // §4-Y): no ÷S on save, the load side multiplies the S_render/S_captured ratio.
        persistLater("Error saving all layer transforms", session.saveAllLayerTransforms(transforms))
    }

}
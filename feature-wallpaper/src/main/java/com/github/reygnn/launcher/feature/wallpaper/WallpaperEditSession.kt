package com.github.reygnn.launcher.feature.wallpaper

import com.github.reygnn.launcher.core.wallpaper.LayerTransform
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The wallpaper edit session (SPEC_NYX_REWRITE 3a-3): the displayed state, the session with its
 * snapshot, the rollback generation and the file marks — PURE and SYNCHRONOUS. Every change is a
 * transition computed from the current state and applied in one step ([apply]); it returns what
 * the caller has to do as data ([Effect], [SessionEnd]): persist a state, let the
 * [WallpaperImageStore] delete candidates. The class decides, the callers execute (Kolibri's
 * `WallpaperDelegate`; Nyx's coordinator in 3b). No coroutine, no I/O: testable without either.
 *
 * **Main-thread confined.** Not thread-safe and needs not be: call it from one thread only. A
 * caller that suspends (file copy, save) reads [rollbackGeneration] before and hands it back
 * after, on the same thread.
 *
 * **Single writer during a session.** While a session is open, nobody else writes the wallpaper
 * state — backup import and reset run from the settings, never with the editor open. So
 * [onPersistedState] ignores emissions during a session: a stale one (an older save landing late)
 * must not revive a layer the session just removed. After every session end the caller re-syncs
 * ([resync]) with the latest persisted state, read after all of the session's writes; until then
 * emissions stay ignored too, so a late one (delivered after commit) cannot flash the old state.
 * A future second writer during a session breaks this assumption and must be noticed here.
 * Known today (3b-5, Q5): the backup import writes the wallpaper state past this mechanism; that it
 * never runs during a session is excluded only by the UI (from the editor one cannot reach the
 * settings), not by the code — see SPEC_NYX_REWRITE 3b-5 Q5 before making the import reachable.
 *
 * Decisions (02.10.): re-entering a running session is ignored (E3) — overwriting the snapshot
 * would make a cancel restore the already-edited state; commit does NOT bump the generation (E2) —
 * a layer whose copy is still running when the user commits is still applied (nothing the user
 * did disappears silently), while a cancel discards it.
 */
class WallpaperEditSession {

    /** What a change asks the caller to do: persist [persist], then let the store check [deleteNow]. */
    data class Effect(val persist: WallpaperState, val deleteNow: List<String> = emptyList())

    /**
     * The end of a session. [persist] is the restored snapshot to save on cancel (null on commit,
     * whose state is already persisted); [deleteCandidates] go to the store afterwards;
     * [imageChanged] says whether the image content changed during the session (Kolibri offers a
     * scrim reset then); [finalState] is what the launcher returns to.
     */
    data class SessionEnd(
        val persist: WallpaperState?,
        val deleteCandidates: Set<String>,
        val imageChanged: Boolean,
        val finalState: WallpaperState,
    )

    private val _state = MutableStateFlow(WallpaperState.NONE)

    /** The displayed state: the persisted one, or the session's live edit. */
    val state: StateFlow<WallpaperState> = _state.asStateFlow()

    private val _isEditMode = MutableStateFlow(false)
    val isEditMode: StateFlow<Boolean> = _isEditMode.asStateFlow()

    private val _pendingFocusLayerId = MutableStateFlow<String?>(null)

    /** Layer to activate after the next view rebuild (a just-added layer). */
    val pendingFocusLayerId: StateFlow<String?> = _pendingFocusLayerId.asStateFlow()

    fun consumePendingFocusLayerId() {
        _pendingFocusLayerId.value = null
    }

    private var snapshot: WallpaperState? = null
    private var awaitingResync = false
    private var imageChangedInSession = false
    private val pendingRemovalsOnCommit = mutableSetOf<String>()
    private val pendingRemovalsOnCancel = mutableSetOf<String>()

    /**
     * Bumped by every cancel and by a "remove wallpaper" that took effect; a suspended add or
     * replace compares it and discards itself.
     */
    var rollbackGeneration: Long = 0L
        private set

    // ---- outside writes ----

    /**
     * A persisted state arrived; applied unless a session is open or its re-sync is still pending.
     * Returns whether it was applied.
     */
    fun onPersistedState(persisted: WallpaperState): Boolean {
        if (_isEditMode.value || awaitingResync) return false
        _state.value = persisted
        return true
    }

    /**
     * Ends the re-sync after a session: [persisted] is the latest persisted state, read after all of
     * the session's writes (null when it could not be read — the display stays as it is). Applied
     * unless a new session has opened meanwhile. Returns whether the displayed state changed. The
     * caller must call this after every [commit] and [cancel], also when the read fails.
     */
    fun resync(persisted: WallpaperState?): Boolean {
        awaitingResync = false
        if (persisted == null || _isEditMode.value || persisted == _state.value) return false
        _state.value = persisted
        return true
    }

    // ---- the session ----

    /** Opens a session; false (and nothing changes) if one is already open (E3). */
    fun enter(): Boolean {
        if (_isEditMode.value) return false
        snapshot = _state.value
        pendingRemovalsOnCommit.clear()
        pendingRemovalsOnCancel.clear()
        imageChangedInSession = false
        _isEditMode.value = true
        return true
    }

    /**
     * "Remove wallpaper" took effect: an add or replace still copying must discard itself, because
     * the removal deleted every image file — its copy may be among them (audit A2). Bumps the
     * generation like a cancel; the displayed state is the caller's business.
     */
    fun invalidatePendingChanges() {
        rollbackGeneration++
    }

    /** Keeps the edit: the files removed during the session go to the store. No generation bump (E2). */
    fun commit(): SessionEnd {
        val end = SessionEnd(
            persist = null,
            deleteCandidates = pendingRemovalsOnCommit.toSet(),
            imageChanged = imageChangedInSession,
            finalState = _state.value,
        )
        closeSession()
        return end
    }

    /** Restores the snapshot synchronously; the files added during the session go to the store. */
    fun cancel(): SessionEnd {
        rollbackGeneration++
        val restored = snapshot
        if (restored != null) _state.value = restored
        // A focus hint from an add in this session points to a layer the restore removed.
        _pendingFocusLayerId.value = null
        val end = SessionEnd(
            persist = restored,
            deleteCandidates = pendingRemovalsOnCancel.toSet(),
            imageChanged = false, // rolled back: no image change
            finalState = _state.value,
        )
        closeSession()
        return end
    }

    private fun closeSession() {
        awaitingResync = true
        pendingRemovalsOnCommit.clear()
        pendingRemovalsOnCancel.clear()
        snapshot = null
        imageChangedInSession = false
        _isEditMode.value = false
    }

    /**
     * The image content changed (add, replace, remove wallpaper). Returns true when the caller
     * should report it now; inside a session it is deferred to [commit] ([SessionEnd.imageChanged]).
     */
    fun noteImageChanged(): Boolean {
        if (_isEditMode.value) {
            imageChangedInSession = true
            return false
        }
        return true
    }

    // ---- changes ----

    /** "Choose wallpaper" with the copied [newUri]; null when a cancel happened since [rollbackGenAtStart]. */
    fun replace(newUri: String, rollbackGenAtStart: Long): Effect? {
        if (rollbackGeneration != rollbackGenAtStart) return null
        val replaced = _state.value.referencedUris.toList()
        val inEdit = _isEditMode.value
        return apply(
            newState = WallpaperState.single(uri = newUri),
            // Outside a session the old files go once the new state is saved; inside, at commit
            // (cancel restores them), and the new copy goes again on cancel.
            deleteNow = if (inEdit) emptyList() else replaced,
            deferToCommit = if (inEdit) replaced else emptyList(),
            trackForCancel = if (inEdit) listOf(newUri) else emptyList(),
        )
    }

    /** Appends a layer for the copied [internalUri]; null when a cancel happened since [rollbackGenAtStart]. */
    fun addLayer(internalUri: String, rollbackGenAtStart: Long): Effect? {
        if (rollbackGeneration != rollbackGenAtStart) return null
        val newLayer = WallpaperLayerState(imageUri = internalUri)
        return apply(
            newState = _state.value.withAddedLayer(newLayer),
            trackForCancel = if (_isEditMode.value) listOf(internalUri) else emptyList(),
            focusLayerId = newLayer.id,
        )
    }

    fun removeLayer(layerIndex: Int): Effect {
        val current = _state.value
        val layerUri = current.getLayer(layerIndex)?.imageUri
        val inEdit = _isEditMode.value
        return apply(
            newState = current.withRemovedLayer(layerIndex),
            deleteNow = if (!inEdit && layerUri != null) listOf(layerUri) else emptyList(),
            deferToCommit = if (inEdit && layerUri != null) listOf(layerUri) else emptyList(),
        )
    }

    fun swapLayers(indexA: Int, indexB: Int): Effect = apply(_state.value.withSwappedLayers(indexA, indexB))

    /** The single-image transform writes into layer 0; null when there is no wallpaper. */
    fun saveSingleTransform(scale: Float, translateX: Float, translateY: Float, captureSampleSize: Int?): Effect? {
        val current = _state.value
        if (!current.hasWallpaper) return null
        return apply(
            current.withUpdatedLayer(0) {
                it.copy(scale = scale, translateX = translateX, translateY = translateY, captureSampleSize = captureSampleSize)
            },
        )
    }

    fun saveLayerTransform(layerIndex: Int, scale: Float, translateX: Float, translateY: Float, captureSampleSize: Int?): Effect =
        apply(
            _state.value.withUpdatedLayer(layerIndex) {
                it.copy(scale = scale, translateX = translateX, translateY = translateY, captureSampleSize = captureSampleSize)
            },
        )

    /** Stores the view scale RAW and tags captureSampleSize (no division on save). */
    fun saveAllLayerTransforms(transforms: List<LayerTransform>): Effect {
        var state = _state.value
        transforms.forEachIndexed { index, t ->
            state = state.withUpdatedLayer(index) {
                it.copy(scale = t.scale, translateX = t.translateX, translateY = t.translateY, captureSampleSize = t.sampleSize)
            }
        }
        return apply(state)
    }

    /**
     * THE synchronous critical section: marks and focus first, the state LAST, so observers of the
     * state already see them. Non-suspending by construction.
     */
    private fun apply(
        newState: WallpaperState,
        deleteNow: List<String> = emptyList(),
        deferToCommit: List<String> = emptyList(),
        trackForCancel: List<String> = emptyList(),
        focusLayerId: String? = null,
    ): Effect {
        focusLayerId?.let { _pendingFocusLayerId.value = it }
        pendingRemovalsOnCommit.addAll(deferToCommit)
        pendingRemovalsOnCancel.addAll(trackForCancel)
        _state.value = newState
        return Effect(persist = newState, deleteNow = deleteNow)
    }
}

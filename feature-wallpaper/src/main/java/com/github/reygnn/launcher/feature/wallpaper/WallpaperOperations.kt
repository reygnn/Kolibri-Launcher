package com.github.reygnn.launcher.feature.wallpaper

import android.net.Uri
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The narrow persistence interface for [WallpaperOperations] (3a-9, K1): Kolibri maps it onto its use
 * cases, Nyx (3b) onto its repository.
 */
interface WallpaperPersistence {
    suspend fun save(state: WallpaperState)
    suspend fun clear()
    fun observe(): Flow<WallpaperState>
}

/**
 * The executing half of the wallpaper edit (SPEC_NYX_REWRITE 3a-9): the rules both apps need
 * around the pure [WallpaperEditSession] — persist and delete in order, the re-sync after a
 * session, replace and add with the rollback generation, "remove wallpaper", and the start-up
 * wiring (persisted state → session, orphan GC once per process, composite refill). The caller
 * (Kolibri's `WallpaperDelegate`, Nyx's coordinator in 3b) keeps only intents and the mapping of
 * results to its UI. Moved unchanged from Kolibri's delegate.
 *
 * One instance per host, never a singleton: it belongs to exactly one [session].
 *
 * **Main-thread confined**, like the session it drives: every public member is called on Main,
 * and every suspending member returns to Main after its I/O (the host's [launch] runs there), so
 * the session is only touched on Main.
 *
 * **Lock order:** the composite's lock ([WallpaperComposite.exclusive]) BEFORE [persistLock],
 * never the reverse — a reversed order on any path would deadlock. kotlinx `Mutex` is not
 * reentrant: nothing inside [persistLock] may take it again (the store and the persistence port
 * never do).
 */
class WallpaperOperations(
    val session: WallpaperEditSession,
    private val persistence: WallpaperPersistence,
    private val imageStore: WallpaperImageStore,
    private val composite: WallpaperComposite,
    private val compositeHost: WallpaperComposite.Host,
    /** Runs [block] in the host's scope with its error handling, logging [errorMessage] on failure. */
    private val launch: (errorMessage: String, block: suspend () -> Unit) -> Unit,
) {

    /** Outcome of [replace] and [addLayer], for the host to map onto its UI. */
    sealed interface ImageResult {
        /** The picked image could not be copied; nothing changed (logged here). */
        data object CopyFailed : ImageResult

        /** A cancel landed during the copy; the change was dropped. */
        data object Discarded : ImageResult

        /** Applied and persisted; the image content changed. */
        data object Applied : ImageResult
    }

    /**
     * Serializes every wallpaper write: saves land in the order they were issued, the re-sync
     * after a session end reads only after all of them, and "remove wallpaper" waits for every
     * earlier save. See the lock order in the class KDoc.
     */
    private val persistLock = Mutex()

    // ---- start-up ----

    /** Persisted state → session (ignored during a session), orphan GC once per process, refill. */
    fun start() = launch("Error observing wallpaper state") {
        var gcHasRun = false
        persistence.observe().collect { state ->
            // Ignored while a session is open (3a-3, E4); the session end re-syncs.
            session.onPersistedState(state)
            if (!gcHasRun) {
                val sessionOpen = session.isEditMode.value
                // Once per process — also when the GC fails; while a session is open the store
                // refuses (edit guard) and the next emission tries again.
                if (!sessionOpen) gcHasRun = true
                try {
                    imageStore.collectOrphans(editSessionOpen = sessionOpen)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Catch kept (Expected error, four-category frame): a failed GC leaves
                    // orphans for the next start; OOM extends Error → Throwable.
                    TimberWrapper.silentError(e, "Wallpaper orphan GC failed")
                }
            }
            refill(state)
        }
    }

    /** Refills the display composite for [state] (no-op while editing / on a cache hit). */
    fun refill(state: WallpaperState) = composite.refill(state, compositeHost)

    /** The display configuration changed (rotate, fold): refill for the current state. */
    fun refillCurrent() = refill(session.state.value)

    // ---- persist ----

    /** Save, then let the store delete what no persisted layer references (copy → save → delete). */
    suspend fun persist(effect: WallpaperEditSession.Effect) {
        persistLock.withLock {
            persistence.save(effect.persist)
            imageStore.deleteUnreferenced(effect.deleteNow)
        }
    }

    /** A synchronous session change (already applied) plus its scheduled persist. */
    fun persistLater(errorMessage: String, effect: WallpaperEditSession.Effect?) {
        if (effect == null) return
        launch(errorMessage) { persist(effect) }
    }

    // ---- replace / add ----

    /**
     * "Choose wallpaper": copy in, apply as a session change (also inside a session), persist,
     * delete what is no longer referenced. [rollbackGenAtStart] is read at invocation time; a
     * cancel during the copy discards the change — the copy is then an orphan for the GC.
     * Saved through the port as the single-image state (3a-9, K2: what the old use case did).
     */
    suspend fun replace(imageUri: Uri, rollbackGenAtStart: Long): ImageResult {
        val internalUri = imageStore.copyIn(imageUri)
        if (internalUri == null) {
            TimberWrapper.silentError("Failed to copy wallpaper to internal storage")
            return ImageResult.CopyFailed
        }
        val newUri = internalUri.toString()
        val effect = session.replace(newUri, rollbackGenAtStart) ?: return ImageResult.Discarded
        persistLock.withLock {
            persistence.save(effect.persist)
            imageStore.deleteUnreferenced(effect.deleteNow)
        }
        return ImageResult.Applied
    }

    /**
     * Adds a layer: copy in, apply (re-validated through the rollback generation), persist. A
     * cancel during the copy discards the add, and the store removes the unreferenced copy. A
     * commit does NOT bump the generation (E2): an add resuming after a commit is applied.
     */
    suspend fun addLayer(imageUri: Uri, rollbackGenAtStart: Long): ImageResult {
        val internalUri = imageStore.copyIn(imageUri)
        if (internalUri == null) {
            TimberWrapper.silentError("Failed to copy layer image to internal storage")
            return ImageResult.CopyFailed
        }
        val internalUriString = internalUri.toString()
        val effect = session.addLayer(internalUriString, rollbackGenAtStart)
        if (effect == null) {
            // Rolled back during the copy: nothing persisted references the new copy (3a-2c).
            imageStore.deleteUnreferenced(listOf(internalUriString))
            return ImageResult.Discarded
        }
        persist(effect)
        return ImageResult.Applied
    }

    // ---- remove wallpaper ----

    /**
     * "Remove wallpaper": state first, files second, only against an empty persisted state
     * (3a-2d), under the composite lock and then [persistLock] (3a-3b) — a save still pending from
     * a session just committed lands BEFORE the clear. Returns whether the removal took effect;
     * if not, the wallpaper stays as it is on disk.
     *
     * **Refused while an edit session is open (3b-3)** — checked first, before any lock: the
     * session and its snapshot still reference the files, emissions are ignored during it (E4),
     * and its commit or cancel would write those references back after the files were deleted —
     * a dangling reference. Not "cancel the session, then remove": that would silently drop the
     * user's edit; not a session change either (no "remove everything" session operation for a
     * path no UI offers). Neither app's UI reaches this today; the rule makes the code guarantee
     * it. The caller maps the `false` onto its "couldn't remove" message.
     *
     * Checked TWICE (3b-3b-b): the check here is only the fast path that saves the wait. The
     * deciding one sits inside [persistLock], right before the clear — while this call waits for a
     * save still running from a just-committed session, a new session can open on Main
     * (`enter` is synchronous and takes no lock), and only the check under the lock sees it.
     */
    suspend fun clear(): Boolean {
        if (session.isEditMode.value) return false
        return clearUnderLocks()
    }

    private suspend fun clearUnderLocks(): Boolean = composite.exclusive {
        // Lock order: the composite lock (this block), then persistLock — never the reverse.
        val removalTookEffect = persistLock.withLock {
            // The deciding check (see clear()): a session opened while this call waited for the
            // lock refuses the removal — nothing cleared, nothing deleted.
            if (session.isEditMode.value) return@exclusive false
            persistence.clear()
            imageStore.deleteAllIfNothingPersisted()
        }
        if (!removalTookEffect) return@exclusive false
        // Drop the composite and its luminance: nothing displays it after a clear.
        composite.invalidate(dropLuminance = true)
        // Optimistic NONE: closes the window in which a warm resuming right after this lock
        // releases would still read the pre-clear state (its key-gated put then fails on NONE).
        session.onPersistedState(WallpaperState.NONE)
        true
    }

    // ---- session end ----

    /**
     * Commit: the session's removed files go to the store after the session's saves; leave the
     * session (refill + re-sync). [onImageChanged] fires when the image content changed during
     * the session (Kolibri's deferred scrim-reset offer).
     */
    fun commit(onImageChanged: () -> Unit) {
        val end = session.commit()
        if (end.imageChanged) onImageChanged()
        if (end.deleteCandidates.isNotEmpty()) {
            launch("Error committing wallpaper edit") {
                persistLock.withLock { imageStore.deleteUnreferenced(end.deleteCandidates) }
            }
        }
        leave(end.finalState)
    }

    /** Cancel: the session restored its snapshot synchronously; persist it, then delete session-added files. */
    fun cancel() {
        val end = session.cancel()
        if (end.persist != null || end.deleteCandidates.isNotEmpty()) {
            launch("Error canceling wallpaper edit") {
                persistLock.withLock {
                    end.persist?.let { persistence.save(it) }
                    imageStore.deleteUnreferenced(end.deleteCandidates)
                }
            }
        }
        leave(end.finalState)
    }

    private fun leave(finalState: WallpaperState) {
        refill(finalState)
        resyncWithPersisted()
    }

    /**
     * After a session end (3a-3, E4): apply the latest PERSISTED state, read after every write
     * issued so far ([persistLock]). Always ends the re-sync, also when the read fails — a
     * pending re-sync must never freeze the display.
     */
    private fun resyncWithPersisted() = launch("Error re-syncing the wallpaper after an edit") {
        var persisted: WallpaperState? = null
        try {
            persistLock.withLock { persisted = persistence.observe().firstOrNull() }
        } finally {
            val latest = persisted
            if (session.resync(latest) && latest != null) refill(latest)
        }
    }
}

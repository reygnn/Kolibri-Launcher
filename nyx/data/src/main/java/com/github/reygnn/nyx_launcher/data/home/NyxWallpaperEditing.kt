package com.github.reygnn.nyx_launcher.data.home

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import com.github.reygnn.launcher.core.ApplicationScope
import com.github.reygnn.launcher.core.MainDispatcher
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.feature.wallpaper.WallpaperComposite
import com.github.reygnn.launcher.feature.wallpaper.WallpaperEditSession
import com.github.reygnn.launcher.feature.wallpaper.WallpaperImageStore
import com.github.reygnn.launcher.feature.wallpaper.WallpaperOperations
import com.github.reygnn.launcher.feature.wallpaper.WallpaperPersistence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nyx's one wallpaper edit session (SPEC_NYX_REWRITE 3b-3, M1): exactly one shared
 * [WallpaperEditSession] and one [WallpaperOperations], process-wide, so the edit coordinator in
 * `MainActivity` and the image setter behind Settings and the customization sheet drive the SAME
 * session — one truth, one persist lock.
 *
 * Threads: the session is main-confined. Everything this component launches runs on the
 * [mainDispatcher] inside the existing [appScope] (whose own dispatcher is Default) — never on the
 * scope's default dispatcher.
 *
 * Lifetime: the session stays bound to the editing host. [onHostDestroyed] cancels an open session
 * when `MainActivity` ends — like leaving the editor without saving — so no orphaned session keeps
 * the GC locked and the display frozen.
 *
 * The display composite: the injected shared [WallpaperComposite] — Nyx binds
 * `WallpaperComposite.None` (3b-6d) until O7-K decides otherwise; the wiring is the one of 3b-6, so
 * binding `CachedWallpaperComposite` is the one change. Its key would use the display size from the
 * app context's resources, which follow configuration changes (as in Kolibri).
 */
@Singleton
class NyxWallpaperEditing @Inject constructor(
    repository: WallpaperRepository,
    imageStore: WallpaperImageStore,
    composite: WallpaperComposite,
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope,
    @param:MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) {
    val session = WallpaperEditSession()

    private val persistence = object : WallpaperPersistence {
        override suspend fun save(state: WallpaperState) = repository.saveWallpaperState(state)
        override suspend fun clear() = repository.clearWallpaper()
        override fun observe(): Flow<WallpaperState> = repository.wallpaperState
    }

    private val compositeHost = object : WallpaperComposite.Host {
        override fun currentState(): WallpaperState = session.state.value
        override fun isEditing(): Boolean = session.isEditMode.value
        override fun displaySize(): Pair<Int, Int> =
            context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        override fun launch(block: suspend () -> Unit) = launchOnMain("Error refilling the wallpaper composite", block)
    }

    val operations = WallpaperOperations(
        session = session,
        persistence = persistence,
        imageStore = imageStore,
        composite = composite,
        compositeHost = compositeHost,
        launch = ::launchOnMain,
    )

    private var started = false

    /** Observes the persisted state and runs the orphan GC — once per process, from any host. Main only. */
    fun start() {
        if (started) return
        started = true
        operations.start()
    }

    /**
     * The home became visible again (`MainActivity.onStart`, 3b-6): re-warm the composite for what
     * is shown — a no-op on a cache hit and while editing; after an invalidation under memory
     * pressure it warms anew, so home does not stay on the slow per-layer path. Main only.
     */
    fun onHostStarted() = operations.refillCurrent()

    /**
     * The editing host (`MainActivity`) ended: an open session is cancelled — snapshot back,
     * re-sync. Main only. NOT when the host is only being recreated for a configuration change
     * Nyx does not handle itself (locale, font scale — audit A3): the new activity restores the
     * editor from [session]'s `isEditMode`, so the user's edit survives the recreation.
     */
    fun onHostDestroyed(changingConfigurations: Boolean = false) {
        if (changingConfigurations) return
        if (session.isEditMode.value) operations.cancel()
    }

    /** Runs [block] on Main in the app scope; a failure is logged with [errorMessage], never thrown. */
    fun launchOnMain(errorMessage: String, block: suspend () -> Unit) {
        appScope.launch(mainDispatcher) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Catch kept (Expected error, four-category frame): a failed wallpaper write is
                // logged like Nyx's launchSafe did; OOM extends Error → Throwable.
                TimberWrapper.silentError(e, errorMessage)
            }
        }
    }
}

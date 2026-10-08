package com.github.reygnn.launcher.feature.wallpaper

import android.graphics.Bitmap
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperBitmapLuminanceImpl
import com.github.reygnn.launcher.common.ui.LaunchTrace
import com.github.reygnn.launcher.common.ui.wallpaper.DecodedWallpaperBitmap
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperCompositeCache
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperFlattener
import com.github.reygnn.launcher.core.CompositeLuminanceSignal
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.wallpaper.WallpaperCompositeKey
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The display composite of a multi-layer wallpaper (SPEC_NYX_REWRITE 3a-8, E3): one flattened,
 * resolution-keyed HARDWARE bitmap the view draws instead of decoding every layer. The write side
 * (the caller's state changes) and the read side (the render) share one cache, so the
 * implementation is a singleton.
 */
interface WallpaperComposite {

    /**
     * What the composite needs from its caller — Kolibri's delegate, Nyx's `NyxWallpaperEditing`
     * (since 3b-3) — as plain functions, no session (J2). **Every member is called on the main thread**, the
     * implementation guarantees it (its checks run after returning from the I/O context), so a
     * host may read main-confined state such as a `WallpaperEditSession`.
     */
    interface Host {
        /** The state the launcher shows now — the "is it still current" reference. */
        fun currentState(): WallpaperState

        /** No composite while editing: the editor works on the real layers. */
        fun isEditing(): Boolean

        /** Current display size in px (width, height): the composite key's resolution. */
        fun displaySize(): Pair<Int, Int>

        /** Runs the warm in the host's scope with its error handling. */
        fun launch(block: suspend () -> Unit)

        /** A composite was filled (Kolibri shows a debug toast behind its own build flag). */
        fun onCompositeFilled(widthPx: Int, heightPx: Int) {}
    }

    /**
     * Makes sure a composite exists for [target] (write side): drops one that no longer fits,
     * warms a new one off the main thread — single-flighted — and publishes it only if the
     * [Host.currentState] still has the same key afterwards. Call on the main thread.
     */
    fun refill(target: WallpaperState, host: Host)

    /** Read side: the `composite://` key for [state] at [widthPx]×[heightPx] if it is cached, else null. */
    fun cachedKeyFor(state: WallpaperState, widthPx: Int, heightPx: Int): String?

    /** Read side: the cached composite for [key], or null (not warm yet). */
    fun cachedBitmap(key: String): DecodedWallpaperBitmap?

    /** Drops the cached composite; with [dropLuminance] also the composite's luminance signal. */
    fun invalidate(dropLuminance: Boolean)

    /**
     * Runs [block] under the composite's lock, so it cannot interleave a warm's cache put (used by
     * "remove wallpaper"). **Lock order: this lock BEFORE the caller's persist lock, never the
     * reverse** — a reversed order on any path would deadlock. kotlinx `Mutex` is not reentrant:
     * nothing inside [block] may call [exclusive] again.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T

    /**
     * For hosts without a composite and for tests (3b-3, M2). Nyx binds it (3b-6d, its long-lived
     * activity view would pay the flatten without showing the result) until O7-K decides otherwise:
     * [refill] and [invalidate] do nothing, the read side finds nothing, so no flatten runs in the
     * background for a result nobody draws. [exclusive] is a REAL lock, so the lock order of 3a-3b
     * (this lock before the persist lock) holds as well. One instance per [WallpaperOperations].
     */
    class None : WallpaperComposite {
        private val lock = Mutex()

        override fun refill(target: WallpaperState, host: Host) = Unit

        override fun cachedKeyFor(state: WallpaperState, widthPx: Int, heightPx: Int): String? = null

        override fun cachedBitmap(key: String): DecodedWallpaperBitmap? = null

        override fun invalidate(dropLuminance: Boolean) = Unit

        override suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }
    }
}

/**
 * [WallpaperComposite] on the shared [WallpaperCompositeCache] — moved from Kolibri's
 * `WallpaperDelegate` in 3a-8, behaviour unchanged. The `wallpaper_warm` and `wallpaper_flatten`
 * trace sections keep their names and EXACT boundaries (F4: the before/after measurement compares
 * them).
 */
@Singleton
class CachedWallpaperComposite @Inject constructor(
    private val cache: WallpaperCompositeCache,
    private val flattener: WallpaperFlattener,
    private val luminance: WallpaperBitmapLuminanceImpl,
    private val luminanceSignal: CompositeLuminanceSignal,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : WallpaperComposite {

    /** Single-flight: one warm at a time; a change meanwhile is picked up when it ends. */
    private var refillInProgress = false

    /** Serializes a warm's cache put with [exclusive] (the clear). */
    private val regenLock = Mutex()

    override fun refill(target: WallpaperState, host: WallpaperComposite.Host) {
        if (refillInProgress) return
        if (host.isEditing()) return
        val key = keyOrNull(target, host)
        if (key == null) {
            // No composite for the target — drop the old one only if the CURRENT state needs none
            // either (a stale target must not throw away the composite of what is on screen).
            if (keyOrNull(host.currentState(), host) == null) {
                cache.invalidate()
                luminanceSignal.emit(null)
            }
            return
        }
        cache.invalidateIfNotKey(key)
        if (cache.get(key) != null) {
            return
        }
        refillInProgress = true
        host.launch {
            try {
                warm(target, key, host)
            } finally {
                refillInProgress = false
                // A change that landed during the warm was skipped by the single-flight guard;
                // pick it up now (on Main — the host's launch runs there).
                val current = host.currentState()
                if (!host.isEditing()) {
                    val currentKey = keyOrNull(current, host)
                    if (currentKey != null && currentKey != key && cache.get(currentKey) == null) {
                        refill(current, host)
                    }
                }
            }
        }
    }

    override fun cachedKeyFor(state: WallpaperState, widthPx: Int, heightPx: Int): String? {
        val key = WallpaperCompositeKey.of(state, widthPx, heightPx)
        return if (cache.get(key) != null) key else null
    }

    override fun cachedBitmap(key: String): DecodedWallpaperBitmap? = cache.get(key)

    override fun invalidate(dropLuminance: Boolean) {
        cache.invalidate()
        if (dropLuminance) luminanceSignal.emit(null)
    }

    override suspend fun <T> exclusive(block: suspend () -> T): T = regenLock.withLock { block() }

    /** Only multi-layer wallpapers get a composite; a lone image decodes live (§25 P4). */
    private fun keyOrNull(state: WallpaperState, host: WallpaperComposite.Host): String? =
        if (state.hasWallpaper && state.layerCount >= 2) keyOf(state, host) else null

    /** The composite key at the CURRENT display size (read on Main through the host). */
    private fun keyOf(state: WallpaperState, host: WallpaperComposite.Host): String {
        val (width, height) = host.displaySize()
        return WallpaperCompositeKey.of(state, width, height)
    }

    /**
     * Flatten [state] (SOFTWARE) -> copy to HARDWARE -> key-gated cache put -> recycle the software
     * temp. A partial flatten returns null (all-or-nothing) and is not cached. Serialized with
     * [exclusive] so a clear cannot interleave the cache put.
     */
    private suspend fun warm(state: WallpaperState, key: String, host: WallpaperComposite.Host) = regenLock.withLock {
        // Async trace sections (measured by :macrobenchmark) — the warm suspends / hops threads,
        // so sync sections would mis-report. Cookies are constants: single-flight guarantees no
        // two warms overlap, and warm/flatten nest by distinct name+cookie. try/finally keeps
        // them balanced across the early returns. Boundaries unchanged by the 3a-8 move (F4).
        LaunchTrace.beginAsync(LaunchTrace.Names.WALLPAPER_WARM, WARM_TRACE_COOKIE)
        try {
            val (width, height) = host.displaySize()
            LaunchTrace.beginAsync(LaunchTrace.Names.WALLPAPER_FLATTEN, FLATTEN_TRACE_COOKIE)
            val software = try {
                flattener.flatten(state, width, height)
            } finally {
                LaunchTrace.endAsync(LaunchTrace.Names.WALLPAPER_FLATTEN, FLATTEN_TRACE_COOKIE)
            }
            if (software == null) {
                dropLuminanceIfCurrent(key, host)
                return@withLock
            }
            val hardware: Bitmap?
            val lum: Float?
            try {
                val out = withContext(ioDispatcher) {
                    val computed = luminance.computeFromBitmap(software)
                    val hw = software.copy(Bitmap.Config.HARDWARE, /* isMutable = */ false)
                    hw to computed
                }
                hardware = out.first
                lum = out.second
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Catch kept (Expected error, four-category frame): a failed HARDWARE copy or
                // luminance read leaves the per-layer path; OOM extends Error → Throwable.
                TimberWrapper.silentError(e, "Composite HARDWARE copy / luminance failed")
                dropLuminanceIfCurrent(key, host)
                return@withLock
            } finally {
                software.recycle()
            }
            if (hardware == null) {
                dropLuminanceIfCurrent(key, host)
                return@withLock
            }
            // Back on Main: publish only if what is on screen still has this key.
            val current = host.currentState()
            if (current.layerCount >= 2 && keyOf(current, host) == key) {
                cache.put(
                    key,
                    DecodedWallpaperBitmap(
                        bitmap = hardware,
                        sampleSize = 1,
                        originalWidth = width,
                        originalHeight = height,
                    ),
                )
                luminanceSignal.emit(lum)
                host.onCompositeFilled(width, height)
            } else {
                // Superseded (audit A12): this HARDWARE bitmap was never published, so nothing
                // draws or caches it — release its graphics memory now instead of waiting for the
                // GC. The cache's never-recycle invariant concerns PUBLISHED bitmaps only.
                hardware.recycle()
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
    private fun dropLuminanceIfCurrent(key: String, host: WallpaperComposite.Host) {
        val current = host.currentState()
        if (current.layerCount >= 2 && keyOf(current, host) == key) {
            luminanceSignal.emit(null)
        }
    }
}

private const val WARM_TRACE_COOKIE = 0x7A31
private const val FLATTEN_TRACE_COOKIE = 0x7A32

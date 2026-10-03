package com.github.reygnn.kolibri_launcher.ui.main

import android.view.View
import androidx.core.view.doOnPreDraw
import com.github.reygnn.launcher.common.ui.LaunchTrace

/**
 * The two wallpaper paint spans for the 3a-8 before/after measurement (SPEC_NYX_REWRITE F4),
 * after the pattern of `favorites_first_paint`: measurement points only, no behaviour.
 *
 *  - [LaunchTrace.Names.WALLPAPER_FIRST_PAINT]: opened in `MainActivity.onCreate` once per
 *    process, closed in the pre-draw of the first frame after the view applied a state WITH a
 *    wallpaper.
 *  - [LaunchTrace.Names.WALLPAPER_CHANGE_PAINT]: opened on the editor's save tap, closed in the
 *    pre-draw after the next applied wallpaper state. A commit whose view needs no rebuild ends
 *    it at the following apply — for a multi-layer wallpaper the composite attach.
 *
 * Main-thread only (begin/end and the pre-draw callbacks all run there), so the state needs no
 * synchronisation. A span whose frame never comes stays open on purpose: an unclosed async slice
 * does not match in the benchmark, which is the right signal. Trace calls are near-free with no
 * tracer attached, so this is present in release — what lets the benchmark measure the ship build.
 */
internal object WallpaperPaintTrace {

    private enum class Span { IDLE, STARTED, AWAITING_DRAW, DONE }

    private var firstPaint = Span.IDLE
    private var changePaint = Span.IDLE

    /** Cold start; a second call in the same process (Activity recreation) measures nothing. */
    fun beginFirstPaint() {
        if (firstPaint != Span.IDLE) return
        firstPaint = Span.STARTED
        LaunchTrace.beginAsync(LaunchTrace.Names.WALLPAPER_FIRST_PAINT, FIRST_PAINT_COOKIE)
    }

    /** The editor's save tap. A span still open from an earlier save keeps running (no restart). */
    fun beginChangePaint() {
        if (changePaint == Span.STARTED || changePaint == Span.AWAITING_DRAW) return
        changePaint = Span.STARTED
        LaunchTrace.beginAsync(LaunchTrace.Names.WALLPAPER_CHANGE_PAINT, CHANGE_PAINT_COOKIE)
    }

    /** The wallpaper view applied a state; closes the open spans one frame later. */
    fun onWallpaperApplied(view: View, hasWallpaper: Boolean) {
        if (!hasWallpaper) return
        if (firstPaint == Span.STARTED) {
            firstPaint = Span.AWAITING_DRAW
            view.doOnPreDraw {
                if (firstPaint == Span.AWAITING_DRAW) {
                    firstPaint = Span.DONE
                    LaunchTrace.endAsync(LaunchTrace.Names.WALLPAPER_FIRST_PAINT, FIRST_PAINT_COOKIE)
                }
            }
        }
        if (changePaint == Span.STARTED) {
            changePaint = Span.AWAITING_DRAW
            view.doOnPreDraw {
                if (changePaint == Span.AWAITING_DRAW) {
                    changePaint = Span.IDLE
                    LaunchTrace.endAsync(LaunchTrace.Names.WALLPAPER_CHANGE_PAINT, CHANGE_PAINT_COOKIE)
                }
            }
        }
    }

    /** Async begin/end cookies (matched by `Trace`; arbitrary but stable per span). */
    private const val FIRST_PAINT_COOKIE = 0x3A8F
    private const val CHANGE_PAINT_COOKIE = 0x3A8C
}

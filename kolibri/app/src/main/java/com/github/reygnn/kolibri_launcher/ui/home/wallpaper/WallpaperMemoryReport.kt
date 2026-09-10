package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import java.util.Locale

/**
 * Pure, JVM-testable model of the retained-bitmap memory footprint of the
 * currently displayed wallpaper (Rule 10 — the decision/formatting logic lives
 * outside the Android-runtime view). Fed by [com.github.reygnn.kolibri_launcher
 * .ui.home.ZoomableImageView.collectWallpaperMemoryRows] and rendered by the
 * info dialog in `WallpaperEditController`.
 *
 * The point of surfacing the SOURCE resolution alongside the decoded size: a
 * layer that looks small in the collage can still be a full-resolution photo —
 * the source pixels are the memory cost driver, and this is where the user sees
 * which layer is the heavy one (see WALLPAPER_AGGREGATE_MEM_SPEC §1).
 */
data class WallpaperMemoryRow(
    /** 0-based layer position (single-layer wallpaper reports index 0). */
    val index: Int,
    /** Width of the decoded bitmap actually held in memory. */
    val decodedWidth: Int,
    /** Height of the decoded bitmap actually held in memory. */
    val decodedHeight: Int,
    /** `inSampleSize` used for the decode — 1 = full resolution. */
    val sampleSize: Int,
    /** Full-resolution source width (0 = unknown). */
    val originalWidth: Int,
    /** Full-resolution source height (0 = unknown). */
    val originalHeight: Int,
    /** Retained backing allocation of the decoded bitmap in bytes. */
    val bytes: Long,
    /**
     * `Bitmap.Config` name of the decoded bitmap, e.g. `HARDWARE` (pixels in
     * graphics memory, off the Java heap) or `ARGB_8888` (on-heap fallback);
     * `"?"` when unknown. Kept as the raw config NAME (a String) on purpose: this
     * dialog is a diagnostic that wants to show EXACTLY which config a bitmap
     * decoded to (HARDWARE vs ARGB_8888 vs RGB_565 …), and a String keeps the
     * model Android-free / JVM-testable without re-spelling every `Bitmap.Config`
     * value in a local enum. A typed enum would be equally testable but would
     * either duplicate those names or collapse away the exact one — not worth it
     * for a diagnostic field.
     */
    val config: String,
) {
    /**
     * True when the bitmap was decoded BELOW its source resolution, so showing
     * the original dimensions adds information. Guards against a bogus source
     * (unknown dims reported as 0) by requiring the source to actually exceed
     * the decoded size.
     */
    val isDownsampled: Boolean
        get() = sampleSize > 1 &&
            (originalWidth > decodedWidth || originalHeight > decodedHeight)
}

/** A full report: the per-layer rows plus their summed retained bytes. */
data class WallpaperMemoryReport(
    val rows: List<WallpaperMemoryRow>,
    val totalBytes: Long,
) {
    companion object {
        fun of(rows: List<WallpaperMemoryRow>): WallpaperMemoryReport =
            WallpaperMemoryReport(rows, rows.sumOf { it.bytes })
    }
}

/**
 * Formats a byte count as e.g. `12.4 MB` (one decimal). Locale-aware so a German
 * device renders `12,4 MB` — [locale] defaults to the platform locale in
 * production and is pinned in tests for determinism. `MB` is a universal unit,
 * not translatable prose.
 */
fun formatMegabytes(bytes: Long, locale: Locale = Locale.getDefault()): String =
    String.format(locale, "%.1f MB", bytes / 1_048_576.0)

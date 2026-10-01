package com.github.reygnn.nyx_launcher.home.model

/**
 * What a backup contains, read before importing it (2b-3a) — the restore dialog offers only
 * the parts that are there. Read like Kolibri's preview: the whole container through the
 * engine, staged blobs discarded; a manifest-only preview for both apps is open question O4.
 */
data class BackupPreview(
    val appVersion: String,
    val timestamp: Long,
    /** Home grid items plus dock items; null when the backup has no layout. */
    val homeItemCount: Int?,
    val drawerFolderCount: Int,
    /** Null when the backup has no hidden-apps field (the current set would stay). */
    val hiddenAppCount: Int?,
    val hasSettings: Boolean,
    val wallpaperLayerCount: Int,
)

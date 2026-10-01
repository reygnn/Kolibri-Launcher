package com.github.reygnn.nyx_launcher.home.model

/**
 * Outcome of importing a backup (2b-3a). Same shape as Kolibri's: each refusal the shared
 * engine can report has its own result, so the UI can say what happened. That both apps
 * map the engine's outcomes alike is pinned by the backup contracts; one shared type in
 * `:feature-backup` is open question O4.
 */
sealed interface ImportResult {
    /** Imported. [droppedWallpaperLayers]: layers left out because their image blob was rejected or missing. */
    data class Success(val droppedWallpaperLayers: Int = 0) : ImportResult

    /** Not a readable backup, or its Nyx section can't be decoded. */
    data object InvalidFormat : ImportResult

    /** A container format newer than this app reads. */
    data class UnsupportedVersion(val version: String) : ImportResult

    /** A backup made by another app ([appId]); nothing is imported. */
    data class ForeignBackup(val appId: String) : ImportResult

    /** A pre-container archive; Nyx reads only the container format (E5a). */
    data object OutdatedBackup : ImportResult

    /** Anything else (too large, I/O); [message] is for logs, the UI shows its own text. */
    data class Error(val message: String) : ImportResult
}

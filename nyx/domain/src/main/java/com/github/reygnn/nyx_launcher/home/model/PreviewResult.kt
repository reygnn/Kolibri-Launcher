package com.github.reygnn.nyx_launcher.home.model

/**
 * Outcome of previewing a backup before importing it (2b-3b, same form as Kolibri's 2a-7b).
 * A refused file carries the reason, mapped exactly as its import would be, so the settings
 * show the matching message at once — no options dialog.
 */
sealed interface PreviewResult {
    /** A readable Nyx backup; the options dialog offers what [preview] contains. */
    data class Readable(val preview: BackupPreview) : PreviewResult

    /**
     * Not importable. [result] is the import's own refusal for the same file, never
     * [ImportResult.Success] (the backup contracts pin it). A failure while reading is
     * [ImportResult.Error], never a missing result.
     */
    data class Refused(val result: ImportResult) : PreviewResult
}

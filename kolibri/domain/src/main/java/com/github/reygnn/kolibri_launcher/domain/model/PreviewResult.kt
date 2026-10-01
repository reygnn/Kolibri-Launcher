package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Outcome of previewing a backup before importing it (SPEC_NYX_REWRITE 2a-7b). A refused
 * file carries the reason, mapped exactly as its import would be, so the backup screen shows
 * the matching message at once — no options dialog, no timeout.
 */
sealed interface PreviewResult {
    /** A readable Kolibri backup; the options dialog offers what [preview] contains. */
    data class Readable(val preview: BackupPreview) : PreviewResult

    /**
     * Not importable. [result] is the import's own refusal for the same file: never
     * [ImportResult.Success] or [ImportResult.LimitExceeded] (the backup contracts pin both).
     * A failure while reading is [ImportResult.Error], never a missing result.
     */
    data class Refused(val result: ImportResult) : PreviewResult
}
